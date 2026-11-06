package com.dongpiandi.voicehelper.benchmark

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelBenchmarkPolicyTest {
    @Test
    fun benchmarkTargetsTheRequestedModelsInOrder() {
        assertEquals(
            listOf(
                "workbuddy/deepseek-v4.1-flash",
            ),
            ModelBenchmarkPolicy.MODELS,
        )
    }

    @Test
    fun modelOrderRotatesAcrossRounds() {
        val models = listOf("a", "b", "c")

        assertEquals(listOf("a", "b", "c"), ModelBenchmarkPolicy.orderForRound(models, 0))
        assertEquals(listOf("b", "c", "a"), ModelBenchmarkPolicy.orderForRound(models, 1))
        assertEquals(listOf("c", "a", "b"), ModelBenchmarkPolicy.orderForRound(models, 2))
        assertEquals(listOf("a", "b", "c"), ModelBenchmarkPolicy.orderForRound(models, 3))
    }

    @Test
    fun roundCountIsBoundedAndPromptIsUnique() {
        assertEquals(1, ModelBenchmarkPolicy.normalizedRounds(0))
        assertEquals(10, ModelBenchmarkPolicy.normalizedRounds(99))
        assertEquals("请回答（第2轮）", ModelBenchmarkPolicy.promptForRound("  请回答  ", 1))
    }

    @Test
    fun requestUsesTheProductionResponsesShape() {
        val body =
            ModelBenchmarkPolicy.buildRequestJson(
                model = "workbuddy/deepseek-v4.1-flash",
                prompt = "只回答收到",
                systemPrompt = "自定义回答要求",
            )

        assertEquals("workbuddy/deepseek-v4.1-flash", body.getString("model"))
        assertFalse(body.getBoolean("stream"))
        assertFalse(body.getBoolean("store"))
        assertEquals(ModelBenchmarkPolicy.MAX_OUTPUT_TOKENS, body.getInt("max_output_tokens"))
        assertEquals("auto", body.getString("tool_choice"))
        assertFalse(body.getBoolean("parallel_tool_calls"))
        assertEquals(ModelBenchmarkPolicy.MAX_SEARCH_CALLS, body.getInt("max_tool_calls"))
        assertTrue(body.getString("instructions").contains("自定义回答要求"))
        assertEquals(1, body.getJSONArray("tools").length())
        assertEquals("只回答收到", firstInputText(body))
    }

    @Test
    fun requestIncludesTheProductionSearchTool() {
        val body =
            ModelBenchmarkPolicy.buildRequestJson(
                model = "model",
                prompt = "latest news",
                systemPrompt = null,
            )
        val tools = body.getJSONArray("tools")

        assertEquals(1, tools.length())
        assertEquals("web_search", tools.getJSONObject(0).getString("type"))
        assertEquals("auto", body.getString("tool_choice"))
        assertEquals(1, body.getInt("max_tool_calls"))
    }

    @Test
    fun parsesOutputTextAndSearchUsage() {
        val output =
            JSONArray()
                .put(JSONObject().put("type", "web_search_call"))
                .put(
                    JSONObject()
                        .put("type", "message")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "output_text")
                                    .put("text", "收到"),
                            ),
                        ),
                )
        val answer =
            ModelBenchmarkPolicy.parseAnswer(
                JSONObject().put("output", output).toString(),
            )

        assertEquals(2, answer?.characters)
        assertTrue(answer?.usedWebSearch == true)
    }

    @Test
    fun emptyAnswerIsRejectedAndMedianIsStable() {
        assertNull(
            ModelBenchmarkPolicy.parseAnswer(
                JSONObject().put("output", JSONArray()).toString(),
            ),
        )
        assertEquals(20L, ModelBenchmarkPolicy.median(listOf(30L, 10L, 20L)))
        assertEquals(25L, ModelBenchmarkPolicy.median(listOf(30L, 10L, 20L, 40L)))
    }

    private fun firstInputText(body: JSONObject): String =
        body
            .getJSONArray("input")
            .getJSONObject(0)
            .getJSONArray("content")
            .getJSONObject(0)
            .getString("text")
}
