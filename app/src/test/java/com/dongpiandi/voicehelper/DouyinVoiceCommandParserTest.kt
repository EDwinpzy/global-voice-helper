package com.dongpiandi.voicehelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinVoiceCommandParserTest {
    @Test
    fun extractsScopedSearchAndPlayRequests() {
        assertEquals(
            DouyinVoiceRequest(
                operation = DouyinVoiceOperation.SEARCH,
                query = "三国演义",
                openFirstResult = false,
            ),
            DouyinVoiceCommandParser.parse("在抖音搜索三国演义"),
        )
        assertEquals(
            DouyinVoiceRequest(
                operation = DouyinVoiceOperation.SEARCH,
                query = "三国演义",
                openFirstResult = true,
            ),
            DouyinVoiceCommandParser.parse("抖音播放三国演义"),
        )
        assertEquals(
            DouyinVoiceRequest(
                operation = DouyinVoiceOperation.SEARCH,
                query = "周杰伦的晴天",
                openFirstResult = true,
            ),
            DouyinVoiceCommandParser.parse("帮我用抖音看一下周杰伦的晴天"),
        )
    }

    @Test
    fun searchCanRequestTheFirstResultWithoutASecondTurn() {
        val request = DouyinVoiceCommandParser.parse("在抖音搜索三国演义并播放第一个")

        assertEquals(DouyinVoiceOperation.SEARCH, request?.operation)
        assertEquals("三国演义", request?.query)
        assertTrue(request?.openFirstResult == true)
    }

    @Test
    fun parsesPlaybackAndEngagementOperations() {
        assertEquals(
            DouyinVoiceOperation.PAUSE,
            DouyinVoiceCommandParser.parse("抖音暂停一下")?.operation,
        )
        assertEquals(
            DouyinVoiceOperation.PLAY,
            DouyinVoiceCommandParser.parse("在抖音继续播放")?.operation,
        )
        assertEquals(
            DouyinVoiceOperation.NEXT,
            DouyinVoiceCommandParser.parse("抖音下一个视频")?.operation,
        )
        assertEquals(
            DouyinVoiceOperation.PREVIOUS,
            DouyinVoiceCommandParser.parse("抖音上一个视频")?.operation,
        )
        assertEquals(
            DouyinVoiceOperation.COMMENT,
            DouyinVoiceCommandParser.parse("在抖音打开评论")?.operation,
        )
        assertEquals(
            DouyinVoiceOperation.FAVORITE,
            DouyinVoiceCommandParser.parse("抖音收藏这个视频")?.operation,
        )
        assertEquals(
            DouyinVoiceOperation.UNFAVORITE,
            DouyinVoiceCommandParser.parse("抖音取消收藏")?.operation,
        )
        assertEquals(
            DouyinVoiceOperation.AUTHOR,
            DouyinVoiceCommandParser.parse("在抖音打开作者主页")?.operation,
        )
    }

    @Test
    fun leavesGenericAppLaunchAndConversationAlone() {
        assertNull(DouyinVoiceCommandParser.parse("打开抖音"))
        assertNull(DouyinVoiceCommandParser.parse("今天抖音怎么样"))
        assertNull(DouyinVoiceCommandParser.parse("在抖音上看视频"))
        assertNull(DouyinVoiceCommandParser.parse("抖音"))
        assertFalse(DouyinVoiceCommandParser.parse("抖音搜索三国演义")?.openFirstResult == true)
    }
}
