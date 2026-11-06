# ADR-0001：采用独立全局语音 helper

- Status: accepted
- Date: 2026-11-05

## Context

语音能力需要在多个 Android TV App 中使用。把逻辑复制进每个业务 App 会产生重复实现、
重复权限和互相冲突的无障碍服务。修改系统固件或在原厂语音 App 内替换模型则要求系统
签名或 root，无法作为当前设备的可维护方案。

设备必须保留原厂 `com.jmgo.ai.voice`，因为麦克风路由、中文 AIUI 识别和 TTS 都依赖
该厂商组件。用户同时要求输入框场景只做听写，无输入框场景才调用大模型。

## Decision

建立独立 APK `com.dongpiandi.voicehelper`：

- 用 AccessibilityService 全局接收语音键。
- 用 Android 13 Accessibility InputMethod 获取活动编辑连接。
- 有编辑连接时直接调用原厂识别并提交文本。
- 没有编辑连接时显示自有覆盖层，调用自定义 Responses API 和原厂 TTS。
- 业务 App 不嵌入 helper，也不依赖 helper。

`voice-core` 作为独立 Android library 承载厂商识别、PCM 和会话逻辑，应用模块
只负责产品交互与模型请求。

## Consequences

优点：

- 一次安装覆盖所有 App。
- 不修改业务 App，不需要系统签名或 root。
- 输入框听写和语音助手共用一个语音键路由。
- 厂商语音能力与模型 UI 可以独立测试和替换。

成本与风险：

- 用户必须手动启用无障碍服务并授予麦克风权限。
- 固件升级可能改变语音键、广播或 AIUI 二进制接口。
- S621 的 AIUI 依赖 32 位进程和 `armeabi-v7a` native library 标记。
- 闭源原厂组件的内部行为不能由本项目保证。

## Alternatives considered

- 在每个业务 App 内嵌听写：无法在不修改其他 App 的情况下覆盖全局输入框，且重复。
- 直接替换原厂语音 APK：需要系统签名和更新通道，风险高且当前不可行。
- 修改固件或使用 root：设备没有可用 root，维护和回滚成本过高。
- 只用普通输入法 App：遥控器语音键和部分 TV App 的自绘键盘无法统一覆盖。
