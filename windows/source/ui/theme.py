# -*- coding: utf-8 -*-
"""统一视觉主题：高对比配色、样式表与可复用控件工厂。

设计目标：与 Android 版同源的蓝紫主色 + 青绿点缀，桌面端采用
三栏布局、卡片分区、立体按钮与清晰层级。

v1.9.1 修复：旧版用位置型 %s 格式化样式表，参数顺序错位导致
普通按钮文字变成 #E2E8F0（浅灰白）配深色边框，白底上几乎看不见。
本版改用 string.Template 命名占位符，从根本上杜绝错位，并全面
提高文字对比度、统一所有控件风格。
"""
from string import Template
from PyQt5.QtCore import Qt
from PyQt5.QtGui import QColor, QFont
from PyQt5.QtWidgets import QPushButton, QLabel, QFrame, QComboBox, QLineEdit

# ---- 高对比配色 ----
INK = "#0B1220"          # 标题/最深文字
TEXT = "#1E293B"         # 正文主色（深 slate）
MUTED = "#475569"        # 次要文字（比旧版 #64748B 更深）
SUBTLE = "#64748B"       # 辅助文字
SURFACE = "#F2F5FB"      # 窗口底色
CARD = "#FFFFFF"         # 卡片底色
PRIMARY = "#4F46E5"      # 主色（靛蓝）
PRIMARY_DARK = "#3730A3" # 主色深阶
PRIMARY_LIGHT = "#EEF2FF"# 主色浅阶
ACCENT = "#0D9488"       # 青绿点缀（深于旧版，对比更强）
BORDER = "#D5DEEB"       # 卡片边框
LINE = "#CBD5E1"         # 控件边框
DANGER = "#E11D48"

_COLORS = dict(
    INK=INK, TEXT=TEXT, MUTED=MUTED, SUBTLE=SUBTLE, SURFACE=SURFACE, CARD=CARD,
    PRIMARY=PRIMARY, PRIMARY_DARK=PRIMARY_DARK, PRIMARY_LIGHT=PRIMARY_LIGHT,
    ACCENT=ACCENT, BORDER=BORDER, LINE=LINE,
)

_STYLE_TEMPLATE = r"""
QWidget { font-family: 'Microsoft YaHei', 'PingFang SC', 'Segoe UI', sans-serif; font-size: 13px; color: $TEXT; }
QMainWindow, QDialog { background: $SURFACE; }
QFrame#card { background: $CARD; border: 1px solid $BORDER; border-radius: 14px; }
QFrame#hero { background: qlineargradient(x1:0,y1:0,x2:1,y2:1, stop:0 #4338CA, stop:0.55 #6D28D9, stop:1 #0D9488); border-radius: 16px; }
QFrame#divider { background: $BORDER; max-height: 1px; }
QFrame#previewHost { background: #E8EDF5; border: 1px solid $BORDER; border-radius: 12px; }
QLabel#heroTitle { color: #FFFFFF; font-size: 26px; font-weight: bold; }
QLabel#heroSub { color: rgba(255,255,255,225); font-size: 12px; }
QLabel#section { color: $INK; font-size: 13px; font-weight: bold; border-left: 3px solid $PRIMARY; padding-left: 8px; }
QLabel#hint { color: $MUTED; font-size: 11px; }
QLabel#previewStatus { color: $TEXT; font-size: 11px; font-weight: 600; }
QLabel#badge { color: #065F46; background: #D1FAE5; border-radius: 10px; padding: 3px 10px; font-size: 11px; font-weight: bold; }

QPushButton { background: $CARD; color: $TEXT; border: 1px solid $LINE; border-radius: 9px; padding: 8px 14px; font-weight: 600; }
QPushButton:hover { background: $PRIMARY_LIGHT; border-color: $PRIMARY; color: $PRIMARY_DARK; }
QPushButton:pressed { background: #E0E7FF; border-color: $PRIMARY_DARK; }
QPushButton:disabled { color: $SUBTLE; background: #F1F5F9; border-color: $BORDER; }
QPushButton[flat="true"] { border: none; background: transparent; }
QPushButton[flat="true"]:hover { background: $PRIMARY_LIGHT; }

QPushButton[role="primary"] {
  background: qlineargradient(x1:0,y1:0,x2:0,y2:1, stop:0 #6366F1, stop:1 #4338CA);
  color: #FFFFFF; border: 1px solid $PRIMARY_DARK; font-weight: bold; padding: 9px 18px;
}
QPushButton[role="primary"]:hover { background: qlineargradient(x1:0,y1:0,x2:0,y2:1, stop:0 #818CF8, stop:1 #4F46E5); border-color: $PRIMARY; color: #FFFFFF; }
QPushButton[role="primary"]:pressed { background: #312E81; border-color: #312E81; color: #FFFFFF; }
QPushButton[role="primary"]:disabled { background: #C7D2FE; border-color: #C7D2FE; color: #F8FAFC; }

QPushButton[role="chip"] { border-radius: 16px; padding: 6px 12px; color: $TEXT; background: #F8FAFC; border: 1px solid $LINE; font-weight: 600; }
QPushButton[role="chip"]:hover { background: $PRIMARY_LIGHT; border-color: $PRIMARY; color: $PRIMARY_DARK; }
QPushButton[role="chip"][checked="true"] {
  background: qlineargradient(x1:0,y1:0,x2:0,y2:1, stop:0 #6366F1, stop:1 #4338CA);
  color: #FFFFFF; border-color: $PRIMARY_DARK; font-weight: bold;
}

QPushButton[role="tab"] { border: none; border-bottom: 3px solid transparent; border-radius: 0px; color: $MUTED; padding: 10px 16px; background: transparent; font-weight: 600; }
QPushButton[role="tab"][checked="true"] { color: $PRIMARY_DARK; border-bottom-color: $PRIMARY; font-weight: bold; }
QPushButton[role="tab"]:hover { color: $PRIMARY; background: $PRIMARY_LIGHT; }

QTabWidget::pane { border: 1px solid $BORDER; border-radius: 10px; background: white; top: -1px; }
QTabBar::tab { background: #EEF2F8; color: $MUTED; padding: 8px 10px; font-size: 12px; border-top-left-radius: 8px; border-top-right-radius: 8px; margin-right: 1px; }
QTabBar::tab:selected { background: white; color: $PRIMARY_DARK; font-weight: bold; border: 1px solid $BORDER; border-bottom-color: white; }
QTabBar::tab:hover { color: $PRIMARY; background: $PRIMARY_LIGHT; }

QSlider::groove:horizontal { height: 5px; background: $BORDER; border-radius: 3px; }
QSlider::sub-page:horizontal { background: qlineargradient(x1:0,y1:0,x2:1,y2:0, stop:0 $PRIMARY, stop:1 #7C3AED); border-radius: 3px; }
QSlider::add-page:horizontal { background: $BORDER; border-radius: 3px; }
QSlider::handle:horizontal { width: 15px; height: 15px; margin: -5px 0; border-radius: 8px; background: white; border: 3px solid $PRIMARY; }
QSlider::handle:horizontal:hover { border-color: #7C3AED; }

QComboBox, QLineEdit, QSpinBox { background: white; color: $TEXT; border: 1px solid $LINE; border-radius: 8px; padding: 7px 10px; selection-background-color: $PRIMARY; selection-color: white; }
QComboBox:hover, QLineEdit:hover, QSpinBox:hover { border-color: $PRIMARY; }
QComboBox:focus, QLineEdit:focus { border-color: $PRIMARY; }
QComboBox::drop-down { border: none; width: 22px; }
QComboBox QAbstractItemView { border: 1px solid $BORDER; border-radius: 8px; background: white; color: $TEXT; selection-background-color: $PRIMARY_LIGHT; selection-color: $PRIMARY_DARK; }

QListWidget { background: white; color: $TEXT; border: 1px solid $BORDER; border-radius: 10px; padding: 4px; }
QListWidget::item { border-radius: 8px; padding: 4px; color: $TEXT; }
QListWidget::item:selected { background: $PRIMARY; color: white; }
QListWidget::item:hover { background: $PRIMARY_LIGHT; }

QScrollArea { border: none; background: transparent; }
QScrollBar:vertical { background: transparent; width: 9px; margin: 2px; }
QScrollBar::handle:vertical { background: #94A3B8; border-radius: 4px; min-height: 30px; }
QScrollBar::handle:vertical:hover { background: $MUTED; }
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical { height: 0; }
QScrollBar:horizontal { background: transparent; height: 9px; margin: 2px; }
QScrollBar::handle:horizontal { background: #94A3B8; border-radius: 4px; min-width: 30px; }
QScrollBar::add-line:horizontal, QScrollBar::sub-line:horizontal { width: 0; }

QProgressBar { border: 1px solid $BORDER; border-radius: 7px; background: white; color: $TEXT; text-align: center; height: 16px; }
QProgressBar::chunk { background: qlineargradient(x1:0,y1:0,x2:1,y2:0, stop:0 $PRIMARY, stop:1 $ACCENT); border-radius: 6px; }

QCheckBox { spacing: 7px; color: $TEXT; }
QCheckBox::indicator { width: 16px; height: 16px; border: 1px solid $LINE; border-radius: 4px; background: white; }
QCheckBox::indicator:checked { background: $PRIMARY; border-color: $PRIMARY_DARK; }
QMenuBar { background: white; color: $TEXT; }
QStatusBar { background: white; color: $TEXT; }
QToolTip { background: $INK; color: white; border: none; padding: 5px 8px; border-radius: 5px; }
QDialogButtonBox QPushButton { min-width: 88px; }
"""

STYLE_SHEET = Template(_STYLE_TEMPLATE).substitute(_COLORS)


def primary_button(text: str, slot=None, *, min_width=0) -> QPushButton:
    btn = QPushButton(text)
    btn.setProperty("role", "primary")
    if min_width:
        btn.setMinimumWidth(min_width)
    if slot:
        btn.clicked.connect(slot)
    return btn


def chip_button(text: str, checked: bool = False, slot=None) -> QPushButton:
    btn = QPushButton(text)
    btn.setProperty("role", "chip")
    btn.setCheckable(True)
    btn.setChecked(checked)
    btn.setProperty("checked", "true" if checked else "false")
    if slot:
        btn.clicked.connect(lambda: slot(btn))
    return btn


def section_label(text: str) -> QLabel:
    lab = QLabel(text)
    lab.setObjectName("section")
    return lab


def hint_label(text: str) -> QLabel:
    lab = QLabel(text)
    lab.setObjectName("hint")
    lab.setWordWrap(True)
    return lab


def card() -> QFrame:
    f = QFrame()
    f.setObjectName("card")
    return f


def divider() -> QFrame:
    f = QFrame()
    f.setObjectName("divider")
    f.setFixedHeight(1)
    return f


def mono_font(size: int = 12) -> QFont:
    font = QFont("Consolas", size)
    return font
