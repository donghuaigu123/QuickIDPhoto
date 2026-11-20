package cn.quickidphoto.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Touch-based visual HSV color picker with live preview, saturation/value area and hue rail. */
class VisualColorPickerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    var selectedColor: Int = Color.rgb(67, 142, 219)
        private set
    var onColorChanged: ((Int) -> Unit)? = null

    private val hsv = FloatArray(3)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private var svRect = RectF()
    private var hueRect = RectF()
    private var previewRect = RectF()

    init {
        setColor(selectedColor)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setColor(color: Int) {
        Color.colorToHSV(color, hsv)
        selectedColor = color
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = 12f
        previewRect = RectF(pad, pad, w - pad, 76f)
        svRect = RectF(pad, 92f, w - pad, h - 76f)
        hueRect = RectF(pad, h - 54f, w - pad, h - pad)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 30f, 30f, paint)

        paint.color = selectedColor
        canvas.drawRoundRect(previewRect, 22f, 22f, paint)
        paint.color = Color.argb(60, 0, 0, 0)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRoundRect(previewRect, 22f, 22f, paint)
        paint.style = Paint.Style.FILL

        val hueColor = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        val saturationShader = LinearGradient(svRect.left, svRect.top, svRect.right, svRect.top,
            Color.WHITE, hueColor, Shader.TileMode.CLAMP)
        val valueShader = LinearGradient(svRect.left, svRect.top, svRect.left, svRect.bottom,
            Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        paint.shader = ComposeShader(valueShader, saturationShader, PorterDuff.Mode.MULTIPLY)
        canvas.drawRoundRect(svRect, 20f, 20f, paint)
        paint.shader = null
        paint.color = Color.argb(50, 15, 23, 42)
        paint.style = Paint.Style.STROKE
        canvas.drawRoundRect(svRect, 20f, 20f, paint)
        paint.style = Paint.Style.FILL

        val hueColors = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        paint.shader = LinearGradient(hueRect.left, hueRect.top, hueRect.right, hueRect.top,
            hueColors, null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(hueRect, 20f, 20f, paint)
        paint.shader = null

        val svX = svRect.left + svRect.width() * hsv[1]
        val svY = svRect.top + svRect.height() * (1f - hsv[2])
        markerPaint.color = Color.WHITE
        canvas.drawCircle(svX, svY, 15f, markerPaint)
        paint.color = selectedColor
        canvas.drawCircle(svX, svY, 10f, paint)
        val hueX = hueRect.left + hueRect.width() * (hsv[0] / 360f)
        markerPaint.color = Color.WHITE
        canvas.drawCircle(hueX, hueRect.centerY(), 17f, markerPaint)
        paint.color = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        canvas.drawCircle(hueX, hueRect.centerY(), 11f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (hueRect.contains(event.x, event.y)) {
                    hsv[0] = ((event.x - hueRect.left) / hueRect.width()).coerceIn(0f, 1f) * 359.999f
                } else if (event.y >= svRect.top) {
                    hsv[1] = ((event.x - svRect.left) / svRect.width()).coerceIn(0f, 1f)
                    hsv[2] = (1f - (event.y - svRect.top) / svRect.height()).coerceIn(0f, 1f)
                }
                selectedColor = Color.HSVToColor(hsv)
                onColorChanged?.invoke(selectedColor)
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
