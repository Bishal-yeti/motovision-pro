package com.motovision.pro.processing

import kotlin.math.exp

/**
 * Fast, edge-preserving bilateral denoiser optimized for multi-core ARM architectures.
 * Smooths high-frequency sensor noise in uniform regions while maintaining edge gradients
 * to prevent the stock Motorola "smear / oil painting" artifact.
 */
object BilateralDenoiseFilter {

    /**
     * Applies an edge-preserving bilateral filter to an RGB pixel array in-place or into output.
     *
     * @param pixels ARGB_8888 packed integers
     * @param width Image width
     * @param height Image height
     * @param sigmaSpatial Spatial radius/decay (typically 1.5 to 3.0)
     * @param sigmaRange Photometric range decay (typically 0.05 to 0.15 in normalized linear space)
     */
    fun filter(
        pixels: IntArray,
        width: Int,
        height: Int,
        sigmaSpatial: Float,
        sigmaRange: Float,
        output: IntArray = IntArray(pixels.size)
    ): IntArray {
        val radius = Math.ceil((sigmaSpatial * 1.5).toDouble()).toInt().coerceIn(1, 4)
        val twoSigmaSpatialSq = 2.0f * sigmaSpatial * sigmaSpatial
        val twoSigmaRangeSq = 2.0f * sigmaRange * sigmaRange

        // Precompute spatial weights
        val spatialWeights = FloatArray((2 * radius + 1) * (2 * radius + 1))
        var idx = 0
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val distSq = (dx * dx + dy * dy).toFloat()
                spatialWeights[idx++] = exp(-distSq / twoSigmaSpatialSq)
            }
        }

        // Process rows in parallel across available CPU threads
        val threadCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val rowsPerChunk = Math.ceil(height.toDouble() / threadCount).toInt()

        val threads = Array(threadCount) { threadIdx ->
            val startY = threadIdx * rowsPerChunk
            val endY = Math.min(startY + rowsPerChunk, height)

            Thread {
                for (y in startY until endY) {
                    for (x in 0 until width) {
                        val centerIdx = y * width + x
                        val centerPixel = pixels[centerIdx]
                        val centerA = (centerPixel ushr 24) and 0xFF
                        val centerR = (centerPixel ushr 16) and 0xFF
                        val centerG = (centerPixel ushr 8) and 0xFF
                        val centerB = centerPixel and 0xFF

                        val centerLum = (centerR * 0.299f + centerG * 0.587f + centerB * 0.114f) / 255.0f

                        var weightSum = 0.0f
                        var rSum = 0.0f
                        var gSum = 0.0f
                        var bSum = 0.0f

                        var spatialIdx = 0
                        for (dy in -radius..radius) {
                            val py = (y + dy).coerceIn(0, height - 1)
                            val rowOffset = py * width

                            for (dx in -radius..radius) {
                                val px = (x + dx).coerceIn(0, width - 1)
                                val neighborPixel = pixels[rowOffset + px]

                                val nR = (neighborPixel ushr 16) and 0xFF
                                val nG = (neighborPixel ushr 8) and 0xFF
                                val nB = neighborPixel and 0xFF
                                val nLum = (nR * 0.299f + nG * 0.587f + nB * 0.114f) / 255.0f

                                val rangeDiff = nLum - centerLum
                                val rangeWeight = exp(-(rangeDiff * rangeDiff) / twoSigmaRangeSq)
                                val weight = spatialWeights[spatialIdx++] * rangeWeight

                                rSum += nR * weight
                                gSum += nG * weight
                                bSum += nB * weight
                                weightSum += weight
                            }
                        }

                        val invW = 1.0f / weightSum
                        val finalR = (rSum * invW + 0.5f).toInt().coerceIn(0, 255)
                        val finalG = (gSum * invW + 0.5f).toInt().coerceIn(0, 255)
                        val finalB = (bSum * invW + 0.5f).toInt().coerceIn(0, 255)

                        output[centerIdx] = (centerA shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
                    }
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        return output
    }
}
