package com.dongpiandi.voicehelper

import java.util.Locale

internal enum class DouyinVoiceOperation(
    val wireValue: String,
) {
    SEARCH("search"),
    PLAY("play"),
    PAUSE("pause"),
    NEXT("next"),
    PREVIOUS("previous"),
    COMMENT("comment"),
    FAVORITE("favorite"),
    UNFAVORITE("unfavorite"),
    AUTHOR("author"),
}

internal data class DouyinVoiceRequest(
    val operation: DouyinVoiceOperation,
    val query: String? = null,
    val openFirstResult: Boolean = false,
)

/** Parses commands explicitly scoped to Douyin without guessing at generic video requests. */
internal object DouyinVoiceCommandParser {
    private const val MIN_QUERY_LENGTH = 1
    private const val MAX_QUERY_LENGTH = 60

    private data class QueryPrefix(
        val text: String,
        val openFirstResult: Boolean = false,
    )

    private val appScopedCommand =
        Regex(
            """^(?:(?:请|麻烦你|麻烦)\s*)?(?:(?:帮我|给我|替我)\s*)?""" +
                """(?:在|用|打开)?\s*""" +
                """(?:抖音(?:TV版|电视版|短视频|TV|电视)?|douyin(?:\s+tv)?)""" +
                """(?:\s*app)?\s*(?:上(?!一(?:个|条|集|页))|里(?:面)?|中)?""" +
                """\s*[，,：:]?\s*""" +
                """(?:(?:帮我|给我|替我)\s*)?(?:一下)?\s*[，,：:]?\s*(.*)$""",
            RegexOption.IGNORE_CASE,
        )

    private val trailingDecoration =
        Regex(
            """(?:一下|一点|吧|啊|呀|呗|呢|啦|嘛|谢谢|拜托|""" +
                """[\s，,。.!！?？:：~～]+)+$""",
        )

    private val leadingTitleDecoration =
        Regex("""^(?:(?:一部|一个|个|部|这条|那条|这个|那个|的)[，,：:\s]*)+""")

    private val trailingMediaDecoration =
        Regex("""(?:的)?(?:短)?视频$""")

    private val playbackSuffix =
        Regex(
            """(?:然后|并且|再|并)?(?:播放|打开|看一下|看)(?:一下)?""" +
                """(?:第(?:一|1)个|首条)?(?:视频)?$""",
        )

    private val searchPrefixes =
        listOf(
            QueryPrefix("搜索然后播放", openFirstResult = true),
            QueryPrefix("搜索并播放", openFirstResult = true),
            QueryPrefix("搜一下然后播放", openFirstResult = true),
            QueryPrefix("搜一下并播放", openFirstResult = true),
            QueryPrefix("搜索后播放", openFirstResult = true),
            QueryPrefix("搜索一下"),
            QueryPrefix("搜一搜"),
            QueryPrefix("搜一下"),
            QueryPrefix("搜索"),
            QueryPrefix("查一下"),
            QueryPrefix("查找"),
            QueryPrefix("找一下"),
            QueryPrefix("找找"),
            QueryPrefix("搜"),
            QueryPrefix("找"),
        ).sortedByDescending(QueryPrefix::text)

    private val playPrefixes =
        listOf(
            QueryPrefix("播放一下", openFirstResult = true),
            QueryPrefix("放一下", openFirstResult = true),
            QueryPrefix("看一下", openFirstResult = true),
            QueryPrefix("播放", openFirstResult = true),
            QueryPrefix("打开", openFirstResult = true),
            QueryPrefix("看看", openFirstResult = true),
            QueryPrefix("看", openFirstResult = true),
        ).sortedByDescending(QueryPrefix::text)

    private val operationPhrases =
        mapOf(
            DouyinVoiceOperation.PLAY to
                setOf("播放", "继续", "继续播放", "恢复播放", "接着播放", "继续看", "接着看"),
            DouyinVoiceOperation.PAUSE to
                setOf("暂停", "暂停播放", "暂停视频", "暂停一下"),
            DouyinVoiceOperation.NEXT to
                setOf(
                    "下一个",
                    "下一个视频",
                    "下一条",
                    "下一条视频",
                    "下一集",
                    "下一页",
                    "看下一个",
                    "看下一个视频",
                ),
            DouyinVoiceOperation.PREVIOUS to
                setOf(
                    "上一个",
                    "上一个视频",
                    "上一条",
                    "上一条视频",
                    "上一集",
                    "上一页",
                    "前一个",
                    "前一个视频",
                    "看上一个",
                    "看上一个视频",
                ),
            DouyinVoiceOperation.COMMENT to
                setOf("评论", "打开评论", "看评论", "看看评论", "打开评论区", "进入评论"),
            DouyinVoiceOperation.FAVORITE to
                setOf("收藏", "收藏视频", "收藏这个视频", "加收藏", "打开收藏"),
            DouyinVoiceOperation.UNFAVORITE to
                setOf("取消收藏", "取消收藏视频", "取消这个收藏"),
            DouyinVoiceOperation.AUTHOR to
                setOf(
                    "作者",
                    "看作者",
                    "看看作者",
                    "打开作者",
                    "作者主页",
                    "打开作者主页",
                    "进入作者主页",
                ),
        )

    fun parse(transcript: String): DouyinVoiceRequest? {
        val input = transcript.trim().replace(Regex("\\s+"), " ")
        if (input.length < 3) return null

        val match = appScopedCommand.matchEntire(input) ?: return null
        var rest = match.groupValues[1].trim()
        rest = trimQueryEdges(rest)
        if (rest.isEmpty()) return null

        val operation = operationFor(rest)
        if (operation != null) {
            return DouyinVoiceRequest(operation)
        }

        searchPrefixes.firstOrNull { rest.startsWith(it.text) }?.let { prefix ->
            return buildSearchRequest(
                rawQuery = rest.removePrefix(prefix.text),
                openFirstResult = prefix.openFirstResult,
            )
        }
        playPrefixes.firstOrNull { rest.startsWith(it.text) }?.let { prefix ->
            return buildSearchRequest(
                rawQuery = rest.removePrefix(prefix.text),
                openFirstResult = true,
            )
        }
        return null
    }

    private fun operationFor(value: String): DouyinVoiceOperation? {
        val compact =
            trailingDecoration.replace(value, "")
                .replace(Regex("\\s+"), "")
        return operationPhrases.entries.firstOrNull { (_, phrases) ->
            compact.lowercase(Locale.ROOT) in phrases
        }?.key
    }

    private fun buildSearchRequest(
        rawQuery: String,
        openFirstResult: Boolean,
    ): DouyinVoiceRequest? {
        var query = trimQueryEdges(rawQuery)
        var shouldOpenFirst = openFirstResult
        playbackSuffix.find(query)?.takeIf { it.range.last == query.lastIndex }?.let { match ->
            query = query.removeRange(match.range).trim()
            shouldOpenFirst = true
        }
        query = trimQueryEdges(query)
        while (true) {
            val stripped = trailingMediaDecoration.replace(query, "").trim()
            val next = trimQueryEdges(stripped)
            if (next == query) break
            query = next
        }
        if (query.length !in MIN_QUERY_LENGTH..MAX_QUERY_LENGTH) return null
        return DouyinVoiceRequest(
            operation = DouyinVoiceOperation.SEARCH,
            query = query,
            openFirstResult = shouldOpenFirst,
        )
    }

    private fun trimQueryEdges(value: String): String {
        var current = leadingTitleDecoration.replace(value.trim(), "").trim()
        while (true) {
            val stripped = trailingDecoration.replace(current, "")
            val next =
                stripped.trim(
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
            if (next == current) break
            current = next
        }
        return current
    }
}
