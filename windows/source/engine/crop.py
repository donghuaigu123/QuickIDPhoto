# -*- coding: utf-8 -*-
"""人脸感知构图：与 Android FaceCropCalculator 逐行等价的确定性算法。"""
from .models import CropBox


def clamp(v: float, lo: float, hi: float) -> float:
    return max(lo, min(v, hi))


def centered(image_width: float, image_height: float, aspect: float) -> CropBox:
    width = min(image_width, image_height * aspect)
    height = width / aspect
    left = (image_width - width) / 2.0
    top = (image_height - height) / 2.0
    return CropBox(left, top, left + width, top + height)


def calculate(image_width: int, image_height: int, aspect: float, face,
              face_scale: float = 0.57, head_top_margin: float = 0.10,
              horizontal_offset: float = 0.0) -> CropBox:
    iw = float(image_width)
    ih = float(image_height)
    if face is None or face.width <= 0 or face.height <= 0:
        return centered(iw, ih, aspect)

    safe_face_scale = clamp(face_scale, 0.30, 0.88)
    crop_height = face.height / safe_face_scale
    crop_width = crop_height * aspect
    fit_scale = min(1.0, min(iw / crop_width, ih / crop_height))
    crop_width *= fit_scale
    crop_height *= fit_scale

    center_x = face.center_x + clamp(horizontal_offset, -0.35, 0.35) * crop_width
    top = face.top - crop_height * clamp(head_top_margin, 0.01, 0.35)
    left = center_x - crop_width / 2.0
    safe_left = max(0.0, min(left, iw - crop_width))
    safe_top = max(0.0, min(top, ih - crop_height))
    return CropBox(safe_left, safe_top, safe_left + crop_width, safe_top + crop_height)
