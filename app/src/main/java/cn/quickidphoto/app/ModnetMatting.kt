package cn.quickidphoto.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.nio.FloatBuffer
import kotlin.math.floor
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
     *
     * 关键修正（v1.9.0）：hivision_modnet 权重是按「整图直接拉伸到正方形」训练的。
     * 之前用 letterbox（保持比例 + 两侧填充）喂给模型，会让模型在填充边上产生误判，
     * 证件照里复杂背景（树林/深色头发）会被判为前景 → 背景抠不净、边缘贴合残留。
     * 实测对用户的真实一寸照：letterbox 右耳旁树丛 alpha=0.68，直接拉伸仅 0.087，
     * 且人像（头发0.99/脸0.99/肩膀0.86）完整保留。证件照本身比例固定，拉伸形变极小。
     */
    fun matte(source: Bitmap, size: Int = INPUT_SIZE): FloatArray? {
        val active = session ?: return null
        val activeEnv = env ?: return null
        val sw = source.width
        val sh = source.height
        return try {
            val srcPixels = IntArray(sw * sh)
            source.getPixels(srcPixels, 0, sw, 0, 0, sw, sh)
            // 直接拉伸到 size×size（双线性），不用 letterbox、不做假背景填充。
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                val sy = (y + 0.5f) * sh / size - 0.5f
                val row = y * size
                for (x in 0 until size) {
                    val sx = (x + 0.5f) * sw / size - 0.5f
                    pixels[row + x] = bilinearColor(srcPixels, sw, sh, sx, sy, srcPixels[0])
                }
            }
            val input = FloatArray(size * size * 3)
            for (i in 0 until size * size) {
                val px = pixels[i]
                input[i] = (Color.red(px) - 127.5f) / 127.5f
                input[size * size + i] = (Color.green(px) - 127.5f) / 127.5f
                input[2 * size * size + i] = (Color.blue(px) - 127.5f) / 127.5f
            }
            val buffer = FloatBuffer.wrap(input)
            val inputName = active.inputNames.first()
            OnnxTensor.createTensor(activeEnv, buffer, longArrayOf(1, 3, size.toLong(), size.toLong()))
                .use { tensor ->
                    active.run(mapOf(inputName to tensor)).use { result ->
                        val output = result.get(0) as OnnxTensor
                        val raw = output.floatBuffer
                        val plane = size * size
                        if (raw.remaining() < plane) return null
                        // 512 输出直接映射回原图（拉伸的逆变换，双线性）
                        return mappedAlpha(raw, plane, size, sw, sh)
                    }
                }
        } catch (error: Throwable) {
            null
        }
    }

    /** 把模型输出的 512×512 alpha 用双线性映射回原图尺寸。 */
    private fun mappedAlpha(raw: java.nio.FloatBuffer, plane: Int, size: Int, sw: Int, sh: Int): FloatArray {
        val out = FloatArray(sw * sh)
        for (y in 0 until sh) {
            val my = (y + 0.5f) * size / sh - 0.5f
            val y0 = floor(my).toInt()
            val y1 = minOf(size - 1, y0 + 1)
            val fy = (my - y0).coerceIn(0f, 1f)
            for (x in 0 until sw) {
                val mx = (x + 0.5f) * size / sw - 0.5f
                val x0 = floor(mx).toInt()
                val x1 = minOf(size - 1, x0 + 1)
                val fx = (mx - x0).coerceIn(0f, 1f)
                val v00 = if (y0 in 0 until size && x0 in 0 until size) raw.get(y0 * size + x0) else 0f
                val v10 = if (y0 in 0 until size && x1 in 0 until size) raw.get(y0 * size + x1) else 0f
                val v01 = if (y1 in 0 until size && x0 in 0 until size) raw.get(y1 * size + x0) else 0f
                val v11 = if (y1 in 0 until size && x1 in 0 until size) raw.get(y1 * size + x1) else 0f
                val a = (v00 * (1f - fx) + v10 * fx) * (1f - fy) + (v01 * (1f - fx) + v11 * fx) * fy
                out[y * sw + x] = a.coerceIn(0f, 1f)
            }
        }
        return out
    }

    companion object {
        private const val MODEL_ASSET = "hivision_modnet.onnx"
        const val INPUT_SIZE = 512

        /** 双线性采样一个 ARGB 像素数组；越界返回 fill 颜色。 */
        private fun bilinearColor(values: IntArray, w: Int, h: Int, x: Float, y: Float, fill: Int): Int {
            val x0 = x.toInt()
            val y0 = y.toInt()
            val fx = (x - x0).coerceIn(0f, 1f)
            val fy = (y - y0).coerceIn(0f, 1f)
            fun at(px: Int, py: Int): Int =
                if (px in 0 until w && py in 0 until h) values[py * w + px] else fill
            fun lerp(c1: Int, c2: Int, t: Float): Int = Color.argb(
                (Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * t).roundToInt().coerceIn(0, 255),
                (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t).roundToInt().coerceIn(0, 255),
                (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t).roundToInt().coerceIn(0, 255),
                (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t).roundToInt().coerceIn(0, 255))
            val top = lerp(at(x0, y0), at(x0 + 1, y0), fx)
            val bottom = lerp(at(x0, y0 + 1), at(x0 + 1, y0 + 1), fx)
            return lerp(top, bottom, fy)
        }

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
