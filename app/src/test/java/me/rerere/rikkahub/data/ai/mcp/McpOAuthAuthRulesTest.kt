package me.rerere.rikkahub.data.ai.mcp

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the A-lite MCP OAuth policy rules (401→OAuth classification, header
 * merge order, single-retry gate). Pure JVM, no network/Android.
 */
class McpOAuthAuthRulesTest {

    private fun err(message: String): Throwable = IllegalStateException(message)

    @Test
    fun `401 with resource metadata is classified as unauthorized`() {
        assertTrue(
            mcpLooksUnauthorized(
                err("HTTP 401 Unauthorized — WWW-Authenticate resource_metadata=\"https://auth.example/.well-known/oauth-protected-resource\"")
            )
        )
        assertTrue(mcpLooksUnauthorized(err("invalid_token expired")))
        assertTrue(mcpLooksUnauthorized(IOException("missing or invalid credentials")))
        // Nested cause chain is also inspected.
        assertTrue(
            mcpLooksUnauthorized(
                RuntimeException("outer", err("Unauthorized"))
            )
        )
    }

    @Test
    fun `non-oauth failures are not classified as unauthorized`() {
        assertFalse(mcpLooksUnauthorized(err("connect timed out")))
        assertFalse(mcpLooksUnauthorized(err("connection refused")))
        assertFalse(mcpLooksUnauthorized(IOException("no route to host")))
        assertFalse(mcpLooksUnauthorized(err("")))
    }

    @Test
    fun `oauth disabled never injects bearer`() {
        val base = listOf("X-Api-Key" to "abc")
        assertEquals(base, mcpMergeOAuthHeader(base, oauthEnabled = false, accessToken = "tok"))
    }

    @Test
    fun `oauth token appended after static headers when no explicit authorization`() {
        val base = listOf("X-Api-Key" to "abc")
        val merged = mcpMergeOAuthHeader(base, oauthEnabled = true, accessToken = "tok")
        assertEquals(listOf("X-Api-Key" to "abc", "Authorization" to "Bearer tok"), merged)
    }

    @Test
    fun `explicit authorization header always wins over oauth`() {
        val base = listOf("Authorization" to "Bearer static", "X-Custom" to "1")
        val merged = mcpMergeOAuthHeader(base, oauthEnabled = true, accessToken = "oauth-tok")
        assertEquals(listOf("Authorization" to "Bearer static", "X-Custom" to "1"), merged)
    }

    @Test
    fun `missing oauth token leaves headers unchanged`() {
        val base = listOf("X-Api-Key" to "abc")
        assertEquals(base, mcpMergeOAuthHeader(base, oauthEnabled = true, accessToken = null))
        assertEquals(base, mcpMergeOAuthHeader(base, oauthEnabled = true, accessToken = "  "))
    }

    @Test
    fun `single retry gate requires access and refresh token`() {
        val full = McpOAuthState(enabled = true, accessToken = "a", refreshToken = "r")
        assertTrue(mcpCanSingleRetryOnOAuth(full))
        assertFalse(mcpCanSingleRetryOnOAuth(full.copy(enabled = false)))
        assertFalse(mcpCanSingleRetryOnOAuth(full.copy(refreshToken = null)))
        assertFalse(mcpCanSingleRetryOnOAuth(full.copy(accessToken = null)))
        assertFalse(mcpCanSingleRetryOnOAuth(null))
    }
}
