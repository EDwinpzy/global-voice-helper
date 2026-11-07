package com.dongpiandi.voicehelper.assistant

import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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
    private val timeoutFallbackModel: String = TIMEOUT_FALLBACK_MODEL,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val zoneIdProvider: () -> ZoneId = ZoneId::systemDefault,
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

    private val timeoutFallbackModels =
        if (timeoutFallbackModel.isBlank()) emptyList() else listOf(timeoutFallbackModel)
    private val candidateModels = models + timeoutFallbackModels
    private val timeoutFallbackIndex = models.size

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

        var timedOut = false
        for ((index, model) in candidateModels.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (index >= timeoutFallbackIndex && !timedOut) continue
            try {
                val timeoutMs =
                    attemptTimeoutsMs.getOrNull(index)
                        ?: attemptTimeoutsMs.lastOrNull()
                        ?: CALL_TIMEOUT_SECONDS * 1_000
                val attemptNow = nowProvider()
                val attemptZone = zoneIdProvider()
                val attempt =
                    withTimeoutOrNull(timeoutMs) {
                        executeAttempt(
                            endpoint = endpoint,
                            config = config,
                            model = model,
                            userPrompt = userPrompt,
                            history = history,
                            nowEpochMs = attemptNow,
                            zone = attemptZone,
                        )
                    }
                if (attempt == null) {
                    timedOut = true
                    continue
                }
                val action =
                    AssistantActionValidator.validateAt(
                        action = attempt.action,
                        nowEpochMs = attemptNow,
                    )
                if (attempt.actionRequested && action == null) {
                    throw RetryableChatAttemptException()
                }
                return ChatResult(attempt.answer, model, attempt.usedWebSearch, action)
            } catch (_: AuthenticationRejectedException) {
                return ChatResult(SAFE_AUTH_MESSAGE, "")
            } catch (_: TimeoutChatAttemptException) {
                timedOut = true
            } catch (_: RetryableChatAttemptException) {
                continue
            } catch (_: NonRetryableChatAttemptException) {
                return ChatResult(SAFE_UNAVAILABLE_MESSAGE, "")
            } catch (error: IOException) {
                if (isTimeoutFailure(error)) timedOut = true
                continue
            } catch (_: JSONException) {
                continue
            }
        }
        return ChatResult(SAFE_UNAVAILABLE_MESSAGE, "")
    }

    private suspend fun executeAttempt(
        endpoint: String,
        config: AssistantConfig,
        model: String,
        userPrompt: String,
        history: List<ConversationTurn>,
        nowEpochMs: Long,
        zone: ZoneId,
    ): AttemptAnswer =
        executeRequest(
            buildRequest(
                endpoint = endpoint,
                config = config,
                model = model,
                userPrompt = userPrompt,
                history = history,
                nowEpochMs = nowEpochMs,
                zone = zone,
            ),
            zone = zone,
        )

    private suspend fun executeRequest(
        request: Request,
        zone: ZoneId? = null,
    ): AttemptAnswer {
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
                            val answer = response.use { readAnswer(it, zone) }
                            if (continuation.isActive) continuation.resume(answer)
                        } catch (error: IOException) {
                            continuation.resumeFailure(error)
                        } catch (error: AuthenticationRejectedException) {
                            continuation.resumeFailure(error)
                        } catch (error: TimeoutChatAttemptException) {
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
        nowEpochMs: Long,
        zone: ZoneId,
    ): Request {
        val payload =
            JSONObject()
                .put("model", model)
                .put(
                    "instructions",
                    buildInstructions(
                        systemPrompt = config.systemPrompt,
                        history = history,
                        nowEpochMs = nowEpochMs,
                        zone = zone,
                    ),
                )
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

    private fun buildInstructions(
        systemPrompt: String?,
        history: List<ConversationTurn>,
        nowEpochMs: Long,
        zone: ZoneId,
    ): String {
        val configuredPrompt = systemPrompt?.trim().orEmpty()
        val base =
            if (configuredPrompt.isEmpty()) {
                DEFAULT_INSTRUCTIONS
            } else {
                "$configuredPrompt\n\n$DEFAULT_INSTRUCTIONS"
            }
        val currentTime = Instant.ofEpochMilli(nowEpochMs).atZone(zone)
        val readableTime =
            currentTime.format(
                DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm", Locale.CHINA),
            )
        val isoTime = currentTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val currentTimeBlock =
            """
            当前时间：$readableTime
            当前时间的 RFC 3339 表示：$isoTime
            系统时区：${zone.id}（UTC${currentTime.offset.id}）
            """.trimIndent()
        val withContext = "$currentTimeBlock\n\n$base\n\n$ACTION_INSTRUCTIONS"
        if (history.isEmpty()) return withContext

        val recent =
            history.joinToString("\n") { turn ->
                "用户：${turn.user}\n助手：${turn.assistant}"
            }
        return "$withContext\n\n以下是之前的对话，仅用于理解指代和追问，不要重复回答。" +
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

    private fun parseAnswerText(
        raw: String,
        usedWebSearch: Boolean,
        zone: ZoneId?,
    ): AttemptAnswer {
        val envelope = zone?.let { AssistantActionJson.parseEnvelope(raw, it) }
        if (envelope != null) {
            val normalized = normalizeAnswer(envelope.answer)
            if (normalized.isEmpty()) throw RetryableChatAttemptException()
            return AttemptAnswer(
                answer = normalized,
                usedWebSearch = usedWebSearch,
                actionRequested = envelope.actionRequested,
                action = envelope.action,
            )
        }

        val normalized =
            normalizeAnswer(raw)
                .takeIf(String::isNotEmpty)
                ?: throw RetryableChatAttemptException()
        return AttemptAnswer(
            answer = normalized,
            usedWebSearch = usedWebSearch,
            actionRequested = false,
            action = null,
        )
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

    private fun readAnswer(
        response: Response,
        zone: ZoneId?,
    ): AttemptAnswer {
        when {
            response.code == 401 || response.code == 403 ->
                throw AuthenticationRejectedException()
            response.code == 408 || response.code == 504 ->
                throw TimeoutChatAttemptException()
            isFallbackStatus(response.code) -> throw RetryableChatAttemptException()
            !response.isSuccessful -> throw NonRetryableChatAttemptException()
        }
        val body = response.body?.string().orEmpty()
        if (body.isBlank()) throw RetryableChatAttemptException()
        return parseAnswer(body, zone)
    }

    private fun parseAnswer(
        body: String,
        zone: ZoneId?,
    ): AttemptAnswer {
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

        val rawText =
            answer.toString().ifBlank {
                root.optString("output_text")
            }
        if (rawText.isNotBlank()) {
            return parseAnswerText(rawText, usedWebSearch, zone)
        }

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

    private fun isTimeoutFailure(error: IOException): Boolean =
        error is SocketTimeoutException ||
            error.javaClass.simpleName.contains("Timeout", ignoreCase = true) ||
            error.message?.contains("timeout", ignoreCase = true) == true

    private fun CancellableContinuation<AttemptAnswer>.resumeFailure(error: Throwable) {
        if (isActive) resumeWithException(error)
    }

    companion object {
        @JvmField
        val DEFAULT_MODELS: List<String> =
            listOf(
                "workbuddy/deepseek-v4.1-flash",
            )

        const val TIMEOUT_FALLBACK_MODEL = "group/free"
        const val CONNECT_TIMEOUT_SECONDS = 8L
        const val READ_TIMEOUT_SECONDS = 45L
        const val CALL_TIMEOUT_SECONDS = 50L
        val DEFAULT_ATTEMPT_TIMEOUTS_MS: List<Long> = listOf(30_000L, 30_000L)
        const val MAX_OUTPUT_TOKENS = 4_000
        const val MAX_SEARCH_CALLS = 2
        const val SEARCH_CONTEXT_SIZE = "medium"
        const val MAX_ANSWER_CHARS = 300
        const val MIN_TRUNCATE_CHARS = 120

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

        private val ACTION_INSTRUCTIONS =
            """
            你可以根据用户意图选择一个设备动作，但只能用下面列出的动作。普通问答仍然直接返回纯文本，不要返回 JSON。
            只有用户确实要你现在操作设备，或要你在未来某个确定时刻提醒、询问或执行时，才只输出一个 JSON 对象，不要加代码块或解释。

            JSON 外层格式：
            {"answer":"给用户看和朗读的简短回答","action":{"type":"动作类型","其他字段":"..."}}

            动作类型和字段：
            1. OPEN_LIVE_FOOTBALL：现在打开足球直播。query 可选，填球队、赛事或比赛名称。
            2. OPEN_VIDEO_SEARCH：现在在视频应用搜索影视内容。query 必填；openFirstResult=true 表示直接尝试打开第一条结果，false 表示停在搜索结果。
            3. OPEN_MUSIC：现在在音乐应用播放歌曲。query 必填。
            4. SCHEDULE_LIVE_FOOTBALL：到未来开赛时间自动打开足球直播。query 必填，kickoffAt 必填，displayName 可选。
            5. SCHEDULE_REMINDER：在未来某个时间提醒用户，并可让用户当场确认下一步；也支持到点直接执行白名单动作。triggerAt、title、message 必填；confirmLabel 可选；followUp 可选；autoRunFollowUp 可选。
               followUp 只能是 OPEN_LIVE_FOOTBALL、OPEN_VIDEO_SEARCH 或 OPEN_MUSIC，格式与上面的动作相同。

            时间和执行规则：
            1. kickoffAt 和 triggerAt 必须使用带时区的 RFC 3339 时间，例如 2026-11-08T03:00:00+08:00，不能写“今晚”“开赛后”等相对文本。
            2. 所有时间以本轮给出的当前时间和系统时区为基准。不要把已经过去的时刻安排成未来提醒；如果事情已经开始且用户要马上看，选择立即动作。
            3. 电影上线、节目更新、比赛开赛等时间可能变化。依赖这些信息时必须先使用 web_search 核实。没有可靠未来时间就不要编造，改用普通纯文本说明缺少什么并询问用户。
            4. 对于电影上线这类用户要求“到点提醒并问是否要看”的场景，选择 SCHEDULE_REMINDER，message 写成到时要问用户的问题，followUp 填确认后要执行的立即动作。
            5. 只有用户明确要求“到点直接播放/打开，不要问我”时，才设置 autoRunFollowUp=true，并必须提供 followUp；到点直接执行该动作，不再询问。仅说“提醒我看看”时保持 false，仍生成提醒和确认按钮。
            6. 如果只有日期没有可靠时刻，而用户明确希望当天提醒，可以按当天 09:00 系统本地时间安排，并在 answer 里说明这是按当天上午提醒。
            7. 一场比赛只有一个开赛时刻。用户说“比赛开始时打开直播”表示未来执行，不要当成现在打开。
            8. 如果题干有歧义、上一轮回答里有多个候选且用户没有指明，只返回普通纯文本请用户补充，不要猜一个动作执行。

            定时提醒示例：
            用户：等《示例电影》上线时提醒我，并问我要不要马上看。
            助手：{"answer":"已按核实到的 12 月 31 日设置当天上午 09:00 提醒，到时我会问你是否现在查找观看。","action":{"type":"SCHEDULE_REMINDER","triggerAt":"2026-12-31T09:00:00+08:00","title":"《示例电影》上线提醒","message":"《示例电影》今天上线，要现在查找观看吗？","confirmLabel":"现在查找","followUp":{"type":"OPEN_VIDEO_SEARCH","query":"示例电影","openFirstResult":false}}}

            到时自动执行示例：
            用户：明晚八点直接播放周杰伦的晴天，不要再问我。
            助手：{"answer":"已安排明晚八点自动播放《晴天》。","action":{"type":"SCHEDULE_REMINDER","triggerAt":"2026-11-08T20:00:00+08:00","title":"播放《晴天》","message":"到点自动播放音乐。","autoRunFollowUp":true,"followUp":{"type":"OPEN_MUSIC","query":"周杰伦 晴天"}}}

            立即动作示例：
            用户：现在打开阿森纳直播。
            助手：{"answer":"正在打开阿森纳直播。","action":{"type":"OPEN_LIVE_FOOTBALL","query":"阿森纳"}}
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

private class TimeoutChatAttemptException : Exception()

private class RetryableChatAttemptException : Exception()

private class NonRetryableChatAttemptException : Exception()

private data class AttemptAnswer(
    val answer: String,
    val usedWebSearch: Boolean,
    val actionRequested: Boolean,
    val action: AssistantAction?,
)
