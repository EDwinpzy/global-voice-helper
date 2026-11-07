package com.dongpiandi.voicehelper

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dongpiandi.voicehelper.assistant.AssistantAction

internal enum class ScheduledReminderExactness {
    EXACT,
    APPROXIMATE,
}

internal data class ScheduledReminderBooking(
    val reminder: ScheduledReminder,
    val exactness: ScheduledReminderExactness,
)

internal object ScheduledReminderScheduler {
    const val EXTRA_REMINDER_ID = "reminder_id"

    fun schedule(
        context: Context,
        triggerAtEpochMs: Long,
        title: String,
        message: String,
        confirmLabel: String?,
        followUp: AssistantAction.Immediate?,
        autoRunFollowUp: Boolean = false,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): ScheduledReminderBooking {
        require(triggerAtEpochMs > 0L) { "invalid reminder time" }
        require(title.isNotBlank()) { "empty reminder title" }
        require(message.isNotBlank()) { "empty reminder message" }

        val store = ScheduledReminderStore(context)
        val reminder =
            store.findEquivalent(title, triggerAtEpochMs)?.copy(
                triggerAtEpochMs = triggerAtEpochMs,
                title = title.trim().take(MAX_TITLE_LENGTH),
                message = message.trim().take(MAX_MESSAGE_LENGTH),
                confirmLabel = confirmLabel?.trim()?.take(MAX_CONFIRM_LABEL_LENGTH),
                followUp = followUp,
                autoRunFollowUp = autoRunFollowUp,
            ) ?: store.newReminder(
                triggerAtEpochMs = triggerAtEpochMs,
                createdAtEpochMs = nowEpochMs,
                title = title,
                message = message,
                confirmLabel = confirmLabel,
                followUp = followUp,
                autoRunFollowUp = autoRunFollowUp,
            )
        store.upsert(reminder)
        return try {
            val exactness = setAlarm(context, reminder, nowEpochMs)
            ScheduledReminderBooking(reminder, exactness)
        } catch (error: Exception) {
            store.remove(reminder.id)
            throw error
        }
    }

    fun rescheduleAll(
        context: Context,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        val store = ScheduledReminderStore(context)
        store.removeExpired(nowEpochMs)
        var restored = 0
        store.all().forEach { reminder ->
            runCatching {
                setAlarm(context, reminder, nowEpochMs)
                restored++
            }.onFailure { error ->
                Log.w(TAG, "restore failed type=${error.javaClass.simpleName}")
            }
        }
        if (restored > 0) Log.i(TAG, "restored count=$restored")
    }

    fun canScheduleExactAlarms(context: Context): Boolean =
        context
            .getSystemService(AlarmManager::class.java)
            .canScheduleExactAlarms()

    private fun setAlarm(
        context: Context,
        reminder: ScheduledReminder,
        nowEpochMs: Long,
    ): ScheduledReminderExactness {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = launchPendingIntent(context, reminder)
        val triggerAt = maxOf(reminder.triggerAtEpochMs, nowEpochMs + 1_000L)
        if (canScheduleExactAlarms(context)) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
                return ScheduledReminderExactness.EXACT
            } catch (_: SecurityException) {
                // Permission may have been revoked between the check and this call.
            }
        }
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            pendingIntent,
        )
        return ScheduledReminderExactness.APPROXIMATE
    }

    private fun launchPendingIntent(
        context: Context,
        reminder: ScheduledReminder,
    ): PendingIntent {
        val intent =
            Intent(context, ScheduledReminderActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_REMINDER_ID, reminder.id)
        return PendingIntent.getActivity(
            context,
            reminder.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val TAG = "VoiceHelperReminder"
    private const val MAX_TITLE_LENGTH = 100
    private const val MAX_MESSAGE_LENGTH = 240
    private const val MAX_CONFIRM_LABEL_LENGTH = 20
}
