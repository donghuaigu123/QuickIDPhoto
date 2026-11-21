# -*- coding: utf-8 -*-
"""MODNet 本地抠图（ONNX Runtime）。

关键点与 Android v1.9.0 一致：
  * hivision_modnet 按「整图直接拉伸到正方形」训练，绝不使用 letterbox 填充；
  * 归一化 (x - 127.5) / 127.5，NCHW 输入 1×3×512×512；
  * 输出 1×1×512×512 的软 alpha，用双线性逆映射回原裁剪尺寸。
模型与推理全部离线，不上传任何照片。
"""
import os
import threading
import numpy as np
import cv2

INPUT_SIZE = 512


class ModnetMatting:
    def __init__(self, model_path: str = None):
        self.model_path = model_path or os.path.join(
            os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
            "resources", "hivision_modnet.onnx")
        self._lock = threading.Lock()
        self._session = None
        self._failed = False
        self._error = None

    @property
    def is_ready(self) -> bool:
        return self._session is not None

    @property
    def error(self):
        return self._error

    def ensure_session(self) -> bool:
        if self._session is not None:
            return True
        if self._failed:
            return False
        with self._lock:
            if self._session is not None or self._failed:
                return self._session is not None
            try:
                import onnxruntime as ort
                opts = ort.SessionOptions()
                opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
                # v2.0.0 性能修复：ONNX 默认在多核机器上开满所有线程（本机 32 核），
                # 单次推理抢占全部 CPU，导致界面卡顿。限制为 2 线程，交互式预览更跟手，
                # 512×512 的 MODNet 在该规模下耗时几乎不变，但系统负载大幅下降。
                opts.intra_op_num_threads = 2
                opts.inter_op_num_threads = 1
                with open(self.model_path, "rb") as f:
                    model_bytes = f.read()
                self._session = ort.InferenceSession(
                    model_bytes, sess_options=opts,
                    providers=["CPUExecutionProvider"])
            except Exception as exc:  # 任何失败都降级为无模型路径，绝不使程序崩溃
                self._failed = True
                self._error = str(exc)
        return self._session is not None

    def matte(self, source: np.ndarray) -> np.ndarray:
        """source: H×W×3 RGB uint8 -> H×W float32 alpha in [0,1]。失败返回 None。"""
        if not self.ensure_session():
            return None
        h, w = source.shape[:2]
        try:
            resized = cv2.resize(source, (INPUT_SIZE, INPUT_SIZE),
                                 interpolation=cv2.INTER_LINEAR)
            rgb = resized.astype(np.float32)
            tensor = np.empty((1, 3, INPUT_SIZE, INPUT_SIZE), dtype=np.float32)
            tensor[0, 0] = (rgb[:, :, 0] - 127.5) / 127.5
            tensor[0, 1] = (rgb[:, :, 1] - 127.5) / 127.5
            tensor[0, 2] = (rgb[:, :, 2] - 127.5) / 127.5
            name = self._session.get_inputs()[0].name
            out = self._session.run(None, {name: tensor})[0]
            alpha_small = np.asarray(out).reshape(INPUT_SIZE, INPUT_SIZE).astype(np.float32)
            # 512 -> 原尺寸：半像素对齐的双线性逆映射（等价于 Kotlin mappedAlpha）
            alpha = cv2.resize(alpha_small, (w, h), interpolation=cv2.INTER_LINEAR)
            return np.clip(alpha, 0.0, 1.0)
        except Exception as exc:
            self._error = str(exc)
            return None
