package com.dongpiandi.voicehelper

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/** Starts Video Pro's signature-protected search bridge from the assistant path. */
internal object VideoSearchLauncher {
    private const val VIDEO_PRO_PACKAGE = "com.dongpiandi.videopro"
    private const val VOICE_SEARCH_ACTIVITY = "com.qiubo.optimaltv.voice.VoiceSearchActivity"
    private const val ACTION_OPEN_SEARCH = "com.dongpiandi.videopro.action.OPEN_SEARCH"
    private const val EXTRA_QUERY = "query"
    private const val MAX_QUERY_LENGTH = 80
    private const val TAG = "VoiceHelperVideoSearch"

    fun open(
        context: Context,
        query: String,
    ): Boolean {
        val normalized = query.trim().take(MAX_QUERY_LENGTH)
        if (normalized.isEmpty()) return false

        val intent =
            Intent(ACTION_OPEN_SEARCH)
                .setComponent(ComponentName(VIDEO_PRO_PACKAGE, VOICE_SEARCH_ACTIVITY))
                .putExtra(EXTRA_QUERY, normalized)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)
            true
        } catch (missing: ActivityNotFoundException) {
            Log.w(TAG, "Video Pro search activity unavailable")
            false
        } catch (denied: SecurityException) {
            Log.w(TAG, "Video Pro search permission denied")
            false
        }
    }
}
