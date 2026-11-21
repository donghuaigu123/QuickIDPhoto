package cn.quickidphoto.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * MODNet portrait matting (hivision_modnet.onnx) running fully offline via ONNX Runtime.
 * Produces a soft alpha matte that preserves hair-level detail far better than
 * coarse selfie segmentation. Model is bundled in assets; no network is required.
 */
class ModnetMatting(context: Context) {

    private val appContext = context.applicationContext
    // Loading the ONNX runtime native library must never crash the app: any failure
    // (missing ABI, extraction problem, low memory) degrades to the ML Kit fallback.
    private val env: OrtEnvironment? = try {
        OrtEnvironment.getEnvironment()
    } catch (error: Throwable) {
        null
    }
    private val lock = Any()

    @Volatile
    private var session: OrtSession? = null

    @Volatile
    private var loadFailed = false

    val isReady: Boolean get() = session != null

    /** Loads the model on first use. Returns false when the model is unusable. */
    fun ensureSession(): Boolean {
        session?.let { return true }
        if (loadFailed) return false
        val activeEnv = env ?: run { loadFailed = true; return false }
        synchronized(lock) {
            if (session == null && !loadFailed) {
                session = try {
                    val bytes = appContext.assets.open(MODEL_ASSET).use { it.readBytes() }
                    activeEnv.createSession(bytes)
                } catch (error: Throwable) {
                    loadFailed = true
                    null
                }
            }
        }
        return session != null
    }

    /**
     * Runs matting and returns a soft alpha of the SOURCE resolution in [0,1].
     * The source is letterboxed into the square model input (edge replication)
     * instead of being stretched, so portrait proportions and edge positions
     * stay correct; the alpha is then mapped back to the original crop.
     */
    fun matte(source: Bitmap, size: Int = INPUT_SIZE): FloatArray? {
        val active = session ?: return null
        val activeEnv = env ?: return null
        val sw = source.width
        val sh = source.height
        return try {
            val scale = minOf(size.toFloat() / sw, size.toFloat() / sh)
            val nw = max(1, (sw * scale).roundToInt())
            val nh = max(1, (sh * scale).roundToInt())
            val offsetX = (size - nw) / 2
            val offsetY = (size - nh) / 2
            val srcPixels = IntArray(sw * sh)
            source.getPixels(srcPixels, 0, sw, 0, 0, sw, sh)
            // 用四角采样得到的背景色填充 letterbox 空白区（而非边缘复制）。
            // 边缘复制会把人像轮廓延伸到填充区、误导模型把填充区判为前景，
            // 是“背景扣不干净 / 位置不准”的诱因之一。
            var br = 0f; var bg = 0f; var bb = 0f; var bc = 0
            val cs = maxOf(1, minOf(sw, sh) / 12)
            fun corner(cx: Int, cy: Int) {
                for (dy in 0 until cs) for (dx in 0 until cs) {
                    val px = srcPixels[(cy + dy) * sw + (cx + dx)]
                    br += Color.red(px); bg += Color.green(px); bb += Color.blue(px); bc++
                }
            }
            corner(0, 0); corner(sw - cs, 0); corner(0, sh - cs); corner(sw - cs, sh - cs)
            val bgColor = if (bc > 0) Color.rgb((br / bc).roundToInt(), (bg / bc).roundToInt(), (bb / bc).roundToInt())
                          else Color.WHITE
            val pixels = IntArray(size * size) { bgColor }
            for (y in 0 until size) {
                val sy = ((y - offsetY).toFloat() / scale).roundToInt()
                if (sy !in 0 until sh) continue
                val row = y * size
                val srcRow = sy * sw
                for (x in 0 until size) {
                    val sx = ((x - offsetX).toFloat() / scale).roundToInt()
                    if (sx !in 0 until sw) continue
                    pixels[row + x] = srcPixels[srcRow + sx]
                }
            }
            val buffer = FloatBuffer.allocate(3 * size * size)
            for (channel in 0 until 3) {
                for (i in pixels.indices) {
                    val value = when (channel) {
                        0 -> Color.red(pixels[i])
                        1 -> Color.green(pixels[i])
                        else -> Color.blue(pixels[i])
                    }
                    buffer.put((value - 127.5f) / 127.5f)
                }
            }
            buffer.rewind()

            val inputName = active.inputNames.first()
            var squareAlpha: FloatArray? = null
            OnnxTensor.createTensor(activeEnv, buffer, longArrayOf(1, 3, size.toLong(), size.toLong()))
                .use { input ->
                    active.run(mapOf(inputName to input)).use { result ->
                        val output = result.get(0) as OnnxTensor
                        val shape = output.info.shape
                        val raw = output.floatBuffer
                        val plane = size * size
                        val total = raw.remaining()
                        val channelCount = max(1, (shape.getOrNull(1) ?: 1).toInt())
                        val lastDim = (shape.lastOrNull() ?: 1).toInt()
                        squareAlpha = when {
                            total == plane -> FloatArray(plane) { raw.get().coerceIn(0f, 1f) }
                            channelCount >= plane && total >= plane ->
                                FloatArray(plane) { raw.get().coerceIn(0f, 1f) }
                            total == plane * 2 && lastDim == 2 ->
                                FloatArray(plane) { raw.get(); raw.get().coerceIn(0f, 1f) }
                            else -> null
                        }
                    }
                }
            val alphaSquare = squareAlpha ?: return null
            // 将方形推理结果映射回原始宽高比的区域，双线性采样保持边缘平滑
            val result = FloatArray(sw * sh)
            for (y in 0 until sh) {
                val my = offsetY.toFloat() + (y + 0.5f) * nh / sh - 0.5f
                for (x in 0 until sw) {
                    val mx = offsetX.toFloat() + (x + 0.5f) * nw / sw - 0.5f
                    result[y * sw + x] = bilinear(alphaSquare, size, size, mx, my)
                }
            }
            result
        } catch (error: Throwable) {
            null
        }
    }

    fun close() {
        synchronized(lock) {
            session?.close()
            session = null
        }
    }

    companion object {
        private const val MODEL_ASSET = "hivision_modnet.onnx"
        const val INPUT_SIZE = 512

        private fun bilinear(values: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
            val x0 = x.toInt().coerceIn(0, w - 1)
            val y0 = y.toInt().coerceIn(0, h - 1)
            val x1 = min(w - 1, x0 + 1)
            val y1 = min(h - 1, y0 + 1)
            val fx = (x - x0).coerceIn(0f, 1f)
            val fy = (y - y0).coerceIn(0f, 1f)
            val top = values[y0 * w + x0] * (1f - fx) + values[y0 * w + x1] * fx
            val bottom = values[y1 * w + x0] * (1f - fx) + values[y1 * w + x1] * fx
            return top * (1f - fy) + bottom * fy
        }
    }
}
