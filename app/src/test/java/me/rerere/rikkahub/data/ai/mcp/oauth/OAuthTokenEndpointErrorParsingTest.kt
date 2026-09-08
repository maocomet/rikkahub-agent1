package me.rerere.rikkahub.data.ai.mcp.oauth

import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM regression for generic OAuth token endpoint error parsing.
 *
 * Some authorization servers (e.g. GitHub) answer token-endpoint failures with
 * "HTTP 2xx + OAuth error JSON". Those must be surfaced as a typed error/error_description —
 * never as a MissingField(access_token) SerializationException. No network / Android involved.
 */
class OAuthTokenEndpointErrorParsingTest {

    // Must mirror OAuthHttpClient.json so classification matches production decode behaviour.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `success token body is not classified as an oauth error`() {
        val body = """{"access_token":"tok","token_type":"Bearer","expires_in":3600}"""
        assertNull(parseOAuthTokenError(body, json))
    }

    @Test
    fun `oauth error body yields typed error with description`() {
        val body = """{"error":"bad_verification_code","error_description":"The code passed is incorrect or expired."}"""
        val err = parseOAuthTokenError(body, json)
        assertNotNull(err)
        assertEquals("bad_verification_code", err!!.error)
        assertEquals("The code passed is incorrect or expired.", err.errorDescription)
    }

    @Test
    fun `oauth error body without description still parsed`() {
        val body = """{"error":"unauthorized_client"}"""
        val err = parseOAuthTokenError(body, json)
        assertNotNull(err)
        assertEquals("unauthorized_client", err!!.error)
        assertNull(err.errorDescription)
    }

    @Test
    fun `blank or null error field is not an oauth error`() {
        assertNull(parseOAuthTokenError("""{"error":""}""", json))
        assertNull(parseOAuthTokenError("""{"access_token":"x","error":null}""", json))
    }

    @Test
    fun `non-json or empty body is not classified as oauth error`() {
        assertNull(parseOAuthTokenError("<html>not json</html>", json))
        assertNull(parseOAuthTokenError("", json))
    }

    @Test
    fun `typed exception carries actionable code description and http status`() {
        val ex = OAuthTokenEndpointException(
            oauthError = "incorrect_client_credentials",
            oauthErrorDescription = "The client_id and/or client_secret are incorrect.",
            httpStatus = 200,
            contentType = "application/json",
        )
        assertTrue(ex is IOException)
        val msg = ex.message.orEmpty()
        assertTrue(msg.contains("incorrect_client_credentials"))
        assertTrue(msg.contains("HTTP 200"))
        assertTrue(msg.contains("incorrect."))
        assertFalse(msg.contains("access_token"))
    }

    @Test
    fun `missing access_token in success decode still throws serialization error`() {
        // Documents the pre-fix failure mode that the error classifier now prevents from
        // reaching users verbatim: decoding an error body as a success TokenResponse is a
        // MissingField(access_token) SerializationException.
        val thrown = try {
            json.decodeFromString(OAuthHttpClient.TokenResponse.serializer(), """{"error":"bad_verification_code"}""")
            null
        } catch (e: Exception) {
            e
        }
        assertNotNull(thrown)
        assertTrue(thrown is SerializationException)
    }
}
