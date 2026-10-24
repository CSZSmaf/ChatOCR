# 拾语（ChatOCR）

Android 本地聊天与文章整理工具。支持微信、QQ 悬浮窗采集和聊天录屏导入，也可从滚动录屏中提取文章与公式。

## 功能

- 聊天：采集或导入录屏 → 自动去重 → 校对 → 导出纪念册。
- 图片与表情：裁剪聊天画面中的图片，附在对应消息旁；动态表情保存静态截图。
- 文章：导入录屏，框选正文，提取文字或公式，阅读与校对。
- 导出：聊天支持 ZIP、HTML、JSON、TXT；文章支持 ZIP、Markdown、TXT。
- 识别在手机本地完成，应用不申请联网权限。原图、识别原文及人工校订分别保留。

当前版本 **0.5.1**，Android 10 及以上，支持 arm64-v8a 与 x86_64。安装包见本仓库的 [Releases](https://github.com/CSZSmaf/ChatOCR/releases)。

## 使用

**悬浮采集**：聊天 → 新建纪念册 → 悬浮窗采集。允许悬浮窗及屏幕共享，进入聊天后点“开始识别”，框住消息列表。每次滚动约半屏并稍作停留，结束后校对与导出。

**聊天录屏**：聊天 → 新建纪念册 → 导入聊天录屏。选择微信或 QQ、单聊或群聊，框选消息后开始。可暂停和继续；续识别需要保留原录屏。

**文章**：切换“文章”，导入滚动录屏并框选正文。旧卷读文章可从“设置 → 导入卷读阅读包”迁入。

外观和阅读字号位于设置；完整操作说明也在设置中。卸载前请导出需要保留的内容。

## 构建

需要 JDK 21、Android SDK Platform 35 和 Build Tools 35.0.0。Gradle Wrapper 随项目提供。

```powershell
# 首次构建：下载离线公式模型与排版资源
python scripts/fetch_video_assets.py
.\build.ps1 -JdkPath 'JDK 21 目录' -SdkPath 'Android SDK 目录'

# 手机安装包（仅 arm64）
.\gradlew.bat :app:assembleDebug -PtargetAbi=arm64-v8a
```

模型和 APK 不存入 Git。模型下载脚本固定版本并检查完整性；应用运行不需要下载模型。输出目录为 `app/build/outputs/apk/debug/`，`build.ps1` 另将通用包复制到 `dist/`。

测试命令及目录说明见 [开发说明](docs/DEVELOPMENT.md)。

## 已知限制

当前采用截图识别，无法恢复原始附件、动态表情或语音内容。复杂背景、特殊字体、群昵称、引用及选区边缘内容可能需要人工校对。时间仅使用聊天画面显示的内容。

已进行单元测试与 Android 17 模拟器受控录屏验证；vivo X300 Pro 真机、真实微信和 QQ 的多主题及多小时采集仍需实际验证。

## 版权

© 2026 Yijin Wang · CHN_wangyijin@proton.me

源代码沿用卷读项目的 AGPL-3.0-only，见 [LICENSE](LICENSE)。第三方代码、模型及排版资源保留各自许可，见 [第三方说明](THIRD_PARTY_NOTICES.md)。
