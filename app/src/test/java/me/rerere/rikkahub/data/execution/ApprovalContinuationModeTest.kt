package me.rerere.rikkahub.data.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The closed vocabulary for `pending_tool_approvals.continuation_mode`.
 *
 * This is where the column's closure actually lives. Room 2.8.4 cannot declare a `CHECK`, so
 * SQLite will store any string a raw `INSERT` hands it; what makes the vocabulary closed in
 * practice is that the enum is the only thing that produces a value the app writes, and that
 * reading one has no default to fall back to.
 *
 * The failure that matters is specific and asymmetric. Falling back to `RESUME_COMMAND` for a
 * value nobody can read would be *silently plausible* — the approval would still be granted, and
 * then a resume command would be created for a generation that, if the unreadable value really was
 * `IN_FLIGHT`, is still blocked inside the Worker waiting for an answer. That is a second
 * generation nobody asked for, which is the exact outcome the mode exists to prevent. So an
 * unreadable value must fail, not default.
 */
class ApprovalContinuationModeTest {

    @Test
    fun `the vocabulary is exactly the two states`() {
        assertEquals(
            listOf("RESUME_COMMAND", "IN_FLIGHT"),
            ApprovalContinuationMode.entries.map { it.name },
        )
    }

    @Test
    fun `both states round-trip through their wire spelling`() {
        ApprovalContinuationMode.entries.forEach { mode ->
            assertEquals(mode.name, mode.toWire())
            assertEquals(mode, ApprovalContinuationMode.fromWireOrNull(mode.toWire()))
            assertEquals(mode, ApprovalContinuationMode.fromWire(mode.toWire()))
        }
    }

    @Test
    fun `an unreadable value never falls back to RESUME_COMMAND`() {
        // The whole point, stated once. Every entry below is a near miss a real database could
        // plausibly hold — a lower-cased writing, a padded one, a value from a future build, or
        // this schema's own bootstrap sentinel — and no reader may answer "RESUME_COMMAND" for any
        // of them, because a fallback is indistinguishable from a correct read.
        val unreadable = listOf<String?>(
            null,
            "",
            " ",
            "\t",
            "resume_command",
            "Resume_Command",
            "RESUME_COMMAND ",
            " RESUME_COMMAND",
            "in_flight",
            "In_Flight",
            "IN-FLIGHT",
            "IN_FLIGHT ",
            "UNKNOWN",
            "RESUME_COMMAND_2",
            "SENTINEL",
        )

        unreadable.forEach { candidate ->
            assertNull(
                "<$candidate> narrowed to a mode instead of failing",
                ApprovalContinuationMode.fromWireOrNull(candidate),
            )
            assertThrows(IllegalArgumentException::class.java) {
                ApprovalContinuationMode.fromWire(candidate)
            }
        }
    }
}
