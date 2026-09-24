package me.rerere.rikkahub.data.execution

/**
 * How an approved tool call is allowed to continue.
 *
 * ## The two states, and why they are not the same question as "approved"
 *
 * A pending approval reaches `APPROVED` in both modes, and the difference is what happens next:
 *
 * - [RESUME_COMMAND] is the existing behaviour. The generation that raised the call has already
 *   **ended** — the turn was broken so the user could be asked — so approving it creates a
 *   deterministic resume command that re-enters the normal command queue and runs the tool.
 * - [IN_FLIGHT] is the Claude P bridge behaviour. The generation has **not** ended: the peer is
 *   blocked inside the Worker waiting for this call's answer. Approving it releases the waiter
 *   that is already there and must never create a second generation — the resume command that is
 *   correct for [RESUME_COMMAND] would, here, start a run nobody asked for.
 *
 * The mode is chosen when the pending barrier is created and never changes afterwards. It is a
 * property of *who is waiting*, and that does not change between the barrier and the decision.
 *
 * ## There is no SQL-level constraint, and this file must not pretend otherwise
 *
 * The column is plain `TEXT NOT NULL DEFAULT 'RESUME_COMMAND'`. Room 2.8.4 cannot declare a
 * `CHECK`, so SQLite does not reject a third value written by raw SQL, and nothing here should be
 * described as a database-level constraint. What actually closes the vocabulary is:
 *
 * - this enum, which is the only thing that produces a value the app writes;
 * - [fromWireOrNull] and [fromWire], whose matching is exact and case-sensitive and which have no
 *   fallback — an unrecognised value is `null` or a failure, never [RESUME_COMMAND];
 * - `PendingToolApprovalRecord.continuationMode` being typed by this enum at the boundary rather
 *   than by a `String`;
 * - the cold-restore/import path, which validates every stored value and refuses the database
 *   rather than repairing or guessing one.
 *
 * A reader who needs a database-level guarantee does not have one here. That is a known,
 * deliberate limitation of the minimal implementation, not an oversight.
 */
enum class ApprovalContinuationMode {
    /** The generation is over; approval may create a deterministic resume command. */
    RESUME_COMMAND,

    /** The generation is still waiting inside the Worker; approval releases that waiter only. */
    IN_FLIGHT,
    ;

    /** The exact string this mode is stored and compared as. */
    fun toWire(): String = name

    companion object {
        /**
         * Exact, case-sensitive narrowing. `null` for anything that is not exactly one of the two
         * names — including `null` itself, then blanks, then differently-cased spellings.
         *
         * Returning `null` rather than a default is the whole point: a caller that has to handle
         * absence cannot accidentally treat an unreadable value as [RESUME_COMMAND], which would
         * re-enable the resume command this mode exists to suppress.
         */
        fun fromWireOrNull(value: String?): ApprovalContinuationMode? =
            entries.firstOrNull { it.name == value }

        /** [fromWireOrNull], with the failure made loud instead of silent. */
        fun fromWire(value: String?): ApprovalContinuationMode =
            fromWireOrNull(value)
                ?: throw IllegalArgumentException(
                    "unknown approval continuation mode; expected one of " +
                        entries.joinToString(", ") { it.name },
                )
    }
}
