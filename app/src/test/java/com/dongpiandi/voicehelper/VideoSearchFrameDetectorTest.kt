package com.dongpiandi.voicehelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VideoSearchFrameDetectorTest {
    @Test
    fun detectsSearchResultsAndTargetsFirstCard() {
        val target =
            VideoSearchFrameDetector.detectFirstResult(
                width = 1920,
                height = 1080,
                pixels = syntheticFrame(searchPage = true, firstPoster = true),
            )

        assertNotNull(target)
        assertEquals(180, target?.x)
        assertEquals(583, target?.y)
    }

    @Test
    fun requiresSearchUnderline() {
        assertNull(
            VideoSearchFrameDetector.detectFirstResult(
                width = 1920,
                height = 1080,
                pixels = syntheticFrame(searchPage = false, firstPoster = true),
            ),
        )
    }

    @Test
    fun requiresPopulatedFirstCard() {
        assertNull(
            VideoSearchFrameDetector.detectFirstResult(
                width = 1920,
                height = 1080,
                pixels = syntheticFrame(searchPage = true, firstPoster = false),
            ),
        )
    }

    @Test
    fun ignoresTinyFrames() {
        assertNull(VideoSearchFrameDetector.detectFirstResult(320, 180) { _, _ -> 0 })
    }

    private fun syntheticFrame(
        searchPage: Boolean,
        firstPoster: Boolean,
    ): VideoSearchPixelSource =
        VideoSearchPixelSource { x, y ->
            when {
                searchPage && y == 270 && x in 130..1790 -> 0xFFC0C0C1.toInt()
                firstPoster && x in 80..290 && y in 440..730 -> posterColor(x, y)
                else -> 0xFF2C2C2E.toInt()
            }
        }

    private fun posterColor(
        x: Int,
        y: Int,
    ): Int {
        val red = 60 + ((x / 9) % 160)
        val green = 50 + ((y / 11) % 170)
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or 80
    }
}
