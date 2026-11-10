package com.dongpiandi.voicehelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlobalControlCommandParserTest {
    @Test
    fun mapsNavigationAndConfirmationCommands() {
        assertEquals(GlobalControlAction.UP, GlobalControlCommandParser.parse("向上"))
        assertEquals(GlobalControlAction.DOWN, GlobalControlCommandParser.parse("下一项"))
        assertEquals(GlobalControlAction.LEFT, GlobalControlCommandParser.parse("往左"))
        assertEquals(GlobalControlAction.RIGHT, GlobalControlCommandParser.parse("向右"))
        assertEquals(GlobalControlAction.CONFIRM, GlobalControlCommandParser.parse("确定"))
        assertEquals(GlobalControlAction.BACK, GlobalControlCommandParser.parse("返回"))
        assertEquals(GlobalControlAction.HOME, GlobalControlCommandParser.parse("回桌面"))
    }

    @Test
    fun mapsVolumeAndPlaybackCommands() {
        assertEquals(GlobalControlAction.VOLUME_UP, GlobalControlCommandParser.parse("大声点"))
        assertEquals(GlobalControlAction.VOLUME_DOWN, GlobalControlCommandParser.parse("音量小点"))
        assertEquals(GlobalControlAction.MUTE, GlobalControlCommandParser.parse("静音"))
        assertEquals(GlobalControlAction.PLAY_PAUSE, GlobalControlCommandParser.parse("暂停"))
        assertEquals(GlobalControlAction.NEXT, GlobalControlCommandParser.parse("下一个视频"))
        assertEquals(GlobalControlAction.REWIND, GlobalControlCommandParser.parse("快退"))
    }

    @Test
    fun leavesContentRequestsForTheirOwnParsers() {
        assertNull(GlobalControlCommandParser.parse("帮我播放周杰伦的晴天"))
        assertNull(GlobalControlCommandParser.parse("打开抖音"))
        assertNull(GlobalControlCommandParser.parse("今天天气怎么样"))
    }
}
