package me.rerere.ai.provider.claudep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The canonical selected-variant identity.
 *
 * These assert the properties the identity has to have to be usable as a session key, not just that
 * it returns a string: determinism, order sensitivity, the length-prefix property that stops two
 * different selection vectors colliding, and a refusal to describe a malformed one.
 */
class ClaudePSessionBranchIdTest {

    private fun id(vararg pairs: Pair<String, String>): String {
        val result = ClaudePSessionBranchId.compute(
            pairs.map { ClaudePSessionBranchId.Selection(it.first, it.second) },
        )
        assertTrue("expected a valid digest, got $result", result is ClaudePSessionBranchId.Result.Valid)
        return (result as ClaudePSessionBranchId.Result.Valid).id
    }

    private fun rejection(vararg pairs: Pair<String, String>): ClaudePSessionBranchId.Reason {
        val result = ClaudePSessionBranchId.compute(
            pairs.map { ClaudePSessionBranchId.Selection(it.first, it.second) },
        )
        assertTrue("expected a rejection, got $result", result is ClaudePSessionBranchId.Result.Malformed)
        return (result as ClaudePSessionBranchId.Result.Malformed).reason
    }

    @Test
    fun `root branch has an explicit versioned digest, never the empty string`() {
        val root = id()

        assertTrue(root.isNotBlank())
        assertEquals(64, root.length)
        assertEquals(root, root.lowercase())
    }

    @Test
    fun `the same selection vector produces the same digest`() {
        assertEquals(id("n1" to "m1", "n2" to "m2"), id("n1" to "m1", "n2" to "m2"))
    }

    @Test
    fun `a different selection vector produces a different digest`() {
        assertNotEquals(id("n1" to "m1"), id("n1" to "m2"))
        assertNotEquals(id("n1" to "m1"), id())
        assertNotEquals(id("n1" to "m1", "n2" to "m2"), id("n1" to "m1"))
    }

    /**
     * Order is part of the identity: two vectors that differ only by order describe two different
     * branch selections and must not share a digest.
     */
    @Test
    fun `node order is encoded`() {
        assertNotEquals(
            id("n1" to "m1", "n2" to "m2"),
            id("n2" to "m2", "n1" to "m1"),
        )
    }

    /**
     * The whole reason labels and values are length-prefixed. Without it, `("ab","c")` and
     * `("a","bc")` would concatenate to identical bytes and two different branches would share one
     * identity — a digest two inputs share is worse than no digest at all.
     */
    @Test
    fun `adjacent fields cannot collide by concatenation`() {
        assertNotEquals(id("ab" to "c"), id("a" to "bc"))
        assertNotEquals(id("n1" to "ab", "n2" to "c"), id("n1" to "a", "n2" to "bc"))
    }

    @Test
    fun `an entry boundary cannot collide with a resized vector`() {
        assertNotEquals(id("n1" to "m1", "n2" to "m2"), id("n1" to "m1n2m2"))
    }

    /**
     * Identities may contain astral characters. The framing counts UTF-8 bytes, so a character
     * outside the BMP must not be able to make two different vectors agree.
     */
    @Test
    fun `astral characters are encoded by their bytes`() {
        assertEquals(id("😀" to "m"), id("😀" to "m"))
        assertNotEquals(id("😀" to "m"), id("😀😀" to "m"))
    }

    @Test
    fun `blank identities are rejected`() {
        assertEquals(ClaudePSessionBranchId.Reason.BLANK_NODE_ID, rejection("" to "m1"))
        assertEquals(ClaudePSessionBranchId.Reason.BLANK_NODE_ID, rejection("   " to "m1"))
        assertEquals(ClaudePSessionBranchId.Reason.BLANK_SELECTED_MESSAGE_ID, rejection("n1" to ""))
    }

    @Test
    fun `duplicate node or message identity is rejected`() {
        assertEquals(ClaudePSessionBranchId.Reason.DUPLICATE_NODE_ID, rejection("n1" to "m1", "n1" to "m2"))
        assertEquals(
            ClaudePSessionBranchId.Reason.DUPLICATE_SELECTED_MESSAGE_ID,
            rejection("n1" to "m1", "n2" to "m1"),
        )
    }

    @Test
    fun `a rejected vector never yields a digest`() {
        // The rejection is the result, not a digest of whatever survived the validation.
        assertTrue(
            ClaudePSessionBranchId.compute(listOf(ClaudePSessionBranchId.Selection("n1", "m1"), ClaudePSessionBranchId.Selection("n1", "m2")))
                is ClaudePSessionBranchId.Result.Malformed,
        )
    }

    @Test
    fun `identities are not trimmed or case folded`() {
        assertNotEquals(id("n1" to "m1"), id(" n1" to "m1"))
        assertNotEquals(id("n1" to "m1"), id("N1" to "m1"))
    }

    // -----------------------------------------------------------------------------------------
    // Golden vectors
    // -----------------------------------------------------------------------------------------

    /**
     * FIXED DIGESTS — the persisted session-identity contract.
     *
     * These are not a snapshot of whatever the code happens to produce. A branch digest is written
     * into durable session state, so changing what any input hashes to silently orphans or, worse,
     * re-points every binding that already exists. The values below therefore pin the **bytes**:
     * the domain label, the field order, the per-field labels, the presence byte, the 4-byte
     * big-endian length prefixes and their UTF-8 byte counting.
     *
     * **Changing the domain label, the encoding, the labels, the presence byte, the length prefix or
     * the field order requires an explicit version bump** (a new [ClaudePSessionBranchId.DOMAIN]).
     * Any such change makes this test fail, and that failure is the point — it is the review gate,
     * not an inconvenience.
     *
     * **Do not regenerate these values to make this test pass.** Updating them by re-running the
     * implementation is exactly the silent drift they exist to prevent: it converts a contract
     * change into a green build. If a value here is wrong, the reason is decided first and the
     * version bumped second.
     */
    @Test
    fun `golden vectors pin the persisted identity contract`() {
        // 1. Root branch: an empty selection vector still gets a real versioned digest.
        assertEquals(
            "2635ccc7f9300cebb28d19b810ec1650e075e6d962acbf7e9d49ec6d5c8081c8",
            id(),
        )

        // 2. A single selection.
        assertEquals(
            "422af6dabf197d84f0e76655c14e69caffd263555b6e034413abb45f3d208ad0",
            id("node-1" to "msg-1"),
        )

        // 3. Two selections, in a fixed order. Node order is part of the contract.
        assertEquals(
            "e0068157fb985f988bd26b525475b5e62c642808fc7586bebbbf68b4c8dfd676",
            id("node-1" to "msg-1", "node-2" to "msg-2"),
        )

        // 4. Non-BMP input, which the framing counts in UTF-8 bytes rather than UTF-16 code units.
        assertEquals(
            "9808326b0d6c8f7111b2b04681109a08e41922798476ce5709dd9e06cd761d4d",
            id("😀-node" to "m-😀"),
        )
    }
}
