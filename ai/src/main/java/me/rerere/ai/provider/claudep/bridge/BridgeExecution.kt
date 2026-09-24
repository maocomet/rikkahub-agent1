package me.rerere.ai.provider.claudep.bridge

/**
 * What the bridge needs from whatever is *actually* running a tool call.
 *
 * ## Why this is a port and not an implementation
 *
 * The bridge decides *which* call an inbound frame is and whether it may run. It does not run
 * anything, and it must not learn how to: the real work belongs to the app's existing
 * `DefaultToolRuntime`, its existing approval lifecycle and its existing `McpManager`, and a
 * second implementation of any of those is the thing the M2 gate forbids by name.
 *
 * So the bridge is given one narrow question it can ask — "can you prove this call is over?" —
 * and the app answers it from the real execution it owns. There is no queue, no job table and
 * no tool history here: the app looks up a handle it already holds because *it* started the
 * call, and this side never holds one at all.
 *
 * ## Why every method is synchronous and bounded
 *
 * A generation ends on a path that is itself shutting down, so a suspend function here would
 * be called from a scope that may already be cancelled — and a cancellation thrown at the
 * moment of conclusion is how a conclusion gets skipped and a record left pending forever.
 * The wait is bounded by [BridgeClosing.DEFAULT_STOP_WAIT_MS] and the caller is expected to
 * invoke it off the main thread; nothing here can block indefinitely.
 *
 * ## Why [NONE] is the default
 *
 * It proves nothing and stops nothing, which makes every call it is asked about conclude
 * `failed`. That is the conservative answer, and it is the correct one for a bridge that has
 * no executor behind it: an Android that cannot prove it stopped a tool must not say it did.
 */
interface BridgeExecutionHost {

    /**
     * Closes or rejects whatever approval UI is waiting for this call.
     *
     * Called before any stop is requested, because an approval prompt for a turn that has
     * ended is a prompt whose answer would arrive with nowhere to go — and a user who taps
     * "approve" on it must not be able to start a tool for a generation that is already gone.
     */
    fun abandonApproval(generationId: String, toolCallId: String)

    /**
     * Asks the runtime to stop these calls, through the runtime's own cancellation capability.
     *
     * Called once, for every call still pending, **before** [awaitConclusions]. Whether the
     * request can be honoured is the runtime's business and is reported by that method's
     * answer, not by this one: a stop *request* is not a stop.
     */
    fun requestStop(generationId: String, toolCallIds: List<String>)

    /**
     * Waits, bounded, for real conclusions.
     *
     * Returns only what the runtime can **prove**: a call whose execution reached a terminal,
     * or one that was refused at the approval gate. A call that is absent from the returned map
     * is one this host cannot say anything about — the handle was never held, it was lost, or
     * the wait elapsed first — and the bridge concludes those `failed` rather than inventing a
     * stop that it never observed.
     *
     * A returned state must be one of [ANDROID_REPORTABLE_TOOL_CALL_STATES]; anything else is
     * ignored, because a host that reports `pending` or one of the Server's own verdicts is
     * reporting something the bridge may not pass on.
     */
    fun awaitConclusions(
        generationId: String,
        toolCallIds: List<String>,
        waitMs: Long,
    ): Map<String, ToolCallOutcome>

    companion object {
        /**
         * The host that can prove nothing and stop nothing.
         *
         * Every pending call ends `failed` with [BridgeCloseReason.STOP_UNPROVEN]. That is a
         * real answer and a safe one: the user loses a tool call and cannot lose a side effect
         * they were not told about.
         */
        val NONE: BridgeExecutionHost = object : BridgeExecutionHost {
            override fun abandonApproval(generationId: String, toolCallId: String) = Unit

            override fun requestStop(generationId: String, toolCallIds: List<String>) = Unit

            override fun awaitConclusions(
                generationId: String,
                toolCallIds: List<String>,
                waitMs: Long,
            ): Map<String, ToolCallOutcome> = emptyMap()
        }
    }
}

/** Bounds local to closing a generation. None of these travel; none are contract values. */
object BridgeClosing {
    /**
     * How long a close waits for the runtime to prove a stop.
     *
     * Short on purpose. This runs while a generation is shutting down, and the alternative to
     * waiting is not "the tool keeps running" — it is "the tool keeps running and Android says
     * `failed` instead of pretending". A longer wait buys a nicer report at the cost of holding
     * the shutting-down path open, and the honest report is already available.
     */
    const val DEFAULT_STOP_WAIT_MS = 2_000L

    /**
     * How long a closed generation id is remembered, so it can never be reopened.
     *
     * This is not an arbitrary number: it is exactly the longest a tool call may remain pending
     * (`MAX_DEADLINE_MS`). A generation that has been closed can only be reopened by a peer
     * re-delivering one of its frames, and the Server stops waiting on a call at that call's own
     * deadline — so after this window there is no re-delivery left to guard against, and the
     * tombstone has done its whole job.
     */
    const val CLOSED_GENERATION_TTL_MS = BridgeLimits.MAX_DEADLINE_MS
}

/**
 * Why a call that a closing generation was holding ended the way it did.
 *
 * A closed set with stable spellings. These are **local**: they are written to diagnostics and
 * never to a frame, because the contract's vocabulary describes what Android concluded about a
 * tool, and "we could not prove a stop" is not a tool's outcome.
 */
enum class BridgeCloseReason(val wire: String) {
    /**
     * The runtime reached a terminal of its own and the outcome is recorded verbatim.
     *
     * Covers a call that completed, one that failed, one the user refused, one a cancel really
     * did stop, and one whose deadline elapsed — the state already says which. What this reason
     * adds is the claim that the *runtime* said so.
     */
    RUNTIME_CONCLUDED("runtime_concluded"),

    /**
     * The call was still pending and no stop could be proven.
     *
     * Reported `failed`. It is the answer for every shape of "we do not know": no handle was
     * ever held, a handle was lost, the execution was interrupted by the generation ending, or
     * the bounded wait elapsed before anything came back. Reported as `cancelled` it would be a
     * claim about a tool that may still be running and may still have a side effect, which is
     * precisely what this reason exists to refuse.
     */
    STOP_UNPROVEN("stop_unproven"),
}

/**
 * One call a closing generation was holding, and what Android concluded about it.
 *
 * Both halves are needed by the caller and neither implies the other: the [outcome] is what
 * goes to the Server's ledger, and the [reason] is what tells the app whether it is holding a
 * real result or admitting it could not prove one.
 */
data class BridgeClosedCall(
    val toolCallId: String,
    val outcome: ToolCallOutcome,
    val reason: BridgeCloseReason,
)
