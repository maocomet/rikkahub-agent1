package me.rerere.rikkahub.data.ai.mcp

/**
 * Pure, testable OAuth policy helpers for the A-lite MCP flow. Kept free of Android / network
 * so the 401-classification, header-merge order, and single-retry decisions can be unit tested.
 */

/** Cheap classifier over the exception cause-chain text. */
internal fun mcpLooksUnauthorized(error: Throwable): Boolean {
    val message = generateSequence(error) { it.cause }
        .mapNotNull { it.message }
        .joinToString(" ")
        .lowercase()
    return message.contains("401") ||
        message.contains("unauthorized") ||
        message.contains("invalid_token") ||
        message.contains("invalid access token") ||
        message.contains("missing or invalid")
}

/**
 * Merge OAuth `Authorization: Bearer` after static/secret-vault headers.
 * An explicit Authorization header (static or vault) always wins and is never overwritten.
 */
internal fun mcpMergeOAuthHeader(
    base: List<Pair<String, String>>,
    oauthEnabled: Boolean,
    accessToken: String?,
): List<Pair<String, String>> {
    if (!oauthEnabled) return base
    if (base.any { it.first.equals("Authorization", ignoreCase = true) }) return base
    return if (accessToken.isNullOrBlank()) base else base + ("Authorization" to "Bearer $accessToken")
}

/** A call failure may auto-refresh+retry exactly once only when we have both an access and refresh token. */
internal fun mcpCanSingleRetryOnOAuth(oauth: McpOAuthState?): Boolean =
    oauth?.enabled == true &&
        !oauth.accessToken.isNullOrBlank() &&
        !oauth.refreshToken.isNullOrBlank()

/**
 * OAuth 客户端来源决策：给定无动态注册（或无可用注册通道）的授权服务器时，coordinator
 * 该用哪个 client 去授权。纯函数、可单测；授权流程用它决定走静态 / 复用 / DCR / 报错。
 *
 * 优先级（先满足先采用）：
 * 1. 用户预注册静态客户端（[McpCommonOptions.oauthStaticClient].clientId 非空）——显式配置优先；
 * 2. 复用已授权/已注册客户端（`oauth.clientId` 非空，且其 redirect 与本次一致）——避免对同一服务器重复 DCR；
 * 3. 授权服务器支持 RFC 7591 动态注册 → [McpOAuthRegistrationPlan.DynamicRegistration]；
 * 4. 否则 [McpOAuthRegistrationPlan.NoUsableClient]（coordinator 需给出“请填写 client_id”的可操作错误）。
 */
internal enum class McpOAuthRegistrationPlan {
    StaticClient,
    ReuseRegisteredClient,
    DynamicRegistration,
    NoUsableClient,
}

internal fun mcpResolveOAuthRegistrationPlan(
    staticClientId: String?,
    registeredClientId: String?,
    registeredRedirectUri: String?,
    redirectUri: String,
    registrationEndpoint: String?,
): McpOAuthRegistrationPlan = when {
    !staticClientId.isNullOrBlank() -> McpOAuthRegistrationPlan.StaticClient
    !registeredClientId.isNullOrBlank() && registeredRedirectUri == redirectUri ->
        McpOAuthRegistrationPlan.ReuseRegisteredClient
    !registrationEndpoint.isNullOrBlank() -> McpOAuthRegistrationPlan.DynamicRegistration
    else -> McpOAuthRegistrationPlan.NoUsableClient
}

/**
 * 写入/清除 transient OAuth 状态的统一入口：只改 [McpCommonOptions.oauth]，
 * **绝不触碰**用户预配置的 [McpCommonOptions.oauthStaticClient]。
 *
 * coordinator 的 persistOAuthState（token 写入、token 清除 clearAuthorization 均传 `oauth=null`
 * 走这里），因此清除授权后用户填写的预注册 client_id 仍然保留、可一键再授权。
 */
internal fun McpServerConfig.withOAuthState(oauth: McpOAuthState?): McpServerConfig =
    clone(commonOptions = commonOptions.copy(oauth = oauth))
