package me.rerere.rikkahub.data.claudep

import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Which Claude P continuation each admitted command owes, for as long as that command is running.
 *
 * ## Why the decision has to be carried rather than recomputed
 *
 * The gate's decision is computed **once**, at admission, from the graph the admission transaction
 * is about to commit. Every later use of it — the settlement attached to the run, the binding
 * request the dispatch carries — must be about *that* decision, and recomputing it later would be
 * recomputing it from a graph that has since moved. The barrier the admission wrote is itself a
 * change to that graph, so a second reading does not only risk disagreeing: it reliably does, and
 * the disagreement is always in the direction of "this branch is fine, dispatch it".
 *
 * ## Why this is process-local and not durable
 *
 * Nothing here is a fact about the branch. The durable facts are all in the graph — the barrier,
 * its revision, its state — and they are written by the admission transaction. This is the
 * *run-local* half: which decision the run that is currently executing was admitted under. A
 * process that dies loses it, which is correct, because a run that died has no continuation to
 * settle and the next admission reads the branch's state from the graph instead.
 *
 * ## Why a duplicate is not an error
 *
 * The durable queue may admit the same command more than once, and the later admission re-persists
 * the graph through the same mutation. Keeping the first decision is what makes that safe: the
 * second admission re-writes the *same* barrier, rather than deriving a fresh decision from a graph
 * that now already carries one and refusing the command it already admitted.
 *
 * Thread-safe, because admission runs on the submit path and the decision is read from the run.
 */
class ClaudePSessionContinuationAdmissions {

    private val byCommand =
        ConcurrentHashMap<Uuid, ClaudePSessionContinuationGate.Decision>()

    /**
     * Records [decision] for [commandId], keeping the first one recorded.
     *
     * A later record is not an error and not an overwrite: the second admission of one command is
     * the same admission, and a different decision for it would mean the two reads disagreed about
     * a branch that only one of them wrote.
     *
     * @return the decision now recorded — the first, whichever call recorded it.
     */
    fun record(
        commandId: Uuid,
        decision: ClaudePSessionContinuationGate.Decision,
    ): ClaudePSessionContinuationGate.Decision =
        byCommand.putIfAbsent(commandId, decision) ?: decision

    /** The decision this command was admitted under, or `null` when it was admitted under none. */
    fun find(commandId: Uuid?): ClaudePSessionContinuationGate.Decision? {
        if (commandId == null) return null
        return byCommand[commandId]
    }

    /** Forgets a command whose run has ended. */
    fun forget(commandId: Uuid?) {
        if (commandId == null) return
        byCommand.remove(commandId)
    }

    /** How many commands are currently carried. For diagnostics and tests. */
    val size: Int get() = byCommand.size
}

/**
 * The admission stopped before any command row or barrier was written, and whether the runtime may
 * carry on.
 *
 * ## Why this is an exception and not a return value
 *
 * Admission is a `RuntimeCommandAdmissionGraphProvider` call: its contract is "return the graph to
 * commit", and it has no vocabulary for "commit nothing". A stop therefore has to leave through the
 * only channel that survives a `Result`-returning caller — and the caller already treats any
 * failure as a rejected command, which is exactly the fail-visible answer the brief requires.
 *
 * ## Why the caller is told whether to pause
 *
 * A **reconciled** stop is a repair that succeeded: the stranded branch is now `INTERRUPTED`, the
 * command was refused, and the next attempt is a fresh admission against a graph this build can
 * read. Pausing there would turn a recovered state into a stopped queue.
 *
 * A **failed** reconciliation is the opposite: the graph still says a model may be running on a
 * branch no run owns, and this build could not write the record that would fix it. Continuing would
 * dispatch onto exactly the state the barrier exists to prevent, so the queue pauses and waits for
 * a human rather than for a retry.
 */
class ClaudePSessionAdmissionStop(
    val pauseQueue: Boolean,
    message: String,
) : IllegalStateException(message) {

    companion object {
        /**
         * A stranded start was superseded and **this** operation stops.
         *
         * The reconciliation is the whole of what this admission did: it repaired a branch and
         * dispatched nothing. `generation.start` is zero by construction, not by a later check.
         */
        fun reconciled(): ClaudePSessionAdmissionStop =
            ClaudePSessionAdmissionStop(
                pauseQueue = false,
                message = "claude_p_stale_start_reconciled",
            )

        /** The stranded start could not be superseded. Nothing may be dispatched. */
        fun reconciliationFailed(reason: String): ClaudePSessionAdmissionStop =
            ClaudePSessionAdmissionStop(
                pauseQueue = true,
                message = "claude_p_stale_start_reconciliation_failed:$reason",
            )
    }
}
