package cn.quickidphoto.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.label(text: String, size: Float = 15f, color: Int = Color.rgb(35, 40, 55)): TextView =
    TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
    }

fun Context.title(text: String): TextView = label(text, 23f).apply {
    setTypeface(typeface, Typeface.BOLD)
    setPadding(0, contextDp(8), 0, contextDp(8))
}

private fun Context.contextDp(value: Int) = dp(value)

fun Context.action(text: String, onClick: (View) -> Unit = {}): MaterialButton =
    MaterialButton(this).apply {
        this.text = text
        setOnClickListener(onClick)
        insetTop = 0
        insetBottom = 0
    }

fun vertical(context: Context): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
}

fun horizontal(context: Context): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}
