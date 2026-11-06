package com.dongpiandi.voicehelper.benchmark

import android.content.Context
import android.util.Log
import com.dongpiandi.voicehelper.assistant.AssistantConfigPolicy
import com.dongpiandi.voicehelper.assistant.AssistantConfigStore
import com.dongpiandi.voicehelper.assistant.ChatEndpointPolicy
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException

/** Debug-only serial benchmark runner using the app's encrypted assistant configuration. */
internal object ModelBenchmarkRunner {
    private const val TAG = "VoiceModelBenchmark"
    private const val CONNECT_TIMEOUT_SECONDS = 5L
    private const val REQUEST_TIMEOUT_SECONDS = 30L

    private val running = AtomicBoolean(false)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val httpClient =
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

    fun start(
        context: Context,
        rounds: Int,
        prompt: String,
    ): Boolean {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "benchmark rejected: already running")
            return false
        }
        Thread(
            {
                try {
                    run(context.applicationContext, rounds, prompt)
                } finally {
                    running.set(false)
                }
            },
            "voice-model-benchmark",
        ).start()
        return true
    }

    private fun run(
        context: Context,
        requestedRounds: Int,
        prompt: String,
    ) {
        val rounds = ModelBenchmarkPolicy.normalizedRounds(requestedRounds)
        val config =
            try {
                AssistantConfigStore(context).get()
            } catch (_: Exception) {
                Log.w(TAG, "benchmark aborted: configuration unavailable")
                return
            }
        if (!AssistantConfigPolicy.isApiKeyUsable(config.apiKey)) {
            Log.w(TAG, "benchmark aborted: api key invalid")
            return
        }
        val endpoint =
            try {
                ChatEndpointPolicy.responsesUrl(config.baseUrl)
            } catch (_: Exception) {
                Log.w(TAG, "benchmark aborted: endpoint invalid")
                return
            }

        Log.i(
            TAG,
            "started models=${ModelBenchmarkPolicy.MODELS.size} rounds=$rounds",
        )
        val headers = linkedMapOf<String, MutableList<Long>>()
        val totals = linkedMapOf<String, MutableList<Long>>()
        val failures = linkedMapOf<String, Int>()

        loop@ for (round in 0 until rounds) {
            val roundPrompt = ModelBenchmarkPolicy.promptForRound(prompt, round)
            val orderedModels = ModelBenchmarkPolicy.orderForRound(ModelBenchmarkPolicy.MODELS, round)
            for ((position, model) in orderedModels.withIndex()) {
                val result =
                    execute(
                        endpoint = endpoint,
                        apiKey = config.apiKey,
                        systemPrompt = config.systemPrompt,
                        model = model,
                        prompt = roundPrompt,
                    )
                if (result.fatal) {
                    Log.w(TAG, "benchmark aborted model=$model round=${round + 1} status=${result.status}")
                    break@loop
                }
                if (!result.success) {
                    failures[model] = failures.getOrDefault(model, 0) + 1
                    Log.w(
                        TAG,
                        "round=${round + 1} position=${position + 1} model=$model " +
                            "result=${result.failure} headersMs=${result.headersMs} " +
                            "totalMs=${result.totalMs}",
                    )
                    continue
                }
                headers.getOrPut(model, ::mutableListOf).add(result.headersMs)
                totals.getOrPut(model, ::mutableListOf).add(result.totalMs)
                Log.i(
                    TAG,
                    "round=${round + 1} position=${position + 1} model=$model result=ok " +
                        "headersMs=${result.headersMs} totalMs=${result.totalMs} " +
                        "chars=${result.characters} webSearch=${result.usedWebSearch}",
                )
            }
        }

        for (model in ModelBenchmarkPolicy.MODELS) {
            val modelHeaders = headers[model].orEmpty()
            val modelTotals = totals[model].orEmpty()
            Log.i(
                TAG,
                "summary model=$model successes=${modelTotals.size} " +
                    "failures=${failures.getOrDefault(model, 0)} " +
                    "medianHeadersMs=${ModelBenchmarkPolicy.median(modelHeaders) ?: -1} " +
                    "medianTotalMs=${ModelBenchmarkPolicy.median(modelTotals) ?: -1} " +
                    "minTotalMs=${modelTotals.minOrNull() ?: -1} " +
                    "maxTotalMs=${modelTotals.maxOrNull() ?: -1}",
            )
        }
        Log.i(TAG, "finished")
    }

    private fun execute(
        endpoint: String,
        apiKey: String,
        systemPrompt: String?,
        model: String,
        prompt: String,
    ): AttemptResult {
        val payload =
            ModelBenchmarkPolicy.buildRequestJson(
                model = model,
                prompt = prompt,
                systemPrompt = systemPrompt,
            )
        val request =
            Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .apply {
                    val key = apiKey.trim()
                    if (key.isNotEmpty()) header("Authorization", "Bearer $key")
                }
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
        val call = httpClient.newCall(request)
        val startedAt = System.nanoTime()
        return try {
            call.execute().use { response ->
                val headersMs = elapsedMs(startedAt)
                val status = response.code
                val body = response.body?.string().orEmpty()
                val totalMs = elapsedMs(startedAt)
                when {
                    status == 401 || status == 403 ->
                        AttemptResult(
                            fatal = true,
                            status = status,
                            failure = "auth_rejected",
                            headersMs = headersMs,
                            totalMs = totalMs,
                        )
                    status !in 200..299 ->
                        AttemptResult(
                            status = status,
                            failure = "http_$status",
                            headersMs = headersMs,
                            totalMs = totalMs,
                        )
                    body.isBlank() ->
                        AttemptResult(
                            status = status,
                            failure = "empty_body",
                            headersMs = headersMs,
                            totalMs = totalMs,
                        )
                    else -> {
                        val answer =
                            try {
                                ModelBenchmarkPolicy.parseAnswer(body)
                            } catch (_: JSONException) {
                                null
                            }
                        if (answer == null) {
                            AttemptResult(
                                status = status,
                                failure = "invalid_response",
                                headersMs = headersMs,
                                totalMs = totalMs,
                            )
                        } else {
                            AttemptResult(
                                success = true,
                                status = status,
                                headersMs = headersMs,
                                totalMs = totalMs,
                                characters = answer.characters,
                                usedWebSearch = answer.usedWebSearch,
                            )
                        }
                    }
                }
            }
        } catch (error: IOException) {
            val totalMs = elapsedMs(startedAt)
            AttemptResult(
                failure = "io_${error.javaClass.simpleName}",
                totalMs = totalMs,
            )
        } catch (_: RuntimeException) {
            val totalMs = elapsedMs(startedAt)
            AttemptResult(
                failure = "runtime_error",
                totalMs = totalMs,
            )
        }
    }

    private fun elapsedMs(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private data class AttemptResult(
        val success: Boolean = false,
        val fatal: Boolean = false,
        val status: Int = 0,
        val failure: String = "",
        val headersMs: Long = -1,
        val totalMs: Long = -1,
        val characters: Int = 0,
        val usedWebSearch: Boolean = false,
    )

}
