# -*- coding: utf-8 -*-
"""数据模型：与 Android v1.9.0 完全一致的规格、背景与全部可调参数。"""
from dataclasses import dataclass, replace


@dataclass
class PhotoSpec:
    name: str
    width_mm: float
    height_mm: float
    width_px: int
    height_px: int
    dpi: int = 300
    note: str = ""

    @property
    def aspect(self) -> float:
        return self.width_px / float(self.height_px)

    @property
    def label(self) -> str:
        return f"{self.name}  {self.width_px}×{self.height_px}px"

    @classmethod
    def preset_list(cls):
        return [
            cls("一寸", 25, 35, 295, 413, note="简历、考试常用"),
            cls("大一寸", 33, 48, 390, 567, note="护照、签证常用"),
            cls("二寸", 35, 49, 413, 579, note="证书、材料常用"),
            cls("小二寸", 35, 45, 413, 531, note="部分签证常用"),
            cls("中国护照", 33, 48, 390, 567, note="33×48mm"),
            cls("港澳通行证", 33, 48, 390, 567, note="33×48mm"),
            cls("美国签证", 51, 51, 602, 602, note="51×51mm"),
            cls("日本签证", 45, 45, 531, 531, note="45×45mm"),
            cls("申根签证", 35, 45, 413, 531, note="35×45mm"),
            cls("社保卡", 26, 32, 358, 441, note="358×441px"),
            cls("驾驶证", 22, 32, 260, 378, note="22×32mm"),
            cls("公务员考试", 25, 35, 295, 413, note="常用报名尺寸"),
            cls("教师资格证", 25, 35, 295, 413, note="请核对报名公告"),
            cls("英语四六级", 24, 32, 144, 192, note="部分考点要求144×192", dpi=150),
            cls("简历形象照", 30, 40, 354, 472, note="3:4竖版"),
        ]

    @classmethod
    def color_list(cls):
        # 名称 -> (R, G, B)
        return [
            ("纯白", (255, 255, 255)),
            ("证件蓝", (67, 142, 219)),
            ("标准蓝", (0, 102, 204)),
            ("证件红", (220, 53, 69)),
            ("浅灰", (238, 240, 244)),
            ("深蓝", (33, 71, 120)),
        ]


@dataclass
class FaceBox:
    left: float
    top: float
    right: float
    bottom: float

    @property
    def width(self) -> float:
        return self.right - self.left

    @property
    def height(self) -> float:
        return self.bottom - self.top

    @property
    def center_x(self) -> float:
        return (self.left + self.right) / 2.0


@dataclass
class CropBox:
    left: float
    top: float
    right: float
    bottom: float

    @property
    def width(self) -> float:
        return self.right - self.left

    @property
    def height(self) -> float:
        return self.bottom - self.top


@dataclass
class EditorSettings:
    # 构图
    face_scale: float = 0.57
    head_top_margin: float = 0.10
    horizontal_offset: float = 0.0
    # 光影调色
    brightness: int = 0
    contrast: int = 0
    saturation: int = 0
    temperature: int = 0
    tint: int = 0
    shadows: int = 0
    highlights: int = 0
    # 美颜
    smooth: int = 0
    whiten: int = 0
    rosy: int = 0
    sharpen: int = 0
    beauty_intensity: int = 100
    # 抠图
    edge_feather: int = 45
    edge_shift: int = 0
    hair_detail: int = 50
    # 输出
    output_scale: int = 2
    gradient_background: bool = False
    gradient_start: tuple = (58, 123, 213)
    gradient_end: tuple = (178, 226, 248)
    target_kb: int = 0
    print_layout: int = 0
    print_background_color: tuple = (255, 255, 255)
    background_color: tuple = (255, 255, 255)
    transparent_background: bool = False
    keep_original_background: bool = True
    jpeg_quality: int = 95
    png: bool = False

    def copy_values(self) -> "EditorSettings":
        return replace(self)


# 与 Kotlin `PhotoSpec.presets` / `PhotoSpec.backgroundColors` 同名成员，
# 便于界面与引擎按 Android 侧习惯直接访问。
PhotoSpec.presets = PhotoSpec.preset_list()
PhotoSpec.background_colors = PhotoSpec.color_list()


LAYOUTS = [
    ("六寸相纸", 1800, 1200),
    ("五寸相纸", 1500, 1050),
    ("A4 纸张", 3508, 2480),
    ("3R 相纸", 1050, 1500),
    ("4R 相纸", 1200, 1800),
]

GRADIENTS = [
    ("美式蓝白", (58, 123, 213), (178, 226, 248)),
    ("晨曦暖橙", (255, 154, 86), (255, 222, 180)),
    ("暮紫", (142, 116, 255), (222, 208, 255)),
    ("青绿", (26, 188, 156), (168, 233, 208)),
]
