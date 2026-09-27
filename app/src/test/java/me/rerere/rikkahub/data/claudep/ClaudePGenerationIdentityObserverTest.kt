package me.rerere.rikkahub.data.claudep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generation-id rule: one dispatch, one id, handed over once.
 *
 * This is the seam a `deferred` generation's `BIND_PENDING` write depends on — the app must hold
 * the Server's generation id before the transaction that commits the branch variant, and this is
 * where it arrives. Getting it wrong is not a display bug: it is either a branch bound to a
 * generation that never produced it, or a generation that ran whose candidate is never bound.
 */
class ClaudePGenerationIdentityObserverTest {

    private val accepted = mutableListOf<String>()

    private fun observer() = ClaudePGenerationIdentityObserver { accepted += it }

    @Test
    fun `the first id is handed over exactly once`() {
        val observer = observer()

        observer.observe("gen-1")
        observer.observe("gen-1")
        observer.observe("gen-1")

        assertEquals(listOf("gen-1"), accepted)
        assertTrue(observer.hasIdentity)
    }

    /** Every generation that carries no bind obligation stamps nothing, and must be ignored. */
    @Test
    fun `a chunk with no id is ignored`() {
        val observer = observer()

        observer.observe(null)
        observer.observe(null)

        assertTrue(accepted.isEmpty())
        assertFalse(observer.hasIdentity)
    }

    @Test
    fun `an id after idless chunks is still handed over`() {
        val observer = observer()

        observer.observe(null)
        observer.observe("gen-1")

        assertEquals(listOf("gen-1"), accepted)
    }

    /**
     * A second, different id is a second generation — a fallback or a retry, which the turn runner
     * issues as its own `generation.start`. The observer refuses rather than choosing: whichever
     * one it picked, the other would be a candidate the app could never bind.
     */
    @Test
    fun `a second different id fails closed and hands over nothing further`() {
        val observer = observer()
        observer.observe("gen-1")

        val failure = assertThrows(IllegalStateException::class.java) {
            observer.observe("gen-2")
        }

        assertEquals("claude_p_generation_identity_conflict", failure.message)
        assertEquals(listOf("gen-1"), accepted)
    }

    /** A repeat of the same id is a replayed event, not a second generation. */
    @Test
    fun `a repeat of the same id does not fail`() {
        val observer = observer()

        observer.observe("gen-1")
        observer.observe("gen-1")
        observer.observe(null)
        observer.observe("gen-1")

        assertEquals(listOf("gen-1"), accepted)
    }

    /** Nothing is handed over before an id arrives, so a caller cannot bind on a guess. */
    @Test
    fun `no id is handed over before one is observed`() {
        val observer = observer()

        assertFalse(observer.hasIdentity)
        assertTrue(accepted.isEmpty())
    }
}
