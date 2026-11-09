package com.dongpiandi.voicehelper

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

/** Executes generic TV remote actions against the currently focused app. */
internal object GlobalControlExecutor {
    private var accessibilityService: AccessibilityService? = null

    fun attach(service: AccessibilityService) {
        accessibilityService = service
    }

    fun detach(service: AccessibilityService) {
        if (accessibilityService === service) {
            accessibilityService = null
        }
    }

    fun perform(
        context: Context,
        action: GlobalControlAction,
    ): Boolean =
        when (action) {
            GlobalControlAction.VOLUME_UP ->
                adjustVolume(context, AudioManager.ADJUST_RAISE)
            GlobalControlAction.VOLUME_DOWN ->
                adjustVolume(context, AudioManager.ADJUST_LOWER)
            GlobalControlAction.MUTE ->
                adjustVolume(context, AudioManager.ADJUST_TOGGLE_MUTE)
            GlobalControlAction.PLAY_PAUSE ->
                dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            GlobalControlAction.PREVIOUS ->
                dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            GlobalControlAction.NEXT ->
                dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
            GlobalControlAction.FAST_FORWARD ->
                dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            GlobalControlAction.REWIND ->
                dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_REWIND)
            else -> performGlobalAction(action)
        }

    private fun performGlobalAction(action: GlobalControlAction): Boolean {
        val service = accessibilityService ?: return false
        val globalAction =
            when (action) {
                GlobalControlAction.UP -> AccessibilityService.GLOBAL_ACTION_DPAD_UP
                GlobalControlAction.DOWN -> AccessibilityService.GLOBAL_ACTION_DPAD_DOWN
                GlobalControlAction.LEFT -> AccessibilityService.GLOBAL_ACTION_DPAD_LEFT
                GlobalControlAction.RIGHT -> AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT
                GlobalControlAction.CONFIRM -> AccessibilityService.GLOBAL_ACTION_DPAD_CENTER
                GlobalControlAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
                GlobalControlAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
                GlobalControlAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
                GlobalControlAction.NOTIFICATIONS ->
                    AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                else -> return false
            }
        return runCatching { service.performGlobalAction(globalAction) }.getOrDefault(false)
    }

    private fun adjustVolume(
        context: Context,
        direction: Int,
    ): Boolean =
        runCatching {
            val audio = context.getSystemService(AudioManager::class.java) ?: return false
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI,
            )
            true
        }.getOrDefault(false)

    private fun dispatchMediaKey(
        context: Context,
        keyCode: Int,
    ): Boolean =
        runCatching {
            val audio = context.getSystemService(AudioManager::class.java) ?: return false
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            true
        }.getOrDefault(false)
}
