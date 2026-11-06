package com.dongpiandi.voicehelper

internal enum class VoiceMode {
    DICTATION,
    ASSISTANT,
}

internal object VoiceModePolicy {
    fun resolve(
        editorAttached: Boolean,
        inputMethodVisible: Boolean,
    ): VoiceMode =
        if (editorAttached && inputMethodVisible) {
            VoiceMode.DICTATION
        } else {
            VoiceMode.ASSISTANT
        }
}
