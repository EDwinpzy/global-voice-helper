# Global Voice Helper

面向 Android TV 的独立全局语音助手。它不是桌面、影视 App 或系统固件的一部分，
而是通过无障碍服务在任意应用中提供语音听写和语音助手能力。

GitHub 私有仓库：<https://github.com/EDwinpzy/global-voice-helper>

当前状态：内部测试版，主要在坚果 S621（Android 13）上验证。原厂识别依赖
`com.jmgo.ai.voice`，32 位 ABI 要求依赖具体固件。

## 功能

- 输入框有活动编辑连接时，按遥控器语音键，由原厂 AIUI 识别中文并直接写入当前光标；
  不启动大模型、TTS 或原厂助手窗口。
- 没有活动输入框时，显示 helper 自有覆盖层，使用原厂识别、自定义
  OpenAI-compatible 模型和原厂 TTS。
- 助手覆盖层出现时，整屏背景会降清晰度并加暗；语音岛使用灰蓝玻璃胶囊、
  实心麦克风和动态声波，不显示发送按钮。1920 × 1080 下语音岛为 520 × 78。
- 输入框听写复用同一尺寸的语音岛。进入听写时收起系统输入法，识别文本在波形区域
  单行显示；有声音输入时波形持续起伏。
- 动效包括语音岛入场、麦克风呼吸、动态声波、文字淡入、回答展开与退出淡出；
  不显示“正在思考”文案，回答静止或关闭后停止持续重绘，并遵循系统动画开关。
- 模型固定为 `workbuddy/deepseek-v4.1-flash`，单次请求最多等待 30 秒。
- 无输入框助手会带上最近两轮对话；普通问题使用中等搜索上下文，最多搜索两次；
  回答按“结论 + 关键点 + 建议”组织；每个完整句子单独成行，多项内容使用
  阿拉伯数字编号并留出间距，且限制在覆盖层可完整显示的长度内。
- 模型配置在设备上输入并保存到 `EncryptedSharedPreferences`，仓库不保存
  Base URL、API Key、账号或设备数据。

## 快速开始

前置条件：

- JDK 17
- Android SDK Platform 35 与兼容的 build-tools
- Android NDK/CMake（用于生成 32 位 ABI 标记）

在 `local.properties` 中配置本机 SDK，然后运行：

```powershell
.\gradlew.bat :app:assembleDebug
```

APK 输出：

```text
app\build\outputs\apk\debug\app-debug.apk
```

覆盖安装时保留原包名和签名身份：

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

首次使用需要在 App 内配置模型并启用无障碍服务。详细步骤见
[设备配置与验证](docs/how-to/setup-and-verify.md)。

## 工程结构

```text
app/          Android 应用、无障碍服务、配置界面和助手覆盖层
voice-core/   原厂 AIUI、PCM、会话、按键路由和厂商助手抑制
docs/         架构、安装验证和架构决策记录
gradle/       Gradle Wrapper
```

`app/` 专注产品级交互和模型调用，`voice-core/` 只承载可测试的原厂语音能力。

## 开发验证

```powershell
.\gradlew.bat :voice-core:testDebugUnitTest :app:testDebugUnitTest `
  :voice-core:lintDebug :app:lintDebug :app:assembleDebug --rerun-tasks
```

Debug 构建包含仅本地 QA 使用的 `DUMP` 权限广播入口，用于注入文本和 PCM。
这些入口不会进入未来的 release 构建。

## 适用边界

- 需要 Android 13（API 33）及以上，以及支持 Accessibility InputMethod 的固件。
- S621 上必须运行在 32 位进程；`app` 通过 native ABI 桩选择 `armeabi-v7a`。
- 原厂语音包不能被普通第三方应用卸载或签名替换。helper 只调用其识别与 TTS 能力。
- 无障碍服务关闭、App 被卸载或厂商组件缺失时，跨应用语音功能会停止。
- 当前没有 root、系统签名或固件集成能力。

## 文档

- [文档索引](docs/README.md)
- [架构概览](docs/architecture/overview.md)
- [设备配置与验证](docs/how-to/setup-and-verify.md)
- [在现有 App 内测试模型延迟](docs/how-to/benchmark-models.md)
- [2026-11-05 模型延迟结果](docs/reference/model-benchmark-2026-11-05.md)
- [ADR-0001：独立全局 helper](docs/architecture/adr/0001-independent-global-helper.md)

当前仓库未声明开源许可证。对外分发前需由项目所有者补充真实许可证和安全联系方式。
