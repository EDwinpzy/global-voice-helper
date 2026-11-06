package com.dongpiandi.voicehelper

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceIslandMetricsTest {
    @Test
    fun projectorMetricsKeepListeningIslandFixed() {
        val metrics = MistMetrics.forScreen(1920, 1080)

        assertEquals(520f, metrics.capsuleWidth, 0.01f)
        assertEquals(78f, metrics.capsuleHeight, 0.01f)
    }

    @Test
    fun projectorMetricsKeepAnswerCardAtConfirmedSize() {
        val metrics = MistMetrics.forScreen(1920, 1080)

        assertEquals(1040f, metrics.panelWidth, 0.01f)
        assertEquals(210f, metrics.maxPanelHeight, 0.01f)
        assertEquals(14f, metrics.islandGap, 0.01f)
    }

    @Test
    fun smallerDisplaysScaleTheConfirmedGeometryTogether() {
        val metrics = MistMetrics.forScreen(1280, 720)

        assertEquals(346.67f, metrics.capsuleWidth, 0.01f)
        assertEquals(52f, metrics.capsuleHeight, 0.01f)
        assertEquals(693.33f, metrics.panelWidth, 0.01f)
        assertEquals(140f, metrics.maxPanelHeight, 0.01f)
    }
}
