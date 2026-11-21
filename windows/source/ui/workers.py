# -*- coding: utf-8 -*-
"""后台线程：预览渲染与批量导出，避免界面卡顿。"""
from PyQt5.QtCore import QThread, pyqtSignal

from engine.imageio import load_image, save_image, save_print_sheet
from engine.layout import generate as generate_layout


class RenderWorker(QThread):
    rendered = pyqtSignal(object)
    failed = pyqtSignal(str)

    def __init__(self, processor, source, spec, settings, face, preview=True, parent=None):
        super().__init__(parent)
        self.processor = processor
        self.source = source
        self.spec = spec
        self.settings = settings
        self.face = face
        self.preview = preview
        self._cancelled = False

    def cancel(self):
        """协作式取消：只置标志，绝不 terminate（原生代码强制终止会死锁）。"""
        self._cancelled = True

    def run(self):
        if self._cancelled:
            return
        try:
            # v2.0.0 性能修复：预览必须传 preview=True，避免做 output_scale 高清放大
            # （此前预览默认 preview=False，按 2~3 倍分辨率渲染，CPU/内存开销翻数倍）
            result = self.processor.render(self.source, self.spec, self.settings,
                                           self.face, preview=self.preview)
            if not self._cancelled:
                self.rendered.emit(result)
        except Exception as exc:
            if not self._cancelled:
                self.failed.emit(str(exc))


class BatchWorker(QThread):
    progress = pyqtSignal(int, int, str)
    item_done = pyqtSignal(int, object)
    finished_all = pyqtSignal(int, int, str)

    def __init__(self, processor, paths, spec, settings, export_dir, parent=None):
        super().__init__(parent)
        self.processor = processor
        self.paths = list(paths)
        self.spec = spec
        self.settings = settings
        self.export_dir = export_dir
        self.cancelled = False
        self.saved_files = []

    def cancel(self):
        self.cancelled = True

    def run(self):
        success, failed = 0, 0
        total = len(self.paths)
        for index, path in enumerate(self.paths):
            if self.cancelled:
                break
            self.progress.emit(index + 1, total, "正在处理：%s" % path.split("\\")[-1].split("/")[-1])
            try:
                source = load_image(path)
                face = self.processor.detect_largest_face(source)
                output = self.processor.render(source, self.spec, self.settings.copy_values(), face)
                target = self.export_dir
                saved = save_image(output, target, self.spec, self.settings, "%02d" % (index + 1))
                self.saved_files.append(saved)
                self.item_done.emit(index, saved)
                success += 1
            except Exception as exc:
                failed += 1
                self.progress.emit(index + 1, total, "失败：%s · %s" % (path.split("\\")[-1].split("/")[-1], exc))
        self.finished_all.emit(success, failed, self.export_dir)


class PrintSheetWorker(QThread):
    done = pyqtSignal(str)
    failed = pyqtSignal(str)

    def __init__(self, processor, source, spec, settings, export_dir, face=None, parent=None):
        super().__init__(parent)
        self.processor = processor
        self.source = source
        self.spec = spec
        self.settings = settings
        self.export_dir = export_dir
        self.face = face
        self._cancelled = False

    def cancel(self):
        self._cancelled = True

    def run(self):
        if self._cancelled:
            return
        try:
            render_settings = self.settings.copy_values()
            render_settings.output_scale = 1  # 排版照按标准尺寸，保证张数合理
            photo = self.processor.render(self.source, self.spec, render_settings,
                                          self.face, preview=True)
            sheet = generate_layout(photo[:, :, :3], self.settings.print_layout,
                                    self.settings.print_background_color)
            path = save_print_sheet(sheet, self.export_dir, self.spec, self.settings)
            if not self._cancelled:
                self.done.emit(path)
        except Exception as exc:
            if not self._cancelled:
                self.failed.emit(str(exc))


class SaveCurrentWorker(QThread):
    saved = pyqtSignal(str)
    failed = pyqtSignal(str)

    def __init__(self, processor, source, spec, settings, face, export_dir, parent=None):
        super().__init__(parent)
        self.processor = processor
        self.source = source
        self.spec = spec
        self.settings = settings
        self.face = face
        self.export_dir = export_dir
        self._cancelled = False

    def cancel(self):
        self._cancelled = True

    def run(self):
        if self._cancelled:
            return
        try:
            output = self.processor.render(self.source, self.spec,
                                           self.settings.copy_values(), self.face)
            path = save_image(output, self.export_dir, self.spec, self.settings)
            if not self._cancelled:
                self.saved.emit(path)
        except Exception as exc:
            if not self._cancelled:
                self.failed.emit(str(exc))
