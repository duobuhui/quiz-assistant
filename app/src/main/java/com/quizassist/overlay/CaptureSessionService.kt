package com.quizassist.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CaptureSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var capturer: ScreenshotCapturer? = null

    override fun onCreate() {
        super.onCreate()
        runCatching {
            startForeground(72, notification())
            capturer = ScreenshotCapturer(this).also { it.ensureProjection() }
            OverlayController.onCaptureSessionReady()
        }.onFailure {
            OverlayController.onCaptureFailed("\u622a\u56fe\u4f1a\u8bdd\u542f\u52a8\u5931\u8d25\uff1a${it.message.orEmpty()}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CAPTURE) {
            val after = intent.getLongExtra(EXTRA_AFTER_UPTIME, 0L)
            scope.launch { captureOnce(after) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        capturer?.stop()
        capturer = null
        OverlayController.onCaptureSessionStopped()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun captureOnce(afterUptimeMs: Long) {
        val cap = capturer
        if (cap == null || !cap.ensureProjection()) {
            OverlayController.onCaptureFailed("\u622a\u56fe\u4f1a\u8bdd\u672a\u5c31\u7eea\uff0c\u8bf7\u91cd\u65b0\u6388\u6743")
            return
        }
        val bitmap = runCatching {
            if (afterUptimeMs > 0L) {
                delay(180L)
                cap.captureFreshFrame(afterUptimeMs + 180L)
            } else {
                cap.captureFullScreen()
            }
        }
            .onFailure { OverlayController.onCaptureFailed("\u622a\u56fe\u5931\u8d25\uff1a${it.message.orEmpty()}") }
            .getOrNull()
        if (bitmap != null) {
            OverlayController.onCaptured(bitmap)
        }
    }

    private fun notification(): Notification {
        val channelId = "quiz_assist_capture"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, "\u7b54\u9898\u52a9\u624b\u622a\u56fe\u4f1a\u8bdd", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("\u7b54\u9898\u52a9\u624b\u622a\u56fe\u5df2\u5c31\u7eea")
                .setContentText("\u5df2\u4fdd\u6301\u5c4f\u5e55\u622a\u56fe\u6388\u6743\uff0c\u70b9\u51fb\u60ac\u6d6e\u7a97\u53ef\u76f4\u63a5\u8bc6\u522b")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("\u7b54\u9898\u52a9\u624b\u622a\u56fe\u5df2\u5c31\u7eea")
                .setContentText("\u5df2\u4fdd\u6301\u5c4f\u5e55\u622a\u56fe\u6388\u6743\uff0c\u70b9\u51fb\u60ac\u6d6e\u7a97\u53ef\u76f4\u63a5\u8bc6\u522b")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        }
    }

    companion object {
        const val ACTION_CAPTURE = "com.quizassist.action.CAPTURE"
        const val EXTRA_AFTER_UPTIME = "after_uptime"

        fun startIntent(context: Context): Intent =
            Intent(context, CaptureSessionService::class.java)

        fun captureIntent(context: Context, afterUptimeMs: Long = SystemClock.uptimeMillis()): Intent =
            Intent(context, CaptureSessionService::class.java)
                .setAction(ACTION_CAPTURE)
                .putExtra(EXTRA_AFTER_UPTIME, afterUptimeMs)
    }
}
