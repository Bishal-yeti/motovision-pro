package com.motovision.pro.opt

import com.motovision.pro.model.ProcessingProfile
import com.motovision.pro.processing.ColorLut
import com.motovision.pro.processing.ColorSciencePipeline
import kotlin.math.exp

/**
 * Advanced Selfie and Portrait Engine for the 16 MP Front Camera.
 * - Face-aware exposure optimization (corrects backlit silhouettes).
 * - Authentic skin texture preservation (beauty filtering disabled by default).
 * - Highlight protection against forehead/nose specular blowout.
 * - Optional and adjustable optical bokeh background blur synthesis.
 */
object FrontSelfieEngine {

    data class FaceRegion(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * Processes a front selfie image.
     *
     * @param pixels Input image ARGB array
     * @param width Image width
     * @param height Image height
     * @param profile Processing profile
     * @param portraitBlurRadius Radius of background blur (0 = blur off, 4..16 = natural bokeh)
     */
    fun processSelfie(
        pixels: IntArray,
        width: Int,
        height: Int,
        profile: ProcessingProfile = ProcessingProfile.NATURAL,
        portraitBlurRadius: Int = 0,
        output: IntArray = IntArray(pixels.size)
    ): IntArray {
        val total = width * height

        // 1. Detect face and skin locus
        val skinMask = FloatArray(total)
        var facePixelCount = 0L
        var faceLumSum = 0.0

        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val idx = rowOffset + x
                val p = pixels[idx]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF

                // YCbCr skin ellipse
                val Y = 0.299f * r + 0.587f * g + 0.114f * b
                val Cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
                val Cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b

                val isSkin = (Cb in 80f..130f) && (Cr in 133f..175f) && (Y > 25f)
                if (isSkin) {
                    skinMask[idx] = 1.0f
                    facePixelCount++
                    faceLumSum += Y
                } else {
                    skinMask[idx] = 0.0f
                }
            }
        }

        // 2. Face-aware Exposure Compensation
        // If face is in dark backlight (mean lum < 100), compute gentle exposure boost
        val faceExposureGain = if (facePixelCount > (total * 0.02)) {
            val meanFaceLum = faceLumSum / facePixelCount
            if (meanFaceLum < 110.0) {
                (128.0 / Math.max(40.0, meanFaceLum)).toFloat().coerceIn(1.0f, 1.35f)
            } else {
                1.0f
            }
        } else {
            1.0f
        }

        // 3. Apply natural skin correction and face exposure
        val exposedPixels = IntArray(total)
        for (i in 0 until total) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            var r = ((p ushr 16) and 0xFF).toFloat()
            var g = ((p ushr 8) and 0xFF).toFloat()
            var b = (p and 0xFF).toFloat()

            // If skin pixel, apply face exposure gain with highlight soft rolloff
            if (skinMask[i] > 0.5f && faceExposureGain > 1.01f) {
                r = (r * faceExposureGain).coerceAtMost(250f)
                g = (g * faceExposureGain).coerceAtMost(250f)
                b = (b * faceExposureGain).coerceAtMost(250f)
            }

            exposedPixels[i] = (a shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
        }

        // 4. Color Science pass
        val colorCorrected = IntArray(total)
        ColorSciencePipeline.processColors(exposedPixels, width, height, profile, colorCorrected)

        // 5. Optional Portrait Background Bokeh Blur Synthesis
        if (portraitBlurRadius > 1) {
            applyPortraitBokeh(colorCorrected, skinMask, width, height, portraitBlurRadius, output)
        } else {
            System.arraycopy(colorCorrected, 0, output, 0, total)
        }

        return output
    }

    /**
     * Synthesizes smooth optical bokeh blur on non-subject background pixels,
     * maintaining crisp foreground subject edges without halos.
     */
    private fun applyPortraitBokeh(
        pixels: IntArray,
        foregroundMask: FloatArray,
        width: Int,
        height: Int,
        blurRadius: Int,
        output: IntArray
    ) {
        val total = width * height
        // Fast box blur approximation of background
        val blurredBg = IntArray(total)
        val r = blurRadius.coerceIn(2, 16)

        // Horizontal blur pass
        val temp = IntArray(total)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                var count = 0

                for (dx in -r..r) {
                    val px = (x + dx).coerceIn(0, width - 1)
                    val p = pixels[row + px]
                    rSum += (p ushr 16) and 0xFF
                    gSum += (p ushr 8) and 0xFF
                    bSum += p and 0xFF
                    count++
                }

                temp[row + x] = (0xFF shl 24) or ((rSum / count) shl 16) or ((gSum / count) shl 8) or (bSum / count)
            }
        }

        // Vertical blur pass
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                var count = 0

                for (dy in -r..r) {
                    val py = (y + dy).coerceIn(0, height - 1)
                    val p = temp[py * width + x]
                    rSum += (p ushr 16) and 0xFF
                    gSum += (p ushr 8) and 0xFF
                    bSum += p and 0xFF
                    count++
                }

                blurredBg[row + x] = (0xFF shl 24) or ((rSum / count) shl 16) or ((gSum / count) shl 8) or (bSum / count)
            }
        }

        // Alpha blend foreground subject with blurred background based on skin/subject mask
        for (i in 0 until total) {
            val fgWeight = foregroundMask[i].coerceIn(0.0f, 1.0f)
            val bgWeight = 1.0f - fgWeight

            val pFg = pixels[i]
            val pBg = blurredBg[i]

            val finalR = (((pFg ushr 16) and 0xFF) * fgWeight + ((pBg ushr 16) and 0xFF) * bgWeight + 0.5f).toInt()
            val finalG = (((pFg ushr 8) and 0xFF) * fgWeight + ((pBg ushr 8) and 0xFF) * bgWeight + 0.5f).toInt()
            val finalB = ((pFg and 0xFF) * fgWeight + (pBg and 0xFF) * bgWeight + 0.5f).toInt()

            output[i] = (0xFF shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
        }
    }
}
