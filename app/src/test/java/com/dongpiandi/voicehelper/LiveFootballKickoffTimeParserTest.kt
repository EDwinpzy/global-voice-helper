package com.dongpiandi.voicehelper

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveFootballKickoffTimeParserTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun parsesTonightTimeAndKeepsTheReferenceDate() {
        val reference = at(2026, 11, 7, 18, 0)
        val result =
            parse(
                text = "阿森纳 vs 利物浦，今晚 23:30",
                reference = reference,
                now = reference.plusMinutes(5),
            )

        assertTrue(result is LiveFootballKickoffResult.Found)
        assertEquals(
            at(2026, 11, 7, 23, 30),
            (result as LiveFootballKickoffResult.Found).at,
        )
    }

    @Test
    fun parsesTomorrowAfterMidnightFromALateReference() {
        val reference = at(2026, 11, 7, 23, 55)
        val result =
            parse(
                text = "曼城 vs 切尔西，明天凌晨 3 点",
                reference = reference,
                now = reference.plusMinutes(1),
            )

        assertTrue(result is LiveFootballKickoffResult.Found)
        assertEquals(
            at(2026, 11, 8, 3, 0),
            (result as LiveFootballKickoffResult.Found).at,
        )
    }

    @Test
    fun parsesExplicitDateAndChineseHalfHour() {
        val reference = at(2026, 11, 7, 18, 0)
        val explicit =
            parse(
                text = "11月8日 03:00 阿森纳 vs 利物浦",
                reference = reference,
                now = reference,
            )
        val chinese =
            parse(
                text = "今晚十一点半开赛",
                reference = reference,
                now = reference,
            )

        assertEquals(
            at(2026, 11, 8, 3, 0),
            (explicit as LiveFootballKickoffResult.Found).at,
        )
        assertEquals(
            at(2026, 11, 7, 23, 30),
            (chinese as LiveFootballKickoffResult.Found).at,
        )
    }

    @Test
    fun rejectsFinishedMatchesButAllowsTheStartGraceWindow() {
        val reference = at(2026, 11, 7, 18, 0)
        val finished =
            parse(
                text = "阿森纳 vs 利物浦，今晚 20:00",
                reference = reference,
                now = at(2026, 11, 7, 20, 6),
            )
        val justStarted =
            parse(
                text = "阿森纳 vs 利物浦，今晚 20:00",
                reference = reference,
                now = at(2026, 11, 7, 20, 2),
            )

        assertEquals(LiveFootballKickoffResult.Started, finished)
        assertTrue(justStarted is LiveFootballKickoffResult.Found)
    }

    @Test
    fun treatsAnEarlyMorningTimeListedInTheEveningAsTomorrow() {
        val result =
            parse(
                text = "阿森纳 vs 利物浦，03:00",
                reference = at(2026, 11, 7, 18, 0),
                now = at(2026, 11, 7, 18, 5),
            )

        assertEquals(
            at(2026, 11, 8, 3, 0),
            (result as LiveFootballKickoffResult.Found).at,
        )
    }

    @Test
    fun missingTimeIsReportedSeparately() {
        val result =
            parse(
                text = "阿森纳 vs 利物浦，开赛时间待定",
                reference = at(2026, 11, 7, 18, 0),
                now = at(2026, 11, 7, 18, 5),
            )

        assertEquals(LiveFootballKickoffResult.Missing, result)
    }

    private fun parse(
        text: String,
        reference: ZonedDateTime,
        now: ZonedDateTime,
    ): LiveFootballKickoffResult =
        LiveFootballKickoffTimeParser.parse(
            text = text,
            reference = reference,
            now = now,
            zone = zone,
        )

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): ZonedDateTime = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)
}
