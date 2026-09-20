package me.rerere.ai.provider

import androidx.compose.runtime.Composable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.uuid.Uuid
import me.rerere.ai.provider.claudep.ClaudePCachedModel
import me.rerere.ai.provider.claudep.ClaudePDeviceDescriptor
import me.rerere.ai.provider.claudep.ClaudePPairingState

@Serializable
data class BalanceOption(
    val enabled: Boolean = false, // 是否开启余额获取功能
    val apiPath: String = "/credits", // 余额获取API路径
    val resultPath: String = "data.total_usage", // 余额获取JSON路径
)

@Serializable
enum class ClaudePromptCacheTtl(val apiValue: String?) {
    @SerialName("5m")
    FIVE_MINUTES(null),

    @SerialName("1h")
    ONE_HOUR("1h")
}

@Serializable
sealed class ProviderSetting {
    abstract val id: Uuid
    abstract val enabled: Boolean
    abstract val name: String
    abstract val models: List<Model>
    abstract val balanceOption: BalanceOption

    abstract val builtIn: Boolean
    abstract val description: @Composable() () -> Unit
    abstract val shortDescription: @Composable() () -> Unit

    abstract fun addModel(model: Model): ProviderSetting
    abstract fun editModel(model: Model): ProviderSetting
    abstract fun delModel(model: Model): ProviderSetting
    abstract fun moveMove(from: Int, to: Int): ProviderSetting
    abstract fun copyProvider(
        id: Uuid = this.id,
        enabled: Boolean = this.enabled,
        name: String = this.name,
        models: List<Model> = this.models,
        balanceOption: BalanceOption = this.balanceOption,
        builtIn: Boolean = this.builtIn,
        description: @Composable (() -> Unit) = this.description,
        shortDescription: @Composable (() -> Unit) = this.shortDescription,
    ): ProviderSetting

    @Serializable
    @SerialName("openai")
    data class OpenAI(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "OpenAI",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://api.openai.com/v1",
        var chatCompletionsPath: String = "/chat/completions",
        var useResponseApi: Boolean = false,
        // OpenRouter only: emit per-block cache_control breakpoints. Anthropic/Gemini/Qwen
        // need them explicitly; auto-caching providers have the field stripped upstream, so
        // it is applied to every model on the OpenRouter host. See ChatCompletionsAPI.
        var promptCaching: Boolean = true,
        var includeHistoryReasoning: Boolean = true,
        // OpenRouter only: provider-routing preferences emitted as the `provider` object.
        var routing: OpenRouterRouting = OpenRouterRouting(),
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                builtIn = builtIn,
                description = description,
                balanceOption = balanceOption,
                shortDescription = shortDescription
            )
        }
    }

    @Serializable
    @SerialName("google")
    data class Google(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Google",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        var vertexAI: Boolean = false,
        var useServiceAccount: Boolean = false,
        var privateKey: String = "", // only for vertex AI service account
        var serviceAccountEmail: String = "", // only for vertex AI service account
        var location: String = "us-central1", // only for vertex AI service account
        var projectId: String = "", // only for vertex AI service account
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
                balanceOption = balanceOption
            )
        }
    }

    @Serializable
    @SerialName("claude")
    data class Claude(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Claude",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://api.anthropic.com/v1",
        var promptCaching: Boolean = true,  // ~10% input rate on cache hits, near-pure win
        var promptCacheTtl: ClaudePromptCacheTtl = ClaudePromptCacheTtl.FIVE_MINUTES,
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                balanceOption = balanceOption,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }
    }

    @Serializable
    @SerialName("aicore")
    data class AICore(
        override var id: Uuid = AICORE_PROVIDER_ID,
        override var enabled: Boolean = true,
        override var name: String = "AICore (on-device)",
        override var models: List<Model> = AICORE_DEFAULT_MODELS,
        override val balanceOption: BalanceOption = BalanceOption(),
        @Transient override val builtIn: Boolean = true,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        // Defaults to PREVIEW because the STABLE feature ID is missing on most current
        // AICore beta channels — PREVIEW is what actually resolves to a working model on
        // Pixel 8/9/10 today. Users can flip back to STABLE once Google promotes it.
        var releaseStage: AICoreReleaseStage = AICoreReleaseStage.PREVIEW,
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting = this // synthetic models, no add
        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model else it })
        }

        override fun delModel(model: Model): ProviderSetting = this // synthetic models, no delete

        override fun moveMove(from: Int, to: Int): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val m = removeAt(from)
                add(to, m)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
                balanceOption = balanceOption,
            )
        }
    }

    @Serializable
    @SerialName("local_litert")
    data class LiteRtLocal(
        override var id: Uuid = LITERT_PROVIDER_ID,
        override var enabled: Boolean = false,
        override var name: String = "Local · LiteRT",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        @Transient override val builtIn: Boolean = true,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting = copy(models = models + model)
        override fun editModel(model: Model): ProviderSetting =
            copy(models = models.map { if (it.id == model.id) model else it })
        override fun delModel(model: Model): ProviderSetting =
            copy(models = models.filter { it.id != model.id })
        override fun moveMove(from: Int, to: Int): ProviderSetting =
            copy(models = models.toMutableList().apply { add(to, removeAt(from)) })
        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting = copy(
            id = id, enabled = enabled, name = name, models = models,
            builtIn = builtIn, description = description, shortDescription = shortDescription,
            balanceOption = balanceOption,
        )
    }

    @Serializable
    @SerialName("codex")
    data class Codex(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = false,
        override var name: String = "Codex",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        @Transient override val builtIn: Boolean = true,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting = copy(models = models + model)

        override fun editModel(model: Model): ProviderSetting =
            copy(models = models.map { if (it.id == model.id) model.copy() else it })

        override fun delModel(model: Model): ProviderSetting =
            copy(models = models.filter { it.id != model.id })

        override fun moveMove(from: Int, to: Int): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                balanceOption = balanceOption,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }
    }

    /**
     * Claude P — a remote Claude Code runtime behind a user-owned Gateway.
     *
     * Deliberately its own subtype rather than a reuse of [Claude] (which is the Anthropic
     * Messages API) or [Codex] (OpenAI Responses). The three authenticate completely differently:
     * Claude P holds a revocable device identity and never sees a Claude OAuth token.
     *
     * ### What may be persisted here
     *
     * Only non-secret, user-visible state: the paired origin, the gateway's public fingerprint and
     * installation id, the opaque device id, a cached model catalog and a pairing state.
     *
     * ### What must never be persisted here
     *
     * Claude OAuth credentials, VPS SSH keys, access/refresh tokens, cookies, Worker socket paths
     * and Claude CLI paths. Settings JSON is backed up to WebDAV and exported through QR codes, so
     * a secret written here is a secret published. The Keystore-held device private key and the
     * short-lived access credential live in dedicated stores (CP1-B).
     */
    @Serializable
    @SerialName("claude_p")
    data class ClaudeP(
        override var id: Uuid = CLAUDEP_PROVIDER_ID,
        // Ships disabled. The provider cannot be used until the user pairs a gateway (CP1-B).
        override var enabled: Boolean = false,
        override var name: String = "Claude P",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        @Transient override val builtIn: Boolean = true,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        /** Normalized https origin learned from the pairing QR. Never hand-edited. */
        @SerialName("paired_origin") var pairedOrigin: String? = null,
        /** Public key fingerprint of the paired gateway, shown for user verification. */
        @SerialName("gateway_fingerprint") var gatewayFingerprint: String? = null,
        /** Stable identifier of the gateway installation. */
        @SerialName("gateway_installation_id") var gatewayInstallationId: String? = null,
        /** Opaque device identity; useless without the Keystore private key. */
        @SerialName("device") var device: ClaudePDeviceDescriptor = ClaudePDeviceDescriptor(),
        @SerialName("pairing_state") var pairingState: ClaudePPairingState = ClaudePPairingState.NOT_PAIRED,
        /** Cached catalog. A display cache only — never the authority for capabilities. */
        @SerialName("cached_models") var cachedModels: List<ClaudePCachedModel> = emptyList(),
        @SerialName("catalog_cached_at") var catalogCachedAt: String? = null,
        /** Claude Code version the gateway reported, shown read-only. */
        @SerialName("claude_code_version") var claudeCodeVersion: String? = null,
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting = copy(models = models + model)

        override fun editModel(model: Model): ProviderSetting =
            copy(models = models.map { if (it.id == model.id) model.copy() else it })

        override fun delModel(model: Model): ProviderSetting =
            copy(models = models.filter { it.id != model.id })

        override fun moveMove(from: Int, to: Int): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                balanceOption = balanceOption,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }
    }

    companion object {
        // Types presented to the user when adding / converting a provider. AICore is
        // intentionally NOT in this list: it is a singleton built-in (one per device,
        // synthesized from the AICore system app), so the "type segmented row" inside
        // the Add-Provider dialog and ProviderConfigure should not offer it as a choice.
        // Including it overflowed the dialog width and wrapped the OpenAI / Google labels
        // onto two lines on a Pixel 10 Pro.
        val Types by lazy {
            listOf(
                OpenAI::class,
                Google::class,
                Claude::class,
            )
        }
    }
}

@Serializable
enum class AICoreReleaseStage { STABLE, PREVIEW }

// Stable IDs for the synthetic AICore provider + models so saved settings and
// conversations referencing them survive app re-installs and provider re-seeds.
val AICORE_PROVIDER_ID: Uuid = Uuid.parse("a1c0a1c0-1234-4111-a000-000000000001")
val LITERT_PROVIDER_ID: Uuid = Uuid.parse("11111111-aaaa-bbbb-cccc-000000000002")

// Stable identity for the Claude P provider, so persisted settings, backup archives and QR
// exports keep referring to the same provider across re-installs and re-seeds. Claude P is a
// singleton built-in (one paired gateway per device), so unlike OpenAI/Google/Claude it is not
// offered in `ProviderSetting.Types` and never appears in the Add/Convert selector.
val CLAUDEP_PROVIDER_ID: Uuid = Uuid.parse("cb1ade90-0001-4a1a-9f01-0000000000a1")
private val AICORE_NANO_FAST_ID: Uuid = Uuid.parse("a1c0a1c0-1234-4111-a000-000000000002")
private val AICORE_NANO_FULL_ID: Uuid = Uuid.parse("a1c0a1c0-1234-4111-a000-000000000003")

val AICORE_NANO_FAST_MODEL: Model = Model(
    id = AICORE_NANO_FAST_ID,
    modelId = "nano-fast",
    displayName = "Gemini Nano (FAST)",
    abilities = listOf(ModelAbility.TOOL),
)

val AICORE_NANO_FULL_MODEL: Model = Model(
    id = AICORE_NANO_FULL_ID,
    modelId = "nano-full",
    displayName = "Gemini Nano (FULL)",
    abilities = listOf(ModelAbility.TOOL),
)

val AICORE_DEFAULT_MODELS: List<Model> = listOf(AICORE_NANO_FAST_MODEL, AICORE_NANO_FULL_MODEL)
