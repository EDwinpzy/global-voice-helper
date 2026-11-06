package com.dongpiandi.voicehelper.benchmark

import org.json.JSONArray
import org.json.JSONObject

internal data class ModelBenchmarkAnswer(
    val characters: Int,
    val usedWebSearch: Boolean,
)

/** Debug-only model benchmark protocol and request factory. */
internal object ModelBenchmarkPolicy {
    val MODELS: List<String> =
        listOf(
            "workbuddy/deepseek-v4.1-flash",
        )

    const val DEFAULT_ROUNDS = 5
    const val MAX_ROUNDS = 10
    const val DEFAULT_PROMPT = "请只回复“收到”，不要解释。"
    const val MAX_OUTPUT_TOKENS = 4_000
    const val MAX_SEARCH_CALLS = 1

    fun normalizedRounds(requested: Int): Int =
        requested.coerceIn(1, MAX_ROUNDS)

    fun promptForRound(
        basePrompt: String,
        round: Int,
    ): String {
        val prompt = basePrompt.trim().ifEmpty { DEFAULT_PROMPT }
        return "$prompt（第${round + 1}轮）"
    }

    fun orderForRound(
        models: List<String>,
        round: Int,
    ): List<String> {
        if (models.isEmpty()) return emptyList()
        val offset = Math.floorMod(round, models.size)
        return models.drop(offset) + models.take(offset)
    }

    fun buildRequestJson(
        model: String,
        prompt: String,
        systemPrompt: String?,
    ): JSONObject {
        return JSONObject()
            .put("model", model)
            .put("instructions", buildInstructions(systemPrompt))
            .put(
                "input",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "input_text")
                                    .put("text", prompt),
                            ),
                        ),
                ),
            )
            .put(
                "tools",
                JSONArray().put(
                    JSONObject()
                        .put("type", "web_search")
                        .put("external_web_access", true)
                        .put("search_context_size", "low"),
                ),
            )
            .put("tool_choice", "auto")
            .put("parallel_tool_calls", false)
            .put("max_tool_calls", MAX_SEARCH_CALLS)
            .put("store", false)
            .put("stream", false)
            .put("max_output_tokens", MAX_OUTPUT_TOKENS)
    }

    fun parseAnswer(body: String): ModelBenchmarkAnswer? {
        val root = JSONObject(body)
        val output = root.optJSONArray("output")
        val answer = StringBuilder()
        var usedWebSearch = false
        if (output != null) {
            for (index in 0 until output.length()) {
                val item = output.optJSONObject(index) ?: continue
                when (item.optString("type")) {
                    "web_search_call" -> usedWebSearch = true
                    "message" -> appendContent(answer, item.opt("content"))
                }
            }
        }
        if (answer.isBlank()) {
            answer.append(root.optString("output_text"))
        }
        val text = answer.toString().trim()
        if (text.isEmpty()) return null
        return ModelBenchmarkAnswer(text.length, usedWebSearch)
    }

    fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2
        } else {
            sorted[middle]
        }
    }

    private fun appendContent(
        answer: StringBuilder,
        content: Any?,
    ) {
        when (content) {
            is String -> answer.append(content)
            is JSONObject -> appendPart(answer, content)
            is JSONArray -> {
                for (index in 0 until content.length()) {
                    when (val part = content.opt(index)) {
                        is String -> answer.append(part)
                        is JSONObject -> appendPart(answer, part)
                    }
                }
            }
        }
    }

    private fun appendPart(
        answer: StringBuilder,
        part: JSONObject,
    ) {
        when (part.optString("type")) {
            "output_text", "text" -> answer.append(part.optString("text"))
        }
    }

    private fun buildInstructions(systemPrompt: String?): String {
        val configuredPrompt = systemPrompt?.trim().orEmpty()
        return if (configuredPrompt.isEmpty()) {
            DEFAULT_INSTRUCTIONS
        } else {
            "$configuredPrompt\n\n$DEFAULT_INSTRUCTIONS"
        }
    }

    private const val DEFAULT_INSTRUCTIONS =
        "请使用与用户相同的语言，给出适合电视语音播报的简洁回答。" +
            "遇到新闻、天气、价格、版本、日程或其他可能变化的信息时，" +
            "使用一次 web_search 获取最新资料后直接回答，不要重复搜索。" +
            "不要输出 Markdown 表格、代码块或长网址；引用来源时只保留来源名称。"
}
