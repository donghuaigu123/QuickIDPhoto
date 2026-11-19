package cn.quickidphoto.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** All image operations run locally. No photo is uploaded by this class. */
class IdPhotoProcessor {
    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .setMinFaceSize(0.08f)
        .build()

    fun detectLargestFace(bitmap: Bitmap): FaceBox? {
        val detector = FaceDetection.getClient(detectorOptions)
        return try {
            val faces = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))
            faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }?.let {
                FaceBox(
                    it.boundingBox.left.toFloat(), it.boundingBox.top.toFloat(),
                    it.boundingBox.right.toFloat(), it.boundingBox.bottom.toFloat()
                )
            }
        } finally {
            detector.close()
        }
    }

    fun transform(source: Bitmap, rotation: Float, mirror: Boolean): Bitmap {
        if (rotation == 0f && !mirror) return source
        val matrix = Matrix().apply {
            postRotate(rotation)
            if (mirror) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    fun render(
        source: Bitmap,
        spec: PhotoSpec,
        settings: EditorSettings,
        face: FaceBox?
    ): Bitmap {
        val crop = FaceCropCalculator.calculate(
            source.width, source.height, spec.aspect, face,
            settings.faceScale, settings.headTopMargin, settings.horizontalOffset
        )
        val left = crop.left.roundToInt().coerceIn(0, source.width - 1)
        val top = crop.top.roundToInt().coerceIn(0, source.height - 1)
        val width = crop.width.roundToInt().coerceIn(1, source.width - left)
        val height = crop.height.roundToInt().coerceIn(1, source.height - top)
        var cropped = Bitmap.createBitmap(source, left, top, width, height)
        if (cropped.width > 1600 || cropped.height > 1600) {
            val scale = 1600f / max(cropped.width, cropped.height)
            cropped = Bitmap.createScaledBitmap(
                cropped, (cropped.width * scale).roundToInt(),
                (cropped.height * scale).roundToInt(), true
            )
        }
        val composited = if (settings.keepOriginalBackground) {
            cropped
        } else {
            replaceBackground(cropped, settings)
        }
        val beautified = applyBeauty(composited, settings)
        val scaled = Bitmap.createScaledBitmap(beautified, spec.widthPx, spec.heightPx, true)
        val output = scaled.copy(Bitmap.Config.ARGB_8888, true)
        output.density = spec.dpi
        applyAdjustments(output, settings)
        if (settings.sharpen > 0) applySharpen(output, settings.sharpen / 100f)
        return output
    }

    private fun replaceBackground(source: Bitmap, settings: EditorSettings): Bitmap {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
        val segmenter = Segmentation.getClient(options)
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        try {
            val mask = Tasks.await(segmenter.process(InputImage.fromBitmap(source, 0)))
            val buffer = mask.buffer
            buffer.rewind()
            val values = FloatArray(mask.width * mask.height)
            for (i in values.indices) values[i] = buffer.float
            val pixels = IntArray(source.width * source.height)
            source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
            val softness = (settings.edgeFeather / 100f * 0.45f).coerceAtLeast(0.002f)
            val threshold = (0.5f - settings.edgeShift / 100f * 0.30f).coerceIn(0.1f, 0.9f)
            for (y in 0 until source.height) {
                val my = y.toFloat() * (mask.height - 1) / max(1, source.height - 1)
                for (x in 0 until source.width) {
                    val mx = x.toFloat() * (mask.width - 1) / max(1, source.width - 1)
                    val confidence = bilinear(values, mask.width, mask.height, mx, my)
                    val alpha = ((confidence - threshold + softness) / (2f * softness))
                        .coerceIn(0f, 1f)
                    val index = y * source.width + x
                    val fg = pixels[index]
                    val bg = if (settings.transparentBackground) 0 else settings.backgroundColor
                    val outAlpha = if (settings.transparentBackground) (alpha * 255).roundToInt() else 255
                    val r = (Color.red(fg) * alpha + Color.red(bg) * (1f - alpha)).roundToInt()
                    val g = (Color.green(fg) * alpha + Color.green(bg) * (1f - alpha)).roundToInt()
                    val b = (Color.blue(fg) * alpha + Color.blue(bg) * (1f - alpha)).roundToInt()
                    pixels[index] = Color.argb(outAlpha, r, g, b)
                }
            }
            result.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        } finally {
            segmenter.close()
        }
        return result
    }

    private fun bilinear(values: FloatArray, width: Int, height: Int, x: Float, y: Float): Float {
        val x0 = x.toInt().coerceIn(0, width - 1)
        val y0 = y.toInt().coerceIn(0, height - 1)
        val x1 = min(width - 1, x0 + 1)
        val y1 = min(height - 1, y0 + 1)
        val fx = x - x0
        val fy = y - y0
        val top = values[y0 * width + x0] * (1f - fx) + values[y0 * width + x1] * fx
        val bottom = values[y1 * width + x0] * (1f - fx) + values[y1 * width + x1] * fx
        return top * (1f - fy) + bottom * fy
    }

    private fun applyBeauty(source: Bitmap, settings: EditorSettings): Bitmap {
        if (settings.smooth == 0 && settings.whiten == 0) return source
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(result.width * result.height)
        result.getPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
        val blurredPixels = if (settings.smooth > 0) {
            val small = Bitmap.createScaledBitmap(
                result, max(1, result.width / 10), max(1, result.height / 10), true
            )
            val blurred = Bitmap.createScaledBitmap(small, result.width, result.height, true)
            IntArray(result.width * result.height).also {
                blurred.getPixels(it, 0, result.width, 0, 0, result.width, result.height)
            }
        } else null
        val smoothAmount = settings.smooth / 100f * 0.85f
        val whitenAmount = settings.whiten / 100f * 0.16f
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val y = 0.299f * r + 0.587f * g + 0.114f * b
            val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
            val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
            val skin = cb in 77f..127f && cr in 133f..178f && y > 35f
            if (!skin) continue
            var nr = r.toFloat()
            var ng = g.toFloat()
            var nb = b.toFloat()
            blurredPixels?.let {
                nr += (Color.red(it[i]) - nr) * smoothAmount
                ng += (Color.green(it[i]) - ng) * smoothAmount
                nb += (Color.blue(it[i]) - nb) * smoothAmount
            }
            nr += (255f - nr) * whitenAmount
            ng += (255f - ng) * whitenAmount
            nb += (255f - nb) * whitenAmount
            pixels[i] = Color.argb(Color.alpha(color), nr.roundToInt(), ng.roundToInt(), nb.roundToInt())
        }
        result.setPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
        return result
    }

    private fun applyAdjustments(bitmap: Bitmap, s: EditorSettings) {
        if (listOf(s.brightness, s.contrast, s.saturation, s.temperature, s.tint,
                s.shadows, s.highlights).all { it == 0 }) return
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val contrast = 1f + s.contrast / 100f * 0.85f
        val saturation = 1f + s.saturation / 100f
        val brightness = s.brightness * 0.65f
        for (i in pixels.indices) {
            val c = pixels[i]
            var r = Color.red(c).toFloat()
            var g = Color.green(c).toFloat()
            var b = Color.blue(c).toFloat()
            r += s.temperature * 0.38f
            b -= s.temperature * 0.38f
            g += s.tint * 0.30f
            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            val shadowWeight = (1f - (luma / 255f).coerceIn(0f, 1f)).pow(2f)
            val highlightWeight = (luma / 255f).coerceIn(0f, 1f).pow(2f)
            val tonal = s.shadows * 0.55f * shadowWeight + s.highlights * 0.55f * highlightWeight
            r = (r - 127.5f) * contrast + 127.5f + brightness + tonal
            g = (g - 127.5f) * contrast + 127.5f + brightness + tonal
            b = (b - 127.5f) * contrast + 127.5f + brightness + tonal
            val newLuma = 0.299f * r + 0.587f * g + 0.114f * b
            r = newLuma + (r - newLuma) * saturation
            g = newLuma + (g - newLuma) * saturation
            b = newLuma + (b - newLuma) * saturation
            pixels[i] = Color.argb(Color.alpha(c), r.roundToInt().coerceIn(0, 255),
                g.roundToInt().coerceIn(0, 255), b.roundToInt().coerceIn(0, 255))
        }
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun applySharpen(bitmap: Bitmap, amount: Float) {
        if (bitmap.width < 3 || bitmap.height < 3) return
        val small = Bitmap.createScaledBitmap(bitmap, max(1, bitmap.width / 3), max(1, bitmap.height / 3), true)
        val blurred = Bitmap.createScaledBitmap(small, bitmap.width, bitmap.height, true)
        val original = IntArray(bitmap.width * bitmap.height)
        val low = IntArray(original.size)
        bitmap.getPixels(original, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        blurred.getPixels(low, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in original.indices) {
            val a = Color.alpha(original[i])
            val r = (Color.red(original[i]) + (Color.red(original[i]) - Color.red(low[i])) * amount)
                .roundToInt().coerceIn(0, 255)
            val g = (Color.green(original[i]) + (Color.green(original[i]) - Color.green(low[i])) * amount)
                .roundToInt().coerceIn(0, 255)
            val b = (Color.blue(original[i]) + (Color.blue(original[i]) - Color.blue(low[i])) * amount)
                .roundToInt().coerceIn(0, 255)
            original[i] = Color.argb(a, r, g, b)
        }
        bitmap.setPixels(original, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    fun suggestWhiteBalance(source: Bitmap): Pair<Int, Int> {
        val sample = Bitmap.createScaledBitmap(source, 64, 64, true)
        val pixels = IntArray(64 * 64)
        sample.getPixels(pixels, 0, 64, 0, 0, 64, 64)
        var r = 0.0
        var g = 0.0
        var b = 0.0
        pixels.forEach {
            r += Color.red(it)
            g += Color.green(it)
            b += Color.blue(it)
        }
        r /= pixels.size
        g /= pixels.size
        b /= pixels.size
        val temperature = ((b - r) / 255.0 * 180.0).roundToInt().coerceIn(-100, 100)
        val tint = (((r + b) / 2.0 - g) / 255.0 * 200.0).roundToInt().coerceIn(-100, 100)
        return temperature to tint
    }
}

object LayoutSheetGenerator {
    /** Creates a 6×4 inch, 300 DPI print sheet with cutting guides. */
    fun sixInch(photo: Bitmap): Bitmap {
        val sheet = Bitmap.createBitmap(1800, 1200, Bitmap.Config.ARGB_8888)
        sheet.density = 300
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)
        val margin = 36
        val gap = 18
        val photoRatio = photo.width.toFloat() / photo.height
        val cellHeight = (1200 - margin * 2 - gap) / 2
        val cellWidth = (cellHeight * photoRatio).roundToInt()
        val columns = ((1800 - margin * 2 + gap) / (cellWidth + gap)).coerceAtLeast(1)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            strokeWidth = 2f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        }
        for (row in 0 until 2) {
            for (column in 0 until columns) {
                val x = margin + column * (cellWidth + gap)
                val y = margin + row * (cellHeight + gap)
                if (x + cellWidth > 1800 - margin) continue
                canvas.drawBitmap(photo, null, android.graphics.Rect(x, y, x + cellWidth, y + cellHeight), null)
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + cellWidth).toFloat(), (y + cellHeight).toFloat(), paint)
            }
        }
        return sheet
    }
}
