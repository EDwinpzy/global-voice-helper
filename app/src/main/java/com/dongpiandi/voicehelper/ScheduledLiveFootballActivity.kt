package com.dongpiandi.voicehelper

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast

/**
 * A transparent alarm target. AlarmManager launches this activity from the system, which avoids
 * Android background-activity restrictions before handing off to QiuJi.
 */
class ScheduledLiveFootballActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val scheduleId = intent.getStringExtra(ScheduledLiveFootballScheduler.EXTRA_SCHEDULE_ID)
        val query =
            intent
                .getStringExtra(ScheduledLiveFootballScheduler.EXTRA_SCHEDULE_QUERY)
                ?.trim()
                .orEmpty()
        if (scheduleId != null) {
            runCatching {
                ScheduledLiveFootballStore(this).remove(scheduleId)
            }
        }
        if (query.isNotEmpty()) {
            if (LiveFootballLauncher.open(this, query)) {
                Log.i(TAG, "scheduled live launched chars=${query.length}")
            } else {
                Toast.makeText(this, R.string.assistant_live_football_unavailable, Toast.LENGTH_LONG)
                    .show()
            }
        }
        finishAndRemoveTask()
    }

    private companion object {
        const val TAG = "VoiceHelperLiveAlarm"
    }
}
