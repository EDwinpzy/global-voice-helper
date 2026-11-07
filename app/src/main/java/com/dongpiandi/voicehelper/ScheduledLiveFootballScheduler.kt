package com.dongpiandi.voicehelper

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

internal enum class ScheduledLiveFootballExactness {
    EXACT,
    APPROXIMATE,
}

internal data class ScheduledLiveFootballBooking(
    val schedule: ScheduledLiveFootball,
    val exactness: ScheduledLiveFootballExactness,
)

internal object ScheduledLiveFootballScheduler {
    const val EXTRA_SCHEDULE_ID = "schedule_id"
    const val EXTRA_SCHEDULE_QUERY = "schedule_query"

    fun schedule(
        context: Context,
        query: String,
        kickoffAtEpochMs: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): ScheduledLiveFootballBooking {
        val normalizedQuery = query.trim().take(MAX_QUERY_LENGTH)
        require(normalizedQuery.isNotEmpty()) { "empty live football query" }
        require(kickoffAtEpochMs > 0L) { "invalid live football kickoff" }

        val store = ScheduledLiveFootballStore(context)
        val schedule =
            store.findEquivalent(normalizedQuery, kickoffAtEpochMs)?.copy(
                query = normalizedQuery,
                kickoffAtEpochMs = kickoffAtEpochMs,
            ) ?: store.newSchedule(
                query = normalizedQuery,
                kickoffAtEpochMs = kickoffAtEpochMs,
                createdAtEpochMs = nowEpochMs,
            )
        store.upsert(schedule)
        return try {
            val exactness = setAlarm(context, schedule, nowEpochMs)
            ScheduledLiveFootballBooking(schedule, exactness)
        } catch (error: Exception) {
            store.remove(schedule.id)
            throw error
        }
    }

    fun rescheduleAll(
        context: Context,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        val store = ScheduledLiveFootballStore(context)
        store.removeExpired(nowEpochMs)
        var restored = 0
        store.all().forEach { schedule ->
            runCatching {
                setAlarm(context, schedule, nowEpochMs)
                restored++
            }.onFailure { error ->
                Log.w(TAG, "restore failed type=${error.javaClass.simpleName}")
            }
        }
        if (restored > 0) Log.i(TAG, "restored count=$restored")
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        return context
            .getSystemService(AlarmManager::class.java)
            .canScheduleExactAlarms()
    }

    fun openExactAlarmSettings(context: Context): Boolean {
        if (canScheduleExactAlarms(context)) {
            return false
        }
        val request =
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(request)
            true
        } catch (_: ActivityNotFoundException) {
            openApplicationDetails(context)
        } catch (_: SecurityException) {
            openApplicationDetails(context)
        }
    }

    private fun setAlarm(
        context: Context,
        schedule: ScheduledLiveFootball,
        nowEpochMs: Long,
    ): ScheduledLiveFootballExactness {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = launchPendingIntent(context, schedule)
        val triggerAt = maxOf(schedule.kickoffAtEpochMs, nowEpochMs + 1_000L)
        if (canScheduleExactAlarms(context)) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
                return ScheduledLiveFootballExactness.EXACT
            } catch (_: SecurityException) {
                // Permission may have been revoked between the check and this call.
            }
        }
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            pendingIntent,
        )
        return ScheduledLiveFootballExactness.APPROXIMATE
    }

    private fun launchPendingIntent(
        context: Context,
        schedule: ScheduledLiveFootball,
    ): PendingIntent {
        val intent =
            Intent(context, ScheduledLiveFootballActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                .putExtra(EXTRA_SCHEDULE_ID, schedule.id)
                .putExtra(EXTRA_SCHEDULE_QUERY, schedule.query)
        return PendingIntent.getActivity(
            context,
            schedule.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openApplicationDetails(context: Context): Boolean =
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (_: Exception) {
            false
        }

    private const val TAG = "VoiceHelperLiveAlarm"
    private const val MAX_QUERY_LENGTH = 80
}
