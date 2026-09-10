package me.rerere.rikkahub.data.ai.execution

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.capability.SubjectType

/**
 * TEMPORARY diagnostics seam for the workflow `action_timeout` (60s) investigation.
 *
 * Localises *where* a headless workflow action spends its wall-clock budget by stamping one
 * marker at each boundary between [me.rerere.rikkahub.workflow.execution.WorkflowActionRunner]
 * and the tool body inside [DefaultToolRuntime]. It observes only; it never changes scheduling,
 * budgets, locking, or control flow.
 *
 * Content-free by construction. The API accepts a phase name, the host-owned workflow identity,
 * the tool name, an elapsed millisecond count, and two origin flags — tool arguments, tool
 * output, notification titles/bodies, prompts, tokens, secrets and user content are never
 * parameters here and therefore can never reach the log.
 *
 * Gating: every marker except [beginAction] is a no-op unless [beginAction] has anchored that
 * exact tool-call id. Only the workflow action runner anchors, so chat, owner, direct-mode and
 * cron tool calls (which go through the same [DefaultToolRuntime]) emit nothing.
 *
 * Removal: delete this file and its call sites once the timeout root cause is fixed.
 */
internal object WorkflowActionDiagnostics {

    private const val TAG = "WorkflowActionDiag"

    /** Wall-clock anchor per in-flight workflow action, keyed by host tool-call id. */
    private val anchors = ConcurrentHashMap<String, Long>()

    /** Marks the start of one workflow action. Populates the elapsed reference for its markers. */
    fun beginAction(toolCallId: String) {
        anchors[toolCallId] = System.nanoTime()
    }

    /** Releases the anchor for a finished action so the map cannot grow across a long fire. */
    fun endAction(toolCallId: String) {
        anchors.remove(toolCallId)
    }

    /**
     * Emits one content-free boundary marker. No-op for any tool call that the workflow action
     * runner did not anchor, which is what keeps non-workflow surfaces silent.
     */
    fun mark(
        phase: String,
        toolCallId: String,
        toolName: String,
        workflowId: String,
        isHeadless: Boolean,
        callOrigin: String,
    ) {
        val anchor = anchors[toolCallId] ?: return
        val elapsedMs = (System.nanoTime() - anchor) / 1_000_000L
        // runCatching mirrors the existing logSafe guards: JVM unit tests run against an
        // unmocked android.util.Log, and diagnostics must never alter a tool outcome.
        runCatching {
            Log.w(
                TAG,
                "phase=$phase workflow_id=$workflowId tool_name=$toolName " +
                    "elapsed_ms=$elapsedMs is_headless=$isHeadless call_origin=$callOrigin",
            )
        }
    }

    /**
     * The workflow id this execution context belongs to, or null when the context is not a
     * workflow fire. The engine stamps it as the capability subject id `workflow:<id>`.
     */
    fun workflowIdOf(context: ToolExecutionContext): String? = context.capabilitySubject
        ?.takeIf { it.type == SubjectType.WORKFLOW }
        ?.id
        ?.removePrefix("workflow:")

    /** The workflow id carried by a live action-runner invocation, or "" when there is none. */
    fun workflowIdOf(invocation: ToolRuntimeInvocation): String =
        workflowIdOf(invocation.executionContext).orEmpty()
}
