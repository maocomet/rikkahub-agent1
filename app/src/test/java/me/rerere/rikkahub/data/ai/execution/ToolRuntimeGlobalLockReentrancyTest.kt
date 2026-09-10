package me.rerere.rikkahub.data.ai.execution

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.CancelRequestResult
import me.rerere.rikkahub.data.ai.tools.StartableTool
import me.rerere.rikkahub.data.ai.tools.ToolCancelReason
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.ai.tools.ToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.ToolTerminationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * Regression for the nested-workflow self-deadlock.
 *
 * `chat → workflow_run (GLOBAL_SERIAL) → WorkflowEngine → WorkflowActionRunner →
 * post_notification (GLOBAL_SERIAL)` re-entered the runtime's non-reentrant `globalMutex`
 * from a call chain that already held it. The inner acquisition waited on its own lock and
 * unwound only when the per-action wall-clock budget expired — surfacing as `action_timeout`
 * after the full 60s, with the notification never posted. The same workflow fired from the UI
 * ("Run now") or from a real trigger is NOT nested and always succeeded, which is what
 * pointed at lock re-entrancy rather than at Room persistence or the tool body.
 *
 * Both halves are pinned here: a nested chain must not wait on a lock it already holds, and
 * an unrelated concurrent caller must still be blocked by that lock (the fix must skip a
 * duplicate acquisition, never locking itself).
 */
class ToolRuntimeGlobalLockReentrancyTest {

    private val serialPolicy = ToolExecutionPolicy(
        effects = setOf(ToolEffect.PERSISTENT_STATE),
        concurrency = ToolConcurrency.GLOBAL_SERIAL,
        cancellationCapability = ToolCancellationCapability.LOCAL_WAIT_ONLY,
    )

    private fun runtime() = DefaultToolRuntime(
        policyResolver = ToolExecutionPolicyResolver { _, _, _ -> serialPolicy },
        securityDescriptorResolver = ToolSecurityDescriptorResolver { toolName, _ ->
            ToolSecurityDescriptor(
                toolName = toolName,
                source = ToolDescriptorSource.INTERNAL,
                approval = ToolDescriptorApproval.DEFAULT,
                allowsPermanentApproval = true,
            )
        },
    )

    private fun request(
        toolCallId: String,
        toolName: String,
        budgetMs: Long = 5_000,
        startable: StartableTool,
    ) = ToolExecutionPlanRequest(
        toolCallId = toolCallId,
        toolName = toolName,
        args = buildJsonObject {},
        executionContext = ToolExecutionContext(
            runId = Uuid.random(),
            conversationId = Uuid.random(),
            assistantId = "assistant-1",
            callOrigin = ToolCallOrigin.TrustedWorkflow,
        ),
        startableTool = startable,
        legacyExecute = { error("legacy executor must not be used") },
        runControl = null,
        wallClockBudgetMs = budgetMs,
    )

    /** A startable whose body is [body] and whose handle reports [label]. */
    private fun startable(
        label: String,
        body: suspend () -> Unit = {},
    ) = object : StartableTool {
        override suspend fun start(
            args: JsonElement,
            context: ToolExecutionContext,
        ): ToolExecutionHandle = object : ToolExecutionHandle {
            override val executionId: String = "handle-$label"
            override suspend fun awaitResult(): List<UIMessagePart> {
                body()
                return listOf(UIMessagePart.Text(label))
            }

            override fun requestCancel(reason: ToolCancelReason) = CancelRequestResult.Requested
            override suspend fun awaitTermination(gracePeriod: Duration) =
                ToolTerminationState.StoppedConfirmed
        }
    }

    /**
     * The deadlock itself. The outer GLOBAL_SERIAL call is still inside its critical section
     * when the inner one runs — exactly the nesting `workflow_run` produces.
     *
     * The inner call is wrapped in a timeout so pre-fix behaviour fails this test cleanly
     * instead of hanging the suite forever.
     */
    @Test
    fun `nested global-serial call does not wait on the lock its own chain holds`() = runBlocking {
        val runtime = runtime()
        var inner: ToolExecutionPlanResult? = null

        val outer = startable("outer") {
            inner = withTimeoutOrNull(2_000) {
                runtime.execute(
                    request("inner", "post_notification", startable = startable("inner")),
                )
            }
        }

        val outerResult = runtime.execute(request("outer", "workflow_run", startable = outer))

        assertTrue("outer should complete: $outerResult", outerResult is ToolExecutionPlanResult.Completed)
        // Pre-fix this is null: the nested call waited on its own held lock until the
        // withTimeoutOrNull guard fired (production uses the per-action budget, ~60s).
        assertTrue(
            "nested call waited on the already-held global lock (pre-fix deadlock), got $inner",
            inner is ToolExecutionPlanResult.Completed,
        )
        assertEquals("inner", ((inner as ToolExecutionPlanResult.Completed).output.single() as UIMessagePart.Text).text)
    }

    /**
     * A token for a DIFFERENT lock must not suppress the acquisition.
     *
     * This is the property that makes the element safe to be constructible: the skip keys on
     * the *identity* of the lock, not on the mere presence of a token. A caller carrying an
     * element minted for some other mutex (a stray token, another runtime instance, or a
     * future code path that installs one wrongly) still has to acquire this runtime's lock —
     * and therefore must still be blocked by a concurrent holder.
     */
    @Test
    fun `token for a different lock does not bypass this runtime lock`() = runBlocking {
        val runtime = runtime()
        val foreignMutex = Mutex()
        val holderInside = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()
        val foreignEntered = CompletableDeferred<Unit>()

        coroutineScope {
            val holder = async {
                runtime.execute(
                    request(
                        "holder",
                        "workflow_run",
                        startable = startable("holder") {
                            holderInside.complete(Unit)
                            releaseHolder.await()
                        },
                    ),
                )
            }
            holderInside.await()

            // Carry a token that attests to an unrelated lock the whole time.
            val foreign = async {
                withContext(GlobalPolicyLockToken(foreignMutex)) {
                    runtime.execute(
                        request(
                            "foreign",
                            "post_notification",
                            startable = startable("foreign") { foreignEntered.complete(Unit) },
                        ),
                    )
                }
            }

            delay(250)
            assertFalse(
                "a token for a different mutex must NOT bypass the held lock",
                foreignEntered.isCompleted,
            )

            releaseHolder.complete(Unit)
            holder.await()
            foreign.await()
        }

        assertTrue("foreign-token caller completes once the lock is free", foreignEntered.isCompleted)
    }

    /** A single, non-nested GLOBAL_SERIAL call keeps working — the fix must not break it. */
    @Test
    fun `non-nested global-serial call still completes`() = runBlocking {
        val result = runtime().execute(
            request("solo", "post_notification", startable = startable("solo")),
        )
        assertTrue("expected completion, got $result", result is ToolExecutionPlanResult.Completed)
    }

    /**
     * The security property: skipping the duplicate acquisition must not disable the lock.
     * A caller that is NOT part of the holding chain still has to wait for it.
     */
    @Test
    fun `concurrent sibling caller still waits for the held global lock`() = runBlocking {
        val runtime = runtime()
        val outerInside = CompletableDeferred<Unit>()
        val releaseOuter = CompletableDeferred<Unit>()
        val siblingDone = CompletableDeferred<Unit>()

        coroutineScope {
            val outer = async {
                runtime.execute(
                    request(
                        "outer",
                        "workflow_run",
                        startable = startable("outer") {
                            outerInside.complete(Unit)
                            releaseOuter.await()
                        },
                    ),
                )
            }
            outerInside.await()

            val sibling = async {
                runtime.execute(
                    request(
                        "sibling",
                        "post_notification",
                        startable = startable("sibling") { siblingDone.complete(Unit) },
                    ),
                )
            }

            // Give the sibling every chance to (wrongly) slip past the held lock.
            delay(250)
            assertFalse(
                "sibling entered the critical section while the lock was held",
                siblingDone.isCompleted,
            )

            releaseOuter.complete(Unit)
            outer.await()
            sibling.await()
        }

        assertTrue("sibling should complete once the lock is released", siblingDone.isCompleted)
    }
}
