package me.rerere.rikkahub.data.repository

import androidx.core.net.toUri
import kotlin.uuid.Uuid
import me.rerere.rikkahub.assistant.SecondUserAuthorityService
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.space.SpaceRepository

sealed interface AssistantRemovalResult {
    data object Removed : AssistantRemovalResult
    data object RetainedSecondUser : AssistantRemovalResult
    data object NotFound : AssistantRemovalResult
}

/** Deletes dependent data only after the global second-user protection has been checked. */
class AssistantRemovalService(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val conversations: ConversationRepository,
    private val filesManager: FilesManager,
    private val authority: SecondUserAuthorityService,
    private val spaceRepository: SpaceRepository,
) {
    suspend fun remove(assistant: Assistant): AssistantRemovalResult = AssistantRemovalSequence(
        currentAssistants = { settingsStore.settingsFlow.value.assistants },
        isDeletionProtected = { authority.isAssistantDeletionProtected(it) },
        deleteConversationsOf = { conversations.deleteConversationOfAssistant(it) },
        cleanupFiles = { cleanupAssistantFiles(it) },
        deleteMemoriesOf = { memoryRepository.deleteMemoriesOfAssistant(it) },
        deleteSpaceFootprintOf = { spaceRepository.deleteAssistantFootprint(it) },
        removeFromSettings = { id ->
            settingsStore.update { current ->
                current.copy(assistants = current.assistants.filter { it.id != id })
            }
        },
    ).remove(assistant)

    private fun cleanupAssistantFiles(assistant: Assistant) {
        val uris = buildList {
            (assistant.avatar as? Avatar.Image)?.let { add(it.url.toUri()) }
            assistant.background?.let { add(it.toUri()) }
        }
        if (uris.isNotEmpty()) filesManager.deleteChatFiles(uris)
    }
}

/**
 * The order in which a removed assistant's data is cleaned up.
 *
 * Pulled out of [AssistantRemovalService] with each collaborator reduced to the single call it is
 * needed for, because the thing worth pinning here is the ORDER and it is not reachable otherwise:
 * the service's real collaborators are Android-bound — `SettingsStore` needs a Context, and the
 * repositories need the database — so a JVM test cannot construct one. The indirection costs one
 * object per removal.
 *
 * **The order is the guarantee.** Every guard returns before any destructive step, so an assistant
 * the person is not allowed to remove — a protected second user, or one whose conversation is
 * retained — keeps all of its data, Cat Garden included. Nothing is erased until the removal is
 * certain.
 *
 * Past that point, the assistant's Cat Garden footprint is part of the cleanup rather than an
 * afterthought. `space_posts.author_id` is deliberately not a foreign key, so nothing removes those
 * rows automatically: leaving them behind turns the assistant into a ghost author whose posts stay
 * in the timeline, and its comments and likes would outlive it on other identities' posts.
 */
internal class AssistantRemovalSequence(
    private val currentAssistants: suspend () -> List<Assistant>,
    private val isDeletionProtected: suspend (Uuid) -> Boolean,
    private val deleteConversationsOf: suspend (Uuid) -> ConversationBatchDeletionResult,
    private val cleanupFiles: suspend (Assistant) -> Unit,
    private val deleteMemoriesOf: suspend (String) -> Unit,
    /** Erases the assistant's Cat Garden rows. See [SpaceRepository.deleteAssistantFootprint]. */
    private val deleteSpaceFootprintOf: suspend (String) -> Unit,
    private val removeFromSettings: suspend (Uuid) -> Unit,
) {
    suspend fun remove(assistant: Assistant): AssistantRemovalResult {
        if (currentAssistants().none { it.id == assistant.id }) return AssistantRemovalResult.NotFound
        if (isDeletionProtected(assistant.id)) return AssistantRemovalResult.RetainedSecondUser
        if (deleteConversationsOf(assistant.id).retained.isNotEmpty()) {
            return AssistantRemovalResult.RetainedSecondUser
        }

        // Removal is certain from here on. Everything below has to happen, and Cat Garden is one of
        // the things that has to happen: memory and conversations are not the only place an
        // assistant leaves a trace.
        cleanupFiles(assistant)
        deleteMemoriesOf(assistant.id.toString())
        deleteSpaceFootprintOf(assistant.id.toString())
        removeFromSettings(assistant.id)
        return AssistantRemovalResult.Removed
    }
}
