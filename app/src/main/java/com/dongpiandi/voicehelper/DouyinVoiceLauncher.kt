package com.dongpiandi.voicehelper

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log

/** Sends a command through Douyin's UID-authenticated ContentProvider bridge. */
internal object DouyinVoiceLauncher {
    private const val DOUYIN_PACKAGE = "com.dongpiandi.douyintv"
    private const val VOICE_ENTRY_ACTIVITY =
        "com.dongpiandi.douyintv.voice.VoiceEntryActivity"
    private const val PROVIDER_AUTHORITY = "com.dongpiandi.douyintv.voice.commands"
    private const val PROVIDER_METHOD = "dispatch"
    private const val EXTRA_OPERATION = "operation"
    private const val EXTRA_QUERY = "query"
    private const val EXTRA_OPEN_FIRST_RESULT = "openFirstResult"
    private const val EXTRA_ACCEPTED = "accepted"
    private const val TAG = "VoiceHelperDouyin"

    fun open(
        context: Context,
        request: DouyinVoiceRequest,
    ): Boolean {
        val query = request.query?.trim().orEmpty()
        val foreground = isDouyinForeground(context)

        if (!foreground) {
            val activityIntent =
                Intent()
                    .setComponent(ComponentName(DOUYIN_PACKAGE, VOICE_ENTRY_ACTIVITY))
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
            try {
                context.startActivity(activityIntent)
            } catch (error: Exception) {
                Log.w(TAG, "Douyin launch failed type=${error.javaClass.simpleName}")
                return false
            }
        }

        val accepted = dispatchCommand(context, request, query)
        if (accepted) {
            Log.i(
                TAG,
                "provider route foreground=$foreground " +
                    "operation=${request.operation.wireValue} chars=${query.length}",
            )
        }
        return accepted
    }

    private fun dispatchCommand(
        context: Context,
        request: DouyinVoiceRequest,
        query: String,
    ): Boolean {
        val extras =
            Bundle().apply {
                putString(EXTRA_OPERATION, request.operation.wireValue)
                putString(EXTRA_QUERY, query)
                putBoolean(EXTRA_OPEN_FIRST_RESULT, request.openFirstResult)
            }
        return runCatching {
            context.contentResolver
                .call(
                    Uri.parse("content://$PROVIDER_AUTHORITY"),
                    PROVIDER_METHOD,
                    null,
                    extras,
                )
                ?.getBoolean(EXTRA_ACCEPTED, false) == true
        }.getOrElse { error ->
            Log.w(TAG, "Douyin command dispatch failed type=${error.javaClass.simpleName}")
            false
        }
    }

    private fun isDouyinForeground(context: Context): Boolean {
        val service = context as? AccessibilityService ?: return false
        val activeWindowMatches =
            runCatching {
                service.rootInActiveWindow?.packageName?.toString() == DOUYIN_PACKAGE
            }.getOrDefault(false)
        if (activeWindowMatches) return true
        return runCatching {
            service.windows.any { window ->
                window.root?.packageName?.toString() == DOUYIN_PACKAGE
            }
        }.getOrDefault(false)
    }
}
