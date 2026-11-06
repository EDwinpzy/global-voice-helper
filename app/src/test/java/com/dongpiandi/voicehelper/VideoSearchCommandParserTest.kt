package com.dongpiandi.voicehelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoSearchCommandParserTest {
    @Test
    fun extractsTitleFromExplicitViewingRequest() {
        assertEquals("流浪地球", VideoSearchCommandParser.parse("我要看流浪地球"))
        assertEquals("流浪地球2", VideoSearchCommandParser.parse("我想看《流浪地球2》"))
        assertEquals("庆余年", VideoSearchCommandParser.parse("帮我看一下庆余年吧"))
    }

    @Test
    fun removesMediaWordingAndPoliteSuffixes() {
        assertEquals(
            "流浪地球",
            VideoSearchCommandParser.parse("我想看一部电影，叫流浪地球"),
        )
        assertEquals(
            "流浪地球",
            VideoSearchCommandParser.parse("帮我找流浪地球电影，谢谢"),
        )
        assertEquals(
            "三体",
            VideoSearchCommandParser.parse("打开三体电视剧可以吗"),
        )
    }

    @Test
    fun ignoresNonVideoConversation() {
        assertNull(VideoSearchCommandParser.parse("今天天气怎么样"))
        assertNull(VideoSearchCommandParser.parse("打开设置"))
        assertNull(VideoSearchCommandParser.parse("我要看电影"))
        assertNull(VideoSearchCommandParser.parse("播放音乐"))
        assertNull(VideoSearchCommandParser.parse("我要看"))
    }

    @Test
    fun ignoresOverlongTitles() {
        val longTitle = "流".repeat(61)
        assertNull(VideoSearchCommandParser.parse("我要看$longTitle"))
    }
}
