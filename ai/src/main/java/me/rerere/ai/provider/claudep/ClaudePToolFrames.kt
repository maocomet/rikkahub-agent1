package me.rerere.ai.provider.claudep

import me.rerere.ai.provider.claudep.bridge.BridgeBinding
import me.rerere.ai.provider.claudep.bridge.BridgeLimits
import me.rerere.ai.provider.claudep.bridge.ToolCallOutcome
import me.rerere.ai.provider.claudep.bridge.ToolCallState

/**
 * The tool-call states of the **public wire protocol**, in their snake_case spelling.
 *
 * This is deliberately a second enum rather than a re-use of the bridge contract's
 * [ToolCallState]. The two vocabularies are the same nine values, but they are not the same
 * type: one is what the Server writes on the wire, the other is what the bridge contract
 * compares, and the mapping between them happens once on the Server. Collapsing them here would
 * put a wire string and a contract value in one variable, and the first place that would show up
 * is a validator that accepts a spelling the peer never sends.
 */
enum class ClaudePToolCallState(val wireValue: String) {
    PENDING("pending"),
    COMPLETED("completed"),
    DENIED("denied"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    TIMED_OUT("timed_out"),
    NOT_FOUND("not_found"),
    CONFLICT("conflict"),
    DISCONNECTED("disconnected"),

    /**
     * A state string this build does not know.
     *
     * Not a tenth contract state — the contract's set is closed and total — but a **local**
     * sentinel for "the Server wrote something this build cannot name". It exists so an unknown
     * spelling narrows to a value rather than throwing, and it is deliberately *not* terminal
     * and *not* reportable, so every path that could act on it fails closed instead.
     */
    UNKNOWN("");

    companion object {
        private val byWire = entries.filter { it != UNKNOWN }.associateBy { it.wireValue }

        fun fromWire(value: String?): ClaudePToolCallState =
            value?.let { byWire[it] } ?: UNKNOWN
    }
}

/**
 * True when no further answer will arrive for this call.
 *
 * [ClaudePToolCallState.UNKNOWN] is deliberately not terminal: a state this build could not
 * name is a state it must not conclude anything from, and treating it as finished would end a
 * call on the strength of a string nobody recognised.
 */
fun ClaudePToolCallState.isTerminal(): Boolean =
    this != ClaudePToolCallState.PENDING && this != ClaudePToolCallState.UNKNOWN

/**
 * True for the states Android may put in an outcome.
 *
 * The same five the bridge contract allows, and for the same reason: `pending` would be a call
 * that never terminates, and `disconnected`, `not_found` and `conflict` are verdicts about the
 * *Server's* ledger, which Android does not hold.
 */
fun ClaudePToolCallState.isAndroidReportable(): Boolean = when (this) {
    ClaudePToolCallState.COMPLETED,
    ClaudePToolCallState.DENIED,
    ClaudePToolCallState.FAILED,
    ClaudePToolCallState.CANCELLED,
    ClaudePToolCallState.TIMED_OUT,
    -> true

    ClaudePToolCallState.PENDING,
    ClaudePToolCallState.NOT_FOUND,
    ClaudePToolCallState.CONFLICT,
    ClaudePToolCallState.DISCONNECTED,
    ClaudePToolCallState.UNKNOWN,
    -> false
}

/** The bridge-contract state this wire state denotes, or `null` for [ClaudePToolCallState.UNKNOWN]. */
fun ClaudePToolCallState.toBridgeState(): ToolCallState? = when (this) {
    ClaudePToolCallState.PENDING -> ToolCallState.PENDING
    ClaudePToolCallState.COMPLETED -> ToolCallState.COMPLETED
    ClaudePToolCallState.DENIED -> ToolCallState.DENIED
    ClaudePToolCallState.FAILED -> ToolCallState.FAILED
    ClaudePToolCallState.CANCELLED -> ToolCallState.CANCELLED
    ClaudePToolCallState.TIMED_OUT -> ToolCallState.TIMED_OUT
    ClaudePToolCallState.NOT_FOUND -> ToolCallState.NOT_FOUND
    ClaudePToolCallState.CONFLICT -> ToolCallState.CONFLICT
    ClaudePToolCallState.DISCONNECTED -> ToolCallState.DISCONNECTED
    ClaudePToolCallState.UNKNOWN -> null
}

/** The wire spelling of a bridge-contract state. */
fun ToolCallState.toWireState(): ClaudePToolCallState =
    ClaudePToolCallState.fromWire(wire)

/** Why an outbound tool frame must not be sent. A closed set; never carries the value. */
enum class ClaudePToolFrameRefusal {
    /** The call id is empty, over-long, or carries a control character. */
    CALL_ID_INVALID,

    /** The state is not one Android is permitted to report. */
    STATE_NOT_REPORTABLE,

    /** A body was supplied for a state that cannot carry one. */
    BODY_NOT_ALLOWED_FOR_STATE,

    /** The result body is larger than the Server will accept. */
    RESULT_TOO_LARGE,
}

/** The verdict on one outbound frame. */
sealed interface ClaudePToolFrameVerdict {
    data object Sendable : ClaudePToolFrameVerdict
    data class Refused(val reason: ClaudePToolFrameRefusal) : ClaudePToolFrameVerdict
}

/**
 * The wire rules for the frames Android **sends**.
 *
 * These are checked here rather than left to the Server, because the Server's answer to a
 * malformed outcome is to close the connection — a frame this build invented is a bug, not a
 * late arrival. Refusing locally turns "the app has a bug" into "one tool call was not
 * reported" instead of "the session ended".
 *
 * The rules are the Server's, restated:
 *
 * - the state must be one of the five Android may report;
 * - a body is **only** legal on `completed` — a denial carrying output is a caller that meant
 *   to send something and sent it for the wrong state;
 * - the body is bounded by the same byte limit the Server enforces.
 *
 * Nothing here inspects a body's *content*. A result body is opaque: a tool that returns a
 * token — a credential inspector, a secret manager reading back what it stored — is an ordinary
 * tool doing what it was asked, and the isolation that matters is architectural rather than a
 * scan.
 */
object ClaudePToolFrames {

    /**
     * Checks an outbound `tool.result` body before it is sent.
     *
     * @return [ClaudePToolFrameVerdict.Sendable] or a refusal naming the rule.
     */
    fun validateOutboundResult(body: ClaudePToolResultBody): ClaudePToolFrameVerdict {
        if (!BridgeBinding.isToolCallId(body.toolCallId)) {
            return ClaudePToolFrameVerdict.Refused(ClaudePToolFrameRefusal.CALL_ID_INVALID)
        }

        val state = ClaudePToolCallState.fromWire(body.state)
        if (!state.isAndroidReportable()) {
            return ClaudePToolFrameVerdict.Refused(ClaudePToolFrameRefusal.STATE_NOT_REPORTABLE)
        }

        val result = body.body
        if (result == null) return ClaudePToolFrameVerdict.Sendable

        if (state != ClaudePToolCallState.COMPLETED) {
            return ClaudePToolFrameVerdict.Refused(
                ClaudePToolFrameRefusal.BODY_NOT_ALLOWED_FOR_STATE,
            )
        }
        if (result.toByteArray(Charsets.UTF_8).size > BridgeLimits.MAX_TOOL_RESULT_BYTES) {
            return ClaudePToolFrameVerdict.Refused(ClaudePToolFrameRefusal.RESULT_TOO_LARGE)
        }
        return ClaudePToolFrameVerdict.Sendable
    }

    /**
     * The frame that reports a bridge outcome, or `null` when it must not be sent.
     *
     * Returning `null` rather than a refusal is the shape the call sites want: an outcome that
     * cannot be reported is dropped and logged, and a caller that had to handle a sealed verdict
     * at every call site would eventually handle it by sending anyway.
     */
    fun asOutboundResult(outcome: ToolCallOutcome): ClaudePToolResultBody? {
        val body = ClaudePToolResultBody(
            toolCallId = outcome.toolCallId,
            state = outcome.state.wire,
            body = outcome.body,
        )
        return if (validateOutboundResult(body) is ClaudePToolFrameVerdict.Sendable) body else null
    }

    /**
     * True when a `tool.query.result` reports a terminal.
     *
     * A non-terminal answer — `pending`, or a state this build cannot name — means the call is
     * still the Server's to conclude, so the app must keep waiting rather than guessing. An
     * unknown state is treated as non-terminal on purpose: concluding from a state we could not
     * name is exactly the guess the closed vocabulary exists to prevent.
     */
    fun isTerminalQueryResult(body: ClaudePToolQueryResultBody): Boolean =
        body.safeState.isTerminal()
}
