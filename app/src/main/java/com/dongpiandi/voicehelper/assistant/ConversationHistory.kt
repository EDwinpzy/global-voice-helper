package com.dongpiandi.voicehelper.assistant

data class ConversationTurn(
    val user: String,
    val assistant: String,
)

/**
 * Keeps only the most recent exchanges so the assistant can understand follow-up questions
 * without carrying an unbounded conversation into every request.
 */
internal class ConversationHistory(
    private val maxTurns: Int = DEFAULT_MAX_TURNS,
    private val maxCharsPerMessage: Int = DEFAULT_MAX_CHARS_PER_MESSAGE,
    private val ttlMs: Long = DEFAULT_CONTEXT_TTL_MS,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    private val turns = ArrayDeque<ConversationTurn>()
    private var lastUpdatedAtEpochMs = 0L

    fun snapshot(): List<ConversationTurn> {
        clearIfExpired()
        return turns.toList()
    }

    fun add(
        user: String,
        assistant: String,
    ) {
        clearIfExpired()
        val userText = normalize(user)
        val assistantText = normalize(assistant)
        if (userText.isEmpty() || assistantText.isEmpty()) return

        turns.addLast(ConversationTurn(userText, assistantText))
        lastUpdatedAtEpochMs = nowProvider()
        while (turns.size > maxTurns) {
            turns.removeFirst()
        }
    }

    fun clear() {
        turns.clear()
        lastUpdatedAtEpochMs = 0L
    }

    private fun clearIfExpired() {
        if (lastUpdatedAtEpochMs <= 0L) return
        val age = nowProvider() - lastUpdatedAtEpochMs
        if (age >= ttlMs) clear()
    }

    private fun normalize(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.length <= maxCharsPerMessage) {
            trimmed
        } else {
            trimmed.take(maxCharsPerMessage).trimEnd() + "…"
        }
    }

    companion object {
        const val DEFAULT_MAX_TURNS = 2
        const val DEFAULT_MAX_CHARS_PER_MESSAGE = 300
        const val DEFAULT_CONTEXT_TTL_MS = 12L * 60L * 60L * 1_000L
    }
}
