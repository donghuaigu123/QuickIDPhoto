package cn.quickidphoto.app

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
    private val processor = IdPhotoProcessor()
    private var original: BitmapHolder? = null
    private var face: FaceBox? = null
    private var spec = PhotoSpec.presets[0]
    private var settings = EditorSettings()
    private var preview: EditorPreviewView? = null
    private var previewStatus: TextView? = null
    private var editorPanel: LinearLayout? = null
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
            setPadding(dp(22), dp(28), dp(22), dp(30))
            setBackgroundColor(Color.rgb(247, 248, 252))
        }
        scroll.addView(root)
        root.addView(label("QUICK ID PHOTO", 12f, Color.rgb(49, 87, 213)))
        root.addView(title("快捷证件照"))
        root.addView(label("专业构图 · 智能换底 · 批量制作", 16f, Color.GRAY).apply {
            setPadding(0, 0, 0, dp(24))
        })
        root.addView(homeCard("拍摄证件照", "人形轮廓引导 · 实时人脸构图检测", "开始拍摄") {
            cameraLauncher.launch(Intent(this, CameraActivity::class.java).putExtra("spec", PhotoSpec.presets.indexOf(spec)))
        })
        root.addView(homeCard("从相册制作", "自动识别人脸，精细调整每一处细节", "选择单张照片") {
            singlePicker.launch("image/*")
        })
        root.addView(homeCard("批量处理", "统一规格、背景和美颜参数，一次导出多张", "选择多张照片") {
            batchPicker.launch(arrayOf("image/*"))
        })
        root.addView(label("核心能力", 19f).apply { setPadding(0, dp(22), 0, dp(10)) })
        root.addView(label(
            "• 15 种常见规格与自定义像素 / DPI\n" +
            "• 人脸自动定位，脸部占比、头顶留白、水平位置可调\n" +
            "• 本地 AI 人像分割，纯色 / 透明背景与边缘精修\n" +
            "• 磨皮、肤色提亮、锐化、亮度、对比度、白平衡\n" +
            "• JPG / PNG 导出、六寸相纸排版与系统分享\n\n" +
            "所有照片均在本机处理，不上传服务器。不同机构可能有特殊要求，请以官方最新公告为准。",
            14f, Color.DKGRAY
        ).apply { setLineSpacing(0f, 1.35f) })
        setContentView(scroll)
    }

    private fun homeCard(title: String, subtitle: String, button: String, onClick: (View) -> Unit): View {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(Color.WHITE)
            useCompatPadding = true
        }
        val content = vertical(this).apply { setPadding(dp(18), dp(18), dp(18), dp(18)) }
        content.addView(label(title, 19f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        content.addView(label(subtitle, 13f, Color.GRAY).apply { setPadding(0, dp(6), 0, dp(12)) })
        content.addView(action(button, onClick))
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
        val root = vertical(this).apply { setBackgroundColor(Color.rgb(247, 248, 252)) }
        val toolbar = horizontal(this).apply { setPadding(dp(6), dp(4), dp(6), dp(4)) }
        toolbar.addView(action("‹ 首页") { showHome() }, LinearLayout.LayoutParams(0, dp(54), 1f))
        toolbar.addView(label("专业证件照编辑", 18f).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, dp(54), 2f))
        toolbar.addView(action("保存") { savePhoto() }, LinearLayout.LayoutParams(0, dp(54), 1f))
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
            "已自动定位人脸 · 单指拖动微调 · 双指缩放脸部占比", 12f, Color.GRAY).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }
        root.addView(previewStatus)
        root.addView(editorTabs())
        editorPanel = vertical(this).apply { setPadding(dp(14), dp(4), dp(14), dp(8)) }
        val panelScroll = ScrollView(this).apply { addView(editorPanel) }
        root.addView(panelScroll, LinearLayout.LayoutParams(-1, dp(252)))
        setContentView(root)
        showEditorTab(0)
    }

    private fun editorTabs(): View {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = horizontal(this)
        listOf("智能构图", "抠图换底", "美颜精修", "光影调色", "输出排版").forEachIndexed { index, name ->
            row.addView(action(name) { showEditorTab(index) }, LinearLayout.LayoutParams(dp(112), dp(50)))
        }
        scroll.addView(row)
        return scroll
    }

    private fun showEditorTab(index: Int) {
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
        panel.addView(label("人像分割在本机完成；复杂发丝建议放大检查边缘", 13f, Color.GRAY))
        val colors = mutableListOf("保留原背景" to 0).apply { addAll(PhotoSpec.backgroundColors) }
        val scroll = HorizontalScrollView(this)
        val row = horizontal(this)
        colors.forEach { (name, color) ->
            row.addView(action(name) {
                settings.keepOriginalBackground = name == "保留原背景"
                settings.transparentBackground = false
                if (!settings.keepOriginalBackground) settings.backgroundColor = color
                refreshPreview()
            }, LinearLayout.LayoutParams(dp(104), dp(48)))
        }
        row.addView(action("透明 PNG") {
            settings.keepOriginalBackground = false
            settings.transparentBackground = true
            settings.png = true
            refreshPreview()
        }, LinearLayout.LayoutParams(dp(112), dp(48)))
        row.addView(action("自定义颜色") { customColorDialog() }, LinearLayout.LayoutParams(dp(120), dp(48)))
        scroll.addView(row)
        panel.addView(scroll)
        slider(panel, "边缘柔化", 0, 100, settings.edgeFeather, "") { settings.edgeFeather = it }
        slider(panel, "轮廓收缩 / 扩展", -50, 50, settings.edgeShift, "") { settings.edgeShift = it }
        panel.addView(label("提示：透明背景请使用 PNG；JPG 不支持透明通道。", 12f, Color.GRAY))
    }

    private fun beautyPanel(panel: LinearLayout) {
        val presets = horizontal(this)
        presets.addView(action("自然") { applyBeautyPreset(20, 8, 10) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        presets.addView(action("清透") { applyBeautyPreset(35, 20, 16) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        presets.addView(action("精致") { applyBeautyPreset(55, 28, 24) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        presets.addView(action("重置") { applyBeautyPreset(0, 0, 0) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        panel.addView(presets)
        slider(panel, "皮肤平滑（保留非皮肤区域）", 0, 100, settings.smooth, "") { settings.smooth = it }
        slider(panel, "肤色提亮", 0, 100, settings.whiten, "") { settings.whiten = it }
        slider(panel, "细节锐化", 0, 100, settings.sharpen, "") { settings.sharpen = it }
        panel.addView(label("建议适度美颜；正式证件照不得改变五官和真实面貌。", 12f, Color.GRAY))
    }

    private fun tonePanel(panel: LinearLayout) {
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
        slider(panel, "亮度", -100, 100, settings.brightness, "") { settings.brightness = it }
        slider(panel, "对比度", -100, 100, settings.contrast, "") { settings.contrast = it }
        slider(panel, "饱和度", -100, 100, settings.saturation, "") { settings.saturation = it }
        slider(panel, "色温（冷 ↔ 暖）", -100, 100, settings.temperature, "") { settings.temperature = it }
        slider(panel, "色调（绿 ↔ 品红）", -100, 100, settings.tint, "") { settings.tint = it }
        slider(panel, "阴影", -100, 100, settings.shadows, "") { settings.shadows = it }
        slider(panel, "高光", -100, 100, settings.highlights, "") { settings.highlights = it }
    }

    private fun outputPanel(panel: LinearLayout) {
        panel.addView(label("${spec.name} · ${spec.widthPx}×${spec.heightPx}px · ${spec.dpi} DPI", 15f))
        val format = horizontal(this)
        format.addView(action("JPG") { settings.png = false; refreshPreview() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        format.addView(action("PNG") { settings.png = true; refreshPreview() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        format.addView(action("保存到相册") { savePhoto() }, LinearLayout.LayoutParams(0, dp(48), 1.4f))
        panel.addView(format)
        slider(panel, "JPG 质量", 60, 100, settings.jpegQuality, "") { settings.jpegQuality = it }
        val export = horizontal(this)
        export.addView(action("六寸相纸排版") { savePrintSheet() }, LinearLayout.LayoutParams(0, dp(50), 1f))
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
                    processor.render(source, spec, settings.copyValues(), face)
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

    private fun applyBeautyPreset(smooth: Int, whiten: Int, sharpen: Int) {
        settings.smooth = smooth; settings.whiten = whiten; settings.sharpen = sharpen
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
        val input = EditText(this).apply {
            setText("#438EDB")
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "#RRGGBB"
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(24), 0, dp(24), 0); addView(input)
        }
        AlertDialog.Builder(this).setTitle("输入背景颜色（十六进制）").setView(container)
            .setPositiveButton("应用") { _, _ ->
                try {
                    settings.backgroundColor = Color.parseColor(input.text.toString())
                    settings.keepOriginalBackground = false
                    settings.transparentBackground = false
                    refreshPreview()
                } catch (_: IllegalArgumentException) {
                    Toast.makeText(this, "颜色格式应为 #RRGGBB", Toast.LENGTH_SHORT).show()
                }
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
                    val sheet = LayoutSheetGenerator.sixInch(output)
                    val sheetSpec = PhotoSpec("六寸排版", 152.4f, 101.6f, 1800, 1200)
                    PhotoRepository.save(this@MainActivity, sheet, sheetSpec, settings)
                }
                lastSaved = uri
                Toast.makeText(this@MainActivity, "六寸排版已保存到相册", Toast.LENGTH_LONG).show()
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
