package com.dongpiandi.voicehelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LiveFootballScheduleCommandParserTest {
    @Test
    fun extractsMatchFromNaturalScheduledRequest() {
        assertEquals(
            "阿森纳",
            LiveFootballScheduleCommandParser
                .parse("阿森纳这场比赛开始时帮我打开直播")
                ?.query,
        )
        assertEquals(
            "国际米兰对AC米兰",
            LiveFootballScheduleCommandParser
                .parse("国际米兰对AC米兰的比赛开赛时打开直播")
                ?.query,
        )
        assertEquals(
            "阿森纳",
            LiveFootballScheduleCommandParser
                .parse("今晚十一点阿森纳比赛开始时帮我打开直播")
                ?.query,
        )
        assertEquals(
            "阿森纳",
            LiveFootballScheduleCommandParser
                .parse("明天凌晨3点阿森纳比赛开始时帮我打开直播")
                ?.query,
        )
        assertEquals(
            "阿森纳",
            LiveFootballScheduleCommandParser
                .parse("阿森纳明天凌晨3点的比赛开始时帮我打开直播")
                ?.query,
        )
        assertEquals(
            "阿森纳",
            LiveFootballScheduleCommandParser
                .parse("请帮我打开阿森纳比赛开始时打开直播")
                ?.query,
        )
    }

    @Test
    fun extractsMatchWhenMatchNameFollowsTheScheduleWord() {
        assertEquals(
            "阿森纳",
            LiveFootballScheduleCommandParser
                .parse("比赛开始时帮我打开阿森纳直播")
                ?.query,
        )
        assertNotNull(
            LiveFootballScheduleCommandParser
                .parse("英超这轮比赛到点自动播放直播"),
        )
    }

    @Test
    fun ignoresImmediatePlaybackAndOrdinaryQuestions() {
        assertNull(LiveFootballScheduleCommandParser.parse("打开足球直播"))
        assertNull(LiveFootballScheduleCommandParser.parse("播放英超直播"))
        assertNull(LiveFootballScheduleCommandParser.parse("英超比赛什么时候开始"))
        assertNull(LiveFootballScheduleCommandParser.parse("比赛开始时提醒我"))
    }
}
