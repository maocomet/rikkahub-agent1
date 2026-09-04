package me.rerere.rikkahub.data.ai.mcp.oauth

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

private const val TAG = "OAuthCallbackFgs"

/** 浏览器授权期间保持 app 进程与 loopback 回调服务器活跃。 */
class OAuthCallbackForegroundService : Service() {
    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasActiveSessions()) {
            stopService()
            return START_NOT_STICKY
        }
        val started = startForegroundCompat()
        notifyForegroundStarted(started)
        if (!started) stopService()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        notifyServiceDestroyed()
        super.onDestroy()
    }

    private fun startForegroundCompat(): Boolean = runCatching {
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForeground = true
    }.onFailure {
        Log.e(TAG, "Failed to enter foreground", it)
    }.isSuccess

    private fun stopService() {
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "OAuth callback",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): android.app.Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                NOTIFICATION_ID,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("OAuth authorization")
            .setContentText("Completing authorization in the browser…")
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply { contentIntent?.let(::setContentIntent) }
            .build()
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "oauth_callback"
        private const val NOTIFICATION_ID = 24_001
        private val FOREGROUND_START_TIMEOUT = 5.seconds

        private val lifecycleLock = Any()
        private val activeSessionIds = mutableSetOf<String>()
        private val pendingForegroundStarts = mutableMapOf<String, CompletableDeferred<Boolean>>()
        private var serviceIsForeground = false

        internal suspend fun acquire(context: Context, sessionId: String): Boolean {
            val appContext = context.applicationContext
            val foregroundStarted = CompletableDeferred<Boolean>()
            val shouldStartService = synchronized(lifecycleLock) {
                if (!activeSessionIds.add(sessionId)) {
                    foregroundStarted.complete(true)
                    false
                } else if (serviceIsForeground) {
                    foregroundStarted.complete(true)
                    false
                } else {
                    pendingForegroundStarts[sessionId] = foregroundStarted
                    true
                }
            }
            if (shouldStartService) {
                runCatching {
                    ContextCompat.startForegroundService(
                        appContext,
                        Intent(appContext, OAuthCallbackForegroundService::class.java),
                    )
                }.onFailure {
                    Log.e(TAG, "Unable to start OAuth callback foreground service", it)
                    synchronized(lifecycleLock) {
                        pendingForegroundStarts.remove(sessionId)?.complete(false)
                    }
                }
            }
            val started = withTimeoutOrNull(FOREGROUND_START_TIMEOUT) { foregroundStarted.await() } == true
            if (!started) release(appContext, sessionId)
            return started
        }

        internal fun release(context: Context, sessionId: String) {
            val appContext = context.applicationContext
            synchronized(lifecycleLock) {
                if (!activeSessionIds.remove(sessionId)) return
                pendingForegroundStarts.remove(sessionId)?.cancel()
                if (activeSessionIds.isEmpty()) {
                    serviceIsForeground = false
                    appContext.stopService(Intent(appContext, OAuthCallbackForegroundService::class.java))
                }
            }
        }

        private fun hasActiveSessions(): Boolean = synchronized(lifecycleLock) { activeSessionIds.isNotEmpty() }

        private fun notifyForegroundStarted(started: Boolean) {
            synchronized(lifecycleLock) {
                serviceIsForeground = started
                pendingForegroundStarts.values.forEach { it.complete(started) }
                pendingForegroundStarts.clear()
                if (!started) activeSessionIds.clear()
            }
        }

        private fun notifyServiceDestroyed() {
            synchronized(lifecycleLock) { serviceIsForeground = false }
        }
    }
}
