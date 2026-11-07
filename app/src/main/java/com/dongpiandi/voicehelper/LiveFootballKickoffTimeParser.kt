package com.dongpiandi.voicehelper

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

internal sealed interface LiveFootballKickoffResult {
    data class Found(val at: ZonedDateTime) : LiveFootballKickoffResult

    data object Started : LiveFootballKickoffResult

    data object Missing : LiveFootballKickoffResult
}

/** Parses the kickoff date and time from a single line of a football fixture answer. */
internal object LiveFootballKickoffTimeParser {
    private val colonTime =
        Regex("""(?<!\d)(\d{1,2})\s*[:：]\s*(\d{2})(?!\d)""")

    private val arabicHalfHour =
        Regex("""(?<!\d)(\d{1,2})\s*点\s*半""")

    private val chineseHalfHour =
        Regex("""(?<!\d)([零〇一二两三四五六七八九十]{1,3})\s*点\s*半""")

    private val arabicPointTime =
        Regex("""(?<!\d)(\d{1,2})\s*[点时]\s*(\d{1,2})?\s*分?""")

    private val chinesePointTime =
        Regex(
            """(?<!\d)([零〇一二两三四五六七八九十]{1,3})\s*[点时]\s*([零〇一二三四五六七八九十]{1,3})?\s*分?""",
        )

    private val isoDate =
        Regex("""(?<!\d)(\d{4})[-/](\d{1,2})[-/](\d{1,2})(?!\d)""")

    private val chineseDate =
        Regex("""(?:(?:(\d{4})\s*年\s*)?(\d{1,2})\s*月\s*(\d{1,2})\s*[日号])""")

    private val timeMention =
        Regex(
            """(?<!\d)(?:\d{1,2}\s*[:：]\s*\d{2}|(?:\d{1,2}|[零〇一二两三四五六七八九十]{1,3})\s*[点时]\s*(?:\d{1,2}|[零〇一二三四五六七八九十]{1,3})?\s*分?)(?!\d)""",
        )

    private val latePeriod =
        listOf(
            "下午",
            "晚上",
            "傍晚",
            "今晚",
            "明晚",
            "夜里",
        )

    private val digits =
        mapOf(
            '零' to 0,
            '〇' to 0,
            '一' to 1,
            '二' to 2,
            '两' to 2,
            '三' to 3,
            '四' to 4,
            '五' to 5,
            '六' to 6,
            '七' to 7,
            '八' to 8,
            '九' to 9,
        )

    fun parse(
        text: String,
        reference: ZonedDateTime,
        now: ZonedDateTime,
        zone: ZoneId,
    ): LiveFootballKickoffResult {
        val value = text.trim()
        if (value.isEmpty()) return LiveFootballKickoffResult.Missing
        val time = parseTime(value) ?: return LiveFootballKickoffResult.Missing
        val referenceAtZone = reference.withZoneSameInstant(zone)
        val nowAtZone = now.withZoneSameInstant(zone)

        val explicitDate = parseDate(value, referenceAtZone.toLocalDate())
        val dayOffset = parseDayOffset(value, referenceAtZone)
        var date =
            explicitDate?.date
                ?: referenceAtZone.toLocalDate().plusDays(dayOffset?.toLong() ?: 0L)
        var hour = time.first
        if (hour in 1..11 && latePeriod.any(value::contains)) {
            hour += 12
        }
        if (hour == 12 && value.contains("凌晨")) {
            hour = 0
        }
        if (hour !in 0..23 || time.second !in 0..59) {
            return LiveFootballKickoffResult.Missing
        }

        if (
            explicitDate == null &&
            dayOffset == null &&
            hour < EARLY_MORNING_HOUR &&
            referenceAtZone.hour >= NOON_HOUR
        ) {
            date = date.plusDays(1)
        }

        val kickoff =
            ZonedDateTime.of(
                date,
                LocalTime.of(hour, time.second),
                zone,
            )
        return when {
            kickoff.isAfter(nowAtZone) -> LiveFootballKickoffResult.Found(kickoff)
            !kickoff.isBefore(nowAtZone.minusMinutes(LATE_GRACE_MINUTES)) ->
                LiveFootballKickoffResult.Found(kickoff)
            else -> LiveFootballKickoffResult.Started
        }
    }

    fun countTimeMentions(text: String): Int = timeMention.findAll(text).count()

    private fun parseTime(value: String): Pair<Int, Int>? {
        colonTime.find(value)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            return hour to minute
        }
        arabicHalfHour.find(value)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            return hour to 30
        }
        chineseHalfHour.find(value)?.let { match ->
            val hour = parseChineseNumber(match.groupValues[1]) ?: return null
            return hour to 30
        }
        arabicPointTime.find(value)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            return hour to minute
        }
        chinesePointTime.find(value)?.let { match ->
            val hour = parseChineseNumber(match.groupValues[1]) ?: return null
            val minute =
                match.groupValues[2]
                    .takeIf(String::isNotEmpty)
                    ?.let(::parseChineseNumber)
                    ?: 0
            return hour to minute
        }
        return null
    }

    private fun parseDate(
        value: String,
        referenceDate: LocalDate,
    ): ParsedDate? {
        isoDate.find(value)?.let { match ->
            return parsedDate(
                year = match.groupValues[1].toIntOrNull(),
                month = match.groupValues[2].toIntOrNull(),
                day = match.groupValues[3].toIntOrNull(),
                referenceDate = referenceDate,
            )
        }
        chineseDate.find(value)?.let { match ->
            return parsedDate(
                year = match.groupValues[1].toIntOrNull(),
                month = match.groupValues[2].toIntOrNull(),
                day = match.groupValues[3].toIntOrNull(),
                referenceDate = referenceDate,
            )
        }
        return null
    }

    private fun parsedDate(
        year: Int?,
        month: Int?,
        day: Int?,
        referenceDate: LocalDate,
    ): ParsedDate? {
        if (month == null || day == null) return null
        if (month !in 1..12 || day !in 1..31) return null
        val explicitYear = year != null
        var date =
            runCatching {
                LocalDate.of(year ?: referenceDate.year, month, day)
            }.getOrNull() ?: return null
        if (!explicitYear && date.isBefore(referenceDate.minusDays(180))) {
            date = date.plusYears(1)
        }
        return ParsedDate(date)
    }

    private fun parseDayOffset(
        value: String,
        reference: ZonedDateTime,
    ): Int? =
        when {
            value.contains("大后天") -> 3
            value.contains("后天") -> 2
            value.contains("明天") ||
                value.contains("明日") ||
                value.contains("明晚") -> 1
            value.contains("今天") ||
                value.contains("今日") ||
                value.contains("今晚") ||
                value.contains("今夜") ||
                value.contains("本日") -> 0
            value.contains("凌晨") && reference.hour >= NOON_HOUR -> 1
            else -> null
        }

    private fun parseChineseNumber(raw: String): Int? {
        if (raw.isEmpty()) return null
        raw.toIntOrNull()?.let { return it }
        if ('十' !in raw) {
            return digits[raw.singleOrNull() ?: return null]
        }
        val parts = raw.split('十', limit = 2)
        val tens =
            if (parts[0].isEmpty()) {
                1
            } else {
                digits[parts[0].lastOrNull() ?: return null] ?: return null
            }
        val ones =
            if (parts.size == 1 || parts[1].isEmpty()) {
                0
            } else {
                digits[parts[1].firstOrNull() ?: return null] ?: return null
            }
        return tens * 10 + ones
    }

    private data class ParsedDate(val date: LocalDate)

    private const val EARLY_MORNING_HOUR = 6
    private const val NOON_HOUR = 12
    private const val LATE_GRACE_MINUTES = 5L
}
