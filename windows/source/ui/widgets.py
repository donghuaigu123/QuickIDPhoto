# -*- coding: utf-8 -*-
"""可复用控件：滑块行、色块组、目录选择行、带棋盘背景的预览窗格。"""
import os
import numpy as np
from PyQt5.QtCore import Qt, pyqtSignal, QSize, QPoint
from PyQt5.QtGui import QImage, QPainter, QColor, QBrush, QWheelEvent, QPen
from PyQt5.QtWidgets import (QWidget, QHBoxLayout, QVBoxLayout, QLabel, QSlider,
                             QPushButton, QFileDialog, QLineEdit, QScrollArea, QSizePolicy)

from .theme import chip_button, hint_label, MUTED


class SliderRow(QWidget):
    """标题 + 实时数值 + 滑块；on_changed 在拖动时与释放时均回调。"""
    valueChanged = pyqtSignal(int)

    def __init__(self, name: str, minimum: int, maximum: int, value: int,
                 suffix: str = "", on_changed=None, parent=None):
        super().__init__(parent)
        self.name = name
        self.suffix = suffix
        self.minimum = minimum
        self.maximum = maximum
        self.on_changed = on_changed
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 2, 0, 2)
        layout.setSpacing(2)
        self.caption = QLabel()
        self.caption.setStyleSheet("color:#334155;font-size:12px;")
        self.slider = QSlider(Qt.Horizontal)
        self.slider.setRange(minimum, maximum)
        self.slider.setValue(value)
        self.slider.setSingleStep(1)
        self.slider.setPageStep(5)
        layout.addWidget(self.caption)
        layout.addWidget(self.slider)
        self._update_caption(value)
        self.slider.valueChanged.connect(self._on_value)

    def _update_caption(self, value):
        self.caption.setText("%s：%s%s" % (self.name, value, self.suffix))

    def _on_value(self, value):
        self._update_caption(value)
        self.valueChanged.emit(value)
        if self.on_changed:
            self.on_changed(value)

    def value(self) -> int:
        return self.slider.value()

    def set_value(self, value: int):
        self.slider.blockSignals(True)
        self.slider.setValue(max(self.minimum, min(self.maximum, value)))
        self.slider.blockSignals(False)
        self._update_caption(self.slider.value())


class ChipGroup(QWidget):
    """一排可切换的选项按钮，选中项高亮；回调收到 (text, index, button)。"""
    def __init__(self, options, selected=0, on_select=None, stretch=False, parent=None):
        super().__init__(parent)
        self.buttons = []
        self.options = options
        layout = QHBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(6)
        for index, option in enumerate(options):
            if isinstance(option, tuple):
                text = option[0]
            else:
                text = option
            btn = chip_button(text, index == selected)
            btn.clicked.connect(lambda checked=False, i=index: self.select(i, notify=True))
            self.buttons.append(btn)
            layout.addWidget(btn, 1 if stretch else 0)
        self.on_select = on_select
        self.selected = selected if 0 <= selected < len(options) else 0

    def select(self, index: int, notify: bool = True):
        if not (0 <= index < len(self.buttons)):
            return
        self.selected = index
        for i, btn in enumerate(self.buttons):
            checked = i == index
            btn.setChecked(checked)
            btn.setProperty("checked", "true" if checked else "false")
            btn.style().polish(btn)
        if notify and self.on_select:
            self.on_select(index)

    def set_options_enabled(self, enabled: bool):
        for btn in self.buttons:
            btn.setEnabled(enabled)


class DirectoryRow(QWidget):
    """可输入的目录选择行：文本框 + 浏览按钮。"""
    def __init__(self, label: str = "导出目录", value: str = "", on_changed=None, parent=None):
        super().__init__(parent)
        self.on_changed = on_changed
        layout = QHBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(8)
        lab = QLabel(label)
        lab.setFixedWidth(58)
        lab.setStyleSheet("color:#475569;")
        self.edit = QLineEdit(value)
        self.edit.setPlaceholderText("选择用于保存证件照的文件夹")
        self.edit.textChanged.connect(self._changed)
        btn = QPushButton("浏览…")
        btn.setFixedWidth(66)
        btn.clicked.connect(self.browse)
        layout.addWidget(lab)
        layout.addWidget(self.edit, 1)
        layout.addWidget(btn)

    def browse(self):
        start = self.edit.text() if self.edit.text() and os.path.isdir(self.edit.text()) else ""
        path = QFileDialog.getExistingDirectory(self, "选择导出目录", start)
        if path:
            self.edit.setText(path)

    def _changed(self, text):
        if self.on_changed:
            self.on_changed(text)

    def text(self) -> str:
        return self.edit.text().strip()

    def set_text(self, text: str):
        self.edit.setText(text or "")


class PreviewCanvas(QWidget):
    """绘制棋盘透明背景 + 居中图像；尺寸随缩放变化，交给 QScrollArea 滚动。"""
    def __init__(self, parent=None):
        super().__init__(parent)
        self.image = None
        self.scale = 1.0
        self.show_grid = False
        self.setMouseTracking(False)

    def set_image(self, image: QImage, scale: float):
        self.image = image
        self.scale = max(0.02, min(12.0, scale))
        if image is None or image.isNull():
            self.resize(320, 420)
        else:
            self.resize(max(1, int(image.width() * self.scale)),
                        max(1, int(image.height() * self.scale)))
        self.update()

    def paintEvent(self, event):
        painter = QPainter(self)
        # 只绘制可见区域（event.rect()），避免高倍缩放时遍历整块超大画布造成卡顿
        visible = event.rect().intersected(self.rect())
        painter.fillRect(visible, QColor("#E8EDF5"))
        tile = 16
        light = QColor("#FFFFFF")
        dark = QColor("#D9E0EC")
        y0 = (visible.top() // tile) * tile
        x0 = (visible.left() // tile) * tile
        for y in range(y0, visible.bottom() + 1, tile):
            for x in range(x0, visible.right() + 1, tile):
                color = light if ((x // tile + y // tile) % 2 == 0) else dark
                painter.fillRect(x, y, min(tile, visible.right() + 1 - x),
                                 min(tile, visible.bottom() + 1 - y), color)
        if self.image is not None and not self.image.isNull():
            target = QSize(max(1, int(self.image.width() * self.scale)),
                           max(1, int(self.image.height() * self.scale)))
            scaled = self.image.scaled(target, Qt.IgnoreAspectRatio, Qt.SmoothTransformation)
            painter.drawImage(0, 0, scaled)
            if self.show_grid:
                pen = QPen(QColor(255, 255, 255, 190), 1, Qt.DashLine)
                painter.setPen(pen)
                for i in (1, 2):
                    x = int(target.width() * i / 3.0)
                    y = int(target.height() * i / 3.0)
                    painter.drawLine(x, 0, x, target.height())
                    painter.drawLine(0, y, target.width(), y)
        painter.end()


class PreviewPane(QScrollArea):
    """支持滚轮缩放、拖拽平移、适应窗口的证件照预览区。"""
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("previewHost")
        self.setWidgetResizable(False)
        self.setAlignment(Qt.AlignCenter)
        self.canvas = PreviewCanvas(self)
        self.setWidget(self.canvas)
        self.image = None
        self.fit_scale = 1.0
        self.user_scale = None
        self._drag_origin = None
        self._scroll_origin = None

    def set_image_array(self, array):
        if array is None:
            self.image = None
            self.canvas.set_image(None, 1.0)
            return
        arr = np.ascontiguousarray(array)
        if arr.ndim == 2:
            image = QImage(arr.data, arr.shape[1], arr.shape[0], arr.shape[1], QImage.Format_Grayscale8).copy()
        elif arr.shape[2] == 4:
            image = QImage(arr.data, arr.shape[1], arr.shape[0], arr.shape[1] * 4, QImage.Format_RGBA8888).copy()
        else:
            image = QImage(arr.data, arr.shape[1], arr.shape[0], arr.shape[1] * 3, QImage.Format_RGB888).copy()
        self.image = image
        self.zoom_fit()

    def zoom_fit(self):
        if self.image is None or self.image.isNull():
            return
        vw = max(1, self.viewport().width() - 16)
        vh = max(1, self.viewport().height() - 16)
        self.fit_scale = min(vw / float(self.image.width()), vh / float(self.image.height()))
        self.user_scale = None
        self.canvas.set_image(self.image, self.fit_scale)

    def zoom_by(self, factor: float):
        if self.image is None or self.image.isNull():
            return
        current = self.user_scale if self.user_scale is not None else self.fit_scale
        self.user_scale = max(0.05, min(12.0, current * factor))
        self.canvas.set_image(self.image, self.user_scale)

    def toggle_grid(self):
        self.canvas.show_grid = not self.canvas.show_grid
        self.canvas.update()
        return self.canvas.show_grid

    def zoom_100(self):
        if self.image is None or self.image.isNull():
            return
        self.user_scale = 1.0
        self.canvas.set_image(self.image, 1.0)

    def wheelEvent(self, event: QWheelEvent):
        if self.image is None or self.image.isNull():
            return
        factor = 1.16 if event.angleDelta().y() > 0 else 1.0 / 1.16
        self.zoom_by(factor)

    def mousePressEvent(self, event):
        if event.button() == Qt.LeftButton and self.image is not None:
            self._drag_origin = QPoint(event.pos())
            self._scroll_origin = QPoint(self.horizontalScrollBar().value(),
                                         self.verticalScrollBar().value())
            self.setCursor(Qt.ClosedHandCursor)

    def mouseMoveEvent(self, event):
        if self._drag_origin is not None and self._scroll_origin is not None:
            delta = event.pos() - self._drag_origin
            self.horizontalScrollBar().setValue(self._scroll_origin.x() - delta.x())
            self.verticalScrollBar().setValue(self._scroll_origin.y() - delta.y())

    def mouseReleaseEvent(self, event):
        self._drag_origin = None
        self._scroll_origin = None
        self.unsetCursor()

    def mouseDoubleClickEvent(self, event):
        self.zoom_fit()

    def resizeEvent(self, event):
        super().resizeEvent(event)
        if self.user_scale is None and self.image is not None:
            self.zoom_fit()
