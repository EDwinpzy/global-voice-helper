# 2026-11-05 模型延迟基准

> 注意：2026-11-06 起生产请求已改为 `search_context_size=medium`、
> `max_tool_calls=2`，并加入最近两轮对话。本页数据采集于旧策略（`low` / 1 次搜索），
> 现在只用于历史对比，不能直接代表当前生产延迟。

## 环境与方法

- 设备：坚果 S621，Android 13，32 位 `armeabi-v7a`
- 请求路径：现有 `com.dongpiandi.voicehelper` Debug App
- 配置：设备内 `EncryptedSharedPreferences` 保存的同一端点、密钥和 system prompt
- API：`POST /v1/responses`，非流式，`max_output_tokens=4000`
- 工具：生产同款单次 `web_search`
- 轮数：每个模型 5 轮
- 顺序：每轮轮换模型顺序
- 超时：单个请求 30 秒
- 指标：完整响应总耗时，单位为毫秒

本轮没有保存或输出 Base URL、API Key、请求头和回答正文。

## 纯模型速度

提示词要求只回复“收到”，模型没有触发 `web_search`。6 个模型均完成 5/5 次请求。

| 模型 | 中位耗时 | 最小 | 最大 | 成功率 |
|---|---:|---:|---:|---:|
| `commandcode-plan/deepseek/deepseek-v4.1-flash` | 1786 ms | 1716 ms | 1986 ms | 5/5 |
| `workbuddy/deepseek-v4.1-flash` | 2105 ms | 1753 ms | 2292 ms | 5/5 |
| `commandcode-plan/reflection/beam:free` | 2112 ms | 1826 ms | 2361 ms | 5/5 |
| `workbuddy/hy3` | 2213 ms | 2190 ms | 2325 ms | 5/5 |
| `commandcode-plan/stealth/crouton-alpha` | 2848 ms | 1964 ms | 3644 ms | 5/5 |
| `workbuddy/hy4free` | 3248 ms | 2110 ms | 4587 ms | 5/5 |

`commandcode-plan/deepseek/deepseek-v4.1-flash` 的纯模型中位数最快，但只比
`workbuddy/deepseek-v4.1-flash` 快 319 ms，属于接近而非压倒性领先。

## 实际联网速度

提示词要求联网查询当天国际米兰最新比赛消息。所有成功请求都触发了
`web_search`。

| 模型 | 中位耗时 | 最小 | 最大 | 成功/轮数 |
|---|---:|---:|---:|---:|
| `workbuddy/deepseek-v4.1-flash` | 6912 ms | 4818 ms | 8382 ms | 5/5 |
| `commandcode-plan/deepseek/deepseek-v4.1-flash` | 9113 ms | 8122 ms | 10819 ms | 4/5 |
| `commandcode-plan/reflection/beam:free` | 13274 ms | 12958 ms | 14649 ms | 3/5 |
| `workbuddy/hy3` | 16068 ms | 12069 ms | 21308 ms | 5/5 |
| `commandcode-plan/stealth/crouton-alpha` | 18408 ms | 14388 ms | 26538 ms | 3/5 |
| `workbuddy/hy4free` | 25915 ms | 24714 ms | 27117 ms | 2/5 |

超时均发生在 30 秒上限内，没有记录为永久失败。多个模型在不同轮次出现过长等待，
说明服务端排队或联网搜索耗时存在明显波动。

## 结论

- 纯模型速度冠军：`commandcode-plan/deepseek/deepseek-v4.1-flash`。
- 实际联网场景冠军且完整完成 5/5：`workbuddy/deepseek-v4.1-flash`。
- 面向语音助手体验，优先选择 `workbuddy/deepseek-v4.1-flash`，因为正常联网回答
  中位约 6.9 秒，且本轮没有超时。
- 如需第二候选，`workbuddy/hy3` 虽然联网中位约 16.1 秒，但本轮 5/5 完成；
  `commandcode-plan/deepseek/deepseek-v4.1-flash` 更快，但本轮只有 4/5 完成。

本结果只代表 2026-11-05 这一网络和服务端状态，不应视为所有时段的固定性能承诺。

## 最终构建复测

并行 UI 修改完成并重新构建后，在 S621 上使用最终 Debug APK 再次执行相同矩阵。
安装包为版本 `1.4`（`versionCode=5`），设备进程保持 `armeabi-v7a`，测试期间只启用
全局语音助手的无障碍服务。

- APK SHA-256：`DCCA0BCCBD9C0C3AD563A7EB57BD646B3047D648F7F7A6AE54B58027842A5814`
- 纯模型日志：`build/benchmark/final-simple-5-rounds-2026-11-05.log`
- 联网日志：`build/benchmark/final-websearch-5-rounds-2026-11-05.log`

### 纯模型

| 模型 | 中位耗时 | 最小 | 最大 | 成功率 |
|---|---:|---:|---:|---:|
| `commandcode-plan/deepseek/deepseek-v4.1-flash` | 2260 ms | 2039 ms | 2474 ms | 5/5 |
| `workbuddy/deepseek-v4.1-flash` | 2307 ms | 1970 ms | 2550 ms | 5/5 |
| `commandcode-plan/reflection/beam:free` | 2396 ms | 2267 ms | 3738 ms | 5/5 |
| `workbuddy/hy3` | 2419 ms | 2221 ms | 2885 ms | 5/5 |
| `workbuddy/hy4free` | 2765 ms | 1902 ms | 3389 ms | 5/5 |
| `commandcode-plan/stealth/crouton-alpha` | 4254 ms | 3617 ms | 4464 ms | 5/5 |

纯模型场景中，`commandcode-plan/deepseek/deepseek-v4.1-flash` 与
`workbuddy/deepseek-v4.1-flash` 仅相差 47 ms，应视为速度接近。

### 实际联网

| 模型 | 中位耗时 | 最小 | 最大 | 成功率 |
|---|---:|---:|---:|---:|
| `workbuddy/deepseek-v4.1-flash` | 6803 ms | 6564 ms | 7451 ms | 5/5 |
| `commandcode-plan/reflection/beam:free` | 11269 ms | 10627 ms | 17277 ms | 5/5 |
| `commandcode-plan/deepseek/deepseek-v4.1-flash` | 11370 ms | 10336 ms | 15164 ms | 4/5 |
| `commandcode-plan/stealth/crouton-alpha` | 16305 ms | 14767 ms | 16548 ms | 4/5 |
| `workbuddy/hy3` | 16493 ms | 12222 ms | 29167 ms | 4/5 |
| `workbuddy/hy4free` | 未完成 | - | - | 0/5 |

超时按 30 秒失败记录。所有成功回答都触发了 `web_search=true`。

### 最终结论

- 面向实际语音助手联网回答，首选 `workbuddy/deepseek-v4.1-flash`：5/5 完成，
  中位约 6.8 秒，明显领先其他模型。
- 如果只比较短回答的首字/完整响应延迟，两个 deepseek flash 型号速度接近；
  `commandcode-plan/deepseek/deepseek-v4.1-flash` 在本次纯模型测试中快 47 ms，
  但联网测试只有 4/5。
- 第二候选可考虑 `commandcode-plan/reflection/beam:free`：本次联网 5/5，
  中位约 11.3 秒；但首轮基线曾只有 3/5，稳定性仍需更长周期观察。
- `workbuddy/hy4free` 在本次联网测试中 5 次全部超时，不适合当前语音助手默认路径。
