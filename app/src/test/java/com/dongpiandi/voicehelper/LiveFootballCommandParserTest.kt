package com.dongpiandi.voicehelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveFootballCommandParserTest {
    @Test
    fun recognizesExplicitFootballAndLiveRequests() {
        assertTrue(LiveFootballCommandParser.parse("打开足球直播") != null)
        assertEquals("英超", LiveFootballCommandParser.parse("我要看英超")?.query)
        assertEquals("欧冠", LiveFootballCommandParser.parse("播放欧冠比赛")?.query)
        assertTrue(LiveFootballCommandParser.parse("看看球赛") != null)
    }

    @Test
    fun extractsNamedTeamFromNaturalRequest() {
        assertEquals(
            "国际米兰",
            LiveFootballCommandParser.parse("帮我打开国际米兰的比赛")?.query,
        )
        assertEquals(
            "国米",
            LiveFootballCommandParser.parse("播放国米的直播")?.query,
        )
        assertNull(LiveFootballCommandParser.parse("打开足球直播")?.query)
    }

    @Test
    fun ignoresMusicAndVideoRequests() {
        assertNull(LiveFootballCommandParser.parse("播放晴天音乐"))
        assertNull(LiveFootballCommandParser.parse("播放流浪地球电影"))
        assertNull(LiveFootballCommandParser.parse("打开设置"))
    }
}
