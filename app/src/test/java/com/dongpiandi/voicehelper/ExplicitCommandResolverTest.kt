package com.dongpiandi.voicehelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitCommandResolverTest {
    @Test
    fun resolvesExplicitVideoMusicAndFootballCommands() {
        assertEquals(
            ExplicitCommand.OpenVideoSearch(
                VideoSearchRequest("流浪地球", openFirstResult = true),
            ),
            ExplicitCommandResolver.resolve("我要看流浪地球"),
        )
        assertEquals(
            ExplicitCommand.OpenVideoSearch(
                VideoSearchRequest("凡人修仙传", openFirstResult = true),
            ),
            ExplicitCommandResolver.resolve("帮我播放凡人修仙传"),
        )
        assertEquals(
            ExplicitCommand.PlayMusic("晴天"),
            ExplicitCommandResolver.resolve("我想听晴天"),
        )
        assertEquals(
            ExplicitCommand.PlayMusic("周杰伦的晴天"),
            ExplicitCommandResolver.resolve("帮我播放周杰伦的晴天"),
        )
        assertEquals(
            ExplicitCommand.OpenVideoSearch(
                VideoSearchRequest("凡人修仙传", openFirstResult = true),
            ),
            ExplicitCommandResolver.resolve("帮我播放凡人修仙传动漫"),
        )
        assertEquals(
            ExplicitCommand.OpenLiveFootball(LiveFootballRequest("阿森纳")),
            ExplicitCommandResolver.resolve("帮我打开阿森纳的比赛"),
        )
        assertEquals(
            ExplicitCommand.OpenLiveFootball(LiveFootballRequest("国际米兰")),
            ExplicitCommandResolver.resolve("帮我打开国际米兰的比赛"),
        )
        assertEquals(
            ExplicitCommand.OpenApp(AppLaunchRequest("抖音")),
            ExplicitCommandResolver.resolve("打开抖音"),
        )
        assertEquals(
            ExplicitCommand.ControlDouyin(
                DouyinVoiceRequest(
                    operation = DouyinVoiceOperation.SEARCH,
                    query = "三国演义",
                    openFirstResult = true,
                ),
            ),
            ExplicitCommandResolver.resolve("在抖音播放三国演义"),
        )
        assertEquals(
            ExplicitCommand.Control(GlobalControlAction.CONFIRM),
            ExplicitCommandResolver.resolve("确定"),
        )
    }

    @Test
    fun scheduleCommandWinsOverImmediateFootballOpen() {
        val command = ExplicitCommandResolver.resolve("比赛开始时打开足球直播")

        assertTrue(command is ExplicitCommand.ScheduleLiveFootball)
    }

    @Test
    fun futureReminderIsLeftForTheModel() {
        assertNull(ExplicitCommandResolver.resolve("明晚八点直接播放晴天音乐"))
        assertNull(ExplicitCommandResolver.resolve("到点自动播放音乐"))
    }

    @Test
    fun questionsAndTroubleshootingAreLeftForTheModel() {
        assertNull(ExplicitCommandResolver.resolve("怎么播放晴天音乐"))
        assertNull(ExplicitCommandResolver.resolve("播放不了怎么办"))
        assertNull(ExplicitCommandResolver.resolve("为什么打开不了阿森纳直播"))
    }
}
