package com.dongpiandi.voicehelper

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.LineHeightSpan
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A one-turn, button-free assistant island over the app that is already on screen.
 *
 * The island uses a prewarmed, cached tinted-glass surface so the first visible frame does not
 * wait for a window mount or full-screen capture. Only the visible voice wave redraws, at a
 * capped frame rate; idle and static answer states remain paused.
 */
internal class VoiceAssistantOverlay(
    private val service: AccessibilityService,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val screenCaptureExecutor =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "voice-helper-screen-blur").apply { isDaemon = true }
        }
    private var root: MistGlassIslandView? = null
    private var rootHasAttached = false
    private var screenBackdrop: ScreenBackdropView? = null
    private var screenBackdropAnimator: ValueAnimator? = null
    private var screenCaptureGeneration = 0
    private var screenBlurActive = false
    private var exitAnimator: Animator? = null
    private var visible = false
    private var currentQuestion = ""
    private var mode = OverlayMode.ASSISTANT

    private var requestedState = VisualState.LISTENING
    private var requestedQuestion = ""
    private var requestedAnswer = ""

    private var requestedAt = 0L
    private var attachRequest = 0

    val isShowing: Boolean
        get() = visible && root?.isAttachedToWindow == true

    fun prewarm() {
        // Keep the assistant backdrop mount warm without capturing or showing it. Dictation only
        // needs the island and leaves this transparent layer at alpha zero.
        ensureScreenBackdrop()
        if (liveRoot() != null) return
        ensureView(reveal = false)
    }

    fun showListening() {
        mode = OverlayMode.ASSISTANT
        beginScreenBlurSession()
        val wasVisible = visible
        visible = true
        currentQuestion = ""
        requestedState = VisualState.LISTENING
        requestedQuestion = ""
        requestedAnswer = ""
        cancelPendingExit()
        val view = liveRoot()
        if (view != null) {
            view.prepareForShow()
            view.showListening()
            if (!wasVisible) view.playEntrance()
        } else {
            ensureView(reveal = true)
        }
    }

    fun beginDictationListening() {
        mode = OverlayMode.DICTATION
        // Dictation shares the voice island but never dims, blurs, or captures the screen.
        endScreenBlurSession(immediate = true)
        val wasVisible = visible
        visible = true
        currentQuestion = ""
        requestedState = VisualState.DICTATION_LISTENING
        requestedQuestion = ""
        requestedAnswer = ""
        cancelPendingExit()
        val view = liveRoot()
        if (view != null) {
            view.prepareForShow()
            view.showDictationListening()
            if (!wasVisible) view.playEntrance()
        } else {
            ensureView(reveal = true)
        }
    }

    fun updateDictationTranscript(text: String) {
        if (mode != OverlayMode.DICTATION) return
        val transcript = text.trim()
        if (transcript.isEmpty()) return
        currentQuestion = transcript
        requestedState = VisualState.DICTATION_TRANSCRIPT
        requestedQuestion = transcript
        requestedAnswer = ""
        val view = liveRoot()
        if (view != null) {
            revealIfNeeded(view)
            view.showDictationTranscript(transcript)
        } else {
            ensureView(reveal = true)
        }
    }

    fun endDictation() {
        dismiss()
    }

    fun updateTranscript(text: String) {
        if (mode == OverlayMode.DICTATION) {
            updateDictationTranscript(text)
            return
        }
        val question = text.trim()
        if (question.isEmpty()) return
        beginScreenBlurSession()
        currentQuestion = question
        requestedState = VisualState.TRANSCRIPT
        requestedQuestion = question
        requestedAnswer = ""
        val view = liveRoot()
        if (view != null) {
            revealIfNeeded(view)
            view.showListeningTranscript(question)
        } else {
            ensureView(reveal = true)
        }
    }

    fun showThinking(transcript: String) {
        if (mode != OverlayMode.ASSISTANT) return
        val question = transcript.trim()
        beginScreenBlurSession()
        requestedState = VisualState.THINKING
        requestedAnswer = ""
        if (question.isEmpty()) {
            requestedQuestion = currentQuestion
            val view = liveRoot()
            if (view != null) {
                revealIfNeeded(view)
                view.waitForResult()
            } else {
                ensureView(reveal = true)
            }
            return
        }
        currentQuestion = question
        requestedQuestion = question
        val view = liveRoot()
        if (view != null) {
            revealIfNeeded(view)
            view.showThinking(question)
        } else {
            ensureView(reveal = true)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun showAnswer(
        question: String,
        answer: String,
        model: String,
        usedWebSearch: Boolean,
    ) {
        if (mode != OverlayMode.ASSISTANT) return
        beginScreenBlurSession()
        val resolvedQuestion = question.trim().ifBlank { currentQuestion }
        currentQuestion = resolvedQuestion
        requestedState = VisualState.ANSWER
        requestedQuestion = resolvedQuestion
        requestedAnswer = answer.trim()
        val view = liveRoot()
        if (view != null) {
            revealIfNeeded(view)
            view.showAnswer(resolvedQuestion, requestedAnswer)
        } else {
            ensureView(reveal = true)
        }
    }

    fun showError(message: String) {
        if (mode != OverlayMode.ASSISTANT) return
        beginScreenBlurSession()
        val question = currentQuestion.trim()
        requestedState = VisualState.ERROR
        requestedQuestion = question
        requestedAnswer = message.trim()
        val view = liveRoot()
        if (view != null) {
            revealIfNeeded(view)
            view.showError(question, requestedAnswer)
        } else {
            ensureView(reveal = true)
        }
    }

    fun dismiss() {
        attachRequest++
        if (!visible) return
        visible = false
        endScreenBlurSession()
        val view = liveRoot() ?: return
        currentQuestion = ""
        exitAnimator?.cancel()
        exitAnimator =
            view.createExitAnimator(
                onEnd = {
                    exitAnimator = null
                    if (root === view && !visible) {
                        view.finishHidden()
                    }
                },
            ).also { it.start() }
    }

    fun close() {
        attachRequest++
        visible = false
        endScreenBlurSession(immediate = true)
        cancelPendingExit()
        val view = root
        root = null
        rootHasAttached = false
        currentQuestion = ""
        mode = OverlayMode.ASSISTANT
        if (view != null) {
            runCatching { view.stopEverything() }
            runCatching { windowManager?.removeView(view) }
            view.releaseBackdrop()
        }
        releaseScreenBackdrop()
        screenCaptureExecutor.shutdownNow()
    }

    private fun beginScreenBlurSession() {
        if (screenBlurActive) return
        screenBlurActive = true
        ensureScreenBackdrop()
        captureScreenBackdrop()
        animateScreenBackdropTo(1f, BACKDROP_ENTER_MS)
    }

    private fun endScreenBlurSession(immediate: Boolean = false) {
        if (!screenBlurActive && !immediate) return
        screenBlurActive = false
        screenCaptureGeneration++
        animateScreenBackdropTo(
            target = 0f,
            duration = if (immediate) 0L else BACKDROP_EXIT_MS,
            clearWhenFinished = true,
        )
    }

    private fun ensureScreenBackdrop() {
        if (screenBackdrop != null) return
        val manager = windowManager ?: return
        val metrics = service.resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val view = ScreenBackdropView(service, width, height)
        view.alpha = 0f
        view.visibility = View.VISIBLE
        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
        runCatching {
            manager.addView(view, params)
            screenBackdrop = view
        }.onFailure { error ->
            Log.w(TAG, "screen backdrop attach failed: ${error.javaClass.simpleName}")
            runCatching { manager.removeView(view) }
            view.clearBackdrop()
        }
    }

    private fun captureScreenBackdrop() {
        val generation = ++screenCaptureGeneration
        val startedAt = SystemClock.uptimeMillis()
        runCatching {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenCaptureExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        val software =
                            runCatching decodeBackdrop@{
                                val hardwareBitmap =
                                    Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                        ?: return@decodeBackdrop null
                                val copy =
                                    hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                        ?: return@decodeBackdrop null
                                scaleBackdrop(copy)
                            }.getOrNull()
                        buffer.close()
                        if (software == null) {
                            Log.w(TAG, "screen backdrop decode failed")
                            return
                        }
                        main.post {
                            if (generation != screenCaptureGeneration || !screenBlurActive) {
                                software.recycle()
                                return@post
                            }
                            val view = screenBackdrop
                            if (view == null) {
                                software.recycle()
                                return@post
                            }
                            view.setBackdrop(software)
                            Log.i(
                                TAG,
                                "screen backdrop ready captureMs=" +
                                    "${SystemClock.uptimeMillis() - startedAt} " +
                                    "size=${software.width}x${software.height}",
                            )
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "screen backdrop capture failed code=$errorCode")
                    }
                },
            )
        }.onFailure { error ->
            Log.w(TAG, "screen backdrop request failed: ${error.javaClass.simpleName}")
        }
    }

    private fun scaleBackdrop(source: Bitmap): Bitmap {
        val longestSide = max(source.width, source.height)
        if (longestSide <= MAX_BLUR_SOURCE_WIDTH) return source
        val scale = MAX_BLUR_SOURCE_WIDTH.toFloat() / longestSide
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        source.recycle()
        return scaled
    }

    private fun animateScreenBackdropTo(
        target: Float,
        duration: Long,
        clearWhenFinished: Boolean = false,
    ) {
        val view = screenBackdrop ?: return
        screenBackdropAnimator?.cancel()
        val start = view.alpha
        if (duration <= 0L || !ValueAnimator.areAnimatorsEnabled()) {
            view.alpha = target
            if (clearWhenFinished && target <= 0.001f) view.clearBackdrop()
            return
        }
        screenBackdropAnimator =
            ValueAnimator.ofFloat(start, target).apply {
                this.duration = duration
                interpolator = LinearInterpolator()
                addUpdateListener { animation ->
                    view.alpha = animation.animatedValue as Float
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            screenBackdropAnimator = null
                            if (clearWhenFinished && target <= 0.001f) {
                                view.clearBackdrop()
                            }
                        }
                    },
                )
                start()
            }
    }

    private fun releaseScreenBackdrop() {
        screenCaptureGeneration++
        screenBackdropAnimator?.cancel()
        screenBackdropAnimator = null
        val view = screenBackdrop ?: return
        screenBackdrop = null
        runCatching { windowManager?.removeView(view) }
        view.clearBackdrop()
    }

    private fun cancelPendingExit() {
        exitAnimator?.cancel()
        exitAnimator = null
    }

    private fun liveRoot(): MistGlassIslandView? {
        val view = root ?: return null
        // addView() can expose a root one frame before its first window attach.
        // Until that attach has happened, the root is valid and must not be replaced.
        if (view.isAttachedToWindow || !rootHasAttached) return view
        root = null
        rootHasAttached = false
        visible = false
        cancelPendingExit()
        view.releaseBackdrop()
        return null
    }

    private fun revealIfNeeded(view: MistGlassIslandView) {
        if (visible) return
        visible = true
        cancelPendingExit()
        view.prepareForShow()
        view.playEntrance()
    }

    private fun ensureView(reveal: Boolean) {
        if (root != null) return
        val metrics = service.resources.displayMetrics
        val spec = MistMetrics.forScreen(metrics.widthPixels, metrics.heightPixels)
        requestedAt = SystemClock.uptimeMillis()
        val request = ++attachRequest
        attachView(spec, null, request, attempt = 0, reveal = reveal)
    }

    private fun attachView(
        spec: MistMetrics,
        backdrop: Bitmap?,
        request: Int,
        attempt: Int,
        reveal: Boolean,
    ) {
        if (request != attachRequest) {
            backdrop?.recycle()
            return
        }
        if (root != null) {
            backdrop?.recycle()
            return
        }
        val manager = windowManager
        if (manager == null) {
            backdrop?.recycle()
            return
        }

        val stageWidth = spec.panelWidth + spec.windowSidePadding * 2f
        val stageHeight =
            spec.maxAnswerHeight + spec.windowTopPadding + spec.windowBottomPadding
        val originX = (spec.screenWidth - stageWidth) / 2f
        val windowTop =
            spec.screenHeight - spec.panelBottom - spec.maxAnswerHeight - spec.windowTopPadding
        val windowY = (spec.screenHeight - windowTop - stageHeight).roundToInt()

        val view =
            MistGlassIslandView(
                context = service,
                metrics = spec,
                screenOriginX = originX,
                screenOriginY = windowTop,
                backdrop = backdrop,
            )
        view.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(attachedView: View) {
                    if (root === attachedView) rootHasAttached = true
                }

                override fun onViewDetachedFromWindow(detachedView: View) {
                    if (root !== detachedView) return
                    root = null
                    rootHasAttached = false
                    visible = false
                    cancelPendingExit()
                    runCatching { view.releaseBackdrop() }
                }
            },
        )
        if (!reveal) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
        }
        val params =
            WindowManager.LayoutParams(
                stageWidth.roundToInt(),
                stageHeight.roundToInt(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = windowY
            }

        rootHasAttached = false
        runCatching {
            manager.addView(view, params)
            root = view
            visible = reveal
            Log.i(
                TAG,
                "overlay attached attachMs=${SystemClock.uptimeMillis() - requestedAt} " +
                    "attempt=$attempt reveal=$reveal",
            )
            if (reveal) {
                view.post {
                    if (root === view && visible) {
                        view.prepareForShow()
                        view.playEntrance()
                        applyRequestedState(view)
                    }
                }
            }
        }.getOrElse { error ->
            Log.w(
                TAG,
                "overlay attach failed attempt=$attempt: ${error.javaClass.simpleName}",
            )
            runCatching { manager.removeView(view) }
            if (attempt < MAX_ATTACH_ATTEMPTS) {
                main.postDelayed(
                    {
                        attachView(spec, backdrop, request, attempt + 1, reveal)
                    },
                    ATTACH_RETRY_DELAY_MS,
                )
            } else {
                if (request == attachRequest) visible = false
                view.releaseBackdrop()
            }
        }
    }

    private fun applyRequestedState(view: MistGlassIslandView) {
        when (requestedState) {
            VisualState.LISTENING -> view.showListening()
            VisualState.TRANSCRIPT -> view.showListeningTranscript(requestedQuestion)
            VisualState.DICTATION_LISTENING -> view.showDictationListening()
            VisualState.DICTATION_TRANSCRIPT ->
                view.showDictationTranscript(requestedQuestion)
            VisualState.THINKING -> view.showThinking(requestedQuestion)
            VisualState.ANSWER -> view.showAnswer(requestedQuestion, requestedAnswer)
            VisualState.ERROR -> view.showError(requestedQuestion, requestedAnswer)
        }
    }

    private enum class OverlayMode {
        ASSISTANT,
        DICTATION,
    }

    private enum class VisualState {
        LISTENING,
        TRANSCRIPT,
        DICTATION_LISTENING,
        DICTATION_TRANSCRIPT,
        THINKING,
        ANSWER,
        ERROR,
    }

    private companion object {
        const val TAG = "VoiceHelperOverlay"
        const val MAX_ATTACH_ATTEMPTS = 1
        const val ATTACH_RETRY_DELAY_MS = 64L
        const val MAX_BLUR_SOURCE_WIDTH = 960
        const val BACKDROP_ENTER_MS = 150L
        const val BACKDROP_EXIT_MS = 220L
    }
}

internal data class MistMetrics(
    val screenWidth: Int,
    val screenHeight: Int,
    val scale: Float,
    val panelWidth: Float,
    val basePanelHeight: Float,
    val maxPanelHeight: Float,
    val maxAnswerHeight: Float,
    val islandGap: Float,
    val panelBottom: Float,
    val panelRadius: Float,
    val capsuleWidth: Float,
    val capsuleHeight: Float,
    val contentSidePadding: Float,
    val contentTopPadding: Float,
    val contentBottomPadding: Float,
    val replyTopPadding: Float,
    val questionSize: Float,
    val answerSize: Float,
    val questionMaxWidth: Float,
    val answerMaxWidth: Float,
    val windowSidePadding: Float,
    val windowTopPadding: Float,
    val windowBottomPadding: Float,
) {
    companion object {
        fun forScreen(
            width: Int,
            height: Int,
        ): MistMetrics {
            val screenWidth = width.coerceAtLeast(1)
            val screenHeight = height.coerceAtLeast(1)
            val scale =
                min(
                    screenWidth / PROJECTOR_WIDTH,
                    screenHeight / PROJECTOR_HEIGHT,
                ).coerceAtLeast(0.1f)
            val capsuleWidth = 520f * scale
            val capsuleHeight = 78f * scale
            val panelWidth = 1040f * scale
            val panelHeight = 210f * scale
            val islandGap = 14f * scale
            val panelBottom = 95.04f * scale
            val radius = 30f * scale
            val shadowPadding = 144f * scale
            val contentSide = 38f * scale
            val contentTop = 28f * scale
            val contentBottom = 28f * scale
            val replyTop = 12f * scale
            val questionSize = 19f * scale
            val answerSize = 30f * scale
            val windowTopPadding = capsuleHeight + islandGap + shadowPadding
            val maxAnswerHeight =
                (screenHeight - panelBottom - windowTopPadding)
                    .coerceAtLeast(panelHeight)

            return MistMetrics(
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                scale = scale,
                panelWidth = panelWidth,
                basePanelHeight = capsuleHeight,
                maxPanelHeight = panelHeight,
                maxAnswerHeight = maxAnswerHeight,
                islandGap = islandGap,
                panelBottom = panelBottom,
                panelRadius = radius,
                capsuleWidth = capsuleWidth,
                capsuleHeight = capsuleHeight,
                contentSidePadding = contentSide,
                contentTopPadding = contentTop,
                contentBottomPadding = contentBottom,
                replyTopPadding = replyTop,
                questionSize = questionSize,
                answerSize = answerSize,
                questionMaxWidth = panelWidth - contentSide * 2f,
                answerMaxWidth = panelWidth - contentSide * 2f,
                windowSidePadding = shadowPadding,
                windowTopPadding = windowTopPadding,
                windowBottomPadding = panelBottom,
            )
        }

        private const val PROJECTOR_WIDTH = 1920f
        private const val PROJECTOR_HEIGHT = 1080f
    }
}

@SuppressLint("ViewConstructor")
private class MistGlassIslandView(
    context: Context,
    private val metrics: MistMetrics,
    private val screenOriginX: Float,
    private val screenOriginY: Float,
    private val backdrop: Bitmap?,
) : View(context) {
    private val main = Handler(Looper.getMainLooper())
    private val panelWidth = metrics.panelWidth
    private val basePanelHeight = metrics.basePanelHeight
    private val maxPanelHeight = metrics.maxPanelHeight
    private val maxAnswerHeight = metrics.maxAnswerHeight
    private val capsuleWidth = metrics.capsuleWidth
    private val capsuleHeight = metrics.capsuleHeight
    private val panelRadius = metrics.panelRadius
    private val contentSidePadding = metrics.contentSidePadding
    private val contentTopPadding = metrics.contentTopPadding
    private val contentBottomPadding = metrics.contentBottomPadding
    private val replyTopPadding = metrics.replyTopPadding
    private val windowBottomPadding = metrics.windowBottomPadding
    private val questionLineHeight = metrics.questionSize * 1.18f
    private val answerLineHeight = metrics.answerSize * 1.48f
    private val questionWidth =
        min(metrics.questionMaxWidth, panelWidth - contentSidePadding * 2f)
            .coerceAtLeast(1f)
    private val answerWidth =
        min(metrics.answerMaxWidth, panelWidth - contentSidePadding * 2f)
            .coerceAtLeast(1f)
    private val minQuestionReplyGap = max(14f, metrics.questionSize * 0.32f)

    private val slowOut = PathInterpolator(0.17f, 0.86f, 0.18f, 1f)
    private val softOut = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private val cssEase = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)

    private val currentBounds = RectF()
    private val clipPath = Path()
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backdropPaint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
            alpha = (255f * CARD_OPACITY).roundToInt()
        }
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val radialPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linearPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frostPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val questionPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = metrics.questionSize
            color = QUESTION_COLOR
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = -0.052f
            isSubpixelText = true
            setShadowLayer(2f, 0f, 1f, Color.argb(46, 0, 0, 0))
        }
    private val answerPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = metrics.answerSize
            color = ANSWER_COLOR
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            letterSpacing = -0.022f
            isSubpixelText = true
        }
    private val answerLeadTypeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val answerLeadStyle = AnswerLeadStyle(answerLeadTypeface)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val insetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val wavePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
    private val waveGlowPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
    private val waveBarBounds = RectF()
    private val chromePaint =
        Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val chromeDestination = RectF()
    private val waveInvalidationBounds = Rect()
    private var backdropEffect: RenderEffect? = null
    private var backdropNode: RenderNode? = null
    private var chromeBitmap: Bitmap? = null
    private var retiredChromeBitmap: Bitmap? = null
    private var chromeBitmapWidth = 0
    private var chromeBitmapHeight = 0
    private var chromeBitmapRadius = 0f
    private var listeningStartedAt = SystemClock.uptimeMillis()
    private var waveActive = false
    private var waitingForAnswer = false
    private var openScheduled = false
    private var questionScheduled = false
    private var questionVisible = false
    private var isOpen = false
    private var openStartedAt = 0L
    private var posedPanelHeight = basePanelHeight
    private var desiredPanelHeight = basePanelHeight

    private var widthExpansion = 0f
    private var heightExpansion = 0f
    private var radiusExpansion = 0f
    private var shadowExpansion = 0f
    private var waveAnimator: ValueAnimator? = null
    private var expansionAnimator: ValueAnimator? = null
    private var waveAlpha = 1f
    private var waveScale = 1f
    private var conversationAlpha = 0f
    private var conversationAnimator: ValueAnimator? = null
    private var answerProgress = 0f
    private var answerAnimator: ValueAnimator? = null
    private var answerLayout: StaticLayout? = null
    private var answerText = ""
    private var answerVisible = false
    private var questionText = ""
    private var questionLayout: StaticLayout? = null
    private val questionLines = mutableListOf<QuestionLine>()
    private val lineAnimators = mutableListOf<Animator>()
    private var entranceAnimator: ValueAnimator? = null
    private var entranceTranslation = 0f
    private var entranceScale = 1f
    private var exitProgress = 0f
    private var exitContentAlpha = 1f
    private var exitContentOffset = 0f
    private var attached = false
    private var waveTickerRunning = false
    private var firstDrawLogged = false
    private var revealRequestedAt = 0L
    private var revealFrameLogged = false

    private val openRunnable =
        Runnable {
            if (!isAttachedToWindow) return@Runnable
            startOpening()
        }
    private val questionRunnable =
        Runnable {
            if (!isAttachedToWindow) return@Runnable
            revealQuestion()
        }
    private val waveTicker =
        object : Runnable {
            override fun run() {
                if (!shouldAnimateWave()) {
                    waveTickerRunning = false
                    return
                }
                invalidateWaveArea()
                main.postDelayed(this, WAVE_FRAME_MS)
            }
        }

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        isLongClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)

        backdrop?.let {
            val colorMatrix =
                ColorMatrix().apply {
                    setSaturation(0.82f)
                    postConcat(
                        ColorMatrix().apply {
                            setScale(0.86f, 0.86f, 0.86f, 1f)
                        },
                    )
                }
            val blur =
                RenderEffect.createBlurEffect(
                    34f,
                    34f,
                    Shader.TileMode.CLAMP,
                )
            backdropEffect =
                RenderEffect.createColorFilterEffect(
                    ColorMatrixColorFilter(colorMatrix),
                    blur,
                )
            backdropNode = createBackdropNode(it, backdropEffect!!)
        }
    }

    fun playEntrance() {
        if (!animationsEnabled()) {
            alpha = 1f
            entranceTranslation = 0f
            entranceScale = 1f
            setRenderEffect(null)
            invalidate()
            return
        }
        entranceAnimator?.cancel()
        alpha = 0f
        entranceTranslation = 24f
        entranceScale = 0.94f
        setRenderEffect(null)

        entranceAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ENTRANCE_DURATION_MS
                startDelay = 0L
                interpolator = LinearInterpolator()
                addUpdateListener { animation ->
                    val progress = animation.animatedValue as Float
                    val eased = slowOut.getInterpolation(progress)
                    val alphaProgress =
                        if (progress <= 0.65f) {
                            slowOut.getInterpolation(progress / 0.65f)
                        } else {
                            1f
                        }
                    alpha = alphaProgress
                    entranceTranslation = 24f * (1f - eased)
                    entranceScale = 0.94f + 0.06f * eased
                    invalidate()
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            alpha = 1f
                            entranceTranslation = 0f
                            entranceScale = 1f
                            setRenderEffect(null)
                            invalidate()
                        }
                    },
                )
                start()
            }
    }

    fun showListening() {
        cancelTimelines()
        cancelTextAnimations()
        waitingForAnswer = false
        waveActive = true
        clearAnswer(immediate = true)
        setQuestionText("")
        questionVisible = false
        conversationAnimator?.cancel()
        conversationAlpha = 0f
        isOpen = false
        openStartedAt = 0L
        listeningStartedAt = SystemClock.uptimeMillis()
        posedPanelHeight = basePanelHeight
        desiredPanelHeight = basePanelHeight
        widthExpansion = 0f
        heightExpansion = 0f
        radiusExpansion = 0f
        shadowExpansion = 0f
        waveAnimator?.cancel()
        waveAlpha = 1f
        waveScale = 1f
        startWaveTicker()
        invalidate()
    }

    fun showListeningTranscript(text: String) {
        waitingForAnswer = false
        waveActive = true
        setQuestionText(text)
        clearAnswer(immediate = true)
        startWaveTicker()
        invalidate()
    }

    fun showDictationListening() {
        showListening()
    }

    fun showDictationTranscript(text: String) {
        showListeningTranscript(text)
    }

    fun showQuestion(text: String) {
        showListeningTranscript(text)
    }

    fun showThinking(text: String) {
        waitingForAnswer = true
        waveActive = true
        listeningStartedAt = SystemClock.uptimeMillis()
        waveAnimator?.cancel()
        waveAnimator = null
        waveAlpha = 1f
        waveScale = 1f
        setQuestionText(text)
        clearAnswer(immediate = false)
        startWaveTicker()
        invalidate()
    }

    fun showAnswer(
        question: String,
        answer: String,
    ) {
        if (question.isNotBlank()) setQuestionText(question)
        setAnswerText(answer)
        startAnswerReveal()
        waitingForAnswer = false
        waveActive = false
        stopWaveTicker()
        fadeWaveOut()
        invalidate()
    }

    fun showError(
        question: String,
        message: String,
    ) {
        if (question.isNotBlank()) {
            setQuestionText(question)
        } else {
            setQuestionText(message)
        }
        if (question.isNotBlank()) {
            setAnswerText(message)
            startAnswerReveal()
        } else {
            clearAnswer(immediate = true)
        }
        waitingForAnswer = false
        waveActive = false
        stopWaveTicker()
        fadeWaveOut()
        invalidate()
    }

    fun waitForResult() {
        waitingForAnswer = true
        waveActive = true
        listeningStartedAt = SystemClock.uptimeMillis()
        waveAnimator?.cancel()
        waveAnimator = null
        waveAlpha = 1f
        waveScale = 1f
        startWaveTicker()
        invalidate()
    }

    fun createExitAnimator(onEnd: () -> Unit): Animator {
        cancelTimelines()
        stopWaveAnimation()
        entranceAnimator?.cancel()
        entranceAnimator = null
        alpha = 1f
        setRenderEffect(null)
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (animationsEnabled()) EXIT_DURATION_MS else 0L
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                exitProgress = animation.animatedValue as Float
                val contentProgress =
                    cssEase.getInterpolation(
                        (exitProgress * EXIT_DURATION_MS / EXIT_CONTENT_DURATION_MS)
                            .coerceIn(0f, 1f),
                    )
                exitContentAlpha = 1f - contentProgress
                exitContentOffset = 8f * contentProgress
                val eased = cssEase.getInterpolation(exitProgress)
                alpha = 1f - eased
                entranceTranslation = 20f * eased
                entranceScale = 1f - 0.06f * eased
                invalidate()
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        onEnd()
                    }
                },
            )
        }
    }

    fun stopEverything() {
        attached = false
        main.removeCallbacks(openRunnable)
        main.removeCallbacks(questionRunnable)
        cancelTimelines()
        cancelTextAnimations()
        clearAnswer(immediate = true)
        entranceAnimator?.cancel()
        entranceAnimator = null
        stopWaveAnimation()
        setRenderEffect(null)
    }

    fun prepareForShow() {
        attached = true
        revealRequestedAt = SystemClock.uptimeMillis()
        revealFrameLogged = false
        visibility = View.VISIBLE
        alpha = 1f
        entranceTranslation = 0f
        entranceScale = 1f
        exitProgress = 0f
        exitContentAlpha = 1f
        exitContentOffset = 0f
    }

    fun finishHidden() {
        stopEverything()
        alpha = 0f
        visibility = View.VISIBLE
    }

    fun releaseBackdrop() {
        backdropPaint.shader = null
        backdropEffect = null
        backdropNode?.discardDisplayList()
        backdropNode = null
        chromeBitmap?.recycle()
        chromeBitmap = null
        retiredChromeBitmap?.recycle()
        retiredChromeBitmap = null
        if (backdrop != null && !backdrop.isRecycled) backdrop.recycle()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        startWaveTicker()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        attached = false
        stopEverything()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!attached || width <= 0 || height <= 0) return
        if (!firstDrawLogged) {
            firstDrawLogged = true
            Log.i("VoiceHelperOverlay", "first frame drawn ${width}x$height")
        }
        if (revealRequestedAt > 0L && !revealFrameLogged) {
            revealFrameLogged = true
            Log.i(
                "VoiceHelperOverlay",
                "visible frame drawn revealMs=${SystemClock.uptimeMillis() - revealRequestedAt}",
            )
        }
        updateCurrentBounds()
        if (currentBounds.width() < 1f || currentBounds.height() < 1f) return

        val save = canvas.save()
        canvas.translate(0f, entranceTranslation)
        canvas.scale(
            entranceScale,
            entranceScale,
            currentBounds.centerX(),
            currentBounds.centerY(),
        )

        drawKitIsland(canvas)
        canvas.restoreToCount(save)
    }

    private val referencePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val referenceText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(248, 248, 250)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private var referenceAnswerLayout: StaticLayout? = null
    private var referenceAnswerKey = ""

    private fun drawKitIsland(canvas: Canvas) {
        val b = currentBounds
        if (answerText.isNotBlank()) {
            drawAnswerCard(canvas, b)
            val islandBounds =
                RectF(
                    b.centerX() - capsuleWidth / 2f,
                    b.top - metrics.islandGap - capsuleHeight,
                    b.centerX() + capsuleWidth / 2f,
                    b.top - metrics.islandGap,
                )
            drawVoiceIsland(
                canvas = canvas,
                bounds = islandBounds,
                transcript = questionText,
                animateWave = false,
            )
        } else {
            drawVoiceIsland(
                canvas = canvas,
                bounds = b,
                transcript = questionText,
                animateWave = waveActive,
            )
        }
    }

    private fun drawVoiceIsland(
        canvas: Canvas,
        bounds: RectF,
        transcript: String,
        animateWave: Boolean,
    ) {
        val unit = bounds.width() / 520f
        if (unit <= 0f || bounds.height() <= 0f) return
        drawReferenceGlass(canvas, bounds, bounds.height() / 2f)

        val cy = bounds.centerY()
        val micX = bounds.left + 44f * unit
        val breathing =
            if (animateWave && animationsEnabled()) {
                1f + 0.025f * sin((SystemClock.uptimeMillis() - listeningStartedAt) * 0.0035f)
            } else {
                1f
            }
        val micSave = canvas.save()
        canvas.scale(breathing, breathing, micX, cy)
        drawReferenceButton(canvas, micX, cy, 23.5f * unit)
        if (animateWave) {
            drawCompactMicWave(canvas, micX, cy, unit)
        } else {
            drawCompactMic(canvas, micX, cy, unit)
        }
        canvas.restoreToCount(micSave)

        val clip = canvas.save()
        val glassClip =
            Path().apply {
                addRoundRect(
                    bounds,
                    bounds.height() / 2f,
                    bounds.height() / 2f,
                    Path.Direction.CW,
                )
            }
        canvas.clipPath(glassClip)
        if (transcript.isNotBlank()) {
            drawIslandTranscript(canvas, bounds, transcript, unit)
        }
        canvas.restoreToCount(clip)
    }

    private fun drawCompactMic(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        unit: Float,
    ) {
        referencePaint.shader = null
        referencePaint.color = Color.rgb(251, 251, 253)
        referencePaint.style = Paint.Style.FILL
        canvas.drawRoundRect(
            cx - 7f * unit,
            cy - 17f * unit,
            cx + 7f * unit,
            cy + 5f * unit,
            7f * unit,
            7f * unit,
            referencePaint,
        )
        referencePaint.style = Paint.Style.STROKE
        referencePaint.strokeWidth = 3f * unit
        referencePaint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(
            RectF(
                cx - 12f * unit,
                cy - 10f * unit,
                cx + 12f * unit,
                cy + 13f * unit,
            ),
            0f,
            180f,
            false,
            referencePaint,
        )
        canvas.drawLine(
            cx,
            cy + 13f * unit,
            cx,
            cy + 19f * unit,
            referencePaint,
        )
        referencePaint.style = Paint.Style.FILL
    }

    private fun drawCompactMicWave(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        unit: Float,
    ) {
        val count = COMPACT_MIC_WAVE_HEIGHTS.size
        val step = 4.6f * unit
        val totalWidth = (count - 1) * step
        val phase =
            if (animationsEnabled()) {
                (SystemClock.uptimeMillis() - listeningStartedAt) *
                    0.0062f
            } else {
                0f
            }
        referencePaint.shader = null
        referencePaint.style = Paint.Style.FILL
        referencePaint.color = Color.rgb(251, 251, 253)
        for (index in 0 until count) {
            val pulse = 0.80f + 0.20f * sin(phase + index * 0.92f)
            val height =
                (COMPACT_MIC_WAVE_HEIGHTS[index] * 22f * unit * pulse)
                    .coerceIn(3f * unit, 22f * unit)
            val x = cx - totalWidth / 2f + index * step
            val halfWidth = 1.6f * unit
            canvas.drawRoundRect(
                x - halfWidth,
                cy - height / 2f,
                x + halfWidth,
                cy + height / 2f,
                halfWidth,
                halfWidth,
                referencePaint,
            )
        }
    }

    private fun drawIslandTranscript(
        canvas: Canvas,
        bounds: RectF,
        transcript: String,
        unit: Float,
    ) {
        referenceText.textSize = 25f * unit
        referenceText.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        referenceText.alpha = 255
        referenceText.color = Color.rgb(250, 250, 253)
        referenceText.setShadowLayer(2f * unit, 0f, 1f * unit, Color.argb(125, 0, 0, 0))
        val textLeft = bounds.left + 98f * unit
        val textRight = bounds.right - 18f * unit
        val available = (textRight - textLeft).coerceAtLeast(1f)
        val fitted =
            android.text.TextUtils.ellipsize(
                transcript,
                referenceText,
                available,
                android.text.TextUtils.TruncateAt.END,
            )
        val baseline =
            bounds.centerY() -
                (referenceText.ascent() + referenceText.descent()) / 2f
        canvas.drawText(fitted, 0, fitted.length, textLeft, baseline, referenceText)
        referenceText.clearShadowLayer()
    }

    private fun drawAnswerCard(
        canvas: Canvas,
        bounds: RectF,
    ) {
        val reveal =
            if (animationsEnabled()) {
                softOut.getInterpolation(answerProgress.coerceIn(0f, 1f))
            } else {
                1f
            }
        if (reveal <= 0.001f) return
        val save = canvas.saveLayerAlpha(bounds, (255f * reveal).roundToInt())
        drawReferenceGlass(canvas, bounds, panelRadius)
        val available =
            (bounds.width() - contentSidePadding * 2f)
                .roundToInt()
                .coerceAtLeast(1)
        val key = "$available:$answerText"
        if (key != referenceAnswerKey) {
            referenceAnswerKey = key
            referenceAnswerLayout = buildReferenceAnswerLayout(answerText, available)
        }
        referenceText.textSize = metrics.answerSize
        referenceText.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        referenceText.alpha = 255
        referenceText.color = Color.rgb(248, 248, 250)
        referenceText.clearShadowLayer()
        val textSave = canvas.save()
        canvas.clipRect(bounds)
        canvas.translate(
            bounds.left + contentSidePadding,
            bounds.top +
                contentTopPadding +
                14f * metrics.scale * (1f - reveal),
        )
        referenceAnswerLayout?.draw(canvas)
        canvas.restoreToCount(textSave)
        canvas.restoreToCount(save)
    }

    private fun drawReferenceGlass(canvas: Canvas, bounds: RectF, radius: Float) {
        referencePaint.alpha = 255
        referencePaint.style = Paint.Style.FILL
        referencePaint.shader = LinearGradient(bounds.left, bounds.top, bounds.left, bounds.bottom,
            intArrayOf(Color.argb(210, 91, 97, 116), Color.argb(200, 54, 59, 75), Color.argb(218, 48, 53, 68), Color.argb(225, 83, 89, 106)),
            floatArrayOf(0f, 0.20f, 0.72f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(bounds, radius, radius, referencePaint)
        referencePaint.shader = null
        val save = canvas.save()
        val clip = Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) }
        canvas.clipPath(clip)
        referencePaint.shader = RadialGradient(bounds.left + bounds.width() * 0.12f, bounds.top, bounds.width() * 0.90f,
            intArrayOf(Color.argb(45, 227, 232, 246), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, referencePaint)
        referencePaint.shader = null
        canvas.restoreToCount(save)
        referencePaint.style = Paint.Style.STROKE
        referencePaint.strokeWidth = 2f * panelWidth / 1460f
        referencePaint.shader = LinearGradient(bounds.left, bounds.top, bounds.left, bounds.bottom,
            intArrayOf(Color.argb(175, 238, 241, 251), Color.argb(55, 220, 225, 238), Color.argb(190, 230, 234, 247)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(bounds, radius, radius, referencePaint)
        referencePaint.shader = null
        referencePaint.style = Paint.Style.FILL
    }

    private fun drawReferenceButton(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        referencePaint.alpha = 255
        referencePaint.shader = RadialGradient(cx - radius * 0.25f, cy - radius * 0.65f, radius * 1.8f,
            intArrayOf(Color.argb(220, 139, 145, 163), Color.argb(210, 87, 93, 112), Color.argb(230, 108, 114, 132)),
            floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius, referencePaint)
        referencePaint.shader = null
        referencePaint.style = Paint.Style.STROKE
        referencePaint.strokeWidth = 1.4f
        referencePaint.color = Color.argb(65, 232, 235, 245)
        canvas.drawCircle(cx, cy, radius, referencePaint)
        referencePaint.style = Paint.Style.FILL
    }

    private fun drawReferenceMic(canvas: Canvas, cx: Float, cy: Float, unit: Float) {
        referencePaint.color = Color.rgb(251, 251, 253)
        canvas.drawRoundRect(cx - 14f * unit, cy - 35f * unit, cx + 14f * unit, cy + 10f * unit, 14f * unit, 14f * unit, referencePaint)
        referencePaint.style = Paint.Style.STROKE
        referencePaint.strokeWidth = 6f * unit
        referencePaint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(RectF(cx - 24f * unit, cy - 19f * unit, cx + 24f * unit, cy + 26f * unit), 0f, 180f, false, referencePaint)
        canvas.drawLine(cx, cy + 26f * unit, cx, cy + 39f * unit, referencePaint)
        referencePaint.style = Paint.Style.FILL
    }
    private fun drawCachedChrome(canvas: Canvas) {
        val bitmap = ensureChromeBitmap()
        if (bitmap == null) {
            drawDirectChrome(canvas)
            return
        }
        chromeDestination.set(
            currentBounds.left - CHROME_SHADOW_PADDING,
            currentBounds.top - CHROME_SHADOW_PADDING,
            currentBounds.right + CHROME_SHADOW_PADDING,
            currentBounds.bottom + CHROME_SHADOW_PADDING,
        )
        canvas.drawBitmap(bitmap, null, chromeDestination, chromePaint)
    }

    private fun ensureChromeBitmap(): Bitmap? {
        val width =
            ceil(
                (currentBounds.width() + CHROME_SHADOW_PADDING * 2f) *
                    CHROME_CACHE_SCALE,
            ).toInt()
        val height =
            ceil(
                (currentBounds.height() + CHROME_SHADOW_PADDING * 2f) *
                    CHROME_CACHE_SCALE,
            ).toInt()
        val radius = currentRadius()
        if (
            chromeBitmap != null &&
            chromeBitmapWidth == width &&
            chromeBitmapHeight == height &&
            abs(chromeBitmapRadius - radius) < 0.5f
        ) {
            return chromeBitmap
        }

        val startedAt = SystemClock.uptimeMillis()
        val next =
            runCatching {
                Bitmap.createBitmap(
                    width.coerceAtLeast(1),
                    height.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
            }.getOrNull() ?: return null
        val cacheCanvas = Canvas(next)
        cacheCanvas.scale(CHROME_CACHE_SCALE, CHROME_CACHE_SCALE)
        cacheCanvas.translate(
            CHROME_SHADOW_PADDING - currentBounds.left,
            CHROME_SHADOW_PADDING - currentBounds.top,
        )
        drawShadow(cacheCanvas)
        val clipped = cacheCanvas.save()
        cacheCanvas.clipPath(clipPath)
        drawBackdrop(cacheCanvas)
        drawGlass(cacheCanvas)
        cacheCanvas.restoreToCount(clipped)
        drawRim(cacheCanvas)

        retiredChromeBitmap?.recycle()
        retiredChromeBitmap = chromeBitmap
        chromeBitmap = next
        chromeBitmapWidth = width
        chromeBitmapHeight = height
        chromeBitmapRadius = radius
        Log.i(
            "VoiceHelperOverlay",
            "chrome cached ${width}x$height in " +
                "${SystemClock.uptimeMillis() - startedAt}ms",
        )
        return next
    }

    private fun drawDirectChrome(canvas: Canvas) {
        drawShadow(canvas)
        val clipped = canvas.save()
        canvas.clipPath(clipPath)
        drawBackdrop(canvas)
        drawGlass(canvas)
        canvas.restoreToCount(clipped)
        drawRim(canvas)
    }

    private fun scheduleQuestionTimeline() {
        if (questionText.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        if (!isOpen && !openScheduled) {
            val earliestOpen = listeningStartedAt + OPEN_DELAY_MS
            val openAt = max(now, earliestOpen)
            openScheduled = true
            main.postDelayed(openRunnable, (openAt - now).coerceAtLeast(0L))
        }
        if (!questionScheduled && !questionVisible) {
            val openAt =
                when {
                    openStartedAt > 0L -> openStartedAt
                    openScheduled -> listeningStartedAt + OPEN_DELAY_MS
                    else -> now
                }
            val revealAt = max(now, openAt + QUESTION_DELAY_AFTER_OPEN_MS)
            questionScheduled = true
            main.postDelayed(questionRunnable, (revealAt - now).coerceAtLeast(0L))
        }
    }

    private fun startOpening() {
        if (isOpen) return
        openScheduled = false
        isOpen = true
        openStartedAt = SystemClock.uptimeMillis()
        animateExpansionTo(1f)
        if (waitingForAnswer) {
            waveActive = true
            waveAlpha = 1f
            waveScale = 1f
            startWaveTicker()
        } else {
            animateWaveOut()
        }
        if (!questionScheduled && questionText.isNotEmpty()) {
            questionScheduled = true
            main.postDelayed(questionRunnable, QUESTION_DELAY_AFTER_OPEN_MS)
        }
    }

    private fun revealQuestion() {
        if (questionVisible) return
        questionScheduled = false
        questionVisible = true
        conversationAnimator?.cancel()
        conversationAlpha = 0f
        conversationAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (animationsEnabled()) CONVERSATION_DURATION_MS else 0L
                startDelay = if (animationsEnabled()) CONVERSATION_START_DELAY_MS else 0L
                interpolator = cssEase
                addUpdateListener { animation ->
                    conversationAlpha = animation.animatedValue as Float
                    invalidate()
                }
                start()
            }
        startLineAnimations()
    }

    private fun animateExpansionTo(target: Float) {
        expansionAnimator?.cancel()
        val start = widthExpansion
        if (!animationsEnabled()) {
            widthExpansion = target
            heightExpansion = target
            radiusExpansion = target
            shadowExpansion = target
            invalidate()
            return
        }
        expansionAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = 420L
            interpolator = softOut
            addUpdateListener {
                val value = it.animatedValue as Float
                widthExpansion = value
                heightExpansion = value
                radiusExpansion = value
                shadowExpansion = value
                invalidate()
            }
            start()
        }
    }

    private fun animateWaveOut() {
        animateWave(0f, 0.76f, 120L)
    }

    private fun animateWaveIn() {
        animateWave(1f, 1f, 140L)
    }

    private fun fadeWaveOut() {
        if (waveAlpha < 0.01f) return
        animateWave(0f, 0.76f, 120L)
    }

    private fun animateWave(
        targetAlpha: Float,
        targetScale: Float,
        duration: Long,
    ) {
        waveAnimator?.cancel()
        val startAlpha = waveAlpha
        val startScale = waveScale
        if (!animationsEnabled()) {
            waveAlpha = targetAlpha
            waveScale = targetScale
            invalidate()
            return
        }
        waveAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration
                interpolator = LinearInterpolator()
                addUpdateListener { animation ->
                    val progress = animation.animatedValue as Float
                    val alphaProgress = cssEase.getInterpolation(progress)
                    val scaleProgress = slowOut.getInterpolation(progress)
                    waveAlpha = startAlpha + (targetAlpha - startAlpha) * alphaProgress
                    waveScale = startScale + (targetScale - startScale) * scaleProgress
                    invalidateWaveArea()
                }
                start()
            }
    }

    private fun setQuestionText(text: String) {
        val normalized = text.trim()
        if (normalized == questionText) return
        questionText = normalized
        cancelLineAnimations()
        questionLines.clear()
        questionLayout = null
        questionVisible = normalized.isNotEmpty()
        recomputePanelHeight()
    }

    private fun startLineAnimations() {
        if (!questionVisible || !animationsEnabled()) {
            if (!animationsEnabled()) {
                questionLines.forEach { it.progress = 1f }
                invalidate()
            }
            return
        }
        cancelLineAnimations()
        questionLines.forEachIndexed { index, line ->
            if (line.progress >= 0.999f) return@forEachIndexed
            val startProgress = line.progress
            val remaining = (LINE_REVEAL_MS * (1f - startProgress)).roundToInt().toLong()
            val animator =
                ValueAnimator.ofFloat(startProgress, 1f).apply {
                    duration = remaining.coerceAtLeast(1L)
                    startDelay = LINE_STAGGER_MS * index
                    interpolator = LinearInterpolator()
                    addUpdateListener { animation ->
                        line.progress = animation.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            lineAnimators += animator
        }
    }

    private fun cancelLineAnimations() {
        lineAnimators.forEach(Animator::cancel)
        lineAnimators.clear()
    }

    private fun setAnswerText(text: String) {
        val normalized = text.trim()
        if (normalized == answerText) return
        answerText = normalized
        answerLayout = null
        recomputePanelHeight()
    }

    private fun startAnswerReveal() {
        if (answerText.isBlank()) {
            clearAnswer(immediate = true)
            return
        }
        answerVisible = true
        answerAnimator?.cancel()
        if (!animationsEnabled()) {
            answerProgress = 1f
            invalidate()
            return
        }
        answerProgress = 0f
        answerAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ANSWER_TIMELINE_MS
                interpolator = LinearInterpolator()
                addUpdateListener { animation ->
                    answerProgress = animation.animatedValue as Float
                    invalidate()
                }
                start()
            }
    }

    private fun clearAnswer(immediate: Boolean) {
        answerAnimator?.cancel()
        answerAnimator = null
        answerVisible = false
        answerProgress = if (immediate) 0f else answerProgress
        answerText = ""
        answerLayout = null
        recomputePanelHeight()
        invalidate()
    }

    private fun recomputePanelHeight() {
        val target =
            if (answerText.isBlank()) {
                basePanelHeight
            } else {
                val layout =
                    buildReferenceAnswerLayout(
                        answerText,
                        answerWidth.roundToInt().coerceAtLeast(1),
                    )
                (contentTopPadding + layout.height + contentBottomPadding)
                    .coerceIn(maxPanelHeight, maxAnswerHeight)
            }
        if (abs(target - desiredPanelHeight) < 0.5f) return
        desiredPanelHeight = target
        posedPanelHeight = target
        invalidate()
    }

    private fun buildReferenceAnswerLayout(
        text: String,
        available: Int,
    ): StaticLayout {
        referenceText.textSize = metrics.answerSize
        referenceText.alpha = 255
        return StaticLayout.Builder
            .obtain(text, 0, text.length, referenceText, available)
            .setIncludePad(false)
            .setLineSpacing(8f * metrics.scale, 1f)
            .build()
    }

    private fun buildQuestionLayout(text: String): StaticLayout {
        val content = SpannableString(forceQuestionClauseBreak(text))
        content.setSpan(
            ExactLineHeightSpan(questionLineHeight.roundToInt()),
            0,
            content.length,
            Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
        )
        return StaticLayout.Builder
            .obtain(content, 0, content.length, questionPaint, questionWidth.roundToInt())
            .setIncludePad(false)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setMaxLines(2)
            .build()
    }

    private fun forceQuestionClauseBreak(text: String): String {
        if (text.contains('\n') || questionPaint.measureText(text) > questionWidth) {
            return text
        }
        val breakIndex = text.indexOfAny(charArrayOf('，', ',', '；', ';'))
        if (breakIndex <= 0 || breakIndex >= text.lastIndex) return text
        val firstLine = text.substring(0, breakIndex + 1).trimEnd()
        val secondLine = text.substring(breakIndex + 1).trimStart()
        if (firstLine.isEmpty() || secondLine.isEmpty()) return text
        return "$firstLine\n$secondLine"
    }

    private fun buildAnswerLayout(text: String): StaticLayout {
        val content = SpannableString(text)
        val leadLength = answerLeadLength(text)
        if (leadLength > 0) {
            content.setSpan(
                answerLeadStyle,
                0,
                leadLength,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        content.setSpan(
            ExactLineHeightSpan(answerLineHeight.roundToInt()),
            0,
            content.length,
            Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
        )
        return StaticLayout.Builder
            .obtain(content, 0, content.length, answerPaint, answerWidth.roundToInt())
            .setIncludePad(false)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
    }

    private fun answerLeadLength(text: String): Int {
        for (prefix in ANSWER_LEAD_PREFIXES) {
            if (text.startsWith(prefix)) return prefix.length
        }
        return 0
    }

    private fun updateCurrentBounds() {
        val answerVisible = answerText.isNotBlank()
        val widthNow = if (answerVisible) panelWidth else capsuleWidth
        val heightNow = if (answerVisible) posedPanelHeight else capsuleHeight
        val panelBottom = height - windowBottomPadding
        val centerX = width / 2f
        currentBounds.set(
            centerX - widthNow / 2f,
            panelBottom - heightNow,
            centerX + widthNow / 2f,
            panelBottom,
        )
        val radiusNow = currentRadius()
        clipPath.reset()
        clipPath.addRoundRect(currentBounds, radiusNow, radiusNow, Path.Direction.CW)
    }

    private fun currentRadius(): Float =
        if (answerText.isNotBlank()) panelRadius else capsuleHeight / 2f

    private fun drawShadow(canvas: Canvas) {
        val bounds = currentBounds
        if (bounds.width() < 1f) return
        val progress = shadowExpansion.coerceIn(0f, 1f)
        val largeBlur = lerp(72f, 120f, progress)
        val smallBlur = lerp(22f, 34f, progress)
        val largeOffset = lerp(28f, 44f, progress)
        val smallOffset = lerp(7f, 12f, progress)
        val largeAlpha = lerp(0.34f, 0.42f, progress)
        val smallAlpha = lerp(0.20f, 0.25f, progress)
        drawLayeredShadow(canvas, bounds, largeBlur, largeOffset, largeAlpha, 4)
        drawLayeredShadow(canvas, bounds, smallBlur, smallOffset, smallAlpha, 2)
    }

    private fun drawLayeredShadow(
        canvas: Canvas,
        bounds: RectF,
        blur: Float,
        offsetY: Float,
        maxAlpha: Float,
        layers: Int,
    ) {
        val radius =
            lerp(
                capsuleHeight / 2f,
                panelRadius,
                radiusExpansion.coerceIn(0f, 1f),
            )
        val layerAlpha = (maxAlpha / layers * 255f).roundToInt().coerceIn(1, 255)
        shadowPaint.color = Color.argb(layerAlpha, 0, 0, 0)
        for (layer in layers downTo 1) {
            val fraction = layer / layers.toFloat()
            val spread = blur * 0.5f * fraction
            canvas.drawRoundRect(
                bounds.left - spread,
                bounds.top - spread + offsetY,
                bounds.right + spread,
                bounds.bottom + spread + offsetY,
                radius + spread,
                radius + spread,
                shadowPaint,
            )
        }
    }

    private fun drawBackdrop(canvas: Canvas) {
        val node = backdropNode
        if (node == null || !isHardwareAccelerated) {
            fallbackPaint.alpha = (255f * CARD_OPACITY).roundToInt()
            fallbackPaint.shader =
                LinearGradient(
                    currentBounds.left,
                    currentBounds.top,
                    currentBounds.right,
                    currentBounds.bottom,
                    intArrayOf(
                        Color.argb(238, 48, 58, 66),
                        Color.argb(246, 18, 25, 31),
                        Color.argb(250, 7, 11, 15),
                    ),
                    floatArrayOf(0f, 0.48f, 1f),
                    Shader.TileMode.CLAMP,
                )
            canvas.drawRect(currentBounds, fallbackPaint)
            return
        }
        canvas.drawRenderNode(node)
    }

    private fun drawGlass(canvas: Canvas) {
        frostPaint.color =
            Color.argb(
                (18f * CARD_OPACITY).roundToInt(),
                232,
                238,
                241,
            )
        canvas.drawRect(currentBounds, frostPaint)

        val shader =
            RadialGradient(
                0f,
                0f,
                1f,
                intArrayOf(
                    Color.argb(48, 255, 255, 255),
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.43f, 1f),
                Shader.TileMode.CLAMP,
            )
        val matrix =
            Matrix().apply {
                setScale(currentBounds.width() * 1.3f, currentBounds.height() * 1.8f)
                postTranslate(
                    currentBounds.left + currentBounds.width() * 0.04f,
                    currentBounds.top,
                )
            }
        shader.setLocalMatrix(matrix)
        radialPaint.shader = shader
        canvas.drawRect(currentBounds, radialPaint)

        linearPaint.shader =
            run {
                val directionX = 0.70710677f
                val directionY = 0.70710677f
                val halfAxis =
                    0.5f *
                        (
                            abs(currentBounds.width() * directionX) +
                                abs(currentBounds.height() * directionY)
                        )
                LinearGradient(
                    currentBounds.centerX() - directionX * halfAxis,
                    currentBounds.centerY() - directionY * halfAxis,
                    currentBounds.centerX() + directionX * halfAxis,
                    currentBounds.centerY() + directionY * halfAxis,
                intArrayOf(
                    Color.argb((19f * CARD_OPACITY).roundToInt(), 255, 255, 255),
                    Color.argb((97f * CARD_OPACITY).roundToInt(), 22, 29, 35),
                    Color.argb((128f * CARD_OPACITY).roundToInt(), 7, 11, 15),
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
                )
            }
        canvas.drawRect(currentBounds, linearPaint)
    }

    private fun drawConversation(canvas: Canvas) {
        val alphaNow = conversationAlpha * exitContentAlpha
        if (alphaNow <= 0.001f) return
        val panelTop = currentBounds.top
        val panelBottom = currentBounds.bottom
        val contentLeft = currentBounds.left + contentSidePadding
        val questionTop = panelTop + contentTopPadding + QUESTION_TOP_OFFSET

        drawQuestion(canvas, contentLeft, questionTop, alphaNow)

        val layout = answerLayout
        if (layout != null && answerVisible) {
            val replyTransform =
                softOut.getInterpolation((answerProgress / 0.86f).coerceIn(0f, 1f))
            val replyOpacity =
                cssEase.getInterpolation((answerProgress / 0.68f).coerceIn(0f, 1f))
            val replyOffset = 17f * (1f - replyTransform) + exitContentOffset
            val answerBottom = panelBottom - contentBottomPadding
            val answerTop = answerBottom - layout.height + replyOffset
            val dividerY = answerTop - replyTopPadding
            val replyAlpha = alphaNow * replyOpacity

            if (replyAlpha > 0.001f) {
                dividerPaint.strokeWidth = 1f
                dividerPaint.shader = null
                dividerPaint.color =
                    Color.argb(
                        (255f * 0.145f * replyAlpha).roundToInt().coerceIn(0, 255),
                        255,
                        255,
                        255,
                    )
                canvas.drawLine(
                    contentLeft,
                    dividerY,
                    contentLeft + answerWidth,
                    dividerY,
                    dividerPaint,
                )
                answerPaint.alpha =
                    (255f * 0.68f * replyAlpha).roundToInt().coerceIn(0, 255)
                val save = canvas.save()
                canvas.translate(contentLeft, answerTop)
                layout.draw(canvas)
                canvas.restoreToCount(save)
            }
        }
    }

    private fun drawQuestion(
        canvas: Canvas,
        contentLeft: Float,
        questionTop: Float,
        conversationAlpha: Float,
    ) {
        val layout = questionLayout ?: return
        if (!questionVisible) return
        for (index in questionLines.indices) {
            if (index >= layout.lineCount) break
            val line = questionLines[index]
            if (line.progress <= 0.001f) continue
            val transform = softOut.getInterpolation(line.progress.coerceIn(0f, 1f))
            val opacity =
                cssEase.getInterpolation((line.progress / 0.68f).coerceIn(0f, 1f))
            val alphaNow = conversationAlpha * opacity
            if (alphaNow <= 0.001f) continue

            val lineTop = questionTop + layout.getLineTop(index)
            val lineBottom = questionTop + layout.getLineBottom(index)
            val offset = questionLineHeight * 1.12f * (1f - transform)

            val save = canvas.save()
            canvas.clipRect(
                contentLeft,
                lineTop,
                contentLeft + questionWidth,
                lineBottom,
            )
            canvas.translate(0f, offset)
            questionPaint.alpha = (255f * alphaNow).roundToInt().coerceIn(0, 255)
            val textSave = canvas.save()
            canvas.translate(contentLeft, questionTop)
            layout.draw(canvas)
            canvas.restoreToCount(textSave)
            canvas.restoreToCount(save)
        }
    }

    private fun createBackdropNode(
        bitmap: Bitmap,
        effect: RenderEffect,
    ): RenderNode {
        val stageWidth =
            ceil(metrics.panelWidth + metrics.windowSidePadding * 2f)
                .toInt()
                .coerceAtLeast(1)
        val stageHeight =
            ceil(
                metrics.maxAnswerHeight +
                    metrics.windowTopPadding +
                    metrics.windowBottomPadding,
            )
                .toInt()
                .coerceAtLeast(1)
        val node = RenderNode("mist-backdrop")
        node.setPosition(0, 0, stageWidth, stageHeight)
        node.setRenderEffect(effect)
        val recording = node.beginRecording(stageWidth, stageHeight)
        recording.drawBitmap(
            bitmap,
            null,
            RectF(
                -screenOriginX,
                -screenOriginY,
                -screenOriginX + bitmap.width,
                -screenOriginY + bitmap.height,
            ),
            backdropPaint,
        )
        node.endRecording()
        return node
    }

    private fun drawWave(canvas: Canvas) {
        if (waveAlpha <= 0.001f) return
        val centerX = currentBounds.centerX()
        val expansion = widthExpansion.coerceIn(0f, 1f)
        val expandedWave = expansion > 0.05f
        val centerY = waveCenterY(expandedWave)
        val totalWidth = waveSpanWidth(expansion)
        val barCount = if (expandedWave) WAVE_BARS_EXPANDED else WAVE_BARS_COMPACT
        val step = totalWidth / (barCount - 1f)
        val barWidth = lerp(3.8f, 7.4f, expansion)
        val maxHeight = lerp(25f, 86f, expansion)
        val left = centerX - totalWidth / 2f
        val elapsed = SystemClock.uptimeMillis() - listeningStartedAt
        val phase =
            (elapsed % WAVE_PERIOD_MS).toFloat() / WAVE_PERIOD_MS.toFloat() * TWO_PI
        val save = canvas.save()
        canvas.scale(waveScale, waveScale, centerX, centerY)
        val alpha = waveAlpha.coerceIn(0f, 1f)
        val centerIndex = (barCount - 1) / 2f
        for (index in 0 until barCount) {
            val distance = abs(index - centerIndex) / centerIndex
            val ring =
                min(index, barCount - 1 - index)
                    .coerceAtLeast(0)
            val dome = (1f - distance * distance).coerceIn(0f, 1f)
            val breath =
                sin(distance * 10.6f + phase) * 0.13f +
                    sin(distance * 23.8f - phase * 1.35f) * 0.055f
            val energy = (0.88f + breath * dome).coerceIn(0.58f, 1f)
            val x = left + index * step
            val fade = 0.34f + dome * 0.66f
            val barAlpha =
                (alpha * fade * (0.86f + energy * 0.14f) * 246f)
                    .roundToInt()
                    .coerceIn(0, 255)
            waveGlowPaint.color =
                Color.argb(
                    (barAlpha * 0.12f).roundToInt().coerceIn(0, 255),
                    226,
                    231,
                    233,
                )
            wavePaint.color = Color.argb(barAlpha, 235, 238, 239)

            if (ring < WAVE_DOT_RINGS) {
                val dotProgress = ring.toFloat() / (WAVE_DOT_RINGS - 1f)
                val radius = barWidth * lerp(0.34f, 0.60f, dotProgress)
                canvas.drawCircle(x, centerY, radius + 1.1f, waveGlowPaint)
                canvas.drawCircle(x, centerY, radius, wavePaint)
            } else {
                val height =
                    (maxHeight * (0.055f + dome * 0.945f) * energy)
                        .coerceAtLeast(barWidth * 1.55f)
                waveBarBounds.set(
                    x - barWidth / 2f - 1.2f,
                    centerY - height / 2f - 1.2f,
                    x + barWidth / 2f + 1.2f,
                    centerY + height / 2f + 1.2f,
                )
                canvas.drawRoundRect(
                    waveBarBounds,
                    barWidth,
                    barWidth,
                    waveGlowPaint,
                )
                waveBarBounds.set(
                    x - barWidth / 2f,
                    centerY - height / 2f,
                    x + barWidth / 2f,
                    centerY + height / 2f,
                )
                canvas.drawRoundRect(
                    waveBarBounds,
                    barWidth / 2f,
                    barWidth / 2f,
                    wavePaint,
                )
            }
        }
        canvas.restoreToCount(save)
    }

    private fun waveSpanWidth(expansion: Float): Float =
        lerp(
            capsuleWidth * 0.72f,
            panelWidth - contentSidePadding * 2f,
            expansion.coerceIn(0f, 1f),
        )

    private fun drawRim(canvas: Canvas) {
        val radius =
            lerp(
                capsuleHeight / 2f,
                panelRadius,
                radiusExpansion.coerceIn(0f, 1f),
            )
        rimPaint.strokeWidth = 1f
        rimPaint.shader = null
        rimPaint.color = Color.argb(59, 255, 255, 255)
        val half = rimPaint.strokeWidth / 2f
        canvas.drawRoundRect(
            currentBounds.left + half,
            currentBounds.top + half,
            currentBounds.right - half,
            currentBounds.bottom - half,
            radius,
            radius,
            rimPaint,
        )

        insetPaint.strokeWidth = 1f
        insetPaint.color = Color.argb(6, 255, 255, 255)
        val inset = 1.5f
        canvas.drawRoundRect(
            currentBounds.left + inset,
            currentBounds.top + inset,
            currentBounds.right - inset,
            currentBounds.bottom - inset,
            (radius - inset).coerceAtLeast(0.5f),
            (radius - inset).coerceAtLeast(0.5f),
            insetPaint,
        )

        insetPaint.color = Color.argb(87, 255, 255, 255)
        canvas.drawLine(
            currentBounds.left + min(radius, currentBounds.width() / 2f),
            currentBounds.top + 1.5f,
            currentBounds.right - min(radius, currentBounds.width() / 2f),
            currentBounds.top + 1.5f,
            insetPaint,
        )
        insetPaint.color = Color.argb(18, 255, 255, 255)
        canvas.drawLine(
            currentBounds.left + min(radius, currentBounds.width() / 2f),
            currentBounds.bottom - 1.5f,
            currentBounds.right - min(radius, currentBounds.width() / 2f),
            currentBounds.bottom - 1.5f,
            insetPaint,
        )
    }

    private fun cancelTimelines() {
        main.removeCallbacks(openRunnable)
        main.removeCallbacks(questionRunnable)
        openScheduled = false
        questionScheduled = false
    }

    private fun cancelTextAnimations() {
        expansionAnimator?.cancel()
        expansionAnimator = null
        stopWaveTicker()
        conversationAnimator?.cancel()
        conversationAnimator = null
        answerAnimator?.cancel()
        answerAnimator = null
        waveAnimator?.cancel()
        waveAnimator = null
        cancelLineAnimations()
    }

    private fun stopWaveAnimation() {
        waitingForAnswer = false
        waveActive = false
        stopWaveTicker()
        waveAnimator?.cancel()
        waveAnimator = null
        waveAlpha = 0f
        waveScale = 0.76f
    }

    private fun startWaveTicker() {
        if (waveTickerRunning || !shouldAnimateWave()) return
        waveTickerRunning = true
        main.post(waveTicker)
    }

    private fun stopWaveTicker() {
        waveTickerRunning = false
        main.removeCallbacks(waveTicker)
    }

    private fun shouldAnimateWave(): Boolean =
        attached &&
            animationsEnabled() &&
            waveActive &&
            waveAlpha > 0.001f &&
            answerText.isBlank()

    private fun waveCenterY(expandedWave: Boolean): Float =
        if (expandedWave) {
            currentBounds.bottom - THINKING_WAVE_BOTTOM_OFFSET
        } else {
            currentBounds.centerY()
        }

    private fun invalidateWaveArea() {
        if (waitingForAnswer) {
            invalidate(currentBounds.left.toInt(), currentBounds.top.toInt(), currentBounds.right.toInt() + 1, currentBounds.bottom.toInt() + 1)
            return
        }
        if (!isOpen) {
            invalidate(
                currentBounds.left.toInt(),
                currentBounds.top.toInt(),
                currentBounds.right.toInt() + 1,
                currentBounds.bottom.toInt() + 1,
            )
            return
        }
        val centerX = currentBounds.centerX().roundToInt()
        val expansion = widthExpansion.coerceIn(0f, 1f)
        val centerY = waveCenterY(expansion > 0.05f).roundToInt()
        val halfWidth = waveSpanWidth(expansion) / 2f + WAVE_INVALIDATION_PADDING
        waveInvalidationBounds.set(
            (centerX - halfWidth).roundToInt(),
            (centerY - WAVE_INVALIDATION_PADDING).roundToInt(),
            (centerX + halfWidth).roundToInt(),
            (centerY + WAVE_INVALIDATION_PADDING).roundToInt(),
        )
        invalidate(waveInvalidationBounds)
    }

    private fun animationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

    private fun lerp(
        start: Float,
        stop: Float,
        fraction: Float,
    ): Float = start + (stop - start) * fraction

    private data class QuestionLine(
        val text: String,
        var progress: Float,
    )

    private class ExactLineHeightSpan(
        private val lineHeightPx: Int,
    ) : LineHeightSpan {
        override fun chooseHeight(
            text: CharSequence,
            start: Int,
            end: Int,
            spanstartv: Int,
            lineHeight: Int,
            fm: Paint.FontMetricsInt,
        ) {
            val current = fm.descent - fm.ascent + fm.leading
            val difference = lineHeightPx - current
            val topAdjustment = difference / 2
            fm.ascent -= topAdjustment
            fm.descent += difference - topAdjustment
            fm.leading = 0
            fm.top = fm.ascent
            fm.bottom = fm.descent
        }
    }

    private class AnswerLeadStyle(
        private val typeface: Typeface,
    ) : CharacterStyle() {
        override fun updateDrawState(tp: TextPaint) {
            val strengthenedAlpha =
                (tp.alpha * (ANSWER_LEAD_ALPHA / ANSWER_BODY_ALPHA))
                    .roundToInt()
                    .coerceIn(0, 255)
            tp.typeface = typeface
            tp.color = Color.argb(strengthenedAlpha, 249, 249, 246)
        }
    }

    private companion object {
        const val QUESTION_COLOR = 0xFFF3F4F2.toInt()
        const val ANSWER_COLOR = 0xFFEBEFF0.toInt()
        const val ANSWER_BODY_ALPHA = 0.68f
        const val ANSWER_LEAD_ALPHA = 0.94f
        const val QUESTION_TOP_OFFSET = 12f

        val ANSWER_LEAD_PREFIXES = arrayOf("好的，", "好的！", "好，", "好！")

        const val ENTRANCE_DURATION_MS = 280L
        const val OPEN_DELAY_MS = 60L
        const val QUESTION_DELAY_AFTER_OPEN_MS = 40L
        const val CONVERSATION_DURATION_MS = 140L
        const val CONVERSATION_START_DELAY_MS = 20L
        const val LINE_STAGGER_MS = 30L
        const val LINE_REVEAL_MS = 260L
        const val ANSWER_TIMELINE_MS = 360L
        const val EXIT_CONTENT_DURATION_MS = 100L
        const val EXIT_DURATION_MS = 260L
        const val EXIT_FADE_START = 0.86f
        const val CHROME_SHADOW_PADDING = 144f
        const val CHROME_CACHE_SCALE = 0.5f
        const val CARD_OPACITY = 0.60f

        const val WAVE_BARS_EXPANDED = 49
        const val WAVE_BARS_COMPACT = 31
        const val WAVE_DOT_RINGS = 3
        const val WAVE_FRAME_MS = 32L
        const val THINKING_WAVE_BOTTOM_OFFSET = 112f
        const val WAVE_PERIOD_MS = 2400L
        const val WAVE_INVALIDATION_PADDING = 96f
        const val TWO_PI = 6.2831855f
        const val PI = 3.1415927f
        val COMPACT_MIC_WAVE_HEIGHTS =
            floatArrayOf(
                0.34f,
                0.64f,
                1f,
                0.58f,
                0.40f,
            )

    }
}
