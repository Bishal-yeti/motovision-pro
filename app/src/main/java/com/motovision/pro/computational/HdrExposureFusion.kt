package com.motovision.pro.computational

import com.motovision.pro.model.ProcessingProfile
import com.motovision.pro.processing.ColorSciencePipeline
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Computational HDR Exposure Fusion based on the Mertens Exposure Fusion algorithm.
 * Blends multi-exposure bracketed frames without tone-mapping inversion,
 * preserving cloud details, deep shadows, and vibrant natural contrast.
 */
object HdrExposureFusion {

    /**
     * Fuses multi-exposure bracketed images into a single high-dynamic-range photograph.
     *
     * @param exposures List of ARGB images (typically Underexposed, Normal, Overexposed)
     * @param width Image width
     * @param height Image height
     * @param profile ProcessingProfile
     */
    fun fuseHdrBracket(
        exposures: List<IntArray>,
        width: Int,
        height: Int,
        profile: ProcessingProfile = ProcessingProfile.BALANCED
    ): IntArray {
        require(exposures.isNotEmpty()) { "At least one exposure is required for HDR fusion" }
        if (exposures.size == 1) return exposures[0].clone()

        val numExposures = exposures.size
        val totalPixels = width * height

        // 1. Align auxiliary exposures to the middle/normal exposure
        val refIdx = numExposures / 2
        val refExposure = exposures[refIdx]

        val alignedExposures = Array(numExposures) { i ->
            if (i == refIdx) {
                refExposure
            } else {
                val offset = FrameAlignment.computeOffset(refExposure, exposures[i], width, height, maxSearchRadius = 24)
                val aligned = IntArray(totalPixels)
                FrameAlignment.warp(exposures[i], width, height, offset, aligned)
            }
        }

        // 2. Compute pixel quality weight maps for each exposure
        // Weights combine contrast, saturation, and well-exposedness
        val weightMaps = Array(numExposures) { FloatArray(totalPixels) }

        for (e in 0 until numExposures) {
            val pixels = alignedExposures[e]
            val weights = weightMaps[e]

            for (i in 0 until totalPixels) {
                val p = pixels[i]
                val r = ((p ushr 16) and 0xFF) / 255.0f
                val g = ((p ushr 8) and 0xFF) / 255.0f
                val b = (p and 0xFF) / 255.0f

                val lum = r * 0.299f + g * 0.587f + b * 0.114f

                // A. Well-exposedness: Gaussian centered at 0.5 (sigma = 0.2)
                val dR = r - 0.5f
                val dG = g - 0.5f
                val dB = b - 0.5f
                val wExp = exp(-(dR * dR + dG * dG + dB * dB) / (2.0f * 0.2f * 0.2f))

                // B. Saturation: Standard deviation of RGB
                val mean = (r + g + b) / 3.0f
                val varR = r - mean
                val varG = g - mean
                val varB = b - mean
                val wSat = sqrt((varR * varR + varG * varG + varB * varB) / 3.0f)

                // Combined weight with small epsilon to avoid division by zero
                weights[i] = (wExp * (wSat + 0.1f)).coerceAtLeast(1e-4f)
            }
        }

        // 3. Normalize weights and blend exposures
        val fusedPixels = IntArray(totalPixels)

        for (i in 0 until totalPixels) {
            var weightSum = 0.0f
            for (e in 0 until numExposures) {
                weightSum += weightMaps[e][i]
            }

            val invSum = 1.0f / weightSum
            var rBlend = 0.0f
            var gBlend = 0.0f
            var bBlend = 0.0f

            for (e in 0 until numExposures) {
                val normWeight = weightMaps[e][i] * invSum
                val p = alignedExposures[e][i]
                rBlend += ((p ushr 16) and 0xFF) * normWeight
                gBlend += ((p ushr 8) and 0xFF) * normWeight
                bBlend += (p and 0xFF) * normWeight
            }

            val finalR = (rBlend + 0.5f).toInt().coerceIn(0, 255)
            val finalG = (gBlend + 0.5f).toInt().coerceIn(0, 255)
            val finalB = (bBlend + 0.5f).toInt().coerceIn(0, 255)

            fusedPixels[i] = (0xFF shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
        }

        // 4. Pass through ColorSciencePipeline for natural tones
        val output = IntArray(totalPixels)
        ColorSciencePipeline.processColors(fusedPixels, width, height, profile, output)
        return output
    }
}
