package com.dongpiandi.voicehelper.assistant

import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantActionJsonTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now =
        ZonedDateTime.of(2026, 11, 7, 20, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()

    @Test
    fun ordinaryTextIsNotTreatedAsAnActionEnvelope() {
        assertNull(
            AssistantActionJson.parseEnvelope(
                raw = "今晚英超有两场比赛。",
                zone = zone,
            ),
        )
    }

    @Test
    fun fencedJsonEnvelopeIsStillRecognized() {
        val envelope =
            AssistantActionJson.parseEnvelope(
                raw =
                    """
                    ```json
                    {"answer":"正在打开直播。","action":{"type":"OPEN_LIVE_FOOTBALL"}}
                    ```
                    """.trimIndent(),
                zone = zone,
            )

        assertEquals("正在打开直播。", envelope?.answer)
        assertEquals(
            AssistantAction.OpenLiveFootball(query = null),
            envelope?.action,
        )
    }

    @Test
    fun jsonEnvelopeSurvivesLeadingProse() {
        val envelope =
            AssistantActionJson.parseEnvelope(
                raw =
                    """
                    好的，正在为你处理。
                    {"answer":"正在打开音乐。","action":{"type":"OPEN_MUSIC","query":"晴天"}}
                    """.trimIndent(),
                zone = zone,
            )

        assertEquals("正在打开音乐。", envelope?.answer)
        assertEquals(
            AssistantAction.OpenMusic("晴天"),
            envelope?.action,
        )
    }

    @Test
    fun bracesInOrdinaryTextAreNotTreatedAsAnEnvelope() {
        assertNull(
            AssistantActionJson.parseEnvelope(
                raw = "集合 {1, 2} 不是设备动作。",
                zone = zone,
            ),
        )
    }

    @Test
    fun dateOnlyReminderUsesTheLocalMorningFallback() {
        val action =
            AssistantActionJson.parseAction(
                action =
                    JSONObject(
                        """
                        {
                          "type": "SCHEDULE_REMINDER",
                          "triggerAt": "2026-12-31",
                          "title": "电影上线",
                          "message": "要现在看吗？"
                        }
                        """.trimIndent(),
                    ),
                zone = zone,
            ) as AssistantAction.ScheduleReminder

        assertEquals(
            ZonedDateTime.of(2026, 12, 31, 9, 0, 0, 0, zone)
                .toInstant()
                .toEpochMilli(),
            action.triggerAtEpochMs,
        )
    }

    @Test
    fun localDateTimeWithoutOffsetUsesTheDeviceZone() {
        val action =
            AssistantActionJson.parseAction(
                action =
                    JSONObject(
                        """
                        {
                          "type": "SCHEDULE_LIVE_FOOTBALL",
                          "query": "阿森纳",
                          "kickoffAt": "2026-11-07T23:30:00",
                          "displayName": "阿森纳 vs 利物浦"
                        }
                        """.trimIndent(),
                    ),
                zone = zone,
            ) as AssistantAction.ScheduleLiveFootball

        assertEquals(
            ZonedDateTime.of(2026, 11, 7, 23, 30, 0, 0, zone)
                .toInstant()
                .toEpochMilli(),
            action.kickoffAtEpochMs,
        )
    }

    @Test
    fun invalidFollowUpInvalidatesTheWholeReminder() {
        assertNull(
            AssistantActionJson.parseAction(
                action =
                    JSONObject(
                        """
                        {
                          "type": "SCHEDULE_REMINDER",
                          "triggerAt": "2026-12-31T09:00:00+08:00",
                          "title": "电影上线",
                          "message": "要现在看吗？",
                          "followUp": {
                            "type": "SCHEDULE_REMINDER",
                            "triggerAt": "2026-12-31T10:00:00+08:00",
                            "title": "嵌套",
                            "message": "不允许"
                          }
                        }
                        """.trimIndent(),
                    ),
                zone = zone,
            ),
        )
    }

    @Test
    fun autoRunReminderRequiresAndParsesItsFollowUp() {
        val action =
            AssistantActionJson.parseAction(
                action =
                    JSONObject(
                        """
                        {
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
                        """.trimIndent(),
                    ),
                zone = zone,
            ) as AssistantAction.ScheduleReminder

        assertTrue(action.autoRunFollowUp)
        assertEquals(
            AssistantAction.OpenMusic("周杰伦 晴天"),
            action.followUp,
        )

        assertNull(
            AssistantActionJson.parseAction(
                action =
                    JSONObject(
                        """
                        {
                          "type": "SCHEDULE_REMINDER",
                          "triggerAt": "2026-11-08T20:00:00+08:00",
                          "title": "缺少后续动作",
                          "message": "不应被安排。",
                          "autoRunFollowUp": true
                        }
                        """.trimIndent(),
                    ),
                zone = zone,
            ),
        )
    }

    @Test
    fun validatorRejectsPastSchedulesAndAcceptsFutureOnes() {
        val past =
            AssistantAction.ScheduleReminder(
                triggerAtEpochMs = now - 120_000L,
                title = "过期",
                message = "不应该执行",
                confirmLabel = null,
                followUp = null,
            )
        val future =
            past.copy(triggerAtEpochMs = now + 60_000L)

        assertNull(AssistantActionValidator.validateAt(past, now))
        assertEquals(future, AssistantActionValidator.validateAt(future, now))

        val autoFuture =
            future.copy(
                autoRunFollowUp = true,
                followUp = AssistantAction.OpenMusic("晴天"),
            )
        assertEquals(autoFuture, AssistantActionValidator.validateAt(autoFuture, now))
        assertNull(
            AssistantActionValidator.validateAt(
                autoFuture.copy(followUp = null),
                now,
            ),
        )
    }

    @Test
    fun encodedFollowUpRoundTripsThroughTheReminderStoreShape() {
        val followUp =
            AssistantAction.OpenVideoSearch(
                query = "示例电影",
                openFirstResult = false,
            )
        val encoded = AssistantActionJson.encode(followUp)
        val decoded = AssistantActionJson.parseAction(encoded, zone)

        assertEquals(followUp, decoded)
        assertTrue(encoded.has("query"))
        assertFalse(encoded.has("followUp"))
    }

    @Test
    fun musicEntriesAndNamedCollectionsAreRoundTripped() {
        val entry = AssistantAction.OpenMusicEntry(MusicEntry.LIKED)
        assertEquals(
            entry,
            AssistantActionJson.parseAction(AssistantActionJson.encode(entry), zone),
        )

        val playlist =
            AssistantAction.OpenMusicEntry(
                entry = MusicEntry.PLAYLIST,
                query = "车载MP3永久保留曲目",
            )
        assertEquals(
            playlist,
            AssistantActionJson.parseAction(AssistantActionJson.encode(playlist), zone),
        )

        assertNull(
            AssistantActionJson.parseAction(
                JSONObject(
                    """
                    {
                      "type": "OPEN_MUSIC_ENTRY",
                      "entry": "music.playlist"
                    }
                    """.trimIndent(),
                ),
                zone,
            ),
        )
        assertNull(
            AssistantActionJson.parseAction(
                JSONObject(
                    """
                    {
                      "type": "OPEN_MUSIC_ENTRY",
                      "entry": "music.unknown"
                    }
                    """.trimIndent(),
                ),
                zone,
            ),
        )
    }

    @Test
    fun douyinSearchIsPartOfTheImmediateWhitelist() {
        val action =
            AssistantAction.OpenDouyinSearch(
                query = "三国演义",
                openFirstResult = true,
            )
        assertEquals(
            action,
            AssistantActionJson.parseAction(AssistantActionJson.encode(action), zone),
        )
        assertEquals(
            action,
            AssistantActionValidator.validateAt(action, now),
        )
    }
}
