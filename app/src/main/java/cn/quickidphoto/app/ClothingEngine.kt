package cn.quickidphoto.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import kotlin.math.roundToInt

/**
 * 智能换装：程序生成正装图层（西装 / 衬衫 / 制服），
 * 依据人脸位置自动对齐到肩胸部并与原图按人像蒙版融合。
 * 灵感来自 HivisionIDPhotos / image-matting 的证件照换装管线，素材完全本地生成。
 */
object ClothingEngine {

    data class Style(val name: String, val kind: Int, val defaultColor: Int)

    const val KIND_NONE = 0
    const val KIND_SUIT = 1
    const val KIND_SHIRT = 2
    const val KIND_UNIFORM = 3

    val styles = listOf(
        Style("不换装", KIND_NONE, Color.TRANSPARENT),
        Style("黑色西装", KIND_SUIT, Color.rgb(35, 38, 48)),
        Style("藏青西装", KIND_SUIT, Color.rgb(38, 52, 84)),
        Style("深灰西装", KIND_SUIT, Color.rgb(72, 78, 90)),
        Style("白色衬衫", KIND_SHIRT, Color.rgb(244, 246, 250)),
        Style("商务蓝衬衫", KIND_SHIRT, Color.rgb(126, 164, 218)),
        Style("藏青制服", KIND_UNIFORM, Color.rgb(46, 58, 92))
    )

    private fun lighten(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) + (255 - Color.red(color)) * factor).roundToInt().coerceIn(0, 255),
        (Color.green(color) + (255 - Color.green(color)) * factor).roundToInt().coerceIn(0, 255),
        (Color.blue(color) + (255 - Color.blue(color)) * factor).roundToInt().coerceIn(0, 255)
    )

    private fun darken(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * (1f - factor)).roundToInt().coerceIn(0, 255),
        (Color.green(color) * (1f - factor)).roundToInt().coerceIn(0, 255),
        (Color.blue(color) * (1f - factor)).roundToInt().coerceIn(0, 255)
    )

    /** 生成一件正装的透明底图层， neckY 处为领口开口中心。 */
    fun renderLayer(styleIndex: Int, color: Int, width: Int, height: Int): Bitmap {
        val style = styles[styleIndex.coerceIn(0, styles.lastIndex)]
        val layer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (style.kind == KIND_NONE || width < 8 || height < 8) return layer
        val canvas = Canvas(layer)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path()

        val base = if (style.kind == KIND_SHIRT) color else color
        val shade = darken(base, 0.22f)
        val deep = darken(base, 0.42f)

        // 衣身：肩部圆润的外套轮廓
        path.moveTo(cx - w * 0.06f, h * 0.04f)
        path.cubicTo(cx - w * 0.30f, h * 0.05f, -w * 0.06f, h * 0.10f, -w * 0.04f, h * 0.24f)
        path.lineTo(-w * 0.05f, h)
        path.lineTo(w * 1.05f, h)
        path.lineTo(w * 1.04f, h * 0.24f)
        path.cubicTo(w * 1.06f, h * 0.10f, cx + w * 0.30f, h * 0.05f, cx + w * 0.06f, h * 0.04f)
        path.close()
        paint.shader = LinearGradient(0f, 0f, w * 0.4f, h,
            intArrayOf(lighten(base, 0.16f), base, shade),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(path, paint)
        paint.shader = null

        // 布料褶皱：两侧浅色高光 + 深色阴影线
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.012f
        paint.color = Color.argb(46, 255, 255, 255)
        canvas.drawLine(w * 0.16f, h * 0.30f, w * 0.20f, h * 0.96f, paint)
        canvas.drawLine(w * 0.84f, h * 0.30f, w * 0.80f, h * 0.96f, paint)
        paint.color = Color.argb(40, 0, 0, 0)
        canvas.drawLine(w * 0.34f, h * 0.34f, w * 0.30f, h * 0.95f, paint)
        canvas.drawLine(w * 0.66f, h * 0.34f, w * 0.70f, h * 0.95f, paint)
        paint.style = Paint.Style.FILL

        if (style.kind == KIND_SUIT) {
            // 衬衫 V 区
            paint.color = Color.rgb(246, 248, 252)
            path.reset()
            path.moveTo(cx - w * 0.115f, h * 0.055f)
            path.lineTo(cx + w * 0.115f, h * 0.055f)
            path.lineTo(cx + w * 0.02f, h * 0.52f)
            path.lineTo(cx - w * 0.02f, h * 0.52f)
            path.close()
            canvas.drawPath(path, paint)
            // 领带
            paint.color = Color.rgb(52, 58, 82)
            path.reset()
            path.moveTo(cx - w * 0.045f, h * 0.07f)
            path.lineTo(cx + w * 0.045f, h * 0.07f)
            path.lineTo(cx + w * 0.028f, h * 0.47f)
            path.lineTo(cx - w * 0.028f, h * 0.47f)
            path.close()
            canvas.drawPath(path, paint)
            // 西装翻领（左右两片）
            paint.color = lighten(base, 0.06f)
            path.reset()
            path.moveTo(cx - w * 0.055f, h * 0.05f)
            path.lineTo(-w * 0.02f, h * 0.115f)
            path.lineTo(w * 0.30f, h * 0.66f)
            path.lineTo(w * 0.40f, h * 0.52f)
            path.lineTo(cx - w * 0.015f, h * 0.10f)
            path.close()
            canvas.drawPath(path, paint)
            path.reset()
            path.moveTo(cx + w * 0.055f, h * 0.05f)
            path.lineTo(w * 1.02f, h * 0.115f)
            path.lineTo(w * 0.70f, h * 0.66f)
            path.lineTo(w * 0.60f, h * 0.52f)
            path.lineTo(cx + w * 0.015f, h * 0.10f)
            path.close()
            canvas.drawPath(path, paint)
            // 衬衫领口小三角
            paint.color = Color.rgb(250, 251, 253)
            path.reset()
            path.moveTo(cx - w * 0.115f, h * 0.055f)
            path.lineTo(cx - w * 0.01f, h * 0.085f)
            path.lineTo(cx - w * 0.085f, h * 0.14f)
            path.close()
            canvas.drawPath(path, paint)
            path.reset()
            path.moveTo(cx + w * 0.115f, h * 0.055f)
            path.lineTo(cx + w * 0.01f, h * 0.085f)
            path.lineTo(cx + w * 0.085f, h * 0.14f)
            path.close()
            canvas.drawPath(path, paint)
        } else if (style.kind == KIND_SHIRT) {
            // 开领 V 口
            paint.color = darken(base, 0.18f)
            path.reset()
            path.moveTo(cx - w * 0.12f, h * 0.055f)
            path.lineTo(cx + w * 0.12f, h * 0.055f)
            path.lineTo(cx + w * 0.015f, h * 0.34f)
            path.lineTo(cx - w * 0.015f, h * 0.34f)
            path.close()
            canvas.drawPath(path, paint)
            // 领片
            paint.color = lighten(base, 0.10f)
            path.reset()
            path.moveTo(cx - w * 0.13f, h * 0.05f)
            path.lineTo(cx - w * 0.01f, h * 0.09f)
            path.lineTo(cx - w * 0.09f, h * 0.17f)
            path.close()
            canvas.drawPath(path, paint)
            path.reset()
            path.moveTo(cx + w * 0.13f, h * 0.05f)
            path.lineTo(cx + w * 0.01f, h * 0.09f)
            path.lineTo(cx + w * 0.09f, h * 0.17f)
            path.close()
            canvas.drawPath(path, paint)
            // 门襟与纽扣
            paint.color = darken(base, 0.12f)
            canvas.drawRect(cx - w * 0.008f, h * 0.30f, cx + w * 0.008f, h, paint)
            paint.color = lighten(base, 0.28f)
            for (i in 0 until 4) {
                val by = h * (0.40f + i * 0.15f)
                canvas.drawCircle(cx, by, w * 0.014f, paint)
            }
        } else {
            // 制服外套：立领 + 深色门襟 + 三粒扣
            paint.color = deep
            path.reset()
            path.moveTo(cx - w * 0.10f, h * 0.05f)
            path.lineTo(cx + w * 0.10f, h * 0.05f)
            path.lineTo(cx + w * 0.13f, h * 0.30f)
            path.lineTo(cx - w * 0.13f, h * 0.30f)
            path.close()
            canvas.drawPath(path, paint)
            // 立领翻片
            paint.color = lighten(base, 0.10f)
            path.reset()
            path.moveTo(cx - w * 0.105f, h * 0.05f)
            path.lineTo(cx - w * 0.02f, h * 0.075f)
            path.lineTo(cx - w * 0.10f, h * 0.20f)
            path.close()
            canvas.drawPath(path, paint)
            path.reset()
            path.moveTo(cx + w * 0.105f, h * 0.05f)
            path.lineTo(cx + w * 0.02f, h * 0.075f)
            path.lineTo(cx + w * 0.10f, h * 0.20f)
            path.close()
            canvas.drawPath(path, paint)
            // 门襟
            paint.color = shade
            canvas.drawRect(cx - w * 0.055f, h * 0.28f, cx + w * 0.055f, h, paint)
            paint.color = lighten(base, 0.30f)
            for (i in 0 until 3) {
                canvas.drawCircle(cx, h * (0.38f + i * 0.18f), w * 0.016f, paint)
            }
        }
        return layer
    }

    /**
     * 将正装图层贴合到人物图上：
     * 领口对齐下巴下方，宽度按脸宽自适应，仅覆盖下巴以下区域，
     * 并用人像蒙版控制融合强度，避免盖住头发和背景。
     */
    fun applyClothing(
        person: Bitmap, matte: FloatArray, face: FaceBox, settings: EditorSettings
    ): Bitmap {
        if (settings.clothingStyle <= 0) return person
        val chinY = face.bottom - face.height * 0.10f
        val layerW = (face.width * 3.8f).roundToInt().coerceAtLeast(16)
        val layerH = (layerW * 1.45f).roundToInt()
        val layer = renderLayer(settings.clothingStyle, settings.clothingColor, layerW, layerH)
        val layerPixels = IntArray(layerW * layerH)
        layer.getPixels(layerPixels, 0, layerW, 0, 0, layerW, layerH)

        val w = person.width
        val h = person.height
        // 服装图层通常比裁剪画面更宽，必须做交集裁剪后再采样，
        // 否则 getPixels 的负坐标会直接抛异常导致“换装不能用”。
        val desiredLeft = (face.centerX - layerW / 2f).roundToInt()
        val desiredTop = (chinY - layerH * 0.02f).roundToInt()
        val x0 = maxOf(0, desiredLeft)
        val y0 = maxOf(0, desiredTop)
        val x1 = minOf(w, desiredLeft + layerW)
        val y1 = minOf(h, desiredTop + layerH)
        val regionW = x1 - x0
        val regionH = y1 - y0
        if (regionW <= 0 || regionH <= 0) return person.copy(Bitmap.Config.ARGB_8888, true)

        val result = person.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(regionW * regionH)
        result.getPixels(pixels, 0, regionW, x0, y0, regionW, regionH)
        for (y in 0 until regionH) {
            val py = y0 + y
            if (py < chinY) continue
            val layerY = y + (y0 - desiredTop)
            if (layerY !in 0 until layerH) continue
            for (x in 0 until regionW) {
                val px = x0 + x
                val layerX = x + (x0 - desiredLeft)
                if (layerX !in 0 until layerW) continue
                val lp = layerPixels[layerY * layerW + layerX]
                val la = Color.alpha(lp)
                if (la == 0) continue
                val matteAlpha = matte.getOrElse(py * w + px) { 0f }.coerceIn(0f, 1f)
                // 人体区域(蒙版高)完全覆盖；蒙版低的边缘按原比例渐变，领口处额外保留过渡
                val coverage = (0.30f + 0.70f * matteAlpha).coerceIn(0f, 1f)
                val a = la / 255f * coverage
                if (a <= 0.01f) continue
                val index = y * regionW + x
                val p = pixels[index]
                val r = (Color.red(lp) * a + Color.red(p) * (1f - a)).roundToInt()
                val g = (Color.green(lp) * a + Color.green(p) * (1f - a)).roundToInt()
                val b = (Color.blue(lp) * a + Color.blue(p) * (1f - a)).roundToInt()
                pixels[index] = Color.argb(255, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
            }
        }
        result.setPixels(pixels, 0, regionW, x0, y0, regionW, regionH)
        return result
    }
}
