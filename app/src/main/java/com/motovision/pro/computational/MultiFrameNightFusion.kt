package com.motovision.pro.computational

import com.motovision.pro.model.ProcessingProfile
import com.motovision.pro.processing.ColorSciencePipeline

/**
 * Dedicated Computational Night Photography Fusion Engine.
 * Integrates multi-frame burst alignment, de-ghosted temporal noise reduction,
 * shadow detail extraction, and highlight protection.
 */
object MultiFrameNightFusion {

    /**
     * Fuses multiple burst frames into a high-SNR, low-noise night photograph.
     *
     * @param frames List of ARGB frame buffers (each of size width * height)
     * @param width Image width
     * @param height Image height
     * @param profile ProcessingProfile (default BALANCED or NATURAL)
     */
    fun fuseNightBurst(
        frames: List<IntArray>,
        width: Int,
        height: Int,
        profile: ProcessingProfile = ProcessingProfile.BALANCED
    ): IntArray {
        require(frames.isNotEmpty()) { "At least one frame is required for night fusion" }
        if (frames.size == 1) return frames[0].clone()

        val totalPixels = width * height

        // 1. Select the sharpest frame as reference using Laplacian variance
        var bestFrameIdx = 0
        var bestSharpness = -1.0
        for (i in frames.indices) {
            val score = computeSharpnessScore(frames[i], width, height)
            if (score > bestSharpness) {
                bestSharpness = score
                bestFrameIdx = i
            }
        }
        val refPixels = frames[bestFrameIdx]

        // Accumulators for linear RGB
        val rAccum = FloatArray(totalPixels)
        val gAccum = FloatArray(totalPixels)
        val bAccum = FloatArray(totalPixels)
        val weightAccum = FloatArray(totalPixels)

        // Seed reference frame into accumulator with weight 1.0
        for (i in 0 until totalPixels) {
            val p = refPixels[i]
            rAccum[i] = ((p ushr 16) and 0xFF).toFloat()
            gAccum[i] = ((p ushr 8) and 0xFF).toFloat()
            bAccum[i] = (p and 0xFF).toFloat()
            weightAccum[i] = 1.0f
        }

        // 2. Align and integrate each auxiliary frame
        val motionMask = FloatArray(totalPixels)
        val alignedBuffer = IntArray(totalPixels)

        for (idx in frames.indices) {
            if (idx == bestFrameIdx) continue

            val auxPixels = frames[idx]

            // Estimate translational alignment
            val offset = FrameAlignment.computeOffset(refPixels, auxPixels, width, height, maxSearchRadius = 24)

            // Warp auxiliary frame into reference coordinates
            FrameAlignment.warp(auxPixels, width, height, offset, alignedBuffer)

            // Detect motion between reference and aligned frame
            MotionDetector.computeMotionMask(refPixels, alignedBuffer, width, height, noiseTolerance = 0.05f, motionMask)

            // Accumulate with motion-adaptive weighting:
            // Static pixels (motion ~ 0) receive weight 1.0; moving pixels (motion ~ 1) receive weight 0.0
            for (i in 0 until totalPixels) {
                val motion = motionMask[i]
                val weight = 1.0f - motion

                if (weight > 0.01f) {
                    val p = alignedBuffer[i]
                    val r = (p ushr 16) and 0xFF
                    val g = (p ushr 8) and 0xFF
                    val b = p and 0xFF

                    rAccum[i] += r * weight
                    gAccum[i] += g * weight
                    bAccum[i] += b * weight
                    weightAccum[i] += weight
                }
            }
        }

        // 3. Normalize accumulators and apply shadow recovery with highlight protection
        val fusedPixels = IntArray(totalPixels)

        for (i in 0 until totalPixels) {
            val invW = 1.0f / weightAccum[i]
            val meanR = rAccum[i] * invW
            val meanG = gAccum[i] * invW
            val meanB = bAccum[i] * invW

            val lum = (meanR * 0.299f + meanG * 0.587f + meanB * 0.114f) / 255.0f

            // Shadow recovery factor: gently lift dark shadows (< 0.25) without artificial wash-out
            val shadowLiftFactor = if (lum < 0.25f) {
                1.0f + (0.25f - lum) * 0.8f // Up to 20% shadow lift
            } else {
                1.0f
            }

            // Highlight preservation factor: softly compress bright lights (> 0.85) to prevent blowout
            val highlightCompressFactor = if (lum > 0.85f) {
                val excess = lum - 0.85f
                1.0f - excess * 0.5f
            } else {
                1.0f
            }

            val factor = shadowLiftFactor * highlightCompressFactor

            val finalR = (meanR * factor + 0.5f).toInt().coerceIn(0, 255)
            val finalG = (meanG * factor + 0.5f).toInt().coerceIn(0, 255)
            val finalB = (meanB * factor + 0.5f).toInt().coerceIn(0, 255)

            fusedPixels[i] = (0xFF shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
        }

        // 4. Pass through Color Science Pipeline for natural night white balance and tone
        val output = IntArray(totalPixels)
        ColorSciencePipeline.processColors(fusedPixels, width, height, profile, output)
        return output
    }

    /**
     * Fast Laplacian variance score to select the sharpest reference frame in a burst.
     */
    fun computeSharpnessScore(pixels: IntArray, width: Int, height: Int): Double {
        var sum = 0.0
        var sqSum = 0.0
        var count = 0

        // Subsample for fast computation on Helio G85
        val step = 4
        for (y in 2 until height - 2 step step) {
            val row = y * width
            val prevRow = (y - 1) * width
            val nextRow = (y + 1) * width

            for (x in 2 until width - 2 step step) {
                // Laplacian kernel: 4 * center - top - bottom - left - right
                val center = getLum(pixels[row + x])
                val top = getLum(pixels[prevRow + x])
                val bottom = getLum(pixels[nextRow + x])
                val left = getLum(pixels[row + x - 1])
                val right = getLum(pixels[row + x + 1])

                val laplacian = 4 * center - top - bottom - left - right
                sum += laplacian
                sqSum += laplacian * laplacian
                count++
            }
        }

        if (count == 0) return 0.0
        val mean = sum / count
        return (sqSum / count) - (mean * mean)
    }

    private inline fun getLum(p: Int): Int {
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
