package com.dongpiandi.voicehelper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDictationKeyboardModeLatchTest {
    @Test
    fun oneSessionAppliesAndRestoresKeyboardModeOnce() {
        val latch = VoiceDictationKeyboardModeLatch()

        assertTrue(latch.enter())
        assertTrue(latch.active)
        assertFalse(latch.enter())
        assertTrue(latch.exit())
        assertFalse(latch.active)
        assertFalse(latch.exit())
    }

    @Test
    fun newDictationCanHideKeyboardAfterPreviousSessionEnds() {
        val latch = VoiceDictationKeyboardModeLatch()

        assertTrue(latch.enter())
        assertTrue(latch.exit())
        assertTrue(latch.enter())
        assertTrue(latch.active)
    }
}
