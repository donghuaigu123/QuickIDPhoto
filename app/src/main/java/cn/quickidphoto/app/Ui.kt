package cn.quickidphoto.app

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

val Ink = Color.rgb(15, 23, 42)
val Muted = Color.rgb(100, 116, 139)
val Surface = Color.rgb(248, 250, 252)
val Primary = Color.rgb(79, 102, 255)
val PrimaryDeep = Color.rgb(124, 92, 255)
val Accent = Color.rgb(20, 184, 166)

private val OnPrimaryText = Color.WHITE
private val OnLightText = Color.rgb(30, 41, 82)
private val OnLightMuted = Color.rgb(71, 85, 105)

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.label(text: String, size: Float = 15f, color: Int = Ink): TextView =
    TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        setLineSpacing(0f, 1.18f)
    }

fun Context.title(text: String): TextView = label(text, 30f, Ink).apply {
    setTypeface(typeface, Typeface.BOLD)
    letterSpacing = -0.02f
    setPadding(0, dp(8), 0, dp(8))
}

/**
 * 全应用唯一按钮实现：平台 Button + 自定义 StateListDrawable。
 * 不使用 MaterialButton，避免其 backgroundTint / ripple 覆盖立体背景。
 */
class ThreeDButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : Button(context, attrs, android.R.attr.buttonStyle) {
    private var restingElevation = 0f

    init {
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.01f
        minWidth = 0
        minHeight = 0
        setPadding(context.dp(12), 0, context.dp(12), 0)
        stateListAnimator = null
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.translationY = context.dp(2).toFloat()
                    view.elevation = context.dp(1).toFloat()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.translationY = 0f
                    view.elevation = restingElevation
                }
            }
            false
        }
    }

    fun style(
        top: Int, bottom: Int, pressedTop: Int, pressedBottom: Int,
        radiusDp: Int, textColor: Int, strokeColor: Int? = null, elevationDp: Int
    ) {
        val radius = context.dp(radiusDp).toFloat()
        fun layer(t: Int, b: Int) = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t, b)
        ).apply {
            cornerRadius = radius
            strokeColor?.let { setStroke(context.dp(1), it) }
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), layer(pressedTop, pressedBottom))
            addState(intArrayOf(-android.R.attr.state_enabled), layer(pressedTop, pressedBottom))
            addState(intArrayOf(), layer(top, bottom))
        }
        setTextColor(textColor)
        restingElevation = context.dp(elevationDp).toFloat()
        elevation = restingElevation
    }
}

private fun Context.baseButton(text: String, onClick: (View) -> Unit): ThreeDButton =
    ThreeDButton(this).apply {
        this.text = text
        setOnClickListener(onClick)
    }

fun Context.action(text: String, onClick: (View) -> Unit = {}): ThreeDButton =
    baseButton(text, onClick).apply {
        style(Color.WHITE, Color.rgb(226, 232, 245),
            Color.rgb(226, 232, 245), Color.rgb(203, 213, 235),
            18, OnLightText, Color.rgb(203, 213, 235), 4)
    }

fun Context.primaryAction(text: String, onClick: (View) -> Unit = {}): ThreeDButton =
    baseButton(text, onClick).apply {
        style(Color.rgb(111, 132, 255), Color.rgb(64, 78, 226),
            Color.rgb(64, 78, 226), Color.rgb(43, 55, 178),
            20, OnPrimaryText, null, 6)
    }

/** 选中/未选中 Chip，与页面按钮共享同一套立体样式。 */
fun ThreeDButton.applySelectedStyle(selected: Boolean) {
    if (selected) {
        style(Color.rgb(111, 132, 255), Color.rgb(64, 78, 226),
            Color.rgb(64, 78, 226), Color.rgb(43, 55, 178),
            22, Color.WHITE, null, 5)
    } else {
        style(Color.WHITE, Color.rgb(235, 239, 249),
            Color.rgb(226, 232, 245), Color.rgb(210, 218, 238),
            22, OnLightMuted, Color.rgb(215, 222, 240), 2)
    }
}

fun Context.chip(text: String, selected: Boolean, onClick: (View) -> Unit = {}): ThreeDButton =
    baseButton(text, onClick).apply { applySelectedStyle(selected) }

/** 弹窗按钮也使用同一套立体样式，避免系统默认扁平按钮显得不统一。 */
fun Context.threeDDialog(): AlertDialog.Builder =
    AlertDialog.Builder(ContextThemeWrapper(this, R.style.QuickIDDialog))

fun Context.sectionTitle(text: String): TextView = label(text, 13f, Muted).apply {
    typeface = Typeface.create(typeface, Typeface.BOLD)
    letterSpacing = 0.08f
    setPadding(dp(2), dp(14), 0, dp(8))
}

fun Context.modernSlider(seekBar: SeekBar) {
    seekBar.progressTintList = ColorStateList.valueOf(Primary)
    seekBar.thumbTintList = ColorStateList.valueOf(PrimaryDeep)
    seekBar.progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(226, 232, 240))
}

fun Context.modernSwitch(switch: Switch) {
    switch.thumbTintList = ColorStateList.valueOf(Color.WHITE)
    switch.trackTintList = ColorStateList.valueOf(Color.rgb(165, 180, 252))
}

fun vertical(context: Context): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
}

fun horizontal(context: Context): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}
