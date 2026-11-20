package cn.quickidphoto.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.google.android.material.button.MaterialButton

val Ink = Color.rgb(15, 23, 42)
val Muted = Color.rgb(100, 116, 139)
val Surface = Color.rgb(248, 250, 252)
val Primary = Color.rgb(79, 102, 255)
val PrimaryDeep = Color.rgb(124, 92, 255)
val Accent = Color.rgb(20, 184, 166)

// 高对比度文字色：确保与按钮底色对比度明显
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

/** 立体按钮背景：顶部高光 + 底部深色营造 3D 厚度，按下后整体变暗。 */
private fun Context.threeDBackground(
    topColor: Int, bottomColor: Int,
    pressedTop: Int, pressedBottom: Int,
    radius: Int, strokeColor: Int? = null
): StateListDrawable {
    fun layer(top: Int, bottom: Int) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)
    ).apply {
        cornerRadius = radius.toFloat()
        strokeColor?.let { setStroke(dp(1), it) }
    }
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), layer(pressedTop, pressedBottom))
        addState(intArrayOf(-android.R.attr.state_enabled), layer(pressedTop, pressedBottom))
        addState(intArrayOf(), layer(topColor, bottomColor))
    }
}

/** 按下时按钮下沉并降低阴影，松开后回弹，增强立体按压感。 */
private fun View.attachPressDepth(enabledElevation: Float) {
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    view.translationY = context.dp(2).toFloat()
                    view.elevation = context.dp(1).toFloat()
                }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.translationY = 0f
                view.elevation = enabledElevation
            }
        }
        false
    }
}

private fun Context.baseButton(text: String, onClick: (View) -> Unit): MaterialButton =
    MaterialButton(this).apply {
        this.text = text
        textSize = 14f
        letterSpacing = 0.01f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAllCaps = false
        minWidth = 0
        minHeight = 0
        insetTop = 0
        insetBottom = 0
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener(onClick)
    }

fun Context.action(text: String, onClick: (View) -> Unit = {}): MaterialButton =
    baseButton(text, onClick).apply {
        background = threeDBackground(
            Color.WHITE, Color.rgb(226, 232, 245),
            Color.rgb(226, 232, 245), Color.rgb(203, 213, 235),
            dp(18), Color.rgb(203, 213, 235)
        )
        setTextColor(OnLightText)
        stateListAnimator = null
        elevation = dp(4).toFloat()
        attachPressDepth(dp(4).toFloat())
    }

fun Context.primaryAction(text: String, onClick: (View) -> Unit = {}): MaterialButton =
    baseButton(text, onClick).apply {
        background = threeDBackground(
            Color.rgb(111, 132, 255), Color.rgb(64, 78, 226),
            Color.rgb(64, 78, 226), Color.rgb(43, 55, 178),
            dp(20)
        )
        setTextColor(OnPrimaryText)
        stateListAnimator = null
        elevation = dp(6).toFloat()
        attachPressDepth(dp(6).toFloat())
    }

fun Context.glassAction(text: String, onClick: (View) -> Unit = {}): MaterialButton =
    baseButton(text, onClick).apply {
        background = threeDBackground(
            Color.argb(96, 255, 255, 255), Color.argb(60, 255, 255, 255),
            Color.argb(60, 255, 255, 255), Color.argb(40, 255, 255, 255),
            dp(20), Color.argb(120, 255, 255, 255)
        )
        setTextColor(Color.WHITE)
        stateListAnimator = null
        elevation = dp(3).toFloat()
        attachPressDepth(dp(3).toFloat())
    }

/** 统一设置 Chip 的选中/未选中立体样式，供编辑器页签等需要动态切换的地方调用。 */
fun MaterialButton.applySelectedStyle(selected: Boolean) {
    val radius = context.dp(22)
    if (selected) {
        background = context.threeDBackground(
            Color.rgb(111, 132, 255), Color.rgb(64, 78, 226),
            Color.rgb(64, 78, 226), Color.rgb(43, 55, 178), radius
        )
        setTextColor(Color.WHITE)
        stateListAnimator = null
        elevation = context.dp(5).toFloat()
    } else {
        background = context.threeDBackground(
            Color.WHITE, Color.rgb(235, 239, 249),
            Color.rgb(226, 232, 245), Color.rgb(210, 218, 238), radius,
            Color.rgb(215, 222, 240)
        )
        setTextColor(OnLightMuted)
        stateListAnimator = null
        elevation = context.dp(2).toFloat()
    }
}

/** Selected/unselected pill chip used for presets and option groups. */
fun Context.chip(text: String, selected: Boolean, onClick: (View) -> Unit = {}): MaterialButton =
    baseButton(text, onClick).apply { applySelectedStyle(selected) }

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
