package com.dongpiandi.voice.core

/** Keeps a QA PCM sample armed for exactly one recognition session. */
internal class OneShotPcmInjection {
    private var pcm: ByteArray? = null

    @Synchronized
    fun replace(value: ByteArray?) {
        pcm = value
    }

    @Synchronized
    fun consume(): ByteArray? =
        pcm.also {
            pcm = null
        }
}
