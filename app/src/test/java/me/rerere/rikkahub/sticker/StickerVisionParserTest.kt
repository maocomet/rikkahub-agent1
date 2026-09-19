package me.rerere.rikkahub.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The parser's contract: extract what is honestly there, and answer null otherwise.
 *
 * The null cases matter as much as the successful ones. A parser that salvaged *something* from
 * every reply would record a model's refusal, or a truncated fragment, as a successful recognition
 * — which reads as a working feature and is worse than an empty description, because nothing
 * prompts anyone to fix it.
 */
class StickerVisionParserTest {

    @Test
    fun `a plain json object yields its description and tags`() {
        val parsed = StickerVisionParser.parse(
            """{"description":"一只猫缩成一团，看起来委屈","tags":["委屈","猫猫"]}""",
        )

        assertEquals("一只猫缩成一团，看起来委屈", parsed?.description)
        assertEquals(listOf("委屈", "猫猫"), parsed?.tags)
    }

    @Test
    fun `a fenced json block is read the same way`() {
        val parsed = StickerVisionParser.parse(
            """
            ```json
            {"description":"无语地看着镜头","tags":["无语","猫猫"]}
            ```
            """.trimIndent(),
        )

        assertEquals("无语地看着镜头", parsed?.description)
        assertEquals(listOf("无语", "猫猫"), parsed?.tags)
    }

    @Test
    fun `an object surrounded by prose is still found`() {
        val parsed = StickerVisionParser.parse(
            "好的，这是结果：{\"description\":\"震惊\",\"tags\":[\"震惊\"]} 希望有帮助。",
        )

        assertEquals("震惊", parsed?.description)
        assertEquals(listOf("震惊"), parsed?.tags)
    }

    @Test
    fun `unknown keys do not break the parse`() {
        val parsed = StickerVisionParser.parse(
            """{"description":"开心","tags":["开心"],"confidence":0.9,"extra":{"a":1}}""",
        )

        assertEquals("开心", parsed?.description)
    }

    @Test
    fun `tags given as one delimited string are split rather than discarded`() {
        val parsed = StickerVisionParser.parse(
            """{"description":"委屈","tags":"委屈, 猫猫、撒娇"}""",
        )

        assertEquals(listOf("委屈", "猫猫", "撒娇"), parsed?.tags)
    }

    @Test
    fun `a missing tags key leaves the description intact with no tags`() {
        val parsed = StickerVisionParser.parse("""{"description":"只有一个描述"}""")

        assertEquals("只有一个描述", parsed?.description)
        assertEquals(emptyList<String>(), parsed?.tags)
    }

    @Test
    fun `tags are normalised and capped at the stated maximum`() {
        val many = (1..20).joinToString(",") { "\"标签$it\"" }
        val parsed = StickerVisionParser.parse("""{"description":"很多标签","tags":[$many]}""")

        assertEquals(StickerTags.MAX_TAGS, parsed?.tags?.size)
    }

    @Test
    fun `duplicate and blank tags are dropped`() {
        val parsed = StickerVisionParser.parse(
            """{"description":"x","tags":["委屈"," 委屈 ","","  ","猫猫"]}""",
        )

        assertEquals(listOf("委屈", "猫猫"), parsed?.tags)
    }

    @Test
    fun `a blank description is not a description`() {
        assertNull(StickerVisionParser.parse("""{"description":"   ","tags":["a"]}"""))
        assertNull(StickerVisionParser.parse("""{"description":"","tags":["a"]}"""))
    }

    @Test
    fun `a non-string description is refused`() {
        assertNull(StickerVisionParser.parse("""{"description":42,"tags":["a"]}"""))
        assertNull(StickerVisionParser.parse("""{"description":null,"tags":["a"]}"""))
    }

    @Test
    fun `a missing description is refused even when tags are present`() {
        assertNull(StickerVisionParser.parse("""{"tags":["委屈","猫猫"]}"""))
    }

    @Test
    fun `a refusal with no json in it produces nothing`() {
        // The case this parser exists to get right: storing this as a description would present a
        // model's apology as a successful recognition of the sticker.
        assertNull(StickerVisionParser.parse("抱歉，我无法处理这张图片。"))
    }

    @Test
    fun `empty and whitespace replies produce nothing`() {
        assertNull(StickerVisionParser.parse(""))
        assertNull(StickerVisionParser.parse("   \n  "))
    }

    @Test
    fun `a truncated json object produces nothing rather than a partial description`() {
        assertNull(StickerVisionParser.parse("""{"description":"一只猫"""))
    }

    @Test
    fun `a bare array produces nothing`() {
        assertNull(StickerVisionParser.parse("""["委屈","猫猫"]"""))
    }

    @Test
    fun `an unterminated brace pair is refused`() {
        assertNull(StickerVisionParser.parse("""{"description":"x","tags":["a"]"""))
    }

    @Test
    fun `a json object mixed into later prose takes the outermost braces`() {
        val parsed = StickerVisionParser.parse(
            """{"description":"前面","tags":["a"]} 后面还有 {"description":"后面"}""",
        )

        // The outermost slice is not valid JSON, so this is honestly a failure rather than an
        // arbitrary pick between two objects.
        assertNull(parsed)
    }
}
