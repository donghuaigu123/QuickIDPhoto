package cn.quickidphoto.app

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
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
    private var settings = EditorSettings()
    private var preview: EditorPreviewView? = null
    private var previewStatus: TextView? = null
    private var editorPanel: LinearLayout? = null
    private val editorTabButtons = mutableListOf<MaterialButton>()
    private var rendered: android.graphics.Bitmap? = null
    private var renderJob: Job? = null
    private var renderVersion = 0
    private var lastSaved: Uri? = null
    private var pendingStorageAction: (() -> Unit)? = null
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
        showHome()
    }

    private fun showHome() {
        renderJob?.cancel()
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
            "• MODNet 发丝级 AI 抠图，背景色去污染\n" +
            "• 15 种规格与自定义像素 / DPI，人脸自动构图\n" +
            "• 纯色 / 渐变 / 透明背景，可视化取色\n" +
            "• Lanczos-3 高清输出 1x / 2x / 3x，KB 大小限制\n" +
            "• 美颜、白平衡、五尺寸排版照与裁切线\n\n" +
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
                settings = EditorSettings()
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
                settings.horizontalOffset = (settings.horizontalOffset + dx * 0.8f).coerceIn(-0.35f, 0.35f)
                settings.headTopMargin = (settings.headTopMargin + dy * 0.35f).coerceIn(0.01f, 0.35f)
                if (scale != 1f) settings.faceScale = (settings.faceScale * scale).coerceIn(0.30f, 0.88f)
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
            if (index == selected) {
                button.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Primary, PrimaryDeep)).apply { cornerRadius = dp(20).toFloat() }
                button.setTextColor(Color.WHITE)
                button.elevation = dp(4).toFloat()
            } else {
                button.background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor(Color.WHITE)
                    setStroke(dp(1), Color.rgb(224, 230, 242))
                }
                button.setTextColor(Muted)
                button.elevation = 0f
            }
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
        slider(panel, "脸部占照片高度", 30, 88, (settings.faceScale * 100).roundToInt(), "%") {
            settings.faceScale = it / 100f
        }
        slider(panel, "头顶留白", 1, 35, (settings.headTopMargin * 100).roundToInt(), "%") {
            settings.headTopMargin = it / 100f
        }
        slider(panel, "水平位置", -35, 35, (settings.horizontalOffset * 100).roundToInt(), "%") {
            settings.horizontalOffset = it / 100f
        }
        val row = horizontal(this)
        row.addView(action("重新检测") { redetectFace() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(action("重置构图") {
            settings.faceScale = 0.57f; settings.headTopMargin = 0.10f; settings.horizontalOffset = 0f
            showEditorTab(0); refreshPreview()
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(action("三分线") { preview?.showGrid = !(preview?.showGrid ?: false) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        panel.addView(row)
        val transformRow = horizontal(this)
        transformRow.addView(action("左旋 90°") { transformSource(-90f, false) }, LinearLayout.LayoutParams(0, dp(46), 1f))
        transformRow.addView(action("右旋 90°") { transformSource(90f, false) }, LinearLayout.LayoutParams(0, dp(46), 1f))
        transformRow.addView(action("水平镜像") { transformSource(0f, true) }, LinearLayout.LayoutParams(0, dp(46), 1f))
        panel.addView(transformRow)
    }

    private fun backgroundPanel(panel: LinearLayout) {
        panel.addView(sectionTitle("纯色背景"))
        val colors = mutableListOf("保留原背景" to 0).apply { addAll(PhotoSpec.backgroundColors) }
        val scroll = HorizontalScrollView(this)
        val row = horizontal(this)
        colors.forEach { (name, color) ->
            row.addView(chip(name, !settings.keepOriginalBackground && !settings.transparentBackground &&
                !settings.gradientBackground && settings.backgroundColor == color) {
                settings.keepOriginalBackground = name == "保留原背景"
                settings.transparentBackground = false
                settings.gradientBackground = false
                if (!settings.keepOriginalBackground) settings.backgroundColor = color
                showEditorTab(1); refreshPreview()
            }, LinearLayout.LayoutParams(dp(104), dp(48)))
        }
        row.addView(chip("透明 PNG", settings.transparentBackground) {
            settings.keepOriginalBackground = false
            settings.transparentBackground = true
            settings.gradientBackground = false
            settings.png = true
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
            gradientButtons.addView(chip(name, settings.gradientBackground &&
                settings.gradientStart == pair.first && settings.gradientEnd == pair.second) {
                settings.keepOriginalBackground = false
                settings.transparentBackground = false
                settings.gradientBackground = true
                settings.gradientStart = pair.first
                settings.gradientEnd = pair.second
                showEditorTab(1); refreshPreview()
            }, LinearLayout.LayoutParams(dp(116), dp(48)))
        }
        gradientButtons.addView(action("自定义渐变") { customGradientDialog() }, LinearLayout.LayoutParams(dp(130), dp(48)))
        gradientRow.addView(gradientButtons)
        panel.addView(gradientRow)

        panel.addView(sectionTitle("边缘细节"))

        slider(panel, "边缘柔化", 0, 100, settings.edgeFeather, "") { settings.edgeFeather = it }
        slider(panel, "轮廓收缩 / 扩展", -50, 50, settings.edgeShift, "") { settings.edgeShift = it }
        slider(panel, "发丝细节保留", 0, 100, settings.hairDetail, "") { settings.hairDetail = it }
        panel.addView(label("发丝细节越高，头发边缘越锐利通透；柔化则让过渡更自然。透明背景请使用 PNG。", 12f, Muted))
    }

    private fun beautyPanel(panel: LinearLayout) {
        panel.addView(sectionTitle("快速预设"))
        val presets = horizontal(this)
        fun isPreset(s: Int, w: Int, r: Int, sh: Int) =
            settings.smooth == s && settings.whiten == w && settings.rosy == r && settings.sharpen == sh
        presets.addView(chip("自然", isPreset(28, 14, 8, 14)) { applyBeautyPreset(28, 14, 8, 14) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        presets.addView(chip("清透", isPreset(48, 26, 14, 22)) { applyBeautyPreset(48, 26, 14, 22) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        presets.addView(chip("精致", isPreset(68, 36, 22, 30)) { applyBeautyPreset(68, 36, 22, 30) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        presets.addView(chip("重置", isPreset(0, 0, 0, 0)) { applyBeautyPreset(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        panel.addView(presets)
        panel.addView(sectionTitle("手动微调"))
        slider(panel, "美颜总强度", 0, 100, settings.beautyIntensity, "%") { settings.beautyIntensity = it }
        slider(panel, "皮肤平滑（保留非皮肤区域）", 0, 100, settings.smooth, "") { settings.smooth = it }
        slider(panel, "肤色提亮", 0, 100, settings.whiten, "") { settings.whiten = it }
        slider(panel, "气色红润", 0, 100, settings.rosy, "") { settings.rosy = it }
        slider(panel, "细节锐化", 0, 100, settings.sharpen, "") { settings.sharpen = it }
        panel.addView(label("建议适度美颜；正式证件照不得改变五官和真实面貌。", 12f, Muted))
    }

    private fun tonePanel(panel: LinearLayout) {
        panel.addView(sectionTitle("快捷操作"))
        val row = horizontal(this)
        row.addView(action("自动白平衡") { autoWhiteBalance() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(action("自然增强") {
            settings.brightness = 6; settings.contrast = 8; settings.saturation = 4
            showEditorTab(3); refreshPreview()
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(action("全部归零") {
            settings.brightness = 0; settings.contrast = 0; settings.saturation = 0
            settings.temperature = 0; settings.tint = 0; settings.shadows = 0; settings.highlights = 0
            showEditorTab(3); refreshPreview()
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        panel.addView(row)
        panel.addView(sectionTitle("光影参数"))
        slider(panel, "亮度", -100, 100, settings.brightness, "") { settings.brightness = it }
        slider(panel, "对比度", -100, 100, settings.contrast, "") { settings.contrast = it }
        slider(panel, "饱和度", -100, 100, settings.saturation, "") { settings.saturation = it }
        slider(panel, "色温（冷 ↔ 暖）", -100, 100, settings.temperature, "") { settings.temperature = it }
        slider(panel, "色调（绿 ↔ 品红）", -100, 100, settings.tint, "") { settings.tint = it }
        slider(panel, "阴影", -100, 100, settings.shadows, "") { settings.shadows = it }
        slider(panel, "高光", -100, 100, settings.highlights, "") { settings.highlights = it }
    }

    private fun outputPanel(panel: LinearLayout) {
        val scale = settings.outputScale.coerceIn(1, 3)
        panel.addView(label("${spec.name} · 输出 ${spec.widthPx * scale}×${spec.heightPx * scale}px · ${spec.dpi * scale} DPI", 15f))
        panel.addView(sectionTitle("高清倍率"))
        val hd = horizontal(this)
        listOf(1 to "标准 1x", 2 to "高清 2x", 3 to "超清 3x").forEach { (value, name) ->
            hd.addView(chip(name, settings.outputScale == value) {
                settings.outputScale = value; showEditorTab(4)
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        panel.addView(hd)
        panel.addView(label("高清倍率在标准尺寸基础上放大输出，Lanczos-3 重采样保留细节。", 12f, Muted))
        panel.addView(sectionTitle("格式与保存"))
        val format = horizontal(this)
        format.addView(chip("JPG", !settings.png) { settings.png = false; showEditorTab(4) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        format.addView(chip("PNG", settings.png) { settings.png = true; showEditorTab(4) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        format.addView(primaryAction("保存到相册") { savePhoto() }, LinearLayout.LayoutParams(0, dp(48), 1.4f))
        panel.addView(format)
        slider(panel, "JPG 质量", 60, 100, settings.jpegQuality, "") { settings.jpegQuality = it }
        slider(panel, "文件大小上限", 0, 1024, settings.targetKb, " KB") { settings.targetKb = it }
        panel.addView(label("大小上限仅对 JPG 生效；设为 0 表示不限制。报名系统要求 20–200KB 时可直接设置。", 12f, Muted))
        panel.addView(sectionTitle("排版照（300DPI 含裁切线）"))
        val layoutRow = HorizontalScrollView(this)
        val layoutButtons = horizontal(this)
        LayoutSheetGenerator.layouts.forEachIndexed { index, layout ->
            layoutButtons.addView(chip(layout.first, settings.printLayout == index) {
                settings.printLayout = index
                showEditorTab(4)
            }, LinearLayout.LayoutParams(dp(110), dp(48)))
        }
        layoutRow.addView(layoutButtons)
        panel.addView(layoutRow)
        val printBg = horizontal(this)
        listOf("排版白底" to Color.WHITE, "排版浅灰" to Color.rgb(238, 240, 244), "排版米白" to Color.rgb(250, 246, 238)).forEach { (name, color) ->
            printBg.addView(chip(name, settings.printBackgroundColor == color) {
                settings.printBackgroundColor = color
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
        val source = original?.bitmap ?: return
        val targetPreview = preview ?: return
        val version = ++renderVersion
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            delay(if (settings.keepOriginalBackground) 50 else 180)
            try {
                val output = withContext(Dispatchers.Default) {
                    processor.render(source, spec, settings.copyValues(), face, preview = true)
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
        settings.smooth = smooth; settings.whiten = whiten; settings.rosy = rosy; settings.sharpen = sharpen
        showEditorTab(2); refreshPreview()
    }

    private fun autoWhiteBalance() {
        val source = original?.bitmap ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { processor.suggestWhiteBalance(source) }
            settings.temperature = result.first; settings.tint = result.second
            showEditorTab(3); refreshPreview()
        }
    }

    private fun customSpecDialog() {
        val form = vertical(this).apply { setPadding(dp(24), dp(8), dp(24), 0) }
        val width = numberInput("宽度 px", spec.widthPx.toString())
        val height = numberInput("高度 px", spec.heightPx.toString())
        val dpi = numberInput("DPI", spec.dpi.toString())
        form.addView(width); form.addView(height); form.addView(dpi)
        AlertDialog.Builder(this).setTitle("自定义输出规格").setView(form)
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
            setColor(if (settings.keepOriginalBackground) Color.rgb(67, 142, 219) else settings.backgroundColor)
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(18), dp(6), dp(18), 0)
            addView(picker)
        }
        AlertDialog.Builder(this).setTitle("可视化选择背景颜色").setView(container)
            .setPositiveButton("应用") { _, _ ->
                settings.backgroundColor = picker.selectedColor
                settings.keepOriginalBackground = false
                settings.transparentBackground = false
                refreshPreview()
            }.setNegativeButton("取消", null).show()
    }

    /** Two-picker gradient dialog: choose top and bottom colors visually. */
    private fun customGradientDialog() {
        val topPicker = VisualColorPickerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(300))
            setColor(settings.gradientStart)
        }
        val bottomPicker = VisualColorPickerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(300))
            setColor(settings.gradientEnd)
        }
        val form = vertical(this).apply {
            setPadding(dp(18), dp(6), dp(18), 0)
            addView(label("上半部分颜色", 13f, Muted))
            addView(topPicker)
            addView(label("下半部分颜色", 13f, Muted))
            addView(bottomPicker)
        }
        AlertDialog.Builder(this).setTitle("自定义渐变背景").setView(form)
            .setPositiveButton("应用") { _, _ ->
                settings.keepOriginalBackground = false
                settings.transparentBackground = false
                settings.gradientBackground = true
                settings.gradientStart = topPicker.selectedColor
                settings.gradientEnd = bottomPicker.selectedColor
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
                    val output = processor.render(source, spec, settings.copyValues(), face)
                    val uri = PhotoRepository.save(this@MainActivity, output, spec, settings)
                    output to uri
                }
                rendered = result.first
                preview?.bitmap = result.first
                lastSaved = result.second
                AlertDialog.Builder(this@MainActivity).setTitle("已保存到相册")
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
                    val output = processor.render(source, spec, settings.copyValues(), face)
                    val layout = LayoutSheetGenerator.layouts[settings.printLayout.coerceIn(0, LayoutSheetGenerator.layouts.lastIndex)]
                    val sheet = LayoutSheetGenerator.generate(output, settings.printLayout, settings.printBackgroundColor)
                    val sheetSpec = PhotoSpec("${layout.first}排版", layout.second * 25.4f / 300, layout.third * 25.4f / 300, layout.second, layout.third)
                    PhotoRepository.save(this@MainActivity, sheet, sheetSpec, settings)
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
            type = if (settings.png || settings.transparentBackground) "image/png" else "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "分享证件照"))
    }

    private fun showBatch(uris: List<Uri>) {
        val root = vertical(this).apply {
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.rgb(247, 248, 252))
        }
        val toolbar = horizontal(this)
        toolbar.addView(action("‹ 首页") { showHome() }, LinearLayout.LayoutParams(0, dp(54), 1f))
        toolbar.addView(label("批量制作 · ${uris.size} 张", 20f).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, dp(54), 2f))
        toolbar.addView(View(this), LinearLayout.LayoutParams(0, dp(54), 1f))
        root.addView(toolbar)
        root.addView(label("每张照片将独立检测人脸并自动构图，统一应用以下参数。", 13f, Color.GRAY))
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            PhotoSpec.presets.map { "${it.name} · ${it.widthPx}×${it.heightPx}" })
        root.addView(spinner, LinearLayout.LayoutParams(-1, dp(56)))
        val backgrounds = listOf("纯白", "证件蓝", "标准蓝", "证件红", "浅灰", "保留原背景")
        val bgSpinner = Spinner(this)
        bgSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, backgrounds)
        root.addView(label("统一背景", 13f))
        root.addView(bgSpinner, LinearLayout.LayoutParams(-1, dp(52)))
        val batchSettings = EditorSettings(keepOriginalBackground = false)
        bgSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                batchSettings.keepOriginalBackground = position == backgrounds.lastIndex
                if (!batchSettings.keepOriginalBackground) {
                    batchSettings.backgroundColor = PhotoSpec.backgroundColors[position].second
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        slider(root, "脸部占照片高度", 30, 88, 57, "%") { batchSettings.faceScale = it / 100f }
        slider(root, "头顶留白", 1, 35, 10, "%") { batchSettings.headTopMargin = it / 100f }
        val beauty = Switch(this).apply { text = "自然美颜与轻微锐化"; isChecked = true }
        root.addView(beauty)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = uris.size
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(20)))
        val status = label("等待开始", 14f, Color.GRAY).apply {
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(status)
        root.addView(action("开始批量处理") {
            withStoragePermission {
            it.isEnabled = false
            lifecycleScope.launch {
                var success = 0
                var failed = 0
                uris.forEachIndexed { index, uri ->
                    status.text = "正在处理 ${index + 1}/${uris.size}"
                    try {
                        withContext(Dispatchers.IO) {
                            val bitmap = PhotoRepository.load(this@MainActivity, uri)
                            val detected = processor.detectLargestFace(bitmap)
                            val currentSpec = PhotoSpec.presets[spinner.selectedItemPosition]
                            batchSettings.smooth = if (beauty.isChecked) 20 else 0
                            batchSettings.whiten = if (beauty.isChecked) 8 else 0
                            batchSettings.sharpen = if (beauty.isChecked) 10 else 0
                            val output = processor.render(bitmap, currentSpec, batchSettings.copyValues(), detected)
                            PhotoRepository.save(this@MainActivity, output, currentSpec, batchSettings,
                                "%02d".format(index + 1))
                        }
                        success++
                    } catch (_: Throwable) { failed++ }
                    progress.progress = index + 1
                }
                status.text = "完成：成功 $success 张，失败 $failed 张\n保存位置：Pictures/快捷证件照"
                Toast.makeText(this@MainActivity, "批量处理完成", Toast.LENGTH_LONG).show()
            }
            }
        }, LinearLayout.LayoutParams(-1, dp(58)))
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private data class BitmapHolder(val bitmap: android.graphics.Bitmap)
}
