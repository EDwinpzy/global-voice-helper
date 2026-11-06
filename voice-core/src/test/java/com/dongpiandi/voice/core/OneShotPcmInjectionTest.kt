package com.dongpiandi.voice.core

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class OneShotPcmInjectionTest {
    @Test
    fun injectedPcmIsConsumedByOnlyOneSession() {
        val injection = OneShotPcmInjection()
        val pcm = byteArrayOf(1, 2, 3)

        injection.replace(pcm)

        assertSame(pcm, injection.consume())
        assertNull(injection.consume())
    }

    @Test
    fun clearingInjectionDisarmsNextSession() {
        val injection = OneShotPcmInjection()
        injection.replace(byteArrayOf(1))

        injection.replace(null)

        assertNull(injection.consume())
    }
}
