package com.dongpiandi.voicehelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSearchCommandParserTest {
    @Test
    fun extractsTitleFromExplicitViewingRequest() {
        assertEquals("流浪地球", VideoSearchCommandParser.parse("我要看流浪地球")?.query)
        assertEquals("流浪地球2", VideoSearchCommandParser.parse("我想看《流浪地球2》")?.query)
        assertEquals("庆余年", VideoSearchCommandParser.parse("帮我看一下庆余年吧")?.query)
        assertTrue(VideoSearchCommandParser.parse("我要看流浪地球")?.openFirstResult == true)
        assertNull(VideoSearchCommandParser.parse("帮我播放凡人修仙传"))
        assertNull(VideoSearchCommandParser.parse("给我播放凡人修仙传"))
    }

    @Test
    fun removesMediaWordingAndPoliteSuffixes() {
        assertEquals(
            "流浪地球",
            VideoSearchCommandParser.parse("我想看一部电影，叫流浪地球")?.query,
        )
        assertEquals(
            "流浪地球",
            VideoSearchCommandParser.parse("帮我找流浪地球电影，谢谢")?.query,
        )
        assertEquals(
            "三体",
            VideoSearchCommandParser.parse("打开三体电视剧可以吗")?.query,
        )
        assertFalse(VideoSearchCommandParser.parse("帮我找流浪地球电影，谢谢")?.openFirstResult == true)
        assertTrue(VideoSearchCommandParser.parse("打开三体电视剧可以吗")?.openFirstResult == true)
    }

    @Test
    fun explicitSearchCommandsStayOnTheResultPage() {
        assertFalse(VideoSearchCommandParser.parse("搜索流浪地球电影")?.openFirstResult == true)
        assertFalse(VideoSearchCommandParser.parse("帮我搜一下三体电视剧")?.openFirstResult == true)
        assertTrue(VideoSearchCommandParser.parse("播放流浪地球电影")?.openFirstResult == true)
        assertEquals(
            "凡人修仙传",
            VideoSearchCommandParser.parse("给我播放凡人修仙传动漫")?.query,
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
