package com.dongpiandi.voicehelper

import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAssistantAnswerMetricsTest {
    @Test
    fun answerCardCanGrowBeyondTheConfirmedMinimumHeight() {
        val metrics = MistMetrics.forScreen(1920, 1080)

        assertTrue(metrics.maxAnswerHeight > metrics.maxPanelHeight)
        assertTrue(
            metrics.maxAnswerHeight <=
                metrics.screenHeight - metrics.panelBottom - metrics.windowTopPadding,
        )
    }
}
