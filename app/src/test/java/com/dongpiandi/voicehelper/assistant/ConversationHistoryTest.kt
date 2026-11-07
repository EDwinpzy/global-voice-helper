package com.dongpiandi.voicehelper.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationHistoryTest {
    @Test
    fun keepsOnlyTheMostRecentTurns() {
        val history = ConversationHistory(maxTurns = 2)

        history.add("第一问", "第一答")
        history.add("第二问", "第二答")
        history.add("第三问", "第三答")

        assertEquals(
            listOf(
                ConversationTurn("第二问", "第二答"),
                ConversationTurn("第三问", "第三答"),
            ),
            history.snapshot(),
        )
    }

    @Test
    fun truncatesLongMessagesAndIgnoresBlankOnes() {
        val history =
            ConversationHistory(
                maxTurns = 2,
                maxCharsPerMessage = 5,
            )

        history.add("123456789", "abcdefghi")
        history.add("   ", "answer")

        val turn = history.snapshot().single()
        assertEquals("12345…", turn.user)
        assertEquals("abcde…", turn.assistant)
    }

    @Test
    fun clearRemovesAllTurns() {
        val history = ConversationHistory()
        history.add("问题", "回答")

        history.clear()

        assertTrue(history.snapshot().isEmpty())
    }

    @Test
    fun expiresOldContextAfterTheConfiguredTtl() {
        var now = 1_000_000L
        val history =
            ConversationHistory(
                ttlMs = 12_000L,
                nowProvider = { now },
            )
        history.add("问题", "回答")

        now += 11_999L
        assertEquals(1, history.snapshot().size)

        now += 1L
        assertTrue(history.snapshot().isEmpty())
    }
}
