package com.dongpiandi.voicehelper.assistant

enum class MediaIntent {
    VIDEO,
    MUSIC,
    LIVE,
}

/** Accepts the single classifier label while tolerating quotes or a short explanatory trace. */
internal fun parseMediaIntentAnswer(raw: String): MediaIntent? {
    val upper = raw.uppercase()
    val label =
        Regex("""\b(MUSIC|VIDEO|LIVE|UNKNOWN)\b""")
            .find(upper)
            ?.groupValues
            ?.getOrNull(1)
    return when (label) {
        "MUSIC" -> MediaIntent.MUSIC
        "VIDEO" -> MediaIntent.VIDEO
        "LIVE" -> MediaIntent.LIVE
        "UNKNOWN" -> null
        else ->
            when {
                raw.contains("音乐") -> MediaIntent.MUSIC
                raw.contains("电影") || raw.contains("视频") -> MediaIntent.VIDEO
                raw.contains("直播") || raw.contains("足球") || raw.contains("球赛") -> MediaIntent.LIVE
                else -> null
            }
    }
}
