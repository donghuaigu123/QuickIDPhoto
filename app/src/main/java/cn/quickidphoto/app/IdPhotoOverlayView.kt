package cn.quickidphoto.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class IdPhotoOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    var framingState: Int = 0
        set(value) { field = value; invalidate() }
    var aspect: Float = 295f / 413f
        set(value) { field = value; invalidate() }
    var gridVisible: Boolean = true
        set(value) { field = value; invalidate() }

    fun toggleGrid() {
        gridVisible = !gridVisible
        invalidate()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frameHeight = height * 0.84f
        val frameWidth = minOf(width * 0.90f, frameHeight * aspect)
        val actualHeight = frameWidth / aspect
        val left = (width - frameWidth) / 2f
        val top = (height - actualHeight) / 2f
        val rect = RectF(left, top, left + frameWidth, top + actualHeight)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = when (framingState) {
            1 -> Color.rgb(82, 230, 161)
            2 -> Color.rgb(255, 184, 77)
            else -> Color.WHITE
        }
        paint.pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
        canvas.drawRoundRect(rect, 24f, 24f, paint)
        paint.pathEffect = null

        val headWidth = frameWidth * 0.39f
        val headHeight = actualHeight * 0.29f
        val head = RectF((width - headWidth) / 2f, top + actualHeight * 0.13f,
            (width + headWidth) / 2f, top + actualHeight * 0.13f + headHeight)
        paint.strokeWidth = 5f
        canvas.drawOval(head, paint)

        path.reset()
        val shoulderY = head.bottom + actualHeight * 0.06f
        path.moveTo(width / 2f - frameWidth * 0.10f, head.bottom - 4f)
        path.lineTo(width / 2f - frameWidth * 0.11f, shoulderY)
        path.cubicTo(width / 2f - frameWidth * 0.20f, shoulderY,
            left + frameWidth * 0.16f, shoulderY + actualHeight * 0.03f,
            left + frameWidth * 0.10f, rect.bottom)
        path.moveTo(width / 2f + frameWidth * 0.10f, head.bottom - 4f)
        path.lineTo(width / 2f + frameWidth * 0.11f, shoulderY)
        path.cubicTo(width / 2f + frameWidth * 0.20f, shoulderY,
            left + frameWidth * 0.84f, shoulderY + actualHeight * 0.03f,
            left + frameWidth * 0.90f, rect.bottom)
        canvas.drawPath(path, paint)

        if (gridVisible) {
            paint.color = Color.argb(100, 255, 255, 255)
            paint.strokeWidth = 2f
            for (i in 1 until 3) {
                canvas.drawLine(left + frameWidth * i / 3f, top, left + frameWidth * i / 3f, top + actualHeight, paint)
                canvas.drawLine(left, top + actualHeight * i / 3f, left + frameWidth, top + actualHeight * i / 3f, paint)
            }
        }
        paint.style = Paint.Style.FILL
        paint.textSize = 32f
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("脸部对齐椭圆 · 双肩保持水平", width / 2f, rect.bottom + 46f, paint)
    }
}
