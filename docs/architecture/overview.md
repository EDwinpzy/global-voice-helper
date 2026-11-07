# 架构概览

## 目标

在不修改系统固件的前提下，为 Android TV 提供跨应用的遥控器语音能力：

- 有输入框时只做原厂语音转文字，并把结果提交到当前编辑连接。
- 没有输入框时提供独立助手界面，由自定义模型生成回答，再由原厂 TTS 播报。
- 不打开原厂语音助手窗口，不依赖 Video Pro、Tigris 或其他业务 App。

## 系统边界

```text
遥控器语音键
    |
    v
VoiceGatewayService (AccessibilityService)
    |-- 有编辑连接 -> VoiceDictationController -> voice-core / 原厂 AIUI
    |-- 无编辑连接 -> VoiceAssistantController -> 原厂 AIUI
                                             -> OpenAI-compatible Responses API
                                             -> Accessibility overlay
                                             -> 原厂 TTS
```

`VoiceGatewayService` 使用 Android 13 的 Accessibility InputMethod 编辑连接能力，
同时通过无障碍按键过滤接收遥控器语音键。它不是系统签名应用，也不是输入法替换。

## 模块

### `app`

- `VoiceGatewayService`：服务生命周期、按键路由、编辑连接和窗口判断。
- `VoiceDictationController`：一次按键对应一次原厂识别和一次文本提交。
- `VoiceAssistantController`：助手会话、最近两轮上下文、模型请求、回答展示和 TTS。
- `VoiceAssistantOverlay`：不抢焦点的无障碍覆盖层。无障碍服务连接后预热隐藏窗口，
  触发时只切换可见状态；玻璃底图按半分辨率缓存，聆听阶段保持紧凑波纹，不自动展开
  空白面板；输入音频时隐藏背景波纹，只让麦克风圆钮内的动态声波持续起伏；整屏背景
  每轮只截取并缩小一次，再以静态硬件 RenderNode 模糊，卡片和局部波形独立重绘；
  静止后立即停止动画。
- `VoiceHelperSetupActivity`：模型配置、权限和无障碍启用入口。
- `assistant/`：端点规范化、加密配置存储、Responses API 客户端。

### `voice-core`

- AIUI SDK 加载与 32 位进程约束。
- PCM 录音、WPGS 中间结果合并和最终结果选择。
- 原厂按键服务路由与助手 UI 抑制。
- 可脱离 Android UI 进行单元测试的会话状态机。

## 外部依赖

- `com.jmgo.ai.voice`：原厂 AIUI 识别和 TTS。
- `com.jmgo.hippo`：原厂语音键无障碍服务；helper 活跃时会临时停用其按键服务，
  防止同一按键再次打开原厂助手。
- Android SDK 33+：Accessibility InputMethod、无障碍覆盖层和前台麦克风服务。

仓库不包含这些厂商 APK、native 库、API Key 或设备凭据。

## 数据流

1. 语音键按下。
2. 服务读取当前编辑连接。
3. 编辑连接和系统输入法窗口同时有效时进入听写路径；否则进入助手路径，避免残留
   编辑连接吞掉助手语音键。
4. 原厂 AIUI 接收麦克风 PCM，并返回中间与最终文本。
5. 听写路径直接 `commitText`；助手路径把最终文本和最近两轮对话发给自定义模型。
6. 助手回答显示在覆盖层，并交给原厂 TTS。

## 失败边界

- 没有麦克风权限、原厂 AIUI 未安装或设备不是 32 位 ABI 时，听写不可用。
- 编辑连接在按键期间失效时，文本不会提交，避免写入错误窗口。
- 默认模型请求超时时自动切换 `group/free` 兜底；认证失败和其他非超时错误不触发兜底。
- 回答超过覆盖层可显示长度时，客户端在句号处截断并加省略号，避免出现半行截断。
- 无障碍服务被关闭或与其他听写服务冲突时，跨应用能力停止。
