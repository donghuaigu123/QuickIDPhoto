package cn.quickidphoto.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * 拍摄引导层：按参考截图 100% 复刻。
 * - 黑色区域由相机预览提供，本层不绘制任何填充或取景框
 * - 灰色细线人形轮廓：头顶 → 太阳穴 → 耳廓 → 下颌 → 颈部 → 双肩
 * - 白色虚线：顶部横线、眼线横线、两侧竖线
 * 所有坐标均为参考图（665×1182）实测比例。
 */
class IdPhotoOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    /** 保留接口兼容；参考图样式不使用颜色状态或额外网格。 */
    var framingState: Int = 0
        set(value) { field = value }

    /** 保留旧调用接口；参考图轮廓按画面比例自适应，不再使用规格比例。 */
    var aspect: Float = 295f / 413f
        set(value) { field = value }

    /** 白色虚线参考线是否显示（相机页“网格”按钮）。 */
    var gridVisible: Boolean = true
        set(value) { field = value; invalidate() }

    fun toggleGrid() {
        gridVisible = !gridVisible
        invalidate()
    }

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(122, 122, 122)
        strokeWidth = 2.2f * context.resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2.4f * context.resources.displayMetrics.density
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // ---- 白色虚线参考线（参考图：顶部横线 y=7.2%、眼线 y=19.5%，竖线 x=15.5%/83.9%，止于 65.5%） ----
        if (gridVisible) {
            val dash = DashPathEffect(
                floatArrayOf(12f * context.resources.displayMetrics.density,
                    9f * context.resources.displayMetrics.density), 0f
            )
            dashPaint.pathEffect = dash
            val topLine = h * 0.072f
            val eyeLine = h * 0.195f
            val leftLine = w * 0.155f
            val rightLine = w * 0.839f
            val bottom = h * 0.655f
            canvas.drawLine(0f, topLine, w, topLine, dashPaint)
            canvas.drawLine(0f, eyeLine, w, eyeLine, dashPaint)
            canvas.drawLine(leftLine, topLine, leftLine, bottom, dashPaint)
            canvas.drawLine(rightLine, topLine, rightLine, bottom, dashPaint)
            dashPaint.pathEffect = null
        }

        // ---- 人形轮廓：完全按参考图像素比例绘制，不填充、不加任何装饰 ----
        path.reset()
        path.moveTo(0f, h * 0.664f)
        path.lineTo(w * 0.338f, h * 0.592f)  // 左肩内侧
        path.lineTo(w * 0.338f, h * 0.515f)  // 左颈
        path.cubicTo(w * 0.338f, h * 0.470f, w * 0.27f, h * 0.440f, w * 0.21f, h * 0.393f)
        path.cubicTo(w * 0.175f, h * 0.383f, w * 0.175f, h * 0.338f, w * 0.21f, h * 0.326f) // 左耳
        path.cubicTo(w * 0.21f, h * 0.20f, w * 0.36f, h * 0.097f, w * 0.50f, h * 0.097f) // 头顶
        path.cubicTo(w * 0.64f, h * 0.097f, w * 0.79f, h * 0.20f, w * 0.79f, h * 0.326f)
        path.cubicTo(w * 0.825f, h * 0.338f, w * 0.825f, h * 0.383f, w * 0.79f, h * 0.393f) // 右耳
        path.cubicTo(w * 0.73f, h * 0.440f, w * 0.662f, h * 0.470f, w * 0.662f, h * 0.515f)
        path.lineTo(w * 0.662f, h * 0.592f)  // 右颈
        path.lineTo(w, h * 0.664f)            // 右肩延伸至画面边缘
        canvas.drawPath(path, outlinePaint)
    }
}
