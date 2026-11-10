package com.dongpiandi.voicehelper

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.dongpiandi.voicehelper.assistant.MusicEntry

/** Starts the Music app's voice bridge with the requested song. */
internal object MusicPlayLauncher {
    private const val MUSIC_PACKAGE = "top.izuna.foliamajor"
    private const val ENTRY_ACTIVITY = "top.izuna.foliamajor.EntryActivity"
    private const val ACTION_VOICE_PLAY = "top.izuna.foliamajor.action.VOICE_PLAY"
    private const val ACTION_VOICE_COMMAND_V1 =
        "top.izuna.foliamajor.action.VOICE_COMMAND_V1"
    private const val OPERATION_PLAY_ENTRY = "PLAY_ENTRY"
    private const val EXTRA_QUERY = "query"
    private const val EXTRA_OPERATION = "operation"
    private const val EXTRA_ENTRY = "entry"
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
                .setComponent(ComponentName(MUSIC_PACKAGE, ENTRY_ACTIVITY))
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

    fun openEntry(
        context: Context,
        entry: MusicEntry,
        query: String? = null,
    ): Boolean {
        if (!supportsEntries(context)) return false
        val normalizedQuery = query?.trim()?.take(MAX_QUERY_LENGTH).orEmpty()
        if (entry.requiresQuery && normalizedQuery.isEmpty()) return false

        val intent =
            Intent(ACTION_VOICE_COMMAND_V1)
                .setComponent(ComponentName(MUSIC_PACKAGE, ENTRY_ACTIVITY))
                .putExtra(EXTRA_OPERATION, OPERATION_PLAY_ENTRY)
                .putExtra(EXTRA_ENTRY, entry.wireValue)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (normalizedQuery.isNotEmpty()) intent.putExtra(EXTRA_QUERY, normalizedQuery)

        return try {
            context.startActivity(intent)
            true
        } catch (missing: ActivityNotFoundException) {
            Log.w(TAG, "Music voice-entry activity unavailable")
            false
        } catch (denied: SecurityException) {
            Log.w(TAG, "Music voice-entry activity denied")
            false
        }
    }

    fun isInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(
                MUSIC_PACKAGE,
                PackageManager.PackageInfoFlags.of(0),
            )
            true
        } catch (_: Exception) {
            false
        }

    fun supportsEntries(context: Context): Boolean =
        try {
            context.packageManager.queryIntentActivities(
                Intent(ACTION_VOICE_COMMAND_V1)
                    .setPackage(MUSIC_PACKAGE)
                    .addCategory(Intent.CATEGORY_DEFAULT),
                PackageManager.MATCH_ALL,
            ).isNotEmpty()
        } catch (_: Exception) {
            false
        }
}
