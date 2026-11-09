package com.dongpiandi.voicehelper

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Starts Video Pro's signature-protected search bridge from the assistant path. */
internal object VideoSearchLauncher {
    private const val VIDEO_PRO_PACKAGE = "com.dongpiandi.videopro"
    private const val VOICE_SEARCH_ACTIVITY = "com.qiubo.optimaltv.voice.VoiceSearchActivity"
    private const val ACTION_OPEN_SEARCH = "com.dongpiandi.videopro.action.OPEN_SEARCH"
    private const val EXTRA_QUERY = "query"
    private const val EXTRA_OPEN_FIRST_RESULT = "openFirstResult"
    private const val MAX_QUERY_LENGTH = 80
    private const val AUTO_OPEN_START_DELAY_MS = 6_000L
    private const val AUTO_OPEN_POLL_INTERVAL_MS = 700L
    private const val AUTO_OPEN_TIMEOUT_MS = 25_000L
    private const val AUTO_OPEN_VERIFY_DELAY_MS = 2_300L
    private const val KEYBOARD_CHECK_INTERVAL_MS = 250L
    private const val MAX_KEYBOARD_CHECKS = 4
    private const val MAX_AUTO_OPEN_ATTEMPTS = 2
    private const val DPAD_STEP_DELAY_MS = 700L
    private const val DPAD_LIST_STEP_DELAY_MS = 550L
    private const val TAG = "VoiceHelperVideoSearch"
    private val main = Handler(Looper.getMainLooper())
    private val screenshotExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "voice-video-search-frame").apply { isDaemon = true }
        }
    private var accessibilityService: AccessibilityService? = null
    private var autoOpenSession: AutoOpenSession? = null

    private enum class DpadStage {
        FOCUS_INPUT,
        WAIT_FOR_KEYBOARD,
        DISMISS_KEYBOARD,
        FOCUS_FIRST_RESULT,
        ACTIVATE_RESULT,
    }

    private data class AutoOpenSession(
        val queryLength: Int,
        val startedAtMs: Long,
        var attempts: Int = 0,
        var screenshotInFlight: Boolean = false,
        var stage: DpadStage? = null,
        var keyboardChecks: Int = 0,
    )

    fun open(
        context: Context,
        query: String,
        openFirstResult: Boolean,
    ): Boolean {
        val normalized = query.trim().take(MAX_QUERY_LENGTH)
        if (normalized.isEmpty()) return false

        clearAutoOpen()
        if (!launch(context, intentFor(normalized, openFirstResult))) return false
        if (openFirstResult) {
            val session =
                AutoOpenSession(
                    queryLength = normalized.length,
                    startedAtMs = SystemClock.uptimeMillis(),
                )
            autoOpenSession = session
            schedulePoll(session, AUTO_OPEN_START_DELAY_MS)
            Log.i(TAG, "video-search automatic-open watch scheduled")
        }
        return true
    }

    internal fun attach(service: AccessibilityService) {
        accessibilityService = service
    }

    internal fun detach(service: AccessibilityService) {
        if (accessibilityService === service) {
            accessibilityService = null
            clearAutoOpen()
        }
    }

    private fun schedulePoll(
        session: AutoOpenSession,
        delayMs: Long,
    ) {
        main.postDelayed({ pollForResultPage(session) }, delayMs)
    }

    private fun pollForResultPage(session: AutoOpenSession) {
        if (autoOpenSession !== session || session.screenshotInFlight) return
        val now = SystemClock.uptimeMillis()
        if (now - session.startedAtMs >= AUTO_OPEN_TIMEOUT_MS) {
            Log.w(
                TAG,
                "video-search automatic-open stopped reason=timeout " +
                    "attempts=${session.attempts}",
            )
            clearAutoOpen()
            return
        }
        val service = accessibilityService
        if (service == null || !isVideoProForeground(service)) {
            schedulePoll(session, AUTO_OPEN_POLL_INTERVAL_MS)
            return
        }
        captureResultFrame(session, service)
    }

    private fun captureResultFrame(
        session: AutoOpenSession,
        service: AccessibilityService,
    ) {
        session.screenshotInFlight = true
        val requested = runCatching {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val target =
                            decodeTarget(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace,
                            )
                        screenshot.hardwareBuffer.close()
                        main.post { onFrameReady(session, service, target) }
                    }

                    override fun onFailure(errorCode: Int) {
                        main.post {
                            if (autoOpenSession !== session) return@post
                            session.screenshotInFlight = false
                            Log.w(TAG, "video-search frame capture failed code=$errorCode")
                            schedulePoll(session, AUTO_OPEN_POLL_INTERVAL_MS)
                        }
                    }
                },
            )
        }.isSuccess
        if (!requested) {
            session.screenshotInFlight = false
            schedulePoll(session, AUTO_OPEN_POLL_INTERVAL_MS)
        }
    }

    private fun decodeTarget(
        buffer: android.hardware.HardwareBuffer,
        colorSpace: android.graphics.ColorSpace?,
    ): VideoSearchTapTarget? {
        val bitmap =
            runCatching {
                val hardware = Bitmap.wrapHardwareBuffer(buffer, colorSpace) ?: return@runCatching null
                hardware.copy(Bitmap.Config.ARGB_8888, false)
            }.getOrNull() ?: return null
        return try {
            VideoSearchFrameDetector.detectFirstResult(
                width = bitmap.width,
                height = bitmap.height,
                pixels = VideoSearchPixelSource(bitmap::getPixel),
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun onFrameReady(
        session: AutoOpenSession,
        service: AccessibilityService,
        target: VideoSearchTapTarget?,
    ) {
        if (autoOpenSession !== session) return
        session.screenshotInFlight = false
        if (target == null) {
            if (session.attempts > 0) {
                Log.i(
                    TAG,
                    "video-search automatic-open reached detail attempts=${session.attempts}",
                )
                clearAutoOpen()
            } else {
                schedulePoll(session, AUTO_OPEN_POLL_INTERVAL_MS)
            }
            return
        }
        if (session.attempts >= MAX_AUTO_OPEN_ATTEMPTS) {
            Log.w(TAG, "video-search automatic-open stopped reason=still-on-results")
            clearAutoOpen()
            return
        }
        session.attempts++
        Log.i(
            TAG,
            "video-search automatic-open dpad attempt=${session.attempts} " +
                "queryChars=${session.queryLength}",
        )
        startDpadSequence(session, service)
    }

    private fun startDpadSequence(
        session: AutoOpenSession,
        service: AccessibilityService,
    ) {
        session.stage = DpadStage.FOCUS_INPUT
        session.keyboardChecks = 0
        main.postDelayed({ performDpadStage(session, service) }, 0L)
    }

    private fun performDpadStage(
        session: AutoOpenSession,
        service: AccessibilityService,
    ) {
        if (autoOpenSession !== session) return
        when (session.stage) {
            DpadStage.FOCUS_INPUT -> {
                if (!performAction(service, AccessibilityService.GLOBAL_ACTION_DPAD_DOWN)) {
                    stopForActionFailure(session, "focus-input")
                    return
                }
                session.stage = DpadStage.WAIT_FOR_KEYBOARD
                scheduleDpadStage(session, service, DPAD_STEP_DELAY_MS)
            }
            DpadStage.WAIT_FOR_KEYBOARD -> {
                if (hasVisibleInputMethod(service)) {
                    if (!performAction(service, AccessibilityService.GLOBAL_ACTION_BACK)) {
                        stopForActionFailure(session, "dismiss-keyboard")
                        return
                    }
                    session.stage = DpadStage.DISMISS_KEYBOARD
                    scheduleDpadStage(session, service, DPAD_STEP_DELAY_MS)
                } else if (session.keyboardChecks < MAX_KEYBOARD_CHECKS) {
                    session.keyboardChecks++
                    scheduleDpadStage(session, service, KEYBOARD_CHECK_INTERVAL_MS)
                } else {
                    Log.w(
                        TAG,
                        "video-search automatic-open stopped reason=keyboard-not-visible",
                    )
                    clearAutoOpen()
                }
            }
            DpadStage.DISMISS_KEYBOARD -> {
                if (hasVisibleInputMethod(service)) {
                    Log.w(
                        TAG,
                        "video-search automatic-open stopped reason=keyboard-still-visible",
                    )
                    clearAutoOpen()
                    return
                }
                if (!performAction(service, AccessibilityService.GLOBAL_ACTION_DPAD_DOWN)) {
                    stopForActionFailure(session, "focus-category")
                    return
                }
                session.stage = DpadStage.FOCUS_FIRST_RESULT
                scheduleDpadStage(session, service, DPAD_LIST_STEP_DELAY_MS)
            }
            DpadStage.FOCUS_FIRST_RESULT -> {
                if (!performAction(service, AccessibilityService.GLOBAL_ACTION_DPAD_DOWN)) {
                    stopForActionFailure(session, "focus-first-result")
                    return
                }
                session.stage = DpadStage.ACTIVATE_RESULT
                scheduleDpadStage(session, service, DPAD_LIST_STEP_DELAY_MS)
            }
            DpadStage.ACTIVATE_RESULT -> {
                if (!performAction(service, AccessibilityService.GLOBAL_ACTION_DPAD_CENTER)) {
                    stopForActionFailure(session, "activate-result")
                    return
                }
                session.stage = null
                schedulePoll(session, AUTO_OPEN_VERIFY_DELAY_MS)
            }
            null -> Unit
        }
    }

    private fun scheduleDpadStage(
        session: AutoOpenSession,
        service: AccessibilityService,
        delayMs: Long,
    ) {
        main.postDelayed({ performDpadStage(session, service) }, delayMs)
    }

    private fun performAction(
        service: AccessibilityService,
        action: Int,
    ): Boolean = runCatching { service.performGlobalAction(action) }.getOrDefault(false)

    private fun stopForActionFailure(
        session: AutoOpenSession,
        stage: String,
    ) {
        Log.w(TAG, "video-search automatic-open stopped reason=action-denied stage=$stage")
        clearAutoOpen()
    }

    private fun hasVisibleInputMethod(service: AccessibilityService): Boolean =
        runCatching {
            service.windows.any { window ->
                if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    return@any false
                }
                val bounds = Rect()
                window.getBoundsInScreen(bounds)
                bounds.width() > 0 && bounds.height() > 0
            }
        }.getOrDefault(false)

    private fun isVideoProForeground(service: AccessibilityService): Boolean =
        runCatching {
            val activePackage =
                service.windows
                    .firstOrNull { it.isActive }
                    ?.root
                    ?.packageName
                    ?.toString()
                    ?: service.rootInActiveWindow?.packageName?.toString()
            activePackage == VIDEO_PRO_PACKAGE
        }.getOrDefault(false)

    private fun clearAutoOpen() {
        autoOpenSession = null
    }

    private fun intentFor(
        query: String,
        openFirstResult: Boolean,
    ): Intent =
        Intent(ACTION_OPEN_SEARCH)
            .setComponent(ComponentName(VIDEO_PRO_PACKAGE, VOICE_SEARCH_ACTIVITY))
            .putExtra(EXTRA_QUERY, query)
            .putExtra(EXTRA_OPEN_FIRST_RESULT, openFirstResult)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun launch(
        context: Context,
        intent: Intent,
    ): Boolean =
        try {
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
