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

        // Standard ID-photo composition: ~8.5% headroom, head height 52% of the frame,
        // chin at ~60% height, shoulders fully visible with a small margin on both sides.
        val headCx = width / 2f
        val headW = frameWidth * 0.48f
        val headH = actualHeight * 0.52f
        val headTop = top + actualHeight * 0.085f
        val headBottom = headTop + headH

        // Continuous bust silhouette: head → neck → shoulders → torso.
        path.reset()
        val chinL = headCx - headW * 0.30f
        val chinR = headCx + headW * 0.30f
        path.moveTo(chinL, headBottom)
        path.cubicTo(headCx - headW * 0.53f, headBottom - headH * 0.16f,
            headCx - headW * 0.53f, headTop + headH * 0.16f, headCx, headTop)
        path.cubicTo(headCx + headW * 0.53f, headTop + headH * 0.16f,
            headCx + headW * 0.53f, headBottom - headH * 0.16f, chinR, headBottom)
        val neckY = headBottom + actualHeight * 0.05f
        val neckW = headW * 0.30f
        path.cubicTo(chinR, headBottom + headH * 0.10f, headCx + neckW * 1.15f, neckY,
            headCx + neckW, neckY + actualHeight * 0.012f)
        path.cubicTo(headCx + headW * 0.60f, neckY + actualHeight * 0.035f,
            rect.right - frameWidth * 0.10f, top + actualHeight * 0.745f,
            rect.right - frameWidth * 0.10f, rect.bottom)
        path.lineTo(rect.left + frameWidth * 0.10f, rect.bottom)
        path.cubicTo(rect.left + frameWidth * 0.10f, top + actualHeight * 0.745f,
            headCx - headW * 0.60f, neckY + actualHeight * 0.035f,
            headCx - neckW, neckY + actualHeight * 0.012f)
        path.cubicTo(headCx - neckW * 1.15f, neckY, chinL, headBottom + headH * 0.10f, chinL, headBottom)
        path.close()

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(26, 255, 255, 255)
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5.5f
        paint.color = guideColor
        canvas.drawPath(path, paint)

        // Inner dashed face ellipse for precise alignment.
        val face = RectF(headCx - headW * 0.44f, headTop + headH * 0.04f,
            headCx + headW * 0.44f, headBottom - headH * 0.06f)
        paint.strokeWidth = 3.5f
        paint.color = Color.argb(150, 255, 255, 255)
        paint.pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
        canvas.drawOval(face, paint)
        paint.pathEffect = null

        paint.strokeWidth = 2.5f
        paint.color = Color.argb(135, 255, 255, 255)
        canvas.drawLine(headCx, rect.top + 18f, headCx, rect.bottom - 18f, paint)
        canvas.drawLine(face.left, face.top + face.height() * 0.45f,
            face.right, face.top + face.height() * 0.45f, paint)
        canvas.drawLine(rect.left + frameWidth * 0.13f, neckY + actualHeight * 0.02f,
            rect.right - frameWidth * 0.13f, neckY + actualHeight * 0.02f, paint)

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
