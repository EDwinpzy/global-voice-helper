package com.dongpiandi.voicehelper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.view.View

/** Cached full-screen backdrop. The expensive blur is rendered once per voice session. */
@SuppressLint("ViewConstructor")
internal class ScreenBackdropView(
    context: Context,
    private val screenWidth: Int,
    private val screenHeight: Int,
) : View(context) {
    private val bitmapPaint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val destination =
        RectF(
            0f,
            0f,
            screenWidth.coerceAtLeast(1).toFloat(),
            screenHeight.coerceAtLeast(1).toFloat(),
        )

    private var backdropBitmap: Bitmap? = null
    private var backdropEffect: RenderEffect? = null
    private var backdropNode: RenderNode? = null

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        scrimPaint.shader =
            LinearGradient(
                0f,
                0f,
                0f,
                screenHeight.coerceAtLeast(1).toFloat(),
                intArrayOf(
                    Color.argb(82, 4, 7, 11),
                    Color.argb(104, 4, 7, 11),
                    Color.argb(132, 2, 4, 7),
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP,
            )
    }

    fun setBackdrop(bitmap: Bitmap) {
        clearBackdrop()
        backdropBitmap = bitmap
        val width = screenWidth.coerceAtLeast(1)
        val height = screenHeight.coerceAtLeast(1)
        val colorMatrix =
            ColorMatrix().apply {
                setSaturation(0.76f)
                postConcat(
                    ColorMatrix().apply {
                        setScale(0.88f, 0.88f, 0.88f, 1f)
                    },
                )
            }
        val effect =
            RenderEffect.createColorFilterEffect(
                ColorMatrixColorFilter(colorMatrix),
                RenderEffect.createBlurEffect(
                    34f,
                    34f,
                    Shader.TileMode.CLAMP,
                ),
            )
        backdropEffect = effect
        backdropNode =
            RenderNode("voice-helper-screen-backdrop").also { node ->
                node.setPosition(0, 0, width, height)
                node.setRenderEffect(effect)
                val recording = node.beginRecording(width, height)
                recording.drawBitmap(bitmap, null, destination, bitmapPaint)
                node.endRecording()
            }
        invalidate()
    }

    fun clearBackdrop() {
        backdropEffect = null
        backdropNode?.discardDisplayList()
        backdropNode = null
        backdropBitmap?.recycle()
        backdropBitmap = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        backdropNode?.let(canvas::drawRenderNode)
        canvas.drawRect(destination, scrimPaint)
    }
}
