package me.rerere.ai.provider.claudep

import kotlinx.serialization.json.JsonElement

/**
 * Where one tool call has got to, as a fact this module can state without knowing how it is shown.
 *
 * ## Why this is not the wire vocabulary
 *
 * These are **not** `ToolCallState`. That type is the frozen contract's, it is what travels, and
 * it is deliberately small: five reportable outcomes plus the states Android may not send. This one
 * is the app-facing story of the same call — it has `PENDING_APPROVAL`, which the wire has no word
 * for because an approval is Android's business and the Server is not told about it, and it lacks
 * the Server's own verdicts, because nothing here may pronounce one.
 *
 * Keeping them apart is what stops a convenience like "just reuse the wire states" from later
 * turning an approval step into something the peer can name.
 *
 * ## Why a call needs a status channel at all
 *
 * A Claude P tool call arrives **inside a live stream**, with the peer blocked on its answer. A
 * tool that needs the user's approval cannot be run, and cannot be answered, until someone taps —
 * and the card they tap has to be in the conversation *before* the wait begins, or there is
 * nothing to tap and the call sits until its deadline. So the host that runs the call needs a way
 * to put something in front of the user while it is still inside `execute`, and that is what this
 * vocabulary and its sink are for.
 *
 * ## Ordering, which is the whole point
 *
 * An implementation that needs approval must do these **in this order**, and the order is not
 * advisory:
 *
 * 1. create the approval's exact identity;
 * 2. publish the pending status through the sink, and **require acceptance**;
 * 3. only then register with (or obtain) the waiter that will receive the decision;
 * 4. wait for the decision;
 * 5. only after an approval, run the tool.
 *
 * A rejection at step 2 means nothing was shown, so nothing can be tapped, so the caller must fail
 * closed and run nothing — [ClaudePToolStatusPublication.Refused] exists to make that a value the
 * caller has to handle rather than a failure it can ignore.
 */
enum class ClaudePToolCallStatus {
    /** The call is in front of the user and cannot proceed without their decision. */
    PENDING_APPROVAL,

    /** The user approved. The caller may run the tool. */
    APPROVED,

    /** The user refused. The caller must run nothing and report the call denied. */
    DENIED,

    /** The call is running. */
    RUNNING,

    /** The call reached a terminal the caller has already recorded. */
    COMPLETED,

    /** The call did not run, or ran and failed. Claims nothing about a stop. */
    FAILED,

    /** A stop was requested **and the runtime proved the call is over**. */
    CANCELLED,
}

/**
 * One status, in terms that carry no UI type, no database and no wire value.
 *
 * The arguments are the call's own, verbatim, so whatever renders this is rendering what the peer
 * asked for rather than a restatement of it. Nothing here is a secret: the arguments are already
 * travelling on the wire in the same generation, and this type is what the app's own approval
 * machinery needs to show the user what they are approving.
 */
data class ClaudePToolStatusUpdate(
    val toolCallId: String,
    /** The name the **runtime** knows, which is the app's own tool name and not the frozen one. */
    val toolName: String,
    val arguments: JsonElement,
    val status: ClaudePToolCallStatus,
)

/** What publishing one status did. A closed set, so an unhandled failure is a compile error. */
sealed interface ClaudePToolStatusPublication {
    /** The status reached the user. */
    data object Accepted : ClaudePToolStatusPublication

    /**
     * The status did **not** reach the user, and nothing was shown.
     *
     * Fail-closed: a caller that needed approval must run nothing. [localReason] is a stable
     * spelling for diagnostics and never travels.
     */
    data class Refused(val localReason: String) : ClaudePToolStatusPublication
}

/**
 * Where the host puts a tool call's status so the user can act on it.
 *
 * Implemented by whoever owns the conversation the call belongs to — this module declares the
 * question, the app answers it, and neither learns the other's shape.
 */
fun interface ClaudePToolStatusSink {
    suspend fun publish(update: ClaudePToolStatusUpdate): ClaudePToolStatusPublication

    companion object {
        /**
         * The sink that accepts everything and shows nothing.
         *
         * The correct default for a host that needs no approval — the text path and the inert
         * host — because a call that never has to be shown has nothing that can fail to be shown.
         */
        val NONE: ClaudePToolStatusSink =
            ClaudePToolStatusSink { ClaudePToolStatusPublication.Accepted }
    }
}
