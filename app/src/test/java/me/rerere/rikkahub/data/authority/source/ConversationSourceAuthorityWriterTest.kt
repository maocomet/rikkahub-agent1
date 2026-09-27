package me.rerere.rikkahub.data.authority.source

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSourceAuthorityWriterTest {
    @Test
    fun `disabled initial capture supplies ephemeral correlation with zero authority writes`() =
        runBlocking {
            val store = FakeConversationSourceAuthorityStore()
            val writer = ConversationSourceAuthorityWriter(
                store = store,
                initialCaptureGate = ConversationSourceInitialCaptureGate { false },
            )

            val commit = writer.reconcileAllKnownScopesInCurrentTransaction(snapshot()).single()

            assertFalse(commit.didMutate)
            assertEquals(0, store.countConversationScopes(CONVERSATION_ID))
            assertEquals(0, store.countMessagesForConversation(SCOPE, CONVERSATION_ID))
            assertEquals(
                1L,
                commit.requireActiveMessage(ASSISTANT_MESSAGE_ID, "ASSISTANT").sourceRevision,
            )

            val enabled = ConversationSourceAuthorityWriter(store)
                .reconcileAllKnownScopesInCurrentTransaction(snapshot()).single()
            assertTrue(enabled.didMutate)
            assertEquals(1, store.countConversationScopes(CONVERSATION_ID))
            assertEquals(2, store.countMessagesForConversation(SCOPE, CONVERSATION_ID))
        }

    @Test
    fun `initial graph gets revision one and exact branch head without invalidation`() = runBlocking {
        val store = FakeConversationSourceAuthorityStore()
        val events = FakeSourceEvents()
        val writer = ConversationSourceAuthorityWriter(store, events)

        val commit = writer.reconcileInCurrentTransaction(snapshot())

        assertEquals(1L, commit.conversation.sourceRevision)
        assertEquals(ASSISTANT_MESSAGE_ID, commit.conversation.branchHeadMessageId)
        assertEquals(1L, commit.conversation.branchHeadMessageRevision)
        assertEquals(2, commit.insertedInitialMessageCount)
        assertTrue(commit.messageTransitions.isEmpty())
        assertFalse(commit.insertedOutbox)
        assertTrue(events.events.isEmpty())
    }

    @Test
    fun `idempotent replay does not advance any authority revision`() = runBlocking {
        val store = FakeConversationSourceAuthorityStore()
        val writer = ConversationSourceAuthorityWriter(store)
        val first = writer.reconcileInCurrentTransaction(snapshot())

        val replay = writer.reconcileInCurrentTransaction(snapshot())

        assertEquals(first.conversation, replay.conversation)
        assertFalse(replay.didMutate)
        assertEquals(1L, replay.messagesById.getValue(USER_MESSAGE_ID).sourceRevision)
        assertEquals(1L, replay.messagesById.getValue(ASSISTANT_MESSAGE_ID).sourceRevision)
    }

    @Test
    fun `switching away and back creates new monotonic revisions and never revives evidence`() = runBlocking {
        val store = FakeConversationSourceAuthorityStore()
        val events = FakeSourceEvents()
        val writer = ConversationSourceAuthorityWriter(store, events)
        writer.reconcileInCurrentTransaction(snapshot())

        val switchedAway = writer.reconcileInCurrentTransaction(
            snapshot(selected = listOf(USER_MESSAGE_ID), occurredAtMs = 20L),
        )
        val superseded = switchedAway.messagesById.getValue(ASSISTANT_MESSAGE_ID)
        assertEquals(2L, superseded.sourceRevision)
        assertEquals(1L, superseded.previousSourceRevision)
        assertEquals(ConversationSourceState.SUPERSEDED, superseded.sourceState)
        assertEquals(ConversationSourceChangeKind.BRANCH_SUPERSEDED, superseded.changeKind)

        val selectedAgain = writer.reconcileInCurrentTransaction(
            snapshot(occurredAtMs = 30L),
        )
        val active = selectedAgain.messagesById.getValue(ASSISTANT_MESSAGE_ID)
        assertEquals(3L, active.sourceRevision)
        assertEquals(2L, active.previousSourceRevision)
        assertEquals(ConversationSourceState.ACTIVE, active.sourceState)
        assertEquals(ConversationSourceChangeKind.BRANCH_SELECTED, active.changeKind)
        assertEquals(
            listOf(2L, 3L),
            events.events
                .filter { it.objectKind == SourceAuthorityObjectKind.MESSAGE }
                .map { it.sourceRevision },
        )
    }

    @Test
    fun `payload edit and byte restore each receive a fresh revision`() = runBlocking {
        val store = FakeConversationSourceAuthorityStore()
        val writer = ConversationSourceAuthorityWriter(store)
        writer.reconcileInCurrentTransaction(snapshot())

        val edited = writer.reconcileInCurrentTransaction(
            snapshot(assistantDigest = DIGEST_C, occurredAtMs = 20L),
        )
        assertEquals(2L, edited.messagesById.getValue(ASSISTANT_MESSAGE_ID).sourceRevision)
        assertEquals(
            ConversationSourceChangeKind.UPDATED,
            edited.messagesById.getValue(ASSISTANT_MESSAGE_ID).changeKind,
        )

        val restoredBytes = writer.reconcileInCurrentTransaction(
            snapshot(assistantDigest = DIGEST_B, occurredAtMs = 30L),
        )
        val restored = restoredBytes.messagesById.getValue(ASSISTANT_MESSAGE_ID)
        assertEquals(3L, restored.sourceRevision)
        assertEquals(2L, restored.previousSourceRevision)
        assertEquals(DIGEST_B, restored.payloadIntegritySha256)
    }

    @Test
    fun `deletion leaves a tombstone and later restore fails closed`() = runBlocking {
        val store = FakeConversationSourceAuthorityStore()
        val writer = ConversationSourceAuthorityWriter(store)
        writer.reconcileInCurrentTransaction(snapshot())

        val deleted = writer.reconcileInCurrentTransaction(
            snapshot(
                messages = listOf(message(USER_MESSAGE_ID, "USER", DIGEST_A)),
                selected = listOf(USER_MESSAGE_ID),
                occurredAtMs = 20L,
            ),
        )
        val tombstone = deleted.messagesById.getValue(ASSISTANT_MESSAGE_ID)
        assertEquals(ConversationSourceState.TOMBSTONED, tombstone.sourceState)
        assertEquals(2L, tombstone.sourceRevision)
        assertEquals(null, tombstone.payloadIntegritySha256)

        val error = assertThrows(ConversationSourceAuthorityConflictException::class.java) {
            runBlocking { writer.reconcileInCurrentTransaction(snapshot(occurredAtMs = 30L)) }
        }
        assertEquals("SOURCE_MESSAGE_TOMBSTONE_REVIVAL", error.reasonCode)
    }

    @Test
    fun `conversation deletion tombstones every source and cannot be undone`() = runBlocking {
        val store = FakeConversationSourceAuthorityStore()
        val writer = ConversationSourceAuthorityWriter(store)
        writer.reconcileInCurrentTransaction(snapshot())

        val deleted = writer.reconcileInCurrentTransaction(
            snapshot(
                messages = emptyList(),
                selected = emptyList(),
                occurredAtMs = 20L,
                conversationDeleted = true,
            ),
        )
        assertEquals(ConversationSourceState.TOMBSTONED, deleted.conversation.sourceState)
        assertEquals(null, deleted.conversation.branchHeadMessageId)
        assertTrue(deleted.messagesById.values.all { it.sourceState == ConversationSourceState.TOMBSTONED })

        val error = assertThrows(ConversationSourceAuthorityConflictException::class.java) {
            runBlocking { writer.reconcileInCurrentTransaction(snapshot(occurredAtMs = 30L)) }
        }
        assertEquals("SOURCE_CONVERSATION_TOMBSTONE_REVIVAL", error.reasonCode)
    }

    @Test
    fun `privacy deletion snapshots and tombstones every known scope`() = runBlocking {
        val store = FakeConversationSourceAuthorityStore()
        val writer = ConversationSourceAuthorityWriter(store)
        writer.reconcileInCurrentTransaction(snapshot())
        writer.reconcileInCurrentTransaction(
            snapshot().copy(
                scope = ConversationSourceScope(
                    ConversationSourceScopeKind.AUTHORITY_SUBJECT,
                    "authority:subject:1",
                ),
            ),
        )

        val deleted = writer.tombstoneAllScopesInCurrentTransaction(
            conversationId = CONVERSATION_ID,
            occurredAtMs = 20L,
        )

        assertEquals(2, deleted.size)
        assertEquals(
            setOf(ConversationSourceScopeKind.ASSISTANT, ConversationSourceScopeKind.AUTHORITY_SUBJECT),
            deleted.map { it.conversation.scope.kind }.toSet(),
        )
        assertTrue(deleted.all { it.conversation.sourceState == ConversationSourceState.TOMBSTONED })
        assertTrue(deleted.flatMap { it.messagesById.values }.all {
            it.sourceState == ConversationSourceState.TOMBSTONED
        })

        val repeated = writer.tombstoneAllScopesInCurrentTransaction(
            conversationId = CONVERSATION_ID,
            occurredAtMs = 30L,
        )
        assertTrue(repeated.isEmpty())
    }

    @Test
    fun `privacy deletion fails closed when scope snapshot exceeds the hard bound`() = runBlocking {
        val store = FakeConversationSourceAuthorityStore()
        val seedingWriter = ConversationSourceAuthorityWriter(store)
        seedingWriter.reconcileInCurrentTransaction(snapshot())
        seedingWriter.reconcileInCurrentTransaction(
            snapshot().copy(
                scope = ConversationSourceScope(
                    ConversationSourceScopeKind.AUTHORITY_SUBJECT,
                    "authority:subject:2",
                ),
            ),
        )
        val boundedWriter = ConversationSourceAuthorityWriter(
            store = store,
            maxScopesPerConversation = 1,
        )

        val error = assertThrows(ConversationSourceAuthorityConflictException::class.java) {
            runBlocking {
                boundedWriter.tombstoneAllScopesInCurrentTransaction(CONVERSATION_ID, 20L)
            }
        }
        assertEquals("SOURCE_SCOPE_LIMIT_EXCEEDED", error.reasonCode)
    }

    @Test
    fun `non monotonic source time and fenced CAS conflict are rejected`() = runBlocking {
        val store = FakeConversationSourceAuthorityStore()
        val writer = ConversationSourceAuthorityWriter(store)
        writer.reconcileInCurrentTransaction(snapshot(occurredAtMs = 20L))

        val timeError = assertThrows(ConversationSourceAuthorityConflictException::class.java) {
            runBlocking {
                writer.reconcileInCurrentTransaction(
                    snapshot(assistantDigest = DIGEST_C, occurredAtMs = 19L),
                )
            }
        }
        assertEquals("SOURCE_AUTHORITY_TIME_REGRESSION", timeError.reasonCode)

        store.failNextMessageCas = true
        val casError = assertThrows(ConversationSourceAuthorityConflictException::class.java) {
            runBlocking {
                writer.reconcileInCurrentTransaction(
                    snapshot(assistantDigest = DIGEST_C, occurredAtMs = 21L),
                )
            }
        }
        assertEquals("SOURCE_MESSAGE_REVISION_CONFLICT", casError.reasonCode)
    }

    @Test
    fun `post commit wake is never requested for a duplicate outbox event`() = runBlocking {
        val store = FakeConversationSourceAuthorityStore()
        val events = FakeSourceEvents()
        val writer = ConversationSourceAuthorityWriter(store, events)
        writer.reconcileInCurrentTransaction(snapshot())
        val changed = writer.reconcileInCurrentTransaction(
            snapshot(assistantDigest = DIGEST_C, occurredAtMs = 20L),
        )

        assertTrue(changed.insertedOutbox)
        assertEquals(0, events.dispatchCount)
        writer.dispatchPostCommit(changed)
        assertEquals(1, events.dispatchCount)
        writer.dispatchPostCommit(changed.copy(insertedOutbox = false))
        assertEquals(1, events.dispatchCount)
    }

    @Test
    fun `edit branch supersession and deletion invalidate feedback once before one commit wake`() =
        runBlocking {
            val mutations = listOf(
                snapshot(assistantDigest = DIGEST_C, occurredAtMs = 20L),
                snapshot(selected = listOf(USER_MESSAGE_ID), occurredAtMs = 20L),
                snapshot(
                    messages = listOf(message(USER_MESSAGE_ID, "USER", DIGEST_A)),
                    selected = listOf(USER_MESSAGE_ID),
                    occurredAtMs = 20L,
                ),
            )
            mutations.forEach { mutation ->
                val store = FakeConversationSourceAuthorityStore()
                val events = FakeSourceEvents(acceptInserts = false)
                val invalidations = RecordingTransitionInvalidations()
                val writer = ConversationSourceAuthorityWriter(store, events, invalidations)
                writer.reconcileInCurrentTransaction(snapshot())

                val changed = writer.reconcileInCurrentTransaction(mutation)

                assertEquals(listOf(ASSISTANT_MESSAGE_ID), invalidations.targetIds)
                assertTrue(changed.insertedOutbox)
                assertEquals(0, events.dispatchCount)
                writer.dispatchPostCommit(changed)
                assertEquals(1, events.dispatchCount)

                val replay = writer.reconcileInCurrentTransaction(mutation)
                assertEquals(listOf(ASSISTANT_MESSAGE_ID), invalidations.targetIds)
                assertFalse(replay.insertedOutbox)
            }
        }

    private fun snapshot(
        messages: List<MessageSourceSnapshot> = listOf(
            message(USER_MESSAGE_ID, "USER", DIGEST_A),
            message(ASSISTANT_MESSAGE_ID, "ASSISTANT", DIGEST_B),
        ),
        selected: List<String> = listOf(USER_MESSAGE_ID, ASSISTANT_MESSAGE_ID),
        assistantDigest: String = DIGEST_B,
        occurredAtMs: Long = 10L,
        conversationDeleted: Boolean = false,
    ): ConversationSourceSnapshot {
        val adjustedMessages = messages.map { message ->
            if (message.messageId == ASSISTANT_MESSAGE_ID) {
                message.copy(payloadIntegritySha256 = assistantDigest)
            } else {
                message
            }
        }
        return ConversationSourceSnapshot(
            scope = SCOPE,
            conversationId = CONVERSATION_ID,
            assistantIdSnapshot = ASSISTANT_ID,
            messages = adjustedMessages,
            selectedBranchMessageIds = selected,
            occurredAtMs = occurredAtMs,
            conversationDeleted = conversationDeleted,
        )
    }

    private fun message(id: String, role: String, digest: String) = MessageSourceSnapshot(
        messageId = id,
        messageRole = role,
        payloadIntegritySha256 = digest,
    )

    private class FakeSourceEvents(
        private val acceptInserts: Boolean = true,
    ) : SourceInvalidationAuthorityEventPort {
        val events = mutableListOf<SourceInvalidationAuthorityEvent>()
        var dispatchCount = 0

        override suspend fun appendInCurrentTransaction(event: SourceInvalidationAuthorityEvent): Boolean {
            val inserted = events.none { existing ->
                existing.scope == event.scope && existing.objectKind == event.objectKind &&
                    existing.sourceId == event.sourceId &&
                    existing.sourceRevision == event.sourceRevision
            }
            if (inserted && acceptInserts) events += event
            return inserted && acceptInserts
        }

        override fun dispatchPostCommit(insertedOutbox: Boolean) {
            if (insertedOutbox) dispatchCount++
        }
    }

    private class RecordingTransitionInvalidations : MessageSourceTransitionInvalidationPort {
        val targetIds = mutableListOf<String>()

        override suspend fun invalidateInCurrentTransaction(
            transition: MessageSourceRevisionTransition,
        ): Boolean {
            targetIds += transition.current.messageId
            return true
        }
    }

    private companion object {
        val SCOPE = ConversationSourceScope(
            ConversationSourceScopeKind.ASSISTANT,
            "00000000-0000-0000-0000-000000000001",
        )
        const val CONVERSATION_ID = "00000000-0000-0000-0000-000000000002"
        const val ASSISTANT_ID = "00000000-0000-0000-0000-000000000001"
        const val USER_MESSAGE_ID = "00000000-0000-0000-0000-000000000003"
        const val ASSISTANT_MESSAGE_ID = "00000000-0000-0000-0000-000000000004"
        val DIGEST_A = "a".repeat(64)
        val DIGEST_B = "b".repeat(64)
        val DIGEST_C = "c".repeat(64)
    }
}

/**
 * `internal` rather than file-private so the continuation-integrity suite can drive the real writer
 * through it. A second copy of this fake would be a second answer to "what does the store do", and
 * the two would drift the first time the interface moved.
 */
internal class FakeConversationSourceAuthorityStore : ConversationSourceAuthorityStore {
    private val conversations = linkedMapOf<String, ConversationSourceAuthorityHead>()
    private val messages = linkedMapOf<String, MessageSourceAuthorityHead>()
    var failNextMessageCas = false

    override suspend fun findConversation(
        scope: ConversationSourceScope,
        conversationId: String,
    ): ConversationSourceAuthorityHead? = conversations[conversationKey(scope, conversationId)]

    override suspend fun insertConversationInitial(head: ConversationSourceAuthorityHead): Boolean {
        val key = conversationKey(head.scope, head.conversationId)
        if (key in conversations) return false
        conversations[key] = head
        return true
    }

    override suspend fun updateConversationFenced(
        expectedRevision: Long,
        head: ConversationSourceAuthorityHead,
    ): Boolean {
        val key = conversationKey(head.scope, head.conversationId)
        val current = conversations[key] ?: return false
        if (current.sourceRevision != expectedRevision || current.sourceState == ConversationSourceState.TOMBSTONED) {
            return false
        }
        conversations[key] = head
        return true
    }

    override suspend fun countConversationScopes(conversationId: String): Int =
        conversations.values.count {
            it.conversationId == conversationId &&
                it.sourceState != ConversationSourceState.TOMBSTONED
        }

    override suspend fun listConversationScopesAfter(
        conversationId: String,
        afterScopeKind: String,
        afterScopeId: String,
        limit: Int,
    ): List<ConversationSourceAuthorityHead> {
        val after = "$afterScopeKind\u0000$afterScopeId"
        return conversations.values
            .asSequence()
            .filter {
                it.conversationId == conversationId &&
                    it.sourceState != ConversationSourceState.TOMBSTONED
            }
            .filter { "${it.scope.kind.name}\u0000${it.scope.id}" > after }
            .sortedBy { "${it.scope.kind.name}\u0000${it.scope.id}" }
            .take(limit)
            .toList()
    }

    override suspend fun findMessage(
        scope: ConversationSourceScope,
        messageId: String,
    ): MessageSourceAuthorityHead? = messages[messageKey(scope, messageId)]

    override suspend fun insertMessageInitial(head: MessageSourceAuthorityHead): Boolean {
        val key = messageKey(head.scope, head.messageId)
        if (key in messages) return false
        messages[key] = head
        return true
    }

    override suspend fun updateMessageFenced(
        expectedRevision: Long,
        head: MessageSourceAuthorityHead,
    ): Boolean {
        if (failNextMessageCas) {
            failNextMessageCas = false
            return false
        }
        val key = messageKey(head.scope, head.messageId)
        val current = messages[key] ?: return false
        if (current.sourceRevision != expectedRevision || current.sourceState == ConversationSourceState.TOMBSTONED) {
            return false
        }
        messages[key] = head
        return true
    }

    override suspend fun countMessagesForConversation(
        scope: ConversationSourceScope,
        conversationId: String,
    ): Int = messages.values.count { it.scope == scope && it.conversationId == conversationId }

    override suspend fun listMessagesForConversationAfter(
        scope: ConversationSourceScope,
        conversationId: String,
        afterMessageId: String,
        limit: Int,
    ): List<MessageSourceAuthorityHead> = messages.values
        .asSequence()
        .filter { it.scope == scope && it.conversationId == conversationId && it.messageId > afterMessageId }
        .sortedBy(MessageSourceAuthorityHead::messageId)
        .take(limit)
        .toList()

    fun copyState(): FakeConversationSourceAuthorityStore = FakeConversationSourceAuthorityStore().also { copy ->
        copy.conversations.putAll(conversations)
        copy.messages.putAll(messages)
    }

    fun restoreFrom(source: FakeConversationSourceAuthorityStore) {
        conversations.clear()
        conversations.putAll(source.conversations)
        messages.clear()
        messages.putAll(source.messages)
    }

    private fun conversationKey(scope: ConversationSourceScope, conversationId: String): String =
        "${scope.kind}:${scope.id}:$conversationId"

    private fun messageKey(scope: ConversationSourceScope, messageId: String): String =
        "${scope.kind}:${scope.id}:$messageId"
}
