package me.rerere.rikkahub.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ranking rules, exercised directly.
 *
 * The engine is a pure function over a candidate list precisely so this is possible: every rule
 * that decides which sticker the model is offered is asserted here rather than inferred from
 * whatever a device happened to return.
 */
class StickerSearchTest {

    private fun sticker(
        id: String,
        description: String = "",
        tags: List<String> = emptyList(),
        createdAtMs: Long = 0L,
    ) = Sticker(
        id = id,
        relativePath = "stickers/$id.png",
        mimeType = "image/png",
        width = 64,
        height = 64,
        description = description,
        tags = tags,
        enabled = true,
        createdAtMs = createdAtMs,
        updatedAtMs = createdAtMs,
        checksum = id,
        visionState = StickerVisionState.OK,
        visionFailure = null,
    )

    // ── Tokenisation ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `tokens split on whitespace and on the punctuation people type between tags`() {
        assertEquals(listOf("委屈", "撒娇"), StickerSearch.tokenize("委屈 撒娇"))
        assertEquals(listOf("委屈", "撒娇"), StickerSearch.tokenize("委屈、撒娇"))
        assertEquals(listOf("委屈", "撒娇"), StickerSearch.tokenize(" 委屈 ,  撒娇 "))
    }

    @Test
    fun `a phrase with no separator stays one token`() {
        // No segmenter here and no pretence of one: the query is matched as the person typed it.
        assertEquals(listOf("委屈撒娇"), StickerSearch.tokenize("委屈撒娇"))
    }

    @Test
    fun `tokenisation is case insensitive and drops repeats`() {
        assertEquals(listOf("cat", "喵"), StickerSearch.tokenize("Cat cat 喵"))
    }

    @Test
    fun `a query of only separators tokenises to nothing`() {
        assertEquals(emptyList<String>(), StickerSearch.tokenize("  ,、  "))
    }

    // ── Scoring ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an exact tag match outranks a partial tag match`() {
        val exact = sticker(id = "a", tags = listOf("委屈"))
        val partial = sticker(id = "b", tags = listOf("很委屈"))

        val ranked = StickerSearch.search(listOf(partial, exact), "委屈")

        assertEquals(listOf("a", "b"), ranked.map { it.sticker.id })
    }

    @Test
    fun `a partial tag match outranks a description match`() {
        val tag = sticker(id = "a", tags = listOf("很委屈"))
        val description = sticker(id = "b", description = "看起来委屈")

        val ranked = StickerSearch.search(listOf(description, tag), "委屈")

        assertEquals(listOf("a", "b"), ranked.map { it.sticker.id })
    }

    @Test
    fun `matching more tokens outranks matching one`() {
        val both = sticker(id = "a", tags = listOf("委屈", "撒娇"))
        val one = sticker(id = "b", tags = listOf("委屈"))

        val ranked = StickerSearch.search(listOf(one, both), "委屈 撒娇")

        assertEquals(listOf("a", "b"), ranked.map { it.sticker.id })
    }

    @Test
    fun `a token matching both a tag and the description scores once, at the higher value`() {
        val doubleDuty = sticker(id = "a", tags = listOf("委屈"), description = "很委屈")
        val additional = sticker(id = "b", tags = listOf("委屈", "猫猫"))

        // Otherwise a sticker with one word in two places would outrank one that actually matches
        // more of what was asked for.
        val ranked = StickerSearch.search(listOf(doubleDuty, additional), "委屈 猫猫")

        assertEquals(listOf("b", "a"), ranked.map { it.sticker.id })
    }

    @Test
    fun `a candidate matching nothing is not returned at all`() {
        val unrelated = sticker(id = "a", tags = listOf("开心"), description = "笑得很开心")

        assertTrue(StickerSearch.search(listOf(unrelated), "委屈").isEmpty())
    }

    // ── Result shape ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `an empty query returns nothing rather than everything`() {
        val library = listOf(sticker(id = "a", tags = listOf("委屈")))

        assertTrue(StickerSearch.search(library, "").isEmpty())
        assertTrue(StickerSearch.search(library, "   ").isEmpty())
        assertTrue(StickerSearch.search(library, ",、").isEmpty())
    }

    @Test
    fun `a query that matches nothing returns an empty list, not an error`() {
        val library = listOf(sticker(id = "a", tags = listOf("开心")))

        assertTrue(StickerSearch.search(library, "宇航员").isEmpty())
    }

    @Test
    fun `equal scores are broken by creation time, newest first`() {
        val older = sticker(id = "a", tags = listOf("委屈"), createdAtMs = 1_000)
        val newer = sticker(id = "b", tags = listOf("委屈"), createdAtMs = 2_000)

        val ranked = StickerSearch.search(listOf(older, newer), "委屈")

        assertEquals(listOf("b", "a"), ranked.map { it.sticker.id })
    }

    // ── Limit ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the limit caps how many candidates come back`() {
        val library = (1..10).map { sticker(id = "s$it", tags = listOf("委屈")) }

        assertEquals(3, StickerSearch.search(library, "委屈", limit = 3).size)
    }

    @Test
    fun `the limit is clamped into the advertised range`() {
        assertEquals(StickerSearch.DEFAULT_LIMIT, StickerSearch.clampLimit(null))
        assertEquals(StickerSearch.MIN_LIMIT, StickerSearch.clampLimit(0))
        assertEquals(StickerSearch.MIN_LIMIT, StickerSearch.clampLimit(-5))
        assertEquals(StickerSearch.MAX_LIMIT, StickerSearch.clampLimit(1_000))
        assertEquals(StickerSearch.MAX_LIMIT, StickerSearch.clampLimit(StickerSearch.MAX_LIMIT))
    }

    @Test
    fun `the empty query is caught before ranking even when a limit is given`() {
        val library = (1..10).map { sticker(id = "s$it", tags = listOf("委屈")) }

        assertTrue(StickerSearch.search(library, "", limit = 20).isEmpty())
    }
}
