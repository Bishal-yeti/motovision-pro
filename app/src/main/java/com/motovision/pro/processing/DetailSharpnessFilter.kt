package com.motovision.pro.processing

/**
 * Advanced Edge-Aware Sharpness and Detail Preservation Filter.
 * Restores fine micro-textures while strictly preventing edge halos, ringing artifacts,
 * and unnatural over-sharpening commonly observed in stock Motorola processing.
 */
object DetailSharpnessFilter {

    /**
     * Applies non-linear unsharp masking with halo suppression.
     *
     * @param pixels Input image ARGB array
     * @param width Image width
     * @param height Image height
     * @param sharpnessWeight Strength factor (0.0 = off, 0.2 = natural, 0.5 = balanced, 0.8 = detailed)
     * @param haloThreshold Luminance deviation limit beyond which enhancement is softly damped
     */
    fun enhanceDetails(
        pixels: IntArray,
        width: Int,
        height: Int,
        sharpnessWeight: Float,
        haloThreshold: Float = 0.12f,
        output: IntArray = IntArray(pixels.size)
    ): IntArray {
        if (sharpnessWeight <= 0.01f) {
            System.arraycopy(pixels, 0, output, 0, pixels.size)
            return output
        }

        // Single-pass halo-damped unsharp masking:
        // Computes 3x3 Gaussian low-pass and edge-damping on the fly per pixel.
        // Completely eliminates the 50MB baseLum FloatArray allocation and thread barrier.
        val threadCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val rowsPerChunk = Math.ceil(height.toDouble() / threadCount).toInt()

        val threads = Array(threadCount) { threadIdx ->
            val startY = threadIdx * rowsPerChunk
            val endY = Math.min(startY + rowsPerChunk, height)

            Thread {
                for (y in startY until endY) {
                    val yPrev = (y - 1).coerceAtLeast(0) * width
                    val yCurr = y * width
                    val yNext = (y + 1).coerceAtMost(height - 1) * width

                    for (x in 0 until width) {
                        val xPrev = (x - 1).coerceAtLeast(0)
                        val xNext = (x + 1).coerceAtMost(width - 1)

                        // 3x3 weighted Gaussian-like kernel: [1 2 1; 2 4 2; 1 2 1] / 16
                        val p00 = getLuminance(pixels[yPrev + xPrev])
                        val p01 = getLuminance(pixels[yPrev + x])
                        val p02 = getLuminance(pixels[yPrev + xNext])

                        val p10 = getLuminance(pixels[yCurr + xPrev])
                        val p11 = getLuminance(pixels[yCurr + x])
                        val p12 = getLuminance(pixels[yCurr + xNext])

                        val p20 = getLuminance(pixels[yNext + xPrev])
                        val p21 = getLuminance(pixels[yNext + x])
                        val p22 = getLuminance(pixels[yNext + xNext])

                        val lowPass = (
                            (p00 + p02 + p20 + p22) * 1f +
                            (p01 + p10 + p12 + p21) * 2f +
                            (p11) * 4f
                        ) / 16.0f

                        val idx = yCurr + x
                        val origPixel = pixels[idx]
                        val a = (origPixel ushr 24) and 0xFF
                        val r = (origPixel ushr 16) and 0xFF
                        val g = (origPixel ushr 8) and 0xFF
                        val b = origPixel and 0xFF

                        val origLum = p11
                        val rawDetail = origLum - lowPass

                        // Non-linear coring and soft-clipping to prevent edge halo overshoot
                        val detailAbs = Math.abs(rawDetail)
                        val dampedDetail = if (detailAbs > haloThreshold) {
                            Math.signum(rawDetail) * (haloThreshold + (detailAbs - haloThreshold) * 0.2f)
                        } else {
                            rawDetail
                        }

                        val boostedLumDelta = dampedDetail * sharpnessWeight

                        // Scale RGB components by the boosted luminance delta
                        val factor = if (origLum > 0.001f) (origLum + boostedLumDelta) / origLum else 1.0f
                        val clampedFactor = factor.coerceIn(0.5f, 1.8f)

                        val finalR = (r * clampedFactor + 0.5f).toInt().coerceIn(0, 255)
                        val finalG = (g * clampedFactor + 0.5f).toInt().coerceIn(0, 255)
                        val finalB = (b * clampedFactor + 0.5f).toInt().coerceIn(0, 255)

                        output[idx] = (a shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
                    }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        return output
    }

    private inline fun getLuminance(pixel: Int): Float {
        val r = (pixel ushr 16) and 0xFF
        val g = (pixel ushr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 0.299f + g * 0.587f + b * 0.114f) / 255.0f
    }
}
