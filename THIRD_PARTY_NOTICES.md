# 第三方说明

拾语包含 ChatOCR 聊天功能与卷读 VideoOCR 的文章、视频和公式功能。卷读的原始命名空间与许可文件保留在 `integration/video-origin/`，源代码沿用 AGPL-3.0-only。

- Kotlin、AndroidX / Compose、协程和 Gson：遵循各上游许可。
- ML Kit 中文 OCR：打包式本地识别，遵循 Google SDK 适用条款。
- ONNX Runtime：MIT，许可见 `licenses/ONNXRuntime-MIT.txt`。
- RapidLaTeXOCR：MIT；公式模型、预处理和解码实现的上游声明见 `licenses/RapidLaTeXOCR-MIT.txt`。
- Pix2Text MFD：原项目记录的模型卡 MIT 与模型元数据 AGPL-3.0 差异保留在下方原始说明中。
- KaTeX：MIT；许可随应用和文章 ZIP 阅读包保留。

[卷读原始第三方说明](integration/video-origin/THIRD_PARTY_NOTICES.md)保留详细模型来源与许可信息。模型恢复脚本为 `scripts/fetch_video_assets.py`；许可证全文见 `licenses/`。

项目版权见 [COPYRIGHT](COPYRIGHT)，不替代第三方版权声明。
