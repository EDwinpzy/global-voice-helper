package com.dongpiandi.voicehelper

import android.content.Context
import com.dongpiandi.voicehelper.assistant.AssistantAction
import com.dongpiandi.voicehelper.assistant.AssistantActionJson
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

internal data class ScheduledReminder(
    val id: String,
    val triggerAtEpochMs: Long,
    val createdAtEpochMs: Long,
    val title: String,
    val message: String,
    val confirmLabel: String?,
    val followUp: AssistantAction.Immediate?,
    val autoRunFollowUp: Boolean = false,
)

internal object ScheduledReminderJson {
    fun encode(reminders: List<ScheduledReminder>): String {
        val array = JSONArray()
        reminders.forEach { reminder ->
            array.put(
                JSONObject()
                    .put("id", reminder.id)
                    .put("triggerAt", reminder.triggerAtEpochMs)
                    .put("createdAt", reminder.createdAtEpochMs)
                    .put("title", reminder.title)
                    .put("message", reminder.message)
                    .put("autoRunFollowUp", reminder.autoRunFollowUp)
                    .apply {
                        reminder.confirmLabel?.let { put("confirmLabel", it) }
                        reminder.followUp?.let {
                            put("followUp", AssistantActionJson.encode(it))
                        }
                    },
            )
        }
        return array.toString()
    }

    fun decode(
        raw: String?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ScheduledReminder> {
        if (raw.isNullOrBlank()) return emptyList()
        val array =
            runCatching {
                JSONArray(raw)
            }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val reminder =
                    runCatching {
                        val followUp =
                            item.optJSONObject("followUp")?.let { followUpJson ->
                                AssistantActionJson.parseAction(followUpJson, zone)
                                    as? AssistantAction.Immediate
                            }
                        ScheduledReminder(
                            id = item.getString("id"),
                            triggerAtEpochMs = item.getLong("triggerAt"),
                            createdAtEpochMs = item.optLong("createdAt", 0L),
                            title = item.getString("title").trim(),
                            message = item.getString("message").trim(),
                            confirmLabel =
                                item.optString("confirmLabel")
                                    .trim()
                                    .takeIf(String::isNotEmpty),
                            followUp = followUp,
                            autoRunFollowUp = item.optBoolean("autoRunFollowUp", false),
                        )
                    }.getOrNull() ?: continue
                if (
                    reminder.id.isBlank() ||
                    reminder.triggerAtEpochMs <= 0L ||
                    reminder.title.isBlank() ||
                    reminder.message.isBlank() ||
                    (reminder.autoRunFollowUp && reminder.followUp == null)
                ) {
                    continue
                }
                add(reminder)
            }
        }
    }
}

internal class ScheduledReminderStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )

    @Synchronized
    fun all(): List<ScheduledReminder> =
        ScheduledReminderJson
            .decode(preferences.getString(KEY_REMINDERS, null))
            .sortedBy(ScheduledReminder::triggerAtEpochMs)

    @Synchronized
    fun find(id: String): ScheduledReminder? =
        all().firstOrNull { reminder ->
            reminder.id == id
        }

    @Synchronized
    fun findEquivalent(
        title: String,
        triggerAtEpochMs: Long,
    ): ScheduledReminder? {
        val normalized = title.trim()
        return all().firstOrNull { reminder ->
            reminder.title.equals(normalized, ignoreCase = true) &&
                abs(reminder.triggerAtEpochMs - triggerAtEpochMs) <= DUPLICATE_WINDOW_MS
        }
    }

    @Synchronized
    fun newReminder(
        triggerAtEpochMs: Long,
        createdAtEpochMs: Long,
        title: String,
        message: String,
        confirmLabel: String?,
        followUp: AssistantAction.Immediate?,
        autoRunFollowUp: Boolean,
    ): ScheduledReminder =
        ScheduledReminder(
            id = UUID.randomUUID().toString(),
            triggerAtEpochMs = triggerAtEpochMs,
            createdAtEpochMs = createdAtEpochMs,
            title = title.trim().take(MAX_TITLE_LENGTH),
            message = message.trim().take(MAX_MESSAGE_LENGTH),
            confirmLabel = confirmLabel?.trim()?.take(MAX_CONFIRM_LABEL_LENGTH),
            followUp = followUp,
            autoRunFollowUp = autoRunFollowUp,
        )

    @Synchronized
    fun upsert(reminder: ScheduledReminder) {
        val updated =
            all()
                .filterNot { current -> current.id == reminder.id }
                .toMutableList()
        updated += reminder
        updated.sortBy(ScheduledReminder::triggerAtEpochMs)
        preferences
            .edit()
            .putString(
                KEY_REMINDERS,
                ScheduledReminderJson.encode(updated.take(MAX_REMINDERS)),
            )
            .apply()
    }

    @Synchronized
    fun remove(id: String) {
        val remaining =
            all().filterNot { reminder ->
                reminder.id == id
            }
        preferences
            .edit()
            .putString(KEY_REMINDERS, ScheduledReminderJson.encode(remaining))
            .apply()
    }

    @Synchronized
    fun removeExpired(nowEpochMs: Long) {
        val pending =
            all().filter { reminder ->
                reminder.triggerAtEpochMs > nowEpochMs
            }
        preferences
            .edit()
            .putString(KEY_REMINDERS, ScheduledReminderJson.encode(pending))
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "scheduled_assistant_reminder"
        const val KEY_REMINDERS = "reminders"
        const val MAX_REMINDERS = 16
        const val MAX_TITLE_LENGTH = 100
        const val MAX_MESSAGE_LENGTH = 240
        const val MAX_CONFIRM_LABEL_LENGTH = 20
        const val DUPLICATE_WINDOW_MS = 60_000L
    }
}
