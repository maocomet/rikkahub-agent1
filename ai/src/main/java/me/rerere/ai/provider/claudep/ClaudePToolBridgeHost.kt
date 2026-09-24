package me.rerere.ai.provider.claudep

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.claudep.bridge.BridgeCatalog
import me.rerere.ai.provider.claudep.bridge.BridgeExecutionHost
import me.rerere.ai.provider.claudep.bridge.BridgeInvocation
import me.rerere.ai.provider.claudep.bridge.FrozenCatalog
import me.rerere.ai.provider.claudep.bridge.ToolCallOutcome
import me.rerere.ai.provider.claudep.bridge.ToolCallState
import me.rerere.ai.ui.UIMessagePart

/**
 * The app's half of the tool bridge.
 *
 * ## Why this is an interface, and what it is not
 *
 * The provider knows how to *carry* a tool call: which generation it belongs to, whether it is a
 * repeat, what Android concluded, and how to say that back on the wire. It does not know how to
 * run anything, and it must not learn. Running a tool means the app's own `DefaultToolRuntime`,
 * its own approval lifecycle and its own `McpManager` — and a second implementation of any of
 * those, living here, is exactly the thing the M2 gate forbids by name.
 *
 * So this is the whole surface between them, and it is deliberately three members wide. Each one
 * is a question the app already knows the answer to; none of them hands this layer a copy of a
 * mechanism it should not have.
 *
 * ## Why the default proves nothing
 *
 * [NONE] returns an empty catalog, so no `tool_snapshot` is sent and no `tool.invoke` can ever
 * arrive. That is not a stub that happens to be inert — it is the **text path**, described
 * exactly: a provider with no bridge host behind it behaves the way it did before this existed.
 * An empty catalog means "no tools", never "all tools", and there is no state in which the
 * provider forwards a tool call to something that cannot answer it.
 */
interface ClaudePToolBridgeHost {

    /**
     * Everything this generation needs before it is dispatched.
     *
     * Called once, before `generation.start`, because the catalog has to be in that frame — the
     * Server freezes it and will not accept a tool the catalog did not name later on.
     *
     * The `readOnly` claim on each entry is the app's to make and this layer's to carry
     * unexamined. That is the whole reason the catalog is assembled here rather than derived from
     * [Tool]s in this module: only the assembly layer can say whether a tool is safe to run
     * without asking the user. An empty catalog means no tools, and it is also what a failure to
     * build one must degrade to — a catalog that could not be assembled is an empty catalog,
     * never a permissive one.
     */
    suspend fun prepare(tools: List<Tool>): ClaudePToolPreparation

    /**
     * Runs one admitted invocation to a terminal, through the app's existing runtime.
     *
     * Called **after** the bridge has recorded the call, so a repeat arriving while this is still
     * running attaches to that record rather than starting a second execution.
     *
     * It must not throw. A tool that failed is a `failed` outcome, not an exception: the caller
     * has a peer blocked on an answer, and an exception here would leave the call unanswered
     * until its deadline. An implementation that cannot produce an outcome must say so with
     * [ToolCallState.FAILED], which claims nothing.
     */
    suspend fun execute(invocation: BridgeInvocation): BridgeToolExecution

    /**
     * The execution host a closing generation uses to stop what is running and to find out what
     * really happened.
     *
     * Handed to the registry at construction, so a generation that ends asks the runtime for a
     * real cancellation and settles on a real conclusion instead of assuming that a turn ending
     * stopped anything.
     */
    val executions: BridgeExecutionHost

    companion object {
        /**
         * The host that has no tools, runs nothing and can prove nothing.
         *
         * Its catalog is empty, so the provider sends no `tool_snapshot` and the Server never
         * registers a bridge tool — which makes [execute] unreachable rather than merely unused,
         * and leaves the bytes of `generation.start` exactly as they were.
         */
        val NONE: ClaudePToolBridgeHost = object : ClaudePToolBridgeHost {
            override suspend fun prepare(tools: List<Tool>): ClaudePToolPreparation =
                ClaudePToolPreparation.NONE

            override suspend fun execute(invocation: BridgeInvocation): BridgeToolExecution =
                BridgeToolExecution(
                    // Unreachable while the catalog is empty. If a wiring bug ever did reach it,
                    // `failed` is the honest answer: it claims no execution and no stop.
                    outcome = ToolCallOutcome(
                        toolCallId = invocation.binding.toolCallId,
                        state = ToolCallState.FAILED,
                    ),
                    part = null,
                )

            override val executions: BridgeExecutionHost = BridgeExecutionHost.NONE
        }
    }
}

/**
 * What the app hands the provider before a generation is dispatched.
 *
 * The identity fields are the app's because the app is the only side that knows which assistant
 * and conversation this turn belongs to. They are never sent anywhere: they exist so that a tool
 * call is bound to the conversation it came from, and so that a frame naming a different one is
 * refused rather than answered.
 */
data class ClaudePToolPreparation(
    val deviceRef: String,
    val assistantId: String,
    val conversationId: String,
    val branchId: String,
    /** How long one call may stay pending. Carried as a duration; see `GenerationBinding`. */
    val timeoutMs: Long,
    /** The frozen catalog. Empty means no tools, and no `tool_snapshot` is sent. */
    val catalog: FrozenCatalog,
    /**
     * The `tool_snapshot` body to send, or `null` for the text path.
     *
     * Carried rather than derived from [catalog] here because assembling one from the other is
     * the catalog builder's job, and a second implementation of it would be a second answer to
     * "what bytes did we freeze?" — which is the disagreement the digest exists to catch.
     */
    val snapshot: JsonObject?,
) {
    companion object {
        /** No tools, and therefore no snapshot. */
        val NONE: ClaudePToolPreparation = ClaudePToolPreparation(
            deviceRef = "",
            assistantId = "",
            conversationId = "",
            branchId = "",
            timeoutMs = 0L,
            catalog = BridgeCatalog.EMPTY,
            snapshot = null,
        )
    }
}

/**
 * What one execution produced.
 *
 * The [part] is separate from the [outcome] because the two answer different questions and only
 * one of them is the bridge's business. The outcome goes to the Server's ledger and is bounded by
 * the frozen contract; the part is what the user sees in the conversation, carries whatever
 * approval state the app decided on, and is shaped entirely by the app.
 *
 * A `null` part means "show nothing", which is a legitimate answer for a call the user never
 * asked about — but it is not a missing outcome, and the caller still reports one.
 */
data class BridgeToolExecution(
    val outcome: ToolCallOutcome,
    val part: UIMessagePart.Tool? = null,
)
