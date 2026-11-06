package com.dongpiandi.voicehelper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import com.dongpiandi.voice.core.SystemDictationRecognizer
import com.dongpiandi.voice.core.SystemDictationRecognizerFactory
import com.dongpiandi.voice.core.SystemDictationSession
import com.dongpiandi.voice.core.VendorAssistantSuppressor

/**
 * Minimal lifecycle surface for the helper-owned dictation island. A started session receives
 * exactly one terminal callback: [onFinished] after a successful commit, or [onDismissed] for a
 * cancel, recognition error, timeout, or editor loss.
 */
internal interface VoiceDictationCallbacks {
    fun onListeningStarted()

    fun onPartial(text: String)

    fun onFinished(text: String)

    fun onDismissed()
}

internal object NoOpVoiceDictationCallbacks : VoiceDictationCallbacks {
    override fun onListeningStarted() = Unit

    override fun onPartial(text: String) = Unit

    override fun onFinished(text: String) = Unit

    override fun onDismissed() = Unit
}

/** Keeps UI callbacks ordered and prevents duplicate exits for one recognition session. */
internal class VoiceDictationCallbackDispatcher(
    private val callbacks: VoiceDictationCallbacks,
) {
    private var visible = false

    fun started() {
        if (visible) return
        visible = true
        notify(callbacks::onListeningStarted)
    }

    fun partial(text: String) {
        if (!visible || text.isBlank()) return
        notify { callbacks.onPartial(text) }
    }

    fun finished(text: String) {
        if (!visible) return
        visible = false
        notify { callbacks.onFinished(text) }
    }

    fun dismissed() {
        if (!visible) return
        visible = false
        notify(callbacks::onDismissed)
    }

    private fun notify(action: () -> Unit) {
        runCatching(action)
            .onFailure { error ->
                Log.w(TAG, "dictation callback failed: ${error.javaClass.simpleName}")
            }
    }

    private companion object {
        const val TAG = "VoiceHelperDictation"
    }
}

/**
 * One remote press, one vendor ASR result, and one still-active editor. This path never invokes
 * the LLM, assistant UI, or TTS.
 */
internal class VoiceDictationController(
    private val context: Context,
    private val editorSession: () -> Long?,
    private val commit: (editor: Long, text: String) -> Boolean,
    private val recognizerFactory: () -> SystemDictationRecognizer = {
        SystemDictationRecognizerFactory.create(context)
    },
    private val beginMicrophoneForeground: () -> Boolean = { true },
    private val endMicrophoneForeground: () -> Unit = {},
    callbacks: VoiceDictationCallbacks = NoOpVoiceDictationCallbacks,
) {
    private val main = Handler(Looper.getMainLooper())
    private val vendorAssistant = VendorAssistantSuppressor(context)
    private val session = SystemDictationSession()
    private val callbackDispatcher = VoiceDictationCallbackDispatcher(callbacks)
    private var recognizer: SystemDictationRecognizer? = null
    private var pendingResult: String? = null
    private var resultReady = false
    private var ownsPress = false

    private val timeout =
        Runnable {
            if (session.state == SystemDictationSession.State.IDLE) return@Runnable
            fail(R.string.dictation_timeout)
        }

    val active: Boolean
        get() = session.state != SystemDictationSession.State.IDLE

    fun onEditorChanged() {
        if (active) cancel()
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isVoiceKey(event)) return false

        if (ownsPress) {
            when (event.action) {
                KeyEvent.ACTION_UP -> {
                    ownsPress = false
                    vendorAssistant.finish()
                    if (event.isCanceled) cancel() else release()
                }
                KeyEvent.ACTION_DOWN -> Unit
                else -> return false
            }
            return true
        }

        val editor = editorSession() ?: return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        ownsPress = true
        if (event.repeatCount != 0 || active) return true
        vendorAssistant.begin()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            fail(R.string.dictation_permission_required)
            return true
        }
        if (!runCatching(beginMicrophoneForeground).getOrDefault(false)) {
            fail(R.string.dictation_failed)
            return true
        }

        val token = session.begin(editor) ?: return true
        pendingResult = null
        resultReady = false
        try {
            val engine = recognizerFactory()
            recognizer = engine
            engine.start(
                object : SystemDictationRecognizer.Listener {
                    override fun onPartial(text: String) {
                        main.post {
                            if (token != session.generation) return@post
                            session.observe(token, text)
                            callbackDispatcher.partial(text.trim())
                        }
                    }

                    override fun onResult(text: String) {
                        main.post {
                            if (token != session.generation) return@post
                            session.observe(token, text)
                            callbackDispatcher.partial(text.trim())
                            pendingResult = text
                            resultReady = true
                            if (session.state == SystemDictationSession.State.WAITING_RESULT) {
                                finish(token)
                            }
                        }
                    }

                    override fun onError() {
                        main.post {
                            if (token == session.generation) fail(R.string.dictation_failed)
                        }
                    }
                },
            )
            callbackDispatcher.started()
            main.postDelayed(timeout, MAX_RECORDING_MS)
        } catch (error: Throwable) {
            Log.w(TAG, "recognizer start failed: ${error.javaClass.simpleName}")
            fail(R.string.dictation_engine_unavailable)
        }
        return true
    }

    fun cancel() {
        ownsPress = false
        callbackDispatcher.dismissed()
        if (session.state == SystemDictationSession.State.IDLE && recognizer == null) {
            vendorAssistant.finish()
            return
        }
        session.cancel()
        vendorAssistant.finish()
        cleanup()
    }

    fun close() {
        callbackDispatcher.dismissed()
        session.cancel()
        cleanup()
        vendorAssistant.close()
        main.removeCallbacksAndMessages(null)
    }

    private fun release() {
        if (!session.release()) {
            callbackDispatcher.dismissed()
            cleanup()
            return
        }
        main.removeCallbacks(timeout)
        val engine =
            recognizer
                ?: run {
                    fail(R.string.dictation_failed)
                    return
                }
        try {
            engine.stop()
        } catch (error: Throwable) {
            Log.w(TAG, "recognizer stop failed: ${error.javaClass.simpleName}")
            fail(R.string.dictation_failed)
            return
        }
        if (resultReady) {
            finish(session.generation)
            return
        }
        main.postDelayed(timeout, MAX_RESULT_WAIT_MS)
    }

    private fun finish(token: Int) {
        val editor = editorSession()
        val text = session.finish(token, pendingResult, editor)
        if (text == null) {
            cleanup()
            callbackDispatcher.dismissed()
            return
        }
        cleanup()
        if (editor != null && commit(editor, text)) {
            callbackDispatcher.finished(text)
        } else {
            callbackDispatcher.dismissed()
            Toast.makeText(context, R.string.dictation_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun fail(messageRes: Int) {
        cancel()
        Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun cleanup() {
        main.removeCallbacks(timeout)
        runCatching { recognizer?.cancel() }
        recognizer = null
        pendingResult = null
        resultReady = false
        runCatching(endMicrophoneForeground)
    }

    private fun isVoiceKey(event: KeyEvent): Boolean =
        event.keyCode == VENDOR_VOICE_KEY ||
            event.scanCode == VENDOR_VOICE_SCAN_CODE ||
            event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST ||
            event.keyCode == KeyEvent.KEYCODE_ASSIST

    private companion object {
        const val TAG = "VoiceHelperDictation"
        const val VENDOR_VOICE_KEY = 609
        const val VENDOR_VOICE_SCAN_CODE = 192
        const val MAX_RECORDING_MS = 30_000L
        const val MAX_RESULT_WAIT_MS = 8_000L
    }
}
