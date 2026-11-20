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
        val guideColor = when (framingState) {
            1 -> Color.rgb(45, 212, 191)
            2 -> Color.rgb(251, 191, 36)
            else -> Color.WHITE
        }

        // Standard ID-photo composition: roughly 10% headroom, 53% head height,
        // chin around 64%, shoulders starting around 68% of the frame.
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(88, 5, 8, 22)
        canvas.drawRect(0f, 0f, width.toFloat(), rect.top, paint)
        canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), paint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, paint)
        canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3.5f
        paint.color = Color.argb(170, 255, 255, 255)
        paint.pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
        canvas.drawRoundRect(rect, 28f, 28f, paint)
        paint.pathEffect = null

        val headTop = top + actualHeight * 0.105f
        val headHeight = actualHeight * 0.53f
        val headWidth = minOf(frameWidth * 0.58f, headHeight * 0.76f)
        val centerX = width / 2f
        val head = RectF(centerX - headWidth / 2f, headTop,
            centerX + headWidth / 2f, headTop + headHeight)
        paint.strokeWidth = 5.5f
        paint.color = guideColor
        canvas.drawOval(head, paint)

        val chinY = head.bottom
        val shoulderY = top + actualHeight * 0.68f
        val neckWidth = headWidth * 0.24f
        path.reset()
        path.moveTo(centerX - neckWidth / 2f, chinY - 3f)
        path.lineTo(centerX - neckWidth / 2f, shoulderY)
        path.cubicTo(centerX - frameWidth * 0.19f, shoulderY + actualHeight * 0.015f,
            rect.left + frameWidth * 0.17f, shoulderY + actualHeight * 0.035f,
            rect.left + frameWidth * 0.105f, rect.bottom)
        path.lineTo(rect.right - frameWidth * 0.105f, rect.bottom)
        path.cubicTo(rect.right - frameWidth * 0.17f, shoulderY + actualHeight * 0.035f,
            centerX + frameWidth * 0.19f, shoulderY + actualHeight * 0.015f,
            centerX + neckWidth / 2f, shoulderY)
        path.lineTo(centerX + neckWidth / 2f, chinY - 3f)
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(24, 255, 255, 255)
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.color = guideColor
        canvas.drawPath(path, paint)

        paint.strokeWidth = 2.5f
        paint.color = Color.argb(135, 255, 255, 255)
        canvas.drawLine(centerX, rect.top + 18f, centerX, rect.bottom - 18f, paint)
        canvas.drawLine(head.left, head.top + head.height() * 0.42f,
            head.right, head.top + head.height() * 0.42f, paint)
        canvas.drawLine(rect.left + frameWidth * 0.13f, shoulderY,
            rect.right - frameWidth * 0.13f, shoulderY, paint)

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
