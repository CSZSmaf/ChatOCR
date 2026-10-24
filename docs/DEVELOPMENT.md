# 开发说明

## 模块

- `app`：Android 界面、悬浮采集、视频处理、存储与导出。
- `core`：聊天解析、空间去重、图像区域检测与导出。
- `video-core`：文章拼接、去重与排版。
- `fixture`：独立的合成聊天测试应用，不包含在主应用中。
- `app/src/androidTest/assets`：合成录屏和测试素材，不含个人聊天数据。
- `scripts`：离线资源下载、合成录屏生成及构建辅助。

## 验证

```powershell
.\gradlew.bat :core:test :video-core:test :app:lintDebug
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
# 使用指定测试设备，先安装应用与测试 APK
adb -s <测试设备序列号> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <测试设备序列号> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <测试设备序列号> shell am instrument -w dev.chatocr.app.test/androidx.test.runner.AndroidJUnitRunner
```

历史画面回放测试需要额外的来源参数，未提供时会跳过。受控录屏验证不等于真实聊天逐字准确率或手机续航测试。不要对个人数据执行清空或卸载操作。

`python scripts/generate_chat_video.py` 可生成微信/QQ 风格的合成录屏，需要 Pillow、FFmpeg 和 Windows 微软雅黑字体。

## 数据与兼容

聊天 JSON 的 `schemaVersion` 为 1。识别原文和人工校订分开存储；来源编号用于关联截图，不在阅读页展示。`capturedAt` 为采集时间，`videoTimeMs` 为录屏位置，均不等同于消息发送时间。

每帧处理完成后保存检查点，暂停与恢复共用相同的去重状态。聊天采集、聊天录屏、文章及公式识别通过同一个任务锁协调。新的屏幕共享必须重新获得系统授权。

后台异常的详细信息仅用于设备日志；界面显示可操作的简短提示。界面必要文字不小于 14sp，浅深主题均使用高对比度前景。

## 发布

私有仓库保存源代码、许可证与合成测试素材。真实采集内容、设备私有文件、签名密钥、构建缓存和本机验证输出不提交。调试 APK 只供开发测试；覆盖升级必须使用同一签名。模型恢复方法见 README。
