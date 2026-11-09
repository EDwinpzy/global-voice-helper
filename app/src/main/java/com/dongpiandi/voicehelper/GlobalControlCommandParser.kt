package com.dongpiandi.voicehelper

internal enum class GlobalControlAction {
    UP,
    DOWN,
    LEFT,
    RIGHT,
    CONFIRM,
    BACK,
    HOME,
    RECENTS,
    NOTIFICATIONS,
    VOLUME_UP,
    VOLUME_DOWN,
    MUTE,
    PLAY_PAUSE,
    PREVIOUS,
    NEXT,
    FAST_FORWARD,
    REWIND,
}

/** Maps short, unambiguous remote-control phrases to system-wide TV actions. */
internal object GlobalControlCommandParser {
    private val trailingDecoration =
        Regex("""(?:一下|一点|吧|啊|呀|呗|呢|啦|嘛|谢谢|拜托|[\s，,。.!！?？:：]+)+$""")

    private val phrases: Map<String, GlobalControlAction> =
        listOf(
            listOf("向上", "往上", "上移", "上一项", "上翻", "上") to GlobalControlAction.UP,
            listOf("向下", "往下", "下移", "下一项", "下翻", "下") to GlobalControlAction.DOWN,
            listOf("向左", "往左", "左移", "左") to GlobalControlAction.LEFT,
            listOf("向右", "往右", "右移", "右") to GlobalControlAction.RIGHT,
            listOf("确定", "确认", "选中", "选择它", "点击", "按确定", "按确认") to
                GlobalControlAction.CONFIRM,
            listOf("返回", "后退", "退回去", "退一下", "按返回") to GlobalControlAction.BACK,
            listOf("主页", "回主页", "桌面", "回桌面", "回首页", "回到首页") to
                GlobalControlAction.HOME,
            listOf("最近任务", "多任务", "后台任务") to GlobalControlAction.RECENTS,
            listOf("打开通知", "通知栏", "通知") to GlobalControlAction.NOTIFICATIONS,
            listOf("音量加", "调高音量", "音量大点", "大声点", "增加音量") to
                GlobalControlAction.VOLUME_UP,
            listOf("音量减", "调低音量", "音量小点", "小声点", "减小音量") to
                GlobalControlAction.VOLUME_DOWN,
            listOf("静音", "取消静音", "切换静音") to GlobalControlAction.MUTE,
            listOf("播放", "暂停", "暂停播放", "继续播放", "恢复播放", "播放暂停") to
                GlobalControlAction.PLAY_PAUSE,
            listOf(
                "上一个",
                "上一个视频",
                "上一集",
                "上一首",
                "前一个",
                "前一个视频",
                "上一台",
                "上一个频道",
            ) to GlobalControlAction.PREVIOUS,
            listOf(
                "下一个",
                "下一个视频",
                "下一集",
                "下一首",
                "后一个",
                "后一个视频",
                "下一台",
                "下一个频道",
            ) to GlobalControlAction.NEXT,
            listOf("快进", "向前快进") to GlobalControlAction.FAST_FORWARD,
            listOf("快退", "向后快退") to GlobalControlAction.REWIND,
        ).flatMap { (values, action) -> values.map { it to action } }.toMap()

    fun parse(transcript: String): GlobalControlAction? {
        val input = transcript.trim().replace(Regex("\\s+"), " ")
        if (input.isEmpty()) return null
        val normalized = trailingDecoration.replace(input, "").trim()
        return phrases[normalized]
    }
}
