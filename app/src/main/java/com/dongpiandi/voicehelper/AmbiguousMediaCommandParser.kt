package com.dongpiandi.voicehelper

/**
 * Finds a play request whose media type is genuinely ambiguous, such as "播放晴天". Explicit
 * music or video wording is left to the deterministic parsers; only these candidates need the
 * model classifier.
 */
internal object AmbiguousMediaCommandParser {
    private val commandPrefixes =
        listOf(
            "帮我播放",
            "给我播放",
            "我想播放",
            "我要播放",
            "播放一下",
            "帮我放",
            "给我放",
            "播放",
        ).sortedByDescending { it.length }

    private val musicMarkers =
        listOf("音乐", "歌曲", "单曲", "专辑", "首歌", "歌名", "歌单", "听歌")

    private val videoMarkers =
        listOf("电影", "影片", "视频", "电视剧", "动漫", "综艺", "短剧", "片子")

    private val liveMarkers =
        listOf(
            "直播",
            "足球",
            "球赛",
            "比赛",
            "英超",
            "西甲",
            "意甲",
            "德甲",
            "法甲",
            "中超",
            "欧冠",
            "世界杯",
        )

    fun parse(transcript: String): String? {
        val input = transcript.trim().replace(Regex("\\s+"), " ")
        if (input.length < 3) return null
        if (
            musicMarkers.any(input::contains) ||
            videoMarkers.any(input::contains) ||
            liveMarkers.any(input::contains)
        ) {
            return null
        }
        if (commandPrefixes.none(input::startsWith)) return null
        return MusicPlayCommandParser.parse(input, allowImplicitMusic = true)
    }
}
