# 在 Android TV 上配置并验证

本指南适用于 Android 13+、已安装原厂 `com.jmgo.ai.voice` 的测试设备。

## 1. 构建

确认 JDK 17、Android SDK 和 `local.properties` 可用：

```powershell
.\gradlew.bat :voice-core:testDebugUnitTest :app:testDebugUnitTest `
  :voice-core:lintDebug :app:lintDebug :app:assembleDebug --rerun-tasks
```

成功时 APK 位于 `app\build\outputs\apk\debug\app-debug.apk`。

## 2. 覆盖安装

不要先卸载。覆盖安装可以保留配置和无障碍启用状态：

```powershell
adb -s <设备地址> install -r .\app\build\outputs\apk\debug\app-debug.apk
```

如果签名不匹配，应停止并确认 APK 来源；不要用卸载来绕过签名错误。

## 3. 首次配置

1. 打开“全局语音助手”。
2. 允许麦克风权限。
3. 输入 OpenAI-compatible Base URL 和 API Key。
4. 保存配置。
5. 启用“全局语音助手”无障碍服务。
6. 如果存在其他跨应用听写无障碍服务，只保留一个。

关闭再重新打开设备设置前，先在 helper 内确认服务状态，避免只修改系统字符串但
服务没有真正绑定。

## 4. 验证输入框听写

1. 在支持系统输入法的 App 中选中一个编辑框。
2. 确认系统输入法窗口可见，且没有其他键盘覆盖编辑连接。
3. 按住遥控器语音键，说一句短中文。
4. 保持按住到说完，再松开。

成功判据：

- 原厂助手窗口没有出现。
- 文本出现在当前光标，而不是另一个输入框。
- 助手覆盖层和大模型都没有启动。

日志中应出现 `startInput`、`key=609`、`route=DICTATION`，并在原厂 AIUI
返回最终文本后有提交动作。

注意：部分 App 使用自绘字母键盘，编辑框本身没有系统输入连接。这种页面不能作为
Accessibility InputMethod 听写的有效测试对象。

## 5. 验证无输入框助手

1. 返回桌面或进入没有编辑框的页面。
2. 按住语音键说一个需要联网的问题。
3. 松开并等待识别和模型回答。

成功判据：

- helper 自有覆盖层显示识别文本。
- 按键后出现深灰语音胶囊和动态声波；识别到文字后展开对话卡片。
- 覆盖层出现时整屏背景模糊。
- 默认模型为 `workbuddy/deepseek-v4.1-flash`，单次请求最多等待 30 秒；仅超时时
  自动用 `group/free` 兜底一次。
- 助手会带上最近两轮对话；普通问题使用中等搜索上下文，最多搜索两次。
- 回答以结论开头，每个完整句子单独成行；多项内容用“1. 2. 3.”编号并在项间留白，
  覆盖层应完整显示，不应出现半行截断。
- 回答出现后由原厂 TTS 播报。
- 原厂语音助手窗口不出现。

自动验收不能只依赖注入 PCM。注入 PCM 可以验证识别、路由和提交链路，但实体遥控器
麦克风、声学输入和厂商音频路由必须在目标设备上单独验证。

日志中应出现 `visible frame drawn revealMs=...` 和 `screen backdrop ready captureMs=...`。
覆盖层由无障碍服务预热，正常情况下应在按键后很快出现；若仍等待数百毫秒，检查服务
是否刚被系统重启。

## 6. 常见故障

### 按键完全没有日志

确认 helper 的无障碍服务已绑定，并且没有和第二个听写服务同时消费语音键。

### 收到 `key=609` 但进入助手路径

检查日志中的 `editorAttached` 和 `ime`。编辑连接已经结束、输入法窗口消失或 App
使用自绘键盘时，服务会按无输入框处理。

### 有识别结果但没有写入

检查按键期间编辑连接是否发生变化。听写不会写入已经失效的编辑框。

### 原厂识别不可用

确认设备进程是 `armeabi-v7a`、原厂语音包存在，并且麦克风权限有效。

### 模型回答慢

日志中的 `elapsedMs` 会显示模型请求总耗时。当前默认使用
`workbuddy/deepseek-v4.1-flash`，普通问题使用中等搜索上下文并最多搜索两次，
联网问题会比纯模型问题慢。单次请求超过 30 秒后会改用 `group/free` 兜底一次；
若兜底仍超时或持续出现其他错误，检查 Base URL、网络和密钥，或确认网关是否支持
`medium` 搜索上下文。
