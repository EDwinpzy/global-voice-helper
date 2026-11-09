package com.dongpiandi.voicehelper

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts Video Pro's signature-protected search bridge.
 *
 * Video Pro owns the "open first result" behavior: when [openFirstResult] is true it
 * navigates from the result page to the first detail route itself. The helper must not
 * send remote keys afterwards because a delayed D-pad sequence can land on the detail
 * page and trigger playback.
 */
internal object VideoSearchLauncher {
    private const val VIDEO_PRO_PACKAGE = "com.dongpiandi.videopro"
    private const val VOICE_SEARCH_ACTIVITY = "com.qiubo.optimaltv.voice.VoiceSearchActivity"
    private const val VOICE_SEARCH_RECEIVER = "com.qiubo.optimaltv.voice.VoiceSearchReceiver"
    private const val ACTION_OPEN_SEARCH = "com.dongpiandi.videopro.action.OPEN_SEARCH"
    private const val ACTION_OPEN_SEARCH_IN_APP =
        "com.dongpiandi.videopro.action.OPEN_SEARCH_IN_APP"
    private const val EXTRA_QUERY = "query"
    private const val EXTRA_OPEN_FIRST_RESULT = "openFirstResult"
    private const val MAX_QUERY_LENGTH = 80
    private const val TAG = "VoiceHelperVideoSearch"

    fun open(
        context: Context,
        query: String,
        openFirstResult: Boolean,
    ): Boolean {
        val normalized = query.trim().take(MAX_QUERY_LENGTH)
        if (normalized.isEmpty()) return false

        val foreground = isVideoProForeground(context)
        val inAppIntent =
            Intent(ACTION_OPEN_SEARCH_IN_APP)
                .setComponent(ComponentName(VIDEO_PRO_PACKAGE, VOICE_SEARCH_RECEIVER))
                .putExtra(EXTRA_QUERY, normalized)
                .putExtra(EXTRA_OPEN_FIRST_RESULT, openFirstResult)
        runCatching { context.sendBroadcast(inAppIntent) }
            .onFailure {
                Log.w(TAG, "Video Pro in-app search broadcast failed")
            }
        if (foreground) {
            Log.i(TAG, "in-app route foreground=true")
            return true
        }

        val intent =
            Intent(ACTION_OPEN_SEARCH)
                .setComponent(ComponentName(VIDEO_PRO_PACKAGE, VOICE_SEARCH_ACTIVITY))
                .putExtra(EXTRA_QUERY, normalized)
                .putExtra(EXTRA_OPEN_FIRST_RESULT, openFirstResult)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)
            Log.i(TAG, "activity route foreground=false")
            true
        } catch (missing: ActivityNotFoundException) {
            Log.w(TAG, "Video Pro search activity unavailable")
            false
        } catch (denied: SecurityException) {
            Log.w(TAG, "Video Pro search permission denied")
            false
        }
    }

    private fun isVideoProForeground(context: Context): Boolean {
        val service = context as? AccessibilityService ?: return false
        return runCatching {
            service.windows.any { window ->
                window.root?.packageName?.toString() == VIDEO_PRO_PACKAGE
            }
        }.getOrDefault(false)
    }
}
