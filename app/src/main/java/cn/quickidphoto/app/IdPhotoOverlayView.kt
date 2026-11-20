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

/**
 * 拍摄引导层：按标准证件照构图绘制灰色线框人形轮廓（头/耳/颈/肩），
 * 并以白色虚线标出头顶参考线、眼线与两侧竖线，样式对照用户提供的参考图。
 */
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

        // 取景框外压暗，突出线框引导。
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(110, 5, 8, 22)
        canvas.drawRect(0f, 0f, width.toFloat(), rect.top, paint)
        canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), paint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, paint)
        canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, paint)

        // 取景框细边框
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.argb(120, 255, 255, 255)
        paint.pathEffect = null
        canvas.drawRect(rect, paint)

        // ---- 人形轮廓（参考图样式：灰色线框，含耳廓、颈部与双肩） ----
        val cx = width / 2f
        val headH = actualHeight * 0.50f
        val headW = frameWidth * 0.56f
        val headTop = top + actualHeight * 0.105f
        val chinY = headTop + headH * 0.88f
        val shoulderY = headTop + headH * 1.12f

        // 头部右侧（ temple → 耳廓 → 下颌 → 下巴 ），左侧对称镜像
        val templeX = cx + headW * 0.30f      // 太阳穴
        val earOutX = cx + headW * 0.50f      // 耳廓外缘
        val earTopY = headTop + headH * 0.38f
        val earBottomY = headTop + headH * 0.56f
        val jawX = cx + headW * 0.24f         // 下颌角
        val jawY = headTop + headH * 0.74f
        val chinHalf = headW * 0.15f
        val neckHalf = headW * 0.16f
        val neckY = shoulderY + actualHeight * 0.02f
        val shoulderEndX = cx + frameWidth * 0.62f

        path.reset()
        path.moveTo(cx, headTop)
        // 右侧头顶到太阳穴
        path.cubicTo(cx + headW * 0.26f, headTop, templeX, headTop + headH * 0.10f, templeX, earTopY)
        // 耳廓外凸
        path.cubicTo(earOutX, earTopY + headH * 0.02f, earOutX, earBottomY - headH * 0.02f,
            cx + headW * 0.36f, earBottomY)
        // 下颌内收
        path.cubicTo(jawX + headW * 0.06f, jawY + headH * 0.06f, jawX, jawY + headH * 0.08f,
            chinHalf + headW * 0.03f, chinY)
        path.cubicTo(cx + headW * 0.10f, chinY + headH * 0.035f, cx + neckHalf, chinY + headH * 0.05f,
            cx + neckHalf, neckY)
        // 右肩外扩至画幅底部
        path.cubicTo(cx + neckHalf * 1.4f, neckY + actualHeight * 0.02f,
            shoulderEndX, shoulderY + actualHeight * 0.05f, shoulderEndX, rect.bottom)
        path.lineTo(2 * cx - shoulderEndX, rect.bottom)
        // 左肩
        path.cubicTo(2 * cx - shoulderEndX, shoulderY + actualHeight * 0.05f,
            cx - neckHalf * 1.4f, neckY + actualHeight * 0.02f, cx - neckHalf, neckY)
        // 左侧颈部与下颌
        path.cubicTo(cx - neckHalf, chinY + headH * 0.05f, cx - headW * 0.10f, chinY + headH * 0.035f,
            -(chinHalf + headW * 0.03f) + 2 * cx, chinY)
        path.cubicTo(-(jawX) + 2 * cx, jawY + headH * 0.08f, -(jawX + headW * 0.06f) + 2 * cx,
            jawY + headH * 0.06f, -(cx + headW * 0.36f) + 2 * cx, earBottomY)
        // 左耳廓
        path.cubicTo(-earOutX + 2 * cx, earBottomY - headH * 0.02f, -earOutX + 2 * cx,
            earTopY + headH * 0.02f, -templeX + 2 * cx, earTopY)
        // 左侧头顶回顶
        path.cubicTo(-templeX + 2 * cx, headTop + headH * 0.10f, cx - headW * 0.26f, headTop, cx, headTop)
        path.close()

        // 轮廓填充 + 灰色描边（参考图为半透明深填充 + 浅灰线框）
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(36, 10, 14, 30)
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4.5f
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = when (framingState) {
            1 -> Color.rgb(140, 235, 200)
            2 -> Color.rgb(235, 200, 130)
            else -> Color.rgb(178, 190, 200)
        }
        canvas.drawPath(path, paint)
        paint.strokeJoin = Paint.Join.MITER

        // ---- 白色虚线参考线（参考图：头顶线 + 眼线 + 两侧竖线） ----
        val dash = DashPathEffect(floatArrayOf(16f, 12f), 0f)
        paint.color = Color.argb(225, 255, 255, 255)
        paint.strokeWidth = 3.5f
        paint.pathEffect = dash

        val topGuideY = headTop - actualHeight * 0.030f
        val eyeGuideY = earTopY + headH * 0.02f
        canvas.drawLine(rect.left, topGuideY, rect.right, topGuideY, paint)
        canvas.drawLine(rect.left, eyeGuideY, rect.right, eyeGuideY, paint)
        val verticalX = frameWidth * 0.355f
        canvas.drawLine(cx - verticalX, topGuideY, cx - verticalX, rect.bottom, paint)
        canvas.drawLine(cx + verticalX, topGuideY, cx + verticalX, rect.bottom, paint)

        // 三分构图辅助（可选）
        if (gridVisible) {
            paint.color = Color.argb(85, 255, 255, 255)
            paint.strokeWidth = 2f
            paint.pathEffect = null
            for (i in 1 until 3) {
                canvas.drawLine(left + frameWidth * i / 3f, top, left + frameWidth * i / 3f, top + actualHeight, paint)
                canvas.drawLine(left, top + actualHeight * i / 3f, left + frameWidth, top + actualHeight * i / 3f, paint)
            }
        }
        paint.pathEffect = null

        paint.style = Paint.Style.FILL
        paint.textSize = 32f
        paint.color = Color.argb(220, 255, 255, 255)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("头顶对齐上方虚线 · 双眼对齐中间虚线", width / 2f, rect.bottom + 48f, paint)
    }
}
