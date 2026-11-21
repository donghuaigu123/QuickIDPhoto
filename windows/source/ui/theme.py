# -*- coding: utf-8 -*-
"""统一视觉主题：配色、样式表与可复用控件工厂。

设计目标：与 Android 版同源的蓝紫主色 + 青绿点缀，桌面端采用
三栏布局、卡片分区、立体按钮与清晰的层级，长时间使用不疲劳。
"""
from PyQt5.QtCore import Qt
from PyQt5.QtGui import QColor, QFont
from PyQt5.QtWidgets import QPushButton, QLabel, QFrame, QComboBox, QLineEdit

INK = "#0F172A"
MUTED = "#64748B"
SURFACE = "#F4F7FC"
CARD = "#FFFFFF"
PRIMARY = "#4F66FF"
PRIMARY_DARK = "#3E52D6"
PRIMARY_DEEP = "#7C5CFF"
ACCENT = "#14B8A6"
BORDER = "#E2E8F0"
DANGER = "#E11D48"
SOFT = "#EEF2FF"

STYLE_SHEET = """
QWidget { font-family: 'Microsoft YaHei', 'PingFang SC', 'Segoe UI', sans-serif; font-size: 13px; color: %s; }
QMainWindow, QDialog { background: %s; }
QFrame#card { background: %s; border: 1px solid %s; border-radius: 14px; }
QFrame#hero { background: qlineargradient(x1:0,y1:0,x2:1,y2:1, stop:0 #4C60FF, stop:0.55 #8B5CF6, stop:1 #14B8A6); border-radius: 16px; }
QFrame#divider { background: %s; max-height: 1px; }
QFrame#previewHost { background: #E8EDF5; border: 1px solid %s; border-radius: 12px; }
QLabel#heroTitle { color: white; font-size: 26px; font-weight: bold; }
QLabel#heroSub { color: rgba(255,255,255,210); font-size: 12px; }
QLabel#section { color: %s; font-size: 12px; font-weight: bold; letter-spacing: 1px; }
QLabel#hint { color: %s; font-size: 11px; }
QLabel#previewStatus { color: %s; font-size: 11px; }
QLabel#badge { color: #0F766E; background: #D1FAE5; border-radius: 10px; padding: 3px 10px; font-size: 11px; font-weight: bold; }

QPushButton { background: white; color: %s; border: 1px solid %s; border-radius: 9px; padding: 8px 14px; }
QPushButton:hover { background: #F8FAFF; border-color: #C7D2FE; }
QPushButton:pressed { background: #E8EEFF; }
QPushButton:disabled { color: #94A3B8; background: #F1F5F9; border-color: #E2E8F0; }
QPushButton[flat="true"] { border: none; background: transparent; }
QPushButton[flat="true"]:hover { background: #EEF2FF; }

QPushButton[role="primary"] {
  background: qlineargradient(x1:0,y1:0,x2:0,y2:1, stop:0 #6F84FF, stop:1 #4050E2);
  color: white; border: 1px solid #3E52D6; font-weight: bold; padding: 9px 18px;
}
QPushButton[role="primary"]:hover { background: qlineargradient(x1:0,y1:0,x2:0,y2:1, stop:0 #7D90FF, stop:1 #4A5BEA); }
QPushButton[role="primary"]:pressed { background: #2B37B2; border-color: #2B37B2; }
QPushButton[role="primary"]:disabled { background: #C7D2FE; border-color: #C7D2FE; color: #EEF2FF; }

QPushButton[role="chip"] { border-radius: 16px; padding: 6px 12px; color: #475569; background: white; border: 1px solid #D7DEEA; }
QPushButton[role="chip"][checked="true"] {
  background: qlineargradient(x1:0,y1:0,x2:0,y2:1, stop:0 #6F84FF, stop:1 #4050E2);
  color: white; border-color: #4050E2; font-weight: bold;
}
QPushButton[role="tab"] { border: none; border-bottom: 3px solid transparent; border-radius: 0px; color: %s; padding: 10px 16px; background: transparent; }
QPushButton[role="tab"][checked="true"] { color: #4050E2; border-bottom-color: #4F66FF; font-weight: bold; }
QPushButton[role="tab"]:hover { color: #4F66FF; background: #F5F7FF; }

QTabWidget::pane { border: 1px solid %s; border-radius: 10px; background: white; top: -1px; }
QTabBar::tab { background: #EEF2F8; color: #64748B; padding: 8px 9px; font-size: 12px; border-top-left-radius: 8px; border-top-right-radius: 8px; margin-right: 1px; }
QTabBar::tab:selected { background: white; color: #4050E2; font-weight: bold; border: 1px solid %s; border-bottom-color: white; }
QTabBar::tab:hover { color: #4F66FF; }

QSlider::groove:horizontal { height: 5px; background: #E2E8F0; border-radius: 3px; }
QSlider::sub-page:horizontal { background: qlineargradient(x1:0,y1:0,x2:1,y2:0, stop:0 #4F66FF, stop:1 #7C5CFF); border-radius: 3px; }
QSlider::add-page:horizontal { background: #E2E8F0; border-radius: 3px; }
QSlider::handle:horizontal { width: 15px; height: 15px; margin: -5px 0; border-radius: 8px; background: white; border: 3px solid #4F66FF; }
QSlider::handle:horizontal:hover { border-color: #7C5CFF; }

QComboBox, QLineEdit, QSpinBox { background: white; border: 1px solid %s; border-radius: 8px; padding: 7px 10px; selection-background-color: #4F66FF; }
QComboBox:hover, QLineEdit:hover, QSpinBox:hover { border-color: #A5B4FC; }
QComboBox:focus, QLineEdit:focus { border-color: #4F66FF; }
QComboBox::drop-down { border: none; width: 22px; }
QComboBox QAbstractItemView { border: 1px solid %s; border-radius: 8px; background: white; selection-background-color: #EEF2FF; }

QListWidget { background: white; border: 1px solid %s; border-radius: 10px; padding: 4px; }
QListWidget::item { border-radius: 8px; padding: 4px; }
QListWidget::item:selected { background: #E0E7FF; border: 1px solid #93A5FF; }
QListWidget::item:hover { background: #F1F5FF; }

QScrollArea { border: none; background: transparent; }
QScrollBar:vertical { background: transparent; width: 9px; margin: 2px; }
QScrollBar::handle:vertical { background: #CBD5E1; border-radius: 4px; min-height: 30px; }
QScrollBar::handle:vertical:hover { background: #94A3B8; }
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical { height: 0; }
QScrollBar:horizontal { background: transparent; height: 9px; margin: 2px; }
QScrollBar::handle:horizontal { background: #CBD5E1; border-radius: 4px; min-width: 30px; }
QScrollBar::add-line:horizontal, QScrollBar::sub-line:horizontal { width: 0; }

QProgressBar { border: 1px solid %s; border-radius: 7px; background: white; text-align: center; height: 16px; }
QProgressBar::chunk { background: qlineargradient(x1:0,y1:0,x2:1,y2:0, stop:0 #4F66FF, stop:1 #14B8A6); border-radius: 6px; }

QCheckBox { spacing: 7px; }
QCheckBox::indicator { width: 16px; height: 16px; border: 1px solid #CBD5E1; border-radius: 4px; background: white; }
QCheckBox::indicator:checked { background: #4F66FF; border-color: #4F66FF; }
QMenuBar { background: white; }
QStatusBar { background: white; color: %s; }
QToolTip { background: #1E293B; color: white; border: none; padding: 5px 8px; border-radius: 5px; }
""" % (INK, SURFACE, CARD, BORDER, BORDER, MUTED, MUTED, MUTED, INK, BORDER, INK,
       BORDER, BORDER, BORDER, BORDER, BORDER, BORDER, BORDER, MUTED)


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
