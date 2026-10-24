# 第三方依赖与模型

本项目实现自己的 Android 视频取帧、滚动配准、空间去重、证据校对和导出流程；文字及公式识别使用现有预训练模型，没有宣称训练了新的 OCR 模型。

| 组件 | 使用位置 | 来源与许可 |
|---|---|---|
| ML Kit Text Recognition Chinese 16.0.1 | 本机中文字识别，打包模型 | [Google 官方接入文档](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)，遵循 Google SDK 适用条款 |
| ONNX Runtime Android 1.23.2 | 手机 CPU 公式模型推理；JNI 的 16 KB 页兼容 | [Microsoft ONNX Runtime](https://github.com/microsoft/onnxruntime)，MIT；全文在 `licenses/` |
| RapidLaTeXOCR v0.0.0 模型 | 公式编码器、解码器、尺寸调整器、词表 | [RapidAI/RapidLaTeXOCR](https://github.com/RapidAI/RapidLaTeXOCR)，MIT；Kotlin `FormulaEngine.kt` 的预处理和解码流程参照该项目移植，保留原作者 SWHL 的 MIT 声明 |
| LaTeX-OCR | RapidLaTeXOCR 的上游模型与算法 | [lukas-blecher/LaTeX-OCR](https://github.com/lukas-blecher/LaTeX-OCR)，遵循上游模型与代码的对应许可 |
| Pix2Text MFD 1.5 | 行内和独立公式检测 | [模型卡](https://huggingface.co/breezedeus/pix2text-mfd-1.5)，固定 revision `f470a885e0fca1d3d2bfa2a54991db7ae01f1861`；模型卡标记 MIT，但 ONNX 元数据标记 Ultralytics AGPL-3.0，见下文 |
| KaTeX 0.16.22 | 离线公式渲染与字体 | [KaTeX](https://github.com/KaTeX/KaTeX)，MIT；完整许可随安装资源和导出包包含 |
| Kotlin / Jetpack Compose / AndroidX | 原生界面、协程与 Android 支持库 | 对应上游的 Apache-2.0 等许可 |

## 公式检测模型的许可信息差异

本次下载的 MFD 文件模型卡写明 `license: mit`，而 ONNX 模型的 `custom_metadata_map` 为：

```
description: Ultralytics YOLO11m model trained on .../mfd.yaml
author: Ultralytics
version: 8.3.123
license: AGPL-3.0 License (https://ultralytics.com/license)
names: {0: 'embedding', 1: 'isolated'}
```

因此，不能仅依据模型卡宣称整个安装包适合闭源商业分发。当前交付完整应用源代码、构建脚本、模型来源和许可声明；在正式商业分发之前，应解决上游模型许可的一致性或改用许可明确兼容的模型。项目自己的原创代码采用 AGPL-3.0-only（见 `LICENSE`），第三方组件保留各自许可。

## 完整性与来源

模型文件的大小、来源 URL、SHA-256 记录在 `app/src/main/assets/models/manifest.json`。`scripts/fetch_assets.py` 通过硬编码 SHA-256 验证已知模型字节，不接受未知内容自动替换。KaTeX 从 npm 的固定版本包获取，模型和排版资源全部随安装包提供。

受控测试的文章、录屏和公式图由本项目的 `verification/generate_fixtures.py` 生成；这些是测试输入，不是识别模型的准确率基准数据集。
