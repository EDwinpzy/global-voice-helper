package com.dongpiandi.voicehelper

import com.dongpiandi.voicehelper.assistant.AssistantAction
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScheduledReminderJsonTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun roundTripsReminderWithFollowUpAndSkipsCorruptEntries() {
        val reminders =
            listOf(
                ScheduledReminder(
                    id = "movie",
                    triggerAtEpochMs = 1_798_765_200_000L,
                    createdAtEpochMs = 1_760_000_000_000L,
                    title = "《示例电影》上线提醒",
                    message = "《示例电影》今天上线，要现在查找观看吗？",
                    confirmLabel = "现在查找",
                    followUp =
                        AssistantAction.OpenVideoSearch(
                            query = "示例电影",
                            openFirstResult = false,
                        ),
                    autoRunFollowUp = true,
                ),
                ScheduledReminder(
                    id = "plain",
                    triggerAtEpochMs = 1_798_768_800_000L,
                    createdAtEpochMs = 1_760_000_000_000L,
                    title = "收快递",
                    message = "快递员到了吗？",
                    confirmLabel = null,
                    followUp = null,
                ),
            )

        assertEquals(
            reminders,
            ScheduledReminderJson.decode(
                raw = ScheduledReminderJson.encode(reminders),
                zone = zone,
            ),
        )

        val raw =
            JSONArray()
                .put(
                    JSONObject()
                        .put("id", "valid")
                        .put("triggerAt", 1_798_765_200_000L)
                        .put("title", "上线提醒")
                        .put("message", "要现在看吗？")
                        .put(
                            "followUp",
                            JSONObject()
                                .put("type", "OPEN_LIVE_FOOTBALL")
                                .put("query", "阿森纳"),
                        ),
                )
                .put(
                    JSONObject()
                        .put("id", "missing-action")
                        .put("triggerAt", 1_798_765_200_000L)
                        .put("title", "缺少动作")
                        .put("message", "不应恢复")
                        .put("autoRunFollowUp", true),
                )
                .put(JSONObject().put("id", "broken"))
                .toString()

        val decoded = ScheduledReminderJson.decode(raw, zone)

        assertEquals(1, decoded.size)
        assertEquals(
            AssistantAction.OpenLiveFootball("阿森纳"),
            decoded.single().followUp,
        )
        assertFalse(decoded.single().autoRunFollowUp)
    }
}
