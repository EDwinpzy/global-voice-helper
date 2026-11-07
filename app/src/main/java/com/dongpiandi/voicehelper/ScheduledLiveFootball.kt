package com.dongpiandi.voicehelper

import android.content.Context
import java.util.UUID
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

internal data class ScheduledLiveFootball(
    val id: String,
    val query: String,
    val kickoffAtEpochMs: Long,
    val createdAtEpochMs: Long,
)

internal object ScheduledLiveFootballJson {
    fun encode(schedules: List<ScheduledLiveFootball>): String {
        val array = JSONArray()
        schedules.forEach { schedule ->
            array.put(
                JSONObject()
                    .put("id", schedule.id)
                    .put("query", schedule.query)
                    .put("kickoffAt", schedule.kickoffAtEpochMs)
                    .put("createdAt", schedule.createdAtEpochMs),
            )
        }
        return array.toString()
    }

    fun decode(raw: String?): List<ScheduledLiveFootball> {
        if (raw.isNullOrBlank()) return emptyList()
        val array =
            runCatching {
                JSONArray(raw)
            }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val schedule =
                    runCatching {
                        ScheduledLiveFootball(
                            id = item.getString("id"),
                            query = item.getString("query").trim(),
                            kickoffAtEpochMs = item.getLong("kickoffAt"),
                            createdAtEpochMs = item.optLong("createdAt", 0L),
                        )
                    }.getOrNull() ?: continue
                if (
                    schedule.id.isBlank() ||
                    schedule.query.isBlank() ||
                    schedule.kickoffAtEpochMs <= 0L
                ) {
                    continue
                }
                add(schedule)
            }
        }
    }
}

internal class ScheduledLiveFootballStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )

    @Synchronized
    fun all(): List<ScheduledLiveFootball> =
        ScheduledLiveFootballJson
            .decode(preferences.getString(KEY_SCHEDULES, null))
            .sortedBy(ScheduledLiveFootball::kickoffAtEpochMs)

    @Synchronized
    fun findEquivalent(
        query: String,
        kickoffAtEpochMs: Long,
    ): ScheduledLiveFootball? {
        val normalized = query.trim()
        return all().firstOrNull { schedule ->
            schedule.query.equals(normalized, ignoreCase = true) &&
                abs(schedule.kickoffAtEpochMs - kickoffAtEpochMs) <= DUPLICATE_WINDOW_MS
        }
    }

    @Synchronized
    fun newSchedule(
        query: String,
        kickoffAtEpochMs: Long,
        createdAtEpochMs: Long,
    ): ScheduledLiveFootball =
        ScheduledLiveFootball(
            id = UUID.randomUUID().toString(),
            query = query.trim().take(MAX_QUERY_LENGTH),
            kickoffAtEpochMs = kickoffAtEpochMs,
            createdAtEpochMs = createdAtEpochMs,
        )

    @Synchronized
    fun upsert(schedule: ScheduledLiveFootball) {
        val updated =
            all()
                .filterNot { current ->
                    current.id == schedule.id
                }
                .toMutableList()
        updated += schedule
        updated.sortBy(ScheduledLiveFootball::kickoffAtEpochMs)
        preferences
            .edit()
            .putString(
                KEY_SCHEDULES,
                ScheduledLiveFootballJson.encode(updated.takeLast(MAX_SCHEDULES)),
            )
            .apply()
    }

    @Synchronized
    fun remove(id: String) {
        val remaining =
            all().filterNot { schedule ->
                schedule.id == id
            }
        preferences
            .edit()
            .putString(KEY_SCHEDULES, ScheduledLiveFootballJson.encode(remaining))
            .apply()
    }

    @Synchronized
    fun removeExpired(nowEpochMs: Long) {
        val pending =
            all().filter { schedule ->
                schedule.kickoffAtEpochMs > nowEpochMs
            }
        preferences
            .edit()
            .putString(KEY_SCHEDULES, ScheduledLiveFootballJson.encode(pending))
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "scheduled_live_football"
        const val KEY_SCHEDULES = "schedules"
        const val MAX_SCHEDULES = 16
        const val MAX_QUERY_LENGTH = 80
        const val DUPLICATE_WINDOW_MS = 60_000L
    }
}
