# -*- coding: utf-8 -*-
"""图片读写：EXIF 方向、JPG 大小限制压缩、DPI 元数据、缩略图。"""
import io
import os
from datetime import datetime
import numpy as np
from PIL import Image, ImageOps

IMAGE_EXTENSIONS = (".jpg", ".jpeg", ".png", ".bmp", ".webp", ".gif", ".tif", ".tiff")


def load_image(path: str, max_dimension: int = 2600) -> np.ndarray:
    with Image.open(path) as raw:
        image = ImageOps.exif_transpose(raw)
        image = image.convert("RGB")
        if max(image.size) > max_dimension:
            scale = max_dimension / float(max(image.size))
            image = image.resize((max(1, int(image.width * scale)),
                                  max(1, int(image.height * scale))), Image.LANCZOS)
        return np.array(image)


def qimage_from_array(array: np.ndarray):
    from PyQt5.QtGui import QImage
    arr = np.ascontiguousarray(array)
    if arr.ndim == 2:
        return QImage(arr.data, arr.shape[1], arr.shape[0], arr.shape[1], QImage.Format_Grayscale8).copy()
    if arr.shape[2] == 4:
        return QImage(arr.data, arr.shape[1], arr.shape[0], arr.shape[1] * 4, QImage.Format_RGBA8888).copy()
    return QImage(arr.data, arr.shape[1], arr.shape[0], arr.shape[1] * 3, QImage.Format_RGB888).copy()


def make_thumbnail(path: str, size: int = 128):
    """返回正方形裁剪的缩略图 QImage，失败返回 None。"""
    try:
        with Image.open(path) as raw:
            image = ImageOps.exif_transpose(raw).convert("RGB")
        image = ImageOps.fit(image, (size, size), Image.LANCZOS, centering=(0.5, 0.5))
        return qimage_from_array(np.array(image))
    except Exception:
        return None


def _encode_jpeg(image: Image.Image, quality: int, target_kb: int, dpi: int = 300) -> bytes:
    def encode(q):
        stream = io.BytesIO()
        image.save(stream, format="JPEG", quality=q, subsampling=0, optimize=True,
                   dpi=(dpi, dpi))
        return stream.getvalue()
    if target_kb <= 0:
        return encode(quality)
    target_bytes = target_kb * 1024
    low, high = 15, max(15, min(100, quality))
    best = None
    for _ in range(8):
        mid = (low + high) // 2
        data = encode(mid)
        if len(data) <= target_bytes:
            best = data
            low = mid + 1
        else:
            high = mid - 1
    return best if best is not None else encode(15)


def save_image(array: np.ndarray, directory: str, spec, settings,
               prefix: str = "") -> str:
    """保存成品到目录，返回完整路径。PNG 保留透明，JPG 支持 KB 上限与 DPI。"""
    os.makedirs(directory, exist_ok=True)
    png = bool(settings.png or settings.transparent_background)
    ext = "png" if png else "jpg"
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    name = "快捷证件照_%s_%s%s.%s" % (spec.name, stamp, ("_" + prefix) if prefix else "", ext)
    path = os.path.join(directory, name)
    image = Image.fromarray(np.ascontiguousarray(array), "RGBA" if png else "RGB")
    dpi = spec.dpi * (1 if prefix == "print" else max(1, min(3, int(settings.output_scale))))
    if png:
        image.save(path, format="PNG", dpi=(dpi, dpi))
    else:
        data = _encode_jpeg(image.convert("RGB"), int(settings.jpeg_quality),
                            int(settings.target_kb), dpi)
        with open(path, "wb") as f:
            f.write(data)
    return path


def save_print_sheet(array: np.ndarray, directory: str, spec, settings) -> str:
    os.makedirs(directory, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    path = os.path.join(directory, "快捷证件照_%s排版_%s.jpg" % (spec.name, stamp))
    image = Image.fromarray(np.ascontiguousarray(array), "RGB")
    image.save(path, format="JPEG", dpi=(300, 300), quality=92, subsampling=0, optimize=True)
    return path


def list_images(directory: str, recursive: bool = False):
    result = []
    if recursive:
        for root, _dirs, files in os.walk(directory):
            for name in files:
                if name.lower().endswith(IMAGE_EXTENSIONS):
                    result.append(os.path.join(root, name))
    else:
        for name in os.listdir(directory):
            if name.lower().endswith(IMAGE_EXTENSIONS):
                path = os.path.join(directory, name)
                if os.path.isfile(path):
                    result.append(path)
    return sorted(result)


def file_size_kb(path: str) -> int:
    try:
        return int(os.path.getsize(path) / 1024)
    except OSError:
        return 0
