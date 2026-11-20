package cn.quickidphoto.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
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

private fun pill(colors: IntArray, radius: Int, strokeColor: Int = Color.TRANSPARENT, strokeWidth: Int = 0) =
    GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
        cornerRadius = radius.toFloat()
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

private fun Context.baseButton(text: String, onClick: (View) -> Unit): MaterialButton =
    MaterialButton(this).apply {
        this.text = text
        textSize = 13.5f
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
        background = pill(intArrayOf(Color.WHITE, Color.rgb(247, 249, 255)), dp(18),
            Color.rgb(222, 228, 242), dp(1))
        setTextColor(Primary)
        elevation = dp(1).toFloat()
    }

fun Context.primaryAction(text: String, onClick: (View) -> Unit = {}): MaterialButton =
    baseButton(text, onClick).apply {
        background = pill(intArrayOf(Primary, PrimaryDeep), dp(20))
        setTextColor(Color.WHITE)
        elevation = dp(5).toFloat()
    }

fun Context.glassAction(text: String, onClick: (View) -> Unit = {}): MaterialButton =
    baseButton(text, onClick).apply {
        background = pill(intArrayOf(Color.argb(82, 255, 255, 255), Color.argb(52, 255, 255, 255)),
            dp(20), Color.argb(90, 255, 255, 255), dp(1))
        setTextColor(Color.WHITE)
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
