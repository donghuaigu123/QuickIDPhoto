# 快捷证件照（Quick ID Photo）

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

**[下载最新 APK](https://github.com/donghuaigu123/QuickIDPhoto/releases/latest)** · **[项目仓库](https://github.com/donghuaigu123/QuickIDPhoto)**

一款以**隐私、本地处理和专业构图**为核心的开源 Android 证件照工具。支持相册单张编辑、多照片批量处理，以及带人脸和人形定位轮廓的相机拍摄。

## 功能

- **智能拍摄引导**：标准证件照比例人形剪影（头顶留白 8.5%、头高 52%、双肩定位），实时人脸居中/距离提示，网格、定时、闪光灯、前后镜头切换
- **MODNet 发丝级抠图**：内置 hivision_modnet 模型（ONNX Runtime 本地推理），发丝边缘保真、背景色去污染、边缘柔化/轮廓/发丝细节可调；ML Kit 自动降级
- **人脸自动构图**：检测最大人脸按规范裁剪，脸部占比、头顶留白、水平位置微调，拖动与双指缩放
- **背景系统**：纯色预设、可视化 HSV 取色器、透明 PNG、四款渐变背景与自定义双色渐变（复刻 HivisionIDPhotos 美式证件照）
- **高清输出**：Lanczos-3 重采样，处理链路高分辨率，1x / 2x / 3x 放大输出，DPI 写入文件元数据
- **文件大小限制**：JPG 质量按目标 KB 自动二分压缩（对应 HivisionIDPhotos `-k` 参数，适配报名系统 20–200KB 要求）
- **美颜精修**：总强度 + 皮肤平滑、提亮、红润、锐化，自然/清透/精致预设
- **光影调色**：亮度、对比度、饱和度、色温、色调、阴影、高光、一键自动白平衡
- **排版照**：六寸/五寸/A4/3R/4R 五种 300DPI 排版，自定义排版底色与裁切辅助线（复刻 HivisionIDPhotos 排版功能）
- **批量处理**：多张照片统一规格、背景与美颜参数一次导出
- **隐私**：不声明 Internet 权限，不上传照片；模型与算法全部本机运行

## 致谢

- [HivisionIDPhotos](https://github.com/Zeyi-Lin/HivisionIDPhotos)：hivision_modnet 抠图模型与渐变背景、排版照、KB 限制等功能设计参考（Apache-2.0 代码，模型遵循原项目发布条款）
- [MODNet](https://github.com/ZHKKKe/MODNet)：人像抠图模型
- [image-matting](https://github.com/pangxiaobin/image-matting)：批量抠图、结果页换底色与边缘处理思路参考
- ML Kit、CameraX、ONNX Runtime、AndroidX

## 系统要求

- Android 8.0（API 26）及以上
- 推荐 Android 10 及以上
- 抠图和人脸检测模型随应用提供，首次使用无需下载模型

## 本地构建

1. 安装 JDK 17、Android SDK 35 和 Android Studio（或命令行工具）。
2. 在项目根目录执行：

```bash
./gradlew testDebugUnitTest assembleDebug
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

Release 构建：

```bash
./gradlew assembleRelease
```

发布 APK 应使用自己的签名密钥签名，**不要将密钥或密码提交到仓库**。

## 技术结构

- Kotlin + 原生 Android Views + Material 3
- CameraX：预览、拍摄、实时帧分析
- ML Kit Face Detection：人脸定位
- ONNX Runtime + hivision_modnet（MODNet）：发丝级人像蒙版本地推理
- ML Kit Selfie Segmentation：抠图降级方案
- 自研确定性人脸构图算法与 Kotlin 像素级调色/美颜
- MediaStore + ExifInterface：方向修正和合规保存
- JUnit：人脸构图边界、比例和偏移测试

```text
app/src/main/java/cn/quickidphoto/app/
├── MainActivity.kt             # 首页、编辑器、批量流程与导出
├── CameraActivity.kt           # CameraX 拍摄和实时人脸分析
├── IdPhotoProcessor.kt         # MODNet 抠图、换底、美颜、调色、排版
├── ModnetMatting.kt            # ONNX Runtime 发丝级人像分割
├── FaceCropCalculator.kt       # 可独立测试的人脸自动构图算法
├── PhotoRepository.kt          # EXIF 方向修正、KB 限制压缩与 MediaStore 保存
├── EditorPreviewView.kt        # 编辑预览、拖动、缩放和网格
├── VisualColorPickerView.kt    # 可视化 HSV 取色器
└── IdPhotoOverlayView.kt        # 标准比例人形定位轮廓
```

## 使用提示

- 拍摄时使用均匀光线，正对镜头，双肩保持水平，避免复杂背景。
- 换底后请检查发丝和肩部边缘；可调整“边缘柔化”和“轮廓收缩/扩展”。
- 正式证件照应适度美颜，不得改变五官或真实面貌。
- 尺寸、背景色和文件要求可能随机构或考试公告变化，请在提交前核对最新官方要求。
- 六寸相纸打印请选择原始尺寸，关闭“适应页面”或自动缩放。

## 开源许可

基于 [Apache License 2.0](LICENSE) 开源。ML Kit、CameraX、AndroidX 等依赖遵循各自许可。

## 贡献

欢迎提交 Issue 和 Pull Request。涉及新规格时请附官方尺寸来源；涉及图像算法时请补充单元测试、前后对比与性能说明。
