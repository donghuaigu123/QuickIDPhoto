# -*- coding: utf-8 -*-
"""300DPI 排版照生成（六寸/五寸/A4/3R/4R），含裁切辅助线。"""
import numpy as np
from PIL import Image, ImageDraw

from .models import LAYOUTS


def generate(photo: np.ndarray, layout_index: int = 0, background_color=(255, 255, 255),
             photo_mm=None) -> np.ndarray:
    """按标准物理尺寸排版：照片保持打印尺寸（mm×300DPI），不被缩小，能排几张排几张并居中。

    旧算法遍历 1~12 行追求数量最大化，导致照片被无限缩小塞满相纸（六寸排出上百张小图）。
    新算法以照片标准尺寸为固定 cell，只计算相纸能容纳的行列数，剩余空间居中留白。

    photo_mm: (宽mm, 高mm)。传入时按 300DPI 换算标准像素尺寸；不传则用照片自身像素尺寸。
    """
    name, sheet_width, sheet_height = LAYOUTS[max(0, min(int(layout_index), len(LAYOUTS) - 1))]
    sheet = Image.new("RGB", (sheet_width, sheet_height), tuple(int(v) for v in background_color))

    dpi = 300
    if photo_mm is not None:
        # 按物理尺寸换算标准像素（一寸 25×35mm -> 295×413px @300DPI），与渲染输出分辨率无关
        cell_width = max(1, int(round(float(photo_mm[0]) / 25.4 * dpi)))
        cell_height = max(1, int(round(float(photo_mm[1]) / 25.4 * dpi)))
    else:
        cell_height = int(photo.shape[0])
        cell_width = int(photo.shape[1])

    margin, gap = 24, 12
    cols = max(0, (sheet_width - margin * 2 + gap) // (cell_width + gap))
    rows = max(0, (sheet_height - margin * 2 + gap) // (cell_height + gap))
    # 极端情况（超大规格照片一张都放不下）时逐步等比缩小，直到至少能放一张
    while (cols <= 0 or rows <= 0) and cell_height > 24:
        cell_height = int(cell_height * 0.9)
        cell_width = int(cell_width * 0.9)
        cols = max(0, (sheet_width - margin * 2 + gap) // (cell_width + gap))
        rows = max(0, (sheet_height - margin * 2 + gap) // (cell_height + gap))
    cols, rows = max(1, cols), max(1, rows)

    grid_width = cols * cell_width + (cols - 1) * gap
    grid_height = rows * cell_height + (rows - 1) * gap
    offset_x = (sheet_width - grid_width) // 2
    offset_y = (sheet_height - grid_height) // 2

    mode = "RGBA" if photo.ndim == 3 and photo.shape[2] == 4 else "RGB"
    tile = Image.fromarray(photo, mode=mode).resize((cell_width, cell_height), Image.LANCZOS)
    draw = ImageDraw.Draw(sheet)
    dash_color = (128, 128, 128) if tuple(background_color) == (255, 255, 255) else (200, 200, 200)
    for row in range(rows):
        for col in range(cols):
            x = offset_x + col * (cell_width + gap)
            y = offset_y + row * (cell_height + gap)
            if mode == "RGBA":
                sheet.paste(tile, (x, y), tile)
            else:
                sheet.paste(tile, (x, y))
            # 虚线裁切框
            for dx in range(0, cell_width, 18):
                draw.line([(x + dx, y), (x + min(dx + 10, cell_width), y)], fill=dash_color, width=2)
                draw.line([(x + dx, y + cell_height), (x + min(dx + 10, cell_width), y + cell_height)],
                          fill=dash_color, width=2)
            for dy in range(0, cell_height, 18):
                draw.line([(x, y + dy), (x, y + min(dy + 10, cell_height))], fill=dash_color, width=2)
                draw.line([(x + cell_width, y + dy), (x + cell_width, y + min(dy + 10, cell_height))],
                          fill=dash_color, width=2)
    return np.asarray(sheet)


def dpi_for_layout(layout_index: int) -> int:
    return 300
