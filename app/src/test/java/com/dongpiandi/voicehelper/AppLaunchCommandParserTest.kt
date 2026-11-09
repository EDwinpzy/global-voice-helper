package com.dongpiandi.voicehelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLaunchCommandParserTest {
    @Test
    fun parsesShortAppLaunchCommands() {
        assertEquals("抖音", AppLaunchCommandParser.parse("打开抖音")?.name)
        assertEquals("设置", AppLaunchCommandParser.parse("帮我打开一下设置吧")?.name)
        assertEquals("哔哩哔哩TV", AppLaunchCommandParser.parse("帮我打开哔哩哔哩TV应用")?.name)
        assertEquals("Video Pro", AppLaunchCommandParser.parse("启动 Video Pro")?.name)
    }

    @Test
    fun rejectsQuestionsAndIncompleteCommands() {
        assertNull(AppLaunchCommandParser.parse("打开"))
        assertNull(AppLaunchCommandParser.parse("今天天气怎么样"))
        assertNull(AppLaunchCommandParser.parse("怎么打开设置"))
    }
}
