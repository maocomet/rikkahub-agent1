package me.rerere.rikkahub.security

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.owner.OwnerAction
import me.rerere.rikkahub.owner.OwnerActionResult
import me.rerere.rikkahub.owner.OwnerActionValidation
import me.rerere.rikkahub.owner.OwnerAppliedAction
import me.rerere.rikkahub.owner.OwnerCompensationResult
import me.rerere.rikkahub.owner.OwnerOperationHandler
import me.rerere.rikkahub.owner.OwnerOperationRequest
import me.rerere.rikkahub.owner.OwnerToolFamily
import me.rerere.rikkahub.privilege.PrivilegedSessionContext

class SecretOwnerOperationHandler(
    private val sessions: SecretPlaintextSessionManager,
    private val ephemeralResults: EphemeralToolResultStore,
    private val settingsStore: SettingsStore,
    private val vault: SecondUserSecretVault,
) : OwnerOperationHandler {
    override fun supports(request: OwnerOperationRequest, action: OwnerAction): Boolean =
        request.family == OwnerToolFamily.SECRET && action.type in OPERATIONS

    override suspend fun validate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        val allowed = FIELDS[action.type]
            ?: return invalid("OWNER_ACTION_UNSUPPORTED", "Unsupported secret action.")
        if ((action.arguments.keys - allowed).isNotEmpty()) {
            return invalid("SECRET_UNSUPPORTED_FIELD", "Secret action contains an unsupported field.")
        }
        if (action.type != "secret_session_status") {
            val binding = request.binding()
                ?: return invalid("SECRET_SESSION_BINDING_MISSING", "Model and Provider binding are required.")
            if (!sessions.isOpenFor(binding)) {
                return invalid("SECRET_PLAINTEXT_SESSION_CLOSED", "Open the 30-minute plaintext session with strong biometrics first.")
            }
            if (action.type in SLOT_OPERATIONS && action.arguments.string("slot_id").isNullOrBlank()) {
                return invalid("SECRET_SLOT_REQUIRED", "slot_id is required.")
            }
        }
        if (action.type == "secret_provider_credentials_reveal") {
            val providerIds = action.arguments["provider_ids"]
            if (providerIds != null && providerIds !is JsonArray) {
                return invalid("SECRET_PROVIDER_IDS_INVALID", "provider_ids must be an array of Provider IDs.")
            }
            val requested = (providerIds as? JsonArray).orEmpty().map { element ->
                (element as? JsonPrimitive)?.contentOrNull?.trim()
                    ?: return invalid("SECRET_PROVIDER_IDS_INVALID", "Each provider_ids item must be a Provider ID string.")
            }
            if (requested.size > MAX_PROVIDER_CREDENTIALS || requested.any { it.isBlank() }) {
                return invalid(
                    "SECRET_PROVIDER_IDS_INVALID",
                    "provider_ids must contain at most $MAX_PROVIDER_CREDENTIALS non-empty Provider IDs.",
                )
            }
            val known = settingsStore.settingsFlow.value.providers.mapTo(hashSetOf()) { it.id.toString() }
            if (requested.any { it !in known }) {
                return invalid("SECRET_PROVIDER_NOT_FOUND", "At least one requested Provider does not exist.")
            }
        }
        if (action.type == "secret_replace" && action.arguments.string("find") == null) {
            return invalid("SECRET_FIND_REQUIRED", "find is required for local replacement.")
        }
        return OwnerActionValidation(true, "SECRET_ACTION_VALID", "Secret action validated.")
    }

    override suspend fun apply(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerAppliedAction {
        if (action.type == "secret_session_status") {
            val state = sessions.state.value
            val data = buildJsonObject {
                put("open", state is SecretPlaintextSessionState.Open)
                if (state is SecretPlaintextSessionState.Open) {
                    put("expires_at_ms", state.expiresAtMs)
                    put("model_id", state.binding.modelId)
                    put("provider_id", state.binding.providerId)
                }
                sessions.lastCloseReason()?.let { put("last_close_reason", it.name) }
            }
            return success(index, action.type, "SECRET_SESSION_STATUS", "Plaintext session state returned.", data)
        }
        val binding = request.binding()
            ?: return failure(index, action.type, "SECRET_SESSION_BINDING_MISSING", "Model and Provider binding are required.")
        val slotId = action.arguments.string("slot_id")?.trim()?.take(96).orEmpty()
        return when (action.type) {
            "secret_provider_credentials_reveal" -> revealProviderCredentials(
                index = index,
                action = action,
                request = request,
                binding = binding,
            )
            "secret_plaintext_reveal" -> {
                when (val result = sessions.withPlaintext(slotId, binding) { chars ->
                    ephemeralResults.issue(chars, binding)
                }) {
                    is SecretLeaseResult.Success -> success(
                        index,
                        action.type,
                        "SECRET_REVEALED",
                        "The value is available only to the next request for the bound model.",
                        buildJsonObject {
                            put("value", "[SECRET_REVEALED]")
                            put(EphemeralToolResultStore.EPHEMERAL_TOKEN_FIELD, result.value.token)
                        },
                    )
                    else -> leaseFailure(index, action.type, result)
                }
            }
            "secret_replace" -> {
                val find = SensitiveToolArgument.from(action.arguments.string("find").orEmpty())
                val replacement = SensitiveToolArgument.from(action.arguments.string("replacement").orEmpty())
                try {
                    find.useSuspending { findChars ->
                        replacement.useSuspending { replacementChars ->
                            transform(index, action.type, slotId, binding) { current ->
                                current.concatToString().replace(
                                    findChars.concatToString(),
                                    replacementChars.concatToString(),
                                ).toCharArray()
                            }
                        }
                    }
                } finally {
                    replacement.close()
                    find.close()
                }
            }
            "secret_trim" -> transform(index, action.type, slotId, binding) { current ->
                current.concatToString().trim().toCharArray()
            }
            "secret_remove_prefix" -> {
                val prefix = SensitiveToolArgument.from(action.arguments.string("prefix").orEmpty())
                try {
                    prefix.useSuspending { prefixChars ->
                        transform(index, action.type, slotId, binding) { current ->
                            current.concatToString().removePrefix(prefixChars.concatToString()).toCharArray()
                        }
                    }
                } finally {
                    prefix.close()
                }
            }
            "secret_remove_quotes" -> transform(index, action.type, slotId, binding) { current ->
                val value = current.concatToString()
                if (value.length >= 2 &&
                    ((value.first() == '"' && value.last() == '"') ||
                        (value.first() == '\'' && value.last() == '\''))
                ) value.substring(1, value.lastIndex).toCharArray() else value.toCharArray()
            }
            "secret_remove_newlines" -> transform(index, action.type, slotId, binding) { current ->
                current.filterNot { it == '\r' || it == '\n' }.toCharArray()
            }
            else -> failure(index, action.type, "OWNER_ACTION_UNSUPPORTED", "Unsupported secret action.")
        }
    }

    /**
     * Gives the currently bound cloud model a one-request inventory of Provider endpoints and
     * Vault-backed API keys. The raw endpoints exist only in the active generation copy, while
     * every key is represented by a one-use token until [EphemeralToolResultStore] materializes
     * the immediately following request. No legacy Settings plaintext is exposed here.
     */
    private suspend fun revealProviderCredentials(
        index: Int,
        action: OwnerAction,
        request: OwnerOperationRequest,
        binding: SecretPlaintextSessionBinding,
    ): OwnerAppliedAction {
        val requestedIds = (action.arguments["provider_ids"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?.toSet()
            .orEmpty()
        val allProviders = settingsStore.settingsFlow.value.providers
        val selected = allProviders
            .asSequence()
            .filter { requestedIds.isEmpty() || it.id.toString() in requestedIds }
            .take(MAX_PROVIDER_CREDENTIALS)
            .toList()
        val slots = vault.listMetadata(request.authoritySubjectId)
        val entries = selected.map { provider ->
            val slot = slots.firstOrNull { metadata ->
                metadata.bindings.any { secretBinding ->
                    secretBinding.kind == SecretBindingKind.PROVIDER &&
                        secretBinding.targetId == provider.id.toString()
                }
            }
            val reveal = slot?.let { metadata ->
                sessions.withPlaintext(metadata.slotId, binding) { chars ->
                    ephemeralResults.issue(chars, binding)
                }
            }
            buildJsonObject {
                put("provider_id", provider.id.toString())
                put("name", provider.name.take(200))
                put("type", provider.secretInventoryType())
                put("enabled", provider.enabled)
                provider.secretInventoryBaseUrl()?.let { put("base_url", it.take(MAX_PROVIDER_URL_LENGTH)) }
                put("model_ids", buildJsonArray {
                    provider.models.take(MAX_MODELS_PER_PROVIDER).forEach { model ->
                        add(JsonPrimitive(model.modelId.take(MAX_MODEL_ID_LENGTH)))
                    }
                })
                put("models_truncated", provider.models.size > MAX_MODELS_PER_PROVIDER)
                if (slot != null) put("vault_slot_id", slot.slotId)
                when (reveal) {
                    is SecretLeaseResult.Success -> {
                        put("credential_state", "EPHEMERAL_READY")
                        put("value", "[SECRET_REVEALED]")
                        put(EphemeralToolResultStore.EPHEMERAL_TOKEN_FIELD, reveal.value.token)
                    }
                    SecretLeaseResult.SlotMissing -> put("credential_state", "VALUE_NOT_SET")
                    SecretLeaseResult.BindingDenied -> put("credential_state", "BINDING_DENIED")
                    SecretLeaseResult.AuthorityDenied -> put("credential_state", "SESSION_DENIED")
                    SecretLeaseResult.KeystoreUnavailable -> put("credential_state", "KEYSTORE_UNAVAILABLE")
                    SecretLeaseResult.Corrupt -> put("credential_state", "CIPHERTEXT_CORRUPT")
                    null -> put(
                        "credential_state",
                        when {
                            // Claude P authenticates with a revocable device identity held in the
                            // Keystore, not an API key. Falling through to UNBOUND would advertise
                            // a bindable secret slot that can never apply to this provider.
                            provider is ProviderSetting.ClaudeP -> "NOT_APPLICABLE"
                            provider.legacyApiKeyOrNull().isNullOrBlank() -> "UNBOUND"
                            else -> "LEGACY_MIGRATION_REQUIRED"
                        },
                    )
                }
            }
        }
        return success(
            index = index,
            type = action.type,
            code = "SECRET_PROVIDER_CREDENTIALS_READY",
            message = "Provider endpoints and available credentials are exposed only to the next bound-model request.",
            data = buildJsonObject {
                put("providers", JsonArray(entries))
                put("provider_count", entries.size)
                put(
                    "providers_truncated",
                    requestedIds.isEmpty() && allProviders.size > MAX_PROVIDER_CREDENTIALS,
                )
            },
        )
    }

    override suspend fun verify(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation = if (applied.result.ok) {
        OwnerActionValidation(true, "SECRET_ACTION_VERIFIED", "Secret action completed in the local host.")
    } else {
        invalid(applied.result.code, applied.result.message)
    }

    override suspend fun compensate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerCompensationResult {
        val receipt = applied.compensationReceipt as? SecretTransformReceipt
            ?: return OwnerCompensationResult(true, "SECRET_NO_COMPENSATION_REQUIRED")
        val previous = receipt.copyPrevious()
            ?: return OwnerCompensationResult(false, "SECRET_ROLLBACK_VALUE_CLEARED")
        return try {
            when (sessions.transformSecret(receipt.slotId, receipt.binding) {
                previous.copyOf()
            }) {
                is SecretLeaseResult.Success -> OwnerCompensationResult(true, "SECRET_VALUE_RESTORED")
                else -> OwnerCompensationResult(false, "SECRET_VALUE_RESTORE_FAILED")
            }
        } finally {
            previous.fill('\u0000')
        }
    }

    private suspend fun transform(
        index: Int,
        type: String,
        slotId: String,
        binding: SecretPlaintextSessionBinding,
        block: (CharArray) -> CharArray,
    ): OwnerAppliedAction {
        var previous: CharArray? = null
        return when (val result = sessions.transformSecret(slotId, binding) { current ->
            previous = current.copyOf()
            block(current)
        }) {
        is SecretLeaseResult.Success -> success(
            index, type, "SECRET_TRANSFORMED", "The secret was transformed locally; no plaintext was returned.",
            buildJsonObject { put("value", "[SECRET_REDACTED]") },
            SecretTransformReceipt(slotId, binding, requireNotNull(previous)),
        )
        else -> leaseFailure(index, type, result)
        }.also { applied ->
            if (!applied.result.ok) previous?.fill('\u0000')
        }
    }

    private fun leaseFailure(index: Int, type: String, result: SecretLeaseResult<*>): OwnerAppliedAction {
        val code = when (result) {
            SecretLeaseResult.SlotMissing -> "SECRET_SLOT_MISSING"
            SecretLeaseResult.BindingDenied -> "SECRET_BINDING_DENIED"
            SecretLeaseResult.AuthorityDenied -> "SECRET_SESSION_DENIED"
            SecretLeaseResult.KeystoreUnavailable -> "SECRET_KEYSTORE_UNAVAILABLE"
            SecretLeaseResult.Corrupt -> "SECRET_CIPHERTEXT_CORRUPT"
            is SecretLeaseResult.Success -> "SECRET_RESULT_INVALID"
        }
        return failure(index, type, code, "The local Vault operation did not complete.")
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
        OwnerAppliedAction(OwnerActionResult(index, type, false, code, message))

    private fun invalid(code: String, message: String) = OwnerActionValidation(false, code, message)

    private fun OwnerOperationRequest.binding(): SecretPlaintextSessionBinding? {
        val model = modelId ?: return null
        val provider = providerId ?: return null
        return SecretPlaintextSessionBinding(
            authoritySubjectId = authoritySubjectId,
            authorityEpoch = authorityEpoch,
            assistantId = assistantId,
            conversationId = conversationId,
            modelId = model,
            providerId = provider,
        )
    }

    private class SecretTransformReceipt(
        val slotId: String,
        val binding: SecretPlaintextSessionBinding,
        previous: CharArray,
    ) : AutoCloseable {
        private var value: CharArray? = previous
        @Synchronized fun copyPrevious(): CharArray? = value?.copyOf()
        @Synchronized override fun close() {
            value?.fill('\u0000')
            value = null
        }
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun ProviderSetting.secretInventoryType(): String = when (this) {
        is ProviderSetting.OpenAI -> "openai"
        is ProviderSetting.Google -> "google"
        is ProviderSetting.Claude -> "claude"
        is ProviderSetting.AICore -> "aicore"
        is ProviderSetting.LiteRtLocal -> "local_litert"
        is ProviderSetting.Codex -> "codex"
        // Claude P owns no API-key secret in Settings. Its device private key lives in the Android
        // Keystore and never enters this inventory; the paired origin is reported as the base URL
        // below so the UI can still name the endpoint.
        is ProviderSetting.ClaudeP -> "claude_p"
    }

    private fun ProviderSetting.secretInventoryBaseUrl(): String? = when (this) {
        is ProviderSetting.OpenAI -> baseUrl
        is ProviderSetting.Google -> baseUrl
        is ProviderSetting.Claude -> baseUrl
        is ProviderSetting.ClaudeP -> pairedOrigin
        else -> null
    }

    private companion object {
        const val MAX_PROVIDER_CREDENTIALS = 32
        const val MAX_MODELS_PER_PROVIDER = 200
        const val MAX_PROVIDER_URL_LENGTH = 2_048
        const val MAX_MODEL_ID_LENGTH = 240
        val OPERATIONS = setOf(
            "secret_session_status",
            "secret_provider_credentials_reveal",
            "secret_plaintext_reveal",
            "secret_replace",
            "secret_trim",
            "secret_remove_prefix",
            "secret_remove_quotes",
            "secret_remove_newlines",
        )
        val FIELDS = mapOf(
            "secret_session_status" to emptySet(),
            "secret_provider_credentials_reveal" to setOf("provider_ids"),
            "secret_plaintext_reveal" to setOf("slot_id"),
            "secret_replace" to setOf("slot_id", "find", "replacement"),
            "secret_trim" to setOf("slot_id"),
            "secret_remove_prefix" to setOf("slot_id", "prefix"),
            "secret_remove_quotes" to setOf("slot_id"),
            "secret_remove_newlines" to setOf("slot_id"),
        )
        val SLOT_OPERATIONS = setOf(
            "secret_plaintext_reveal",
            "secret_replace",
            "secret_trim",
            "secret_remove_prefix",
            "secret_remove_quotes",
            "secret_remove_newlines",
        )
    }
}
