package com.motovision.pro.opt

import com.motovision.pro.model.ProcessingProfile
import com.motovision.pro.processing.BilateralDenoiseFilter
import com.motovision.pro.processing.ColorSciencePipeline
import com.motovision.pro.processing.DetailSharpnessFilter

/**
 * Dedicated Optimization Pipeline for the 2 MP Macro Module (f/2.4, 4cm fixed-focus).
 * - Focus guidance metric for 4cm close-range alignment.
 * - Central shadow lift compensating for device shadow cast.
 * - Micro-contrast texture sharpening adapted to the small 1/5" 2MP sensor.
 */
object MacroOptimizationProfile {

    /**
     * Evaluates central focus clarity to guide user to the sharpest 4cm plane.
     * Returns focus quality in [0.0..100.0%].
     */
    fun computeMacroFocusGuidance(pixels: IntArray, width: Int, height: Int): Float {
        val cx = width / 2
        val cy = height / 2
        val halfW = width / 4
        val halfH = height / 4

        var energy = 0.0
        var count = 0

        for (y in (cy - halfH)..(cy + halfH) step 2) {
            val row = y * width
            for (x in (cx - halfW)..(cx + halfW) step 2) {
                val p = pixels[row + x]
                val pRight = pixels[row + x + 1]
                val lum = (p and 0xFF)
                val lumRight = (pRight and 0xFF)
                val diff = lum - lumRight
                energy += diff * diff
                count++
            }
        }

        if (count == 0) return 0.0f
        val meanEnergy = energy / count
        // Normalize typical macro gradient energy to 0..100%
        return (meanEnergy / 25.0).toFloat().coerceIn(0.0f, 100.0f)
    }

    /**
     * Enhances macro capture with central shadow lift and fine-texture unsharp masking.
     */
    fun processMacro(
        pixels: IntArray,
        width: Int,
        height: Int,
        profile: ProcessingProfile = ProcessingProfile.DETAILED,
        output: IntArray = IntArray(pixels.size)
    ): IntArray {
        val total = width * height
        val cx = width / 2.0f
        val cy = height / 2.0f
        val maxDist = Math.sqrt((cx * cx + cy * cy).toDouble()).toFloat()

        // 1. Bilateral filtering tuned to 2MP sensor noise floor
        val denoised = IntArray(total)
        BilateralDenoiseFilter.filter(pixels, width, height, sigmaSpatial = 1.8f, sigmaRange = 0.07f, denoised)

        // 2. Central shadow lift (compensates for phone shadow cast when shooting at 4cm)
        val shadowLifted = IntArray(total)
        for (y in 0 until height) {
            val dy = y - cy
            val rowOffset = y * width

            for (x in 0 until width) {
                val dx = x - cx
                val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                // Center weight: 1.0 at center, drops to 0.0 at edges
                val centerWeight = (1.0f - (dist / maxDist)).coerceIn(0.0f, 1.0f)

                val p = denoised[rowOffset + x]
                val a = (p ushr 24) and 0xFF
                var r = ((p ushr 16) and 0xFF).toFloat()
                var g = ((p ushr 8) and 0xFF).toFloat()
                var b = (p and 0xFF).toFloat()

                val lum = (r * 0.299f + g * 0.587f + b * 0.114f) / 255.0f

                // Lift shadows in central region if shadowed (< 0.4)
                if (lum < 0.45f && centerWeight > 0.3f) {
                    val boost = 1.0f + (0.45f - lum) * 0.5f * centerWeight
                    r = (r * boost).coerceAtMost(255f)
                    g = (g * boost).coerceAtMost(255f)
                    b = (b * boost).coerceAtMost(255f)
                }

                shadowLifted[rowOffset + x] = (a shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
            }
        }

        // 3. Color Science pass
        val colorCorrected = IntArray(total)
        ColorSciencePipeline.processColors(shadowLifted, width, height, profile, colorCorrected)

        // 4. Macro Detail & Sharpness pass (high micro-contrast with low halo threshold)
        DetailSharpnessFilter.enhanceDetails(
            colorCorrected,
            width,
            height,
            sharpnessWeight = 0.55f,
            haloThreshold = 0.08f,
            output
        )

        return output
    }
}
