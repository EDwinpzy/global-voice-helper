package com.dongpiandi.voicehelper

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/** Opens the installed QiuJi app and asks it to start a named live match when available. */
internal object LiveFootballLauncher {
    private const val LIVE_PACKAGE = "com.qiubo.optimaltv.public"
    private const val MAIN_ACTIVITY = "com.qiubo.optimaltv.MainActivity"
    private const val ACTION_VOICE_OPEN_MATCH = "com.qiubo.optimaltv.action.VOICE_OPEN_MATCH"
    private const val EXTRA_QUERY = "query"
    private const val MAX_QUERY_LENGTH = 80
    private const val TAG = "VoiceHelperLive"

    fun open(
        context: Context,
        query: String? = null,
    ): Boolean {
        val normalized = query?.trim()?.take(MAX_QUERY_LENGTH).orEmpty()
        val intent =
            Intent(ACTION_VOICE_OPEN_MATCH)
                .setComponent(ComponentName(LIVE_PACKAGE, MAIN_ACTIVITY))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (normalized.isNotEmpty()) intent.putExtra(EXTRA_QUERY, normalized)
        return try {
            context.startActivity(intent)
            true
        } catch (missing: ActivityNotFoundException) {
            Log.w(TAG, "Live football app unavailable")
            false
        } catch (denied: SecurityException) {
            Log.w(TAG, "Live football app denied")
            false
        }
    }
}
