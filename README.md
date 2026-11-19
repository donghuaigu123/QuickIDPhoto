# 快捷证件照（Quick ID Photo）

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

**[下载最新 APK](https://github.com/donghuaigu123/QuickIDPhoto/releases/latest)** · **[项目仓库](https://github.com/donghuaigu123/QuickIDPhoto)**

一款以**隐私、本地处理和专业构图**为核心的开源 Android 证件照工具。支持相册单张编辑、多照片批量处理，以及带人脸和人形定位轮廓的相机拍摄。

## 功能

- **智能构图**：本地 ML Kit 人脸检测，按目标宽高比自动裁剪；可调脸部占比、头顶留白、水平位置，支持拖动、双指缩放、旋转和镜像。
- **常见规格**：一寸、大一寸、二寸、小二寸、中国护照、港澳通行证、美国/日本/申根签证、社保卡、驾驶证、考试报名等 15 种预设；支持自定义像素与 72–1200 DPI。
- **AI 抠图换底**：本地人像分割，白/蓝/红/灰等背景、自定义十六进制颜色、透明 PNG；可调边缘柔化和轮廓收缩/扩展。
- **美颜与调色**：皮肤区域平滑、肤色提亮、细节锐化；亮度、对比度、饱和度、色温、色调、阴影、高光和自动白平衡。
- **相机辅助**：人脸椭圆与双肩轮廓、实时人脸居中/距离提示、三分线、3 秒定时、闪光灯、前后摄像头切换。
- **批量制作**：统一规格、背景、构图和美颜参数；逐张检测人脸并导出，显示成功与失败数量。
- **输出**：JPG 质量 60–100、PNG、透明通道、系统相册保存与分享、1800×1200px / 300DPI 六寸相纸排版及裁切线。
- **隐私**：不声明 Internet 权限，不上传照片；读取用户主动选择的照片。Android 10 及以上通过 MediaStore 保存，无需存储权限；仅 Android 8/9 在保存时请求受限存储权限。相机功能单独请求相机权限。

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
- ML Kit Selfie Segmentation：人像蒙版
- 自研确定性人脸构图算法与 Kotlin 像素级调色/美颜
- MediaStore + ExifInterface：方向修正和合规保存
- JUnit：人脸构图边界、比例和偏移测试

```text
app/src/main/java/cn/quickidphoto/app/
├── MainActivity.kt             # 首页、编辑器、批量流程与导出
├── CameraActivity.kt           # CameraX 拍摄和实时人脸分析
├── IdPhotoProcessor.kt         # 人像分割、换底、美颜、调色、排版
├── FaceCropCalculator.kt       # 可独立测试的人脸自动构图算法
├── PhotoRepository.kt          # EXIF 方向修正与 MediaStore 保存
├── EditorPreviewView.kt        # 编辑预览、拖动、缩放和网格
└── IdPhotoOverlayView.kt        # 人脸/人形定位轮廓
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
