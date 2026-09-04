package me.rerere.rikkahub.data.ai.mcp.oauth

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

data class OAuthCallback(
    val code: String?,
    val state: String,
    val error: String?,
    val errorDescription: String?,
)

/**
 * 临时 OAuth loopback 回调服务器（A-lite：内化于 :app）。
 *
 * 只绑定 IPv4 回环地址；首个授权会话启动、最后一个会话结束时停止。多会话按 state 路由，
 * 可并发。首个会话由前台服务保活，避免浏览器授权期间进程被回收。
 */
class OAuthLoopbackCallbackServer(
    private val port: Int = 0,
    callbackPath: String = "/oauth/callback",
) {
    private class CallbackRegistration {
        val result = CompletableDeferred<OAuthCallback>()
        val claimed = AtomicBoolean(false)
    }

    private val callbackPath = callbackPath.requireValidCallbackPath()
    private val lifecycleMutex = Mutex()
    private val callbacks = ConcurrentHashMap<String, CallbackRegistration>()

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var redirectUri: String? = null

    init {
        require(port in 0..65535) { "非法的 OAuth 回调端口: $port" }
    }

    /** 打开由前台服务保活的授权会话。 */
    suspend fun openSession(context: Context, expectedState: String): OAuthLoopbackCallbackSession {
        require(expectedState.isNotBlank()) { "OAuth state 不能为空" }
        val registration = CallbackRegistration()
        return lifecycleMutex.withLock {
            check(callbacks.putIfAbsent(expectedState, registration) == null) {
                "OAuth state 已存在待处理的授权会话"
            }
            try {
                val uri = ensureStarted()
                check(
                    OAuthCallbackForegroundService.acquire(context.applicationContext, expectedState),
                ) { "无法启动 OAuth 回调前台服务" }
                OAuthLoopbackCallbackSession(
                    redirectUri = uri,
                    expectedState = expectedState,
                    callback = registration.result,
                    foregroundServiceContext = context.applicationContext,
                    owner = this,
                )
            } catch (e: Exception) {
                callbacks.remove(expectedState, registration)
                OAuthCallbackForegroundService.release(context.applicationContext, expectedState)
                stopServerIfIdle()
                throw e
            }
        }
    }

    private suspend fun ensureStarted(): String {
        redirectUri?.let { return it }
        val newServer = embeddedServer(CIO, host = LOOPBACK_HOST, port = port) {
            routing { get(callbackPath) { handleCallback(call) } }
        }
        try {
            newServer.startSuspend(wait = false)
            val resolvedPort = newServer.engine.resolvedConnectors().single().port
            return "http://$LOOPBACK_HOST:$resolvedPort$callbackPath".also {
                server = newServer
                redirectUri = it
            }
        } catch (e: Exception) {
            runCatching { newServer.stop(gracePeriodMillis = 0, timeoutMillis = 1_000) }
            throw e
        }
    }

    private suspend fun handleCallback(call: ApplicationCall) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val state = call.request.queryParameters["state"]
        val registration = state?.let(callbacks::get)
        if (state.isNullOrBlank() || registration == null) {
            call.respondText(INVALID_HTML, ContentType.Text.Html, HttpStatusCode.BadRequest)
            return
        }
        val code = call.request.queryParameters["code"]
        val error = call.request.queryParameters["error"]
        if (code.isNullOrBlank() && error.isNullOrBlank()) {
            call.respondText(INVALID_HTML, ContentType.Text.Html, HttpStatusCode.BadRequest)
            return
        }
        val result = OAuthCallback(
            code = code,
            state = state,
            error = error,
            errorDescription = call.request.queryParameters["error_description"],
        )
        val responseHtml = if (error == null) SUCCESS_HTML else ERROR_HTML
        if (!registration.claimed.compareAndSet(false, true)) {
            call.respondText(HANDLED_HTML, ContentType.Text.Html, HttpStatusCode.Conflict)
            return
        }
        try {
            call.respondText(responseHtml, ContentType.Text.Html, HttpStatusCode.OK)
        } finally {
            registration.result.complete(result)
        }
    }

    internal suspend fun closeSession(
        expectedState: String,
        callback: CompletableDeferred<OAuthCallback>,
        foregroundServiceContext: Context?,
    ) {
        lifecycleMutex.withLock {
            callbacks[expectedState]
                ?.takeIf { it.result === callback }
                ?.let { callbacks.remove(expectedState, it) }
            callback.cancel()
            foregroundServiceContext?.let { OAuthCallbackForegroundService.release(it, expectedState) }
            stopServerIfIdle()
        }
    }

    private suspend fun stopServerIfIdle() {
        if (callbacks.isEmpty()) {
            server?.stopSuspend(gracePeriodMillis = 0, timeoutMillis = 1_000)
            server = null
            redirectUri = null
        }
    }

    private fun String.requireValidCallbackPath(): String {
        require(startsWith('/') && !startsWith("//")) { "OAuth callbackPath 必须是单个 / 开头的绝对路径" }
        require('?' !in this && '#' !in this) { "OAuth callbackPath 不能包含 query 或 fragment" }
        return this
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val SUCCESS_HTML = "<!doctype html><html><body style='font-family:sans-serif;text-align:center;padding-top:80px'><h2>Authorization complete</h2><p>You can close this tab and return to RikkaHub.</p></body></html>"
        const val ERROR_HTML = "<!doctype html><html><body style='font-family:sans-serif;text-align:center;padding-top:80px'><h2>Authorization failed</h2><p>The authorization provider did not approve this request. Close this tab and retry.</p></body></html>"
        const val INVALID_HTML = "<!doctype html><html><body><h2>Invalid authorization callback</h2></body></html>"
        const val HANDLED_HTML = "<!doctype html><html><body><h2>Authorization already handled</h2></body></html>"
    }
}

class OAuthLoopbackCallbackSession internal constructor(
    val redirectUri: String,
    private val expectedState: String,
    private val callback: CompletableDeferred<OAuthCallback>,
    private val foregroundServiceContext: Context?,
    private val owner: OAuthLoopbackCallbackServer,
) {
    private val closed = AtomicBoolean(false)

    suspend fun awaitCallback(timeout: Duration): OAuthCallback? =
        withTimeoutOrNull(timeout) { callback.await() }

    suspend fun close() {
        if (closed.compareAndSet(false, true)) {
            owner.closeSession(expectedState, callback, foregroundServiceContext)
        }
    }
}
