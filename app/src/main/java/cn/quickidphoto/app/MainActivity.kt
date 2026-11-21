package cn.quickidphoto.app

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private val processor = IdPhotoProcessor(this)
    private var original: BitmapHolder? = null
    private var face: FaceBox? = null
    private var spec = PhotoSpec.presets[0]
    private var panelSettings = EditorSettings()
    private var preview: EditorPreviewView? = null
    private var panelBatchMode = false
    private var batchItems = listOf<BatchItem>()
    private var batchIndex = 0
    private var batchPreviewImage: android.widget.ImageView? = null
    private var batchStatus: TextView? = null
    private var batchThumbs: LinearLayout? = null
    private var batchProgress: ProgressBar? = null
    private var batchRenderJob: Job? = null
    private var batchVersion = 0
    private var previewStatus: TextView? = null
    private var editorPanel: LinearLayout? = null
    private val editorTabButtons = mutableListOf<ThreeDButton>()
    private var rendered: android.graphics.Bitmap? = null
    private var renderJob: Job? = null
    private var renderVersion = 0
    private var lastSaved: Uri? = null
    private var pendingStorageAction: (() -> Unit)? = null

    // 页面状态：返回键/系统侧滑返回时回到上一页，而不是直接退出应用
    private var currentScreen = SCREEN_HOME
    private var lastBackPress = 0L

    companion object {
        private const val SCREEN_HOME = 0
        private const val SCREEN_EDITOR = 1
        private const val SCREEN_BATCH = 2
    }
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pendingStorageAction?.invoke() else
            Toast.makeText(this, "Android 8/9 需要存储权限才能保存到相册", Toast.LENGTH_LONG).show()
        pendingStorageAction = null
    }

    private val singlePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) openEditor(uri)
    }
    private val batchPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) showBatch(uris)
    }
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let { openEditor(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentScreen != SCREEN_HOME) {
                    // 编辑页/批量页：先返回首页，符合日常“返回上一页”习惯
                    renderJob?.cancel()
                    batchRenderJob?.cancel()
                    showHome()
                } else {
                    // 首页：连按两次才退出，避免侧滑误触直接退出应用
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastBackPress < 2200) {
                        isEnabled = false
                        finish()
                    } else {
                        lastBackPress = now
                        Toast.makeText(this@MainActivity, "再返回一次即可退出", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
        showHome()
    }

    private fun showHome() {
        currentScreen = SCREEN_HOME
        renderJob?.cancel()
        batchRenderJob?.cancel()
        panelBatchMode = false
        val scroll = ScrollView(this)
        val root = vertical(this).apply {
            setPadding(dp(20), dp(22), dp(20), dp(30))
            setBackgroundColor(Surface)
        }
        scroll.addView(root)
        val hero = MaterialCardView(this).apply {
            radius = dp(30).toFloat()
            cardElevation = dp(8).toFloat()
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(76, 96, 255), Color.rgb(139, 92, 246), Color.rgb(20, 184, 166)))
        }
        val heroContent = vertical(this).apply { setPadding(dp(24), dp(26), dp(24), dp(26)) }
        heroContent.addView(label("OFFLINE AI PHOTO STUDIO", 11f, Color.argb(210, 255, 255, 255)).apply {
            letterSpacing = 0.16f
        })
        heroContent.addView(label("快捷证件照", 34f, Color.WHITE).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = -0.03f
            setPadding(0, dp(8), 0, dp(6))
        })
        heroContent.addView(label("标准人像构图 · 发丝级边缘优化 · 高清多倍输出", 15f,
            Color.argb(225, 255, 255, 255)))
        hero.addView(heroContent)
        root.addView(hero, LinearLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(18) })
        root.addView(homeCard("拍摄证件照", "标准比例轮廓引导 · 实时人脸构图检测", "开始拍摄") {
            cameraLauncher.launch(Intent(this, CameraActivity::class.java).putExtra("spec", PhotoSpec.presets.indexOf(spec)))
        })
        root.addView(homeCard("从相册制作", "自动识别人脸，精细调整发丝边缘与高清细节", "选择单张照片") {
            singlePicker.launch("image/*")
        })
        root.addView(homeCard("批量处理", "统一规格、背景、构图和美颜参数，一次导出多张", "选择多张照片") {
            batchPicker.launch(arrayOf("image/*"))
        })
        root.addView(label("核心能力", 20f).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(22), 0, dp(10))
        })
        root.addView(label(
            "• MODNet + 人像分割双引擎抠图，背景清理与发丝保留\n" +
            "• 15 种规格与自定义像素 / DPI，人脸自动构图\n" +
            "• 纯色 / 渐变 / 透明背景，可视化取色\n" +
            "• Lanczos-3 高清输出 1x / 2x / 3x，KB 大小限制\n" +
            "• 美颜、白平衡、五尺寸排版照与批量预览出片\n\n" +
            "所有照片均在本机处理，不上传服务器。不同机构可能有特殊要求，请以官方最新公告为准。",
            14f, Muted
        ).apply { setLineSpacing(0f, 1.38f); setPadding(dp(6), 0, dp(6), 0) })
        setContentView(scroll)
    }

    private fun homeCard(title: String, subtitle: String, button: String, onClick: (View) -> Unit): View {
        val card = MaterialCardView(this).apply {
            radius = dp(26).toFloat()
            cardElevation = dp(3).toFloat()
            setCardBackgroundColor(Color.WHITE)
            strokeColor = Color.rgb(228, 233, 244)
            strokeWidth = dp(1)
            useCompatPadding = true
        }
        val content = vertical(this).apply { setPadding(dp(20), dp(20), dp(20), dp(20)) }
        content.addView(label(title, 20f, Ink).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        content.addView(label(subtitle, 13f, Muted).apply { setPadding(0, dp(7), 0, dp(14)) })
        content.addView(primaryAction(button, onClick), LinearLayout.LayoutParams(-1, dp(52)))
        card.addView(content)
        return card
    }

    private fun openEditor(uri: Uri) {
        val progress = vertical(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(30), dp(30), dp(30), dp(30))
            addView(ProgressBar(this@MainActivity))
            addView(label("正在读取照片并检测人脸…", 16f).apply { gravity = Gravity.CENTER })
        }
        setContentView(progress)
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { PhotoRepository.load(this@MainActivity, uri) }
                original = BitmapHolder(loaded)
                panelSettings = EditorSettings()
                panelBatchMode = false
                face = withContext(Dispatchers.IO) { processor.detectLargestFace(loaded) }
                showEditor()
                refreshPreview()
            } catch (error: Throwable) {
                Toast.makeText(this@MainActivity, "打开失败：${error.message}", Toast.LENGTH_LONG).show()
                showHome()
            }
        }
    }

    private fun showEditor() {
        currentScreen = SCREEN_EDITOR
        val root = vertical(this).apply { setBackgroundColor(Surface) }
        val toolbar = horizontal(this).apply { setPadding(dp(10), dp(8), dp(10), dp(6)) }
        toolbar.addView(action("‹ 首页") { showHome() }, LinearLayout.LayoutParams(0, dp(52), 1f))
        toolbar.addView(label("专业证件照编辑", 19f, Ink).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(52), 2f))
        toolbar.addView(primaryAction("保存") { savePhoto() }, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(toolbar)

        preview = EditorPreviewView(this).apply {
            onGesture = { dx, dy, scale ->
                panelSettings.horizontalOffset = (panelSettings.horizontalOffset + dx * 0.8f).coerceIn(-0.35f, 0.35f)
                panelSettings.headTopMargin = (panelSettings.headTopMargin + dy * 0.35f).coerceIn(0.01f, 0.35f)
                if (scale != 1f) panelSettings.faceScale = (panelSettings.faceScale * scale).coerceIn(0.30f, 0.88f)
                refreshPreview()
            }
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        previewStatus = label(if (face == null) "未检测到人脸，已使用居中裁剪；可手动拖动调整" else
            "已自动定位人脸 · 单指拖动微调 · 双指缩放脸部占比", 12f, Muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }
        root.addView(previewStatus)
        root.addView(editorTabs())
        editorPanel = vertical(this).apply { setPadding(dp(16), dp(10), dp(16), dp(12)) }
        val panelScroll = ScrollView(this).apply { addView(editorPanel) }
        val panelCard = MaterialCardView(this).apply {
            radius = dp(28).toFloat()
            cardElevation = dp(3).toFloat()
            setCardBackgroundColor(Color.WHITE)
            strokeColor = Color.rgb(230, 235, 245)
            strokeWidth = dp(1)
            addView(panelScroll)
        }
        root.addView(panelCard, LinearLayout.LayoutParams(-1, dp(260)).apply {
            leftMargin = dp(10); rightMargin = dp(10); bottomMargin = dp(10)
        })
        setContentView(root)
        showEditorTab(0)
    }

    private fun editorTabs(): View {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(8), 0, dp(8), dp(6))
        }
        val row = horizontal(this)
        editorTabButtons.clear()
        listOf("智能构图", "抠图换底", "美颜精修", "光影调色", "输出排版").forEachIndexed { index, name ->
            val button = action(name) { showEditorTab(index) }
            editorTabButtons.add(button)
            row.addView(button, LinearLayout.LayoutParams(dp(116), dp(48)).apply {
                leftMargin = dp(4); rightMargin = dp(4)
            })
        }
        scroll.addView(row)
        return scroll
    }

    private fun updateEditorTabs(selected: Int) {
        editorTabButtons.forEachIndexed { index, button ->
            button.applySelectedStyle(index == selected)
        }
    }

    private fun showEditorTab(index: Int) {
        updateEditorTabs(index)
        val panel = editorPanel ?: return
        panel.removeAllViews()
        when (index) {
            0 -> compositionPanel(panel)
            1 -> backgroundPanel(panel)
            2 -> beautyPanel(panel)
            3 -> tonePanel(panel)
            4 -> outputPanel(panel)
        }
    }

    private fun compositionPanel(panel: LinearLayout) {
        panel.addView(sectionTitle("尺寸规格"))
        val specRow = horizontal(this)
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            PhotoSpec.presets.map { "${it.name} · ${it.widthPx}×${it.heightPx}" })
        spinner.setSelection(PhotoSpec.presets.indexOf(spec).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                spec = PhotoSpec.presets[position]
                previewStatus?.text = "${spec.name} · ${spec.widthMm.toInt()}×${spec.heightMm.toInt()}mm · ${spec.widthPx}×${spec.heightPx}px · ${spec.dpi}DPI"
                refreshPreview()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        specRow.addView(spinner, LinearLayout.LayoutParams(0, dp(52), 1f))
        specRow.addView(action("自定义") { customSpecDialog() }, LinearLayout.LayoutParams(dp(100), dp(52)))
        panel.addView(specRow)
        panel.addView(sectionTitle("人脸构图微调"))
        slider(panel, "脸部占照片高度", 30, 88, (panelSettings.faceScale * 100).roundToInt(), "%") {
            panelSettings.faceScale = it / 100f
        }
        slider(panel, "头顶留白", 1, 35, (panelSettings.headTopMargin * 100).roundToInt(), "%") {
            panelSettings.headTopMargin = it / 100f
        }
        slider(panel, "水平位置", -35, 35, (panelSettings.horizontalOffset * 100).roundToInt(), "%") {
            panelSettings.horizontalOffset = it / 100f
        }
        val row = horizontal(this)
        row.addView(action("重新检测") { redetectFace() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(action("重置构图") {
            panelSettings.faceScale = 0.57f; panelSettings.headTopMargin = 0.10f; panelSettings.horizontalOffset = 0f
            showEditorTab(0); refreshPreview()
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(action("三分线") { preview?.showGrid = !(preview?.showGrid ?: false) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        panel.addView(row)
        if (!panelBatchMode) {
            val transformRow = horizontal(this)
            transformRow.addView(action("左旋 90°") { transformSource(-90f, false) }, LinearLayout.LayoutParams(0, dp(46), 1f))
            transformRow.addView(action("右旋 90°") { transformSource(90f, false) }, LinearLayout.LayoutParams(0, dp(46), 1f))
            transformRow.addView(action("水平镜像") { transformSource(0f, true) }, LinearLayout.LayoutParams(0, dp(46), 1f))
            panel.addView(transformRow)
        }
    }

    private fun backgroundPanel(panel: LinearLayout) {
        panel.addView(sectionTitle("纯色背景"))
        val colors = mutableListOf("保留原背景" to 0).apply { addAll(PhotoSpec.backgroundColors) }
        val scroll = HorizontalScrollView(this)
        val row = horizontal(this)
        colors.forEach { (name, color) ->
            row.addView(chip(name, !panelSettings.keepOriginalBackground && !panelSettings.transparentBackground &&
                !panelSettings.gradientBackground && panelSettings.backgroundColor == color) {
                panelSettings.keepOriginalBackground = name == "保留原背景"
                panelSettings.transparentBackground = false
                panelSettings.gradientBackground = false
                if (!panelSettings.keepOriginalBackground) panelSettings.backgroundColor = color
                showEditorTab(1); refreshPreview()
            }, LinearLayout.LayoutParams(dp(104), dp(48)))
        }
        row.addView(chip("透明 PNG", panelSettings.transparentBackground) {
            panelSettings.keepOriginalBackground = false
            panelSettings.transparentBackground = true
            panelSettings.gradientBackground = false
            panelSettings.png = true
            showEditorTab(1); refreshPreview()
        }, LinearLayout.LayoutParams(dp(112), dp(48)))
        row.addView(action("自定义颜色") { customColorDialog() }, LinearLayout.LayoutParams(dp(120), dp(48)))
        scroll.addView(row)
        panel.addView(scroll)

        panel.addView(sectionTitle("渐变背景"))
        val gradientRow = HorizontalScrollView(this)
        val gradientButtons = horizontal(this)
        val gradients = listOf(
            "美式蓝白" to (Color.rgb(58, 123, 213) to Color.rgb(178, 226, 248)),
            "晨曦暖橙" to (Color.rgb(255, 154, 86) to Color.rgb(255, 222, 180)),
            "暮紫" to (Color.rgb(142, 116, 255) to Color.rgb(222, 208, 255)),
            "青绿" to (Color.rgb(26, 188, 156) to Color.rgb(168, 233, 208))
        )
        gradients.forEach { (name, pair) ->
            gradientButtons.addView(chip(name, panelSettings.gradientBackground &&
                panelSettings.gradientStart == pair.first && panelSettings.gradientEnd == pair.second) {
                panelSettings.keepOriginalBackground = false
                panelSettings.transparentBackground = false
                panelSettings.gradientBackground = true
                panelSettings.gradientStart = pair.first
                panelSettings.gradientEnd = pair.second
                showEditorTab(1); refreshPreview()
            }, LinearLayout.LayoutParams(dp(116), dp(48)))
        }
        gradientButtons.addView(action("自定义渐变") { customGradientDialog() }, LinearLayout.LayoutParams(dp(130), dp(48)))
        gradientRow.addView(gradientButtons)
        panel.addView(gradientRow)

        panel.addView(sectionTitle("边缘细节"))

        slider(panel, "边缘柔化", 0, 100, panelSettings.edgeFeather, "") { panelSettings.edgeFeather = it }
        slider(panel, "轮廓收缩 / 扩展", -50, 50, panelSettings.edgeShift, "") { panelSettings.edgeShift = it }
        slider(panel, "发丝细节保留", 0, 100, panelSettings.hairDetail, "") { panelSettings.hairDetail = it }
        panel.addView(label("发丝细节越高，头发边缘越锐利通透；柔化则让过渡更自然。透明背景请使用 PNG。", 12f, Muted))
    }

    private fun beautyPanel(panel: LinearLayout) {
        panel.addView(sectionTitle("快速预设"))
        val presets = horizontal(this)
        fun isPreset(s: Int, w: Int, r: Int, sh: Int) =
            panelSettings.smooth == s && panelSettings.whiten == w && panelSettings.rosy == r && panelSettings.sharpen == sh
        presets.addView(chip("自然", isPreset(28, 14, 8, 14)) { applyBeautyPreset(28, 14, 8, 14) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        presets.addView(chip("清透", isPreset(48, 26, 14, 22)) { applyBeautyPreset(48, 26, 14, 22) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        presets.addView(chip("精致", isPreset(68, 36, 22, 30)) { applyBeautyPreset(68, 36, 22, 30) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        presets.addView(chip("重置", isPreset(0, 0, 0, 0)) { applyBeautyPreset(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        panel.addView(presets)
        panel.addView(sectionTitle("手动微调"))
        slider(panel, "美颜总强度", 0, 100, panelSettings.beautyIntensity, "%") { panelSettings.beautyIntensity = it }
        slider(panel, "皮肤平滑（保留非皮肤区域）", 0, 100, panelSettings.smooth, "") { panelSettings.smooth = it }
        slider(panel, "肤色提亮", 0, 100, panelSettings.whiten, "") { panelSettings.whiten = it }
        slider(panel, "气色红润", 0, 100, panelSettings.rosy, "") { panelSettings.rosy = it }
        slider(panel, "细节锐化", 0, 100, panelSettings.sharpen, "") { panelSettings.sharpen = it }
        panel.addView(label("建议适度美颜；正式证件照不得改变五官和真实面貌。", 12f, Muted))
    }

    private fun tonePanel(panel: LinearLayout) {
        panel.addView(sectionTitle("快捷操作"))
        val row = horizontal(this)
        row.addView(action("自动白平衡") { autoWhiteBalance() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(action("自然增强") {
            panelSettings.brightness = 6; panelSettings.contrast = 8; panelSettings.saturation = 4
            showEditorTab(3); refreshPreview()
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(action("全部归零") {
            panelSettings.brightness = 0; panelSettings.contrast = 0; panelSettings.saturation = 0
            panelSettings.temperature = 0; panelSettings.tint = 0; panelSettings.shadows = 0; panelSettings.highlights = 0
            showEditorTab(3); refreshPreview()
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        panel.addView(row)
        panel.addView(sectionTitle("光影参数"))
        slider(panel, "亮度", -100, 100, panelSettings.brightness, "") { panelSettings.brightness = it }
        slider(panel, "对比度", -100, 100, panelSettings.contrast, "") { panelSettings.contrast = it }
        slider(panel, "饱和度", -100, 100, panelSettings.saturation, "") { panelSettings.saturation = it }
        slider(panel, "色温（冷 ↔ 暖）", -100, 100, panelSettings.temperature, "") { panelSettings.temperature = it }
        slider(panel, "色调（绿 ↔ 品红）", -100, 100, panelSettings.tint, "") { panelSettings.tint = it }
        slider(panel, "阴影", -100, 100, panelSettings.shadows, "") { panelSettings.shadows = it }
        slider(panel, "高光", -100, 100, panelSettings.highlights, "") { panelSettings.highlights = it }
    }

    private fun outputPanel(panel: LinearLayout) {
        val scale = panelSettings.outputScale.coerceIn(1, 3)
        panel.addView(label("${spec.name} · 输出 ${spec.widthPx * scale}×${spec.heightPx * scale}px · ${spec.dpi * scale} DPI", 15f))
        panel.addView(sectionTitle("高清倍率"))
        val hd = horizontal(this)
        listOf(1 to "标准 1x", 2 to "高清 2x", 3 to "超清 3x").forEach { (value, name) ->
            hd.addView(chip(name, panelSettings.outputScale == value) {
                panelSettings.outputScale = value; showEditorTab(4)
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        panel.addView(hd)
        panel.addView(label("高清倍率在标准尺寸基础上放大输出，Lanczos-3 重采样保留细节。", 12f, Muted))
        panel.addView(sectionTitle("格式与保存"))
        val format = horizontal(this)
        format.addView(chip("JPG", !panelSettings.png) { panelSettings.png = false; showEditorTab(4) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        format.addView(chip("PNG", panelSettings.png) { panelSettings.png = true; showEditorTab(4) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        format.addView(primaryAction(if (panelBatchMode) "批量保存" else "保存到相册") {
            if (panelBatchMode) startBatchSave() else savePhoto()
        }, LinearLayout.LayoutParams(0, dp(48), 1.4f))
        panel.addView(format)
        slider(panel, "JPG 质量", 60, 100, panelSettings.jpegQuality, "") { panelSettings.jpegQuality = it }
        slider(panel, "文件大小上限", 0, 1024, panelSettings.targetKb, " KB") { panelSettings.targetKb = it }
        panel.addView(label("大小上限仅对 JPG 生效；设为 0 表示不限制。报名系统要求 20–200KB 时可直接设置。", 12f, Muted))
        panel.addView(sectionTitle("排版照（300DPI 含裁切线）"))
        val layoutRow = HorizontalScrollView(this)
        val layoutButtons = horizontal(this)
        LayoutSheetGenerator.layouts.forEachIndexed { index, layout ->
            layoutButtons.addView(chip(layout.first, panelSettings.printLayout == index) {
                panelSettings.printLayout = index
                showEditorTab(4)
            }, LinearLayout.LayoutParams(dp(110), dp(48)))
        }
        layoutRow.addView(layoutButtons)
        panel.addView(layoutRow)
        val printBg = horizontal(this)
        listOf("排版白底" to Color.WHITE, "排版浅灰" to Color.rgb(238, 240, 244), "排版米白" to Color.rgb(250, 246, 238)).forEach { (name, color) ->
            printBg.addView(chip(name, panelSettings.printBackgroundColor == color) {
                panelSettings.printBackgroundColor = color
                showEditorTab(4)
            }, LinearLayout.LayoutParams(0, dp(46), 1f))
        }
        panel.addView(printBg)
        val export = horizontal(this)
        export.addView(action("生成排版照") { savePrintSheet() }, LinearLayout.LayoutParams(0, dp(50), 1f))
        export.addView(action("分享成品") { shareLastSaved() }, LinearLayout.LayoutParams(0, dp(50), 1f))
        panel.addView(export)
        panel.addView(label("六寸排版为 1800×1200px / 300DPI，含裁切辅助线。打印时请关闭“适应页面”，选择原始尺寸。", 12f, Color.GRAY))
    }

    private fun slider(
        panel: LinearLayout, name: String, min: Int, max: Int, value: Int,
        unit: String, onChanged: (Int) -> Unit
    ) {
        val caption = label("$name：$value$unit", 13f)
        panel.addView(caption)
        val seek = SeekBar(this).apply {
            this.max = max - min
            progress = value - min
        }
        modernSlider(seek)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val current = min + progress
                caption.text = "$name：$current$unit"
                if (fromUser) onChanged(current)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) { onChanged(min + seek.progress); refreshPreview() }
        })
        panel.addView(seek, LinearLayout.LayoutParams(-1, dp(38)))
    }

    private fun refreshPreview() {
        if (panelBatchMode) { refreshBatchPreview(); return }
        val source = original?.bitmap ?: return
        val targetPreview = preview ?: return
        val version = ++renderVersion
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            delay(if (panelSettings.keepOriginalBackground) 50 else 180)
            try {
                val output = withContext(Dispatchers.Default) {
                    processor.render(source, spec, panelSettings.copyValues(), face, preview = true)
                }
                if (version == renderVersion) {
                    rendered = output
                    targetPreview.bitmap = output
                }
            } catch (error: Throwable) {
                if (version == renderVersion) {
                    previewStatus?.text = "处理失败：${error.message}。可尝试保留原背景或更换照片"
                }
            }
        }
    }

    private fun redetectFace() {
        if (panelBatchMode) {
            val item = batchItems.getOrNull(batchIndex) ?: return
            val source = item.bitmap ?: return
            lifecycleScope.launch {
                item.face = withContext(Dispatchers.IO) {
                    try { processor.detectLargestFace(source) } catch (_: Throwable) { null }
                }
                batchStatus?.text = if (item.face == null) "未检测到人脸，请用其他设置微调构图" else "第 ${batchIndex + 1} 张人脸已重新定位"
                refreshPreview()
            }
            return
        }
        val source = original?.bitmap ?: return
        previewStatus?.text = "正在重新检测人脸…"
        lifecycleScope.launch {
            face = withContext(Dispatchers.IO) {
                try { processor.detectLargestFace(source) } catch (_: Throwable) { null }
            }
            previewStatus?.text = if (face == null) "未检测到人脸，请手动拖动或双指调整" else "人脸定位已更新"
            refreshPreview()
        }
    }

    private fun transformSource(degrees: Float, mirror: Boolean) {
        val holder = original ?: return
        lifecycleScope.launch {
            val transformed = withContext(Dispatchers.Default) { processor.transform(holder.bitmap, degrees, mirror) }
            original = BitmapHolder(transformed)
            face = withContext(Dispatchers.IO) {
                try { processor.detectLargestFace(transformed) } catch (_: Throwable) { null }
            }
            refreshPreview()
        }
    }

    private fun applyBeautyPreset(smooth: Int, whiten: Int, rosy: Int, sharpen: Int) {
        panelSettings.smooth = smooth; panelSettings.whiten = whiten; panelSettings.rosy = rosy; panelSettings.sharpen = sharpen
        // 预设必须恢复总强度，否则用户曾把总强度调到 0 时所有预设看起来都“没效果”
        panelSettings.beautyIntensity = 100
        showEditorTab(2); refreshPreview()
    }

    private fun autoWhiteBalance() {
        val source = if (panelBatchMode) batchItems.getOrNull(batchIndex)?.bitmap else original?.bitmap
        source ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { processor.suggestWhiteBalance(source) }
            panelSettings.temperature = result.first; panelSettings.tint = result.second
            showEditorTab(3); refreshPreview()
        }
    }

    private fun customSpecDialog() {
        val form = vertical(this).apply { setPadding(dp(24), dp(8), dp(24), 0) }
        val width = numberInput("宽度 px", spec.widthPx.toString())
        val height = numberInput("高度 px", spec.heightPx.toString())
        val dpi = numberInput("DPI", spec.dpi.toString())
        form.addView(width); form.addView(height); form.addView(dpi)
        this@MainActivity.threeDDialog().setTitle("自定义输出规格").setView(form)
            .setPositiveButton("应用") { _, _ ->
                val w = width.text.toString().toIntOrNull()
                val h = height.text.toString().toIntOrNull()
                val density = dpi.text.toString().toIntOrNull()
                if (w == null || h == null || density == null || w !in 32..4000 || h !in 32..4000 || density !in 72..1200) {
                    Toast.makeText(this, "请输入有效范围：像素 32–4000，DPI 72–1200", Toast.LENGTH_LONG).show()
                } else {
                    spec = PhotoSpec("自定义", w * 25.4f / density, h * 25.4f / density, w, h, density)
                    previewStatus?.text = "自定义规格 · ${w}×${h}px · ${density}DPI"
                    refreshPreview()
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun customColorDialog() {
        val picker = VisualColorPickerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, dp(360))
            setColor(if (panelSettings.keepOriginalBackground) Color.rgb(67, 142, 219) else panelSettings.backgroundColor)
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(18), dp(6), dp(18), 0)
            addView(picker)
        }
        this@MainActivity.threeDDialog().setTitle("可视化选择背景颜色").setView(container)
            .setPositiveButton("应用") { _, _ ->
                panelSettings.backgroundColor = picker.selectedColor
                panelSettings.keepOriginalBackground = false
                panelSettings.transparentBackground = false
                refreshPreview()
            }.setNegativeButton("取消", null).show()
    }

    /** Two-picker gradient dialog: choose top and bottom colors visually. */
    private fun customGradientDialog() {
        val topPicker = VisualColorPickerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(300))
            setColor(panelSettings.gradientStart)
        }
        val bottomPicker = VisualColorPickerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(300))
            setColor(panelSettings.gradientEnd)
        }
        val form = vertical(this).apply {
            setPadding(dp(18), dp(6), dp(18), 0)
            addView(label("上半部分颜色", 13f, Muted))
            addView(topPicker)
            addView(label("下半部分颜色", 13f, Muted))
            addView(bottomPicker)
        }
        this@MainActivity.threeDDialog().setTitle("自定义渐变背景").setView(form)
            .setPositiveButton("应用") { _, _ ->
                panelSettings.keepOriginalBackground = false
                panelSettings.transparentBackground = false
                panelSettings.gradientBackground = true
                panelSettings.gradientStart = topPicker.selectedColor
                panelSettings.gradientEnd = bottomPicker.selectedColor
                refreshPreview()
            }.setNegativeButton("取消", null).show()
    }

    private fun numberInput(hint: String, value: String): EditText = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = InputType.TYPE_CLASS_NUMBER
    }

    private fun withStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingStorageAction = action
            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun savePhoto() = withStoragePermission { savePhotoNow() }

    private fun savePhotoNow() {
        val source = original?.bitmap ?: return
        Toast.makeText(this, "正在生成并保存…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val output = processor.render(source, spec, panelSettings.copyValues(), face)
                    val uri = PhotoRepository.save(this@MainActivity, output, spec, panelSettings)
                    output to uri
                }
                rendered = result.first
                preview?.bitmap = result.first
                lastSaved = result.second
                this@MainActivity.threeDDialog().setTitle("已保存到相册")
                    .setMessage("Pictures/快捷证件照\n${spec.widthPx}×${spec.heightPx}px")
                    .setPositiveButton("完成", null)
                    .setNeutralButton("分享") { _, _ -> shareLastSaved() }.show()
            } catch (error: Throwable) {
                Toast.makeText(this@MainActivity, "保存失败：${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun savePrintSheet() = withStoragePermission { savePrintSheetNow() }

    private fun savePrintSheetNow() {
        val source = original?.bitmap ?: return
        lifecycleScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val output = processor.render(source, spec, panelSettings.copyValues(), face)
                    val layout = LayoutSheetGenerator.layouts[panelSettings.printLayout.coerceIn(0, LayoutSheetGenerator.layouts.lastIndex)]
                    val sheet = LayoutSheetGenerator.generate(output, panelSettings.printLayout, panelSettings.printBackgroundColor)
                    val sheetSpec = PhotoSpec("${layout.first}排版", layout.second * 25.4f / 300, layout.third * 25.4f / 300, layout.second, layout.third)
                    PhotoRepository.save(this@MainActivity, sheet, sheetSpec, panelSettings)
                }
                lastSaved = uri
                Toast.makeText(this@MainActivity, "排版照已保存到相册", Toast.LENGTH_LONG).show()
            } catch (error: Throwable) {
                Toast.makeText(this@MainActivity, "排版保存失败：${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun shareLastSaved() {
        val uri = lastSaved
        if (uri == null) {
            Toast.makeText(this, "请先保存成品，再分享", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = if (panelSettings.png || panelSettings.transparentBackground) "image/png" else "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "分享证件照"))
    }

    private fun showBatch(uris: List<Uri>) {
        currentScreen = SCREEN_BATCH
        renderJob?.cancel()
        batchRenderJob?.cancel()
        batchItems = uris.map { BatchItem(it) }
        batchIndex = 0
        batchVersion++
        panelBatchMode = true
        panelSettings = EditorSettings(keepOriginalBackground = false)
        spec = PhotoSpec.presets[0]

        val root = vertical(this).apply { setBackgroundColor(Surface) }
        val toolbar = horizontal(this).apply { setPadding(dp(10), dp(8), dp(10), dp(6)) }
        toolbar.addView(action("‹ 首页") { showHome() }, LinearLayout.LayoutParams(0, dp(52), 1f))
        toolbar.addView(label("批量制作 · ${uris.size} 张", 18f, Ink).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(52), 2f))
        toolbar.addView(primaryAction("开始出片") { startBatchSave() }, LinearLayout.LayoutParams(0, dp(52), 1.25f))
        root.addView(toolbar)

        // 大图预览区：点击缩略图或调整参数后自动刷新
        val previewArea = FrameLayout(this).apply { setBackgroundColor(Color.rgb(18, 22, 36)) }
        batchPreviewImage = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        previewArea.addView(batchPreviewImage, FrameLayout.LayoutParams(-1, -1))
        root.addView(previewArea, LinearLayout.LayoutParams(-1, 0, 1f))

        batchStatus = label("正在读取照片…", 12f, Muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        root.addView(batchStatus)

        val thumbScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        batchThumbs = horizontal(this)
        thumbScroll.addView(batchThumbs)
        root.addView(thumbScroll, LinearLayout.LayoutParams(-1, dp(92)))

        batchProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = uris.size
        }
        root.addView(batchProgress, LinearLayout.LayoutParams(-1, dp(14)).apply {
            leftMargin = dp(12); rightMargin = dp(12)
        })

        // 与单张制作完全相同的 6 组设置面板，批量统一应用到每一张照片
        root.addView(editorTabs())
        editorPanel = vertical(this).apply { setPadding(dp(16), dp(10), dp(16), dp(12)) }
        val panelScroll = ScrollView(this).apply { addView(editorPanel) }
        val panelCard = MaterialCardView(this).apply {
            radius = dp(26).toFloat()
            cardElevation = dp(3).toFloat()
            setCardBackgroundColor(Color.WHITE)
            strokeColor = Color.rgb(230, 235, 245)
            strokeWidth = dp(1)
            addView(panelScroll)
        }
        root.addView(panelCard, LinearLayout.LayoutParams(-1, dp(230)).apply {
            leftMargin = dp(10); rightMargin = dp(10); bottomMargin = dp(8)
        })
        setContentView(root)
        showEditorTab(0)

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                batchItems.forEach { item ->
                    try {
                        val bitmap = PhotoRepository.load(this@MainActivity, item.uri, 1600)
                        item.bitmap = bitmap
                        item.face = processor.detectLargestFace(bitmap)
                    } catch (error: Throwable) {
                        item.status = "读取失败：${error.message}"
                    }
                    withContext(Dispatchers.Main) {
                        batchStatus?.text = "已载入 ${batchItems.count { it.bitmap != null }}/${batchItems.size} 张 · 点击缩略图切换预览"
                    }
                }
            }
            rebuildBatchThumbs()
            refreshPreview()
        }
    }

    private fun refreshBatchPreview() {
        val item = batchItems.getOrNull(batchIndex) ?: return
        val source = item.bitmap ?: run {
            batchStatus?.text = "此照片读取失败，请返回重新选择"
            return
        }
        val version = ++batchVersion
        batchRenderJob?.cancel()
        batchStatus?.text = "正在预览第 ${batchIndex + 1} 张…"
        batchRenderJob = lifecycleScope.launch {
            delay(if (panelSettings.keepOriginalBackground) 30 else 120)
            try {
                val output = withContext(Dispatchers.Default) {
                    processor.render(source, spec, panelSettings.copyValues(), item.face, preview = true)
                }
                if (version == batchVersion) {
                    item.previewResult = output
                    batchPreviewImage?.setImageBitmap(output)
                    batchStatus?.text = "预览第 ${batchIndex + 1}/${batchItems.size} 张 · 修改设置后自动刷新 · 可点击缩略图切换"
                    rebuildBatchThumbs()
                }
            } catch (error: Throwable) {
                if (version == batchVersion) batchStatus?.text = "预览失败：${error.message}"
            }
        }
    }

    private fun rebuildBatchThumbs() {
        val row = batchThumbs ?: return
        row.removeAllViews()
        batchItems.forEachIndexed { index, item ->
            val thumb = android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(if (index == batchIndex) Color.rgb(99, 122, 255) else Color.rgb(215, 222, 238))
                setPadding(dp(2), dp(2), dp(2), dp(2))
                setImageBitmap(item.previewResult ?: item.bitmap)
                alpha = if (item.bitmap == null) 0.4f else 1f
            }
            row.addView(thumb, LinearLayout.LayoutParams(dp(76), dp(76)).apply {
                leftMargin = dp(4); rightMargin = dp(4)
            })
            thumb.setOnClickListener {
                batchIndex = index
                rebuildBatchThumbs()
                refreshPreview()
            }
        }
    }

    private fun startBatchSave() = withStoragePermission { startBatchSaveNow() }

    private fun startBatchSaveNow() {
        if (batchItems.isEmpty()) return
        val total = batchItems.size
        batchProgress?.max = total
        lifecycleScope.launch {
            var success = 0
            var failed = 0
            batchItems.forEachIndexed { index, item ->
                batchStatus?.text = "正在导出 ${index + 1}/$total …"
                try {
                    withContext(Dispatchers.IO) {
                        // 导出阶段重新以最高分辨率载入原图，保证成品清晰度
                        val bitmap = PhotoRepository.load(this@MainActivity, item.uri)
                        val detected = processor.detectLargestFace(bitmap)
                        val output = processor.render(bitmap, spec, panelSettings.copyValues(), detected)
                        PhotoRepository.save(this@MainActivity, output, spec, panelSettings, "%02d".format(index + 1))
                    }
                    success++
                } catch (error: Throwable) {
                    failed++
                    item.status = "导出失败：${error.message}"
                }
                batchProgress?.progress = index + 1
            }
            batchStatus?.text = "完成：成功 $success 张，失败 $failed 张 · Pictures/快捷证件照"
            this@MainActivity.threeDDialog().setTitle("批量导出完成")
                .setMessage("成功 $success 张，失败 $failed 张\n保存位置：Pictures/快捷证件照")
                .setPositiveButton("完成", null).show()
        }
    }

    private class BatchItem(val uri: Uri) {
        var bitmap: android.graphics.Bitmap? = null
        var face: FaceBox? = null
        var previewResult: android.graphics.Bitmap? = null
        var status: String = "待处理"
    }

    private data class BitmapHolder(val bitmap: android.graphics.Bitmap)
}
