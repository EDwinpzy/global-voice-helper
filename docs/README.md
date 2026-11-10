# 文档

本目录记录全局语音助手稳定、可复用的架构和操作知识。临时调试记录、截图和 APK
不放在这里。

## 阅读顺序

1. [架构概览](architecture/overview.md)：系统边界、组件和数据流。
2. [设备配置与验证](how-to/setup-and-verify.md)：构建、安装、权限、真机验收。
3. [模型延迟基准](how-to/benchmark-models.md)：在现有 App 内重复测试模型速度。
4. [2026-11-05 模型延迟结果](reference/model-benchmark-2026-11-05.md)：本轮实机
   测试数据与选型结论。
5. [2026-11-10 S621 语音能力巡检](reference/s621-voice-capabilities-2026-11-10.md)：
   各 App 的真实页面入口和可用语音桥。
6. [ADR-0001](architecture/adr/0001-independent-global-helper.md)：为什么采用独立
   helper，而不是绑定单个业务 App 或修改固件。
7. [ADR-0002：模型超时兜底](architecture/adr/0002-timeout-fallback-model.md)：为什么
   只在超时时切换到 `group/free`。
8. [ADR-0003](architecture/adr/0003-scheduled-live-football.md)：为什么用
   `AlarmManager` 和透明启动页实现定时足球直播。
9. [ADR-0004：模型选择定时动作与通用提醒](architecture/adr/0004-model-selected-scheduled-actions.md)：
    为什么改为模型理解意图，以及到点提醒如何询问下一步动作。
10. [ADR-0005：模型判断媒体类型与音乐内置入口](architecture/adr/0005-model-media-intent-routing.md)：
    为什么裸“播放XX”改由模型分类，以及 Folia 如何接收版本化音乐入口。

## 文档职责

- `architecture/` 解释长期结构、外部依赖和关键取舍。
- `how-to/` 面向具体操作结果，提供可复制命令和成功判据。
- 架构决策一旦接受，以 ADR 记录历史；行为变化通过新 ADR 取代，不重写旧决定。
