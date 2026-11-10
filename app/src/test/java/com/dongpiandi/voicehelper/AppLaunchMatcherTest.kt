package com.dongpiandi.voicehelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLaunchMatcherTest {
    private val apps =
        listOf(
            LaunchableApp("com.dongpiandi.douyintv", "抖音TV"),
            LaunchableApp("com.dongpiandi.videopro", "Video Pro"),
            LaunchableApp("com.qiubo.optimaltv.public", "球迹直播"),
            LaunchableApp("top.izuna.foliamajor", "Folia"),
            LaunchableApp("com.android.settings", "设置"),
        )

    @Test
    fun resolvesLabelsAndKnownAliases() {
        assertEquals(
            "com.dongpiandi.douyintv",
            AppLaunchMatcher.find(apps, "抖音")?.packageName,
        )
        assertEquals(
            "com.dongpiandi.videopro",
            AppLaunchMatcher.find(apps, "影视")?.packageName,
        )
        assertEquals(
            "com.qiubo.optimaltv.public",
            AppLaunchMatcher.find(apps, "球迹直播")?.packageName,
        )
        assertEquals(
            "com.android.settings",
            AppLaunchMatcher.find(apps, "设置")?.packageName,
        )
    }

    @Test
    fun ignoresUnknownNames() {
        assertNull(AppLaunchMatcher.find(apps, "不存在的应用"))
    }
}
