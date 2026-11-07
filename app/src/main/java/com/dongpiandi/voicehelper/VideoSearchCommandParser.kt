package com.dongpiandi.voicehelper

import java.util.Locale

/**
 * Extracts an explicit video search request from an assistant transcript.
 *
 * This intentionally handles only a small, deterministic command set. Anything ambiguous falls
 * through to the normal assistant instead of opening Video Pro.
 */
internal object VideoSearchCommandParser {
    private const val MIN_TITLE_LENGTH = 2
    private const val MAX_TITLE_LENGTH = 60

    private data class CommandPrefix(
        val text: String,
        val requiresMediaNoun: Boolean,
    )

    private val commandPrefixes =
        listOf(
            CommandPrefix("我想看一下", false),
            CommandPrefix("我要看一下", false),
            CommandPrefix("帮我看一下", false),
            CommandPrefix("给我看一下", false),
            CommandPrefix("帮我搜索", true),
            CommandPrefix("帮我搜", true),
            CommandPrefix("帮我找", true),
            CommandPrefix("搜索一下", true),
            CommandPrefix("搜一下", true),
            CommandPrefix("打开", true),
            CommandPrefix("我想看", false),
            CommandPrefix("我要看", false),
            CommandPrefix("帮我看", false),
            CommandPrefix("给我看", false),
            CommandPrefix("播放", false),
            CommandPrefix("搜索", true),
            CommandPrefix("找一下", true),
        ).sortedByDescending { it.text.length }

    private val mediaNouns = listOf("电影", "影片", "视频", "电视剧", "动漫", "综艺", "短剧", "片子")

    private val blockedTitles =
        setOf(
            "天气",
            "新闻",
            "设置",
            "时间",
            "日历",
            "闹钟",
            "音乐",
            "歌曲",
            "一首歌",
            "这首歌",
            "那首歌",
            "听歌",
            "电台",
            "直播",
            "股票",
            "比分",
            "菜单",
            "首页",
            "桌面",
            "遥控器",
        )

    private val leadingDecoration =
        Regex(
            """^(?:(?:一下|一部|一个|个|部|这部电影|那部电影|电影|影片|视频|电视剧|动漫|综艺|短剧|片子)(?:叫|名为|名叫)?[，,：:\s]*|(?:叫|名为|名叫)[，,：:\s]*)+""",
        )

    private val trailingDecoration =
        Regex(
            """(?:[。！？!?，,、；;：:…~～\s]+|(?:这部|那部|这个|那个)?(?:电影|影片|视频|电视剧|动漫|综艺|短剧|片子)|吧|啊|呀|呗|呢|啦|嘛|可以吗|好吗|行吗|好不好|谢谢|拜托)+$""",
        )

    fun parse(transcript: String): String? {
        val input = transcript.trim().replace(Regex("\\s+"), " ")
        if (input.length < 3) return null

        val command = commandPrefixes.firstOrNull { input.startsWith(it.text) } ?: return null
        if (command.requiresMediaNoun && mediaNouns.none(input::contains)) return null

        var title = input.removePrefix(command.text).trim()
        title = leadingDecoration.replace(title, "").trim()
        title = stripTitleEdges(title)

        while (true) {
            val stripped = trailingDecoration.replace(title, "")
            val next = stripTitleEdges(stripped)
            if (next == title) break
            title = next
        }

        if (title.length !in MIN_TITLE_LENGTH..MAX_TITLE_LENGTH) return null
        if (title.lowercase(Locale.ROOT) in blockedTitles) return null
        return title
    }

    private fun stripTitleEdges(value: String): String =
        value.trim(
            ' ',
            '\t',
            '\n',
            '\r',
            '，',
            ',',
            '：',
            ':',
            '。',
            '！',
            '!',
            '？',
            '?',
            '“',
            '”',
            '"',
            '\'',
            '《',
            '》',
            '「',
            '」',
            '『',
            '』',
        )
}
