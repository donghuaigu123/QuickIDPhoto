# -*- coding: utf-8 -*-
"""300DPI 排版照生成（六寸/五寸/A4/3R/4R），含裁切辅助线。"""
import numpy as np
from PIL import Image, ImageDraw

from .models import LAYOUTS


def generate(photo: np.ndarray, layout_index: int = 0, background_color=(255, 255, 255)) -> np.ndarray:
    name, sheet_width, sheet_height = LAYOUTS[max(0, min(int(layout_index), len(LAYOUTS) - 1))]
    sheet = Image.new("RGB", (sheet_width, sheet_height), tuple(int(v) for v in background_color))
    margin, gap = 36, 18
    ratio = photo.shape[1] / float(photo.shape[0])
    best = None
    for rows in range(1, 13):
        cell_height = (sheet_height - margin * 2 - gap * (rows - 1)) // rows
        if cell_height <= 0:
            break
        cell_width = int(round(cell_height * ratio))
        if cell_width <= 0:
            continue
        cols = (sheet_width - margin * 2 + gap) // (cell_width + gap)
        if cols <= 0:
            continue
        if best is None or cols * rows > best[0] * best[1]:
            best = (cols, rows, cell_height)
    if best is None:
        best = (1, 1, sheet_height - margin * 2)
    cols, rows, cell_height = best
    cell_width = int(round(cell_height * ratio))
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
