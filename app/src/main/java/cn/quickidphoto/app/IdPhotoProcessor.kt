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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** All image operations run locally. No photo is uploaded by this class. */
class IdPhotoProcessor(context: android.content.Context) {
    // Lazily resolved: the Activity reference passed at construction is still null
    // during field initialization (before attachBaseContext), so resolving it
    // eagerly crashes at startup. Also ensures app startup never touches the
    // ONNX native library; on devices where it cannot load, everything still
    // works via the ML Kit fallback.
    private val appContext by lazy { context.applicationContext }
    private val matting by lazy { ModnetMatting(appContext) }

    // Matte cache: recomputing MODNet on every slider tick is too slow, so the raw
    // alpha is cached and only refinement/compositing re-runs on parameter changes.
    private var cacheSource: Bitmap? = null
    private var cacheLeft = -1
    private var cacheTop = -1
    private var cacheWidth = -1
    private var cacheHeight = -1
    private var cacheAlpha: MatteSource? = null

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
        face: FaceBox?,
        preview: Boolean = false
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
        val processingLimit = if (settings.keepOriginalBackground) 3200 else 2800
        if (cropped.width > processingLimit || cropped.height > processingLimit) {
            val scale = processingLimit.toFloat() / max(cropped.width, cropped.height)
            cropped = resizeLanczos3(
                cropped, max(1, (cropped.width * scale).roundToInt()),
                max(1, (cropped.height * scale).roundToInt())
            )
        }
        val matte = obtainMatte(source, left, top, width, height, cropped)
        val composited = if (settings.keepOriginalBackground) {
            cropped
        } else {
            replaceBackground(cropped, settings, matte)
        }
        val beautified = applyBeauty(composited, settings)
        val effectiveScale = if (preview) 1 else settings.outputScale.coerceIn(1, 3)
        val targetWidth = spec.widthPx * effectiveScale
        val targetHeight = spec.heightPx * effectiveScale
        val scaled = resizeLanczos3(beautified, targetWidth, targetHeight)
        val output = scaled.copy(Bitmap.Config.ARGB_8888, true)
        output.density = spec.dpi * effectiveScale
        applyAdjustments(output, settings)
        if (settings.sharpen > 0) applySharpen(output, settings.sharpen / 100f * 0.9f)
        return output
    }

    private fun replaceBackground(source: Bitmap, settings: EditorSettings, matte: MatteSource): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)

        val softness = (settings.edgeFeather / 100f * 0.22f).coerceAtLeast(0.002f)
        val threshold = (0.5f - settings.edgeShift / 100f * 0.30f).coerceIn(0.08f, 0.92f)
        var alpha = FloatArray(source.width * source.height)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val confidence = matte.alphaAt(x, y, source.width, source.height)
                alpha[y * source.width + x] = ((confidence - threshold + softness) /
                    (2f * softness)).coerceIn(0f, 1f)
            }
        }
        alpha = refineAlpha(alpha, source.width, source.height, settings)
        val originalBackground = estimateBackground(pixels, alpha)
        val decontamStrength = settings.hairDetail / 100f * 0.92f

        // Precompute gradient background rows when enabled.
        val gradientRows = if (settings.gradientBackground) {
            IntArray(source.height) { y ->
                val t = y.toFloat() / max(1, source.height - 1)
                blend(settings.gradientStart, settings.gradientEnd, t)
            }
        } else null

        for (i in pixels.indices) {
            val a = alpha[i]
            var foreground = pixels[i]
            if (originalBackground != null && a in 0.025f..0.975f) {
                // Unmix old background spill from semi-transparent hair and clothing edges.
                val edgeWeight = (1f - abs(a * 2f - 1f)) * decontamStrength
                val invAlpha = 1f / max(a, 0.035f)
                val unmixedR = (Color.red(foreground) - (1f - a) * Color.red(originalBackground)) * invAlpha
                val unmixedG = (Color.green(foreground) - (1f - a) * Color.green(originalBackground)) * invAlpha
                val unmixedB = (Color.blue(foreground) - (1f - a) * Color.blue(originalBackground)) * invAlpha
                foreground = Color.argb(
                    Color.alpha(foreground),
                    (Color.red(foreground) * (1f - edgeWeight) + unmixedR * edgeWeight)
                        .roundToInt().coerceIn(0, 255),
                    (Color.green(foreground) * (1f - edgeWeight) + unmixedG * edgeWeight)
                        .roundToInt().coerceIn(0, 255),
                    (Color.blue(foreground) * (1f - edgeWeight) + unmixedB * edgeWeight)
                        .roundToInt().coerceIn(0, 255)
                )
            }
            val targetBackground = when {
                settings.transparentBackground -> 0
                gradientRows != null -> gradientRows[i / source.width]
                else -> settings.backgroundColor
            }
            val outputAlpha = if (settings.transparentBackground) (a * 255).roundToInt() else 255
            val r = (Color.red(foreground) * a + Color.red(targetBackground) * (1f - a)).roundToInt()
            val g = (Color.green(foreground) * a + Color.green(targetBackground) * (1f - a)).roundToInt()
            val b = (Color.blue(foreground) * a + Color.blue(targetBackground) * (1f - a)).roundToInt()
            pixels[i] = Color.argb(outputAlpha, r, g, b)
        }
        result.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        return result
    }

    /** Resolves the raw portrait alpha, preferring MODNet with an ML Kit fallback.
     *  The matte is cached against the source bitmap and crop rectangle so that
     *  adjusting sliders only re-runs refinement, not the neural network. */
    private fun obtainMatte(
        source: Bitmap, left: Int, top: Int, width: Int, height: Int, cropped: Bitmap
    ): MatteSource {
        val cached = cacheAlpha
        if (cached != null && cacheSource === source &&
            cacheLeft == left && cacheTop == top &&
            cacheWidth == width && cacheHeight == height) {
            return cached
        }
        val matte: MatteSource = if (matting.ensureSession()) {
            matting.matte(cropped)?.let { MatteSource.Modnet(it, ModnetMatting.INPUT_SIZE) }
                ?: mlkitMatte(cropped)?.let { MatteSource.Full(it) }
                ?: MatteSource.Full(FloatArray(cropped.width * cropped.height) { 1f })
        } else {
            mlkitMatte(cropped)?.let { MatteSource.Full(it) }
                ?: MatteSource.Full(FloatArray(cropped.width * cropped.height) { 1f })
        }
        cacheSource = source
        cacheLeft = left; cacheTop = top
        cacheWidth = width; cacheHeight = height
        cacheAlpha = matte
        return matte
    }

    /** Fallback path using ML Kit selfie segmentation (coarse, only when MODNet is unavailable). */
    private fun mlkitMatte(source: Bitmap): FloatArray? {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
        val segmenter = Segmentation.getClient(options)
        return try {
            val mask = Tasks.await(segmenter.process(InputImage.fromBitmap(source, 0)))
            val buffer = mask.buffer
            buffer.rewind()
            val values = FloatArray(mask.width * mask.height)
            for (i in values.indices) values[i] = buffer.float
            val full = FloatArray(source.width * source.height)
            for (y in 0 until source.height) {
                val my = y.toFloat() * (mask.height - 1) / max(1, source.height - 1)
                for (x in 0 until source.width) {
                    val mx = x.toFloat() * (mask.width - 1) / max(1, source.width - 1)
                    full[y * source.width + x] = bilinear(values, mask.width, mask.height, mx, my)
                }
            }
            full
        } catch (error: Throwable) {
            null
        } finally {
            segmenter.close()
        }
    }

    private sealed class MatteSource {
        abstract fun alphaAt(x: Int, y: Int, width: Int, height: Int): Float

        /** Alpha defined at full resolution, index = y * width + x. */
        class Full(private val alpha: FloatArray) : MatteSource() {
            override fun alphaAt(x: Int, y: Int, width: Int, height: Int): Float = alpha[y * width + x]
        }

        /** Alpha defined on a square matte of [size]×[size]; bilinear sampling to target size. */
        class Modnet(private val alpha: FloatArray, private val size: Int) : MatteSource() {
            override fun alphaAt(x: Int, y: Int, width: Int, height: Int): Float {
                val mx = x.toFloat() * (size - 1) / max(1, width - 1)
                val my = y.toFloat() * (size - 1) / max(1, height - 1)
                return IdPhotoProcessor.bilinear(alpha, size, size, mx, my)
            }
        }
    }

    companion object {
        fun bilinear(values: FloatArray, width: Int, height: Int, x: Float, y: Float): Float {
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

        fun blend(from: Int, to: Int, t: Float): Int {
            val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * t).roundToInt()
            val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * t).roundToInt()
            val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).roundToInt()
            return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
        }
    }

    private fun refineAlpha(raw: FloatArray, width: Int, height: Int, settings: EditorSettings): FloatArray {
        val radius = (1 + settings.edgeFeather / 38).coerceIn(1, 3)
        val smoothed = boxBlur(raw, width, height, radius)
        val detail = settings.hairDetail / 100f
        val edgeContrast = 1f + detail * 0.18f
        return FloatArray(raw.size) { i ->
            val preserved = smoothed[i] + (raw[i] - smoothed[i]) * detail * 0.62f
            val centered = (preserved - 0.5f) * edgeContrast + 0.5f
            centered.coerceIn(0f, 1f)
        }
    }

    private fun boxBlur(source: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val stride = width + 1
        val integral = FloatArray((width + 1) * (height + 1))
        for (y in 1..height) {
            var rowSum = 0f
            val sourceOffset = (y - 1) * width
            val integralOffset = y * stride
            val previousOffset = (y - 1) * stride
            for (x in 1..width) {
                rowSum += source[sourceOffset + x - 1]
                integral[integralOffset + x] = integral[previousOffset + x] + rowSum
            }
        }
        val output = FloatArray(source.size)
        for (y in 0 until height) {
            val top = max(0, y - radius)
            val bottom = min(height - 1, y + radius)
            for (x in 0 until width) {
                val left = max(0, x - radius)
                val right = min(width - 1, x + radius)
                val area = (right - left + 1) * (bottom - top + 1)
                val sum = integral[(bottom + 1) * stride + right + 1] -
                    integral[top * stride + right + 1] -
                    integral[(bottom + 1) * stride + left] +
                    integral[top * stride + left]
                output[y * width + x] = sum / area
            }
        }
        return output
    }

    private fun estimateBackground(pixels: IntArray, alpha: FloatArray): Int? {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var count = 0
        var i = 0
        while (i < pixels.size) {
            if (alpha[i] < 0.06f) {
                r += Color.red(pixels[i])
                g += Color.green(pixels[i])
                b += Color.blue(pixels[i])
                count++
            }
            i += 2
        }
        if (count < 32) return null
        return Color.rgb((r / count).roundToInt(), (g / count).roundToInt(), (b / count).roundToInt())
    }

    /** High-quality separable Lanczos-3 resampling, preserving more detail than bilinear scaling. */
    private fun resizeLanczos3(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        if (source.width == targetWidth && source.height == targetHeight) {
            return source.copy(Bitmap.Config.ARGB_8888, true)
        }
        val srcWidth = source.width
        val srcHeight = source.height
        val input = IntArray(srcWidth * srcHeight)
        source.getPixels(input, 0, srcWidth, 0, 0, srcWidth, srcHeight)
        val horizontal = IntArray(targetWidth * srcHeight)
        val xScale = srcWidth.toFloat() / targetWidth
        for (y in 0 until srcHeight) {
            val row = y * srcWidth
            val outRow = y * targetWidth
            for (x in 0 until targetWidth) {
                val center = (x + 0.5f) * xScale - 0.5f
                val start = floor(center).toInt() - 2
                var a = 0f
                var r = 0f
                var g = 0f
                var b = 0f
                var weightSum = 0f
                for (k in 0 until 7) {
                    val sampleX = start + k
                    val weight = lanczos(center - sampleX)
                    if (abs(weight) < 0.0001f) continue
                    val clampedX = sampleX.coerceIn(0, srcWidth - 1)
                    val pixel = input[row + clampedX]
                    a += Color.alpha(pixel) * weight
                    r += Color.red(pixel) * weight
                    g += Color.green(pixel) * weight
                    b += Color.blue(pixel) * weight
                    weightSum += weight
                }
                if (abs(weightSum) < 0.0001f) weightSum = 1f
                horizontal[outRow + x] = Color.argb(
                    (a / weightSum).roundToInt().coerceIn(0, 255),
                    (r / weightSum).roundToInt().coerceIn(0, 255),
                    (g / weightSum).roundToInt().coerceIn(0, 255),
                    (b / weightSum).roundToInt().coerceIn(0, 255)
                )
            }
        }
        val outputPixels = IntArray(targetWidth * targetHeight)
        val yScale = srcHeight.toFloat() / targetHeight
        for (y in 0 until targetHeight) {
            val center = (y + 0.5f) * yScale - 0.5f
            val start = floor(center).toInt() - 2
            val row = y * targetWidth
            for (x in 0 until targetWidth) {
                var a = 0f
                var r = 0f
                var g = 0f
                var b = 0f
                var weightSum = 0f
                for (k in 0 until 7) {
                    val sampleY = start + k
                    val weight = lanczos(center - sampleY)
                    if (abs(weight) < 0.0001f) continue
                    val clampedY = sampleY.coerceIn(0, srcHeight - 1)
                    val pixel = horizontal[clampedY * targetWidth + x]
                    a += Color.alpha(pixel) * weight
                    r += Color.red(pixel) * weight
                    g += Color.green(pixel) * weight
                    b += Color.blue(pixel) * weight
                    weightSum += weight
                }
                if (abs(weightSum) < 0.0001f) weightSum = 1f
                outputPixels[row + x] = Color.argb(
                    (a / weightSum).roundToInt().coerceIn(0, 255),
                    (r / weightSum).roundToInt().coerceIn(0, 255),
                    (g / weightSum).roundToInt().coerceIn(0, 255),
                    (b / weightSum).roundToInt().coerceIn(0, 255)
                )
            }
        }
        return Bitmap.createBitmap(outputPixels, targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    }

    private fun lanczos(value: Float): Float {
        val distance = abs(value)
        if (distance >= 3f) return 0f
        if (distance < 0.0001f) return 1f
        val x = value * PI.toFloat()
        return (3f * sin(x) * sin(x / 3f) / (x * x))
    }

    private fun applyBeauty(source: Bitmap, settings: EditorSettings): Bitmap {
        if (settings.smooth == 0 && settings.whiten == 0 && settings.rosy == 0) return source
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(result.width * result.height)
        result.getPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
        val softPixels = if (settings.smooth > 0) {
            val fine = Bitmap.createScaledBitmap(
                result, max(1, result.width / 8), max(1, result.height / 8), true
            )
            val broad = Bitmap.createScaledBitmap(
                result, max(1, result.width / 16), max(1, result.height / 16), true
            )
            val fineBlur = Bitmap.createScaledBitmap(fine, result.width, result.height, true)
            val broadBlur = Bitmap.createScaledBitmap(broad, result.width, result.height, true)
            val finePixels = IntArray(result.width * result.height)
            val broadPixels = IntArray(result.width * result.height)
            fineBlur.getPixels(finePixels, 0, result.width, 0, 0, result.width, result.height)
            broadBlur.getPixels(broadPixels, 0, result.width, 0, 0, result.width, result.height)
            IntArray(pixels.size) { i ->
                Color.argb(
                    Color.alpha(finePixels[i]),
                    (Color.red(finePixels[i]) * 0.62f + Color.red(broadPixels[i]) * 0.38f).roundToInt(),
                    (Color.green(finePixels[i]) * 0.62f + Color.green(broadPixels[i]) * 0.38f).roundToInt(),
                    (Color.blue(finePixels[i]) * 0.62f + Color.blue(broadPixels[i]) * 0.38f).roundToInt()
                )
            }
        } else null
        val master = settings.beautyIntensity / 100f
        val smoothAmount = settings.smooth / 100f * 0.95f * master
        val whitenAmount = settings.whiten / 100f * 0.28f * master
        val rosyAmount = settings.rosy / 100f * 0.24f * master
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val y = 0.299f * r + 0.587f * g + 0.114f * b
            val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
            val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
            val skin = cb in 72f..132f && cr in 128f..184f && y > 28f
            if (!skin) continue
            var nr = r.toFloat()
            var ng = g.toFloat()
            var nb = b.toFloat()
            softPixels?.let {
                nr += (Color.red(it[i]) - nr) * smoothAmount
                ng += (Color.green(it[i]) - ng) * smoothAmount
                nb += (Color.blue(it[i]) - nb) * smoothAmount
            }
            nr += (255f - nr) * whitenAmount
            ng += (255f - ng) * whitenAmount
            nb += (255f - nb) * whitenAmount
            val rosyR = min(255f, nr + 34f)
            val rosyG = min(255f, ng + 4f)
            val rosyB = min(255f, nb + 10f)
            nr += (rosyR - nr) * rosyAmount
            ng += (rosyG - ng) * rosyAmount
            nb += (rosyB - nb) * rosyAmount
            pixels[i] = Color.argb(Color.alpha(color), nr.roundToInt().coerceIn(0, 255),
                ng.roundToInt().coerceIn(0, 255), nb.roundToInt().coerceIn(0, 255))
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
    /** Print sheet presets ported from HivisionIDPhotos, all rendered at 300 DPI. */
    val layouts = listOf(
        Triple("六寸相纸", 1800, 1200),
        Triple("五寸相纸", 1500, 1050),
        Triple("A4 纸张", 3508, 2480),
        Triple("3R 相纸", 1050, 1500),
        Triple("4R 相纸", 1200, 1800)
    )

    fun sixInch(photo: Bitmap): Bitmap = generate(photo, 0, Color.WHITE)

    /** Arranges photos in a grid that maximizes the count for the sheet size. */
    fun generate(photo: Bitmap, layoutIndex: Int, backgroundColor: Int): Bitmap {
        val (name, sheetWidth, sheetHeight) = layouts[layoutIndex.coerceIn(0, layouts.lastIndex)]
        val sheet = Bitmap.createBitmap(sheetWidth, sheetHeight, Bitmap.Config.ARGB_8888)
        sheet.density = 300
        val canvas = Canvas(sheet)
        canvas.drawColor(backgroundColor)
        val margin = 36
        val gap = 18
        val ratio = photo.width.toFloat() / photo.height

        var best: Triple<Int, Int, Int>? = null // cols, rows, cellHeight
        for (rows in 1..12) {
            val cellHeight = (sheetHeight - margin * 2 - gap * (rows - 1)) / rows
            if (cellHeight <= 0) break
            val cellWidth = (cellHeight * ratio).roundToInt()
            if (cellWidth <= 0) continue
            val cols = (sheetWidth - margin * 2 + gap) / (cellWidth + gap)
            if (cols <= 0) continue
            val count = cols * rows
            val current = best
            if (current == null || count > current.first * current.second) best = Triple(cols, rows, cellHeight)
        }
        val arrangement = best ?: Triple(1, 1, sheetHeight - margin * 2)
        val cellHeight = arrangement.third
        val cellWidth = (cellHeight * ratio).roundToInt()
        val gridWidth = arrangement.first * cellWidth + (arrangement.first - 1) * gap
        val gridHeight = arrangement.second * cellHeight + (arrangement.second - 1) * gap
        val offsetX = (sheetWidth - gridWidth) / 2
        val offsetY = (sheetHeight - gridHeight) / 2

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (backgroundColor == Color.WHITE) Color.GRAY else Color.argb(120, 255, 255, 255)
            strokeWidth = 2f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        }
        for (row in 0 until arrangement.second) {
            for (column in 0 until arrangement.first) {
                val x = offsetX + column * (cellWidth + gap)
                val y = offsetY + row * (cellHeight + gap)
                canvas.drawBitmap(photo, null, android.graphics.Rect(x, y, x + cellWidth, y + cellHeight), null)
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + cellWidth).toFloat(), (y + cellHeight).toFloat(), paint)
            }
        }
        return sheet
    }
}
