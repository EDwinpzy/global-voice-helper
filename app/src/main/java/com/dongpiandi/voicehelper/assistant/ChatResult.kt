package com.dongpiandi.voicehelper.assistant

data class ChatResult(
    val answer: String,
    val model: String,
    val usedWebSearch: Boolean = false,
    val action: AssistantAction? = null,
) {
    override fun toString(): String =
        "ChatResult(answer=<${answer.length} chars>, model=$model, " +
            "webSearch=$usedWebSearch, action=${action?.let { it::class.simpleName } ?: "none"})"
}
