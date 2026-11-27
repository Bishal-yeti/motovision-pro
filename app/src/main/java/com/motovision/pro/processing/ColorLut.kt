package com.motovision.pro.processing

/**
 * High-precision color space transforms, gamma correction,
 * and soft-knee tone curves for the computational photography pipeline.
 */
object ColorLut {

    // 8-bit sRGB to linear float table [0..255] -> [0.0..1.0]
    val sRgbToLinear = FloatArray(256) { i ->
        val v = i / 255.0f
        if (v <= 0.04045f) {
            v / 12.92f
        } else {
            Math.pow(((v + 0.055) / 1.055), 2.4).toFloat()
        }
    }

    /**
     * Exact inverse sRGB transform from linear float [0.0..1.0] to 8-bit integer [0..255].
     */
    fun linearToSrgb(linear: Float): Int {
        val clamped = linear.coerceIn(0.0f, 1.0f)
        val s = if (clamped <= 0.0031308f) {
            12.92f * clamped
        } else {
            1.055f * Math.pow(clamped.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
        }
        return (s * 255.0f + 0.5f).toInt().coerceIn(0, 255)
    }

    /**
     * S-Curve Tone Mapping with Soft-Knee Highlight Rolloff.
     * Prevents harsh digital clipping in bright skies while preserving midtone contrast.
     */
    fun applySoftKneeToneCurve(input: Float, contrastBoost: Float, highlightRolloff: Float): Float {
        val x = input.coerceIn(0.0f, 1.0f)

        // S-curve centered around midtone 0.45
        val centered = (x - 0.45f) * contrastBoost
        val sigmoid = (1.0f / (1.0f + Math.exp(-centered * 4.0).toFloat()))

        // Soft highlight compression above threshold
        val threshold = highlightRolloff
        return if (sigmoid > threshold) {
            val overshoot = sigmoid - threshold
            val maxHeadroom = 1.0f - threshold
            threshold + maxHeadroom * (1.0f - Math.exp(-overshoot / (maxHeadroom * 0.8)).toFloat())
        } else {
            sigmoid
        }.coerceIn(0.0f, 1.0f)
    }
}
