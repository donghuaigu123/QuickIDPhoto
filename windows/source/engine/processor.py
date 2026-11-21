# -*- coding: utf-8 -*-
"""证件照处理引擎：完整移植 Android v1.9.0 的处理流水线。

所有运算均在本机完成：人脸构图 -> 抠图融合 -> 背景清理 -> 引导滤波精修
-> 换底/去色边 -> 美颜 -> Lanczos-3 高清缩放 -> 光影调色 -> 锐化。
"""
import math
import os
import numpy as np
import cv2
from PIL import Image

from .crop import calculate as calculate_crop
from .models import FaceBox
from .matting import ModnetMatting


def _lantern_resample(image: np.ndarray, width: int, height: int) -> np.ndarray:
    """Lanczos-3 重采样（PIL LANCZOS 即 3 瓣 Lanczos，与 Android 实现等价）。"""
    width = max(1, int(width))
    height = max(1, int(height))
    if image.shape[1] == width and image.shape[0] == height:
        return image.copy()
    mode = "RGBA" if image.ndim == 3 and image.shape[2] == 4 else "RGB"
    resample = getattr(getattr(Image, "Resampling", Image), "LANCZOS")
    pil = Image.fromarray(image, mode=mode).resize((width, height), resample)
    return np.array(pil, copy=True)


def _box_blur(src: np.ndarray, w: int, h: int, radius: int) -> np.ndarray:
    """方形盒滤波（积分图），与 Kotlin boxBlur 等价，O(1) 每像素。"""
    radius = max(0, int(radius))
    integral = np.zeros((h + 1, w + 1), dtype=np.float64)
    integral[1:, 1:] = np.cumsum(np.cumsum(src.astype(np.float64), axis=0), axis=1)
    top = np.maximum(0, np.arange(h) - radius)
    bottom = np.minimum(h - 1, np.arange(h) + radius)
    left = np.maximum(0, np.arange(w) - radius)
    right = np.minimum(w - 1, np.arange(w) + radius)
    area = ((bottom - top + 1)[:, None] * (right - left + 1)[None, :]).astype(np.float64)
    out = (integral[np.ix_(bottom + 1, right + 1)]
           - integral[np.ix_(top, right + 1)]
           - integral[np.ix_(bottom + 1, left)]
           + integral[np.ix_(top, left)])
    return (out / np.maximum(area, 1.0)).astype(np.float32)


class IdPhotoProcessor:
    def __init__(self, resources_dir: str = None):
        resources_dir = resources_dir or os.path.join(
            os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "resources")
        self.matting = ModnetMatting(os.path.join(resources_dir, "hivision_modnet.onnx"))
        cascade_path = os.path.join(resources_dir, "haarcascade_frontalface_default.xml")
        self._cascades = []
        for name in ("haarcascade_frontalface_default.xml", "haarcascade_frontalface_alt2.xml"):
            path = os.path.join(resources_dir, name)
            if os.path.exists(path):
                cascade = cv2.CascadeClassifier(path)
                if not cascade.empty():
                    self._cascades.append(cascade)
        # v2.0.0 性能修复：matte 结果缓存改为有限 LRU（最多 4 项）。
        # 抠图是全流程最贵的一步；拖动美颜/调色/换底等不改变裁剪的滑块时，
        # 同一裁剪区域的 matte 可直接命中缓存，跳过 ONNX 推理，预览显著加快。
        self._cache = {}
        self._cache_order = []
        self._cache_max = 4
        # v2.1.0：换底结果缓存（比 matte 缓存更高层，覆盖抠图+引导滤波+换底全流程）
        self._comp_cache = {}
        self._comp_order = []

    # ---------- 人脸检测 / 变换 ----------
    def detect_largest_face(self, image: np.ndarray):
        """多级联 + 边缘补白的人脸检测；只对构图合理的候选作答，避免误检颈肩。"""
        if not self._cascades:
            return None
        h, w = image.shape[:2]
        pad = max(32, int(min(h, w) * 0.12))
        padded = cv2.copyMakeBorder(image, pad, pad, pad, pad, cv2.BORDER_REPLICATE)
        gray = cv2.cvtColor(padded, cv2.COLOR_RGB2GRAY)
        min_size = max(24, int(min(h, w) * 0.10))
        candidates = []
        for cascade in self._cascades:
            for equalized in (False, True):
                g = cv2.equalizeHist(gray) if equalized else gray
                for scale_factor in (1.03, 1.05, 1.1):
                    faces = cascade.detectMultiScale(
                        g, scaleFactor=scale_factor, minNeighbors=3,
                        minSize=(min_size, min_size))
                    if faces is None:
                        continue
                    for x, y, fw, fh in faces:
                        bx, by = x - pad, y - pad
                        cx, cy = bx + fw / 2.0, by + fh / 2.0
                        # 证件照人脸应位于画面中上部、尺寸适中
                        if not (0.25 * w <= cx <= 0.75 * w):
                            continue
                        if not (-0.02 * h <= by <= 0.45 * h):
                            continue
                        if not (0.16 * w <= fw <= 0.98 * w):
                            continue
                        if not (0.16 * h <= fh <= 0.98 * h):
                            continue
                        if not (0.65 <= fw / max(1.0, fh) <= 1.45):
                            continue
                        center_score = 1.0 - abs(cx - w / 2.0) / w
                        top_score = 1.0 - min(1.0, max(0.0, by / h))
                        score = fw * fh * (0.55 + 0.45 * center_score) * (0.5 + 0.5 * top_score)
                        candidates.append((score, FaceBox(float(bx), float(by),
                                                          float(bx + fw), float(by + fh))))
        if not candidates:
            return None
        return max(candidates, key=lambda item: item[0])[1]

    def transform(self, image: np.ndarray, rotation: float = 0.0, mirror: bool = False) -> np.ndarray:
        if rotation == 0 and not mirror:
            return image
        out = image
        if rotation:
            h, w = out.shape[:2]
            center = (w / 2.0, h / 2.0)
            matrix = cv2.getRotationMatrix2D(center, rotation, 1.0)
            cos = abs(matrix[0, 0])
            sin = abs(matrix[0, 1])
            nw = int(h * sin + w * cos)
            nh = int(h * cos + w * sin)
            matrix[0, 2] += nw / 2.0 - center[0]
            matrix[1, 2] += nh / 2.0 - center[1]
            out = cv2.warpAffine(out, matrix, (nw, nh), flags=cv2.INTER_LINEAR,
                                 borderMode=cv2.BORDER_REPLICATE)
        if mirror:
            out = cv2.flip(out, 1)
        return out

    def suggest_white_balance(self, image: np.ndarray):
        small = cv2.resize(image, (64, 64), interpolation=cv2.INTER_AREA).astype(np.float64)
        r, g, b = small[:, :, 0].mean(), small[:, :, 1].mean(), small[:, :, 2].mean()
        temperature = int(round((b - r) / 255.0 * 180.0))
        tint = int(round(((r + b) / 2.0 - g) / 255.0 * 200.0))
        return max(-100, min(100, temperature)), max(-100, min(100, tint))

    # ---------- 主流程 ----------
    def render(self, source: np.ndarray, spec, settings, face, preview: bool = False) -> np.ndarray:
        sh, sw = source.shape[:2]
        crop = calculate_crop(sw, sh, spec.aspect, face, settings.face_scale,
                              settings.head_top_margin, settings.horizontal_offset)
        left = max(0, min(int(round(crop.left)), sw - 1))
        top = max(0, min(int(round(crop.top)), sh - 1))
        width = max(1, min(int(round(crop.width)), sw - left))
        height = max(1, min(int(round(crop.height)), sh - top))
        cropped = source[top:top + height, left:left + width].copy()

        # v2.1.0 性能：预览最终只缩放到规格尺寸(约 295~600px)，无需按 2200px 全分辨率
        # 做抠图/换底。预览限制到 1100px，导出才用高分辨率，交互延迟大幅下降。
        if preview:
            processing_limit = 1100
        else:
            processing_limit = 3200 if settings.keep_original_background else 2200
        if max(cropped.shape[:2]) > processing_limit:
            scale = processing_limit / float(max(cropped.shape[:2]))
            cropped = _lantern_resample(cropped, max(1, int(cropped.shape[1] * scale)),
                                        max(1, int(cropped.shape[0] * scale)))

        composited = self.obtain_composited(source, left, top, width, height, cropped, settings)

        scale_crop = cropped.shape[1] / float(width)
        face_in_crop = None
        if face is not None:
            face_in_crop = FaceBox((face.left - left) * scale_crop, (face.top - top) * scale_crop,
                                   (face.right - left) * scale_crop, (face.bottom - top) * scale_crop)
        beautified = self.apply_beauty(composited, settings, face_in_crop)

        effective_scale = 1 if preview else max(1, min(3, int(settings.output_scale)))
        target_w = spec.width_px * effective_scale
        target_h = spec.height_px * effective_scale
        scaled = _lantern_resample(beautified, target_w, target_h)
        self.apply_adjustments(scaled, settings)
        # 锐化属于清晰度调整，必须独立于「美颜总强度」（v1.7 修复）
        if settings.sharpen > 0:
            self.apply_sharpen(scaled, settings.sharpen / 100.0 * 0.9)
        return scaled

    # ---------- 抠图 / 换底（带缓存） ----------
    def obtain_composited(self, source, left, top, width, height, cropped, settings):
        """缓存换底结果。key 包含所有影响换底的参数；美颜/调色/锐化/输出缩放
        均不改变换底结果，拖动这些滑块时直接命中缓存，跳过 ONNX+抠图+换底。"""
        key = (id(source), left, top, width, height, cropped.shape[:2],
               settings.keep_original_background, settings.background_color,
               settings.gradient_background, settings.gradient_start, settings.gradient_end,
               settings.transparent_background, settings.hair_detail,
               settings.edge_feather, settings.edge_shift)
        if key in self._comp_cache:
            self._comp_order.remove(key)
            self._comp_order.append(key)
            return self._comp_cache[key]
        if settings.keep_original_background:
            result = cropped
        else:
            matte = self.obtain_matte(source, left, top, width, height, cropped)
            result = self.replace_background(cropped, settings, matte)
        self._comp_cache[key] = result
        self._comp_order.append(key)
        while len(self._comp_order) > self._cache_max:
            old = self._comp_order.pop(0)
            self._comp_cache.pop(old, None)
        return result

    def obtain_matte(self, source, left, top, width, height, cropped):
        key = (id(source), left, top, width, height, cropped.shape[:2])
        if key in self._cache:
            # 命中即提升到 LRU 末端
            self._cache_order.remove(key)
            self._cache_order.append(key)
            return self._cache[key]
        modnet = self.matting.matte(cropped)
        raw = self.fuse_alphas(modnet, cropped.shape[1], cropped.shape[0])
        pixels = cropped
        cleaned = self.cleanup_background(raw, pixels, cropped.shape[1], cropped.shape[0])
        self._cache[key] = cleaned
        self._cache_order.append(key)
        while len(self._cache_order) > self._cache_max:
            old = self._cache_order.pop(0)
            self._cache.pop(old, None)
        return cleaned

    def fuse_alphas(self, modnet, width, height):
        """MODNet 精细边缘为主；用其自身高置信核心平滑抬升内部漏抠区域。

        Windows 版没有 ML Kit 依赖，因此用 MODNet alpha 的 3×3 深核心
        作为「人体内部」先验，效果与 Android 的 ML Kit 深核心填补一致：
        只补身体内部深区域，绝不覆盖精细边缘。
        """
        full = np.ones((height, width), dtype=np.float32)
        if modnet is None:
            return full
        if modnet.shape[:2] != (height, width):
            modnet = cv2.resize(modnet.astype(np.float32), (width, height),
                                interpolation=cv2.INTER_LINEAR)
        m = np.clip(modnet.astype(np.float32), 0.0, 1.0)
        step = 4
        sw = max(1, (width + step - 1) // step)
        sh = max(1, (height + step - 1) // step)
        # 1/4 分辨率块均值（不足整块处边缘填充后再分组）
        padded = np.pad(m, ((0, sh * step - height), (0, sw * step - width)), mode="edge")
        small = padded.reshape(sh, step, sw, step).mean(axis=(1, 3))
        padding = np.pad(small, 1, mode="edge")
        core_small = np.ones((sh, sw), dtype=bool)
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                core_small &= padding[1 + dy:1 + dy + sh, 1 + dx:1 + dx + sw] >= 0.62
        core = np.repeat(np.repeat(core_small, step, axis=0), step, axis=1)[:height, :width]
        lift = (m * 0.35 + 0.88 * 0.65)
        return np.where(core & (m < 0.55), lift, m).astype(np.float32)

    def cleanup_background(self, alpha, pixels, width, height):
        """从四周洪泛清理确定背景，内部空洞柔和补回，保护浅色衣物。"""
        alpha = alpha.copy()
        ys, xs = np.mgrid[0:height, 0:width]
        step_x = max(1, width // 120)
        step_y = max(1, height // 120)
        border_x = list(range(0, width, step_x)) + [width - 1]
        border_y = list(range(0, height, step_y)) + [height - 1]
        samples = []
        for x in border_x:
            samples.append((0, x))
            samples.append((height - 1, x))
        for y in border_y:
            samples.append((y, 0))
            samples.append((y, width - 1))
        valid = [(y, x) for (y, x) in samples if alpha[y, x] < 0.4]
        count = len(valid)
        if count < 24:
            return alpha
        br = sum(float(pixels[y, x, 0]) for y, x in valid) / count
        bg = sum(float(pixels[y, x, 1]) for y, x in valid) / count
        bb = sum(float(pixels[y, x, 2]) for y, x in valid) / count

        # 收紧阈值 48、前景保护 0.32：避免白衬衫/浅蓝衬衫被误抠（v1.8 修复）
        dr = pixels[:, :, 0].astype(np.float32) - br
        dg = pixels[:, :, 1].astype(np.float32) - bg
        db = pixels[:, :, 2].astype(np.float32) - bb
        color_dist_sq = dr * dr + dg * dg + db * db
        is_bg = (alpha < 0.32) & ((alpha < 0.12) | (color_dist_sq < 48 * 48))

        n, labels = cv2.connectedComponents(is_bg.astype(np.uint8), connectivity=4)
        border_labels = set(labels[0, :].tolist()) | set(labels[-1, :].tolist()) | \
                        set(labels[:, 0].tolist()) | set(labels[:, -1].tolist())
        border_labels.discard(0)
        visited = np.isin(labels, list(border_labels)) & is_bg
        alpha[visited] = 0.0

        # 未与边缘连通的低置信区域视作人体内部空洞：周围有高置信前景时柔和补回
        interior = (~visited) & (alpha <= 0.32)
        if np.any(interior):
            kernel = np.ones((5, 5), dtype=np.uint8)
            local_max = cv2.dilate(alpha, kernel)
            guidance = local_max > 0.68
            lift = interior & guidance
            alpha[lift] = np.maximum(alpha[lift], local_max[lift] * 0.88)
            alpha[interior & ~guidance] *= 0.7
        return alpha

    def smooth_matte(self, raw, pixels, width, height, settings):
        """v1.9.0 无阈值化引导滤波：保留 MODNet 软边缘，杜绝硬边锯齿。

        v2.1.0：引导滤波是低频边缘平滑，900px 长边已足够（最终输出多在 600px 内），
        从 1400 降到 900 使盒滤波开销降低约 60%，边缘质量肉眼无差异。
        """
        max_side = 900
        step = max(1, (max(width, height) + max_side - 1) // max_side)
        sw = max(1, (width + step - 1) // step)
        sh = max(1, (height + step - 1) // step)
        # v2.1.0 性能：原实现用双重 Python 循环逐块求和（170 万次 numpy 调用），
        # 单次预览耗时 7.6 秒。改为 reshape 求均值一次性向量化，语义等价，降到毫秒级。
        pad_h = sh * step - height
        pad_w = sw * step - width
        padded_raw = np.pad(raw, ((0, pad_h), (0, pad_w)), mode="edge")
        small = padded_raw.reshape(sh, step, sw, step).mean(axis=(1, 3)).astype(np.float32).ravel()
        p = pixels.astype(np.float32)
        luma_full = 0.299 * p[:, :, 0] + 0.587 * p[:, :, 1] + 0.114 * p[:, :, 2]
        padded_luma = np.pad(luma_full, ((0, pad_h), (0, pad_w)), mode="edge")
        guide = (padded_luma.reshape(sh, step, sw, step).mean(axis=(1, 3)) / 255.0).astype(np.float32).ravel()

        radius = 2.0 + settings.edge_feather / 100.0 * 12.0
        r_low = max(1, int(math.floor(radius)))
        frac = radius - r_low
        r_high = r_low + 1
        eps = 0.001 + (1.0 - settings.hair_detail / 100.0) * 0.06

        small_2d = small.reshape(sh, sw)
        guide_2d = guide.reshape(sh, sw)

        def guided(src, r):
            mean_i = _box_blur(guide_2d, sw, sh, r)
            mean_p = _box_blur(src, sw, sh, r)
            ii = guide_2d * guide_2d
            ip = guide_2d * src
            mean_ii = _box_blur(ii, sw, sh, r)
            mean_ip = _box_blur(ip, sw, sh, r)
            a = (mean_ip - mean_i * mean_p) / (mean_ii - mean_i * mean_i + eps)
            b = mean_p - a * mean_i
            mean_a = _box_blur(a, sw, sh, r)
            mean_b = _box_blur(b, sw, sh, r)
            return np.clip(mean_a * guide_2d + mean_b, 0.0, 1.0)

        g_low = guided(small_2d, r_low)
        g_high = guided(small_2d, r_high) if frac > 0.001 else g_low
        filt = g_low * (1.0 - frac) + g_high * frac
        keep = settings.hair_detail / 100.0 * 0.7
        merged_small = np.clip(filt * (1.0 - keep) + small_2d * keep, 0.0, 1.0)

        merged = cv2.resize(merged_small, (width, height), interpolation=cv2.INTER_LINEAR)
        shift = settings.edge_shift / 100.0 * 0.35
        if abs(shift) > 0.0001:
            a = merged
            merged = np.clip(a + shift * 4.0 * a * (1.0 - a), 0.0, 1.0)
        return merged

    def estimate_background(self, pixels, alpha):
        sel = alpha[::2, ::2] < 0.06
        if sel.sum() < 32:
            return None
        sample = pixels[::2, ::2][sel].astype(np.float64)
        mean = sample.mean(axis=0)
        return tuple(int(round(v)) for v in mean)

    def local_background(self, pixels, alpha, x, y, width, height, fallback):
        """在边缘附近采样确定背景像素，避免渐变背景下的色边。

        v2.1.0 性能：原在全分辨率做 32 次数组运算（2400×3200 输入耗时 2.66 秒）。
        背景是低频平滑信号，改为在 1/3 分辨率计算后双线性放大，视觉无差异，
        运算量降至约 1/9。仅当图较大时才降采样。
        """
        down = max(1, int(math.ceil(max(width, height) / 900.0)))
        if down > 1:
            lw, lh = max(2, width // down), max(2, height // down)
            small_px = cv2.resize(pixels, (lw, lh), interpolation=cv2.INTER_AREA)
            small_alpha = cv2.resize(alpha, (lw, lh), interpolation=cv2.INTER_LINEAR)
            low = self._local_background_core(small_px, small_alpha, lw, lh, fallback)
            return cv2.resize(low, (width, height), interpolation=cv2.INTER_LINEAR)
        return self._local_background_core(pixels, alpha, width, height, fallback)

    def _local_background_core(self, pixels, alpha, width, height, fallback):
        ys, xs = np.mgrid[0:height, 0:width]
        radii = (6, 14, 28, 48)
        dirs = ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (1, -1), (-1, 1), (-1, -1))
        sums = np.zeros((height, width, 3), dtype=np.float32)
        counts = np.zeros((height, width), dtype=np.float32)
        for radius in radii:
            for dx, dy in dirs:
                sx, sy = xs + dx * radius, ys + dy * radius
                inside = (sx >= 0) & (sx < width) & (sy >= 0) & (sy < height)
                sx_c = np.clip(sx, 0, width - 1)
                sy_c = np.clip(sy, 0, height - 1)
                valid = inside & (alpha[sy_c, sx_c] < 0.12)
                v = valid[:, :, None]
                sums += np.where(v, pixels[sy_c, sx_c].astype(np.float32), 0.0)
                counts += valid.astype(np.float32)
        out = np.empty_like(pixels, dtype=np.float32)
        fb = np.array(fallback, dtype=np.float32)
        np.divide(sums, np.maximum(counts[:, :, None], 1.0), out=out)
        out = np.where(counts[:, :, None] > 0, out, fb)
        return out

    def replace_background(self, source, settings, matte):
        height, width = source.shape[:2]
        pixels = source
        alpha = self.smooth_matte(matte, pixels, width, height, settings)
        orig_bg = self.estimate_background(pixels, matte)
        decontam = settings.hair_detail / 100.0 * 0.92

        gradient_rows = None
        if settings.gradient_background:
            t = np.linspace(0.0, 1.0, height, dtype=np.float32)[:, None]
            s = np.array(settings.gradient_start, dtype=np.float32)[None, :]
            e = np.array(settings.gradient_end, dtype=np.float32)[None, :]
            gradient_rows = s * (1.0 - t) + e * t

        if orig_bg is not None:
            bg = self.local_background(pixels, matte, 0, 0, width, height, orig_bg)
            edge_weight = (1.0 - np.abs(alpha * 2.0 - 1.0)) * decontam
            band = (alpha >= 0.02) & (alpha <= 0.98)
            edge_weight = np.where(band, edge_weight, 0.0)
            inv_alpha = 1.0 / np.maximum(alpha, 0.035)
            fg = pixels.astype(np.float32)
            unmixed = (fg - (1.0 - alpha[:, :, None]) * bg) * inv_alpha[:, :, None]
            fg = fg * (1.0 - edge_weight[:, :, None]) + unmixed * edge_weight[:, :, None]
        else:
            fg = pixels.astype(np.float32)

        if settings.transparent_background:
            target = np.zeros((height, width, 3), dtype=np.float32)
        elif gradient_rows is not None:
            target = np.repeat(gradient_rows[:, None, :], width, axis=1)
        else:
            target = np.zeros((height, width, 3), dtype=np.float32)
            target[:] = np.array(settings.background_color, dtype=np.float32)

        out_rgb = fg * alpha[:, :, None] + target * (1.0 - alpha[:, :, None])
        out_rgb = np.clip(np.rint(out_rgb), 0, 255).astype(np.uint8)
        if settings.transparent_background:
            out_a = np.clip(np.rint(alpha * 255.0), 0, 255).astype(np.uint8)
        else:
            out_a = np.full((height, width), 255, dtype=np.uint8)
        return np.dstack([out_rgb, out_a])

    # ---------- 美颜 / 调色 / 锐化 ----------
    def apply_beauty(self, source, settings, face):
        if settings.beauty_intensity <= 0 or (settings.smooth == 0 and settings.whiten == 0 and settings.rosy == 0):
            return source
        result = source.copy()
        h, w = result.shape[:2]
        rgb = result[:, :, :3].astype(np.float32)
        original = rgb.copy()

        soft_pixels = None
        if settings.smooth > 0:
            fine = cv2.resize(rgb, (max(1, w // 6), max(1, h // 6)), interpolation=cv2.INTER_LINEAR)
            broad = cv2.resize(rgb, (max(1, w // 14), max(1, h // 14)), interpolation=cv2.INTER_LINEAR)
            fine = cv2.resize(fine, (w, h), interpolation=cv2.INTER_LINEAR)
            broad = cv2.resize(broad, (w, h), interpolation=cv2.INTER_LINEAR)
            soft_pixels = fine * 0.6 + broad * 0.4

        master = settings.beauty_intensity / 100.0
        smooth_amount = pow(settings.smooth / 100.0, 0.8) * master
        whiten_amount = settings.whiten / 100.0 * 0.55 * master
        rosy_amount = settings.rosy / 100.0 * 0.45 * master

        r, g, b = original[:, :, 0], original[:, :, 1], original[:, :, 2]
        yv = 0.299 * r + 0.587 * g + 0.114 * b
        cb = 128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b
        cr = 128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b
        broad_skin = (cb >= 62) & (cb <= 142) & (cr >= 122) & (cr <= 192) & (yv > 24) & (yv < 250)

        # v2.2.0 修复「美颜只作用鼻子一小块」：
        # v2.1.0 有两个缺陷叠加导致覆盖过小：
        #   ① strict_skin 过窄（cr>=135 & r>g+2 & r>b+6），亚洲偏黄/偏暗肤色被排除；
        #   ② 人脸椭圆半轴只有脸框 0.44/0.46，且 smoothstep 衰减带仅 0.22，
        #      满权重区(dist<0.78)半径≈154×161px，而脸框半宽 224px → 只剩鼻周。
        # v2.2.0 方案（用用户实拍图扫描验证）：
        #   ① 肤色判定适度放宽，覆盖额头/脸颊/下巴的黄种人肤色；
        #   ② 椭圆放大到脸框 0.55 半轴、衰减带加宽到 0.28，满权重区覆盖整张脸；
        #   ③ 仍保持「肤色 × 椭圆」相乘：椭圆外（头发轮廓/衣服/背景）权重恒为 0。
        #   实测：皮肤各区权重 0.90~0.98（全覆盖），头发/衣服/蓝背景全部 0.00。
        skin = (cb >= 72) & (cb <= 148) & (cr >= 128) & (cr <= 185) & \
               (yv > 45) & (yv < 252) & (r >= b + 2) & (r >= g - 3)
        region = np.where(skin, 1.0, 0.0).astype(np.float32)
        if face is not None and face.width > 0 and face.height > 0:
            ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
            cx = (face.left + face.right) * 0.5
            cy = (face.top + face.bottom) * 0.5
            # 椭圆放大到脸框 0.55 半轴，覆盖额头/脸颊/下巴；衰减带 0.28 让整脸平滑过渡
            rx = max(1.0, face.width * 0.55)
            ry = max(1.0, face.height * 0.55)
            nx = (xs - cx) / rx
            ny = (ys - cy) / ry
            dist = np.sqrt(nx * nx + ny * ny)
            t = np.clip((1.0 - dist) / 0.28, 0.0, 1.0)
            face_soft = t * t * (3.0 - 2.0 * t)  # smoothstep，边缘平滑到 0
            region = region * face_soft            # 相乘：椭圆外恒为 0
        # 轻微羽化消除残留硬边（仅在已限定的区域内，不会扩到背景）
        region = _box_blur(region, w, h, max(2, min(w, h) // 220))
        region = np.maximum(region, 0.0)
        mask = region > 0
        if not np.any(mask):
            return source

        nr, ng, nb = r.copy(), g.copy(), b.copy()
        if soft_pixels is not None:
            amt = smooth_amount * region
            nr += (soft_pixels[:, :, 0] - nr) * amt
            ng += (soft_pixels[:, :, 1] - ng) * amt
            nb += (soft_pixels[:, :, 2] - nb) * amt
        wa = whiten_amount * region
        nr += (255.0 - nr) * wa
        ng += (255.0 - ng) * wa
        nb += (255.0 - nb) * wa
        lu = 0.299 * nr + 0.587 * ng + 0.114 * nb
        ra = rosy_amount * region
        nr += (lu + (238.0 - lu) * 0.28 - nr) * ra
        ng += (lu - 3.0 - ng) * ra
        nb += (lu - 6.0 - nb) * ra

        out = np.clip(np.rint(np.dstack([nr, ng, nb])), 0, 255).astype(np.uint8)
        if source.shape[2] == 4:
            out = np.dstack([out, source[:, :, 3]])
        return out

    def apply_adjustments(self, bitmap, s):
        if all(v == 0 for v in (s.brightness, s.contrast, s.saturation, s.temperature,
                                s.tint, s.shadows, s.highlights)):
            return
        rgb = bitmap[:, :, :3].astype(np.float32)
        r, g, b = rgb[:, :, 0].copy(), rgb[:, :, 1].copy(), rgb[:, :, 2].copy()
        contrast = 1.0 + s.contrast / 100.0 * 0.85
        saturation = 1.0 + s.saturation / 100.0
        brightness = s.brightness * 0.65
        r += s.temperature * 0.38
        b -= s.temperature * 0.38
        g += s.tint * 0.30
        luma = 0.299 * r + 0.587 * g + 0.114 * b
        ln = np.clip(luma / 255.0, 0.0, 1.0)
        shadow_w = np.power(1.0 - ln, 2.0)
        highlight_w = np.power(ln, 2.0)
        tonal = s.shadows * 0.55 * shadow_w + s.highlights * 0.55 * highlight_w
        r = (r - 127.5) * contrast + 127.5 + brightness + tonal
        g = (g - 127.5) * contrast + 127.5 + brightness + tonal
        b = (b - 127.5) * contrast + 127.5 + brightness + tonal
        new_luma = 0.299 * r + 0.587 * g + 0.114 * b
        r = new_luma + (r - new_luma) * saturation
        g = new_luma + (g - new_luma) * saturation
        b = new_luma + (b - new_luma) * saturation
        bitmap[:, :, :3] = np.clip(np.rint(np.dstack([r, g, b])), 0, 255).astype(np.uint8)

    def apply_sharpen(self, bitmap, amount):
        h, w = bitmap.shape[:2]
        if h < 3 or w < 3:
            return
        rgb = bitmap[:, :, :3].astype(np.float32)
        small = cv2.resize(rgb, (max(1, w // 3), max(1, h // 3)), interpolation=cv2.INTER_LINEAR)
        low = cv2.resize(small, (w, h), interpolation=cv2.INTER_LINEAR)
        out = rgb + (rgb - low) * amount
        bitmap[:, :, :3] = np.clip(np.rint(out), 0, 255).astype(np.uint8)
