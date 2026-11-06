# 文档

本目录记录全局语音助手稳定、可复用的架构和操作知识。临时调试记录、截图和 APK
不放在这里。

## 阅读顺序

1. [架构概览](architecture/overview.md)：系统边界、组件和数据流。
2. [设备配置与验证](how-to/setup-and-verify.md)：构建、安装、权限、真机验收。
3. [模型延迟基准](how-to/benchmark-models.md)：在现有 App 内重复测试模型速度。
4. [2026-11-05 模型延迟结果](reference/model-benchmark-2026-11-05.md)：本轮实机
   测试数据与选型结论。
5. [ADR-0001](architecture/adr/0001-independent-global-helper.md)：为什么采用独立
   helper，而不是绑定单个业务 App 或修改固件。

## 文档职责

- `architecture/` 解释长期结构、外部依赖和关键取舍。
- `how-to/` 面向具体操作结果，提供可复制命令和成功判据。
- 架构决策一旦接受，以 ADR 记录历史；行为变化通过新 ADR 取代，不重写旧决定。
