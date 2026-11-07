package com.dongpiandi.voicehelper

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Restores pending football alarms after reboot, app replacement, or an exact-alarm grant. */
class ScheduledLiveFootballRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> runCatching {
                ScheduledLiveFootballScheduler.rescheduleAll(context)
            }.onFailure { error ->
                Log.w(TAG, "reschedule failed type=${error.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        const val TAG = "VoiceHelperLiveAlarm"
    }
}
