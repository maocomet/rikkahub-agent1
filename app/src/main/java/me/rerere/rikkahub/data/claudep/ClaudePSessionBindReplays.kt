package me.rerere.rikkahub.data.claudep

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import me.rerere.ai.provider.claudep.ClaudePConnectionEpoch

/**
 * The `(generationId, connectionEpoch)` pairs a bind replay has already been attempted on.
 *
 * ## The rule this implements
 *
 * §6.1 permits a bind to be re-sent, but **at most once per active connection**. The two halves of
 * that are different rules and both matter:
 *
 * - **per `generationId`** — a replay re-sends *the same* bind, never a fresh one. Two different
 *   generation ids are two different bind obligations, and each gets its own single attempt.
 * - **per `connectionEpoch`** — a replay is a recovery for a bind whose answer was lost *with a
 *   connection*. Re-sending it on the connection that already carried it is not a recovery; it is a
 *   repeat the Server has to recognise and deduplicate, which is a wasted round trip against an
 *   endpoint that is under no obligation to answer twice.
 *
 * ## Why a new connection re-opens the right
 *
 * The epoch moves when a handshake completes, so a bind still `INTERRUPTED` after the socket came
 * back is **exactly** the case replay exists for, and it becomes attemptable again on the new
 * connection. Refusing it there would leave the branch closed for no reason — the first attempt's
 * answer really was lost, and nothing about it can be recovered except by asking again.
 *
 * ## Why an absent epoch refuses
 *
 * `null` means no carried connection. A caller that cannot name one cannot decide "once per
 * connection", and the two possible readings are not symmetric: replaying anyway risks a repeat on
 * an unknown connection, while refusing costs one recovery attempt that a later user action makes
 * again. So it refuses.
 *
 * ## Why this is process-local
 *
 * It records what *this process* already sent. A process that dies forgets, which is correct: the
 * record of the obligation is durable (the `INTERRUPTED` record with its generation id), and the
 * connection it was sent on did not survive either.
 */
class ClaudePSessionBindReplays {

    private val consumed = ConcurrentHashMap<String, ClaudePConnectionEpoch>()

    /**
     * Claims the single replay attempt for [generationId] on [epoch].
     *
     * @return `true` when this is the first attempt for that pair — the caller may send. `false`
     *   when the same pair has already been attempted, or when [epoch] is `null` and the question
     *   cannot be answered at all.
     */
    fun claim(generationId: String, epoch: ClaudePConnectionEpoch?): Boolean {
        if (epoch == null) return false
        val claimed = AtomicBoolean(false)
        consumed.compute(generationId) { _, previous ->
            if (previous == epoch) {
                previous
            } else {
                claimed.set(true)
                epoch
            }
        }
        return claimed.get()
    }

    /** Forgets a bind that no longer owes a replay. */
    fun forget(generationId: String) {
        consumed.remove(generationId)
    }

    /** How many binds are tracked. For diagnostics and tests. */
    val size: Int get() = consumed.size
}

/**
 * The named refusals a bind replay can produce.
 *
 * Each is returned upward as the reason the user's own command was rejected — dispatch zero, the
 * failure identifiable, the user told why — never as an exception on a path they reached through
 * ordinary use. They are separate codes because they send a reader somewhere different: a repeat on
 * this connection, a Server answer that closed the branch, and an outcome that is still unknown.
 */
object ClaudePSessionReplayRefusal {

    /**
     * This `(generationId, connectionEpoch)` pair was already attempted, or no connection could be
     * named. The branch is unchanged and a later connection may try again.
     */
    const val NOT_PERMITTED = "claude_p_replay_not_permitted"

    /** The Server settled the bind in the negative. The branch is closed; a retry would not help. */
    const val CLOSED = "claude_p_replay_closed"

    /** The outcome is still unknown. The branch stays `INTERRUPTED` with its generation id. */
    const val UNPROVEN = "claude_p_replay_unproven"
}
