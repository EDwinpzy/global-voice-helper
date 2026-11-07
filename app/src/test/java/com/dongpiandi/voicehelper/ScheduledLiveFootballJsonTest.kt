package com.dongpiandi.voicehelper

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduledLiveFootballJsonTest {
    @Test
    fun roundTripsSchedulesAndSkipsCorruptEntries() {
        val schedules =
            listOf(
                ScheduledLiveFootball(
                    id = "first",
                    query = "阿森纳",
                    kickoffAtEpochMs = 1_760_000_000_000L,
                    createdAtEpochMs = 1_759_990_000_000L,
                ),
                ScheduledLiveFootball(
                    id = "second",
                    query = "曼城",
                    kickoffAtEpochMs = 1_760_003_600_000L,
                    createdAtEpochMs = 1_759_990_100_000L,
                ),
            )

        assertEquals(
            schedules,
            ScheduledLiveFootballJson.decode(ScheduledLiveFootballJson.encode(schedules)),
        )

        val raw =
            JSONArray()
                .put(
                    JSONObject()
                        .put("id", "valid")
                        .put("query", "利物浦")
                        .put("kickoffAt", 1_760_000_000_000L),
                )
                .put(JSONObject().put("id", "broken"))
                .toString()
        val decoded = ScheduledLiveFootballJson.decode(raw)

        assertEquals(1, decoded.size)
        assertEquals("valid", decoded.single().id)
        assertEquals(0L, decoded.single().createdAtEpochMs)
    }
}
