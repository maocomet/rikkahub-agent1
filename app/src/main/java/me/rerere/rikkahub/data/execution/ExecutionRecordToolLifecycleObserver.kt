package me.rerere.rikkahub.data.execution

import me.rerere.rikkahub.data.ai.execution.RedactedToolLifecycleEvent
import me.rerere.rikkahub.data.ai.execution.RedactedToolCallContext
import me.rerere.rikkahub.data.ai.execution.CriticalLifecyclePersistenceException
import me.rerere.rikkahub.data.ai.execution.CriticalToolLifecycleSink
import me.rerere.rikkahub.data.ai.tools.ToolTerminationState
import me.rerere.rikkahub.data.capability.ResourceScope
import me.rerere.rikkahub.data.capability.SubjectType
import me.rerere.rikkahub.data.capability.ToolCapabilityResolver
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningScope
import kotlin.uuid.Uuid

/**
 * Projects ToolRuntime lifecycle events into the authoritative execution table.
 *
 * It intentionally resolves resource metadata without argument values, so no command, file
 * content, credential, URL parameter, or tool output can leak into Room, audit, or logs.
 *
 * ## The start of a call is a decision, not an open
 *
 * A call that needs approval was already opened by the approval authority when the barrier was
 * raised, under that approval's own admission identity. Opening it again here under the runtime's
 * ordinary identity is not a retry of the same admission; it is a second, different one, and the
 * ledger refuses it. So [RedactedToolLifecycleEvent.Phase.STARTING] asks
 * [decideToolStartAdmission] what this record is, and either opens one (the ordinary path, byte for
 * byte as before), adopts the approval authority's (only when it is provably the same granted call,
 * already admitted to run) or refuses — and a refusal reaches the runtime as a failed durable
 * write, which stops the tool before its side effect. The storage behind that decision is a
 * [ToolExecutionLedger] so the checks can be run rather than only read.
 */
class ExecutionRecordCriticalToolLifecycleSink(
    private val ledger: ToolExecutionLedger,
) : CriticalToolLifecycleSink {
    override suspend fun persist(event: RedactedToolLifecycleEvent) {
        val context = event.context
        val recordId = ExecutionRecordIds.tool(context.runId, context.toolCallId)
        when (event.phase) {
            RedactedToolLifecycleEvent.Phase.STARTING -> {
                val resolved = ToolCapabilityResolver.resolve(context.toolName)
                val draft = ExecutionRecordDraft(
                    id = recordId,
                    traceId = context.runId,
                    commandId = context.commandId,
                    conversationId = context.conversationId,
                    toolCallId = context.toolCallId,
                    toolName = context.toolName,
                    toolSchemaFingerprint = context.toolSchemaFingerprint,
                    learningScope = context.toLearningScope(),
                    subjectId = context.subjectId.ifBlank { context.assistantId },
                    subjectType = (context.subjectType ?: SubjectType.LOCAL_ASSISTANT).name,
                    origin = context.origin.name,
                    capabilityKeys = resolved.capabilities
                        .map { it.value }
                        .sorted()
                        .joinToString(","),
                    resourceSummary = resolved.resource.toAuditSummary(),
                    runtime = runtimeFor(context.toolName, context.legacyExecution),
                    idempotencyKey = ExecutionRecordIds.toolIdempotency(
                        context.runId,
                        context.toolCallId,
                    ),
                    initialStatus = ExecutionStatus.starting,
                )
                // One read, in one snapshot: the record and the approval that owns it are decided
                // together, so the decision cannot be about a state that never existed.
                val view = ledger.toolStartView(recordId)
                when (
                    val admission = decideToolStartAdmission(
                        existing = view.record,
                        approvalAuthority = view.approval,
                        draft = draft,
                    )
                ) {
                    ToolStartAdmission.OpensRecord -> {
                        val record = ledger.open(
                            draft = draft,
                            mutationId = "open:$recordId",
                            source = ExecutionStateSource.LIVE_EVENT,
                            reasonCode = "execution_opened",
                        )
                        val current = ExecutionStatus.fromWire(record.status)
                        if (current != ExecutionStatus.starting &&
                            current != ExecutionStatus.running
                        ) {
                            requireDurable(
                                ledger.transition(
                                    id = recordId,
                                    target = ExecutionStatus.starting,
                                    mutationId = mutationId(event),
                                    source = ExecutionStateSource.LIVE_EVENT,
                                    reasonCode = "tool_starting",
                                ),
                                ExecutionStatus.starting,
                            )
                        }
                    }

                    // The approval authority's record is already durable at `starting`, written by
                    // the transaction that committed the decision. What is left is to *claim* it —
                    // a compare-and-set against the version the decision was made on — because the
                    // snapshot above can go stale: an emergency stop, a cancellation or a recovery
                    // may commit while this call is between the reading and the run.
                    //
                    // The claim is what makes that impossible to run through. It changes no state
                    // the call depends on (the status stays where the approval put it, because
                    // `running` is the runtime's own statement and it does not have a handle yet);
                    // what it does is fail if anything moved.
                    is ToolStartAdmission.AdoptsApprovedCall -> requireClaimed(
                        ledger.claimApprovedToolStart(
                            record = admission.record,
                            mutationId = mutationId(event),
                            reasonCode = "tool_start_adopted",
                        ),
                    )

                    // Fail closed. The runtime requires durable tracking before a side effect, so
                    // throwing here is what keeps the tool body from running against a record this
                    // sink could not vouch for.
                    is ToolStartAdmission.Refuses ->
                        throw CriticalLifecyclePersistenceException(admission.reasonCode)
                }
            }

            RedactedToolLifecycleEvent.Phase.RUNNING -> requireDurable(
                ledger.transition(
                    id = recordId,
                    target = ExecutionStatus.running,
                    runtimeHandleSummary = event.executionId,
                    runtime = event.executionId?.let { runtimeForHandle(context.toolName, it) },
                    mutationId = mutationId(event),
                    reasonCode = "tool_running",
                ),
                ExecutionStatus.running,
            )

            RedactedToolLifecycleEvent.Phase.CANCEL_REQUESTED -> requireDurable(
                ledger.transition(
                    id = recordId,
                    target = ExecutionStatus.cancel_requested,
                    runtimeHandleSummary = event.executionId,
                    detail = event.detail ?: "cancel_requested",
                    mutationId = mutationId(event),
                    reasonCode = event.detail ?: "cancel_requested",
                    requestedTerminalOutcome = event.requestedTerminalOutcome,
                ),
                ExecutionStatus.cancel_requested,
            )

            RedactedToolLifecycleEvent.Phase.TERMINATING -> requireDurable(
                ledger.transition(
                    id = recordId,
                    target = ExecutionStatus.terminating,
                    runtimeHandleSummary = event.executionId,
                    mutationId = mutationId(event),
                    reasonCode = "termination_started",
                    requestedTerminalOutcome = event.requestedTerminalOutcome,
                ),
                ExecutionStatus.terminating,
            )

            RedactedToolLifecycleEvent.Phase.COMPLETED -> requireDurable(
                ledger.transition(
                    id = recordId,
                    target = ExecutionStatus.succeeded,
                    runtimeHandleSummary = event.executionId,
                    mutationId = mutationId(event),
                    reasonCode = "tool_completed",
                    verificationState = VerificationState.LIVE_CONFIRMED,
                ),
                ExecutionStatus.succeeded,
            )

            RedactedToolLifecycleEvent.Phase.FAILED -> requireDurable(
                ledger.transition(
                    id = recordId,
                    target = ExecutionStatus.failed,
                    runtimeHandleSummary = event.executionId,
                    detail = event.detail ?: "runtime_failed",
                    mutationId = mutationId(event),
                    reasonCode = event.detail ?: "runtime_failed",
                ),
                ExecutionStatus.failed,
            )

            RedactedToolLifecycleEvent.Phase.CANCELLED -> {
                val confirmed = event.terminationState == ToolTerminationState.StoppedConfirmed
                val target = if (confirmed) ExecutionStatus.cancelled else ExecutionStatus.terminating
                val reason = if (confirmed) "termination_confirmed" else "termination_unconfirmed"
                requireDurable(
                    ledger.transition(
                        id = recordId,
                        target = target,
                        runtimeHandleSummary = event.executionId,
                        cancellationResult = event.terminationState?.name,
                        detail = reason,
                        mutationId = mutationId(event),
                        reasonCode = reason,
                        verificationState = if (confirmed) {
                            VerificationState.LIVE_CONFIRMED
                        } else {
                            VerificationState.STALE
                        },
                        requestedTerminalOutcome = RequestedTerminalOutcome.CANCELLED,
                    ),
                    target,
                )
            }

            RedactedToolLifecycleEvent.Phase.TIMED_OUT -> {
                val decision = decideTimedOutExecution(
                    terminationState = event.terminationState,
                    hasRuntimeHandle = event.executionId != null,
                )
                requireDurable(
                    ledger.transition(
                        id = recordId,
                        target = decision.target,
                        runtimeHandleSummary = event.executionId,
                        cancellationResult = event.terminationState?.name,
                        detail = if (event.terminationState == ToolTerminationState.StoppedConfirmed) {
                            "wall_clock_timeout_termination_confirmed"
                        } else {
                            "wall_clock_timeout_termination_unconfirmed"
                        },
                        mutationId = mutationId(event),
                        reasonCode = event.detail ?: "wall_clock_timeout",
                        verificationState = decision.verification,
                        requestedTerminalOutcome = RequestedTerminalOutcome.TIMED_OUT,
                    ),
                    decision.target,
                )
            }
        }
    }

    /**
     * Whether the claim actually took the record, and what to do about each way it could not.
     *
     * The claim is a compare-and-set, so its failures are the point of it rather than an accident:
     *
     * - `Applied` — the record was still exactly what the decision read, at the version it read. The
     *   reducer leaves the status where the approval put it, so this is the only outcome that must
     *   equal `starting`.
     * - `Duplicate` — this exact claim is already journaled, so some runtime attempt has already
     *   crossed the start boundary. It is refused even if the record still says `starting`: this
     *   sink is shared by paths that do not own the Claude P bridge's in-memory claim, and an
     *   approval grants permission, not a second execution right.
     * - Everything else is the state having moved — a stop, a cancellation, a recovery, a conflict —
     *   and every one of them is a refusal. There is deliberately no path here that retries, and no
     *   path that continues as UNTRACKED.
     */
    private fun requireClaimed(result: ExecutionMutationResult) {
        when (result) {
            is ExecutionMutationResult.Applied -> {
                val status = ExecutionStatus.fromWire(result.record.status)
                if (status != ExecutionStatus.starting) {
                    throw CriticalLifecyclePersistenceException("execution_claim_status_drift")
                }
            }

            is ExecutionMutationResult.Duplicate ->
                throw CriticalLifecyclePersistenceException("execution_start_already_claimed")

            is ExecutionMutationResult.Terminal ->
                throw CriticalLifecyclePersistenceException("execution_already_terminal")

            is ExecutionMutationResult.Missing ->
                throw CriticalLifecyclePersistenceException("execution_record_missing")

            is ExecutionMutationResult.Invalid ->
                throw CriticalLifecyclePersistenceException("execution_transition_invalid")

            is ExecutionMutationResult.Conflict ->
                throw CriticalLifecyclePersistenceException("execution_cas_conflict")
        }
    }

    private fun requireDurable(
        result: ExecutionTransitionResult,
        requested: ExecutionStatus,
    ) {
        when (result) {
            is ExecutionTransitionResult.Applied -> Unit
            is ExecutionTransitionResult.Terminal -> {
                if (ExecutionStatus.fromWire(result.record.status) != requested) {
                    throw CriticalLifecyclePersistenceException("execution_already_terminal")
                }
            }
            is ExecutionTransitionResult.Missing ->
                throw CriticalLifecyclePersistenceException("execution_record_missing")
            is ExecutionTransitionResult.Invalid ->
                throw CriticalLifecyclePersistenceException("execution_transition_invalid")
            is ExecutionTransitionResult.Conflict ->
                throw CriticalLifecyclePersistenceException("execution_cas_conflict")
        }
    }

    private fun mutationId(event: RedactedToolLifecycleEvent): String =
        ExecutionRecordIds.toolEvent(
            runId = event.context.runId,
            toolCallId = event.context.toolCallId,
            phase = event.phase.name,
            executionId = event.executionId,
        )

    // Runtime arguments may contain commands, paths, hosts, or user text. The execution ledger
    // stores only the stable resource class; the executable payload remains in the message graph.
    private fun ResourceScope.toAuditSummary(): String = kind

    private fun runtimeFor(toolName: String, legacy: Boolean): ExecutionRuntime = when {
        legacy -> ExecutionRuntime.LEGACY
        toolName.startsWith("termux_") -> ExecutionRuntime.TERMUX
        toolName.startsWith("ssh_") -> ExecutionRuntime.SSH
        toolName.startsWith("workspace_") -> ExecutionRuntime.WORKSPACE
        toolName.startsWith("mcp__") -> ExecutionRuntime.MCP
        toolName.startsWith("plugin__") -> ExecutionRuntime.PLUGIN
        toolName.startsWith("privileged_") || toolName.startsWith("external_bridge_") ->
            ExecutionRuntime.SHIZUKU
        else -> ExecutionRuntime.LOCAL_TOOL
    }

    private fun runtimeForHandle(toolName: String, handle: String): ExecutionRuntime = when {
        handle.startsWith("termux:") -> ExecutionRuntime.TERMUX
        handle.startsWith("workspace:") -> ExecutionRuntime.WORKSPACE
        toolName.startsWith("ssh_") -> ExecutionRuntime.SSH
        else -> runtimeFor(toolName, legacy = false)
    }

}

internal fun RedactedToolCallContext.toLearningScope(): LearningScope =
    if (subjectType == SubjectType.LOCAL_SECOND_USER) {
        LearningScope.AuthoritySubject(subjectId)
    } else {
        LearningScope.Assistant(Uuid.parse(assistantId))
    }

internal data class TimedOutExecutionDecision(
    val target: ExecutionStatus,
    val verification: VerificationState,
)

internal fun decideTimedOutExecution(
    terminationState: ToolTerminationState?,
    hasRuntimeHandle: Boolean,
): TimedOutExecutionDecision {
    val confirmed = terminationState == ToolTerminationState.StoppedConfirmed || !hasRuntimeHandle
    return if (confirmed) {
        TimedOutExecutionDecision(ExecutionStatus.timed_out, VerificationState.LIVE_CONFIRMED)
    } else {
        TimedOutExecutionDecision(ExecutionStatus.terminating, VerificationState.STALE)
    }
}

object ExecutionRecordIds {
    /**
     * Keeps the established readable ID for normal UUID runs, but hashes any non-canonical or
     * oversized identity with a versioned, length-prefixed digest. No identity is ever truncated.
     */
    fun tool(runId: String, toolCallId: String): String {
        validateIdentityField("runId", runId)
        validateIdentityField("toolCallId", toolCallId)
        val legacy = "tool:$runId:$toolCallId"
        val canonicalRun = runCatching { Uuid.parse(runId).toString() == runId }.getOrDefault(false)
        return if (canonicalRun && legacy.length <= MAX_EXECUTION_ID_CHARS) {
            legacy
        } else {
            "tool-v2:" + LearningCanonicalId.digest(
                domainVersion = "execution-tool-record-v2",
                fields = listOf(runId, toolCallId),
            )
        }
    }

    fun toolIdempotency(runId: String, toolCallId: String): String {
        validateIdentityField("runId", runId)
        validateIdentityField("toolCallId", toolCallId)
        val legacy = "tool:$runId:$toolCallId"
        val canonicalRun = runCatching { Uuid.parse(runId).toString() == runId }.getOrDefault(false)
        return if (canonicalRun && legacy.length <= MAX_IDEMPOTENCY_CHARS) {
            legacy
        } else {
            "tool-idempotency-v2:" + LearningCanonicalId.digest(
                domainVersion = "execution-tool-idempotency-v2",
                fields = listOf(runId, toolCallId),
            )
        }
    }

    fun toolEvent(
        runId: String,
        toolCallId: String,
        phase: String,
        executionId: String?,
    ): String {
        validateIdentityField("runId", runId)
        validateIdentityField("toolCallId", toolCallId)
        validateIdentityField("phase", phase)
        executionId?.let { validateIdentityField("executionId", it) }
        val legacy = buildString {
            append("tool-event:").append(runId).append(':').append(toolCallId).append(':').append(phase)
            executionId?.let { append(':').append(it) }
        }
        val canonicalRun = runCatching { Uuid.parse(runId).toString() == runId }.getOrDefault(false)
        val unambiguousToolCall = toolCallId.all {
            it.isLetterOrDigit() || it == '-' || it == '_' || it == '.'
        }
        return if (canonicalRun && unambiguousToolCall && legacy.length <= MAX_MUTATION_ID_CHARS) {
            legacy
        } else {
            "tool-event-v2:" + LearningCanonicalId.digest(
                domainVersion = "execution-tool-event-v2",
                fields = listOf(runId, toolCallId, phase, executionId),
            )
        }
    }

    private fun validateIdentityField(name: String, value: String) {
        require(value.isNotBlank()) { "$name is empty" }
        require(value.none(Char::isISOControl)) { "$name contains control characters" }
        require(value.encodeToByteArray().size <= MAX_CANONICAL_FIELD_BYTES) {
            "$name is too large"
        }
    }

    private const val MAX_EXECUTION_ID_CHARS = 480
    private const val MAX_IDEMPOTENCY_CHARS = 300
    private const val MAX_MUTATION_ID_CHARS = 500
    private const val MAX_CANONICAL_FIELD_BYTES = 4_096
}
