# -*- coding: utf-8 -*-
"""图片读写：EXIF 方向、JPG 大小限制压缩、DPI 元数据、缩略图。"""
import io
import os
import time
import uuid
from datetime import datetime
import numpy as np
from PIL import Image, ImageOps

IMAGE_EXTENSIONS = (".jpg", ".jpeg", ".png", ".bmp", ".webp", ".gif", ".tif", ".tiff")


def _atomic_write(path: str, data: bytes, retries: int = 4) -> None:
    """原子写入字节到 path，规避间歇性 "encoder error -2 when writing image file"。

    该错误的真实成因几乎都是「目标文件正被占用」（同秒重名、杀毒软件实时扫描、
    资源管理器预览句柄未释放）或磁盘瞬时不可写，并非图像编码本身。措施：
      1. 先写同目录临时文件（带 pid+uuid），fsync 后 os.replace 原子替换；
      2. 失败则换新临时名重试，每次短暂退避，等待占用句柄释放。
    """
    directory = os.path.dirname(path) or "."
    os.makedirs(directory, exist_ok=True)
    last = None
    for attempt in range(retries):
        tmp = os.path.join(
            directory,
            ".~tmp_%d_%s_%d" % (os.getpid(), uuid.uuid4().hex[:10], attempt),
        )
        try:
            with open(tmp, "wb") as f:
                f.write(data)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp, path)
            return
        except OSError as exc:
            last = exc
            try:
                if os.path.exists(tmp):
                    os.remove(tmp)
            except OSError:
                pass
            time.sleep(0.05 * (attempt + 1))
    raise last


def _unique_stamp() -> str:
    """秒级时间戳 + 毫秒 + 短随机后缀，确保并发/连续保存绝不重名。"""
    return datetime.now().strftime("%Y%m%d_%H%M%S") + "_%03d_%s" % (
        int(time.time() * 1000) % 1000, uuid.uuid4().hex[:6])


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
    # 强制 RGB（RGBA 直接存 JPEG 会抛 "cannot write mode RGBA as JPEG"，
    # 旧版某些路径下表现为编码器异常）。
    if image.mode != "RGB":
        image = image.convert("RGB")
    d = max(1, int(dpi))

    def encode(q, optimize=True):
        stream = io.BytesIO()
        try:
            image.save(stream, format="JPEG", quality=int(q), subsampling=0,
                       optimize=optimize, dpi=(d, d))
        except Exception:
            # 兼容 Pillow 9.5 在部分图像上 optimize 编码器报错：降级重试
            stream = io.BytesIO()
            image.save(stream, format="JPEG", quality=int(q),
                       subsampling=0, optimize=False, dpi=(d, d))
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
    # v2.2.0：文件名加入毫秒+随机后缀，杜绝连续/批量保存同秒重名导致的写入冲突
    stamp = _unique_stamp()
    name = "快捷证件照_%s_%s%s.%s" % (spec.name, stamp, ("_" + prefix) if prefix else "", ext)
    path = os.path.join(directory, name)
    arr = np.ascontiguousarray(array)
    # 按实际通道数选择 mode，避免 array 通道数与期望不符时
    # "buffer is not large enough / cannot write mode" 错误
    if png:
        if arr.ndim == 3 and arr.shape[2] == 4:
            image = Image.fromarray(arr, "RGBA")
        else:
            image = Image.fromarray(arr, "RGB").convert("RGBA")
    else:
        image = Image.fromarray(arr, "RGB") if (arr.ndim == 3 and arr.shape[2] >= 3) \
            else Image.fromarray(arr, "L").convert("RGB")
    dpi = max(1, int(spec.dpi)) * (1 if prefix == "print" else max(1, min(3, int(settings.output_scale))))
    if png:
        stream = io.BytesIO()
        image.save(stream, format="PNG", dpi=(dpi, dpi))
        _atomic_write(path, stream.getvalue())
    else:
        data = _encode_jpeg(image, int(settings.jpeg_quality), int(settings.target_kb), dpi)
        _atomic_write(path, data)
    return path


def save_print_sheet(array: np.ndarray, directory: str, spec, settings) -> str:
    os.makedirs(directory, exist_ok=True)
    stamp = _unique_stamp()
    path = os.path.join(directory, "快捷证件照_%s排版_%s.jpg" % (spec.name, stamp))
    image = Image.fromarray(np.ascontiguousarray(array), "RGB")
    stream = io.BytesIO()
    try:
        image.save(stream, format="JPEG", dpi=(300, 300), quality=92, subsampling=0, optimize=True)
    except Exception:
        stream = io.BytesIO()
        image.save(stream, format="JPEG", dpi=(300, 300), quality=92, subsampling=0, optimize=False)
    _atomic_write(path, stream.getvalue())
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
