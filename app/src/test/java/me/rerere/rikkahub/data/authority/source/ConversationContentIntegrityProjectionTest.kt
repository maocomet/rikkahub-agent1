package me.rerere.rikkahub.data.authority.source

import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.claudep.ClaudePSessionContinuation
import me.rerere.ai.provider.claudep.ClaudePSessionContinuationState as State
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessageState
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prerequisite for enabling `auto`: a Claude P continuation record must not be message content.
 *
 * Continuation bookkeeping changes twice per turn — a start before dispatch, a terminal after — and
 * every one of those changes used to move the message's source revision, which is a durable
 * identity that an approval lineage and an execution binding both compare. This class is the
 * evidence that the coupling is gone, and it is written as two complementary halves that must hold
 * *together*:
 *
 * - **The continuation never changes the digest.** Any continuation value, on any revision, with
 *   any generation id, over any state.
 * - **Nothing else was removed to achieve that.** Every other field still changes it, so no real
 *   edit can hide behind the projection — that would be a worse defect than the one being fixed,
 *   because it would let a changed message keep an unchanged revision.
 *
 * The second half is the one a reviewer should read skeptically: a projection that returned a
 * constant would pass the first half perfectly.
 */
class ConversationContentIntegrityProjectionTest {

    private val assistant = "00000000-0000-0000-0000-0000000000aa"
    private val branch = "0".repeat(64)

    private fun continuation(
        state: State,
        revision: Long = 1L,
        generationId: String? = null,
        assistantId: String = assistant,
        branchId: String = branch,
    ) = ClaudePSessionContinuation(
        assistantId = assistantId,
        branchId = branchId,
        revision = revision,
        state = state,
        generationId = generationId,
    )

    private fun message(
        role: MessageRole = MessageRole.ASSISTANT,
        parts: List<UIMessagePart> = listOf(UIMessagePart.Text("hello")),
        continuation: ClaudePSessionContinuation? = null,
        state: UIMessageState = UIMessageState.COMPLETED,
        usage: TokenUsage? = null,
        modelId: Uuid? = null,
        annotations: List<UIMessageAnnotation> = emptyList(),
    ) = UIMessage(
        id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
        role = role,
        parts = parts,
        annotations = annotations,
        // Pinned, and that matters: `createdAt` defaults to the current time, so two messages built
        // by two calls to this helper would differ in content for a reason that has nothing to do
        // with what the test is about. Left at the default, the assertions below pass or fail
        // depending on whether the calls land in the same millisecond.
        createdAt = FIXED_CREATED_AT,
        modelId = modelId,
        usage = usage,
        state = state,
        claudePSessionContinuation = continuation,
    )

    private fun digestOf(message: UIMessage) = ConversationSourceSnapshotFactory.payloadIntegritySha256(message)

    // -----------------------------------------------------------------------------------------
    // 1. The continuation never participates
    // -----------------------------------------------------------------------------------------

    /**
     * Every durable state, at every revision, with and without a generation id, over the same
     * content: one digest.
     */
    @Test
    fun `no continuation value changes the content digest`() {
        val baseline = digestOf(message())

        val variants = listOf(
            continuation(State.START_IN_FLIGHT),
            continuation(State.BIND_PENDING, generationId = "gen-1"),
            continuation(State.BOUND, revision = 2L),
            continuation(State.FAILED_CLOSED, revision = 2L),
            continuation(State.INTERRUPTED),
            continuation(State.INTERRUPTED, generationId = "gen-1"),
            // Same state, different revisions and identities: still not content.
            continuation(State.BOUND, revision = 7L),
            continuation(State.BOUND, revision = 1L, assistantId = "00000000-0000-0000-0000-0000000000bb"),
            continuation(State.BOUND, branchId = "f".repeat(64)),
        )

        for (record in variants) {
            assertEquals(
                "continuation $record must not be content",
                baseline,
                digestOf(message(continuation = record)),
            )
        }
    }

    /**
     * The strongest form: the *same* message, differing only in its record, is one identity. This
     * is the property that stops a turn's two bookkeeping writes from being two branch-head moves.
     */
    @Test
    fun `a continuation-only update is not a content change`() {
        val before = message(continuation = continuation(State.START_IN_FLIGHT))
        val after = message(continuation = continuation(State.BOUND, revision = 2L))

        assertEquals(digestOf(before), digestOf(after))
    }

    // -----------------------------------------------------------------------------------------
    // 2. Nothing else was removed
    // -----------------------------------------------------------------------------------------

    @Test
    fun `every other message field still changes the digest`() {
        val baseline = message()

        val changes = listOf(
            "role" to baseline.copy(role = MessageRole.USER),
            "text content" to baseline.copy(parts = listOf(UIMessagePart.Text("goodbye"))),
            "part type" to baseline.copy(parts = listOf(UIMessagePart.Image("file:///a.png"))),
            "extra part" to baseline.copy(
                parts = listOf(UIMessagePart.Text("hello"), UIMessagePart.Text("again")),
            ),
            "part metadata" to baseline.copy(
                parts = listOf(
                    UIMessagePart.Text("hello", metadata = kotlinx.serialization.json.buildJsonObject {
                        put("k", kotlinx.serialization.json.JsonPrimitive("v"))
                    }),
                ),
            ),
            "state" to baseline.copy(state = UIMessageState.STREAMING),
            "usage" to baseline.copy(
                usage = TokenUsage(promptTokens = 1, completionTokens = 2, totalTokens = 3),
            ),
            "model" to baseline.copy(modelId = Uuid.parse("00000000-0000-0000-0000-0000000000cc")),
            "annotation" to baseline.copy(
                annotations = listOf(UIMessageAnnotation.UrlCitation("t", "https://example.invalid")),
            ),
            "message id" to baseline.copy(id = Uuid.parse("00000000-0000-0000-0000-0000000000dd")),
            "createdAt" to baseline.copy(
                createdAt = kotlinx.datetime.LocalDateTime(2020, 1, 1, 0, 0),
            ),
            // `finishedAt` is null in the baseline, so this is a presence change as well as a value
            // change — both are content.
            "finishedAt" to baseline.copy(
                finishedAt = kotlinx.datetime.LocalDateTime(2021, 1, 1, 0, 0),
            ),
            "translation" to baseline.copy(translation = "bonjour"),
        )

        for ((label, changed) in changes) {
            assertNotEquals(
                "a change to $label must still be a content change",
                digestOf(baseline),
                digestOf(changed),
            )
        }
    }

    /**
     * Tool calls carry both the input the model chose and the result the app produced, and both are
     * content: two calls that differ in either are different messages.
     */
    @Test
    fun `a tool input, result or approval change still changes the digest`() {
        fun tool(approvalState: ToolApprovalState) = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "read_file",
            input = """{"path":"/a"}""",
            output = listOf(UIMessagePart.Text("contents")),
            approvalState = approvalState,
        )

        val baseline = message(parts = listOf(tool(ToolApprovalState.Approved)))

        val differentInput = baseline.copy(
            parts = listOf(
                (baseline.parts.single() as UIMessagePart.Tool).copy(input = """{"path":"/b"}"""),
            ),
        )
        val differentOutput = baseline.copy(
            parts = listOf(
                (baseline.parts.single() as UIMessagePart.Tool)
                    .copy(output = listOf(UIMessagePart.Text("other"))),
            ),
        )
        val differentApproval = baseline.copy(parts = listOf(tool(ToolApprovalState.Pending)))
        val differentDenial = baseline.copy(
            parts = listOf(tool(ToolApprovalState.Denied("user said no"))),
        )

        assertNotEquals(digestOf(baseline), digestOf(differentInput))
        assertNotEquals(digestOf(baseline), digestOf(differentOutput))
        assertNotEquals(digestOf(baseline), digestOf(differentApproval))
        assertNotEquals(digestOf(baseline), digestOf(differentDenial))
    }

    // -----------------------------------------------------------------------------------------
    // 3. The stored bytes are unchanged for messages that carry no continuation
    // -----------------------------------------------------------------------------------------

    /**
     * The projection must not be a migration. Every digest already written into an authority row
     * was computed over the message as stored, and almost every one of those messages has a null
     * continuation — so for those, the projection has to produce the identical byte stream, not
     * merely an equivalent one.
     *
     * This is checked against the raw encoder rather than against a stored constant, because a
     * constant would only prove that the two agreed on the day it was written.
     */
    @Test
    fun `a message with no continuation encodes exactly as it did before the projection`() {
        val messages = listOf(
            message(),
            message(
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Text("hi"),
                    UIMessagePart.Image("file:///a.png"),
                    UIMessagePart.Tool(
                        toolCallId = "call-1",
                        toolName = "read_file",
                        input = """{"path":"/a"}""",
                        output = listOf(UIMessagePart.Text("contents")),
                        approvalState = ToolApprovalState.Approved,
                    ),
                ),
                state = UIMessageState.WAITING_TOOL,
                usage = TokenUsage(promptTokens = 5, completionTokens = 6, totalTokens = 11),
                modelId = Uuid.parse("00000000-0000-0000-0000-0000000000ee"),
                annotations = listOf(UIMessageAnnotation.UrlCitation("t", "https://example.invalid")),
            ),
        )

        for (message in messages) {
            assertEquals(
                "a message without a continuation must project to itself",
                JsonInstant.encodeToString(message),
                JsonInstant.encodeToString(
                    ConversationSourceSnapshotFactory.contentIntegrityProjection(message),
                ),
            )
        }
    }

    /**
     * The projection puts `null` back rather than dropping the key, so a message that *does* carry
     * a continuation projects to the same bytes as one that never did. If the key were dropped, the
     * two digests would differ — and the first test in this class would fail.
     */
    @Test
    fun `a message carrying a continuation projects onto the same bytes as one that does not`() {
        val bare = message()
        val carrying = message(continuation = continuation(State.BIND_PENDING, generationId = "gen-1"))

        assertEquals(
            JsonInstant.encodeToString(bare),
            JsonInstant.encodeToString(
                ConversationSourceSnapshotFactory.contentIntegrityProjection(carrying),
            ),
        )
    }

    /**
     * The field is still stored. The projection is an identity for *hashing*; it must not be
     * mistaken for a decision not to persist the record, which is the whole reason the field exists.
     */
    @Test
    fun `the stored message still carries its continuation`() {
        val carrying = message(continuation = continuation(State.BIND_PENDING, generationId = "gen-1"))

        val encoded = JsonInstant.encodeToString(carrying)
        val decoded = JsonInstant.decodeFromString<UIMessage>(encoded)

        assertEquals(State.BIND_PENDING, decoded.claudePSessionContinuation?.state)
        assertEquals("gen-1", decoded.claudePSessionContinuation?.generationId)
        assertTrue(encoded.contains("BIND_PENDING"))
    }

    private companion object {
        val FIXED_CREATED_AT = kotlinx.datetime.LocalDateTime(2019, 6, 1, 12, 0, 0)
    }
}
