package com.dongpiandi.voicehelper

import kotlin.math.roundToInt

internal fun interface VideoSearchPixelSource {
    fun colorAt(
        x: Int,
        y: Int,
    ): Int
}

internal data class VideoSearchTapTarget(
    val x: Int,
    val y: Int,
)

/**
 * Recognizes the current Video Pro 1.0.0 result page without its semantic tree.
 *
 * The projector performance profile hides Compose semantics while the voice helper is enabled,
 * so the compatibility path uses the fixed search-page layout: a long light input underline and
 * a populated first poster cell. Both checks must pass before the caller is allowed to tap.
 */
internal object VideoSearchFrameDetector {
    private const val BACKGROUND_RED = 44
    private const val BACKGROUND_GREEN = 44
    private const val BACKGROUND_BLUE = 46

    fun detectFirstResult(
        width: Int,
        height: Int,
        pixels: VideoSearchPixelSource,
    ): VideoSearchTapTarget? {
        if (width < 640 || height < 360) return null
        if (!hasSearchUnderline(width, height, pixels)) return null
        if (!hasFirstPoster(width, height, pixels)) return null
        return VideoSearchTapTarget(
            x = (width * FIRST_CARD_CENTER_X).roundToInt(),
            y = (height * FIRST_CARD_CENTER_Y).roundToInt(),
        )
    }

    private fun hasSearchUnderline(
        width: Int,
        height: Int,
        pixels: VideoSearchPixelSource,
    ): Boolean {
        val top = (height * UNDERLINE_TOP).toInt()
        val bottom = (height * UNDERLINE_BOTTOM).toInt()
        val left = (width * UNDERLINE_LEFT).toInt()
        val right = (width * UNDERLINE_RIGHT).toInt()
        val step = (width / 240).coerceAtLeast(2)
        val samples = ((right - left) / step) + 1
        val required = (samples * UNDERLINE_COVERAGE).toInt()

        for (y in top..bottom) {
            var bright = 0
            var x = left
            while (x <= right) {
                if (isUnderlineColor(pixels.colorAt(x, y))) bright++
                x += step
            }
            if (bright >= required) return true
        }
        return false
    }

    private fun hasFirstPoster(
        width: Int,
        height: Int,
        pixels: VideoSearchPixelSource,
    ): Boolean {
        val left = (width * POSTER_LEFT).toInt()
        val right = (width * POSTER_RIGHT).toInt()
        val top = (height * POSTER_TOP).toInt()
        val bottom = (height * POSTER_BOTTOM).toInt()
        val step = (minOf(width, height) / 90).coerceAtLeast(3)
        var different = 0
        var samples = 0

        var y = top
        while (y <= bottom) {
            var x = left
            while (x <= right) {
                if (isDifferentFromBackground(pixels.colorAt(x, y))) different++
                samples++
                x += step
            }
            y += step
        }
        return samples > 0 && different.toFloat() / samples >= POSTER_COVERAGE
    }

    private fun isUnderlineColor(color: Int): Boolean {
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF
        val brightest = maxOf(red, green, blue)
        val darkest = minOf(red, green, blue)
        return (red + green + blue) / 3 >= UNDERLINE_MIN_BRIGHTNESS &&
            brightest - darkest <= UNDERLINE_MAX_CHANNEL_DELTA
    }

    private fun isDifferentFromBackground(color: Int): Boolean {
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF
        val distance =
            kotlin.math.abs(red - BACKGROUND_RED) +
                kotlin.math.abs(green - BACKGROUND_GREEN) +
                kotlin.math.abs(blue - BACKGROUND_BLUE)
        return distance > POSTER_MIN_BACKGROUND_DISTANCE
    }

    private const val UNDERLINE_TOP = 0.20f
    private const val UNDERLINE_BOTTOM = 0.30f
    private const val UNDERLINE_LEFT = 0.17f
    private const val UNDERLINE_RIGHT = 0.83f
    private const val UNDERLINE_COVERAGE = 0.75f
    private const val UNDERLINE_MIN_BRIGHTNESS = 120
    private const val UNDERLINE_MAX_CHANNEL_DELTA = 20

    private const val POSTER_LEFT = 0.06f
    private const val POSTER_RIGHT = 0.13f
    private const val POSTER_TOP = 0.45f
    private const val POSTER_BOTTOM = 0.64f
    private const val POSTER_COVERAGE = 0.30f
    private const val POSTER_MIN_BACKGROUND_DISTANCE = 45

    private const val FIRST_CARD_CENTER_X = 0.094f
    private const val FIRST_CARD_CENTER_Y = 0.54f
}
