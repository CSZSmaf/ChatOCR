<img src="docs/assets/chatocr-icon.svg" width="80" height="80" alt="拾语 ChatOCR 图标">

# 拾语（ChatOCR）

Android 本地聊天与文章整理工具，将微信、QQ 聊天记录和滚动录屏整理成可阅读、校对、保存的内容。

## 功能

- **聊天纪念册**：悬浮窗采集或导入聊天录屏，自动识别、去重，按顺序整理消息。
- **图片与表情**：自动裁剪为静态截图，随聊天内容保存。
- **文章提取**：从滚动录屏中提取文字与公式。
- **阅读与校对**：对照原图修改文字，支持搜索、浅深色主题和字号调整。
- **导出保存**：聊天支持 ZIP、HTML、JSON、TXT；文章支持 ZIP、Markdown、TXT。
- **离线处理**：在手机本地完成识别，支持暂停和继续。

## 安装

Android 10 及以上。前往 [下载页面](https://github.com/CSZSmaf/ChatOCR/releases) 获取手机安装包。

## 使用

**悬浮窗采集**

新建纪念册 → 悬浮窗采集 → 授予悬浮窗和屏幕共享权限 → 进入聊天 → 开始识别。框选消息区域，每次滚动约半屏并稍作停留，结束后即可校对、阅读和导出。

**导入聊天录屏**

新建纪念册 → 导入聊天录屏 → 选择微信或 QQ、单聊或群聊 → 框选消息区域 → 开始整理。

**提取文章**

切换“文章” → 导入录屏 → 框选正文 → 选择识别方式 → 阅读与校对。

## 构建

准备 JDK 21、Android SDK Platform 35 和 Build Tools 35.0.0，然后运行：

```powershell
python scripts/fetch_video_assets.py
.\build.ps1 -JdkPath 'JDK 21 目录' -SdkPath 'Android SDK 目录'
```

安装包生成于 `dist/`。

## 版权

© 2026 Yijin Wang · CHN_wangyijin@proton.me

[许可证](LICENSE) · [依赖许可](THIRD_PARTY_NOTICES.md)
