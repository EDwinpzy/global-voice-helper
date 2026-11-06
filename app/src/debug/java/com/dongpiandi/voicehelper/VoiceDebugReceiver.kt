package com.dongpiandi.voicehelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dongpiandi.voice.core.SystemDictationRecognizerFactory
import com.dongpiandi.voicehelper.assistant.AssistantConfigStore
import com.dongpiandi.voicehelper.benchmark.ModelBenchmarkPolicy
import com.dongpiandi.voicehelper.benchmark.ModelBenchmarkRunner

/** Debug-only QA entry points; this class is excluded from release builds. */
class VoiceDebugReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            ACTION_SUBMIT_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                val preserveHistory = intent.getBooleanExtra(EXTRA_KEEP_HISTORY, false)
                VoiceGatewayService.submitTextForTest(text, preserveHistory)
            }
            ACTION_SHOW_DEMO ->
                VoiceGatewayService.showDemoForTest(
                    question =
                        intent.getStringExtra(EXTRA_QUESTION)
                            ?: DEFAULT_QUESTION,
                    answer =
                        intent.getStringExtra(EXTRA_ANSWER)
                            ?: DEFAULT_ANSWER,
                )
            ACTION_SHOW_THINKING ->
                VoiceGatewayService.showThinkingForTest(
                    intent.getStringExtra(EXTRA_QUESTION)
                        ?: DEFAULT_QUESTION,
                )
            ACTION_CANCEL -> VoiceGatewayService.cancelAssistantForTest()
            ACTION_CONFIGURE ->
                runCatching {
                    AssistantConfigStore(context).save(
                        baseUrl = intent.getStringExtra(EXTRA_BASE_URL).orEmpty(),
                        apiKey = intent.getStringExtra(EXTRA_API_KEY).orEmpty(),
                    )
                }
            ACTION_PRESS_VOICE -> {
                val down = intent.getBooleanExtra(EXTRA_DOWN, false)
                VoiceGatewayService.pressVoiceKeyForTest(down)
            }
            ACTION_INJECT_PCM ->
                Thread(
                    {
                        runCatching {
                            val clear = intent.getBooleanExtra(EXTRA_CLEAR_PCM, false)
                            val pcm =
                                if (clear) {
                                    null
                                } else {
                                    context.assets
                                        .open(QA_PCM_ASSET)
                                        .use { it.readBytes() }
                                }
                            val recognizer = SystemDictationRecognizerFactory.create(context)
                            recognizer.javaClass
                                .getMethod("useInjectedPcmForTest", ByteArray::class.java)
                                .invoke(recognizer, pcm)
                            Log.i(TAG, "qa pcm ${if (pcm == null) "cleared" else "injected bytes=${pcm.size}"}")
                        }.onFailure { error ->
                            Log.w(TAG, "qa pcm injection failed: ${error.javaClass.simpleName}")
                        }
                    },
                    "voice-helper-qa-pcm",
                ).start()
            ACTION_BENCHMARK_MODELS -> {
                ModelBenchmarkRunner.start(
                    context = context,
                    rounds = intent.getIntExtra(EXTRA_ROUNDS, ModelBenchmarkPolicy.DEFAULT_ROUNDS),
                    prompt = intent.getStringExtra(EXTRA_PROMPT).orEmpty(),
                )
            }
        }
    }

    companion object {
        const val ACTION_SUBMIT_TEXT = "com.dongpiandi.voicehelper.DEBUG_SUBMIT_TEXT"
        const val ACTION_SHOW_DEMO = "com.dongpiandi.voicehelper.DEBUG_SHOW_DEMO"
        const val ACTION_SHOW_THINKING = "com.dongpiandi.voicehelper.DEBUG_SHOW_THINKING"
        const val ACTION_CANCEL = "com.dongpiandi.voicehelper.DEBUG_CANCEL"
        const val ACTION_CONFIGURE = "com.dongpiandi.voicehelper.DEBUG_CONFIGURE"
        const val ACTION_PRESS_VOICE = "com.dongpiandi.voicehelper.DEBUG_PRESS_VOICE"
        const val ACTION_INJECT_PCM = "com.dongpiandi.voicehelper.DEBUG_INJECT_PCM"
        const val ACTION_BENCHMARK_MODELS = "com.dongpiandi.voicehelper.DEBUG_BENCHMARK_MODELS"
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_TEXT = "text"
        const val EXTRA_KEEP_HISTORY = "keep_history"
        const val EXTRA_QUESTION = "question"
        const val EXTRA_ANSWER = "answer"
        const val EXTRA_DOWN = "down"
        const val EXTRA_CLEAR_PCM = "clear_pcm"
        const val EXTRA_ROUNDS = "rounds"
        const val EXTRA_PROMPT = "prompt"
        private const val TAG = "VoiceHelperDebug"
        private const val QA_PCM_ASSET = "dictation_zh_16k.pcm"
        private const val DEFAULT_QUESTION = "把客厅调到阅读光，再把卧室调暗。"
        private const val DEFAULT_ANSWER = "好，客厅已经调成阅读光，卧室也调暗了。"
    }
}
