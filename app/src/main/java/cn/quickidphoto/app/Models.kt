package cn.quickidphoto.app

import android.graphics.Color

/** Physical dimensions and pixel requirements of an ID photo. */
data class PhotoSpec(
    val name: String,
    val widthMm: Float,
    val heightMm: Float,
    val widthPx: Int,
    val heightPx: Int,
    val dpi: Int = 300,
    val note: String = ""
) {
    val aspect: Float get() = widthPx.toFloat() / heightPx.toFloat()
    val label: String get() = "$name  ${widthPx}×${heightPx}px"

    companion object {
        val presets = listOf(
            PhotoSpec("一寸", 25f, 35f, 295, 413, note = "简历、考试常用"),
            PhotoSpec("大一寸", 33f, 48f, 390, 567, note = "护照、签证常用"),
            PhotoSpec("二寸", 35f, 49f, 413, 579, note = "证书、材料常用"),
            PhotoSpec("小二寸", 35f, 45f, 413, 531, note = "部分签证常用"),
            PhotoSpec("中国护照", 33f, 48f, 390, 567, note = "33×48mm"),
            PhotoSpec("港澳通行证", 33f, 48f, 390, 567, note = "33×48mm"),
            PhotoSpec("美国签证", 51f, 51f, 602, 602, note = "51×51mm"),
            PhotoSpec("日本签证", 45f, 45f, 531, 531, note = "45×45mm"),
            PhotoSpec("申根签证", 35f, 45f, 413, 531, note = "35×45mm"),
            PhotoSpec("社保卡", 26f, 32f, 358, 441, note = "358×441px"),
            PhotoSpec("驾驶证", 22f, 32f, 260, 378, note = "22×32mm"),
            PhotoSpec("公务员考试", 25f, 35f, 295, 413, note = "常用报名尺寸"),
            PhotoSpec("教师资格证", 25f, 35f, 295, 413, note = "请核对报名公告"),
            PhotoSpec("英语四六级", 24f, 32f, 144, 192, note = "部分考点要求144×192", dpi = 150),
            PhotoSpec("简历形象照", 30f, 40f, 354, 472, note = "3:4竖版")
        )
        val backgroundColors = listOf(
            "纯白" to Color.WHITE,
            "证件蓝" to Color.rgb(67, 142, 219),
            "标准蓝" to Color.rgb(0, 102, 204),
            "证件红" to Color.rgb(220, 53, 69),
            "浅灰" to Color.rgb(238, 240, 244),
            "深蓝" to Color.rgb(33, 71, 120)
        )
    }
}

data class FaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
}

data class CropBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

data class EditorSettings(
    var faceScale: Float = 0.57f,
    var headTopMargin: Float = 0.10f,
    var horizontalOffset: Float = 0f,
    var brightness: Int = 0,
    var contrast: Int = 0,
    var saturation: Int = 0,
    var temperature: Int = 0,
    var tint: Int = 0,
    var shadows: Int = 0,
    var highlights: Int = 0,
    var smooth: Int = 0,
    var whiten: Int = 0,
    var rosy: Int = 0,
    var sharpen: Int = 0,
    var edgeFeather: Int = 45,
    var edgeShift: Int = 0,
    var hairDetail: Int = 50,
    var outputScale: Int = 2,
    var beautyIntensity: Int = 100,
    var gradientBackground: Boolean = false,
    var gradientStart: Int = Color.rgb(58, 123, 213),
    var gradientEnd: Int = Color.rgb(178, 226, 248),
    var targetKb: Int = 0,
    var printLayout: Int = 0,
    var printBackgroundColor: Int = Color.WHITE,
    var backgroundColor: Int = Color.WHITE,
    var transparentBackground: Boolean = false,
    var keepOriginalBackground: Boolean = true,
    var jpegQuality: Int = 95,
    var png: Boolean = false
) {
    fun copyValues() = copy()
}
