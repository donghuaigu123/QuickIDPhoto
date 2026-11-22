# -*- coding: utf-8 -*-
"""桌面版主窗口：三栏布局、五个参数页签、目录导入可视化选择与批量导出。"""
import os
from PyQt5.QtCore import Qt, QTimer, QSettings, QUrl, QStandardPaths, QSize
from PyQt5.QtGui import QIcon, QPixmap, QKeySequence, QDesktopServices, QColor
from PyQt5.QtWidgets import (QMainWindow, QWidget, QFrame, QVBoxLayout, QHBoxLayout,
                             QLabel, QPushButton, QComboBox, QSpinBox, QDialog,
                             QDialogButtonBox, QListWidget, QListWidgetItem, QTabWidget,
                             QScrollArea, QMessageBox, QProgressDialog, QShortcut,
                             QFileDialog, QColorDialog, QSizePolicy, QApplication)

from engine.models import PhotoSpec, EditorSettings, LAYOUTS, GRADIENTS
from engine.processor import IdPhotoProcessor
from engine.imageio import load_image, qimage_from_array, make_thumbnail, file_size_kb
from .theme import (STYLE_SHEET, primary_button, chip_button, section_label,
                    hint_label, card, divider, INK, MUTED)
from .widgets import SliderRow, ChipGroup, DirectoryRow, PreviewPane
from .pickers import FolderPhotoPicker
from .workers import RenderWorker, BatchWorker, PrintSheetWorker, SaveCurrentWorker

VERSION = "v2.6.0"


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("快捷证件照 Windows 版 %s · 本地处理不上传" % VERSION)
        self.setMinimumSize(1120, 720)
        self.resize(1440, 900)
        self.setAcceptDrops(True)
        self.qsettings = QSettings("QuickIDPhoto", "WindowsDesktop")

        self.processor = IdPhotoProcessor()
        self.spec = PhotoSpec.presets[0]
        self.editor = EditorSettings()
        self.items = []
        self.current_index = -1
        self.render_worker = None
        self.render_version = 0
        self.active_workers = []
        self.batch_worker = None
        self.save_worker = None
        self.print_worker = None
        self.sliders = {}
        self.chips = {}
        self._preview_timer = QTimer(self)
        self._preview_timer.setSingleShot(True)
        self._preview_timer.timeout.connect(self.render_current)

        self.build_ui()
        self.restore_settings()
        self.setStyleSheet(STYLE_SHEET)
        self.statusBar().showMessage("就绪 · 支持拖入照片或文件夹；所有处理均在本机完成")

    # ---------------- 界面构建 ----------------
    def build_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        root = QVBoxLayout(central)
        root.setContentsMargins(14, 12, 14, 10)
        root.setSpacing(10)

        root.addWidget(self.build_header())

        body = QHBoxLayout()
        body.setSpacing(10)
        body.addWidget(self.build_left_panel(), 0)
        body.addWidget(self.build_center_panel(), 1)
        body.addWidget(self.build_right_panel(), 0)
        root.addLayout(body, 1)

        root.addWidget(self.build_footer())

        # 快捷键：贴合桌面使用习惯
        QShortcut(QKeySequence("Ctrl+O"), self, self.add_files)
        QShortcut(QKeySequence("Ctrl+Shift+O"), self, self.add_directory)
        QShortcut(QKeySequence("Ctrl+S"), self, self.save_current)
        QShortcut(QKeySequence("Ctrl+E"), self, self.start_batch_export)
        QShortcut(QKeySequence("Delete"), self, self.remove_selected)
        QShortcut(QKeySequence("+"), self, lambda: self.preview.zoom_by(1.2))
        QShortcut(QKeySequence("-"), self, lambda: self.preview.zoom_by(1.0 / 1.2))

    def build_header(self):
        frame = QFrame()
        frame.setObjectName("hero")
        layout = QHBoxLayout(frame)
        layout.setContentsMargins(22, 14, 20, 14)
        left = QVBoxLayout()
        title = QLabel("快捷证件照")
        title.setObjectName("heroTitle")
        sub = QLabel("标准人像构图 · 发丝级边缘优化 · 高清多倍输出")
        sub.setObjectName("heroSub")
        badge = QLabel("本地 AI · 不上传照片")
        badge.setObjectName("badge")
        badge.setMaximumWidth(150)
        left.addWidget(title)
        left.addWidget(sub)
        left.addWidget(badge)
        layout.addLayout(left)
        layout.addStretch(1)

        btn_import = QPushButton("导入照片  Ctrl+O")
        btn_import.clicked.connect(self.add_files)
        btn_dir = QPushButton("导入照片目录  Ctrl+Shift+O")
        btn_dir.clicked.connect(self.add_directory)
        btn_open = QPushButton("打开导出目录")
        btn_open.clicked.connect(self.open_export_dir)
        for btn in (btn_import, btn_dir, btn_open):
            btn.setMinimumHeight(36)
            layout.addWidget(btn)
        return frame

    def build_left_panel(self):
        panel = card()
        panel.setFixedWidth(248)
        layout = QVBoxLayout(panel)
        layout.setContentsMargins(12, 12, 12, 12)
        layout.setSpacing(8)

        head = QHBoxLayout()
        lab = QLabel("照片队列")
        lab.setStyleSheet("font-size:15px;font-weight:bold;")
        self.queue_count = QLabel("0 张")
        self.queue_count.setStyleSheet("color:#64748B;")
        head.addWidget(lab)
        head.addStretch(1)
        head.addWidget(self.queue_count)
        layout.addLayout(head)

        row1 = QHBoxLayout()
        b1 = QPushButton("添加照片")
        b1.clicked.connect(self.add_files)
        b2 = QPushButton("添加目录")
        b2.clicked.connect(self.add_directory)
        row1.addWidget(b1)
        row1.addWidget(b2)
        layout.addLayout(row1)

        self.photo_list = QListWidget()
        self.photo_list.setIconSize(QSize(48, 48))
        self.photo_list.setGridSize(QSize(60, 64))
        self.photo_list.currentRowChanged.connect(self.on_select_photo)
        layout.addWidget(self.photo_list, 1)

        row2 = QHBoxLayout()
        b3 = QPushButton("移除选中")
        b3.clicked.connect(self.remove_selected)
        b4 = QPushButton("清空")
        b4.clicked.connect(self.clear_all)
        row2.addWidget(b3)
        row2.addWidget(b4)
        layout.addLayout(row2)
        layout.addWidget(hint_label("提示：双击预览区可适应窗口，滚轮缩放，拖动平移。"))
        return panel

    def build_center_panel(self):
        wrapper = QWidget()
        layout = QVBoxLayout(wrapper)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(8)

        toolbar = QFrame()
        toolbar.setObjectName("card")
        bar = QHBoxLayout(toolbar)
        bar.setContentsMargins(10, 8, 10, 8)
        bar.addWidget(QLabel("当前规格："))
        self.spec_combo = QComboBox()
        for spec in PhotoSpec.presets:
            self.spec_combo.addItem("%s · %d×%d" % (spec.name, spec.width_px, spec.height_px))
        self.spec_combo.currentIndexChanged.connect(self.on_spec_changed)
        bar.addWidget(self.spec_combo, 1)
        btn_fit = QPushButton("适应窗口")
        btn_fit.clicked.connect(lambda: self.preview.zoom_fit())
        btn_100 = QPushButton("100%")
        btn_100.clicked.connect(lambda: self.preview.zoom_100())
        bar.addWidget(btn_fit)
        bar.addWidget(btn_100)
        layout.addWidget(toolbar)

        self.preview = PreviewPane()
        layout.addWidget(self.preview, 1)

        self.preview_status = QLabel("尚未导入照片")
        self.preview_status.setObjectName("previewStatus")
        layout.addWidget(self.preview_status)
        return wrapper

    def build_right_panel(self):
        panel = card()
        panel.setFixedWidth(430)
        layout = QVBoxLayout(panel)
        layout.setContentsMargins(12, 12, 12, 12)
        layout.setSpacing(8)
        self.tabs = QTabWidget()
        for name in ("智能构图", "抠图换底", "美颜精修", "光影调色", "输出排版"):
            scroll = QScrollArea()
            scroll.setWidgetResizable(True)
            page = QWidget()
            page_layout = QVBoxLayout(page)
            page_layout.setContentsMargins(12, 10, 12, 12)
            page_layout.setSpacing(4)
            scroll.setWidget(page)
            self.tabs.addTab(scroll, name)
        self.tabs.currentChanged.connect(self.sync_controls)

        self.build_composition_tab(self.tabs.widget(0).widget().layout())
        self.build_background_tab(self.tabs.widget(1).widget().layout())
        self.build_beauty_tab(self.tabs.widget(2).widget().layout())
        self.build_tone_tab(self.tabs.widget(3).widget().layout())
        self.build_output_tab(self.tabs.widget(4).widget().layout())
        layout.addWidget(self.tabs, 1)
        return panel

    def build_footer(self):
        frame = QFrame()
        frame.setObjectName("card")
        layout = QHBoxLayout(frame)
        layout.setContentsMargins(12, 8, 12, 8)
        layout.setSpacing(10)

        self.export_row = DirectoryRow("导出目录", self.default_export_dir(), self.on_export_dir_changed)
        self.export_row.setMinimumWidth(420)
        layout.addWidget(self.export_row, 2)

        from PyQt5.QtWidgets import QProgressBar
        self.progress = QProgressBar()
        self.progress.setRange(0, 100)
        self.progress.setValue(0)
        self.progress.setFixedWidth(180)
        layout.addWidget(self.progress)

        self.batch_btn = primary_button("批量导出  Ctrl+E", self.start_batch_export, min_width=140)
        self.save_btn = QPushButton("保存当前  Ctrl+S")
        self.save_btn.clicked.connect(self.save_current)
        layout.addWidget(self.save_btn)
        layout.addWidget(self.batch_btn)
        return frame

    # ---------------- 各参数页签 ----------------
    def add_slider(self, layout, key, name, minimum, maximum, value, suffix="", transform=None):
        slider = SliderRow(name, minimum, maximum, value, suffix)

        def changed(v):
            setattr(self.editor, key, transform(v) if transform else v)
            self.schedule_preview()
        slider.on_changed = changed
        self.sliders[key] = slider
        layout.addWidget(slider)
        return slider

    def build_composition_tab(self, layout):
        layout.addWidget(section_label("尺寸规格"))
        self.spec_hint = hint_label("")
        row = QHBoxLayout()
        btn = QPushButton("自定义…")
        btn.clicked.connect(self.custom_spec_dialog)
        row.addWidget(btn)
        row.addStretch(1)
        layout.addLayout(row)
        layout.addWidget(self.spec_hint)
        layout.addWidget(divider())

        layout.addWidget(section_label("人脸构图微调"))
        self.add_slider(layout, "face_scale", "脸部占照片高度", 30, 88,
                        int(self.editor.face_scale * 100), "%", lambda v: v / 100.0)
        self.add_slider(layout, "head_top_margin", "头顶留白", 1, 35,
                        int(self.editor.head_top_margin * 100), "%", lambda v: v / 100.0)
        self.add_slider(layout, "horizontal_offset", "水平位置", -35, 35,
                        int(self.editor.horizontal_offset * 100), "%", lambda v: v / 100.0)

        row2 = QHBoxLayout()
        b1 = QPushButton("重新检测")
        b1.clicked.connect(self.redetect_face)
        b2 = QPushButton("重置构图")
        b2.clicked.connect(self.reset_composition)
        b3 = QPushButton("三分线")
        b3.setCheckable(True)
        b3.clicked.connect(lambda checked: self.preview.toggle_grid())
        row2.addWidget(b1)
        row2.addWidget(b2)
        row2.addWidget(b3)
        layout.addLayout(row2)

        layout.addWidget(section_label("照片变换"))
        row3 = QHBoxLayout()
        for text, degree, mirror in (("左旋 90°", -90, False), ("右旋 90°", 90, False), ("水平镜像", 0, True)):
            b = QPushButton(text)
            b.clicked.connect(lambda checked=False, d=degree, m=mirror: self.transform_source(d, m))
            row3.addWidget(b)
        layout.addLayout(row3)
        layout.addWidget(hint_label("也可在预览区直接判断构图；自动检测失败时请用滑块手动微调。"))
        layout.addStretch(1)

    def build_background_tab(self, layout):
        layout.addWidget(section_label("纯色背景"))
        options = ["保留原背景"] + [c[0] for c in PhotoSpec.background_colors] + ["透明 PNG"]
        group = ChipGroup(options, 0, self.on_background_group)
        self.chips["background"] = group
        layout.addWidget(group)
        row = QHBoxLayout()
        b1 = QPushButton("自定义颜色…")
        b1.clicked.connect(self.custom_color_dialog)
        layer = QPushButton("自定义渐变…")
        layer.clicked.connect(self.custom_gradient_dialog)
        row.addWidget(b1)
        row.addWidget(layer)
        layout.addLayout(row)

        layout.addWidget(section_label("渐变背景"))
        ggroup = ChipGroup([g[0] for g in GRADIENTS], 0, self.on_gradient_group, stretch=True)
        self.chips["gradient"] = ggroup
        layout.addWidget(ggroup)

        layout.addWidget(section_label("边缘细节（v1.9.0 无阈值软边缘）"))
        self.add_slider(layout, "edge_feather", "边缘柔化", 0, 100, self.editor.edge_feather)
        self.add_slider(layout, "edge_shift", "轮廓收缩 / 扩展", -50, 50, self.editor.edge_shift)
        self.add_slider(layout, "hair_detail", "发丝细节保留", 0, 100, self.editor.hair_detail)
        layout.addWidget(hint_label("发丝细节越高越锐利通透；柔化让过渡更自然。透明背景请用 PNG。"))
        layout.addStretch(1)

    def build_beauty_tab(self, layout):
        layout.addWidget(section_label("快速预设"))
        presets = ChipGroup(["自然", "清透", "精致", "重置"], 0, self.on_beauty_preset, stretch=True)
        self.chips["beauty"] = presets
        layout.addWidget(presets)

        layout.addWidget(section_label("手动微调"))
        intensity_slider = self.add_slider(layout, "beauty_intensity", "美颜总强度", 0, 100,
                                           self.editor.beauty_intensity, "%")
        # v2.6.0：单独拖动"美颜总强度"时，若皮肤平滑/提亮/红润全为 0，
        # 引擎会因无基础美颜参数而早退，用户看到"总强度滑块不起作用"。
        # 这里在拖动总强度时自动补一组温和的基础值，保证该滑块始终有效；
        # 不影响默认状态（用户不动滑块时子参数仍为 0，不做任何美化）。
        intensity_changed = intensity_slider.on_changed

        def on_intensity(v):
            if v > 0 and self.editor.smooth == 0 and self.editor.whiten == 0 and self.editor.rosy == 0:
                self.editor.smooth = 30
                self.editor.whiten = 15
                self.editor.rosy = 10
                for key in ("smooth", "whiten", "rosy"):
                    self.sliders[key].set_value(getattr(self.editor, key))
            intensity_changed(v)
        intensity_slider.on_changed = on_intensity
        self.add_slider(layout, "smooth", "皮肤平滑", 0, 100, self.editor.smooth)
        self.add_slider(layout, "whiten", "肤色提亮", 0, 100, self.editor.whiten)
        self.add_slider(layout, "rosy", "气色红润", 0, 100, self.editor.rosy)
        self.add_slider(layout, "sharpen", "细节锐化", 0, 100, self.editor.sharpen)
        layout.addWidget(hint_label("锐化独立于美颜总强度，始终有效。正式证件照请勿改变真实面貌。"))
        layout.addStretch(1)

    def build_tone_tab(self, layout):
        layout.addWidget(section_label("快捷操作"))
        row = QHBoxLayout()
        for text, slot in (("自动白平衡", self.auto_white_balance),
                           ("自然增强", self.natural_enhance),
                           ("全部归零", self.reset_tone)):
            b = QPushButton(text)
            b.clicked.connect(slot)
            row.addWidget(b)
        layout.addLayout(row)

        layout.addWidget(section_label("光影参数"))
        for key, name in (("brightness", "亮度"), ("contrast", "对比度"),
                          ("saturation", "饱和度"), ("temperature", "色温（冷 ↔ 暖）"),
                          ("tint", "色调（绿 ↔ 品红）"), ("shadows", "阴影"), ("highlights", "高光")):
            self.add_slider(layout, key, name, -100, 100, 0)
        layout.addStretch(1)

    def build_output_tab(self, layout):
        self.output_hint = hint_label("")
        layout.addWidget(self.output_hint)

        layout.addWidget(section_label("高清倍率"))
        hgroup = ChipGroup(["标准 1x", "高清 2x", "超清 3x"], 1, self.on_output_scale, stretch=True)
        self.chips["scale"] = hgroup
        layout.addWidget(hgroup)

        layout.addWidget(section_label("格式与保存"))
        fgroup = ChipGroup(["JPG", "PNG"], 0, self.on_format, stretch=True)
        self.chips["format"] = fgroup
        layout.addWidget(fgroup)
        self.add_slider(layout, "jpeg_quality", "JPG 质量", 60, 100, self.editor.jpeg_quality)
        self.add_slider(layout, "target_kb", "文件大小上限", 0, 1024, self.editor.target_kb, " KB")
        layout.addWidget(hint_label("大小上限仅对 JPG 生效；0 表示不限制，报名系统要求 20–200KB 时可直接设置。"))

        layout.addWidget(section_label("排版照（300DPI 含裁切线）"))
        lgroup = ChipGroup([item[0] for item in LAYOUTS], 0, self.on_layout, stretch=True)
        self.chips["layout"] = lgroup
        layout.addWidget(lgroup)
        pgroup = ChipGroup(["排版白底", "排版浅灰", "排版米白"], 0, self.on_print_bg, stretch=True)
        self.chips["print_bg"] = pgroup
        layout.addWidget(pgroup)
        b = primary_button("生成排版照", self.generate_print_sheet)
        layout.addWidget(b)
        layout.addWidget(hint_label("打印时请关闭“适应页面”，选择原始尺寸。"))
        layout.addStretch(1)

    # ---------------- 事件与业务 ----------------
    def default_export_dir(self):
        pictures = QStandardPaths.writableLocation(QStandardPaths.PicturesLocation)
        if not pictures:
            pictures = os.path.expanduser("~")
        return os.path.join(pictures, "快捷证件照")

    def restore_settings(self):
        geo = self.qsettings.value("geometry")
        if geo:
            self.restoreGeometry(geo)
        self.export_row.set_text(self.qsettings.value("export_dir", self.default_export_dir()))
        last_dir = self.qsettings.value("last_dir", "")
        self._last_dir = last_dir if last_dir and os.path.isdir(last_dir) else ""
        self.sync_controls()

    def closeEvent(self, event):
        self.qsettings.setValue("geometry", self.saveGeometry())
        self.qsettings.setValue("export_dir", self.export_row.text())
        self.qsettings.setValue("last_dir", getattr(self, "_last_dir", ""))
        # 协作式停止所有后台线程并等待其结束，避免“QThread 仍在运行时被销毁”导致退出崩溃
        workers = set()
        for worker in (self.render_worker, self.batch_worker, self.save_worker, self.print_worker):
            if worker is not None:
                workers.add(worker)
        for worker in list(self.active_workers):
            workers.add(worker)
        for worker in workers:
            if hasattr(worker, "cancel"):
                worker.cancel()
        for worker in workers:
            try:
                if worker.isRunning():
                    worker.wait(4000)
            except Exception:
                pass
        self._preview_timer.stop()
        super().closeEvent(event)

    def on_export_dir_changed(self, text):
        if text:
            self.qsettings.setValue("export_dir", text)

    def on_spec_changed(self, index):
        if 0 <= index < len(PhotoSpec.presets):
            self.spec = PhotoSpec.presets[index]
            self.sync_controls()
            self.schedule_preview()

    def sync_controls(self):
        """把内存中的设置回写到所有控件，保证多页签之间状态一致。"""
        sliders = {
            "face_scale": int(self.editor.face_scale * 100),
            "head_top_margin": int(self.editor.head_top_margin * 100),
            "horizontal_offset": int(self.editor.horizontal_offset * 100),
        }
        for key, widget in self.sliders.items():
            widget.set_value(sliders.get(key, int(getattr(self.editor, key, 0))))
        self._update_matting_sliders_enabled()
        # 规格提示与输出提示
        scale = max(1, min(3, int(self.editor.output_scale)))
        if hasattr(self, "spec_hint"):
            self.spec_hint.setText("%s · %d×%dmm · %d×%dpx · %dDPI" % (
                self.spec.name, self.spec.width_mm, self.spec.height_mm,
                self.spec.width_px, self.spec.height_px, self.spec.dpi))
        if hasattr(self, "output_hint"):
            self.output_hint.setText("%s · 输出 %d×%dpx · %d DPI" % (
                self.spec.name, self.spec.width_px * scale,
                self.spec.height_px * scale, self.spec.dpi * scale))
        # 色块组
        if "background" in self.chips:
            if self.editor.transparent_background:
                idx = len(PhotoSpec.background_colors) + 1
            elif self.editor.keep_original_background:
                idx = 0
            else:
                colors = PhotoSpec.background_colors
                idx = 1 + next((i for i, c in enumerate(colors) if c[1] == self.editor.background_color), 1)
            self.chips["background"].select(idx, notify=False)
        if "gradient" in self.chips:
            gidx = next((i for i, g in enumerate(GRADIENTS)
                         if g[1] == self.editor.gradient_start and g[2] == self.editor.gradient_end), 0)
            self.chips["gradient"].select(gidx if self.editor.gradient_background else 0, notify=False)
        if "scale" in self.chips:
            self.chips["scale"].select(max(0, min(2, int(self.editor.output_scale) - 1)), notify=False)
        if "format" in self.chips:
            self.chips["format"].select(1 if self.editor.png else 0, notify=False)
        if "layout" in self.chips:
            self.chips["layout"].select(max(0, min(len(LAYOUTS) - 1, self.editor.print_layout)), notify=False)
        if "print_bg" in self.chips:
            table = [(255, 255, 255), (238, 240, 244), (250, 246, 238)]
            self.chips["print_bg"].select(table.index(self.editor.print_background_color)
                                          if self.editor.print_background_color in table else 0, notify=False)

    def schedule_preview(self):
        self.sync_controls()
        self._preview_timer.start(220)

    # ---- 照片队列 ----
    def add_files(self):
        paths, _ = QFileDialog.getOpenFileNames(
            self, "选择照片（可多选）", getattr(self, "_last_dir", "") or None,
            "图片文件 (*.jpg *.jpeg *.png *.bmp *.webp *.gif *.tif *.tiff);;所有文件 (*.*)")
        if paths:
            self.add_paths(paths)

    def add_directory(self):
        dialog = FolderPhotoPicker(self, getattr(self, "_last_dir", ""))
        if dialog.exec_() == QDialog.Accepted and dialog.selected_paths:
            self.add_paths(dialog.selected_paths)

    def add_paths(self, paths):
        if not paths:
            return
        self._last_dir = os.path.dirname(paths[0])
        QApplication.setOverrideCursor(Qt.WaitCursor)
        added = 0
        try:
            for path in paths:
                try:
                    source = load_image(path)
                    face = self.processor.detect_largest_face(source)
                    item = {
                        "path": path, "name": os.path.basename(path),
                        "source": source, "face": face, "preview": None, "saved": ""
                    }
                    self.items.append(item)
                    added += 1
                except Exception as exc:
                    self.statusBar().showMessage("读取失败：%s · %s" % (os.path.basename(path), exc))
            self.rebuild_photo_list()
            if self.current_index < 0 and self.items:
                self.photo_list.setCurrentRow(0)
            else:
                self.render_current()
        finally:
            QApplication.restoreOverrideCursor()
        self.statusBar().showMessage("已加入 %d 张照片，共 %d 张" % (added, len(self.items)))

    def rebuild_photo_list(self):
        self.photo_list.blockSignals(True)
        self.photo_list.clear()
        for index, item in enumerate(self.items):
            qimage = qimage_from_array(item["source"])
            thumb = QPixmap.fromImage(qimage).scaled(48, 48, Qt.KeepAspectRatio, Qt.SmoothTransformation)
            list_item = QListWidgetItem(QIcon(thumb), item["name"][:18])
            list_item.setData(Qt.UserRole, index)
            list_item.setToolTip("%s\n%s" % (item["name"], "已检测人脸" if item["face"] else "未检测到人脸"))
            self.photo_list.addItem(list_item)
        self.photo_list.blockSignals(False)
        if 0 <= self.current_index < self.photo_list.count():
            self.photo_list.setCurrentRow(self.current_index)
        self.queue_count.setText("%d 张" % len(self.items))

    def on_select_photo(self, row):
        if row < 0 or row >= len(self.items):
            return
        self.current_index = row
        item = self.items[row]
        self.preview_status.setText("%s · 原图 %d×%d · %s" % (
            item["name"], item["source"].shape[1], item["source"].shape[0],
            "已自动定位人脸" if item["face"] else "未检测到人脸，可手动调整构图"))
        if item["preview"] is not None:
            self.preview.set_image_array(item["preview"])
        self.render_current()

    def remove_selected(self):
        row = self.photo_list.currentRow()
        if row < 0:
            return
        self.items.pop(row)
        self.current_index = -1
        self.rebuild_photo_list()
        if self.items:
            self.photo_list.setCurrentRow(min(row, len(self.items) - 1))
        else:
            self.preview.set_image_array(None)
            self.preview_status.setText("尚未导入照片")

    def clear_all(self):
        self.items.clear()
        self.current_index = -1
        self.rebuild_photo_list()
        self.preview.set_image_array(None)
        self.preview_status.setText("尚未导入照片")

    # ---- 预览渲染 ----
    def render_current(self):
        # 采用版本号丢弃过期结果，绝不 terminate QThread：
        # 线程中正在执行 NumPy/ONNX 原生代码时强制终止会导致死锁。
        # v2.5.0 性能修复：启动新预览前先协作式取消仍在运行的旧渲染任务，
        # 避免拖动滑块时堆积多个 ONNX 推理线程把 CPU 占满造成卡顿。
        if self.render_worker is not None and self.render_worker.isRunning():
            self.render_worker.cancel()
        self.render_version += 1
        version = self.render_version
        if not (0 <= self.current_index < len(self.items)):
            return
        item = self.items[self.current_index]
        worker = RenderWorker(self.processor, item["source"], self.spec,
                              self.editor.copy_values(), item["face"],
                              preview=True, parent=self)
        worker.rendered.connect(lambda result, v=version: self.on_rendered(result, v))
        worker.failed.connect(lambda msg, v=version: self._on_render_failed(msg, v))
        self.render_worker = worker
        self.active_workers.append(worker)
        worker.finished.connect(lambda w=worker: self._cleanup_worker(w))
        worker.start()

    def _on_render_failed(self, message, version):
        if version == self.render_version:
            self.preview_status.setText("处理失败：%s" % message)

    def _cleanup_worker(self, worker):
        if worker in self.active_workers:
            self.active_workers.remove(worker)

    def on_rendered(self, result, version=None):
        if version is not None and version != self.render_version:
            return
        if 0 <= self.current_index < len(self.items):
            self.items[self.current_index]["preview"] = result
        self.preview.set_image_array(result)
        h, w = result.shape[:2]
        self.preview_status.setText("预览 %d/%d · %s · %d×%dpx · 滚轮缩放 / 拖动平移" % (
            self.current_index + 1, len(self.items), self.spec.name, w, h))

    # ---- 参数回调 ----
    def _update_matting_sliders_enabled(self):
        """抠图边缘三滑块（柔化/收缩/发丝）仅在「换底/透明」时有效。
        v2.5.0：保留原背景时明确禁用并标注，避免用户误以为滑块失灵。"""
        enabled = not bool(self.editor.keep_original_background)
        labels = {"edge_feather": "边缘柔化", "edge_shift": "轮廓收缩 / 扩展",
                  "hair_detail": "发丝细节保留"}
        for key, name in labels.items():
            row = self.sliders.get(key)
            if row is None:
                continue
            row.slider.setEnabled(enabled)
            row.set_name(name if enabled else name + "（换底时有效）")

    def on_background_group(self, index):
        colors = PhotoSpec.background_colors
        if index == 0:
            self.editor.keep_original_background = True
            self.editor.transparent_background = False
            self.editor.gradient_background = False
        elif index == len(colors) + 1:
            self.editor.keep_original_background = False
            self.editor.transparent_background = True
            self.editor.gradient_background = False
            self.editor.png = True
        else:
            self.editor.keep_original_background = False
            self.editor.transparent_background = False
            self.editor.gradient_background = False
            self.editor.background_color = colors[index - 1][1]
        self._update_matting_sliders_enabled()
        self.schedule_preview()

    def on_gradient_group(self, index):
        self.editor.keep_original_background = False
        self.editor.transparent_background = False
        self.editor.gradient_background = True
        self.editor.gradient_start = GRADIENTS[index][1]
        self.editor.gradient_end = GRADIENTS[index][2]
        self._update_matting_sliders_enabled()
        self.schedule_preview()

    def on_beauty_preset(self, index):
        values = [(28, 14, 8, 14), (48, 26, 14, 22), (68, 36, 22, 30), (0, 0, 0, 0)][index]
        self.editor.smooth, self.editor.whiten, self.editor.rosy, self.editor.sharpen = values
        self.editor.beauty_intensity = 100
        self.schedule_preview()

    def on_output_scale(self, index):
        self.editor.output_scale = index + 1
        self.schedule_preview()

    def on_format(self, index):
        self.editor.png = index == 1
        self.schedule_preview()

    def on_layout(self, index):
        self.editor.print_layout = index
        self.sync_controls()

    def on_print_bg(self, index):
        self.editor.print_background_color = [(255, 255, 255), (238, 240, 244), (250, 246, 238)][index]

    def custom_spec_dialog(self):
        dialog = QDialog(self)
        dialog.setWindowTitle("自定义输出规格")
        form = QVBoxLayout(dialog)
        w = QSpinBox(); w.setRange(32, 4000); w.setValue(self.spec.width_px)
        h = QSpinBox(); h.setRange(32, 4000); h.setValue(self.spec.height_px)
        dpi = QSpinBox(); dpi.setRange(72, 1200); dpi.setValue(self.spec.dpi)
        for label, spin in (("宽度 px", w), ("高度 px", h), ("DPI", dpi)):
            row = QHBoxLayout(); row.addWidget(QLabel(label)); row.addWidget(spin); form.addLayout(row)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.accepted.connect(dialog.accept); buttons.rejected.connect(dialog.reject)
        form.addWidget(buttons)
        if dialog.exec_() == QDialog.Accepted:
            self.spec = PhotoSpec("自定义", w.value() * 25.4 / dpi.value(),
                                  h.value() * 25.4 / dpi.value(), w.value(), h.value(), dpi.value())
            # 复用唯一的“自定义”条目，避免反复打开对话框时条目不断累积
            label = "自定义 · %d×%d" % (w.value(), h.value())
            custom_index = -1
            for i in range(self.spec_combo.count()):
                if self.spec_combo.itemText(i).startswith("自定义"):
                    custom_index = i
                    break
            if custom_index >= 0:
                self.spec_combo.setItemText(custom_index, label)
            else:
                custom_index = self.spec_combo.count()
                self.spec_combo.addItem(label)
            # on_spec_changed 只认识预设下标；自定义规格在此直接赋值，避免被回调覆盖
            self.spec_combo.blockSignals(True)
            self.spec_combo.setCurrentIndex(custom_index)
            self.spec_combo.blockSignals(False)
            self.schedule_preview()

    def custom_color_dialog(self):
        initial = QColor(*self.editor.background_color)
        color = QColorDialog.getColor(initial, self, "选择背景颜色")
        if color.isValid():
            self.editor.background_color = (color.red(), color.green(), color.blue())
            self.editor.keep_original_background = False
            self.editor.transparent_background = False
            self.schedule_preview()

    def custom_gradient_dialog(self):
        top = QColorDialog.getColor(QColor(*self.editor.gradient_start), self, "上半部分颜色")
        if not top.isValid():
            return
        bottom = QColorDialog.getColor(QColor(*self.editor.gradient_end), self, "下半部分颜色")
        if not bottom.isValid():
            return
        self.editor.gradient_start = (top.red(), top.green(), top.blue())
        self.editor.gradient_end = (bottom.red(), bottom.green(), bottom.blue())
        self.editor.keep_original_background = False
        self.editor.gradient_background = True
        self.schedule_preview()

    def redetect_face(self):
        if not (0 <= self.current_index < len(self.items)):
            return
        item = self.items[self.current_index]
        QApplication.setOverrideCursor(Qt.WaitCursor)
        try:
            item["face"] = self.processor.detect_largest_face(item["source"])
        finally:
            QApplication.restoreOverrideCursor()
        self.preview_status.setText("人脸已重新定位" if item["face"] else "仍未检测到人脸，请手动微调构图")
        self.render_current()

    def reset_composition(self):
        self.editor.face_scale = 0.57
        self.editor.head_top_margin = 0.10
        self.editor.horizontal_offset = 0.0
        self.schedule_preview()

    def transform_source(self, degrees, mirror):
        if not (0 <= self.current_index < len(self.items)):
            return
        item = self.items[self.current_index]
        QApplication.setOverrideCursor(Qt.WaitCursor)
        try:
            item["source"] = self.processor.transform(item["source"], degrees, mirror)
            item["face"] = self.processor.detect_largest_face(item["source"])
        finally:
            QApplication.restoreOverrideCursor()
        self.render_current()

    def auto_white_balance(self):
        if not (0 <= self.current_index < len(self.items)):
            return
        temperature, tint = self.processor.suggest_white_balance(self.items[self.current_index]["source"])
        self.editor.temperature, self.editor.tint = temperature, tint
        self.schedule_preview()

    def natural_enhance(self):
        self.editor.brightness, self.editor.contrast, self.editor.saturation = 8, 14, 10
        self.schedule_preview()

    def reset_tone(self):
        for key in ("brightness", "contrast", "saturation", "temperature", "tint", "shadows", "highlights"):
            setattr(self.editor, key, 0)
        self.schedule_preview()

    # ---- 保存与导出 ----
    def ensure_export_dir(self):
        path = self.export_row.text() or self.default_export_dir()
        try:
            os.makedirs(path, exist_ok=True)
            return path
        except Exception as exc:
            QMessageBox.warning(self, "导出目录不可用", str(exc))
            return None

    def open_export_dir(self):
        path = self.ensure_export_dir()
        if path:
            QDesktopServices.openUrl(QUrl.fromLocalFile(path))

    def save_current(self):
        if not (0 <= self.current_index < len(self.items)):
            QMessageBox.information(self, "提示", "请先导入至少一张照片")
            return
        export_dir = self.ensure_export_dir()
        if not export_dir:
            return
        item = self.items[self.current_index]
        self.save_btn.setEnabled(False)
        worker = SaveCurrentWorker(self.processor, item["source"], self.spec,
                                   self.editor.copy_values(), item["face"], export_dir, self)
        worker.saved.connect(self.on_saved)
        worker.failed.connect(lambda msg: (self.save_btn.setEnabled(True),
                                           QMessageBox.critical(self, "保存失败", msg)))
        self.save_worker = worker
        worker.start()

    def on_saved(self, path):
        self.save_btn.setEnabled(True)
        if 0 <= self.current_index < len(self.items):
            self.items[self.current_index]["saved"] = path
        self.statusBar().showMessage("已保存：%s · %d KB" % (path, file_size_kb(path)))
        box = QMessageBox(self)
        box.setWindowTitle("保存成功")
        box.setText("文件已保存到：\n%s\n\n大小：%d KB" % (path, file_size_kb(path)))
        open_btn = box.addButton("打开所在文件夹", QMessageBox.ActionRole)
        box.addButton("完成", QMessageBox.AcceptRole)
        box.exec_()
        if box.clickedButton() == open_btn:
            QDesktopServices.openUrl(QUrl.fromLocalFile(os.path.dirname(path)))

    def start_batch_export(self):
        if not self.items:
            QMessageBox.information(self, "提示", "请先导入照片或照片目录")
            return
        export_dir = self.ensure_export_dir()
        if not export_dir:
            return
        self.batch_btn.setEnabled(False)
        self.progress.setValue(0)
        self.progress.setRange(0, len(self.items))
        worker = BatchWorker(self.processor, [i["path"] for i in self.items],
                             self.spec, self.editor.copy_values(), export_dir, self)
        worker.progress.connect(self.on_batch_progress)
        worker.finished_all.connect(self.on_batch_finished)
        self.batch_worker = worker
        worker.start()

    def on_batch_progress(self, current, total, message):
        self.progress.setValue(current)
        self.statusBar().showMessage("%d/%d · %s" % (current, total, message))

    def on_batch_finished(self, success, failed, export_dir):
        self.batch_btn.setEnabled(True)
        self.progress.setValue(self.progress.maximum())
        self.statusBar().showMessage("批量导出完成：成功 %d 张，失败 %d 张" % (success, failed))
        box = QMessageBox(self)
        box.setWindowTitle("批量导出完成")
        box.setText("成功 %d 张，失败 %d 张\n\n保存目录：%s" % (success, failed, export_dir))
        open_btn = box.addButton("打开导出目录", QMessageBox.ActionRole)
        box.addButton("完成", QMessageBox.AcceptRole)
        box.exec_()
        if box.clickedButton() == open_btn:
            QDesktopServices.openUrl(QUrl.fromLocalFile(export_dir))

    def generate_print_sheet(self):
        if not (0 <= self.current_index < len(self.items)):
            QMessageBox.information(self, "提示", "请先选择一张照片")
            return
        export_dir = self.ensure_export_dir()
        if not export_dir:
            return
        item = self.items[self.current_index]
        self.print_worker = PrintSheetWorker(self.processor, item["source"], self.spec,
                                             self.editor.copy_values(), export_dir,
                                             item["face"], self)
        self.print_worker.done.connect(lambda path: self.on_saved(path))
        self.print_worker.failed.connect(lambda msg: QMessageBox.critical(self, "排版失败", msg))
        self.print_worker.start()

    # ---- 拖放支持 ----
    def dragEnterEvent(self, event):
        if event.mimeData().hasUrls():
            event.acceptProposedAction()

    def dropEvent(self, event):
        files, dirs = [], []
        for url in event.mimeData().urls():
            path = url.toLocalFile()
            if not path:
                continue
            if os.path.isdir(path):
                dirs.append(path)
            else:
                files.append(path)
        if files:
            self.add_paths(files)
        for directory in dirs:
            dialog = FolderPhotoPicker(self, directory)
            if dialog.exec_() == QDialog.Accepted and dialog.selected_paths:
                self.add_paths(dialog.selected_paths)
