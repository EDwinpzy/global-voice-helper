package com.dongpiandi.voicehelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPlayCommandParserTest {
    @Test
    fun extractsSongFromExplicitMusicRequest() {
        assertEquals("晴天", MusicPlayCommandParser.parse("帮我播放晴天音乐"))
        assertEquals("晴天", MusicPlayCommandParser.parse("播放《晴天》这首歌"))
        assertEquals("晴天", MusicPlayCommandParser.parse("我想听晴天"))
        assertEquals("周杰伦的晴天", MusicPlayCommandParser.parse("我想听周杰伦的晴天"))
        assertEquals(
            "晴天",
            MusicPlayCommandParser.parse("帮我播放晴天", allowImplicitMusic = true),
        )
    }

    @Test
    fun removesMusicWordingAndPoliteSuffixes() {
        assertEquals("晴天", MusicPlayCommandParser.parse("帮我播放一首歌曲，叫晴天"))
        assertEquals("晴天", MusicPlayCommandParser.parse("来一首晴天吧"))
        assertEquals("晴天", MusicPlayCommandParser.parse("放首晴天，谢谢"))
    }

    @Test
    fun leavesAmbiguousVideoRequestsForTheVideoParser() {
        assertNull(MusicPlayCommandParser.parse("播放流浪地球"))
        assertNull(MusicPlayCommandParser.parse("我要看流浪地球"))
        assertNull(MusicPlayCommandParser.parse("播放音乐"))
        assertNull(MusicPlayCommandParser.parse("我想听歌"))
        assertNull(MusicPlayCommandParser.parse("帮我播放晴天"))
    }

    @Test
    fun artistTitleFormUsesImplicitMusicWithoutStealingVideoNouns() {
        assertTrue(MusicPlayCommandParser.looksLikeImplicitArtistTitle("帮我播放周杰伦的晴天"))
        assertFalse(MusicPlayCommandParser.looksLikeImplicitArtistTitle("帮我播放凡人修仙传动漫"))
        assertEquals(
            "周杰伦的晴天",
            MusicPlayCommandParser.parse(
                "帮我播放周杰伦的晴天",
                allowImplicitMusic = true,
            ),
        )
        assertNull(
            MusicPlayCommandParser.parse(
                "帮我播放凡人修仙传动漫",
                allowImplicitMusic = true,
            ),
        )
    }
}
