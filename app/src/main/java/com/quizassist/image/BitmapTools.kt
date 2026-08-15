package com.quizassist.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import com.quizassist.model.RoiBox
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

object BitmapTools {
    fun cropByRoi(source: Bitmap, roi: RoiBox?): Bitmap {
        if (roi == null || !roi.isValid()) return source
        val rect = roi.toRect(source.width, source.height)
        return Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
    }

    fun toBase64Jpeg(bitmap: Bitmap, quality: Int = 86): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 95), out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun prepareForOcr(bitmap: Bitmap): Bitmap {
        val scaled = upscaleForText(bitmap)
        val output = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val matrix = ColorMatrix().apply {
            setSaturation(0f)
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        1.45f, 0f, 0f, 0f, -28f,
                        0f, 1.45f, 0f, 0f, -28f,
                        0f, 0f, 1.45f, 0f, -28f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        Canvas(output).drawBitmap(scaled, 0f, 0f, paint)
        if (scaled !== bitmap) scaled.recycle()
        return output
    }

    private fun upscaleForText(bitmap: Bitmap): Bitmap {
        val minWidth = 1600
        if (bitmap.width >= minWidth) return bitmap
        val scale = minWidth / bitmap.width.toFloat()
        val width = (bitmap.width * scale).toInt().coerceAtLeast(bitmap.width)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(bitmap.height)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    fun perceptualEnoughHash(bitmap: Bitmap, text: String?): String {
        val scaled = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        val pixels = IntArray(32 * 32)
        scaled.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        val digest = MessageDigest.getInstance("SHA-256")
        pixels.forEach { pixel ->
            digest.update((pixel shr 16).toByte())
            digest.update((pixel shr 8).toByte())
            digest.update(pixel.toByte())
        }
        text?.trim()?.lowercase()?.take(1000)?.let { digest.update(it.toByteArray()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun RoiBox.toRect(width: Int, height: Int): Rect {
        val left = (leftRatio * width).toInt().coerceIn(0, width - 1)
        val top = (topRatio * height).toInt().coerceIn(0, height - 1)
        val right = ((leftRatio + widthRatio) * width).toInt().coerceIn(left + 1, width)
        val bottom = ((topRatio + heightRatio) * height).toInt().coerceIn(top + 1, height)
        return Rect(left, top, right, bottom)
    }
}
