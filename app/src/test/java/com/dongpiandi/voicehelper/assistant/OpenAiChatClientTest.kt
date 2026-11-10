package com.dongpiandi.voicehelper.assistant

import java.net.SocketTimeoutException
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenAiChatClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun successfulFirstModelSendsResponsesWebSearchRequest() = runBlocking {
        server.enqueue(successResponse("\"晴，适合出门。\""))

        val result =
            newClient(
                config =
                    config(
                        apiKey = "secret-token",
                        systemPrompt = "回答简洁。",
                    ),
            ).complete("今天天气怎么样？")

        assertEquals(ChatResult("晴，适合出门。", OpenAiChatClient.DEFAULT_MODELS[0]), result)
        assertEquals(
            listOf("workbuddy/deepseek-v4.1-flash"),
            OpenAiChatClient.DEFAULT_MODELS,
        )
        assertEquals("group/free", OpenAiChatClient.TIMEOUT_FALLBACK_MODEL)
        assertEquals(
            listOf(30_000L, 30_000L),
            OpenAiChatClient.DEFAULT_ATTEMPT_TIMEOUTS_MS,
        )
        assertEquals(1, server.requestCount)

        val request = requireRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/responses", request.path)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("workbuddy/deepseek-v4.1-flash", body.getString("model"))
        assertTrue(body.getString("instructions").contains("回答简洁。"))
        assertTrue(body.getString("instructions").contains("web_search"))
        assertTrue(body.getString("instructions").contains("必须使用阿拉伯数字"))
        assertTrue(body.getString("instructions").contains("当前时间：2026年11月7日"))
        assertTrue(body.getString("instructions").contains("当前时间的 RFC 3339 表示"))
        assertTrue(body.getString("instructions").contains("Asia/Shanghai"))
        assertTrue(body.getString("instructions").contains("SCHEDULE_REMINDER"))
        assertTrue(body.getString("instructions").contains("autoRunFollowUp"))
        assertTrue(body.getString("instructions").contains("OPEN_LIVE_FOOTBALL"))
        assertFalse(body.getBoolean("store"))
        assertFalse(body.getBoolean("stream"))
        assertFalse(body.getBoolean("parallel_tool_calls"))
        assertEquals("auto", body.getString("tool_choice"))
        assertEquals(OpenAiChatClient.MAX_SEARCH_CALLS, body.getInt("max_tool_calls"))
        assertEquals(
            OpenAiChatClient.MAX_OUTPUT_TOKENS,
            body.getInt("max_output_tokens"),
        )
        assertFalse(body.has("messages"))

        val input = body.getJSONArray("input")
        assertEquals(1, input.length())
        val userMessage = input.getJSONObject(0)
        assertEquals("user", userMessage.getString("role"))
        val content = userMessage.getJSONArray("content")
        assertEquals(1, content.length())
        assertEquals("input_text", content.getJSONObject(0).getString("type"))
        assertEquals("今天天气怎么样？", content.getJSONObject(0).getString("text"))

        val tools = body.getJSONArray("tools")
        assertEquals(1, tools.length())
        assertEquals("web_search", tools.getJSONObject(0).getString("type"))
        assertTrue(tools.getJSONObject(0).getBoolean("external_web_access"))
        assertEquals("medium", tools.getJSONObject(0).getString("search_context_size"))
        assertEquals(2, body.getInt("max_tool_calls"))
    }

    @Test
    fun previousTurnsAreIncludedWithoutReplacingTheCurrentInput() = runBlocking {
        server.enqueue(successResponse("\"第二天的回答\""))

        val result =
            newClient().complete(
                userPrompt = "那明天呢？",
                history =
                    listOf(
                        ConversationTurn(
                            user = "今天天气怎么样？",
                            assistant = "今天多云，最高 22 度。",
                        ),
                    ),
            )

        assertEquals("第二天的回答", result.answer)
        val body = JSONObject(requireRequest().body.readUtf8())
        val instructions = body.getString("instructions")
        assertTrue(instructions.contains("用户：今天天气怎么样？"))
        assertTrue(instructions.contains("助手：今天多云，最高 22 度。"))
        assertEquals(1, body.getJSONArray("input").length())
        assertEquals(
            "那明天呢？",
            body.getJSONArray("input")
                .getJSONObject(0)
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text"),
        )
    }

    @Test
    fun markdownAndBulletMarkersAreRemovedFromTheAnswer() = runBlocking {
        server.enqueue(plainAnswerResponse("**结论**\n- 第一点\n- 第二点"))

        val result = newClient().complete("问题")

        assertEquals("结论\n第一点\n第二点", result.answer)
    }

    @Test
    fun numberedItemsAreSeparatedByBlankLines() = runBlocking {
        server.enqueue(
            plainAnswerResponse(
                "推荐这三部。1. 《A》：理由一。2. 《B》：理由二。" +
                    "3. 《C》：理由三。最适合的是《A》。",
            ),
        )

        val result = newClient().complete("问题")

        assertEquals(
            "推荐这三部。\n\n" +
                "1. 《A》：理由一。\n\n" +
                "2. 《B》：理由二。\n\n" +
                "3. 《C》：理由三。\n\n" +
                "最适合的是《A》。",
            result.answer,
        )
    }

    @Test
    fun ordinarySentencesArePlacedOnSeparateLines() = runBlocking {
        server.enqueue(
            plainAnswerResponse(
                "上海多云，气温 19 度。今天最高 19 度，最低 15 度。出门带件外套即可。",
            ),
        )

        val result = newClient().complete("问题")

        assertEquals(
            "上海多云，气温 19 度。\n今天最高 19 度，最低 15 度。\n出门带件外套即可。",
            result.answer,
        )
    }

    @Test
    fun overlongAnswersAreTruncatedAtASentenceBoundary() = runBlocking {
        val longAnswer = "第一句话。" + "第二句话。".repeat(100)
        server.enqueue(plainAnswerResponse(longAnswer))

        val result = newClient().complete("问题")

        assertTrue(result.answer.length <= OpenAiChatClient.MAX_ANSWER_CHARS + 2)
        assertTrue(result.answer.endsWith("……"))
        assertFalse(result.answer.contains("**"))
    }

    @Test
    fun fallbackStatusesRetryTheSecondModel() {
        val statuses =
            listOf(
                400,
                404,
                408,
                409,
                422,
                425,
                429,
                500,
                503,
            )
        statuses.forEach { status ->
            val isolated = MockWebServer()
            isolated.start()
            try {
                isolated.enqueue(MockResponse().setResponseCode(status).setBody("private detail"))
                isolated.enqueue(successResponse("\"fallback answer\""))

                val result =
                    runBlocking {
                        newClient(
                            config = config(serverUrl = isolated.url("/").toString()),
                            models = listOf("test-primary", "test-secondary"),
                        ).complete("question")
                    }

                assertEquals("fallback answer", result.answer)
                assertEquals("test-secondary", result.model)
                assertEquals(2, isolated.requestCount)
                val first = isolated.takeRequest(2, TimeUnit.SECONDS)
                val second = isolated.takeRequest(2, TimeUnit.SECONDS)
                assertNotNull(first)
                assertNotNull(second)
                assertEquals("/v1/responses", first!!.path)
                assertEquals("/v1/responses", second!!.path)
                assertEquals(
                    "test-primary",
                    JSONObject(first.body.readUtf8()).getString("model"),
                )
                assertEquals(
                    "test-secondary",
                    JSONObject(second.body.readUtf8()).getString("model"),
                )
            } finally {
                isolated.shutdown()
            }
        }
    }

    @Test
    fun networkFailureRetriesTheSecondModel() = runBlocking {
        server.enqueue(
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
        )
        server.enqueue(successResponse("\"network fallback\""))

        val result =
            newClient(models = listOf("test-primary", "test-secondary"))
                .complete("question")

        assertEquals("network fallback", result.answer)
        assertEquals("test-secondary", result.model)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun timeoutFailureRetriesTheSecondModel() = runBlocking {
        val attempts = AtomicInteger()
        val timeout =
            Interceptor { chain ->
                if (attempts.incrementAndGet() == 1) {
                    throw SocketTimeoutException("private timeout detail")
                }
                chain.proceed(chain.request())
            }
        val httpClient =
            OkHttpClient.Builder()
                .addInterceptor(timeout)
                .retryOnConnectionFailure(false)
                .build()
        server.enqueue(successResponse("\"timeout fallback\""))

        val result =
            newClient(
                httpClient = httpClient,
                models = listOf("test-primary", "test-secondary"),
            ).complete("question")

        assertEquals("timeout fallback", result.answer)
        assertEquals("test-secondary", result.model)
        assertEquals(2, attempts.get())
        assertEquals(1, server.requestCount)
        assertFalse(result.answer.contains("private timeout detail"))
    }

    @Test
    fun coroutineTimeoutUsesTheFreeFallbackModel() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(successResponse("\"timeout fallback\""))

        val result =
            newClient(
                attemptTimeoutsMs = listOf(150L, 2_000L),
                models = listOf("test-primary"),
            ).complete("question")

        assertEquals("timeout fallback", result.answer)
        assertEquals(OpenAiChatClient.TIMEOUT_FALLBACK_MODEL, result.model)
        assertEquals(2, server.requestCount)
        val first = checkNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        val fallback = checkNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("test-primary", JSONObject(first.body.readUtf8()).getString("model"))
        assertEquals(
            OpenAiChatClient.TIMEOUT_FALLBACK_MODEL,
            JSONObject(fallback.body.readUtf8()).getString("model"),
        )
    }

    @Test
    fun httpTimeoutStatusUsesTheFreeFallbackModel() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(504).setBody("private timeout detail"))
        server.enqueue(successResponse("\"timeout fallback\""))

        val result =
            newClient(models = listOf("test-primary"))
                .complete("question")

        assertEquals("timeout fallback", result.answer)
        assertEquals(OpenAiChatClient.TIMEOUT_FALLBACK_MODEL, result.model)
        assertEquals(2, server.requestCount)
        val first = checkNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        val fallback = checkNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("test-primary", JSONObject(first.body.readUtf8()).getString("model"))
        assertEquals(
            OpenAiChatClient.TIMEOUT_FALLBACK_MODEL,
            JSONObject(fallback.body.readUtf8()).getString("model"),
        )
        assertFalse(result.answer.contains("private timeout detail"))
    }

    @Test
    fun nonTimeoutRetryableFailureDoesNotUseTheFreeFallbackModel() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("private error"))
        server.enqueue(successResponse("\"must not run\""))

        val result =
            newClient(models = listOf("test-primary"))
                .complete("question")

        assertEquals(OpenAiChatClient.SAFE_UNAVAILABLE_MESSAGE, result.answer)
        assertEquals("", result.model)
        assertEquals(1, server.requestCount)
        assertFalse(result.answer.contains("private error"))
    }

    @Test
    fun slowFirstAttemptFallsBackWithoutWaitingForTheGlobalCallTimeout() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(successResponse("\"fast fallback\""))

        val startedAt = System.nanoTime()
        val result =
            newClient(
                attemptTimeoutsMs = listOf(150L, 2_000L),
                models = listOf("test-primary", "test-secondary"),
            )
                .complete("question")
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertEquals("fast fallback", result.answer)
        assertEquals("test-secondary", result.model)
        assertEquals(2, server.requestCount)
        assertTrue("fallback took ${elapsedMs}ms", elapsedMs < 2_000L)
    }

    @Test
    fun emptyMessageRetriesTheSecondModel() = runBlocking {
        server.enqueue(successResponse("\"   \""))
        server.enqueue(successResponse("\"second answer\""))

        val result =
            newClient(models = listOf("test-primary", "test-secondary"))
                .complete("question")

        assertEquals("second answer", result.answer)
        assertEquals("test-secondary", result.model)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun invalidJsonRetriesTheSecondModel() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{not-json"),
        )
        server.enqueue(successResponse("\"second answer\""))

        val result =
            newClient(models = listOf("test-primary", "test-secondary"))
                .complete("question")

        assertEquals("second answer", result.answer)
        assertEquals("test-secondary", result.model)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun invalidResponsesShapeRetriesTheSecondModel() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"completed","output":[]}"""),
        )
        server.enqueue(successResponse("\"second answer\""))

        val result =
            newClient(models = listOf("test-primary", "test-secondary"))
                .complete("question")

        assertEquals("second answer", result.answer)
        assertEquals("test-secondary", result.model)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun authenticationFailuresStopAfterTheFirstRequest() = runBlocking {
        listOf(401, 403).forEach { status ->
            val isolated = MockWebServer()
            isolated.start()
            try {
                isolated.enqueue(
                    MockResponse()
                        .setResponseCode(status)
                        .setBody("private authentication response"),
                )
                isolated.enqueue(successResponse("\"must not run\""))

                val result =
                    newClient(
                        config = config(serverUrl = isolated.url("/").toString()),
                    ).complete("question")

                assertEquals(OpenAiChatClient.SAFE_AUTH_MESSAGE, result.answer)
                assertEquals("", result.model)
                assertEquals(1, isolated.requestCount)
                assertFalse(result.answer.contains("private authentication response"))
            } finally {
                isolated.shutdown()
            }
        }
    }

    @Test
    fun bothModelsFailingReturnsOnlyTheSafeSummary() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("private response one"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("private response two"))

        val result =
            newClient(models = listOf("test-primary", "test-secondary"))
                .complete("question")

        assertEquals(OpenAiChatClient.SAFE_UNAVAILABLE_MESSAGE, result.answer)
        assertEquals("", result.model)
        assertEquals(2, server.requestCount)
        assertFalse(result.answer.contains("private response"))
    }

    @Test
    fun messagePartsAreJoinedWithoutExposingReasoningItems() = runBlocking {
        val output =
            JSONArray()
                .put(
                    JSONObject()
                        .put("type", "reasoning")
                        .put("summary", "private reasoning"),
                )
                .put(
                    JSONObject()
                        .put("type", "web_search_call")
                        .put("status", "completed"),
                )
                .put(
                    JSONObject()
                        .put("type", "message")
                        .put(
                            "content",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put("type", "output_text")
                                        .put("text", "你"),
                                )
                                .put(
                                    JSONObject()
                                        .put("type", "output_text")
                                        .put("text", "好"),
                                ),
                        ),
                )
        server.enqueue(
            jsonResponse(
                JSONObject()
                    .put("status", "completed")
                    .put("output", output),
            ),
        )

        val result = newClient().complete("question")

        assertEquals("你好", result.answer)
        assertEquals(OpenAiChatClient.DEFAULT_MODELS[0], result.model)
        assertTrue(result.usedWebSearch)
        assertFalse(result.answer.contains("private reasoning"))
    }

    @Test
    fun topLevelOutputTextIsAccepted() = runBlocking {
        server.enqueue(
            jsonResponse(
                JSONObject()
                    .put("status", "completed")
                    .put("output_text", "直接回答"),
            ),
        )

        val result = newClient().complete("question")

        assertEquals("直接回答", result.answer)
        assertEquals(OpenAiChatClient.DEFAULT_MODELS[0], result.model)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun modelSelectedLiveFootballActionIsParsedWithoutLeakingJson() = runBlocking {
        server.enqueue(
            plainAnswerResponse(
                """
                {
                  "answer": "正在打开阿森纳直播。",
                  "action": {
                    "type": "OPEN_LIVE_FOOTBALL",
                    "query": "阿森纳"
                  }
                }
                """.trimIndent(),
            ),
        )

        val result = newClient().complete("今晚想看阿森纳")

        assertEquals("正在打开阿森纳直播。", result.answer)
        assertEquals(AssistantAction.OpenLiveFootball("阿森纳"), result.action)
        assertFalse(result.answer.contains("OPEN_LIVE_FOOTBALL"))
    }

    @Test
    fun actionEnvelopeWithLeadingProseIsStillExecuted() = runBlocking {
        server.enqueue(
            plainAnswerResponse(
                """
                好的，正在处理。
                {
                  "answer": "正在播放晴天。",
                  "action": {
                    "type": "OPEN_MUSIC",
                    "query": "晴天"
                  }
                }
                """.trimIndent(),
            ),
        )

        val result = newClient().complete("我想听晴天")

        assertEquals("正在播放晴天。", result.answer)
        assertEquals(AssistantAction.OpenMusic("晴天"), result.action)
        assertFalse(result.answer.contains("OPEN_MUSIC"))
    }

    @Test
    fun modelSelectedReminderCarriesItsFollowUpAction() = runBlocking {
        server.enqueue(
            plainAnswerResponse(
                """
                {
                  "answer": "已安排上线当天上午提醒你。",
                  "action": {
                    "type": "SCHEDULE_REMINDER",
                    "triggerAt": "2026-12-31T09:00:00+08:00",
                    "title": "《示例电影》上线提醒",
                    "message": "《示例电影》今天上线，要现在查找观看吗？",
                    "confirmLabel": "现在查找",
                    "followUp": {
                      "type": "OPEN_VIDEO_SEARCH",
                      "query": "示例电影",
                      "openFirstResult": false
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        val result = newClient().complete("等它上线提醒我，到时候问我要不要看")

        assertEquals("已安排上线当天上午提醒你。", result.answer)
        val action = result.action as AssistantAction.ScheduleReminder
        assertEquals("《示例电影》上线提醒", action.title)
        assertEquals(
            AssistantAction.OpenVideoSearch(
                query = "示例电影",
                openFirstResult = false,
            ),
            action.followUp,
        )
        assertFalse(action.autoRunFollowUp)
    }

    @Test
    fun modelSelectedAutoRunMusicReminderKeepsItsFollowUp() = runBlocking {
        server.enqueue(
            plainAnswerResponse(
                """
                {
                  "answer": "已安排明晚八点自动播放《晴天》。",
                  "action": {
                    "type": "SCHEDULE_REMINDER",
                    "triggerAt": "2026-11-08T20:00:00+08:00",
                    "title": "播放《晴天》",
                    "message": "到点自动播放音乐。",
                    "autoRunFollowUp": true,
                    "followUp": {
                      "type": "OPEN_MUSIC",
                      "query": "周杰伦 晴天"
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        val result =
            newClient().complete("明晚八点直接播放周杰伦的晴天，不要再问我")

        val action = result.action as AssistantAction.ScheduleReminder
        assertTrue(action.autoRunFollowUp)
        assertEquals(AssistantAction.OpenMusic("周杰伦 晴天"), action.followUp)
    }

    @Test
    fun expiredScheduledActionRetriesAnotherModelInsteadOfBookingIt() = runBlocking {
        server.enqueue(
            plainAnswerResponse(
                """
                {
                  "answer": "已经安排。",
                  "action": {
                    "type": "SCHEDULE_REMINDER",
                    "triggerAt": "2026-11-06T09:00:00+08:00",
                    "title": "过期提醒",
                    "message": "这条不应该被安排。"
                  }
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(successResponse("\"第二个回答\""))

        val result =
            newClient(models = listOf("test-primary", "test-secondary"))
                .complete("明天提醒我")

        assertEquals("第二个回答", result.answer)
        assertEquals("test-secondary", result.model)
        assertNull(result.action)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun unknownModelActionRetriesInsteadOfReturningAConfirmation() = runBlocking {
        server.enqueue(
            plainAnswerResponse(
                """
                {
                  "answer": "已经处理。",
                  "action": {
                    "type": "UNSUPPORTED_DEVICE_ACTION"
                  }
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(successResponse("\"普通回答\""))

        val result =
            newClient(models = listOf("test-primary", "test-secondary"))
                .complete("帮我处理一下")

        assertEquals("普通回答", result.answer)
        assertNull(result.action)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun emptyApiKeyOmitsAuthorizationHeader() = runBlocking {
        server.enqueue(successResponse("\"keyless answer\""))

        val result = newClient(config = config(apiKey = "")).complete("question")

        assertEquals("keyless answer", result.answer)
        assertNull(requireRequest().getHeader("Authorization"))
    }

    @Test
    fun invalidLocalConfigurationMakesNoRequest() = runBlocking {
        val result =
            newClient(
                config =
                    AssistantConfig(
                        baseUrl = "ftp://private.example.invalid/path",
                        apiKey = "secret-token",
                        systemPrompt = null,
                    ),
            ).complete("question")

        assertEquals(OpenAiChatClient.SAFE_CONFIGURATION_MESSAGE, result.answer)
        assertEquals("", result.model)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun invalidApiKeyFailsLocallyWithoutEchoingTheKey() = runBlocking {
        val privateKey = "private\u0000key"
        val result = newClient(config = config(apiKey = privateKey)).complete("question")

        assertEquals(OpenAiChatClient.SAFE_CONFIGURATION_MESSAGE, result.answer)
        assertFalse(result.answer.contains("private"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun configurationDescriptionRedactsPrivateValues() {
        val description =
            AssistantConfig(
                baseUrl = "https://private.example.invalid/secret-path",
                apiKey = "private-api-key",
                systemPrompt = "private system prompt",
            ).toString()

        assertFalse(description.contains("private.example.invalid"))
        assertFalse(description.contains("private-api-key"))
        assertFalse(description.contains("private system prompt"))
        assertTrue(description.contains("<redacted>"))
    }

    @Test
    fun resultDescriptionOmitsTheAnswerText() {
        val description =
            ChatResult(
                answer = "private model response",
                model = OpenAiChatClient.DEFAULT_MODELS[0],
                action =
                    AssistantAction.ScheduleReminder(
                        triggerAtEpochMs = 1_800_000_000_000L,
                        title = "private reminder title",
                        message = "private reminder message",
                        confirmLabel = "private label",
                        followUp = AssistantAction.OpenVideoSearch("private query", false),
                    ),
            ).toString()

        assertFalse(description.contains("private model response"))
        assertFalse(description.contains("private reminder"))
        assertFalse(description.contains("private query"))
        assertTrue(description.contains("workbuddy/deepseek-v4.1-flash"))
        assertTrue(description.contains("ScheduleReminder"))
    }

    @Test
    fun cancellationPropagatesAndCancelsTheActiveOkHttpCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val activeCall = AtomicReference<Call>()
        val listener =
            object : EventListener() {
                override fun callStart(call: Call) {
                    activeCall.set(call)
                }
            }
        val client =
            newClient(
                httpClient =
                    OkHttpClient.Builder()
                        .eventListener(listener)
                        .build(),
            )
        val request =
            async(start = CoroutineStart.UNDISPATCHED) {
                client.complete("question")
            }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

        request.cancel()
        try {
            request.await()
            fail("CancellationException was not propagated")
        } catch (_: CancellationException) {
            // Expected.
        }

        val call = activeCall.get()
        assertNotNull(call)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!call.isCanceled() && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(call.isCanceled())
        assertEquals(1, server.requestCount)
    }

    private fun newClient(
        config: AssistantConfig = config(),
        httpClient: OkHttpClient =
            OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .build(),
        attemptTimeoutsMs: List<Long> = OpenAiChatClient.DEFAULT_ATTEMPT_TIMEOUTS_MS,
        models: List<String> = OpenAiChatClient.DEFAULT_MODELS,
        timeoutFallbackModel: String = OpenAiChatClient.TIMEOUT_FALLBACK_MODEL,
        nowEpochMs: Long = FIXED_NOW_EPOCH_MS,
        zoneId: ZoneId = FIXED_ZONE,
    ): OpenAiChatClient =
        OpenAiChatClient(
            configProvider = { config },
            httpClient = httpClient,
            attemptTimeoutsMs = attemptTimeoutsMs,
            models = models,
            timeoutFallbackModel = timeoutFallbackModel,
            nowProvider = { nowEpochMs },
            zoneIdProvider = { zoneId },
        )

    private fun config(
        serverUrl: String = server.url("/").toString(),
        apiKey: String = "test-key",
        systemPrompt: String? = null,
    ): AssistantConfig =
        AssistantConfig(
            baseUrl = serverUrl,
            apiKey = apiKey,
            systemPrompt = systemPrompt,
        )

    private fun successResponse(textJson: String): MockResponse {
        val output =
            JSONArray().put(
                JSONObject()
                    .put("type", "message")
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "output_text")
                                .put("text", JSONObject("""{"text":$textJson}""").getString("text")),
                        ),
                    ),
            )
        return jsonResponse(
            JSONObject()
                .put("status", "completed")
                .put("output", output),
        )
    }

    private fun plainAnswerResponse(text: String): MockResponse {
        val output =
            JSONArray().put(
                JSONObject()
                    .put("type", "message")
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "output_text")
                                .put("text", text),
                        ),
                    ),
            )
        return jsonResponse(
            JSONObject()
                .put("status", "completed")
                .put("output", output),
        )
    }

    private fun jsonResponse(body: JSONObject): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body.toString())

    private fun requireRequest() =
        checkNotNull(server.takeRequest(2, TimeUnit.SECONDS)) {
            "No request reached MockWebServer"
        }

    private companion object {
        val FIXED_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        val FIXED_NOW_EPOCH_MS: Long =
            java.time.ZonedDateTime
                .of(2026, 11, 7, 20, 0, 0, 0, FIXED_ZONE)
                .toInstant()
                .toEpochMilli()
    }
}
