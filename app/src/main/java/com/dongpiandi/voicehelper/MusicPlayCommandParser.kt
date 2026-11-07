package com.dongpiandi.voicehelper

import java.util.Locale

/**
 * Extracts an explicit music request from an assistant transcript.
 *
 * Bare "播放<标题>" stays with the video parser. Music only takes commands that say "听", or
 * "播放/放" together with a music noun, so an ambiguous video title is not stolen.
 */
internal object MusicPlayCommandParser {
    private const val MIN_QUERY_LENGTH = 1
    private const val MAX_QUERY_LENGTH = 60

    private data class CommandPrefix(
        val text: String,
        val requiresMusicNoun: Boolean,
    )

    private val commandPrefixes =
        listOf(
            CommandPrefix("帮我播放", true),
            CommandPrefix("给我播放", true),
            CommandPrefix("播放一下", true),
            CommandPrefix("我想听", false),
            CommandPrefix("我要听", false),
            CommandPrefix("帮我放", true),
            CommandPrefix("给我放", true),
            CommandPrefix("来一首", false),
            CommandPrefix("放一首", false),
            CommandPrefix("听一下", false),
            CommandPrefix("来首", false),
            CommandPrefix("放首", false),
            CommandPrefix("播放", true),
            CommandPrefix("听听", false),
        ).sortedByDescending { it.text.length }

    private val musicNouns = listOf("音乐", "歌曲", "单曲", "专辑", "歌")

    private val blockedQueries =
        setOf(
            "音乐",
            "歌曲",
            "歌",
            "单曲",
            "专辑",
            "歌单",
            "一首歌",
            "这首歌",
            "那首歌",
            "电台",
            "收音机",
            "听歌",
            "唱歌",
        )

    private val leadingDecoration =
        Regex(
            """^(?:(?:一下|一首|一曲|一个|首|曲|这首|那首|歌曲|音乐|歌)(?:叫|名为|名叫)?[，,：:\s]*|(?:叫|名为|名叫)[，,：:\s]*)+""",
        )

    private val trailingDecoration =
        Regex(
            """(?:[。！？!?，,、；;：:…~～\s]+|这首歌|那首歌|这首|那首|(?:的)?(?:音乐|歌曲|单曲|专辑|歌)|吧|啊|呀|呗|呢|啦|嘛|可以吗|好吗|行吗|好不好|谢谢|拜托)+$""",
        )

    fun parse(
        transcript: String,
        allowImplicitMusic: Boolean = false,
    ): String? {
        val input = transcript.trim().replace(Regex("\\s+"), " ")
        if (input.length < 3) return null

        val command = commandPrefixes.firstOrNull { input.startsWith(it.text) } ?: return null
        if (command.requiresMusicNoun && !allowImplicitMusic && musicNouns.none(input::contains)) {
            return null
        }

        var query = input.removePrefix(command.text).trim()
        query = leadingDecoration.replace(query, "").trim()
        query = stripQueryEdges(query)

        while (true) {
            val stripped = trailingDecoration.replace(query, "")
            val next = stripQueryEdges(stripped)
            if (next == query) break
            query = next
        }

        if (query.length !in MIN_QUERY_LENGTH..MAX_QUERY_LENGTH) return null
        if (query.lowercase(Locale.ROOT) in blockedQueries) return null
        return query
    }

    private fun stripQueryEdges(value: String): String =
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
