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
