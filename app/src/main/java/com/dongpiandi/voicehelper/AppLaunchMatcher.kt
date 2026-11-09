package com.dongpiandi.voicehelper

import java.util.Locale

internal data class LaunchableApp(
    val packageName: String,
    val label: String,
    val componentName: String? = null,
)

/** Matches a spoken app name against the launchable apps reported by PackageManager. */
internal object AppLaunchMatcher {
    private val knownAliases =
        mapOf(
            "com.dongpiandi.douyintv" to
                listOf("抖音", "抖音TV", "抖音电视", "douyin", "douyin tv"),
            "com.dongpiandi.videopro" to
                listOf("影视", "视频", "懂片帝", "董片帝", "video pro", "videopro"),
            "com.qiubo.optimaltv.public" to
                listOf("球迹", "球迹直播", "足球", "足球直播", "看球"),
            "top.izuna.foliamajor" to
                listOf("音乐", "听歌", "folia"),
        )

    fun find(
        apps: List<LaunchableApp>,
        requestedName: String,
    ): LaunchableApp? {
        val query = normalize(requestedName)
        if (query.isEmpty()) return null

        var best: LaunchableApp? = null
        var bestScore = 0
        apps.distinctBy(LaunchableApp::packageName).forEach { app ->
            val label = normalize(app.label)
            val aliases = knownAliases[app.packageName].orEmpty().map(::normalize)
            var score =
                when {
                    label == query -> 1_000
                    aliases.any { it == query } -> 950
                    label.startsWith(query) -> 850
                    aliases.any { it.startsWith(query) } -> 800
                    query.length >= 2 && query in label -> 700
                    query.length >= 2 && aliases.any { query in it } -> 650
                    label.length >= 2 && label in query -> 600
                    aliases.any { it.length >= 2 && it in query } -> 550
                    else -> 0
                }
            if (score == 0) return@forEach
            score -= label.length.coerceAtMost(40)
            if (score > bestScore) {
                best = app
                bestScore = score
            }
        }
        return best
    }

    internal fun normalize(value: String): String =
        value
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("""[\s\p{P}\p{S}]+"""), "")
}
