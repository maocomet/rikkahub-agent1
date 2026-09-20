package me.rerere.ai.provider

import android.content.Context
import me.rerere.ai.provider.claudep.UnpairedClaudePGatewayClient
import me.rerere.ai.provider.providers.AICoreProvider
import me.rerere.ai.provider.providers.ClaudePProvider
import me.rerere.ai.provider.providers.ClaudeProvider
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.provider.providers.OpenAIProvider
import okhttp3.OkHttpClient

/**
 * Registry key for [ProviderSetting.ClaudeP]. Stable, because it is also the value the app's DI
 * layer overrides at CP1-B when the real WebSocket transport replaces the inert one.
 */
const val CLAUDEP_REGISTRY_KEY: String = "claude_p"

/**
 * Maps a settings type onto its registry key.
 *
 * Extracted from [ProviderManager] so the mapping is a pure function: the manager itself needs an
 * Android `Context`, which a JVM unit test cannot construct, and an untestable dispatch table is
 * exactly where a new provider type silently falls into the wrong branch.
 */
internal fun providerRegistryKeyOf(setting: ProviderSetting): String = when (setting) {
    is ProviderSetting.OpenAI -> "openai"
    is ProviderSetting.Google -> "google"
    is ProviderSetting.Claude -> "claude"
    is ProviderSetting.AICore -> "aicore"
    is ProviderSetting.LiteRtLocal -> "local_litert"
    is ProviderSetting.Codex -> "codex"
    is ProviderSetting.ClaudeP -> CLAUDEP_REGISTRY_KEY
}

/**
 * Provider管理器，负责注册和获取Provider实例
 */
class ProviderManager private constructor(
    // 存储已注册的Provider实例
    private val providers: MutableMap<String, Provider<*>>,
) {
    constructor(client: OkHttpClient, context: Context) : this(mutableMapOf()) {
        // 注册默认Provider
        registerProvider("openai", OpenAIProvider(client, context))
        registerProvider("google", GoogleProvider(client, context))
        registerProvider("claude", ClaudeProvider(client, context))
        registerProvider("aicore", AICoreProvider(context))
        // Claude P is registered with a deliberately inert transport. The local skeleton has no
        // device pairing yet, so every call reports NOT_PAIRED rather than reaching anything. The
        // real WSS client is bound in place of this at CP1-B.
        //
        // Registering it here (rather than leaving the key absent) keeps getProviderByType total:
        // an interactive caller gets a typed, actionable failure instead of an
        // IllegalArgumentException from a missing registry entry.
        registerProvider(CLAUDEP_REGISTRY_KEY, ClaudePProvider(UnpairedClaudePGatewayClient))
    }

    /**
     * 注册Provider实例
     *
     * @param name Provider名称
     * @param provider Provider实例
     */
    fun registerProvider(name: String, provider: Provider<*>) {
        providers[name] = provider
    }

    /**
     * 获取Provider实例
     *
     * @param name Provider名称
     * @return Provider实例，如果不存在则返回null
     */
    fun getProvider(name: String): Provider<*> {
        return providers[name] ?: throw IllegalArgumentException("Provider not found: $name")
    }

    /**
     * 根据ProviderSetting获取对应的Provider实例
     *
     * @param setting Provider设置
     * @return Provider实例，如果不存在则返回null
     */
    fun <T : ProviderSetting> getProviderByType(setting: T): Provider<T> {
        @Suppress("UNCHECKED_CAST")
        return getProvider(providerRegistryKeyOf(setting)) as Provider<T>
    }

    companion object {
        /**
         * Builds a manager over an explicit registry, with no Android dependency.
         *
         * Exists so the type-to-provider dispatch can be unit tested on the JVM; production code
         * uses the `(OkHttpClient, Context)` constructor.
         */
        internal fun withRegistry(
            vararg entries: Pair<String, Provider<*>>,
        ): ProviderManager = ProviderManager(entries.toMap(mutableMapOf()))
    }
}
