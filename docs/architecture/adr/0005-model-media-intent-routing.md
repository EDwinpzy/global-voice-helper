# ADR-0005：模型判断媒体类型与音乐内置入口

- Status: accepted
- Date: 2026-11-10
- Supersedes in part: ADR-0004 的第 8 条

## Context

ADR-0004 保留了“明确播放/打开命令先走本地快速通道”。实际在 S621 上验收时，这条边界
把大量裸“播放XX”先交给了视频解析器：说“播放每日推荐”会进入 Video Pro，说“打开私人
FM”会被当成不存在的外部 App，说“播放我喜欢”会把入口名当成歌曲名搜索。用户需要的
不是更多关键词，而是由模型先判断内容是歌曲、影视、足球直播还是短视频，再选择目标
App。

同时，Folia TV 首页的真实入口不止“我喜欢 / 私人FM / 每日推荐”三项。实机巡检确认首
页还会显示音乐云盘、歌单、收藏专辑以及推荐电台卡片；这些入口需要由音乐 App 自己
解释，不能让 helper 截图识别或模拟方向键点击。

## Decision

1. “播放 / 观看 / 听 + 内容名”默认交给模型判断媒体类型。模型只能从封闭白名单选择
   动作，不能输出包名、Component、URL 或任意 Intent。
2. 本地快速通道只保留无语义歧义的命令：输入框动作、全局控制、明确的“看/搜索”
   表达、带媒体名词的“播放XX电影/动漫”、明确 App 名和明确足球赛事。裸“播放标题”
   不再被视频解析器抢占；即使句子里带“歌”，歌单、专辑、电台、音乐云盘、我喜欢、
   私人 FM、每日推荐等内置入口也留给模型路由，不能被本地解析成歌曲名。
3. 新增 `OPEN_MUSIC_ENTRY`，用于 Folia 的内置入口：
   `music.resume`、`music.liked`、`music.personal_fm`、
   `music.daily_recommendations`、`music.local`、`music.recent`、
   `music.cloud`、`music.playlist`、`music.album`、`music.radio`。
   歌单、专辑和电台通过 `query` 传入用户说出的名称，由 Folia 在当前首页卡片中匹配。
4. 新增 `OPEN_DOUYIN_SEARCH`，只在用户明确说“抖音 / 抖音TV / 短视频”时选择；普通
   视频仍默认进入 Video Pro。
5. Folia 增加版本化动作
   `top.izuna.foliamajor.action.VOICE_COMMAND_V1`，操作固定为 `PLAY_ENTRY`：

   ```text
   operation=PLAY_ENTRY
   entry=music.liked
   query=（仅歌单/专辑/电台需要）
   ```

   旧的 `VOICE_PLAY` 和标准媒体搜索 Intent 保持不变。helper 先查询目标 App 是否声明
   `VOICE_COMMAND_V1`；旧版本 Folia 不支持时明确提示更新，不把入口名退化成歌曲搜索。
   启动入口带上 `NEW_TASK | CLEAR_TOP | SINGLE_TOP`，确保 Folia 已有 TV/车机任务时仍执行
   新请求，而不是只把旧任务带到前台。
6. S621 实机巡检确认：Video Pro 有 signature 保护的搜索 Activity/前台 Receiver；
   球迹直播有 `VOICE_OPEN_MATCH`；抖音 TV 有 UID 白名单 ContentProvider；影视仓
   `com.xiaodianshi.tv.yst` 只有通用 URI/媒体广播，没有供 helper 使用的可信语音命令
   契约，因此本轮不接入，不能用通用广播假装支持。
7. 标题同时可能是歌曲和影视、或模型无法判断媒体类型时，只返回普通文本追问，不执行
   动作。启动 Activity 成功不代表播放成功；未登录、无搜索结果、平台限制由目标 App
   自己显示状态。
8. Folia 的 WebView 冷启动可能早于账户、曲库或电台数据就绪。语义入口在最多 20 秒内
   等待并重试；永久失败仍只记录日志，不伪报播放成功。
9. 歌单、专辑和电台名称先精确匹配，再接受唯一前缀或唯一片段；同时命中多张卡片时
   不擅自选择。带 `personalFm.station` 的推荐电台直接按该 station 开播，普通推荐歌单
   仍按集合读取歌曲。

## Consequences

优点：

- “播放晴天”“播放流浪地球”“播放每日推荐”不会再被同一个正则强行送到 Video Pro。
- 音乐入口由 Folia 自己解析，复用现有播放队列、账户和 provider 逻辑，不依赖截图或
  D-pad 模拟。
- 新增 App 时只需要增加一个目标适配器或声明新的版本化动作；模型仍然不能构造任意
  Intent。
- 旧 Folia 版本保持兼容，不会因为 helper 先升级而把入口名当歌名播放。

成本与风险：

- 裸“播放标题”多了一次模型请求，网络不佳时比关键词路径慢。
- 模型仍可能把冷门标题分错类别；本轮通过“无法判断就追问”和封闭动作限制影响。
- 歌单/专辑/电台按当前首页卡片名称匹配；名称被用户改名、未登录或 provider 未返回
  该卡片时无法播放，需要用户先打开首页确认或换一个说法。
- 影视仓等没有可信语音契约的第三方 App 暂不支持内容级语音控制，只能按应用名打开。
