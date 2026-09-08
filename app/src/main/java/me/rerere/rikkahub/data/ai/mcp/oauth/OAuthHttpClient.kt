package me.rerere.rikkahub.data.ai.mcp.oauth

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OAuth 2.x 通用 HTTP、PKCE、授权码、刷新令牌客户端（A-lite：内化于 :app，
 * 语义与上游 rikkahub/rikkahub `oauth` 模块一致）。
 */
class OAuthHttpClient(
    private val httpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** RFC 7591 动态客户端注册请求。 */
    @Serializable
    data class ClientRegistrationRequest(
        @SerialName("client_name") val clientName: String,
        @SerialName("redirect_uris") val redirectUris: List<String>,
        @SerialName("grant_types") val grantTypes: List<String> =
            listOf("authorization_code", "refresh_token"),
        @SerialName("response_types") val responseTypes: List<String> = listOf("code"),
        @SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String = "none",
        @SerialName("scope") val scope: String? = null,
    )

    @Serializable
    data class ClientRegistrationResponse(
        @SerialName("client_id") val clientId: String,
        @SerialName("client_secret") val clientSecret: String? = null,
    )

    @Serializable
    data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("token_type") val tokenType: String = "Bearer",
        @SerialName("expires_in") val expiresIn: Long? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        val scope: String? = null,
    )

    data class Pkce(val verifier: String, val challenge: String)

    data class AuthorizationRequest(
        val authorizationEndpoint: String,
        val clientId: String,
        val redirectUri: String,
        val pkce: Pkce,
        val state: String,
        val scope: String? = null,
        val resources: List<String> = emptyList(),
        val additionalParameters: Map<String, String> = emptyMap(),
    )

    data class AuthorizationCodeTokenRequest(
        val tokenEndpoint: String,
        val clientId: String,
        val clientSecret: String? = null,
        val code: String,
        val codeVerifier: String,
        val redirectUri: String,
        val resources: List<String> = emptyList(),
        val additionalParameters: Map<String, String> = emptyMap(),
    )

    data class RefreshTokenRequest(
        val tokenEndpoint: String,
        val clientId: String,
        val clientSecret: String? = null,
        val refreshToken: String,
        val scope: String? = null,
        val resources: List<String> = emptyList(),
        val additionalParameters: Map<String, String> = emptyMap(),
    )

    suspend fun registerClient(
        registrationEndpoint: String,
        request: ClientRegistrationRequest,
    ): ClientRegistrationResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(ClientRegistrationRequest.serializer(), request)
        val httpRequest = Request.Builder()
            .url(registrationEndpoint)
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val text = execute(httpRequest)
        json.decodeFromString(ClientRegistrationResponse.serializer(), text)
    }

    fun generatePkce(): Pkce {
        val verifierBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = base64Url(verifierBytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Pkce(verifier = verifier, challenge = base64Url(digest))
    }

    fun generateState(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return base64Url(bytes)
    }

    fun buildAuthorizationUrl(request: AuthorizationRequest): String {
        val base = request.authorizationEndpoint.toHttpUrlOrNull()
            ?: error("非法的授权端点: ${request.authorizationEndpoint}")
        return base.newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", request.clientId)
            .addQueryParameter("redirect_uri", request.redirectUri)
            .addQueryParameter("code_challenge", request.pkce.challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", request.state)
            .apply {
                if (!request.scope.isNullOrBlank()) addQueryParameter("scope", request.scope)
                request.resources.forEach { addQueryParameter("resource", it) }
                request.additionalParameters.forEach { (name, value) -> addQueryParameter(name, value) }
            }
            .build()
            .toString()
    }

    suspend fun exchangeAuthorizationCode(request: AuthorizationCodeTokenRequest): TokenResponse =
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", request.code)
                .add("redirect_uri", request.redirectUri)
                .add("client_id", request.clientId)
                .add("code_verifier", request.codeVerifier)
                .apply {
                    if (!request.clientSecret.isNullOrBlank()) add("client_secret", request.clientSecret)
                    request.resources.forEach { add("resource", it) }
                    request.additionalParameters.forEach { (name, value) -> add(name, value) }
                }
                .build()
            postToken(request.tokenEndpoint, form)
        }

    suspend fun refreshToken(request: RefreshTokenRequest): TokenResponse =
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", request.refreshToken)
                .add("client_id", request.clientId)
                .apply {
                    if (!request.clientSecret.isNullOrBlank()) add("client_secret", request.clientSecret)
                    if (!request.scope.isNullOrBlank()) add("scope", request.scope)
                    request.resources.forEach { add("resource", it) }
                    request.additionalParameters.forEach { (name, value) -> add(name, value) }
                }
                .build()
            postToken(request.tokenEndpoint, form)
        }

    private suspend fun postToken(tokenEndpoint: String, form: FormBody): TokenResponse {
        val request = Request.Builder()
            .url(tokenEndpoint)
            .header("Accept", "application/json")
            .post(form)
            .build()
        val (httpStatus, contentType, body) = readTokenResponse(request)
        // 先识别 OAuth error 响应：无论 2xx 还是非 2xx，只要 body 是 OAuth error JSON，就以类型化
        // 异常抛出。否则像 GitHub 这样“2xx + error JSON”的 AS 会把拒绝误报成 success decode 的
        // MissingField(access_token)（RFC 6749 §5.2 错误响应本应被客户端显式处理）。
        parseOAuthTokenError(body, json)?.let { err ->
            throw OAuthTokenEndpointException(
                // parseOAuthTokenError 仅在 error 非空时返回非 null，这里可安全断言。
                oauthError = requireNotNull(err.error),
                oauthErrorDescription = err.errorDescription,
                httpStatus = httpStatus,
                contentType = contentType,
            )
        }
        if (httpStatus !in 200..299) {
            throw IOException("HTTP $httpStatus for ${request.url}: ${body.take(300)}")
        }
        // 2xx 且无 OAuth error：按成功解码。真畸形 / 空 body 保留原始 MissingField/序列化语义。
        val token = runCatching { json.decodeFromString(TokenResponse.serializer(), body) }.getOrNull()
        if (token != null) return token
        return json.decodeFromString(TokenResponse.serializer(), body)
    }

    /** 一次读出 token 端点响应的状态、Content-Type 与 body，供错误分类使用（不含任何凭据）。 */
    private suspend fun readTokenResponse(request: Request): Triple<Int, String?, String> {
        return executeRaw(request).use { response ->
            Triple(
                response.code,
                response.header("Content-Type"),
                response.body.string(),
            )
        }
    }

    private suspend fun execute(request: Request): String {
        executeRaw(request).use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for ${request.url}: ${body.take(300)}")
            }
            return body
        }
    }

    private suspend fun executeRaw(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            })
        }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * OAuth token endpoint 的错误响应（RFC 6749 §5.2 / RFC 6750）。
 *
 * 只承载 error / error_description 这类安全字段；绝不含 access/refresh token、authorization
 * code、client_secret 或 PKCE verifier。
 */
@Serializable
data class OAuthTokenErrorResponse(
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

private const val MAX_OAUTH_ERROR_DESCRIPTION_LENGTH = 160

/** 组装安全异常消息：只含 OAuth error code + 截断后的 error_description，绝不内嵌凭据。 */
private fun buildTokenEndpointErrorMessage(error: String, description: String?, httpStatus: Int): String {
    val safeDescription = description?.take(MAX_OAUTH_ERROR_DESCRIPTION_LENGTH)?.takeIf { it.isNotBlank() }
    return if (safeDescription == null) {
        "OAuth token 端点拒绝请求(HTTP $httpStatus): $error"
    } else {
        "OAuth token 端点拒绝请求(HTTP $httpStatus): $error ($safeDescription)"
    }
}

/**
 * 类型化 token endpoint 错误。
 *
 * 消息只含 OAuth error code + 截断后的 error_description（由协调层直接呈现给用户/UI），
 * 不内嵌任何凭据或原始响应体。
 */
internal class OAuthTokenEndpointException(
    val oauthError: String,
    val oauthErrorDescription: String?,
    val httpStatus: Int,
    val contentType: String?,
) : IOException(buildTokenEndpointErrorMessage(oauthError, oauthErrorDescription, httpStatus))

/**
 * 把 token 端点响应 body 尝试解析为 OAuth error 响应；非 error 形状（非 JSON / 无 `error`
 * 字段 / error 为空）返回 null。exchange 与 refresh 共用（见 [OAuthHttpClient.postToken]）。
 */
internal fun parseOAuthTokenError(body: String, json: Json): OAuthTokenErrorResponse? {
    val parsed = runCatching { json.decodeFromString(OAuthTokenErrorResponse.serializer(), body) }.getOrNull()
    return parsed?.takeIf { !it.error.isNullOrBlank() }
}

/** 使用 Custom Tabs 打开 OAuth 授权页面。 */
fun interface OAuthAuthorizationLauncher {
    fun launch(context: Context, authorizationUrl: String)
}

object CustomTabsOAuthAuthorizationLauncher : OAuthAuthorizationLauncher {
    override fun launch(context: Context, authorizationUrl: String) {
        val intent = CustomTabsIntent.Builder().setShowTitle(true).build()
        intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.launchUrl(context, authorizationUrl.toUri())
    }
}
