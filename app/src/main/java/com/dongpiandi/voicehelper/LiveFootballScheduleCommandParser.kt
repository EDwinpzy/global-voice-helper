package com.dongpiandi.voicehelper

import java.util.Locale

internal data class LiveFootballScheduleRequest(
    val transcript: String,
    val query: String?,
)

/**
 * Detects requests to open a football stream when a match starts. This must run before the
 * immediate live-football command parser so "比赛开始时打开直播" is not launched right away.
 */
internal object LiveFootballScheduleCommandParser {
    private val liveTerms =
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
            "比赛",
            "球赛",
            "直播",
        )

    private val scheduleMarkers =
        listOf(
            "开始的时候",
            "开赛的时候",
            "时间到了",
            "一开始",
            "一开赛",
            "开始时",
            "开赛时",
            "到时候",
            "到点",
            "定时",
            "提前打开",
            "提前播放",
        ).sortedByDescending(String::length)

    private val openVerbs =
        listOf(
            "打开",
            "播放",
            "切到",
            "进入",
            "看",
        )

    private val queryPrefixes =
        listOf(
            "帮我",
            "给我",
            "麻烦",
            "请",
            "到时候",
            "记得",
            "自动",
            "直接",
            "到",
            "等",
            "在",
            "于",
        ).sortedByDescending(String::length)

    private val querySuffixes =
        listOf(
            "这场比赛",
            "那场比赛",
            "的比赛",
            "这场",
            "那场",
            "比赛",
            "球赛",
            "足球",
            "直播",
            "在",
            "于",
            "将",
            "的",
        ).sortedByDescending(String::length)

    private val queryLeadWords =
        (queryPrefixes + openVerbs)
            .distinct()
            .sortedByDescending(String::length)

    private val genericQueries =
        setOf(
            "直播",
            "足球",
            "足球直播",
            "比赛",
            "球赛",
            "这场",
            "那场",
            "这场比赛",
            "那场比赛",
            "打开",
            "播放",
            "切到",
            "进入",
            "看",
        )

    private val leadingDay =
        Regex(
            """^(?:(?:今天|今晚|今夜|明天|明日|明晚|后天|大后天|凌晨|早上|上午|中午|下午|晚上|傍晚)\s*)+""",
        )

    private val leadingClock =
        Regex(
            """^(?:\d{1,2}|[零〇一二两三四五六七八九十]{1,3})\s*(?:[:：]\s*\d{1,2}|[点时]\s*(?:\d{1,2}|[零〇一二三四五六七八九十]{1,3})?\s*分?|点半)\s*""",
        )

    private val embeddedDate =
        Regex(
            """(?:(?:今天|今晚|今夜|明天|明日|明晚|后天|大后天)\s*)?\d{1,2}\s*月\s*\d{1,2}\s*[日号]\s*(?:的)?""",
        )

    private val embeddedClock =
        Regex(
            """(?:(?:今天|今晚|今夜|明天|明日|明晚|后天|大后天|凌晨|早上|上午|中午|下午|晚上|傍晚)\s*)*(?:\d{1,2}|[零〇一二两三四五六七八九十]{1,3})\s*(?:[:：]\s*\d{1,2}|[点时]\s*(?:\d{1,2}|[零〇一二三四五六七八九十]{1,3})?\s*分?|点半)\s*(?:的)?""",
        )

    fun parse(transcript: String): LiveFootballScheduleRequest? {
        val original = transcript.trim().replace(Regex("\\s+"), " ")
        if (original.length < 4) return null
        val searchable = original.lowercase(Locale.ROOT)
        val markerIndex =
            scheduleMarkers
                .map(searchable::indexOf)
                .filter { it >= 0 }
                .minOrNull()
                ?: return null
        if (liveTerms.none(searchable::contains)) return null
        if (openVerbs.none(searchable::contains)) return null
        return LiveFootballScheduleRequest(
            transcript = original,
            query = extractQuery(original, markerIndex),
        )
    }

    private fun extractQuery(
        input: String,
        scheduleMarkerIndex: Int,
    ): String? {
        val before = cleanQuery(input.substring(0, scheduleMarkerIndex))
        if (before != null) return before
        return cleanQuery(input.substring(scheduleMarkerIndex), removeScheduleWords = true)
    }

    private fun cleanQuery(
        candidate: String,
        removeScheduleWords: Boolean = false,
    ): String? {
        var value = candidate.trim()
        if (removeScheduleWords) {
            scheduleMarkers.forEach { marker ->
                value = value.replaceFirst(marker, " ", ignoreCase = true)
            }
            openVerbs.forEach { verb ->
                value = value.replaceFirst(verb, " ", ignoreCase = true)
            }
        }
        value = value.replace(Regex("\\s+"), " ").trim()
        value = stripQueryPrefixes(value)
        value = leadingDay.replace(value, "").trim()
        value = leadingClock.replace(value, "").trim()
        value = embeddedDate.replace(value, " ").trim()
        value = embeddedClock.replace(value, " ").trim()
        value = stripQueryPrefixes(value)
        value = value.replace(Regex("[，,。！？!?、；;：:…~～]+"), " ").trim()
        value = value.replace(Regex("\\s+"), " ")
        while (true) {
            val suffix =
                querySuffixes.firstOrNull {
                    value.endsWith(it, ignoreCase = true)
                } ?: break
            value = value.removeSuffix(suffix).trim().trimEnd('的', ' ', '，', ',')
        }
        value = stripQueryPrefixes(value)
        value = value.trim(' ', '，', ',', '：', ':', '。', '！', '!', '？', '?')
        if (value.isEmpty()) return null
        if (value.lowercase(Locale.ROOT) in genericQueries) return null
        return value.take(MAX_QUERY_LENGTH)
    }

    private fun stripQueryPrefixes(candidate: String): String {
        var value = candidate
        while (true) {
            val prefix =
                queryLeadWords.firstOrNull {
                    value.startsWith(it, ignoreCase = true)
                } ?: break
            value = value.substring(prefix.length).trim()
        }
        return value
    }

    private const val MAX_QUERY_LENGTH = 80
}
