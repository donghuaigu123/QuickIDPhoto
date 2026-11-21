# -*- coding: utf-8 -*-
"""快捷证件照 Windows 版入口（兼容 Windows 7 / Python 3.8 / PyQt5）。"""
import os
import sys
import traceback
from datetime import datetime

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, BASE_DIR)

from PyQt5.QtCore import Qt
from PyQt5.QtWidgets import QApplication, QMessageBox

from ui.theme import STYLE_SHEET
from ui.main_window import MainWindow


def _excepthook(exc_type, exc_value, exc_tb):
    text = "".join(traceback.format_exception(exc_type, exc_value, exc_tb))
    try:
        log = os.path.join(BASE_DIR, "error.log")
        with open(log, "a", encoding="utf-8") as f:
            f.write("\n=== %s ===\n%s" % (datetime.now().isoformat(), text))
    except Exception:
        pass
    try:
        QMessageBox.critical(None, "程序异常", text[-1200:])
    except Exception:
        pass
    sys.__excepthook__(exc_type, exc_value, exc_tb)


def main():
    sys.excepthook = _excepthook
    if hasattr(QApplication, "setAttribute"):
        QApplication.setAttribute(Qt.AA_EnableHighDpiScaling, True)
        QApplication.setAttribute(Qt.AA_UseHighDpiPixmaps, True)
    app = QApplication(sys.argv)
    app.setApplicationName("快捷证件照 Windows 版")
    app.setStyleSheet(STYLE_SHEET)
    window = MainWindow()
    window.show()
    sys.exit(app.exec_())


if __name__ == "__main__":
    main()
