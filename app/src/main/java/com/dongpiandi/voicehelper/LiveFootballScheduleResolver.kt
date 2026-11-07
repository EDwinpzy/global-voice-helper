package com.dongpiandi.voicehelper

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs

internal data class LiveFootballContext(
    val answer: String,
    val capturedAtEpochMs: Long,
)

internal data class ResolvedLiveFootballSchedule(
    val query: String,
    val displayName: String,
    val kickoffAtEpochMs: Long,
)

internal sealed interface LiveFootballScheduleResolution {
    data class Ready(val schedule: ResolvedLiveFootballSchedule) : LiveFootballScheduleResolution

    data object NoContext : LiveFootballScheduleResolution

    data object NoTime : LiveFootballScheduleResolution

    data object NoMatch : LiveFootballScheduleResolution

    data object Ambiguous : LiveFootballScheduleResolution

    data object AlreadyStarted : LiveFootballScheduleResolution
}

internal object LiveFootballScheduleResolver {
    const val CONTEXT_TTL_MS = 12L * 60L * 60L * 1_000L

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
            "球赛",
        )

    private val scheduleQuestionTerms =
        listOf(
            "比赛",
            "赛程",
            "对阵",
            "开赛",
            "几点",
            "哪些",
            "有什么",
            "今晚",
            "今天",
            "明天",
        )

    fun isFootballScheduleQuestion(transcript: String): Boolean {
        val value = transcript.trim().lowercase(Locale.ROOT)
        return footballTerms.any(value::contains) &&
            scheduleQuestionTerms.any(value::contains)
    }

    fun resolve(
        request: LiveFootballScheduleRequest,
        context: LiveFootballContext?,
        nowEpochMs: Long,
        zone: ZoneId,
    ): LiveFootballScheduleResolution {
        val now =
            Instant.ofEpochMilli(nowEpochMs)
                .atZone(zone)
        val freshContext = context?.takeIf { isFresh(it, nowEpochMs) }
        val commandTime =
            LiveFootballKickoffTimeParser.parse(
                text = request.transcript,
                reference = now,
                now = now,
                zone = zone,
            )
        if (commandTime is LiveFootballKickoffResult.Found) {
            val explicitQuery = request.query
            if (explicitQuery != null) {
                return ready(
                    query = explicitQuery,
                    displayName = explicitQuery,
                    kickoff = commandTime.at,
                )
            }
            if (freshContext != null) {
                return resolveFromContext(
                    query = null,
                    context = freshContext,
                    now = now,
                    zone = zone,
                    explicitKickoff = commandTime.at,
                )
            }
            return LiveFootballScheduleResolution.NoMatch
        }
        if (commandTime is LiveFootballKickoffResult.Started) {
            return LiveFootballScheduleResolution.AlreadyStarted
        }
        if (freshContext == null) {
            return LiveFootballScheduleResolution.NoContext
        }
        return resolveFromContext(
            query = request.query,
            context = freshContext,
            now = now,
            zone = zone,
            explicitKickoff = null,
        )
    }

    private fun resolveFromContext(
        query: String?,
        context: LiveFootballContext,
        now: ZonedDateTime,
        zone: ZoneId,
        explicitKickoff: ZonedDateTime?,
    ): LiveFootballScheduleResolution {
        val reference =
            Instant.ofEpochMilli(context.capturedAtEpochMs)
                .atZone(zone)
        val parsed = parseContext(context.answer, reference, now, zone)
        if (parsed.found.isEmpty() && parsed.started.isEmpty()) {
            return LiveFootballScheduleResolution.NoTime
        }

        val matching =
            when {
                query == null && explicitKickoff != null ->
                    parsed.found.filter { candidate ->
                        abs(
                            candidate.kickoff.toInstant().toEpochMilli() -
                                explicitKickoff.toInstant().toEpochMilli(),
                        ) <= MATCHING_TIME_WINDOW_MS
                    }
                query == null -> parsed.found
                else ->
                    parsed.found.filter { candidate ->
                        matches(query, candidate.segment)
                    }
            }.distinctBy { candidate ->
                candidate.kickoff.toInstant().toEpochMilli()
            }
        if (matching.isEmpty()) {
            val startedMatches =
                if (query == null) {
                    parsed.started
                } else {
                    parsed.started.filter { candidate ->
                        matches(query, candidate)
                    }
                }
            return if (startedMatches.isNotEmpty()) {
                LiveFootballScheduleResolution.AlreadyStarted
            } else {
                LiveFootballScheduleResolution.NoMatch
            }
        }
        if (matching.size > 1) {
            return LiveFootballScheduleResolution.Ambiguous
        }

        val candidate = matching.single()
        val label = cleanMatchLabel(candidate.segment)
        val launchQuery = query ?: label ?: return LiveFootballScheduleResolution.NoMatch
        val displayName = query ?: label ?: launchQuery
        val kickoff = explicitKickoff ?: candidate.kickoff
        if (explicitKickoff != null && kickoff.isBefore(now.minusMinutes(5))) {
            return LiveFootballScheduleResolution.AlreadyStarted
        }
        return ready(
            query = launchQuery,
            displayName = displayName,
            kickoff = kickoff,
        )
    }

    private fun parseContext(
        answer: String,
        reference: ZonedDateTime,
        now: ZonedDateTime,
        zone: ZoneId,
    ): ParsedContext {
        val found = mutableListOf<ContextCandidate>()
        val started = mutableListOf<String>()
        splitContextSegments(answer)
            .map(::stripListNumber)
            .filter(String::isNotBlank)
            .forEach { segment ->
                when (
                    val result =
                        LiveFootballKickoffTimeParser.parse(
                            text = segment,
                            reference = reference,
                            now = now,
                            zone = zone,
                        )
                ) {
                    is LiveFootballKickoffResult.Found ->
                        found += ContextCandidate(segment, result.at)
                    LiveFootballKickoffResult.Started ->
                        started += segment
                    LiveFootballKickoffResult.Missing -> Unit
                }
            }
        return ParsedContext(found, started)
    }

    private fun splitContextSegments(answer: String): List<String> =
        answer
            .split(Regex("""[\n。！？；]+"""))
            .flatMap { segment ->
                if (LiveFootballKickoffTimeParser.countTimeMentions(segment) <= 1) {
                    listOf(segment)
                } else {
                    groupClausesByTime(segment)
                }
            }

    private fun groupClausesByTime(segment: String): List<String> {
        val grouped = mutableListOf<String>()
        var current = ""
        segment.split(Regex("""[，,]""")).forEach { clause ->
            current =
                if (current.isBlank()) {
                    clause.trim()
                } else {
                    "$current，${clause.trim()}"
                }
            if (LiveFootballKickoffTimeParser.countTimeMentions(clause) > 0) {
                if (current.isNotBlank()) grouped += current
                current = ""
            }
        }
        if (current.isNotBlank()) grouped += current
        return grouped
    }

    private fun matches(
        query: String,
        segment: String,
    ): Boolean {
        val compactQuery = canonicalize(compact(query))
        if (compactQuery.isEmpty()) return false
        val compactSegment = canonicalize(compact(segment))
        if (compactSegment.contains(compactQuery)) return true
        val tokens =
            query
                .split(Regex("""\s*(?:对|VS|vs|Vs|v|-|—|–)\s*"""))
                .map(::compact)
                .map(::canonicalize)
                .filter { it.length >= 2 }
        return tokens.size > 1 && tokens.all(compactSegment::contains)
    }

    private fun compact(value: String): String =
        value
            .lowercase(Locale.ROOT)
            .replace(Regex("""[\s，。！？、；：,.!?;:()（）\[\]【】\-—–]+"""), "")

    private fun canonicalize(value: String): String {
        var normalized = value
        teamAliases.forEach { (alias, canonical) ->
            normalized = normalized.replace(alias, canonical)
        }
        return normalized
    }

    private fun cleanMatchLabel(segment: String): String? {
        var label =
            segment
                .replace(Regex("""^\s*\d{1,2}[.、]\s*"""), "")
                .replace(isoDate, " ")
                .replace(chineseDate, " ")
                .replace(relativeDay, " ")
                .replace(colonTime, " ")
                .replace(pointTime, " ")
                .replace(Regex("""[，,。！？!?、；;：:…~～]+"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
        label = label.trim(' ', '的', '-', '—')
        return label.takeIf(String::isNotBlank)?.take(MAX_LABEL_LENGTH)
    }

    private fun stripListNumber(segment: String): String =
        segment.replace(Regex("""^\s*\d{1,2}[.、]\s*"""), "").trim()

    private fun isFresh(
        context: LiveFootballContext,
        nowEpochMs: Long,
    ): Boolean {
        val age = nowEpochMs - context.capturedAtEpochMs
        return age in -60_000L..CONTEXT_TTL_MS
    }

    private fun ready(
        query: String,
        displayName: String,
        kickoff: ZonedDateTime,
    ): LiveFootballScheduleResolution.Ready =
        LiveFootballScheduleResolution.Ready(
            ResolvedLiveFootballSchedule(
                query = query.take(MAX_QUERY_LENGTH),
                displayName = displayName.take(MAX_LABEL_LENGTH),
                kickoffAtEpochMs = kickoff.toInstant().toEpochMilli(),
            ),
        )

    private data class ParsedContext(
        val found: List<ContextCandidate>,
        val started: List<String>,
    )

    private data class ContextCandidate(
        val segment: String,
        val kickoff: ZonedDateTime,
    )

    private val isoDate =
        Regex("""(?<!\d)\d{4}[-/]\d{1,2}[-/]\d{1,2}(?!\d)""")
    private val chineseDate =
        Regex("""(?:(?:\d{4}\s*年\s*)?\d{1,2}\s*月\s*\d{1,2}\s*[日号])""")
    private val relativeDay =
        Regex("""(?:大后天|后天|明天|明日|明晚|今天|今日|今晚|今夜|本日|凌晨|上午|下午|晚上|傍晚)""")
    private val colonTime =
        Regex("""(?<!\d)\d{1,2}\s*[:：]\s*\d{2}(?!\d)""")
    private val pointTime =
        Regex("""(?<!\d)\d{1,2}\s*[点时]\s*(?:\d{1,2})?\s*分?""")
    private val teamAliases =
        mapOf(
            "国米" to "国际米兰",
            "皇马" to "皇家马德里",
            "巴萨" to "巴塞罗那",
            "马竞" to "马德里竞技",
            "拜仁" to "拜仁慕尼黑",
            "多特" to "多特蒙德",
            "尤文" to "尤文图斯",
            "巴黎" to "巴黎圣日耳曼",
            "热刺" to "托特纳姆热刺",
            "枪手" to "阿森纳",
            "蓝军" to "切尔西",
            "红军" to "利物浦",
        )

    private const val MAX_LABEL_LENGTH = 80
    private const val MAX_QUERY_LENGTH = 80
    private const val MATCHING_TIME_WINDOW_MS = 60_000L
}
