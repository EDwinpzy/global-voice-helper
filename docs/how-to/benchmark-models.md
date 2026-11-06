# 在现有 App 内测试模型延迟

本流程只使用 Debug APK，复用设备上已保存的加密 Base URL、API Key 和 system prompt。
基准请求复用生产路径的 Responses API、`web_search` 工具和输出上限，只替换模型 ID，
并禁止自动降级。为保持历史数据可比，基准仍使用 `search_context_size=low` 和
`max_tool_calls=1`；生产路径已经改为 `medium` 和最多两次搜索，并加入最近两轮对话。

## 构建与安装

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

不要卸载应用。覆盖安装会保留模型配置、麦克风权限和无障碍服务。

## 运行

纯模型场景使用不需要联网的固定短提示词：

```powershell
adb shell am broadcast `
  -a com.dongpiandi.voicehelper.DEBUG_BENCHMARK_MODELS `
  -n com.dongpiandi.voicehelper/.VoiceDebugReceiver `
  --ei rounds 5 `
  --es prompt "请只回复“收到”，不要解释。"
```

实际联网场景使用需要当前信息的问题：

```powershell
adb shell am broadcast `
  -a com.dongpiandi.voicehelper.DEBUG_BENCHMARK_MODELS `
  -n com.dongpiandi.voicehelper/.VoiceDebugReceiver `
  --ei rounds 5 `
  --es prompt "请联网查询并回答：今天国际米兰有什么最新比赛消息？最多30字。"
```

广播会立即返回，测试在现有无障碍服务的进程内继续运行。不要因为
`am broadcast` 已结束就认为测试已经完成。

## 读取结果

```powershell
adb logcat -d -v time -s VoiceModelBenchmark
```

每个成功请求记录 `headersMs`、`totalMs`、字符数和 `webSearch`。最终每个模型记录：

- 成功数和失败数
- 响应头耗时中位数
- 总耗时中位数、最小值和最大值

单请求超时为 30 秒。超时只表示没有在该窗口内完成，不表示永久不可用。

## 测试约束

- 全部请求串行执行，不能并发，否则模型会互相争用网络和服务端容量。
- 每轮轮换模型顺序，降低固定顺序偏差。
- 每轮提示词带轮次标记，降低缓存影响。
- 至少运行 5 轮；不要只凭单轮结果排序。
- 日志不得包含 Base URL、API Key、请求头或回答正文。
- 两个模型中位耗时差小于 500 ms 或 10% 时应视为速度接近。
