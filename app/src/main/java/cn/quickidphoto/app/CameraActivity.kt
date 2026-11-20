package cn.quickidphoto.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlay: IdPhotoOverlayView
    private lateinit var status: TextView
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing = CameraSelector.LENS_FACING_FRONT
    private var countdownSeconds = 0
    private var capturing = false
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val analyzing = AtomicBoolean(false)
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.06f).build()
    )
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "需要相机权限才能拍摄", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val spec = PhotoSpec.presets.getOrElse(intent.getIntExtra("spec", 0)) { PhotoSpec.presets[0] }
        val root = vertical(this).apply { setBackgroundColor(Color.BLACK) }
        val toolbar = horizontal(this).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(glassAction("‹ 返回") { finish() }, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(label("拍摄 · ${spec.name}", 17f, Color.WHITE).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, dp(52), 2f))
            addView(glassAction("切换镜头") { switchCamera() }, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        root.addView(toolbar)
        val frame = FrameLayout(this)
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        overlay = IdPhotoOverlayView(this).apply { aspect = spec.aspect }
        frame.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        status = label("正在启动相机…", 15f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(status)
        val controls = horizontal(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(18))
            addView(glassAction("网格") { this@CameraActivity.overlay.toggleGrid() }, LinearLayout.LayoutParams(0, dp(56), 1f))
            addView(glassAction("定时 关") { view ->
                countdownSeconds = if (countdownSeconds == 0) 3 else 0
                (view as? com.google.android.material.button.MaterialButton)?.text =
                    if (countdownSeconds == 0) "定时 关" else "定时 ${countdownSeconds}s"
            }, LinearLayout.LayoutParams(0, dp(56), 1f))
            addView(primaryAction("拍摄") { takePhoto() }, LinearLayout.LayoutParams(0, dp(64), 1.3f))
            addView(glassAction("闪光灯") { cycleFlash() }, LinearLayout.LayoutParams(0, dp(56), 1f))
        }
        root.addView(controls)
        setContentView(root)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
            .build()
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analysisExecutor) { analyzeFrame(it) }
        provider.unbindAll()
        try {
            provider.bindToLifecycle(this, CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                preview, imageCapture, analysis)
            previewView.scaleX = if (lensFacing == CameraSelector.LENS_FACING_FRONT) -1f else 1f
            status.text = "将面部放入椭圆，保持双肩水平"
        } catch (error: Exception) {
            status.text = "相机启动失败：${error.message}"
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyzeFrame(proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (mediaImage == null || !analyzing.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (face == null) {
                    updateFraming(0, "未检测到人脸，请正对镜头")
                } else {
                    val rotated = proxy.imageInfo.rotationDegrees % 180 != 0
                    val frameWidth = if (rotated) proxy.height else proxy.width
                    val frameHeight = if (rotated) proxy.width else proxy.height
                    val fraction = face.boundingBox.height().toFloat() / frameHeight
                    val center = face.boundingBox.centerX().toFloat() / frameWidth
                    val good = fraction in 0.16f..0.55f && center in 0.30f..0.70f
                    updateFraming(if (good) 1 else 2,
                        if (good) "构图良好，可以拍摄" else "请调整距离，并将人脸居中")
                }
            }
            .addOnFailureListener { updateFraming(2, "人脸检测暂不可用") }
            .addOnCompleteListener { analyzing.set(false); proxy.close() }
    }

    private fun updateFraming(state: Int, message: String) {
        runOnUiThread { overlay.framingState = state; status.text = message }
    }

    private fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT)
            CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
        bindCamera()
    }

    private fun cycleFlash() {
        val capture = imageCapture ?: return
        capture.flashMode = when (capture.flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
            else -> ImageCapture.FLASH_MODE_AUTO
        }
        val text = when (capture.flashMode) {
            ImageCapture.FLASH_MODE_ON -> "闪光灯已开启"
            ImageCapture.FLASH_MODE_OFF -> "闪光灯已关闭"
            else -> "闪光灯自动"
        }
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun takePhoto() {
        if (capturing) return
        capturing = true
        lifecycleScope.launch {
            if (countdownSeconds > 0) {
                for (remaining in countdownSeconds downTo 1) {
                    status.text = "$remaining 秒后拍摄"
                    delay(1000)
                }
            }
            val capture = imageCapture ?: run { capturing = false; return@launch }
            val file = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
            val options = ImageCapture.OutputFileOptions.Builder(file)
                .setMetadata(ImageCapture.Metadata().apply {
                    isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
                }).build()
            capture.takePicture(options, ContextCompat.getMainExecutor(this@CameraActivity),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        setResult(RESULT_OK, Intent().setData(Uri.fromFile(file)))
                        finish()
                    }
                    override fun onError(exception: ImageCaptureException) {
                        capturing = false
                        status.text = "拍摄失败：${exception.message}"
                    }
                })
        }
    }

    override fun onDestroy() {
        faceDetector.close()
        analysisExecutor.shutdown()
        super.onDestroy()
    }
}
