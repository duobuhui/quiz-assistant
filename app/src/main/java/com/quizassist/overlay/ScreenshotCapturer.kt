package com.quizassist.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class ScreenshotCapturer(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var latestFrame: Bitmap? = null
    private var latestFrameAt: Long = 0L
    private var captureWidth: Int = 0
    private var captureHeight: Int = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            cleanupDisplay()
            mediaProjection = null
            ProjectionPermissionStore.clear()
        }
    }

    fun ensureProjection(): Boolean {
        if (mediaProjection != null) {
            ensureDisplay()
            return true
        }
        val code = ProjectionPermissionStore.resultCode
        val data = ProjectionPermissionStore.data ?: return false
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(code, data).also {
            it.registerCallback(projectionCallback, mainHandler)
        }
        ensureDisplay()
        return mediaProjection != null
    }

    suspend fun captureFullScreen(): Bitmap {
        if (!ensureProjection()) error("MediaProjection permission missing")
        latestFrame?.let { return it.copy(Bitmap.Config.ARGB_8888, false) }
        val frame = withTimeoutOrNull(1500L) {
            while (latestFrame == null) {
                delay(50L)
            }
            latestFrame
        } ?: error("No screen frame available")
        return frame.copy(Bitmap.Config.ARGB_8888, false)
    }

    suspend fun captureFreshFrame(afterUptimeMs: Long): Bitmap {
        if (!ensureProjection()) error("MediaProjection permission missing")
        val frame = withTimeoutOrNull(2500L) {
            while (latestFrame == null || latestFrameAt <= afterUptimeMs) {
                delay(40L)
            }
            latestFrame
        } ?: latestFrame ?: error("No fresh screen frame available")
        return frame.copy(Bitmap.Config.ARGB_8888, false)
    }

    fun stop() {
        cleanupDisplay()
        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun ensureDisplay() {
        if (virtualDisplay != null && imageReader != null) return
        val projection = mediaProjection ?: return
        val metrics = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels

        cleanupDisplay()
        val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 3)
        imageReader = reader
        reader.setOnImageAvailableListener({ availableReader ->
            val image = availableReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            image.use {
                val plane = it.planes.first()
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * captureWidth
                val paddedBitmap = Bitmap.createBitmap(
                    captureWidth + rowPadding / pixelStride,
                    captureHeight,
                    Bitmap.Config.ARGB_8888,
                )
                paddedBitmap.copyPixelsFromBuffer(buffer)
                val bitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, captureWidth, captureHeight)
                paddedBitmap.recycle()
                latestFrame?.recycle()
                latestFrame = bitmap
                latestFrameAt = android.os.SystemClock.uptimeMillis()
            }
        }, mainHandler)
        virtualDisplay = projection.createVirtualDisplay(
            "quiz-assist-capture",
            captureWidth,
            captureHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            mainHandler,
        )
    }

    private fun cleanupDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        latestFrame?.recycle()
        latestFrame = null
        latestFrameAt = 0L
    }
}
