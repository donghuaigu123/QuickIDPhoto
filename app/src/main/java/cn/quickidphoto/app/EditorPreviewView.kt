package cn.quickidphoto.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.min

class EditorPreviewView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
        set(value) { field = value; invalidate() }
    var showGrid = false
        set(value) { field = value; invalidate() }
    var onGesture: ((dx: Float, dy: Float, scale: Float) -> Unit)? = null

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255)
        strokeWidth = 2f
    }
    private var lastX = 0f
    private var lastY = 0f
    private var scaleFactor = 1f
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                return true
            }
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                onGesture?.invoke(0f, 0f, scaleFactor)
                scaleFactor = 1f
            }
        })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress && event.pointerCount == 1) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y }
                MotionEvent.ACTION_MOVE -> {
                    onGesture?.invoke((event.x - lastX) / width, (event.y - lastY) / height, 1f)
                    lastX = event.x; lastY = event.y
                }
            }
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(27, 31, 43))
        val image = bitmap ?: return
        val availableWidth = width - 48f
        val availableHeight = height - 48f
        val scale = min(availableWidth / image.width, availableHeight / image.height)
        val w = image.width * scale
        val h = image.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        val rect = RectF(left, top, left + w, top + h)
        if (image.hasAlpha()) {
            val tile = 18f
            val checker = Paint().apply { color = Color.rgb(210, 213, 220) }
            var y = rect.top
            var row = 0
            while (y < rect.bottom) {
                var x = rect.left
                var column = 0
                while (x < rect.right) {
                    if ((row + column) % 2 == 0) {
                        canvas.drawRect(x, y, minOf(x + tile, rect.right), minOf(y + tile, rect.bottom), checker)
                    }
                    x += tile
                    column++
                }
                y += tile
                row++
            }
        }
        canvas.drawBitmap(image, null, rect, imagePaint)
        if (showGrid) {
            for (i in 1 until 3) {
                canvas.drawLine(left + w * i / 3f, top, left + w * i / 3f, top + h, linePaint)
                canvas.drawLine(left, top + h * i / 3f, left + w, top + h * i / 3f, linePaint)
            }
        }
        linePaint.style = Paint.Style.STROKE
        linePaint.pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        canvas.drawRect(rect, linePaint)
        linePaint.pathEffect = null
        linePaint.style = Paint.Style.FILL
    }
}
