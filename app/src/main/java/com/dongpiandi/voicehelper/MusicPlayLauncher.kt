package com.dongpiandi.voicehelper

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/** Starts the Music app's TV voice bridge with the requested song. */
internal object MusicPlayLauncher {
    private const val MUSIC_PACKAGE = "top.izuna.foliamajor"
    private const val TV_ACTIVITY = "top.izuna.foliamajor.TvActivity"
    private const val ACTION_VOICE_PLAY = "top.izuna.foliamajor.action.VOICE_PLAY"
    private const val EXTRA_QUERY = "query"
    private const val MAX_QUERY_LENGTH = 80
    private const val TAG = "VoiceHelperMusicPlay"

    fun open(
        context: Context,
        query: String,
    ): Boolean {
        val normalized = query.trim().take(MAX_QUERY_LENGTH)
        if (normalized.isEmpty()) return false

        val intent =
            Intent(ACTION_VOICE_PLAY)
                .setComponent(ComponentName(MUSIC_PACKAGE, TV_ACTIVITY))
                .putExtra(EXTRA_QUERY, normalized)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        return try {
            context.startActivity(intent)
            true
        } catch (missing: ActivityNotFoundException) {
            Log.w(TAG, "Music voice activity unavailable")
            false
        } catch (denied: SecurityException) {
            Log.w(TAG, "Music voice activity denied")
            false
        }
    }
}
