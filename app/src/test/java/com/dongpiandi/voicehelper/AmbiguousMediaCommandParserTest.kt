package com.dongpiandi.voicehelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmbiguousMediaCommandParserTest {
    @Test
    fun leavesBarePlayRequestsForMediaClassification() {
        assertEquals("晴天", AmbiguousMediaCommandParser.parse("播放晴天"))
        assertEquals("流浪地球", AmbiguousMediaCommandParser.parse("帮我播放流浪地球"))
    }

    @Test
    fun ignoresExplicitMusicAndVideoRequests() {
        assertNull(AmbiguousMediaCommandParser.parse("播放晴天音乐"))
        assertNull(AmbiguousMediaCommandParser.parse("播放流浪地球电影"))
        assertNull(AmbiguousMediaCommandParser.parse("播放英超直播"))
        assertNull(AmbiguousMediaCommandParser.parse("我要看流浪地球"))
    }
}
