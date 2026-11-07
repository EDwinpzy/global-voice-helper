package com.dongpiandi.voicehelper

import java.util.Locale

internal data class LiveFootballRequest(
    val query: String?,
)

/** Detects football/live-sports requests and extracts the requested team or competition. */
internal object LiveFootballCommandParser {
    private val requestPrefixes =
        listOf(
            "帮我打开",
            "帮我播放",
            "给我打开",
            "给我播放",
            "我要看看",
            "我想看看",
            "播放一下",
            "看一看",
            "看看",
            "打开",
            "播放",
            "我要看",
            "我想看",
            "看",
        ).sortedByDescending { it.length }

    private val liveTerms =
        listOf(
            "足球直播",
            "足球比赛",
            "足球赛",
            "看足球",
            "看球",
            "球赛",
            "球直播",
            "体育直播",
            "比赛直播",
            "直播比赛",
            "比赛",
            "直播",
        )

    private val footballTerms =
        listOf(
            "足球",
            "英超",
            "西甲",
            "意甲",
            "德甲",
            "法甲",
            "中超",
            "欧冠",
            "欧联",
            "世界杯",
            "亚洲杯",
            "欧洲杯",
            "美洲杯",
        )

    private val genericQueries =
        setOf(
            "直播",
            "足球",
            "足球直播",
            "足球比赛",
            "足球赛",
            "比赛",
            "球赛",
            "球",
            "看球",
            "体育",
            "体育直播",
            "比赛直播",
        )

    private val trailingDecoration =
        Regex(
            """(?:[。！？!?，,、；;：:…~～\s]+|(?:的)?(?:比赛|赛事|直播|足球|球赛)|吧|啊|呀|呢|啦|嘛|谢谢|拜托)+$""",
        )

    fun parse(transcript: String): LiveFootballRequest? {
        val input = normalize(transcript)
        if (input.length < 2) return null
        val liveContext = liveTerms.any(input::contains) || footballTerms.any(input::contains)
        if (!liveContext) return null
        if (requestPrefixes.none(input::startsWith) && !input.contains("看")) return null
        return LiveFootballRequest(extractQuery(input))
    }

    /** Extracts the team or competition after the model has already classified a request as LIVE. */
    fun extractQuery(transcript: String): String? {
        var query = normalize(transcript)
        requestPrefixes.firstOrNull(query::startsWith)?.let { prefix ->
            query = query.removePrefix(prefix).trim()
        }
        while (true) {
            val stripped = trailingDecoration.replace(query, "").trim()
            if (stripped == query) break
            query = stripped
        }
        query = query.trim(' ', '，', ',', '：', ':', '。', '！', '!', '？', '?')
        if (query in genericQueries) return null
        return query.ifBlank { null }
    }

    private fun normalize(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
}
