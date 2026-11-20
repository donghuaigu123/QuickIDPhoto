package cn.quickidphoto.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * MODNet portrait matting (hivision_modnet.onnx) running fully offline via ONNX Runtime.
 * Produces a soft alpha matte that preserves hair-level detail far better than
 * coarse selfie segmentation. Model is bundled in assets; no network is required.
 */
class ModnetMatting(context: Context) {

    private val appContext = context.applicationContext
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
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
        synchronized(lock) {
            if (session == null && !loadFailed) {
                session = try {
                    val bytes = appContext.assets.open(MODEL_ASSET).use { it.readBytes() }
                    env.createSession(bytes)
                } catch (error: Throwable) {
                    loadFailed = true
                    null
                }
            }
        }
        return session != null
    }

    /**
     * Runs matting and returns a portrait alpha of [size]×[size] in [0,1],
     * or null when the model cannot run.
     */
    fun matte(source: Bitmap, size: Int = INPUT_SIZE): FloatArray? {
        val active = session ?: return null
        return try {
            val resized = Bitmap.createScaledBitmap(source, size, size, true)
            val pixels = IntArray(size * size)
            resized.getPixels(pixels, 0, size, 0, 0, size, size)
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
            OnnxTensor.createTensor(env, buffer, longArrayOf(1, 3, size.toLong(), size.toLong()))
                .use { input ->
                    active.run(mapOf(inputName to input)).use { result ->
                        val output = result.get(0) as OnnxTensor
                        val shape = output.info.shape
                        val raw = output.floatBuffer
                        val channelCount = max(1, (shape.getOrNull(1) ?: 1).toInt())
                        val plane = size * size
                        val alpha = FloatArray(plane)
                        if (channelCount >= plane && raw.remaining() >= plane) {
                            // Shape like (1, H*W) or (H*W,): take the first plane directly.
                            for (i in 0 until plane) alpha[i] = raw.get(i).coerceIn(0f, 1f)
                        } else {
                            // Shape like (1,1,H,W) or (1,H,W,1): average available channels per pixel.
                            val total = raw.remaining()
                            val usable = minOf(total, channelCount * plane)
                            for (i in 0 until plane) {
                                var sum = 0f
                                var count = 0
                                var c = 0
                                while (c < usable - i && c < channelCount * plane) {
                                    val index = c * plane + i
                                    if (index in 0 until total) {
                                        sum += raw.get(index)
                                        count++
                                    }
                                    c++
                                }
                                alpha[i] = if (count > 0) (sum / count).coerceIn(0f, 1f) else 0f
                            }
                        }
                        alpha
                    }
                }
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
    }
}
