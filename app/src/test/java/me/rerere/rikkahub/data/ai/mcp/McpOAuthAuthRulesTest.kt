package me.rerere.rikkahub.data.ai.mcp

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ---- client-source strategy: static > reuse-registered > DCR > none ----

    private val REDIRECT = "http://127.0.0.1:52134/oauth/callback"

    @Test
    fun `preconfigured static client id is preferred over registered and dynamic registration`() {
        assertEquals(
            McpOAuthRegistrationPlan.StaticClient,
            mcpResolveOAuthRegistrationPlan(
                staticClientId = "static-id",
                registeredClientId = "registered-id",
                registeredRedirectUri = REDIRECT,
                redirectUri = REDIRECT,
                registrationEndpoint = "https://auth.example/register",
            )
        )
    }

    @Test
    fun `blank static client id is ignored`() {
        assertEquals(
            McpOAuthRegistrationPlan.DynamicRegistration,
            mcpResolveOAuthRegistrationPlan(
                staticClientId = "   ",
                registeredClientId = null,
                registeredRedirectUri = null,
                redirectUri = REDIRECT,
                registrationEndpoint = "https://auth.example/register",
            )
        )
    }

    @Test
    fun `previously registered client is reused when redirect matches and no static override`() {
        assertEquals(
            McpOAuthRegistrationPlan.ReuseRegisteredClient,
            mcpResolveOAuthRegistrationPlan(
                staticClientId = null,
                registeredClientId = "registered-id",
                registeredRedirectUri = REDIRECT,
                redirectUri = REDIRECT,
                registrationEndpoint = null,
            )
        )
    }

    @Test
    fun `registered client is not reused when redirect does not match`() {
        assertEquals(
            McpOAuthRegistrationPlan.DynamicRegistration,
            mcpResolveOAuthRegistrationPlan(
                staticClientId = null,
                registeredClientId = "registered-id",
                registeredRedirectUri = "https://client.example/cb",
                redirectUri = REDIRECT,
                registrationEndpoint = "https://auth.example/register",
            )
        )
    }

    @Test
    fun `redirect mismatch and no registration endpoint yields no usable client`() {
        assertEquals(
            McpOAuthRegistrationPlan.NoUsableClient,
            mcpResolveOAuthRegistrationPlan(
                staticClientId = null,
                registeredClientId = "registered-id",
                registeredRedirectUri = "https://client.example/cb",
                redirectUri = REDIRECT,
                registrationEndpoint = null,
            )
        )
    }

    @Test
    fun `dynamic registration chosen when nothing registered but registration endpoint exists`() {
        assertEquals(
            McpOAuthRegistrationPlan.DynamicRegistration,
            mcpResolveOAuthRegistrationPlan(
                staticClientId = null,
                registeredClientId = null,
                registeredRedirectUri = null,
                redirectUri = REDIRECT,
                registrationEndpoint = "https://auth.example/register",
            )
        )
    }

    @Test
    fun `no usable client when static missing registered unusable and no registration endpoint`() {
        assertEquals(
            McpOAuthRegistrationPlan.NoUsableClient,
            mcpResolveOAuthRegistrationPlan(
                staticClientId = null,
                registeredClientId = null,
                registeredRedirectUri = null,
                redirectUri = REDIRECT,
                registrationEndpoint = null,
            )
        )
    }

    // ---- clear / write authorization touches ONLY the transient oauth state (#1 regression) ----

    @Test
    fun `clearing authorization keeps preconfigured static client and other common options`() {
        val staticClient = McpStaticOAuthClient(clientId = "static-id")
        val server = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(
                name = "gh",
                oauth = McpOAuthState(enabled = true, accessToken = "tok", refreshToken = "ref"),
                oauthStaticClient = staticClient,
            ),
            url = "https://api.githubcopilot.com/mcp/",
        )

        val cleared = server.withOAuthState(null)

        assertNull(cleared.commonOptions.oauth)
        assertEquals(staticClient, cleared.commonOptions.oauthStaticClient)
        assertEquals("gh", cleared.commonOptions.name)
        assertEquals("https://api.githubcopilot.com/mcp/", cleared.serverUrl)
    }

    @Test
    fun `writing oauth state keeps preconfigured static client untouched`() {
        val staticClient = McpStaticOAuthClient(clientId = "static-id")
        val server = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(name = "gh", oauthStaticClient = staticClient),
            url = "https://api.githubcopilot.com/mcp/",
        )

        val refreshed = server.withOAuthState(McpOAuthState(enabled = true, accessToken = "new"))

        assertEquals(staticClient, refreshed.commonOptions.oauthStaticClient)
        assertTrue(refreshed.commonOptions.oauth?.enabled == true)
    }
}
