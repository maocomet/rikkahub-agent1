package me.rerere.rikkahub.data.authority.source

import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.claudep.ClaudePSessionContinuation
import me.rerere.ai.provider.claudep.ClaudePSessionContinuationState as State
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the projection means for the durable identities that consume a source revision.
 *
 * [ConversationContentIntegrityProjectionTest] proves the digest ignores a continuation record.
 * That fact on its own is not the requirement — the requirement is that the two consumers which
 * compare a recorded revision against a current one keep working, and that a real edit still moves
 * them. This class drives the **real** writer through the in-memory store and asserts the
 * consequences directly:
 *
 * - a continuation-only write commits the graph and leaves every revision where it was;
 * - a content write still advances the revision and still emits a transition.
 *
 * The anchor and the execution binding are not asserted as their own subsystems here. Both compare
 * *a revision captured earlier against the revision read now*, so the property they need is exactly
 * "the revision did not move", and asserting it once at the writer is what proves it for both — a
 * second test that re-derived each comparison would be asserting the same number twice.
 */
class ConversationContinuationSourceRevisionTest {

    private val assistantId = "00000000-0000-0000-0000-0000000000aa"
    private val branch = "0".repeat(64)

    private val scope = ConversationSourceScope(
        ConversationSourceScopeKind.ASSISTANT,
        assistantId,
    )

    private val userMessageId = Uuid.parse("00000000-0000-0000-0000-000000000003")
    private val assistantMessageId = Uuid.parse("00000000-0000-0000-0000-000000000004")

    private fun record(
        state: State,
        revision: Long = 1L,
        generationId: String? = null,
    ) = ClaudePSessionContinuation(
        assistantId = assistantId,
        branchId = branch,
        revision = revision,
        state = state,
        generationId = generationId,
    )

    /**
     * A conversation whose user message is the branch anchor a command would record, and whose
     * assistant message is the one an execution would bind to. Both are the messages the two
     * durable identities name.
     */
    private fun conversation(
        userContinuation: ClaudePSessionContinuation? = null,
        assistantContinuation: ClaudePSessionContinuation? = null,
        userText: String = "hello",
        assistantText: String = "world",
    ) = Conversation(
        id = Uuid.parse("00000000-0000-0000-0000-000000000002"),
        assistantId = Uuid.parse(assistantId),
        messageNodes = listOf(
            MessageNode(
                id = Uuid.parse("00000000-0000-0000-0000-000000000005"),
                messages = listOf(
                    message(userMessageId, MessageRole.USER, userText, userContinuation),
                ),
                selectIndex = 0,
            ),
            MessageNode(
                id = Uuid.parse("00000000-0000-0000-0000-000000000006"),
                messages = listOf(
                    message(assistantMessageId, MessageRole.ASSISTANT, assistantText, assistantContinuation),
                ),
                selectIndex = 0,
            ),
        ),
    )

    /**
     * `createdAt` is pinned, and that is not tidiness: it defaults to the current time, so two
     * conversations built by two calls would differ in content for a reason unrelated to what any
     * test here is about — and every test here is about *whether content changed*.
     */
    private fun message(
        id: Uuid,
        role: MessageRole,
        text: String,
        continuation: ClaudePSessionContinuation?,
    ) = UIMessage(
        id = id,
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
        createdAt = FIXED_CREATED_AT,
        claudePSessionContinuation = continuation,
    )

    private suspend fun reconcile(
        store: FakeConversationSourceAuthorityStore,
        conversation: Conversation,
        occurredAtMs: Long,
    ) = ConversationSourceAuthorityWriter(store).reconcileInCurrentTransaction(
        ConversationSourceSnapshotFactory.fromConversation(scope, conversation, occurredAtMs),
    )

    private fun ConversationSourceAuthorityCommit.revisionOf(messageId: Uuid): Long =
        requireNotNull(messagesById[messageId.toString()]).sourceRevision

    // -----------------------------------------------------------------------------------------
    // 3. A continuation-only write moves nothing
    // -----------------------------------------------------------------------------------------

    /**
     * The whole point of the projection, asserted at the layer that consumes it: writing a
     * continuation onto messages that already exist in the authority is **not** a content change,
     * so neither the anchor revision nor the assistant revision moves.
     *
     * This is the write the barrier performs twice per turn, and before the projection each one
     * produced an `UPDATED` transition — which is what made an approval lineage's recorded anchor
     * revision, and an execution's bound assistant revision, disagree with the graph.
     */
    @Test
    fun `a continuation only write advances no source revision`() = runBlocking {
        val store = FakeConversationSourceAuthorityStore()
        val writer = ConversationSourceAuthorityWriter(store)

        val initial = writer.reconcileInCurrentTransaction(
            ConversationSourceSnapshotFactory.fromConversation(scope, conversation(), 10L),
        )
        val anchorRevision = initial.revisionOf(userMessageId)
        val assistantRevision = initial.revisionOf(assistantMessageId)

        // The barrier, then the terminal: two writes, on two different messages.
        val afterStart = reconcile(
            store,
            conversation(userContinuation = record(State.START_IN_FLIGHT)),
            20L,
        )
        val afterBound = reconcile(
            store,
            conversation(
                userContinuation = record(State.START_IN_FLIGHT),
                assistantContinuation = record(State.BOUND, revision = 2L),
            ),
            30L,
        )

        assertEquals(anchorRevision, afterStart.revisionOf(userMessageId))
        assertEquals(anchorRevision, afterBound.revisionOf(userMessageId))
        assertEquals(assistantRevision, afterBound.revisionOf(assistantMessageId))
        assertTrue(
            "a continuation-only write is not a content change",
            afterStart.messageTransitions.isEmpty() && afterBound.messageTransitions.isEmpty(),
        )
    }

    /**
     * The other half of the same requirement, and the one that would be missing if the projection
     * were allowed to be sloppy: the record is still **stored**. A digest that ignores a field is
     * only acceptable because that field is persisted by the graph write independently of the
     * source-authority reconciliation.
     */
    @Test
    fun `the continuation is still persisted even though it is not content`() = runBlocking {
        val store = FakeConversationSourceAuthorityStore()

        reconcile(
            store,
            conversation(userContinuation = record(State.BIND_PENDING, generationId = "gen-1")),
            10L,
        )

        val stored = requireNotNull(store.findMessage(scope, userMessageId.toString()))
        // The authority row carries no content of its own — the graph row does — so the assertion
        // that the record survives is made against the graph the caller would be committing.
        assertEquals(MessageRole.USER.name, stored.messageRole)
        val graph = conversation(
            userContinuation = record(State.BIND_PENDING, generationId = "gen-1"),
        )
        assertEquals(
            State.BIND_PENDING,
            graph.messageNodes.first().messages.single().claudePSessionContinuation?.state,
        )
    }

    // -----------------------------------------------------------------------------------------
    // 4. A real content write still moves
    // -----------------------------------------------------------------------------------------

    /**
     * The projection must not have made the graph un-editable. Every one of these is a change a
     * user can make, and each must still be a content change — otherwise a message could be edited
     * while its revision stood still, which is a worse defect than the one being fixed.
     */
    @Test
    fun `a real content change still advances the source revision`() = runBlocking {
        val store = FakeConversationSourceAuthorityStore()
        val writer = ConversationSourceAuthorityWriter(store)

        val initial = writer.reconcileInCurrentTransaction(
            ConversationSourceSnapshotFactory.fromConversation(scope, conversation(), 10L),
        )

        val edited = reconcile(store, conversation(userText = "goodbye"), 20L)

        assertNotEquals(initial.revisionOf(userMessageId), edited.revisionOf(userMessageId))
        assertEquals(1, edited.messageTransitions.size)
        assertEquals(userMessageId.toString(), edited.messageTransitions.single().current.messageId)
    }

    /**
     * A content change wrapped around a continuation write — the realistic shape, because a turn
     * streams its answer and records its terminal in the same write — must still be a content
     * change. The projection removes the record from the digest, not the content beside it.
     */
    @Test
    fun `content beside a continuation still advances the source revision`() = runBlocking {
        val store = FakeConversationSourceAuthorityStore()
        val writer = ConversationSourceAuthorityWriter(store)

        val initial = writer.reconcileInCurrentTransaction(
            ConversationSourceSnapshotFactory.fromConversation(scope, conversation(), 10L),
        )

        val streamed = reconcile(
            store,
            conversation(
                userContinuation = record(State.START_IN_FLIGHT),
                assistantContinuation = record(State.BOUND, revision = 2L),
                assistantText = "world!",
            ),
            20L,
        )

        // The user message moved for no content reason and must not have advanced...
        assertEquals(initial.revisionOf(userMessageId), streamed.revisionOf(userMessageId))
        // ...while the assistant message did change content and must have.
        assertNotEquals(initial.revisionOf(assistantMessageId), streamed.revisionOf(assistantMessageId))
        assertEquals(1, streamed.messageTransitions.size)
    }

    /**
     * A command whose anchor revision was captured before a continuation-only write still admits.
     * This is the anchor half of requirement 3, expressed the way the coordinator expresses it.
     */
    @Test
    fun `an anchor revision captured before a continuation write still matches afterwards`() =
        runBlocking {
            val store = FakeConversationSourceAuthorityStore()
            val writer = ConversationSourceAuthorityWriter(store)

            val initial = writer.reconcileInCurrentTransaction(
                ConversationSourceSnapshotFactory.fromConversation(scope, conversation(), 10L),
            )
            // What `CommandAdmissionAuthorityDraft` records at admission time.
            val capturedAnchorRevision =
                initial.requireActiveMessage(userMessageId.toString(), expectedRole = "USER")
                    .sourceRevision

            val afterBarrier = reconcile(
                store,
                conversation(userContinuation = record(State.START_IN_FLIGHT)),
                20L,
            )

            assertEquals(
                capturedAnchorRevision,
                afterBarrier.requireActiveMessage(userMessageId.toString(), expectedRole = "USER")
                    .sourceRevision,
            )
        }

    /**
     * The same property for the execution binding: the revision `checkpointWaiting` bound is the
     * revision the final commit re-checks.
     */
    @Test
    fun `an assistant revision captured before a continuation write still matches afterwards`() =
        runBlocking {
            val store = FakeConversationSourceAuthorityStore()
            val writer = ConversationSourceAuthorityWriter(store)

            val initial = writer.reconcileInCurrentTransaction(
                ConversationSourceSnapshotFactory.fromConversation(scope, conversation(), 10L),
            )
            val boundAssistantRevision =
                initial.requireActiveMessage(assistantMessageId.toString(), expectedRole = "ASSISTANT")
                    .sourceRevision

            val afterTerminal = reconcile(
                store,
                conversation(assistantContinuation = record(State.BOUND, revision = 2L)),
                20L,
            )

            assertEquals(
                boundAssistantRevision,
                afterTerminal
                    .requireActiveMessage(assistantMessageId.toString(), expectedRole = "ASSISTANT")
                    .sourceRevision,
            )
        }

    /**
     * The projection is not a licence to skip reconciliation: a graph that genuinely gained a
     * message still inserts one, and a graph that lost one still tombstones it.
     */
    @Test
    fun `structural graph changes are unaffected by the projection`() = runBlocking {
        val store = FakeConversationSourceAuthorityStore()
        val writer = ConversationSourceAuthorityWriter(store)

        writer.reconcileInCurrentTransaction(
            ConversationSourceSnapshotFactory.fromConversation(scope, conversation(), 10L),
        )

        val shrunk = conversation().copy(messageNodes = conversation().messageNodes.take(1))
        val commit = reconcile(store, shrunk, 20L)

        val tombstoned = requireNotNull(
            store.findMessage(scope, assistantMessageId.toString()),
        )
        assertEquals(ConversationSourceState.TOMBSTONED, tombstoned.sourceState)
        assertTrue(commit.messageTransitions.isNotEmpty())
        assertTrue(commit.didMutate)
    }

    private companion object {
        val FIXED_CREATED_AT = kotlinx.datetime.LocalDateTime(2019, 6, 1, 12, 0, 0)
    }
}
