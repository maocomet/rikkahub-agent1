package me.rerere.rikkahub.data.repository

import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order an assistant's data is cleaned up in, and what the guards protect.
 *
 * Exercised through [AssistantRemovalSequence] rather than [AssistantRemovalService] because the
 * service's real collaborators are Android-bound — `SettingsStore` needs a Context and the
 * repositories need the database — so the sequence is where this logic is reachable from a JVM
 * test. The service is a thin adapter over it, and `AssistantRemovalServiceWiringTest` pins that
 * adapter to the production dependencies.
 *
 * The property under test is the one that is expensive to get wrong and invisible when it is:
 * nothing is deleted until the removal is certain. A protected second user, or an assistant with a
 * retained conversation, must come out of this with its Cat Garden posts, comments and likes
 * intact — not merely with its settings entry preserved.
 */
class AssistantRemovalServiceTest {

    private val assistantId = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000001")
    private val assistant = Assistant(id = assistantId, name = "Mimi")

    private val calls = mutableListOf<String>()
    private val spaceSweeps = mutableListOf<String>()

    private fun sequence(
        assistants: List<Assistant> = listOf(assistant),
        deletionProtected: Boolean = false,
        retainedConversations: List<Uuid> = emptyList(),
    ) = AssistantRemovalSequence(
        currentAssistants = { calls += "assistants"; assistants },
        isDeletionProtected = { calls += "protected-check"; deletionProtected },
        deleteConversationsOf = {
            calls += "conversations"
            ConversationBatchDeletionResult(deleted = 1, retained = retainedConversations)
        },
        cleanupFiles = { calls += "files" },
        deleteMemoriesOf = { calls += "memories" },
        deleteSpaceFootprintOf = { id ->
            calls += "space-footprint"
            spaceSweeps += id
        },
        removeFromSettings = { calls += "settings" },
    )

    @Test
    fun `a removal that is certain cleans everything up in one order`() = runBlocking {
        val result = sequence().remove(assistant)

        assertEquals(AssistantRemovalResult.Removed, result)
        // Asserted as a whole sequence, not as a set: the guards have to come before every
        // destructive step, and the Cat Garden sweep has to be part of the cleanup rather than a
        // step that some future edit appends after the assistant is already gone from settings.
        assertEquals(
            listOf(
                "assistants",
                "protected-check",
                "conversations",
                "files",
                "memories",
                "space-footprint",
                "settings",
            ),
            calls,
        )
        assertEquals(listOf(assistantId.toString()), spaceSweeps)
    }

    @Test
    fun `a protected second user keeps its Cat Garden footprint`() = runBlocking {
        val result = sequence(deletionProtected = true).remove(assistant)

        assertEquals(AssistantRemovalResult.RetainedSecondUser, result)
        assertEquals(listOf("assistants", "protected-check"), calls)
        assertTrue("a protected assistant's space data must not be touched", spaceSweeps.isEmpty())
    }

    @Test
    fun `an assistant with a retained conversation keeps its Cat Garden footprint`() = runBlocking {
        val result = sequence(retainedConversations = listOf(Uuid.random())).remove(assistant)

        assertEquals(AssistantRemovalResult.RetainedSecondUser, result)
        assertEquals(listOf("assistants", "protected-check", "conversations"), calls)
        assertTrue("a half-removed assistant's space data must survive", spaceSweeps.isEmpty())
    }

    @Test
    fun `an assistant that is not in settings has nothing cleaned up`() = runBlocking {
        val result = sequence(assistants = emptyList()).remove(assistant)

        assertEquals(AssistantRemovalResult.NotFound, result)
        assertEquals(listOf("assistants"), calls)
        assertTrue(spaceSweeps.isEmpty())
    }

    @Test
    fun `the sweep is aimed at the assistant being removed, by its stable id`() = runBlocking {
        val other = Assistant(id = Uuid.parse("bbbbbbbb-0000-0000-0000-000000000002"), name = "Other")
        sequence(assistants = listOf(assistant, other)).remove(assistant)

        assertEquals(listOf(assistantId.toString()), spaceSweeps)
    }
}
