package me.rerere.rikkahub.sticker

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A sent sticker appears once, in the message.
 *
 * The rule under test is `liftSentStickers`, the pure body of the transformer. The
 * `OutputMessageTransformer` wrapper around it is a one-line delegation and is not covered here:
 * its context carries an Android `Context`, and this repo has no Robolectric harness.
 */
class StickerSendToMessagePartTransformerTest {

    private fun image(url: String = "file:///data/upload/copy.png") = UIMessagePart.Image(url)

    private fun ack(id: String = "sticker-1") =
        UIMessagePart.Text("""{"ok":true,"code":"STICKER_SENT","sticker_id":"$id"}""")

    private fun sendToolPart(
        outputs: List<UIMessagePart>,
        toolCallId: String = "call-1",
    ) = UIMessagePart.Tool(
        toolCallId = toolCallId,
        toolName = STICKER_SEND_TOOL_NAME,
        input = """{"sticker_id":"sticker-1"}""",
        output = outputs,
    )

    private fun assistantMessage(vararg parts: UIMessagePart) =
        UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

    @Test
    fun `a sent sticker is moved out of the tool output and into the message`() {
        val message = assistantMessage(sendToolPart(listOf(image(), ack())))

        val result = liftSentStickers(listOf(message)).single()

        val images = result.parts.filterIsInstance<UIMessagePart.Image>()
        assertEquals(1, images.size)
        assertEquals(1, result.parts.filterIsInstance<UIMessagePart.Tool>().size)
    }

    @Test
    fun `the sticker is displayed exactly once`() {
        val message = assistantMessage(sendToolPart(listOf(image(), ack())))

        val result = liftSentStickers(listOf(message)).single()

        val inMessage = result.parts.count { it is UIMessagePart.Image }
        val inToolOutput = result.parts
            .filterIsInstance<UIMessagePart.Tool>()
            .sumOf { tool -> tool.output.count { it is UIMessagePart.Image } }
        assertEquals("the tool card must not show a second copy", 0, inToolOutput)
        assertEquals(1, inMessage)
    }

    @Test
    fun `the tool output keeps its acknowledgement, minus the picture`() {
        val message = assistantMessage(sendToolPart(listOf(image(), ack())))

        val result = liftSentStickers(listOf(message)).single()

        val output = result.parts.filterIsInstance<UIMessagePart.Tool>().single().output
        assertEquals(1, output.size)
        assertTrue((output.single() as UIMessagePart.Text).text.contains("STICKER_SENT"))
    }

    @Test
    fun `the image lands immediately after the step that produced it`() {
        val before = UIMessagePart.Text("之前")
        val after = UIMessagePart.Tool(toolCallId = "call-2", toolName = "other_tool", input = "{}")
        val message = assistantMessage(before, sendToolPart(listOf(image(), ack())), after)

        val result = liftSentStickers(listOf(message)).single()

        assertEquals(4, result.parts.size)
        assertSame(before, result.parts[0])
        assertTrue(result.parts[1] is UIMessagePart.Tool)
        assertTrue(result.parts[2] is UIMessagePart.Image)
        assertSame(after, result.parts[3])
    }

    @Test
    fun `running it twice does not duplicate the sticker`() {
        val message = assistantMessage(sendToolPart(listOf(image(), ack())))

        val once = liftSentStickers(listOf(message))
        val twice = liftSentStickers(once)

        assertEquals(once, twice)
        assertEquals(1, twice.single().parts.count { it is UIMessagePart.Image })
    }

    @Test
    fun `a message with no sticker send is returned untouched`() {
        val message = assistantMessage(
            UIMessagePart.Text("hello"),
            UIMessagePart.Tool(toolCallId = "call-9", toolName = "other_tool", input = "{}"),
        )

        assertSame(message, liftSentStickers(listOf(message)).single())
    }

    @Test
    fun `another tool's image output is left alone`() {
        val toolImage = UIMessagePart.Image("file:///data/cache/screenshots/shot.png")
        val message = assistantMessage(
            UIMessagePart.Tool(
                toolCallId = "call-3",
                toolName = "take_screenshot",
                input = "{}",
                output = listOf(toolImage),
            ),
        )

        val result = liftSentStickers(listOf(message)).single()

        // Only sticker_send is promoted; a screenshot is a tool result and belongs in the card.
        assertEquals(listOf(toolImage), result.parts.filterIsInstance<UIMessagePart.Tool>().single().output)
        assertEquals(0, result.parts.count { it is UIMessagePart.Image })
    }

    @Test
    fun `a sticker send with no image is left alone`() {
        val message = assistantMessage(sendToolPart(listOf(ack())))

        assertSame(message, liftSentStickers(listOf(message)).single())
    }

    @Test
    fun `a user turn is never given the image`() {
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(sendToolPart(listOf(image(), ack()))),
        )

        assertSame(message, liftSentStickers(listOf(message)).single())
    }

    @Test
    fun `several sends in one turn each keep exactly one sticker`() {
        val message = assistantMessage(
            sendToolPart(listOf(image("file:///a.png"), ack("a")), toolCallId = "call-a"),
            sendToolPart(listOf(image("file:///b.png"), ack("b")), toolCallId = "call-b"),
        )

        val result = liftSentStickers(listOf(message)).single()

        assertEquals(2, result.parts.count { it is UIMessagePart.Image })
        assertEquals(
            0,
            result.parts.filterIsInstance<UIMessagePart.Tool>()
                .sumOf { tool -> tool.output.count { it is UIMessagePart.Image } },
        )
    }
}
