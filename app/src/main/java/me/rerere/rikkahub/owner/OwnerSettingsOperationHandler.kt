package me.rerere.rikkahub.owner

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.context.ABSOLUTE_CONTEXT_WINDOW_TOKENS
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.ai.tools.local.ConversationExportResult
import me.rerere.rikkahub.data.ai.tools.local.exportConversationToDownloads
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.AssistantRemovalResult
import me.rerere.rikkahub.data.repository.AssistantRemovalService
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import me.rerere.rikkahub.security.SecretBinding
import me.rerere.rikkahub.security.SecretBindingKind
import me.rerere.rikkahub.security.SecretBindingResolution
import me.rerere.rikkahub.security.SecondUserSecretVault
import me.rerere.rikkahub.security.resolveProviderBinding
import kotlin.uuid.Uuid

/** Typed Settings/Room adapter for the high-value assistant, conversation and Provider actions. */
class OwnerSettingsOperationHandler(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val conversations: ConversationRepository,
    private val assistantRemoval: AssistantRemovalService,
    private val providerManager: ProviderManager,
    private val vault: SecondUserSecretVault,
    private val selfPreservation: OwnerSelfPreservationGuard = OwnerSelfPreservationGuard(),
) : OwnerOperationHandler {
    override fun supports(request: OwnerOperationRequest, action: OwnerAction): Boolean =
        action.type in ACTION_FIELDS

    override suspend fun validate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        selfPreservation.validate(action)?.let { return it }
        val fields = ACTION_FIELDS[action.type]
            ?: return invalid("OWNER_ACTION_UNSUPPORTED", "Unsupported Owner settings action.")
        if ((action.arguments.keys - fields).isNotEmpty()) {
            return invalid("OWNER_UNSUPPORTED_FIELD", "Action contains a field outside its typed contract.")
        }
        OPTIONAL_UUID_FIELDS[action.type].orEmpty().forEach { field ->
            if (action.arguments.string(field) != null && action.arguments.uuid(field) == null) {
                return invalid("OWNER_ID_INVALID", "$field must be a UUID when supplied.")
            }
        }
        val forbidden = action.arguments.keys.any { key ->
            key.lowercase() in setOf("api_key", "secret", "password", "token", "private_key", "headers")
        }
        if (forbidden) return invalid("OWNER_SECRET_ARGUMENT_FORBIDDEN", "Provider secrets must be referenced by Vault slot ID.")
        if (action.type.startsWith("provider_") &&
            action.type !in setOf("provider_list", "provider_create", "provider_set_default", "provider_route_set")
        ) {
            val id = action.arguments.uuid("provider_id")
                ?: return invalid("PROVIDER_ID_REQUIRED", "provider_id is required.")
            if (settingsStore.settingsFlow.value.providers.none { it.id == id }) {
                return invalid("PROVIDER_NOT_FOUND", "Provider does not exist.")
            }
        }
        action.arguments.string("vault_slot_id")?.takeIf { it.isNotBlank() }?.let { slotId ->
            if (vault.listMetadata(request.authoritySubjectId).none { it.slotId == slotId }) {
                return invalid("SECRET_SLOT_MISSING", "The requested Vault slot does not exist for this authority epoch.")
            }
        }
        return OwnerActionValidation(true, "OWNER_ACTION_VALID", "Action validated.")
    }

    override suspend fun apply(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerAppliedAction = runCatching {
        when (action.type) {
            "assistant_create" -> assistantCreate(index, action)
            "assistant_clone" -> assistantClone(index, action)
            "assistant_delete" -> assistantDelete(index, action)
            "assistant_set_default" -> assistantSetDefault(index, action)
            "assistant_switch_model" -> assistantSwitchModel(index, action)
            "assistant_switch_tts" -> assistantSwitchTts(index, action)
            "conversation_create" -> conversationCreate(index, action)
            "conversation_branch" -> conversationBranch(index, action)
            "conversation_archive" -> conversationArchive(index, action, archived = true)
            "conversation_restore" -> conversationArchive(index, action, archived = false)
            "conversation_search" -> conversationSearch(index, action)
            "conversation_export" -> conversationExport(index, action)
            "conversation_open" -> conversationOpen(index, action)
            "provider_list" -> providerList(index)
            "provider_create" -> providerCreate(index, request, action)
            "provider_update" -> providerUpdate(index, action)
            "provider_delete" -> providerDelete(index, request, action)
            "provider_refresh_models" -> providerRefresh(index, request, action, persist = true)
            "provider_test" -> providerRefresh(index, request, action, persist = false)
            "provider_set_default" -> providerSetDefault(index, action)
            "provider_model_list" -> providerModelList(index, action)
            "provider_model_upsert" -> providerModelUpsert(index, action)
            "provider_model_delete" -> providerModelDelete(index, action)
            "provider_route_set" -> providerRouteSet(index, action)
            else -> failure(index, action.type, "OWNER_ACTION_UNSUPPORTED", "Unsupported action.")
        }
    }.getOrElse {
        failure(index, action.type, "OWNER_OPERATION_FAILED", "The typed Owner settings operation failed.")
    }

    override suspend fun verify(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation = if (applied.result.ok) {
        OwnerActionValidation(true, "OWNER_ACTION_VERIFIED", "Repository state was verified by the typed operation.")
    } else invalid(applied.result.code, applied.result.message)

    override suspend fun compensate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerCompensationResult {
        val receipt = applied.compensationReceipt as? SettingsReceipt
            ?: return OwnerCompensationResult(
                compensated = action.risk == OwnerOperationRisk.READ_ONLY,
                code = if (action.risk == OwnerOperationRisk.READ_ONLY) {
                    "SETTINGS_NO_COMPENSATION_REQUIRED"
                } else {
                    "SETTINGS_COMPENSATION_UNAVAILABLE"
                },
            )
        return runCatching {
            when (receipt) {
                is SettingsReceipt.CreatedAssistant -> settingsStore.update { current ->
                    current.copy(assistants = current.assistants.filterNot { it.id == receipt.id })
                }
                is SettingsReceipt.PreviousAssistant -> settingsStore.update { current ->
                    current.copy(assistants = current.assistants.map { if (it.id == receipt.value.id) receipt.value else it })
                }
                is SettingsReceipt.PreviousDefaultAssistant -> settingsStore.update { it.copy(assistantId = receipt.id) }
                is SettingsReceipt.PreviousDefaultTts -> settingsStore.update { it.copy(selectedTTSProviderId = receipt.id) }
                is SettingsReceipt.CreatedConversation -> conversations.getConversationById(receipt.id)?.let {
                    conversations.deleteConversation(it)
                }
                is SettingsReceipt.PreviousConversation -> conversations.updateConversation(receipt.value)
                is SettingsReceipt.CreatedProvider -> {
                    settingsStore.update { current ->
                        current.copy(providers = current.providers.filterNot { it.id == receipt.id })
                    }
                    removeProviderBindings(receipt.id, request.authoritySubjectId)
                }
                is SettingsReceipt.PreviousProvider -> settingsStore.update { current ->
                    current.copy(providers = current.providers.map { if (it.id == receipt.value.id) receipt.value else it })
                }
                is SettingsReceipt.PreviousDefaultModel -> settingsStore.update { current ->
                    if (receipt.assistantId == null) {
                        current.copy(chatModelId = requireNotNull(receipt.modelId))
                    } else {
                        current.copy(assistants = current.assistants.map { assistant ->
                            if (assistant.id == receipt.assistantId) assistant.copy(chatModelId = receipt.modelId) else assistant
                        })
                    }
                }
                is SettingsReceipt.PreviousSettings -> settingsStore.update { receipt.value }
                is SettingsReceipt.DeletedProvider -> {
                    settingsStore.update { receipt.settings }
                    restoreProviderBindings(
                        providerId = receipt.providerId,
                        authoritySubjectId = request.authoritySubjectId,
                        snapshot = receipt.bindings,
                    )
                }
            }
            OwnerCompensationResult(true, "SETTINGS_STATE_RESTORED")
        }.getOrElse { OwnerCompensationResult(false, "SETTINGS_COMPENSATION_FAILED") }
    }

    private suspend fun assistantCreate(index: Int, action: OwnerAction): OwnerAppliedAction {
        val args = action.arguments
        val modelId = args.uuid("model_id")
        val settings = settingsStore.settingsFlow.value
        if (modelId != null && settings.findModelById(modelId) == null) {
            return failure(index, action.type, "MODEL_NOT_FOUND", "Selected model does not exist.")
        }
        val assistant = Assistant(
            id = args.uuid("assistant_id") ?: Uuid.random(),
            name = args.string("name")?.trim()?.take(200).orEmpty(),
            systemPrompt = args.string("system_prompt")?.take(128 * 1024).orEmpty(),
            chatModelId = modelId,
        )
        if (settings.assistants.any { it.id == assistant.id }) {
            return failure(index, action.type, "ASSISTANT_ALREADY_EXISTS", "assistant_id already exists.")
        }
        settingsStore.update { it.copy(assistants = it.assistants + assistant) }
        return success(
            index, action.type, "ASSISTANT_CREATED", "Assistant created.",
            idData("assistant_id", assistant.id), SettingsReceipt.CreatedAssistant(assistant.id),
        )
    }

    private suspend fun assistantClone(index: Int, action: OwnerAction): OwnerAppliedAction {
        val sourceId = action.arguments.uuid("source_assistant_id")
            ?: return failure(index, action.type, "ASSISTANT_ID_REQUIRED", "source_assistant_id is required.")
        val source = settingsStore.settingsFlow.value.assistants.firstOrNull { it.id == sourceId }
            ?: return failure(index, action.type, "ASSISTANT_NOT_FOUND", "Source assistant does not exist.")
        val clone = source.copy(
            id = action.arguments.uuid("assistant_id") ?: Uuid.random(),
            name = action.arguments.string("name")?.trim()?.take(200)
                ?: "${source.name.ifBlank { "Assistant" }} copy",
            unrestricted = false,
            privilegedConversationId = null,
            secondUserPolicyConfirmed = false,
            allowConversationHistoryRead = false,
            // A capability, not a preference: silently carrying "this assistant may send from the
            // person's sticker library" into a new assistant is access nobody granted. Same
            // reasoning as allowConversationHistoryRead above.
            stickerToolsEnabled = false,
            petEnabled = false,
            petBootRestoreEnabled = false,
        )
        if (settingsStore.settingsFlow.value.assistants.any { it.id == clone.id }) {
            return failure(index, action.type, "ASSISTANT_ALREADY_EXISTS", "assistant_id already exists.")
        }
        settingsStore.update { it.copy(assistants = it.assistants + clone) }
        return success(
            index, action.type, "ASSISTANT_CLONED", "Assistant cloned without Owner identity.",
            idData("assistant_id", clone.id), SettingsReceipt.CreatedAssistant(clone.id),
        )
    }

    private suspend fun assistantDelete(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("assistant_id")
            ?: return failure(index, action.type, "ASSISTANT_ID_REQUIRED", "assistant_id is required.")
        val settings = settingsStore.settingsFlow.value
        if (settings.assistants.size <= 1) return failure(index, action.type, "LAST_ASSISTANT", "The final assistant cannot be removed.")
        val assistant = settings.assistants.firstOrNull { it.id == id }
            ?: return failure(index, action.type, "ASSISTANT_NOT_FOUND", "Assistant does not exist.")
        return when (assistantRemoval.remove(assistant)) {
            AssistantRemovalResult.Removed -> success(index, action.type, "ASSISTANT_DELETED", "Assistant and unprotected dependent data were deleted.")
            AssistantRemovalResult.RetainedSecondUser -> failure(index, action.type, "OWNER_PERMANENT_PROTECTION", "The protected Owner assistant cannot be deleted.")
            AssistantRemovalResult.NotFound -> failure(index, action.type, "ASSISTANT_NOT_FOUND", "Assistant does not exist.")
        }
    }

    private suspend fun assistantSetDefault(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("assistant_id")
            ?: return failure(index, action.type, "ASSISTANT_ID_REQUIRED", "assistant_id is required.")
        if (settingsStore.settingsFlow.value.assistants.none { it.id == id }) {
            return failure(index, action.type, "ASSISTANT_NOT_FOUND", "Assistant does not exist.")
        }
        val previous = settingsStore.settingsFlow.value.assistantId
        settingsStore.update { it.copy(assistantId = id) }
        return success(
            index, action.type, "DEFAULT_ASSISTANT_UPDATED", "Default assistant updated.",
            receipt = SettingsReceipt.PreviousDefaultAssistant(previous),
        )
    }

    private suspend fun assistantSwitchModel(index: Int, action: OwnerAction): OwnerAppliedAction {
        val assistantId = action.arguments.uuid("assistant_id")
            ?: return failure(index, action.type, "ASSISTANT_ID_REQUIRED", "assistant_id is required.")
        val modelId = action.arguments.uuid("model_id")
            ?: return failure(index, action.type, "MODEL_ID_REQUIRED", "model_id is required.")
        val settings = settingsStore.settingsFlow.value
        if (settings.findModelById(modelId) == null) return failure(index, action.type, "MODEL_NOT_FOUND", "Model does not exist.")
        if (settings.assistants.none { it.id == assistantId }) return failure(index, action.type, "ASSISTANT_NOT_FOUND", "Assistant does not exist.")
        settingsStore.update { current ->
            current.copy(assistants = current.assistants.map { assistant ->
                if (assistant.id == assistantId) assistant.copy(chatModelId = modelId) else assistant
            })
        }
        val previous = settings.assistants.first { it.id == assistantId }
        return success(
            index, action.type, "ASSISTANT_MODEL_UPDATED", "Assistant model updated.",
            receipt = SettingsReceipt.PreviousAssistant(previous),
        )
    }

    private suspend fun assistantSwitchTts(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("tts_provider_id")
            ?: return failure(index, action.type, "TTS_ID_REQUIRED", "tts_provider_id is required.")
        if (settingsStore.settingsFlow.value.ttsProviders.none { it.id == id }) {
            return failure(index, action.type, "TTS_NOT_FOUND", "TTS Provider does not exist.")
        }
        val previous = settingsStore.settingsFlow.value.selectedTTSProviderId
        settingsStore.update { it.copy(selectedTTSProviderId = id) }
        return success(
            index, action.type, "TTS_DEFAULT_UPDATED", "Default TTS Provider updated.",
            receipt = SettingsReceipt.PreviousDefaultTts(previous),
        )
    }

    private suspend fun conversationBranch(index: Int, action: OwnerAction): OwnerAppliedAction {
        val sourceId = action.arguments.uuid("conversation_id")
            ?: return failure(index, action.type, "CONVERSATION_ID_REQUIRED", "conversation_id is required.")
        val source = conversations.getConversationById(sourceId)
            ?: return failure(index, action.type, "CONVERSATION_NOT_FOUND", "Conversation does not exist.")
        val branch = source.copy(
            id = action.arguments.uuid("new_conversation_id") ?: Uuid.random(),
            title = action.arguments.string("title")?.trim()?.take(200)
                ?: "${source.title.ifBlank { "Conversation" }} branch",
            messageNodes = source.messageNodes.map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message -> message.copy(id = Uuid.random()) },
                )
            },
            isPinned = false,
            createAt = java.time.Instant.now(),
            updateAt = java.time.Instant.now(),
            newConversation = false,
        )
        if (conversations.existsConversationById(branch.id)) {
            return failure(index, action.type, "CONVERSATION_ALREADY_EXISTS", "new_conversation_id already exists.")
        }
        conversations.insertConversation(branch)
        return success(
            index, action.type, "CONVERSATION_BRANCHED", "Conversation branch created.",
            idData("conversation_id", branch.id), SettingsReceipt.CreatedConversation(branch.id),
        )
    }

    private suspend fun conversationCreate(index: Int, action: OwnerAction): OwnerAppliedAction {
        val assistantId = action.arguments.uuid("assistant_id")
            ?: return failure(index, action.type, "ASSISTANT_ID_REQUIRED", "assistant_id is required.")
        if (settingsStore.settingsFlow.value.assistants.none { it.id == assistantId }) {
            return failure(index, action.type, "ASSISTANT_NOT_FOUND", "Assistant does not exist.")
        }
        val conversationId = action.arguments.uuid("conversation_id") ?: Uuid.random()
        if (conversations.existsConversationById(conversationId)) {
            return failure(index, action.type, "CONVERSATION_ALREADY_EXISTS", "conversation_id already exists.")
        }
        val conversation = me.rerere.rikkahub.data.model.Conversation.ofId(
            id = conversationId,
            assistantId = assistantId,
            newConversation = true,
        ).copy(title = action.arguments.string("title")?.trim()?.take(200).orEmpty())
        conversations.insertConversation(conversation)
        return success(
            index, action.type, "CONVERSATION_CREATED", "Conversation created.",
            idData("conversation_id", conversationId), SettingsReceipt.CreatedConversation(conversationId),
        )
    }

    private suspend fun conversationArchive(index: Int, action: OwnerAction, archived: Boolean): OwnerAppliedAction {
        val id = action.arguments.uuid("conversation_id")
            ?: return failure(index, action.type, "CONVERSATION_ID_REQUIRED", "conversation_id is required.")
        val conversation = conversations.getConversationById(id)
            ?: return failure(index, action.type, "CONVERSATION_NOT_FOUND", "Conversation does not exist.")
        val folder = if (archived) OWNER_ARCHIVE_FOLDER else conversation.folderId.takeUnless { it == OWNER_ARCHIVE_FOLDER }.orEmpty()
        conversations.updateConversation(conversation.copy(folderId = folder, updateAt = java.time.Instant.now()))
        return success(
            index,
            action.type,
            if (archived) "CONVERSATION_ARCHIVED" else "CONVERSATION_RESTORED",
            if (archived) "Conversation archived." else "Conversation restored.",
            receipt = SettingsReceipt.PreviousConversation(conversation),
        )
    }

    private suspend fun conversationSearch(index: Int, action: OwnerAction): OwnerAppliedAction {
        val query = action.arguments.string("query")?.trim()?.take(200).orEmpty()
        val limit = action.arguments.int("limit")?.coerceIn(1, 50) ?: 20
        val results = conversations.searchConversations(query).first().take(limit)
        return success(index, action.type, "CONVERSATION_SEARCH_RESULTS", "Conversation search completed.", buildJsonObject {
            put("items", buildJsonArray {
                results.forEach { conversation -> add(buildJsonObject {
                    put("conversation_id", conversation.id.toString())
                    put("assistant_id", conversation.assistantId.toString())
                    put("title", conversation.title.take(200))
                    put("archived", conversation.folderId == OWNER_ARCHIVE_FOLDER)
                }) }
            })
        })
    }

    private suspend fun conversationOpen(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("conversation_id")
            ?: return failure(index, action.type, "CONVERSATION_ID_REQUIRED", "conversation_id is required.")
        val conversation = conversations.getConversationById(id)
            ?: return failure(index, action.type, "CONVERSATION_NOT_FOUND", "Conversation does not exist.")
        val previous = settingsStore.settingsFlow.value.assistantId
        settingsStore.update { it.copy(assistantId = conversation.assistantId) }
        OwnerNavigationMailbox.offer(OwnerNavigationTarget.Conversation(id.toString()))
        return success(
            index, action.type, "CONVERSATION_OPEN_REQUESTED", "Conversation navigation requested.",
            receipt = SettingsReceipt.PreviousDefaultAssistant(previous),
        )
    }

    private suspend fun conversationExport(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("conversation_id")
            ?: return failure(index, action.type, "CONVERSATION_ID_REQUIRED", "conversation_id is required.")
        return when (val result = exportConversationToDownloads(context, conversations, id)) {
            is ConversationExportResult.Failure -> failure(index, action.type, result.code, result.message)
            is ConversationExportResult.Success -> success(
                index = index,
                type = action.type,
                code = "CONVERSATION_EXPORTED",
                message = "Conversation exported to Downloads/chat-exports.",
                data = buildJsonObject { put("filename", result.filename) },
            )
        }
    }

    private fun providerList(index: Int): OwnerAppliedAction {
        val settings = settingsStore.settingsFlow.value
        return success(index, "provider_list", "PROVIDER_LIST", "Provider metadata returned.", buildJsonObject {
            put("providers", buildJsonArray {
                settings.providers.forEach { provider -> add(buildJsonObject {
                    put("provider_id", provider.id.toString())
                    put("type", provider.typeName())
                    put("name", provider.name.take(200))
                    put("enabled", provider.enabled)
                    put("model_count", provider.models.size)
                    put("built_in", provider.builtIn)
                }) }
            })
        })
    }

    private suspend fun providerCreate(index: Int, request: OwnerOperationRequest, action: OwnerAction): OwnerAppliedAction {
        val type = action.arguments.string("provider_type")?.lowercase()
            ?: return failure(index, action.type, "PROVIDER_TYPE_REQUIRED", "provider_type is required.")
        val id = action.arguments.uuid("provider_id") ?: Uuid.random()
        if (settingsStore.settingsFlow.value.providers.any { it.id == id }) {
            return failure(index, action.type, "PROVIDER_ALREADY_EXISTS", "provider_id already exists.")
        }
        val name = action.arguments.string("name")?.trim()?.take(200).orEmpty().ifBlank { type.replaceFirstChar(Char::uppercase) }
        val baseUrl = action.arguments.string("base_url")?.trim()?.take(2048)
        val provider: ProviderSetting = when (type) {
            "openai", "openai_compatible" -> ProviderSetting.OpenAI(id = id, name = name, baseUrl = baseUrl ?: "https://api.openai.com/v1")
            "google" -> ProviderSetting.Google(id = id, name = name, baseUrl = baseUrl ?: "https://generativelanguage.googleapis.com/v1beta")
            "claude" -> ProviderSetting.Claude(id = id, name = name, baseUrl = baseUrl ?: "https://api.anthropic.com/v1")
            else -> return failure(index, action.type, "PROVIDER_TYPE_UNSUPPORTED", "Only OpenAI-compatible, Google and Claude are supported.")
        }
        settingsStore.update { it.copy(providers = it.providers + provider) }
        try {
            action.arguments.string("vault_slot_id")?.takeIf { it.isNotBlank() }?.let { slotId ->
                bindProviderSlot(slotId, request.authoritySubjectId, provider.id)
            }
        } catch (failure: Throwable) {
            settingsStore.update { current -> current.copy(providers = current.providers.filterNot { it.id == id }) }
            throw failure
        }
        return success(
            index, action.type, "PROVIDER_CREATED", "Provider created with no plaintext credential in Settings.",
            idData("provider_id", id), SettingsReceipt.CreatedProvider(id),
        )
    }

    private suspend fun providerUpdate(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = requireNotNull(action.arguments.uuid("provider_id"))
        val old = settingsStore.settingsFlow.value.providers.first { it.id == id }
        val name = action.arguments.string("name")?.trim()?.take(200) ?: old.name
        val enabled = action.arguments.boolean("enabled") ?: old.enabled
        val baseUrl = action.arguments.string("base_url")?.trim()?.take(2048)
        val updated = old.withSafeFields(name, enabled, baseUrl)
        settingsStore.update { current -> current.copy(providers = current.providers.map { if (it.id == id) updated else it }) }
        return success(
            index, action.type, "PROVIDER_UPDATED", "Provider metadata updated.",
            receipt = SettingsReceipt.PreviousProvider(old),
        )
    }

    private suspend fun providerDelete(index: Int, request: OwnerOperationRequest, action: OwnerAction): OwnerAppliedAction {
        val id = requireNotNull(action.arguments.uuid("provider_id"))
        val settings = settingsStore.settingsFlow.value
        val provider = settings.providers.first { it.id == id }
        val modelIds = provider.models.mapTo(hashSetOf()) { it.id }
        val replacementId = action.arguments.uuid("replacement_model_id")
        if (action.arguments.string("replacement_model_id") != null && replacementId == null) {
            return failure(index, action.type, "PROVIDER_REPLACEMENT_INVALID", "replacement_model_id must be a UUID.")
        }
        val referencesDeletedModel = settings.referencesAnyModel(modelIds)
        val requiredReplacementTypes = settings.ownerReferencedModelTypes(modelIds)
        if (requiredReplacementTypes.size > 1) {
            return failure(
                index,
                action.type,
                "PROVIDER_REPLACEMENT_TYPE_AMBIGUOUS",
                "This Provider supplies both active chat and image routes. Switch one route in the same Owner request before deleting it.",
            )
        }
        if (referencesDeletedModel && replacementId == null) {
            return failure(index, action.type, "PROVIDER_REPLACEMENT_REQUIRED", "replacement_model_id is required because this Provider is currently in use.")
        }
        if (replacementId != null) {
            val replacement = settings.findModelById(replacementId)
            if (replacementId in modelIds || replacement == null) {
                return failure(index, action.type, "PROVIDER_REPLACEMENT_INVALID", "Replacement model must exist outside the Provider being deleted.")
            }
            val requiredType = requiredReplacementTypes.singleOrNull()
            if (requiredType != null && replacement.type != requiredType) {
                return failure(index, action.type, "PROVIDER_REPLACEMENT_TYPE_INVALID", "Replacement model type must be ${requiredType.name.lowercase()}.")
            }
        }
        val bindingSnapshot = snapshotProviderBindings(id, request.authoritySubjectId)
        val switched = if (referencesDeletedModel) {
            settings.ownerReplaceModelReferences(modelIds, requireNotNull(replacementId))
        } else settings
        settingsStore.update { current -> switched.copy(
            providers = switched.providers.filterNot { candidate -> candidate.id == id },
            deletedBuiltInProviderIds = if (provider.builtIn) switched.deletedBuiltInProviderIds + id
            else switched.deletedBuiltInProviderIds,
        ) }
        try {
            removeProviderBindings(id, request.authoritySubjectId)
        } catch (error: Throwable) {
            settingsStore.update { settings }
            runCatching { restoreProviderBindings(id, request.authoritySubjectId, bindingSnapshot) }
            throw error
        }
        val verified = settingsStore.settingsFlow.value
        if (verified.providers.any { it.id == id } || verified.referencesAnyModel(modelIds)) {
            settingsStore.update { settings }
            restoreProviderBindings(id, request.authoritySubjectId, bindingSnapshot)
            return failure(index, action.type, "PROVIDER_DELETE_VERIFY_FAILED", "Provider replacement or deletion could not be confirmed.")
        }
        return success(
            index, action.type, "PROVIDER_DELETED", "Provider references were switched and the old Provider was deleted.",
            receipt = SettingsReceipt.DeletedProvider(settings, id, bindingSnapshot),
        )
    }

    private suspend fun providerRefresh(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        persist: Boolean,
    ): OwnerAppliedAction {
        val id = requireNotNull(action.arguments.uuid("provider_id"))
        val configured = settingsStore.settingsFlow.value.providers.first { it.id == id }
        val resolved = when (val secret = vault.resolveProviderBinding(configured, request.authoritySubjectId)) {
            SecretBindingResolution.NotBound -> configured
            is SecretBindingResolution.Ready -> secret.value
            is SecretBindingResolution.Unavailable -> return failure(index, action.type, "PROVIDER_SECRET_UNAVAILABLE", secret.code)
        }
        val fetched = providerManager.getProviderByType(resolved).listModels(resolved).toList()
        if (persist) {
            val oldByModel = configured.models.associateBy { it.modelId }
            val merged = fetched.map { model -> oldByModel[model.modelId]?.let { old -> model.copy(id = old.id) } ?: model }
            settingsStore.update { current ->
                current.copy(providers = current.providers.map { provider ->
                    if (provider.id == id) provider.copyProvider(models = merged) else provider
                })
            }
        }
        return success(index, action.type, if (persist) "PROVIDER_MODELS_REFRESHED" else "PROVIDER_TEST_OK", if (persist) "Provider models refreshed." else "Provider connection test succeeded.", buildJsonObject {
            put("model_count", fetched.size)
            put("models", buildJsonArray { fetched.take(50).forEach { add(it.modelId.take(200)) } })
        }, if (persist) SettingsReceipt.PreviousProvider(configured) else null)
    }

    private suspend fun providerSetDefault(index: Int, action: OwnerAction): OwnerAppliedAction {
        val modelId = action.arguments.uuid("model_id")
            ?: return failure(index, action.type, "MODEL_ID_REQUIRED", "model_id is required.")
        val settings = settingsStore.settingsFlow.value
        if (settings.findModelById(modelId) == null) return failure(index, action.type, "MODEL_NOT_FOUND", "Model does not exist.")
        val assistantId = action.arguments.uuid("assistant_id")
        if (assistantId != null && settings.assistants.none { it.id == assistantId }) {
            return failure(index, action.type, "ASSISTANT_NOT_FOUND", "Assistant does not exist.")
        }
        val previousModel = if (assistantId == null) settings.chatModelId
            else settings.assistants.firstOrNull { it.id == assistantId }?.chatModelId
        settingsStore.update { current ->
            if (assistantId == null) current.copy(chatModelId = modelId) else current.copy(
                assistants = current.assistants.map { assistant ->
                    if (assistant.id == assistantId) assistant.copy(chatModelId = modelId) else assistant
                },
            )
        }
        return success(
            index, action.type, "DEFAULT_MODEL_UPDATED", "Model binding updated.",
            receipt = SettingsReceipt.PreviousDefaultModel(assistantId, previousModel),
        )
    }

    private fun providerModelList(index: Int, action: OwnerAction): OwnerAppliedAction {
        val providerId = action.arguments.uuid("provider_id")
            ?: return failure(index, action.type, "PROVIDER_ID_REQUIRED", "provider_id is required.")
        val provider = settingsStore.settingsFlow.value.providers.firstOrNull { it.id == providerId }
            ?: return failure(index, action.type, "PROVIDER_NOT_FOUND", "Provider does not exist.")
        return success(index, action.type, "PROVIDER_MODELS_LISTED", "Provider model metadata returned.", buildJsonObject {
            put("provider_id", providerId.toString())
            put("models", buildJsonArray {
                provider.models.take(200).forEach { model ->
                    add(buildJsonObject {
                        put("model_id", model.id.toString())
                        put("api_model_id", model.modelId.take(300))
                        put("display_name", model.displayName.take(300))
                        put("type", model.type.name)
                        put("abilities", buildJsonArray { model.abilities.forEach { add(it.name) } })
                        put("input_modalities", buildJsonArray { model.inputModalities.forEach { add(it.name) } })
                        put("output_modalities", buildJsonArray { model.outputModalities.forEach { add(it.name) } })
                        model.contextLength?.let { put("context_length", it) }
                        put("user_context_window_tokens", model.userContextWindowTokens)
                        put("supported_parameters", buildJsonArray {
                            model.supportedParameters.take(64).forEach { add(it.take(100)) }
                        })
                    })
                }
            })
        })
    }

    private suspend fun providerModelUpsert(index: Int, action: OwnerAction): OwnerAppliedAction {
        val providerId = action.arguments.uuid("provider_id")
            ?: return failure(index, action.type, "PROVIDER_ID_REQUIRED", "provider_id is required.")
        val definition = action.arguments["definition"] as? JsonObject
            ?: return failure(index, action.type, "MODEL_DEFINITION_REQUIRED", "definition must be an object.")
        val unknown = definition.keys - MODEL_DEFINITION_FIELDS
        if (unknown.isNotEmpty()) return failure(index, action.type, "MODEL_DEFINITION_FIELD_INVALID", "Unsupported model fields: ${unknown.sorted().joinToString()}.")
        val settings = settingsStore.settingsFlow.value
        val provider = settings.providers.firstOrNull { it.id == providerId }
            ?: return failure(index, action.type, "PROVIDER_NOT_FOUND", "Provider does not exist.")
        val requestedId = definition.uuid("model_id")
        if (definition.string("model_id") != null && requestedId == null) return failure(index, action.type, "MODEL_ID_INVALID", "model_id must be a UUID.")
        val apiModelId = definition.string("api_model_id")?.trim()?.takeIf { it.isNotEmpty() }
        val existing = requestedId?.let { id -> provider.models.firstOrNull { it.id == id } }
            ?: apiModelId?.let { name -> provider.models.firstOrNull { it.modelId == name } }
        if (existing == null && apiModelId == null) return failure(index, action.type, "API_MODEL_ID_REQUIRED", "api_model_id is required for a new model.")
        if (existing == null && requestedId != null && settings.providers.any { candidate ->
                candidate.id != providerId && candidate.models.any { it.id == requestedId }
            }
        ) {
            return failure(index, action.type, "MODEL_ID_EXISTS", "model_id already belongs to another Provider.")
        }
        val type = definition.enumValue<ModelType>("type") ?: existing?.type ?: ModelType.CHAT
        val abilities = definition.enumList<ModelAbility>("abilities")
            ?: if ("abilities" in definition) return failure(index, action.type, "MODEL_ABILITIES_INVALID", "abilities contains an unsupported value.") else existing?.abilities.orEmpty()
        val input = definition.enumList<Modality>("input_modalities")
            ?: if ("input_modalities" in definition) return failure(index, action.type, "MODEL_MODALITIES_INVALID", "input_modalities contains an unsupported value.") else existing?.inputModalities ?: listOf(Modality.TEXT)
        val output = definition.enumList<Modality>("output_modalities")
            ?: if ("output_modalities" in definition) return failure(index, action.type, "MODEL_MODALITIES_INVALID", "output_modalities contains an unsupported value.") else existing?.outputModalities ?: listOf(Modality.TEXT)
        val supported = definition.stringList("supported_parameters")
            ?: if ("supported_parameters" in definition) return failure(index, action.type, "MODEL_PARAMETERS_INVALID", "supported_parameters must be a string array.") else existing?.supportedParameters.orEmpty()
        if (abilities.size > 8 || input.size > 8 || output.size > 8 || supported.size > 64 || supported.any { it.length > 100 }) {
            return failure(index, action.type, "MODEL_DEFINITION_LIMIT", "Model capability metadata exceeds safe limits.")
        }
        val contextLength = definition.int("context_length") ?: existing?.contextLength
        val userWindow = definition.int("user_context_window_tokens") ?: existing?.userContextWindowTokens ?: 1_000_000
        if (contextLength != null && contextLength !in 1..10_000_000) {
            return failure(index, action.type, "MODEL_CONTEXT_INVALID", "Advertised context length must be between 1 and 10000000 tokens.")
        }
        if (userWindow !in 1..ABSOLUTE_CONTEXT_WINDOW_TOKENS) {
            return failure(
                index,
                action.type,
                "MODEL_CONTEXT_INVALID",
                "User context window must be between 1 and $ABSOLUTE_CONTEXT_WINDOW_TOKENS tokens.",
            )
        }
        val model = Model(
            id = existing?.id ?: requestedId ?: Uuid.random(),
            modelId = apiModelId?.take(300) ?: existing!!.modelId,
            displayName = definition.string("display_name")?.take(300) ?: existing?.displayName.orEmpty(),
            type = type,
            inputModalities = input.distinct(),
            outputModalities = output.distinct(),
            abilities = abilities.distinct(),
            contextLength = contextLength,
            userContextWindowTokens = userWindow,
            supportedParameters = supported.distinct(),
            customHeaders = existing?.customHeaders.orEmpty(),
            customBodies = existing?.customBodies.orEmpty(),
            tools = existing?.tools.orEmpty(),
            providerOverwrite = existing?.providerOverwrite,
            pricePromptPerToken = existing?.pricePromptPerToken,
            priceCompletionPerToken = existing?.priceCompletionPerToken,
        )
        val updatedProvider = if (existing == null) provider.addModel(model) else provider.editModel(model)
        if (updatedProvider.models.none { it.id == model.id && it == model }) {
            return failure(index, action.type, "PROVIDER_MODEL_IMMUTABLE", "This Provider does not permit model catalog mutation.")
        }
        settingsStore.update { current -> current.copy(providers = current.providers.map { if (it.id == providerId) updatedProvider else it }) }
        return success(index, action.type, if (existing == null) "PROVIDER_MODEL_CREATED" else "PROVIDER_MODEL_UPDATED", "Provider model metadata updated.", buildJsonObject {
            put("provider_id", providerId.toString()); put("model_id", model.id.toString())
        }, SettingsReceipt.PreviousProvider(provider))
    }

    private suspend fun providerModelDelete(index: Int, action: OwnerAction): OwnerAppliedAction {
        val providerId = action.arguments.uuid("provider_id")
            ?: return failure(index, action.type, "PROVIDER_ID_REQUIRED", "provider_id is required.")
        val modelId = action.arguments.uuid("model_id")
            ?: return failure(index, action.type, "MODEL_ID_REQUIRED", "model_id is required.")
        val before = settingsStore.settingsFlow.value
        val provider = before.providers.firstOrNull { it.id == providerId }
            ?: return failure(index, action.type, "PROVIDER_NOT_FOUND", "Provider does not exist.")
        val model = provider.models.firstOrNull { it.id == modelId }
            ?: return failure(index, action.type, "MODEL_NOT_FOUND", "Model does not exist in this Provider.")
        val referenced = before.referencesAnyModel(setOf(modelId))
        val requiredReplacementType = before.ownerReferencedModelTypes(setOf(modelId)).singleOrNull()
        val replacementId = action.arguments.uuid("replacement_model_id")
        if (action.arguments.string("replacement_model_id") != null && replacementId == null) {
            return failure(index, action.type, "MODEL_REPLACEMENT_INVALID", "replacement_model_id must be a UUID.")
        }
        if (referenced && replacementId == null) return failure(index, action.type, "MODEL_REPLACEMENT_REQUIRED", "replacement_model_id is required because the model is in use.")
        val replacement = replacementId?.let(before::findModelById)
        if (replacementId != null && (replacementId == modelId || replacement == null)) {
            return failure(index, action.type, "MODEL_REPLACEMENT_INVALID", "Replacement model does not exist or equals the deleted model.")
        }
        if (requiredReplacementType != null && replacement != null && replacement.type != requiredReplacementType) {
            return failure(index, action.type, "MODEL_REPLACEMENT_TYPE_INVALID", "Replacement model type must be ${requiredReplacementType.name.lowercase()}.")
        }
        val changedProvider = provider.delModel(model)
        if (changedProvider.models.any { it.id == modelId }) return failure(index, action.type, "PROVIDER_MODEL_IMMUTABLE", "This Provider does not permit model deletion.")
        val switched = if (referenced) before.ownerReplaceModelReferences(setOf(modelId), requireNotNull(replacementId)) else before
        settingsStore.update { switched.copy(providers = switched.providers.map { if (it.id == providerId) changedProvider else it }) }
        return success(index, action.type, "PROVIDER_MODEL_DELETED", "Model references were switched and the model was deleted.", receipt = SettingsReceipt.PreviousSettings(before))
    }

    private suspend fun providerRouteSet(index: Int, action: OwnerAction): OwnerAppliedAction {
        val route = action.arguments.string("route")?.lowercase()
            ?: return failure(index, action.type, "MODEL_ROUTE_REQUIRED", "route is required.")
        if (route !in MODEL_ROUTES) return failure(index, action.type, "MODEL_ROUTE_INVALID", "Unsupported model route.")
        val rawId = action.arguments.string("model_id")?.trim()?.takeIf { it.isNotEmpty() }
        val modelId = rawId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (rawId != null && modelId == null) return failure(index, action.type, "MODEL_ID_INVALID", "model_id must be a UUID.")
        if (modelId == null && route !in NULLABLE_MODEL_ROUTES) return failure(index, action.type, "MODEL_ID_REQUIRED", "model_id is required for this route.")
        val before = settingsStore.settingsFlow.value
        val model = modelId?.let { before.findModelById(it) }
        if (modelId != null && model == null) return failure(index, action.type, "MODEL_NOT_FOUND", "Model does not exist.")
        if (route == "image_generation" && model?.type != ModelType.IMAGE) return failure(index, action.type, "MODEL_TYPE_INVALID", "image_generation requires an image model.")
        if (route != "image_generation" && model != null && model.type != ModelType.CHAT) return failure(index, action.type, "MODEL_TYPE_INVALID", "This route requires a chat model.")
        val after = when (route) {
            "chat" -> before.copy(chatModelId = requireNotNull(modelId))
            "fast" -> before.copy(fastModelId = requireNotNull(modelId))
            "memory" -> before.copy(memoryExtractionModelId = modelId)
            "title" -> before.copy(titleModelId = modelId)
            "image_generation" -> before.copy(imageGenerationModelId = requireNotNull(modelId))
            "suggestion" -> before.copy(suggestionModelId = modelId)
            "ocr" -> before.copy(ocrModelId = requireNotNull(modelId))
            "compress" -> before.copy(compressModelId = requireNotNull(modelId))
            "translate" -> before.copy(translateModeId = requireNotNull(modelId))
            else -> before
        }
        settingsStore.update { after }
        return success(index, action.type, "PROVIDER_ROUTE_UPDATED", "Model route updated.", buildJsonObject {
            put("route", route); put("model_id", modelId?.toString() ?: "")
        }, SettingsReceipt.PreviousSettings(before))
    }

    private suspend fun bindProviderSlot(slotId: String, subjectId: String, providerId: Uuid) {
        val slot = vault.listMetadata(subjectId).first { it.slotId == slotId }
        val binding = SecretBinding(SecretBindingKind.PROVIDER, providerId.toString())
        check(vault.updateBindings(slotId, subjectId, (slot.bindings.filterNot {
            it.kind == binding.kind && it.targetId == binding.targetId
        } + binding))) { "provider_vault_binding_failed" }
    }

    private suspend fun removeProviderBindings(providerId: Uuid, subjectId: String) {
        vault.listMetadata(subjectId).forEach { slot ->
            val retained = slot.bindings.filterNot {
                it.kind == SecretBindingKind.PROVIDER && it.targetId == providerId.toString()
            }
            if (retained != slot.bindings) vault.updateBindings(slot.slotId, subjectId, retained)
        }
    }

    private suspend fun snapshotProviderBindings(
        providerId: Uuid,
        authoritySubjectId: String,
    ): Map<String, List<SecretBinding>> = vault.listMetadata(authoritySubjectId).associate { slot ->
        slot.slotId to slot.bindings.filter {
            it.kind == SecretBindingKind.PROVIDER && it.targetId == providerId.toString()
        }
    }

    private suspend fun restoreProviderBindings(
        providerId: Uuid,
        authoritySubjectId: String,
        snapshot: Map<String, List<SecretBinding>>,
    ) {
        vault.listMetadata(authoritySubjectId).forEach { slot ->
            val retained = slot.bindings.filterNot {
                it.kind == SecretBindingKind.PROVIDER && it.targetId == providerId.toString()
            }
            check(vault.updateBindings(slot.slotId, authoritySubjectId, retained + snapshot[slot.slotId].orEmpty())) {
                "provider_vault_binding_restore_failed"
            }
        }
    }

    private fun ProviderSetting.withSafeFields(name: String, enabled: Boolean, baseUrl: String?): ProviderSetting = when (this) {
        is ProviderSetting.OpenAI -> copy(name = name, enabled = enabled, baseUrl = baseUrl ?: this.baseUrl)
        is ProviderSetting.Google -> copy(name = name, enabled = enabled, baseUrl = baseUrl ?: this.baseUrl)
        is ProviderSetting.Claude -> copy(name = name, enabled = enabled, baseUrl = baseUrl ?: this.baseUrl)
        else -> copyProvider(name = name, enabled = enabled)
    }

    private fun ProviderSetting.typeName(): String = when (this) {
        is ProviderSetting.OpenAI -> "openai"
        is ProviderSetting.Google -> "google"
        is ProviderSetting.Claude -> "claude"
        // Explicit rather than the `else` fallback: every other surface (registry key, secret
        // inventory, importer, background host) reports "claude_p", and the bare simpleName would
        // report "claudep" — a type string no other API recognises and `providerCreate` cannot
        // round-trip.
        is ProviderSetting.ClaudeP -> "claude_p"
        else -> this::class.simpleName.orEmpty().lowercase()
    }

    private fun success(
        index: Int,
        type: String,
        code: String,
        message: String,
        data: JsonObject? = null,
        receipt: Any? = null,
    ) = OwnerAppliedAction(OwnerActionResult(index, type, true, code, message, data), receipt)
    private fun failure(index: Int, type: String, code: String, message: String) =
        OwnerAppliedAction(OwnerActionResult(index, type, false, code, message.take(500)))
    private fun invalid(code: String, message: String) = OwnerActionValidation(false, code, message)
    private fun idData(key: String, id: Any) = buildJsonObject { put(key, id.toString()) }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.uuid(key: String): Uuid? = string(key)?.trim()?.takeIf { it.isNotEmpty() }?.let {
        runCatching { Uuid.parse(it) }.getOrNull()
    }
    private fun JsonObject.boolean(key: String): Boolean? = string(key)?.toBooleanStrictOrNull()
    private fun JsonObject.int(key: String): Int? = string(key)?.toIntOrNull()
    private inline fun <reified T : Enum<T>> JsonObject.enumValue(key: String): T? =
        string(key)?.uppercase()?.let { value -> enumValues<T>().firstOrNull { it.name == value } }
    private inline fun <reified T : Enum<T>> JsonObject.enumList(key: String): List<T>? {
        val values = this[key] as? JsonArray ?: return null
        val parsed = values.mapNotNull { element ->
            (element as? JsonPrimitive)?.contentOrNull?.uppercase()?.let { name -> enumValues<T>().firstOrNull { it.name == name } }
        }
        return parsed.takeIf { it.size == values.size }
    }
    private fun JsonObject.stringList(key: String): List<String>? {
        val values = this[key] as? JsonArray ?: return null
        val parsed = values.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return parsed.takeIf { it.size == values.size }
    }

    private sealed interface SettingsReceipt {
        data class CreatedAssistant(val id: Uuid) : SettingsReceipt
        data class PreviousAssistant(val value: Assistant) : SettingsReceipt
        data class PreviousDefaultAssistant(val id: Uuid) : SettingsReceipt
        data class PreviousDefaultTts(val id: Uuid) : SettingsReceipt
        data class CreatedConversation(val id: Uuid) : SettingsReceipt
        data class PreviousConversation(val value: me.rerere.rikkahub.data.model.Conversation) : SettingsReceipt
        data class CreatedProvider(val id: Uuid) : SettingsReceipt
        data class PreviousProvider(val value: ProviderSetting) : SettingsReceipt
        data class PreviousDefaultModel(val assistantId: Uuid?, val modelId: Uuid?) : SettingsReceipt
        data class PreviousSettings(val value: Settings) : SettingsReceipt
        data class DeletedProvider(
            val settings: Settings,
            val providerId: Uuid,
            val bindings: Map<String, List<SecretBinding>>,
        ) : SettingsReceipt
    }

    private companion object {
        const val OWNER_ARCHIVE_FOLDER = "__owner_archive__"
        val ACTION_FIELDS = mapOf(
            "assistant_create" to setOf("assistant_id", "name", "system_prompt", "model_id"),
            "assistant_clone" to setOf("source_assistant_id", "assistant_id", "name"),
            "assistant_delete" to setOf("assistant_id"),
            "assistant_set_default" to setOf("assistant_id"),
            "assistant_switch_model" to setOf("assistant_id", "model_id"),
            "assistant_switch_tts" to setOf("tts_provider_id"),
            "conversation_create" to setOf("conversation_id", "assistant_id", "title"),
            "conversation_branch" to setOf("conversation_id", "new_conversation_id", "title"),
            "conversation_archive" to setOf("conversation_id"),
            "conversation_restore" to setOf("conversation_id"),
            "conversation_search" to setOf("query", "limit"),
            "conversation_export" to setOf("conversation_id"),
            "conversation_open" to setOf("conversation_id"),
            "provider_list" to emptySet(),
            "provider_create" to setOf("provider_id", "provider_type", "name", "base_url", "vault_slot_id"),
            "provider_update" to setOf("provider_id", "name", "base_url", "enabled"),
            "provider_delete" to setOf("provider_id", "replacement_model_id"),
            "provider_refresh_models" to setOf("provider_id"),
            "provider_test" to setOf("provider_id"),
            "provider_set_default" to setOf("model_id", "assistant_id"),
            "provider_model_list" to setOf("provider_id"),
            "provider_model_upsert" to setOf("provider_id", "definition"),
            "provider_model_delete" to setOf("provider_id", "model_id", "replacement_model_id"),
            "provider_route_set" to setOf("route", "model_id"),
        )
        val OPTIONAL_UUID_FIELDS = mapOf(
            "assistant_create" to setOf("assistant_id"),
            "assistant_clone" to setOf("assistant_id"),
            "conversation_create" to setOf("conversation_id"),
            "conversation_branch" to setOf("new_conversation_id"),
            "provider_create" to setOf("provider_id"),
        )
        val MODEL_DEFINITION_FIELDS = setOf(
            "model_id", "api_model_id", "display_name", "type", "abilities", "input_modalities",
            "output_modalities", "context_length", "user_context_window_tokens", "supported_parameters",
        )
        val MODEL_ROUTES = setOf("chat", "fast", "memory", "title", "image_generation", "suggestion", "ocr", "compress", "translate")
        val NULLABLE_MODEL_ROUTES = setOf("memory", "title", "suggestion")
    }
}

private fun Settings.referencesAnyModel(ids: Set<Uuid>): Boolean =
    chatModelId in ids || fastModelId in ids || ids.containsNullable(memoryExtractionModelId) || ids.containsNullable(titleModelId) ||
        imageGenerationModelId in ids || ids.containsNullable(suggestionModelId) || ocrModelId in ids || compressModelId in ids ||
        ids.containsNullable(stickerVisionModelId) ||
        translateModeId in ids || assistants.any { assistant ->
            ids.containsNullable(assistant.chatModelId) || ids.containsNullable(assistant.subAgentModelId)
        }

internal fun Settings.ownerReferencedModelTypes(ids: Set<Uuid>): Set<ModelType> = buildSet {
    if (imageGenerationModelId in ids) add(ModelType.IMAGE)
    if (
        chatModelId in ids || fastModelId in ids || ids.containsNullable(memoryExtractionModelId) ||
        ids.containsNullable(titleModelId) || ids.containsNullable(suggestionModelId) || ocrModelId in ids ||
        ids.containsNullable(stickerVisionModelId) ||
        compressModelId in ids || translateModeId in ids || assistants.any { assistant ->
            ids.containsNullable(assistant.chatModelId) || ids.containsNullable(assistant.subAgentModelId)
        }
    ) {
        add(ModelType.CHAT)
    }
}

internal fun Settings.ownerReplaceModelReferences(ids: Set<Uuid>, replacement: Uuid): Settings = copy(
    chatModelId = chatModelId.takeUnless(ids::contains) ?: replacement,
    fastModelId = fastModelId.takeUnless(ids::contains) ?: replacement,
    memoryExtractionModelId = memoryExtractionModelId.replaceIfIn(ids, replacement),
    titleModelId = titleModelId.replaceIfIn(ids, replacement),
    imageGenerationModelId = imageGenerationModelId.takeUnless(ids::contains) ?: replacement,
    suggestionModelId = suggestionModelId.replaceIfIn(ids, replacement),
    ocrModelId = ocrModelId.takeUnless(ids::contains) ?: replacement,
    // Cleared rather than repointed, unlike every other id above. The others must name *a* model
    // to stay usable; this one has a valid "off" state, and swapping in an arbitrary replacement
    // would aim sticker recognition at a model that may not accept images at all — turning a
    // cleanly disabled feature into one that fails on every import. Null here means the library
    // says 未识别 and the person picks again.
    stickerVisionModelId = stickerVisionModelId?.takeUnless { it in ids },
    compressModelId = compressModelId.takeUnless(ids::contains) ?: replacement,
    translateModeId = translateModeId.takeUnless(ids::contains) ?: replacement,
    favoriteModels = favoriteModels.filterNot(ids::contains),
    assistants = assistants.map { assistant -> assistant.copy(
        chatModelId = assistant.chatModelId.replaceIfIn(ids, replacement),
        subAgentModelId = assistant.subAgentModelId.replaceIfIn(ids, replacement),
    ) },
)

private fun Set<Uuid>.containsNullable(value: Uuid?): Boolean = value != null && value in this
private fun Uuid?.replaceIfIn(ids: Set<Uuid>, replacement: Uuid): Uuid? =
    if (this != null && this in ids) replacement else this

sealed interface OwnerNavigationTarget {
    data class Conversation(val conversationId: String) : OwnerNavigationTarget
    data class Screen(val route: String, val targetId: String? = null) : OwnerNavigationTarget
}

object OwnerNavigationMailbox {
    private val pending = java.util.concurrent.atomic.AtomicReference<OwnerNavigationTarget?>(null)
    private val _targets = kotlinx.coroutines.flow.MutableStateFlow<OwnerNavigationTarget?>(null)
    val targets: kotlinx.coroutines.flow.StateFlow<OwnerNavigationTarget?> = _targets
    fun offer(target: OwnerNavigationTarget) {
        pending.set(target)
        _targets.value = target
    }
    fun consume(): OwnerNavigationTarget? = pending.getAndSet(null)
    fun acknowledge(target: OwnerNavigationTarget) {
        pending.compareAndSet(target, null)
        _targets.compareAndSet(target, null)
    }
}
