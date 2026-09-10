package me.rerere.rikkahub.data.ai.execution

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationRunControl
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.capability.SubjectType
import me.rerere.rikkahub.data.ai.tools.LegacyToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.StartableTool
import me.rerere.rikkahub.data.ai.tools.ToolCancelReason
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.ai.tools.ToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.ToolResult
import me.rerere.rikkahub.data.ai.tools.ToolTerminationState
import me.rerere.rikkahub.data.execution.RequestedTerminalOutcome
import kotlin.time.Duration.Companion.milliseconds

interface ToolRuntime {
    suspend fun assess(request: ToolAssessmentRequest): ToolAssessment
    suspend fun execute(request: ToolExecutionPlanRequest): ToolExecutionPlanResult
}

data class ToolAssessmentRequest(
    val toolName: String,
    val args: JsonObject,
    val context: ToolExecutionContext,
)

data class ToolAssessment(
    val policy: ToolExecutionPolicy,
    val securityDescriptor: ToolSecurityDescriptor?,
    val accepted: Boolean,
    val errorCode: String? = null,
)

data class ToolExecutionPlanRequest(
    val toolCallId: String,
    val toolName: String,
    /** Exact host-owned input-schema SHA-256 frozen with the exposed tool surface. */
    val toolSchemaFingerprint: String? = null,
    val args: JsonElement,
    val executionContext: ToolExecutionContext?,
    val startableTool: StartableTool?,
    val legacyExecute: suspend (JsonElement) -> ToolResult,
    val runControl: GenerationRunControl?,
    val wallClockBudgetMs: Long,
    val preExecutionGate: suspend () -> ToolPreExecutionDecision = {
        ToolPreExecutionDecision.Allow
    },
    /** Optional process-local observer. The caller owns the true queue-entry callback. */
    val timingHook: ToolExecutionTimingHook? = null,
)

enum class ToolExecutionTimingOutcome {
    COMPLETED,
    REJECTED,
    TIMED_OUT,
    CANCELLED,
    FAILED,
}

/**
 * Non-suspending timing seams for one bound tool invocation. Implementations must not block.
 * Runtime-owned callbacks are exception-isolated; [onQueued] is exposed for the batch scheduler,
 * because calling it inside [ToolRuntime.execute] would mislabel preflight as queue time.
 */
interface ToolExecutionTimingHook {
    fun onQueued() = Unit
    fun onPreflightStarted() = Unit
    fun onPreflightFinished() = Unit
    fun onExecutionStarted() = Unit
    fun onRawResultReady() = Unit
    /** The COMPLETED lifecycle persistence barrier returned; persistence may be degraded. */
    fun onCompletionLedgerFinished() = Unit
    fun onTerminal(outcome: ToolExecutionTimingOutcome) = Unit
}

/** Safe queue-entry seam for callers that schedule tools before [ToolRuntime.execute]. */
fun ToolExecutionTimingHook?.notifyQueuedSafely() {
    val hook = this ?: return
    try {
        hook.onQueued()
    } catch (_: Throwable) {
        // Timing is an optional sidecar and must never alter tool scheduling.
    }
}

private class ToolExecutionTimingSession(
    private val hook: ToolExecutionTimingHook,
) {
    private var terminalReported = false

    fun preflightStarted() = safeCall { onPreflightStarted() }
    fun preflightFinished() = safeCall { onPreflightFinished() }
    fun executionStarted() = safeCall { onExecutionStarted() }
    fun rawResultReady() = safeCall { onRawResultReady() }
    fun completionLedgerFinished() = safeCall { onCompletionLedgerFinished() }

    fun terminal(outcome: ToolExecutionTimingOutcome) {
        if (terminalReported) return
        terminalReported = true
        safeCall { onTerminal(outcome) }
    }

    private inline fun safeCall(block: ToolExecutionTimingHook.() -> Unit) {
        try {
            hook.block()
        } catch (_: Throwable) {
            // Observability callbacks are never allowed to change tool behavior.
        }
    }
}

sealed interface ToolPreExecutionDecision {
    data object Allow : ToolPreExecutionDecision
    data class Deny(
        val errorCode: String,
        val reason: String,
    ) : ToolPreExecutionDecision
}

private fun ToolExecutionPlanResult.timingOutcome(): ToolExecutionTimingOutcome = when (this) {
    is ToolExecutionPlanResult.Completed -> ToolExecutionTimingOutcome.COMPLETED
    is ToolExecutionPlanResult.Rejected -> ToolExecutionTimingOutcome.REJECTED
    is ToolExecutionPlanResult.TimedOut -> ToolExecutionTimingOutcome.TIMED_OUT
}

sealed interface ToolExecutionPlanResult {
    val output: ToolResult

    data class Completed(
        override val output: ToolResult,
        val policy: ToolExecutionPolicy,
        val executionId: String,
        val trackingState: ToolTrackingState = ToolTrackingState.TRACKED,
    ) : ToolExecutionPlanResult

    data class Rejected(
        val errorCode: String,
        val detail: String,
        override val output: ToolResult = errorResult(errorCode, detail),
    ) : ToolExecutionPlanResult

    data class TimedOut(
        val policy: ToolExecutionPolicy,
        val executionId: String?,
        val trackingState: ToolTrackingState = ToolTrackingState.TRACKED,
        override val output: ToolResult = errorResult(
            "tool_cancelled_wall_clock",
            "Tool execution exceeded the shared turn budget.",
        ),
    ) : ToolExecutionPlanResult

    companion object {
        private fun errorResult(code: String, detail: String): ToolResult = listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("error", JsonPrimitive(code))
                    put("detail", JsonPrimitive(detail.take(500)))
                }.toString()
            )
        )
    }
}

data class RedactedToolCallContext(
    /** Model-provided call id, used only to correlate a host-side execution record. */
    val toolCallId: String = "",
    val toolName: String,
    /** Host-only immutable schema identity; PluginHookBridge never serializes it. */
    val toolSchemaFingerprint: String? = null,
    val effects: Set<ToolEffect>,
    val resourceNamespaces: Set<String>,
    val origin: ToolCallOrigin,
    val hasConversationOwner: Boolean,
    /** Opaque ownership keys used by trusted host routing; never serialized to plugin code. */
    val assistantId: String,
    val conversationId: String,
    val runId: String,
    /** Host-only durable command identity; PluginHookBridge never serializes this field. */
    val commandId: String? = null,
    /** Principal metadata is host-only; PluginHookBridge intentionally does not serialize it. */
    val subjectId: String = "",
    val subjectType: SubjectType? = null,
    /** Legacy paths can cancel local waiting only and must never be reported as managed. */
    val legacyExecution: Boolean = false,
)

sealed interface ToolHookDecision {
    data object Proceed : ToolHookDecision
    data class Block(val reason: String) : ToolHookDecision
}

interface ToolCallInterceptor {
    suspend fun intercept(context: RedactedToolCallContext): ToolHookDecision
}

fun interface ToolLifecycleObserver {
    suspend fun onEvent(event: RedactedToolLifecycleEvent)
}

data class RedactedToolLifecycleEvent(
    val phase: Phase,
    val context: RedactedToolCallContext,
    val executionId: String? = null,
    val terminationState: ToolTerminationState? = null,
    val requestedTerminalOutcome: RequestedTerminalOutcome = RequestedTerminalOutcome.NONE,
    /** Stable, non-secret runtime reason (never tool input, output, or exception text). */
    val detail: String? = null,
) {
    enum class Phase {
        STARTING,
        RUNNING,
        CANCEL_REQUESTED,
        TERMINATING,
        COMPLETED,
        TIMED_OUT,
        CANCELLED,
        FAILED,
    }
}

class DefaultToolRuntime(
    private val policyResolver: ToolExecutionPolicyResolver,
    private val securityDescriptorResolver: ToolSecurityDescriptorResolver =
        DefaultToolSecurityDescriptorResolver(),
    private val criticalSink: CriticalToolLifecycleSink = CriticalToolLifecycleSink { },
    private val trackingHealth: ExecutionTrackingHealth = ExecutionTrackingHealth(),
    private val interceptors: List<ToolCallInterceptor> = emptyList(),
    private val observers: List<ToolLifecycleObserver> = emptyList(),
    private val interceptorTimeoutMs: Long = 2_000L,
    private val observerTimeoutMs: Long = 500L,
    private val criticalRetryDelaysMs: LongArray = longArrayOf(0L, 100L, 500L),
) : ToolRuntime {
    private enum class LifecycleDispatch {
        TRACKED,
        UNTRACKED,
        BLOCKED,
    }

    private val globalMutex = Mutex()
    private val resourceMutexes = ConcurrentHashMap<ToolResourceKey, Mutex>()

    override suspend fun assess(request: ToolAssessmentRequest): ToolAssessment {
        val policy = policyResolver.resolve(request.toolName, request.args, request.context)
        val descriptor = securityDescriptorResolver.resolve(request.toolName, request.context)
        val explicitlyUntrustedPlugin = descriptor?.source == ToolDescriptorSource.PLUGIN &&
            policy.effects == setOf(ToolEffect.UNKNOWN) &&
            policy.concurrency == ToolConcurrency.GLOBAL_SERIAL
        return if (descriptor == null ||
            (ToolEffect.UNKNOWN in policy.effects && !explicitlyUntrustedPlugin)
        ) {
            ToolAssessment(
                policy = policy,
                securityDescriptor = descriptor,
                accepted = false,
                errorCode = "tool_security_descriptor_missing",
            )
        } else {
            ToolAssessment(
                policy = policy,
                securityDescriptor = descriptor,
                accepted = true,
            )
        }
    }

    override suspend fun execute(request: ToolExecutionPlanRequest): ToolExecutionPlanResult {
        require(request.wallClockBudgetMs >= 0L) { "wallClockBudgetMs cannot be negative" }
        val timing = request.timingHook?.let(::ToolExecutionTimingSession)
        timing?.preflightStarted()
        return try {
            executeObserved(request, timing).also { result ->
                timing?.terminal(result.timingOutcome())
            }
        } catch (cancelled: CancellationException) {
            timing?.terminal(ToolExecutionTimingOutcome.CANCELLED)
            throw cancelled
        } catch (failure: Throwable) {
            timing?.terminal(ToolExecutionTimingOutcome.FAILED)
            throw failure
        }
    }

    private suspend fun executeObserved(
        request: ToolExecutionPlanRequest,
        timing: ToolExecutionTimingSession?,
    ): ToolExecutionPlanResult {
        if (!isValidHostToolCallId(request.toolCallId)) {
            timing?.preflightFinished()
            return ToolExecutionPlanResult.Rejected(
                errorCode = "tool_call_identity_invalid",
                detail = "The host tool-call identity is empty, oversized, or unsafe.",
            )
        }
        if (
            request.toolSchemaFingerprint != null &&
            !request.toolSchemaFingerprint.matches(Regex("[0-9a-f]{64}"))
        ) {
            timing?.preflightFinished()
            return ToolExecutionPlanResult.Rejected(
                errorCode = "tool_schema_identity_invalid",
                detail = "The host tool-schema identity is invalid.",
            )
        }
        val suppliedContext = request.executionContext ?: run {
            timing?.preflightFinished()
            return ToolExecutionPlanResult.Rejected(
                errorCode = "tool_execution_context_missing",
                detail = "Tool execution requires assistant, conversation, run, and origin identity.",
            )
        }
        if (suppliedContext.toolCallId.isNotEmpty() &&
            suppliedContext.toolCallId != request.toolCallId
        ) {
            timing?.preflightFinished()
            return ToolExecutionPlanResult.Rejected(
                errorCode = "tool_call_identity_mismatch",
                detail = "The host tool-call identity changed before execution.",
            )
        }
        if (request.runControl != null && request.runControl.runId != suppliedContext.runId) {
            timing?.preflightFinished()
            return ToolExecutionPlanResult.Rejected(
                errorCode = "tool_run_identity_mismatch",
                detail = "The run-control identity does not own this tool execution context.",
            )
        }
        // The plan request is the host's canonical invocation identity. Bind it before policy,
        // lifecycle persistence or StartableTool side effects; never infer it from an execution ID.
        val context = suppliedContext.copy(toolCallId = request.toolCallId)
        val provisionalRedacted = RedactedToolCallContext(
            toolCallId = request.toolCallId,
            toolName = request.toolName,
            toolSchemaFingerprint = request.toolSchemaFingerprint,
            effects = setOf(ToolEffect.UNKNOWN),
            resourceNamespaces = emptySet(),
            origin = context.callOrigin,
            hasConversationOwner = true,
            assistantId = context.assistantId,
            conversationId = context.conversationId.toString(),
            runId = context.runId.toString(),
            commandId = context.commandId?.toString(),
            subjectId = context.capabilitySubject?.id.orEmpty(),
            subjectType = context.capabilitySubject?.type,
            legacyExecution = request.startableTool == null,
        )
        // ── TEMPORARY diagnostics (diag commit) — no-op unless a workflow action anchored
        // this exact tool-call id. Content-free: phase, workflow id, tool name, elapsed.
        val diagWorkflowId = WorkflowActionDiagnostics.workflowIdOf(context)
        val diagOrigin = context.callOrigin
        val diagHeadless = diagOrigin == ToolCallOrigin.TrustedWorkflow
        fun diagMark(phase: String) = diagWorkflowId?.let { id ->
            WorkflowActionDiagnostics.mark(
                phase, request.toolCallId, request.toolName, id, diagHeadless, diagOrigin.name,
            )
        }
        diagMark("before_dispatch_lifecycle_starting")
        when (val gate = request.preExecutionGate()) {
            ToolPreExecutionDecision.Allow -> Unit
            is ToolPreExecutionDecision.Deny -> {
                timing?.preflightFinished()
                notifyRejected(provisionalRedacted, gate.errorCode)
                return ToolExecutionPlanResult.Rejected(
                    errorCode = gate.errorCode,
                    detail = gate.reason,
                )
            }
        }
        val argsObject = request.args as? JsonObject ?: JsonObject(emptyMap())
        val assessment = assess(ToolAssessmentRequest(request.toolName, argsObject, context))
        if (!assessment.accepted) {
            timing?.preflightFinished()
            notifyRejected(provisionalRedacted, checkNotNull(assessment.errorCode))
            return ToolExecutionPlanResult.Rejected(
                errorCode = checkNotNull(assessment.errorCode),
                detail = "No runtime security descriptor exists for this tool.",
            )
        }

        val redacted = provisionalRedacted.copy(
            effects = assessment.policy.effects,
            resourceNamespaces = assessment.policy.resourceKeys.mapTo(linkedSetOf()) { it.namespace },
        )
        val effectivePolicy = if (request.startableTool == null) {
            assessment.policy.copy(
                cancellationCapability = ToolCancellationCapability.LOCAL_WAIT_ONLY,
            )
        } else {
            assessment.policy
        }
        val durableTrackingRequired = effectivePolicy.requiresDurableTracking(
            hasManagedStartable = request.startableTool != null,
        )
        var trackingState = ToolTrackingState.TRACKED
        if (request.wallClockBudgetMs == 0L) {
            timing?.preflightFinished()
            when (dispatchLifecycle(
                RedactedToolLifecycleEvent(
                    phase = RedactedToolLifecycleEvent.Phase.STARTING,
                    context = redacted,
                ),
                durableTrackingRequired = durableTrackingRequired,
                beforeSideEffect = true,
            )) {
                LifecycleDispatch.BLOCKED -> return trackingUnavailable()
                LifecycleDispatch.UNTRACKED -> trackingState = ToolTrackingState.UNTRACKED
                LifecycleDispatch.TRACKED -> Unit
            }
            if (dispatchLifecycle(
                RedactedToolLifecycleEvent(
                    phase = RedactedToolLifecycleEvent.Phase.TIMED_OUT,
                    context = redacted,
                    detail = "wall_clock_timeout_before_start",
                    requestedTerminalOutcome = RequestedTerminalOutcome.TIMED_OUT,
                ),
                durableTrackingRequired = durableTrackingRequired,
            ) == LifecycleDispatch.UNTRACKED) {
                trackingState = ToolTrackingState.UNTRACKED
            }
            return ToolExecutionPlanResult.TimedOut(
                policy = effectivePolicy,
                executionId = null,
                trackingState = trackingState,
            )
        }
        val hookRejection = runInterceptors(redacted)
        if (hookRejection != null) {
            timing?.preflightFinished()
            notifyRejected(redacted, hookRejection.errorCode)
            return hookRejection
        }
        timing?.preflightFinished()

        var executionId: String? = null
        var timeoutTerminationState: ToolTerminationState? = null
        val completed = withTimeoutOrNull(request.wallClockBudgetMs) {
            // Diagnostic: splits "blocked acquiring the policy mutex" from "everything else in
            // the budget" — the two candidates the audit could not separate statically.
            diagMark("before_policy_lock")
            withPolicyLocks(effectivePolicy) {
                diagMark("after_policy_lock")
                when (dispatchLifecycle(
                    RedactedToolLifecycleEvent(
                        phase = RedactedToolLifecycleEvent.Phase.STARTING,
                        context = redacted,
                    ),
                    durableTrackingRequired = durableTrackingRequired,
                    beforeSideEffect = true,
                )) {
                    LifecycleDispatch.BLOCKED -> return@withPolicyLocks trackingUnavailable()
                    LifecycleDispatch.UNTRACKED -> trackingState = ToolTrackingState.UNTRACKED
                    LifecycleDispatch.TRACKED -> Unit
                }
                diagMark("after_dispatch_lifecycle_starting")
                timing?.executionStarted()
                coroutineScope {
                    val handle = request.startableTool?.start(request.args, context) ?: run {
                        val deferred = async(Dispatchers.IO) {
                            // Diagnostic: proves the tool body is actually invoked, and how much
                            // of the budget was already spent reaching it.
                            diagMark("before_tool_execute")
                            request.legacyExecute(request.args).also {
                                diagMark("after_tool_execute")
                            }
                        }
                        LegacyToolExecutionHandle(result = deferred)
                    }
                    executionId = handle.executionId
                    if (dispatchLifecycle(
                        RedactedToolLifecycleEvent(
                            phase = RedactedToolLifecycleEvent.Phase.RUNNING,
                            context = redacted,
                            executionId = handle.executionId,
                        ),
                        durableTrackingRequired = durableTrackingRequired,
                    ) == LifecycleDispatch.UNTRACKED) {
                        trackingState = ToolTrackingState.UNTRACKED
                    }
                    request.runControl?.registerTool(request.toolCallId, handle)
                    try {
                        val output = handle.awaitResult()
                        timing?.rawResultReady()
                        if (dispatchLifecycle(
                            RedactedToolLifecycleEvent(
                                phase = RedactedToolLifecycleEvent.Phase.COMPLETED,
                                context = redacted,
                                executionId = handle.executionId,
                            ),
                            durableTrackingRequired = durableTrackingRequired,
                        ) == LifecycleDispatch.UNTRACKED) {
                            trackingState = ToolTrackingState.UNTRACKED
                        }
                        timing?.completionLedgerFinished()
                        ToolExecutionPlanResult.Completed(
                            output = output,
                            policy = effectivePolicy,
                            executionId = handle.executionId,
                            trackingState = trackingState,
                        )
                    } catch (cancelled: CancellationException) {
                        val cancelReason = when {
                            cancelled is TimeoutCancellationException -> ToolCancelReason.TIMEOUT
                            request.runControl?.stoppedBy != null -> ToolCancelReason.USER_STOPPED
                            else -> ToolCancelReason.USER_INTERRUPTED
                        }
                        withContext(NonCancellable) {
                            if (dispatchLifecycle(
                                RedactedToolLifecycleEvent(
                                    phase = RedactedToolLifecycleEvent.Phase.CANCEL_REQUESTED,
                                    context = redacted,
                                    executionId = handle.executionId,
                                    detail = cancelReason.message,
                                    requestedTerminalOutcome = if (
                                        cancelReason == ToolCancelReason.TIMEOUT
                                    ) {
                                        RequestedTerminalOutcome.TIMED_OUT
                                    } else {
                                        RequestedTerminalOutcome.CANCELLED
                                    },
                                ),
                                durableTrackingRequired = durableTrackingRequired,
                            ) == LifecycleDispatch.UNTRACKED) {
                                trackingState = ToolTrackingState.UNTRACKED
                            }
                            if (dispatchLifecycle(
                                RedactedToolLifecycleEvent(
                                    phase = RedactedToolLifecycleEvent.Phase.TERMINATING,
                                    context = redacted,
                                    executionId = handle.executionId,
                                    requestedTerminalOutcome = if (
                                        cancelReason == ToolCancelReason.TIMEOUT
                                    ) {
                                        RequestedTerminalOutcome.TIMED_OUT
                                    } else {
                                        RequestedTerminalOutcome.CANCELLED
                                    },
                                ),
                                durableTrackingRequired = durableTrackingRequired,
                            ) == LifecycleDispatch.UNTRACKED) {
                                trackingState = ToolTrackingState.UNTRACKED
                            }
                            val state = cancelHandle(handle, cancelReason)
                            if (cancelReason == ToolCancelReason.TIMEOUT) {
                                timeoutTerminationState = state
                            } else {
                                if (dispatchLifecycle(
                                    RedactedToolLifecycleEvent(
                                        phase = RedactedToolLifecycleEvent.Phase.CANCELLED,
                                        context = redacted,
                                        executionId = handle.executionId,
                                        terminationState = state,
                                        detail = cancelReason.message,
                                        requestedTerminalOutcome = RequestedTerminalOutcome.CANCELLED,
                                    ),
                                    durableTrackingRequired = durableTrackingRequired,
                                ) == LifecycleDispatch.UNTRACKED) {
                                    trackingState = ToolTrackingState.UNTRACKED
                                }
                            }
                        }
                        throw cancelled
                    } catch (failure: Throwable) {
                        if (dispatchLifecycle(
                            RedactedToolLifecycleEvent(
                                phase = RedactedToolLifecycleEvent.Phase.FAILED,
                                context = redacted,
                                executionId = handle.executionId,
                                detail = failure.javaClass.simpleName,
                            ),
                            durableTrackingRequired = durableTrackingRequired,
                        ) == LifecycleDispatch.UNTRACKED) {
                            trackingState = ToolTrackingState.UNTRACKED
                        }
                        throw failure
                    } finally {
                        request.runControl?.unregisterTool(request.toolCallId, handle)
                    }
                }
            }
        }
        if (completed != null) return completed

        if (dispatchLifecycle(
            RedactedToolLifecycleEvent(
                phase = RedactedToolLifecycleEvent.Phase.TIMED_OUT,
                context = redacted,
                executionId = executionId,
                terminationState = timeoutTerminationState,
                detail = "wall_clock_timeout",
                requestedTerminalOutcome = RequestedTerminalOutcome.TIMED_OUT,
            ),
            durableTrackingRequired = durableTrackingRequired,
        ) == LifecycleDispatch.UNTRACKED) {
            trackingState = ToolTrackingState.UNTRACKED
        }
        return ToolExecutionPlanResult.TimedOut(effectivePolicy, executionId, trackingState)
    }

    private suspend fun runInterceptors(
        context: RedactedToolCallContext,
    ): ToolExecutionPlanResult.Rejected? {
        for (interceptor in interceptors) {
            val attempt = runCatching {
                withTimeoutOrNull(interceptorTimeoutMs) { interceptor.intercept(context) }
            }
            if (attempt.isFailure) {
                return ToolExecutionPlanResult.Rejected(
                    "tool_hook_failed",
                    "A tool interceptor failed; the call was blocked.",
                )
            }
            when (val decision = attempt.getOrNull()) {
                null -> return ToolExecutionPlanResult.Rejected(
                    "tool_hook_timeout",
                    "A tool interceptor timed out; the call was blocked.",
                )
                is ToolHookDecision.Block -> return ToolExecutionPlanResult.Rejected(
                    "tool_hook_blocked",
                    decision.reason,
                )
                ToolHookDecision.Proceed -> Unit
            }
        }
        return null
    }

    private suspend fun cancelHandle(
        handle: ToolExecutionHandle,
        reason: ToolCancelReason,
    ): ToolTerminationState = withContext(NonCancellable) {
        runCatching { handle.requestCancel(reason) }
        runCatching { handle.awaitTermination(2_000.milliseconds) }
            .getOrDefault(ToolTerminationState.Unknown)
    }

    private suspend fun notifyObservers(event: RedactedToolLifecycleEvent) {
        observers.forEach { observer ->
            runCatching {
                withTimeoutOrNull(observerTimeoutMs) { observer.onEvent(event) }
            }
        }
    }

    private suspend fun dispatchLifecycle(
        event: RedactedToolLifecycleEvent,
        durableTrackingRequired: Boolean,
        beforeSideEffect: Boolean = false,
    ): LifecycleDispatch {
        val persisted = persistCritical(event)
        notifyObservers(event)
        if (persisted) {
            trackingHealth.markRecovered()
            return LifecycleDispatch.TRACKED
        }
        trackingHealth.markDegraded("critical_lifecycle_write_failed")
        return when {
            beforeSideEffect && durableTrackingRequired -> LifecycleDispatch.BLOCKED
            else -> LifecycleDispatch.UNTRACKED
        }
    }

    private suspend fun persistCritical(event: RedactedToolLifecycleEvent): Boolean {
        criticalRetryDelaysMs.forEachIndexed { index, delayMs ->
            if (index > 0) delay(delayMs)
            if (runCatching { criticalSink.persist(event) }.isSuccess) return true
        }
        return false
    }

    private fun trackingUnavailable(): ToolExecutionPlanResult.Rejected =
        ToolExecutionPlanResult.Rejected(
            errorCode = "execution_tracking_unavailable",
            detail = "Authoritative execution tracking is unavailable; the tool was not started.",
        )

    private suspend fun notifyRejected(
        context: RedactedToolCallContext,
        code: String,
    ) {
        notifyObservers(
            RedactedToolLifecycleEvent(
                phase = RedactedToolLifecycleEvent.Phase.STARTING,
                context = context,
            )
        )
        notifyObservers(
            RedactedToolLifecycleEvent(
                phase = RedactedToolLifecycleEvent.Phase.FAILED,
                context = context,
                detail = code.take(120),
            )
        )
    }

    private suspend fun <T> withPolicyLocks(
        policy: ToolExecutionPolicy,
        block: suspend () -> T,
    ): T = when (policy.concurrency) {
        ToolConcurrency.GLOBAL_SERIAL -> globalMutex.withLock { block() }
        ToolConcurrency.RESOURCE_SERIAL,
        ToolConcurrency.PARALLEL_SAFE -> {
            val mutexes = policy.resourceKeys
                .sortedBy(ToolResourceKey::toString)
                .map { key -> resourceMutexes.computeIfAbsent(key) { Mutex() } }
            withLocks(mutexes, 0, block)
        }
    }

    private suspend fun <T> withLocks(
        locks: List<Mutex>,
        index: Int,
        block: suspend () -> T,
    ): T = if (index >= locks.size) {
        block()
    } else {
        locks[index].withLock { withLocks(locks, index + 1, block) }
    }

}

private const val MAX_HOST_TOOL_CALL_ID_CHARS = 256

internal fun isValidHostToolCallId(value: String): Boolean =
    value.isNotBlank() &&
        value.length <= MAX_HOST_TOOL_CALL_ID_CHARS &&
        value.none { it.isISOControl() }
