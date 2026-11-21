# -*- coding: utf-8 -*-
"""照片目录可视化选择器：浏览目录 → 缩略图网格 → 自由勾选要处理的照片。"""
import os
from PyQt5.QtCore import Qt, QSize
from PyQt5.QtGui import QIcon, QPixmap
from PyQt5.QtWidgets import (QDialog, QVBoxLayout, QHBoxLayout, QLabel, QLineEdit,
                             QPushButton, QCheckBox, QComboBox, QListWidget,
                             QListWidgetItem, QFileDialog, QDialogButtonBox)

from engine.imageio import list_images, make_thumbnail
from .theme import primary_button, hint_label


class FolderPhotoPicker(QDialog):
    def __init__(self, parent=None, initial_dir=""):
        super().__init__(parent)
        self.setWindowTitle("从照片目录导入 · 可视化选择")
        self.resize(900, 660)
        self.setMinimumSize(700, 500)
        self.selected_paths = []
        self._all_paths = []

        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 14, 16, 14)
        layout.setSpacing(10)

        title = QLabel("选择照片目录，然后勾选需要制作的照片")
        title.setStyleSheet("font-size:16px;font-weight:bold;color:#0F172A;")
        layout.addWidget(title)

        folder_row = QHBoxLayout()
        self.folder_edit = QLineEdit(initial_dir)
        self.folder_edit.setPlaceholderText("照片目录，例如 D:\\照片\\证件照原图")
        choose_btn = QPushButton("选择文件夹…")
        choose_btn.clicked.connect(self.choose_folder)
        self.recursive = QCheckBox("包含子文件夹")
        rescan_btn = QPushButton("重新扫描")
        rescan_btn.clicked.connect(self.reload)
        folder_row.addWidget(QLabel("目录："))
        folder_row.addWidget(self.folder_edit, 1)
        folder_row.addWidget(choose_btn)
        folder_row.addWidget(self.recursive)
        folder_row.addWidget(rescan_btn)
        layout.addLayout(folder_row)

        tool_row = QHBoxLayout()
        self.search = QLineEdit()
        self.search.setPlaceholderText("按文件名筛选…")
        self.search.textChanged.connect(self.apply_filter)
        self.sort = QComboBox()
        self.sort.addItems(["按名称", "按修改时间（新→旧）", "按文件大小"])
        self.sort.currentIndexChanged.connect(self.reload)
        all_btn = QPushButton("全选")
        all_btn.clicked.connect(lambda: self.set_all_checks(True))
        none_btn = QPushButton("全不选")
        none_btn.clicked.connect(lambda: self.set_all_checks(False))
        invert_btn = QPushButton("反选")
        invert_btn.clicked.connect(self.invert_checks)
        tool_row.addWidget(self.search, 1)
        tool_row.addWidget(self.sort)
        tool_row.addWidget(all_btn)
        tool_row.addWidget(none_btn)
        tool_row.addWidget(invert_btn)
        layout.addLayout(tool_row)

        self.list = QListWidget()
        self.list.setViewMode(QListWidget.IconMode)
        self.list.setResizeMode(QListWidget.Adjust)
        self.list.setMovement(QListWidget.Static)
        self.list.setSpacing(10)
        self.list.setGridSize(QSize(150, 132))
        self.list.setIconSize(QSize(112, 96))
        self.list.itemDoubleClicked.connect(self.toggle_item)
        self.list.itemChanged.connect(self.update_count)
        layout.addWidget(self.list, 1)

        self.count_label = hint_label("尚未选择目录")
        layout.addWidget(self.count_label)

        buttons = QDialogButtonBox()
        ok_btn = primary_button("加入处理列表")
        ok_btn.clicked.connect(self.accept_selection)
        cancel_btn = QPushButton("取消")
        cancel_btn.clicked.connect(self.reject)
        buttons.addButton(ok_btn, QDialogButtonBox.AcceptRole)
        buttons.addButton(cancel_btn, QDialogButtonBox.RejectRole)
        layout.addWidget(buttons)

        if initial_dir and os.path.isdir(initial_dir):
            self.reload()

    def choose_folder(self):
        path = QFileDialog.getExistingDirectory(self, "选择照片目录", self.folder_edit.text() or None)
        if path:
            self.folder_edit.setText(path)
            self.reload()

    def reload(self):
        folder = self.folder_edit.text().strip()
        self.list.clear()
        self._all_paths = []
        if not folder or not os.path.isdir(folder):
            self.update_count()
            return
        paths = list_images(folder, self.recursive.isChecked())
        mode = self.sort.currentIndex()
        if mode == 1:
            paths.sort(key=lambda p: os.path.getmtime(p), reverse=True)
        elif mode == 2:
            paths.sort(key=lambda p: os.path.getsize(p), reverse=True)
        else:
            paths.sort(key=lambda p: os.path.basename(p).lower())
        self._all_paths = paths
        for path in paths:
            thumb = make_thumbnail(path, 112)
            item = QListWidgetItem(os.path.basename(path))
            if thumb is not None:
                item.setIcon(QIcon(QPixmap.fromImage(thumb)))
            item.setFlags(item.flags() | Qt.ItemIsUserCheckable)
            item.setCheckState(Qt.Checked)
            item.setData(Qt.UserRole, path)
            item.setToolTip(path)
            item.setTextAlignment(Qt.AlignHCenter | Qt.AlignBottom)
            self.list.addItem(item)
        self.apply_filter()

    def apply_filter(self):
        keyword = self.search.text().strip().lower()
        for i in range(self.list.count()):
            item = self.list.item(i)
            item.setHidden(bool(keyword) and keyword not in os.path.basename(item.data(Qt.UserRole)).lower())
        self.update_count()

    def toggle_item(self, item):
        item.setCheckState(Qt.Unchecked if item.checkState() == Qt.Checked else Qt.Checked)

    def set_all_checks(self, checked: bool):
        state = Qt.Checked if checked else Qt.Unchecked
        for i in range(self.list.count()):
            item = self.list.item(i)
            if not item.isHidden():
                item.setCheckState(state)

    def invert_checks(self):
        for i in range(self.list.count()):
            item = self.list.item(i)
            if not item.isHidden():
                item.setCheckState(Qt.Unchecked if item.checkState() == Qt.Checked else Qt.Checked)

    def update_count(self, *_args):
        total = self.list.count()
        checked = sum(1 for i in range(total) if self.list.item(i).checkState() == Qt.Checked)
        self.count_label.setText("共发现 %d 张照片，已勾选 %d 张" % (total, checked))

    def accept_selection(self):
        self.selected_paths = [self.list.item(i).data(Qt.UserRole)
                               for i in range(self.list.count())
                               if self.list.item(i).checkState() == Qt.Checked]
        if not self.selected_paths:
            self.count_label.setText("请至少勾选一张照片")
            return
        self.accept()
