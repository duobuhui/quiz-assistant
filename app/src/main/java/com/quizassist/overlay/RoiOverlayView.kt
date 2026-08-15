package com.quizassist.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.quizassist.model.RoiBox

class RoiOverlayView(
    context: Context,
    private val onRoiChanged: (RoiBox) -> Unit,
) : View(context) {
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 184, 166)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000
        style = Paint.Style.FILL
    }
    private val rect = RectF()
    private var roi = RoiBox(0.08f, 0.22f, 0.84f, 0.38f)
    private var lastX = 0f
    private var lastY = 0f

    fun setRoi(value: RoiBox?) {
        roi = value ?: roi
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        rect.set(
            roi.leftRatio * width,
            roi.topRatio * height,
            (roi.leftRatio + roi.widthRatio) * width,
            (roi.topRatio + roi.heightRatio) * height,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), rect.top, shade)
        canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), shade)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, shade)
        canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, shade)
        canvas.drawRect(rect, border)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.x - lastX) / width
                val dy = (event.y - lastY) / height
                lastX = event.x
                lastY = event.y
                roi = roi.copy(
                    leftRatio = (roi.leftRatio + dx).coerceIn(0f, 1f - roi.widthRatio),
                    topRatio = (roi.topRatio + dy).coerceIn(0f, 1f - roi.heightRatio),
                )
                onRoiChanged(roi)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
