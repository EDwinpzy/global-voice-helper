package com.dongpiandi.voicehelper

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveFootballScheduleResolverTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val capturedAt = at(2026, 11, 7, 18, 0)
    private val now = capturedAt.plusMinutes(5)
    private val context =
        LiveFootballContext(
            answer =
                """
                今晚英超有两场。

                1. 阿森纳 vs 利物浦，23:30。

                2. 曼城 vs 切尔西，明天凌晨 03:00。
                """.trimIndent(),
            capturedAtEpochMs = capturedAt.toInstant().toEpochMilli(),
        )

    @Test
    fun resolvesNamedMatchFromPreviousAnswer() {
        val result =
            resolve(
                request = checkNotNull(LiveFootballScheduleCommandParser.parse("阿森纳这场比赛开始时帮我打开直播")),
                context = context,
            )

        val schedule =
            (result as LiveFootballScheduleResolution.Ready).schedule
        assertEquals("阿森纳", schedule.query)
        assertEquals(at(2026, 11, 7, 23, 30).toInstant().toEpochMilli(), schedule.kickoffAtEpochMs)
    }

    @Test
    fun resolvesCompoundMatchNameAcrossVsSpelling() {
        val result =
            resolve(
                request =
                    checkNotNull(
                        LiveFootballScheduleCommandParser
                            .parse("阿森纳对利物浦的比赛开始时打开直播"),
                    ),
                context = context,
            )

        assertTrue(result is LiveFootballScheduleResolution.Ready)
    }

    @Test
    fun resolvesCommonTeamAliasFromPreviousAnswer() {
        val interContext =
            context.copy(
                answer = "1. 国际米兰 vs AC米兰，23:30。",
            )
        val result =
            resolve(
                request = checkNotNull(LiveFootballScheduleCommandParser.parse("国米比赛开始时打开直播")),
                context = interContext,
            )

        val schedule =
            (result as LiveFootballScheduleResolution.Ready).schedule
        assertEquals("国米", schedule.query)
    }

    @Test
    fun resolvesASecondMatchWhenTimesShareOneAnswerLine() {
        val denseContext =
            context.copy(
                answer = "阿森纳 vs 利物浦，23:30，曼城 vs 切尔西，明天凌晨 03:00。",
            )
        val result =
            resolve(
                request = checkNotNull(LiveFootballScheduleCommandParser.parse("曼城比赛开始时打开直播")),
                context = denseContext,
            )

        val schedule =
            (result as LiveFootballScheduleResolution.Ready).schedule
        assertEquals("曼城", schedule.query)
        assertEquals(
            at(2026, 11, 8, 3, 0).toInstant().toEpochMilli(),
            schedule.kickoffAtEpochMs,
        )
    }

    @Test
    fun explicitTimeCanIdentifyTheMatchWhenNoNameIsSpoken() {
        val result =
            resolve(
                request =
                    checkNotNull(
                        LiveFootballScheduleCommandParser
                            .parse("比赛在今晚11点30分开始时帮我打开直播"),
                    ),
                context = context,
            )

        val schedule =
            (result as LiveFootballScheduleResolution.Ready).schedule
        assertEquals("阿森纳 vs 利物浦", schedule.query)
        assertEquals(at(2026, 11, 7, 23, 30).toInstant().toEpochMilli(), schedule.kickoffAtEpochMs)
    }

    @Test
    fun asksForAChoiceWhenTheMatchIsNotNamed() {
        val result =
            resolve(
                request = checkNotNull(LiveFootballScheduleCommandParser.parse("比赛开始时帮我打开直播")),
                context = context,
            )

        assertEquals(LiveFootballScheduleResolution.Ambiguous, result)
    }

    @Test
    fun explicitCommandTimeWorksWithoutPreviousContext() {
        val request =
            checkNotNull(
                LiveFootballScheduleCommandParser
                    .parse("今晚11点阿森纳比赛开始时帮我打开直播"),
            )
        val result = resolve(request, context = null)

        val schedule =
            (result as LiveFootballScheduleResolution.Ready).schedule
        assertEquals("阿森纳", schedule.query)
        assertEquals(at(2026, 11, 7, 23, 0).toInstant().toEpochMilli(), schedule.kickoffAtEpochMs)
    }

    @Test
    fun reportsMissingMatchAndMissingContextSeparately() {
        val noMatch =
            resolve(
                request = checkNotNull(LiveFootballScheduleCommandParser.parse("热刺比赛开始时打开直播")),
                context = context,
            )
        val noContext =
            resolve(
                request = checkNotNull(LiveFootballScheduleCommandParser.parse("阿森纳比赛开始时打开直播")),
                context = null,
            )

        assertEquals(LiveFootballScheduleResolution.NoMatch, noMatch)
        assertEquals(LiveFootballScheduleResolution.NoContext, noContext)
    }

    @Test
    fun reportsFinishedMatchInsteadOfSchedulingTheNextDay() {
        val startedContext =
            context.copy(
                answer = "阿森纳 vs 利物浦，今晚 19:00。",
            )
        val result =
            resolve(
                request = checkNotNull(LiveFootballScheduleCommandParser.parse("阿森纳比赛开始时打开直播")),
                context = startedContext,
                now = at(2026, 11, 7, 19, 30),
            )

        assertEquals(LiveFootballScheduleResolution.AlreadyStarted, result)
    }

    @Test
    fun staleContextIsIgnored() {
        val result =
            resolve(
                request = checkNotNull(LiveFootballScheduleCommandParser.parse("阿森纳比赛开始时打开直播")),
                context = context,
                now = capturedAt.plusHours(13),
            )

        assertEquals(LiveFootballScheduleResolution.NoContext, result)
    }

    @Test
    fun footballContextPolicyOnlyStoresFixtureQuestions() {
        assertTrue(LiveFootballScheduleResolver.isFootballScheduleQuestion("英超今晚的比赛有哪些"))
        assertTrue(LiveFootballScheduleResolver.isFootballScheduleQuestion("欧冠明天赛程"))
        assertFalse(LiveFootballScheduleResolver.isFootballScheduleQuestion("明天天气怎么样"))
        assertFalse(LiveFootballScheduleResolver.isFootballScheduleQuestion("英超积分榜"))
    }

    private fun resolve(
        request: LiveFootballScheduleRequest,
        context: LiveFootballContext?,
        now: ZonedDateTime = this.now,
    ): LiveFootballScheduleResolution =
        LiveFootballScheduleResolver.resolve(
            request = request,
            context = context,
            nowEpochMs = now.toInstant().toEpochMilli(),
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
