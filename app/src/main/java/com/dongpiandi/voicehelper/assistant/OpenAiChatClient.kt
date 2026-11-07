package com.dongpiandi.voicehelper.assistant

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class OpenAiChatClient(
    private val configProvider: () -> AssistantConfig,
    httpClient: OkHttpClient = OkHttpClient(),
    private val attemptTimeoutsMs: List<Long> = DEFAULT_ATTEMPT_TIMEOUTS_MS,
    private val models: List<String> = DEFAULT_MODELS,
) {
    constructor(
        configStore: AssistantConfigStore,
        httpClient: OkHttpClient = OkHttpClient(),
    ) : this({ configStore.get() }, httpClient, DEFAULT_ATTEMPT_TIMEOUTS_MS)

    private val httpClient =
        httpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    suspend fun complete(
        userPrompt: String,
        history: List<ConversationTurn> = emptyList(),
    ): ChatResult {
        if (userPrompt.isBlank()) {
            return ChatResult(SAFE_EMPTY_PROMPT_MESSAGE, "")
        }

        val config =
            try {
                configProvider()
            } catch (_: AssistantConfigurationException) {
                return ChatResult(SAFE_CONFIGURATION_MESSAGE, "")
            }
        if (!AssistantConfigPolicy.isApiKeyUsable(config.apiKey)) {
            return ChatResult(SAFE_CONFIGURATION_MESSAGE, "")
        }
        val endpoint =
            try {
                ChatEndpointPolicy.responsesUrl(config.baseUrl)
            } catch (_: AssistantConfigurationException) {
                return ChatResult(SAFE_CONFIGURATION_MESSAGE, "")
            }

        for ((index, model) in models.withIndex()) {
            currentCoroutineContext().ensureActive()
            try {
                val timeoutMs =
                    attemptTimeoutsMs.getOrNull(index)
                        ?: attemptTimeoutsMs.lastOrNull()
                        ?: CALL_TIMEOUT_SECONDS * 1_000
                val attempt =
                    withTimeoutOrNull(timeoutMs) {
                        executeAttempt(endpoint, config, model, userPrompt, history)
                    } ?: continue
                return ChatResult(attempt.answer, model, attempt.usedWebSearch)
            } catch (_: AuthenticationRejectedException) {
                return ChatResult(SAFE_AUTH_MESSAGE, "")
            } catch (_: RetryableChatAttemptException) {
                continue
            } catch (_: NonRetryableChatAttemptException) {
                return ChatResult(SAFE_UNAVAILABLE_MESSAGE, "")
            } catch (_: IOException) {
                continue
            } catch (_: JSONException) {
                continue
            }
        }
        return ChatResult(SAFE_UNAVAILABLE_MESSAGE, "")
    }

    /**
     * Asks the configured model whether an ambiguous "播放 X" request means a video or a song.
     * This request deliberately has no web-search tool and a very small output budget.
     */
    suspend fun classifyMediaIntent(userPrompt: String): MediaIntent? {
        val transcript = userPrompt.trim().take(MEDIA_INTENT_MAX_PROMPT_CHARS)
        if (transcript.isEmpty()) return null

        val config =
            try {
                configProvider()
            } catch (_: AssistantConfigurationException) {
                return null
            }
        if (!AssistantConfigPolicy.isApiKeyUsable(config.apiKey)) return null
        val endpoint =
            try {
                ChatEndpointPolicy.responsesUrl(config.baseUrl)
            } catch (_: AssistantConfigurationException) {
                return null
            }

        for ((index, model) in models.withIndex()) {
            currentCoroutineContext().ensureActive()
            try {
                val configuredTimeout =
                    attemptTimeoutsMs.getOrNull(index)
                        ?: attemptTimeoutsMs.lastOrNull()
                        ?: MEDIA_INTENT_TIMEOUT_MS
                val timeoutMs = minOf(configuredTimeout, MEDIA_INTENT_TIMEOUT_MS)
                val attempt =
                    withTimeoutOrNull(timeoutMs) {
                        executeRequest(
                            buildMediaIntentRequest(endpoint, config, model, transcript),
                        )
                    } ?: continue
                return parseMediaIntentAnswer(attempt.answer)
            } catch (_: AuthenticationRejectedException) {
                return null
            } catch (_: RetryableChatAttemptException) {
                continue
            } catch (_: NonRetryableChatAttemptException) {
                return null
            } catch (_: IOException) {
                continue
            } catch (_: JSONException) {
                continue
            }
        }
        return null
    }

    private suspend fun executeAttempt(
        endpoint: String,
        config: AssistantConfig,
        model: String,
        userPrompt: String,
        history: List<ConversationTurn>,
    ): AttemptAnswer =
        executeRequest(buildRequest(endpoint, config, model, userPrompt, history))

    private suspend fun executeRequest(request: Request): AttemptAnswer {
        return suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeFailure(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val answer = response.use(::readAnswer)
                            if (continuation.isActive) continuation.resume(answer)
                        } catch (error: IOException) {
                            continuation.resumeFailure(error)
                        } catch (error: AuthenticationRejectedException) {
                            continuation.resumeFailure(error)
                        } catch (error: RetryableChatAttemptException) {
                            continuation.resumeFailure(error)
                        } catch (error: NonRetryableChatAttemptException) {
                            continuation.resumeFailure(error)
                        } catch (error: RuntimeException) {
                            if (error is kotlinx.coroutines.CancellationException) {
                                throw error
                            }
                            continuation.resumeFailure(RetryableChatAttemptException())
                        }
                    }
                },
            )
        }
    }

    private fun buildRequest(
        endpoint: String,
        config: AssistantConfig,
        model: String,
        userPrompt: String,
        history: List<ConversationTurn>,
    ): Request {
        val payload =
            JSONObject()
                .put("model", model)
                .put("instructions", buildInstructions(config.systemPrompt, history))
                .put("input", buildInput(userPrompt))
                .put("tools", buildWebSearchTools())
                .put("tool_choice", "auto")
                .put("parallel_tool_calls", false)
                .put("max_tool_calls", MAX_SEARCH_CALLS)
                .put("store", false)
                .put("stream", false)
                .put("max_output_tokens", MAX_OUTPUT_TOKENS)
                .toString()
        return Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .apply {
                val apiKey = config.apiKey.trim()
                if (apiKey.isNotEmpty()) {
                    header("Authorization", "Bearer $apiKey")
                }
            }
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun buildMediaIntentRequest(
        endpoint: String,
        config: AssistantConfig,
        model: String,
        transcript: String,
    ): Request {
        val payload =
            JSONObject()
                .put("model", model)
                .put("instructions", MEDIA_INTENT_INSTRUCTIONS)
                .put("input", buildInput("用户原话：$transcript"))
                .put("store", false)
                .put("stream", false)
                .put("max_output_tokens", MEDIA_INTENT_MAX_OUTPUT_TOKENS)
                .toString()
        return Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .apply {
                val apiKey = config.apiKey.trim()
                if (apiKey.isNotEmpty()) {
                    header("Authorization", "Bearer $apiKey")
                }
            }
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun buildInstructions(
        systemPrompt: String?,
        history: List<ConversationTurn>,
    ): String {
        val configuredPrompt = systemPrompt?.trim().orEmpty()
        val base =
            if (configuredPrompt.isEmpty()) {
                DEFAULT_INSTRUCTIONS
            } else {
                "$configuredPrompt\n\n$DEFAULT_INSTRUCTIONS"
            }
        if (history.isEmpty()) return base

        val recent =
            history.joinToString("\n") { turn ->
                "用户：${turn.user}\n助手：${turn.assistant}"
            }
        return "$base\n\n以下是之前的对话，仅用于理解指代和追问，不要重复回答。" +
            "本轮问题在 input 中：\n$recent"
    }

    private fun normalizeAnswer(raw: String): String {
        val normalized =
            raw
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u00A0', ' ')
                .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
                .replace(Regex("(?m)^\\s*[-*•]\\s+"), "")
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .replace(Regex("[ \\t]+\\n"), "\n")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
        val readable = formatReadableAnswer(normalized)
        if (readable.length <= MAX_ANSWER_CHARS) return readable

        val window = readable.take(MAX_ANSWER_CHARS)
        val boundary = window.indexOfLast { it in ANSWER_BOUNDARIES }
        val cut =
            if (boundary >= MIN_TRUNCATE_CHARS) {
                window.substring(0, boundary + 1)
            } else {
                window
            }
        return cut.trimEnd() + "……"
    }

    private fun formatReadableAnswer(text: String): String {
        if (text.isBlank()) return ""

        val expanded =
            text
                .replace(NUMBER_BEFORE_ITEM, "\n")
                .replace(NUMBERED_LINE_PREFIX, "$1. ")
                .replace(Regex("[ \\t]+"), " ")
                .trim()
        val sentences =
            expanded
                .split(SENTENCE_BREAK)
                .map { it.trim() }
                .filter(String::isNotEmpty)

        val lines = mutableListOf<String>()
        var previousWasNumbered = false
        for (sentence in sentences) {
            val numbered = NUMBERED_SENTENCE_PREFIX.containsMatchIn(sentence)
            if (numbered) {
                if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
                lines += sentence
                previousWasNumbered = true
            } else {
                if (previousWasNumbered && lines.isNotEmpty() && lines.last().isNotBlank()) {
                    lines += ""
                }
                lines += sentence
                previousWasNumbered = false
            }
        }
        return lines.joinToString("\n").trim()
    }

    private fun parseAnswerText(raw: String): String {
        return normalizeAnswer(raw)
            .takeIf(String::isNotEmpty)
            ?: throw RetryableChatAttemptException()
    }

    private fun buildInput(userPrompt: String): JSONArray =
        JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put(
                    "content",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "input_text")
                            .put("text", userPrompt),
                    ),
                ),
        )

    private fun buildWebSearchTools(): JSONArray =
        JSONArray().put(
            JSONObject()
                .put("type", "web_search")
                .put("external_web_access", true)
                .put("search_context_size", SEARCH_CONTEXT_SIZE),
        )

    private fun readAnswer(response: Response): AttemptAnswer {
        when {
            response.code == 401 || response.code == 403 ->
                throw AuthenticationRejectedException()
            isFallbackStatus(response.code) -> throw RetryableChatAttemptException()
            !response.isSuccessful -> throw NonRetryableChatAttemptException()
        }
        val body = response.body?.string().orEmpty()
        if (body.isBlank()) throw RetryableChatAttemptException()
        return parseAnswer(body)
    }

    private fun parseAnswer(body: String): AttemptAnswer {
        val root =
            try {
                JSONObject(body)
            } catch (_: JSONException) {
                throw RetryableChatAttemptException()
            }

        val output = root.optJSONArray("output")
        val answer = StringBuilder()
        var usedWebSearch = false
        if (output != null) {
            for (index in 0 until output.length()) {
                val item = output.optJSONObject(index) ?: continue
                when (item.optString("type")) {
                    "web_search_call" -> usedWebSearch = true
                    "message" -> appendOutputContent(answer, item.opt("content"))
                }
            }
        }

        val text = normalizeAnswer(answer.toString())
        if (text.isNotEmpty()) return AttemptAnswer(text, usedWebSearch)

        root.optString("output_text")
            .takeIf(String::isNotBlank)
            ?.let { return AttemptAnswer(parseAnswerText(it), usedWebSearch) }
        throw RetryableChatAttemptException()
    }

    private fun appendOutputContent(
        answer: StringBuilder,
        content: Any?,
    ) {
        when (content) {
            is String -> answer.append(content)
            is JSONObject -> appendOutputPart(answer, content)
            is JSONArray -> {
                for (index in 0 until content.length()) {
                    when (val part = content.opt(index)) {
                        is String -> answer.append(part)
                        is JSONObject -> appendOutputPart(answer, part)
                    }
                }
            }
        }
    }

    private fun appendOutputPart(
        answer: StringBuilder,
        part: JSONObject,
    ) {
        val type = part.optString("type")
        if (type != "output_text" && type != "text") return
        answer.append(part.optString("text"))
    }

    private fun isFallbackStatus(status: Int): Boolean =
        status == 400 ||
            status == 404 ||
            status == 408 ||
            status == 409 ||
            status == 422 ||
            status == 425 ||
            status == 429 ||
            status in 500..599

    private fun CancellableContinuation<AttemptAnswer>.resumeFailure(error: Throwable) {
        if (isActive) resumeWithException(error)
    }

    companion object {
        @JvmField
        val DEFAULT_MODELS: List<String> =
            listOf(
                "workbuddy/deepseek-v4.1-flash",
            )

        const val CONNECT_TIMEOUT_SECONDS = 8L
        const val READ_TIMEOUT_SECONDS = 45L
        const val CALL_TIMEOUT_SECONDS = 50L
        val DEFAULT_ATTEMPT_TIMEOUTS_MS: List<Long> = listOf(30_000L)
        const val MAX_OUTPUT_TOKENS = 4_000
        const val MAX_SEARCH_CALLS = 2
        const val SEARCH_CONTEXT_SIZE = "medium"
        const val MAX_ANSWER_CHARS = 300
        const val MIN_TRUNCATE_CHARS = 120
        const val MEDIA_INTENT_MAX_OUTPUT_TOKENS = 32
        const val MEDIA_INTENT_TIMEOUT_MS = 15_000L
        const val MEDIA_INTENT_MAX_PROMPT_CHARS = 200

        const val SAFE_EMPTY_PROMPT_MESSAGE = "没有识别到要询问的内容。"
        const val SAFE_CONFIGURATION_MESSAGE = "语音助手尚未配置完整。"
        const val SAFE_AUTH_MESSAGE = "语音助手认证失败，请检查接口密钥。"
        const val SAFE_UNAVAILABLE_MESSAGE = "语音助手暂时无法回答，请稍后再试。"

        private val DEFAULT_INSTRUCTIONS =
            """
            你是电视上的语音助手。用户用遥控器和你说话，回答会显示在电视上并被朗读。
            回答要像一个知识面广、说话利落的人：先给结论，再给关键信息，最后给一句判断或建议。

            硬性要求：
            1. 第一句直接回答，不复述问题，不用“当然可以”“这是一个好问题”“总的来说”“简单来说”这类开场。
            2. 每个完整句子单独一行。简单问题 2-4 句，约 60-140 个汉字；需要解释、比较、步骤或背景的问题 4-7 句，约 120-220 个汉字。除非用户要求详细，否则不要超过 220 个汉字。
            3. 每句话都要有信息，至少给出两个具体信息点（事实、数字、名称、时间或原因）；如果问题不需要具体数据，就说明依据。不知道就明确说“我不确定”，不要编造。
            4. 比较类问题必须给出明确推荐和理由，不能只列选项。操作类问题最多 4 步。
            5. 涉及新闻、天气、比赛、价格、版本、日程等可能变化的信息，先调用 web_search；最多搜索两次，不要重复搜索同一内容。回答要基于搜索结果，最后用“来源：名称”标出来源；搜不到就说明。
            6. 使用与用户相同的语言。中文用口语化短句。不要使用 Markdown、表格、代码块、表情符号或长网址。
            7. 推荐多个项目、比较多个选项或说明多个步骤时，必须使用阿拉伯数字“1. ”“2. ”“3. ”编号。每项单独一行且只写一句，项目之间空一行；不要用“第一，”“第二，”或“-”“•”等符号。

            示例：
            用户：这两款投影仪哪个更值得买？
            助手：更推荐第二款。
            它的亮度更高，白天客厅也能看清。
            第一款价格更低，但暗场细节弱一些。
            预算有限再考虑第一款。
            来源：产品参数页。

            用户：推荐三部适合全家看的科幻电影。
            助手：优先推荐《机器人总动员》《星际宝贝》和《怪兽电力公司》。

            1. 《机器人总动员》：几乎没有台词，五岁孩子也能看懂。

            2. 《星际宝贝》：笑点多、冲突温和，片长约 85 分钟。

            3. 《怪兽电力公司》：设定有趣，但几段追逐可能吓到胆小的孩子。

            如果只选一部，选《机器人总动员》。
            """.trimIndent()

        private val MEDIA_INTENT_INSTRUCTIONS =
            """
            你是电视应用的点播类型分类器。判断用户说的“播放/放/我想播放”对象是视频、音乐，还是体育直播。
            只输出一个词：VIDEO、MUSIC、LIVE 或 UNKNOWN。
            歌曲、歌名、歌手、专辑、音乐相关对象输出 MUSIC。
            电影、电视剧、动漫、综艺、短剧、视频相关对象输出 VIDEO。
            足球、球赛、英超、欧冠、世界杯等体育比赛、直播或看球相关对象输出 LIVE。
            看不出对象是哪一种时输出 UNKNOWN。
            不要解释，不要标点，不要输出其他文字。
            """.trimIndent()

        private val ANSWER_BOUNDARIES = charArrayOf('。', '！', '？', '；', '\n')
        private val NUMBER_BEFORE_ITEM =
            Regex("(?<=[。！？；\\s])(?=\\d{1,2}[.、](?!\\d)\\s*\\S)")
        private val NUMBERED_LINE_PREFIX = Regex("(?m)^(\\d{1,2})[.、]\\s*")
        private val NUMBERED_SENTENCE_PREFIX = Regex("^\\d{1,2}\\.\\s+\\S")
        private val SENTENCE_BREAK = Regex("(?<=[。！？])|\\n+")

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private class AuthenticationRejectedException : Exception()

private class RetryableChatAttemptException : Exception()

private class NonRetryableChatAttemptException : Exception()

private data class AttemptAnswer(
    val answer: String,
    val usedWebSearch: Boolean,
)
