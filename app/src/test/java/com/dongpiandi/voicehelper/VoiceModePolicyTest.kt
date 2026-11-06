package com.dongpiandi.voicehelper

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceModePolicyTest {
    @Test
    fun focusedEditorUsesDictation() {
        assertEquals(
            VoiceMode.DICTATION,
            VoiceModePolicy.resolve(
                editorAttached = true,
                inputMethodVisible = true,
            ),
        )
    }

    @Test
    fun noEditorUsesCustomAssistant() {
        assertEquals(
            VoiceMode.ASSISTANT,
            VoiceModePolicy.resolve(
                editorAttached = false,
                inputMethodVisible = false,
            ),
        )
    }

    @Test
    fun staleEditorDoesNotSwallowAssistantKeyWhenInputMethodIsHidden() {
        assertEquals(
            VoiceMode.ASSISTANT,
            VoiceModePolicy.resolve(
                editorAttached = true,
                inputMethodVisible = false,
            ),
        )
    }
}
