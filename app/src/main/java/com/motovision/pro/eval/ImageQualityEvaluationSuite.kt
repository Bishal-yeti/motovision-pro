package com.motovision.pro.eval

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Reproducible Image Quality Evaluation Suite.
 * Benchmarks MTF50 edge sharpness, Signal-to-Noise Ratio (SNR),
 * shadow/highlight dynamic range recovery, color fidelity, and algorithmic latency.
 */
object ImageQualityEvaluationSuite {

    data class EvaluationResult(
        val metricName: String,
        val baselineValue: Double,
        val enhancedValue: Double,
        val improvementPercent: Double,
        val isTargetAchieved: Boolean,
        val details: String
    )

    /**
     * Estimates MTF50 (Modulation Transfer Function at 50% contrast) along a slanted or vertical edge.
     * Higher MTF50 (cycles/pixel) indicates higher genuine micro-detail resolution.
     */
    fun computeMtf50EdgeSharpness(
        pixels: IntArray,
        width: Int,
        height: Int,
        edgeX: Int = width / 2
    ): Double {
        if (width < 8 || height < 8) return 0.0

        // 1. Compute average Edge Spread Function (ESF) horizontally across the edge
        val windowSize = 8
        val startX = (edgeX - windowSize / 2).coerceAtLeast(0)
        val endX = (edgeX + windowSize / 2).coerceAtMost(width - 1)
        val esf = DoubleArray(endX - startX)

        for (y in 2 until height - 2) {
            val yOffset = y * width
            for (x in startX until endX) {
                val rgb = pixels[yOffset + x]
                val r = (rgb ushr 16) and 0xFF
                val g = (rgb ushr 8) and 0xFF
                val b = rgb and 0xFF
                val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
                esf[x - startX] += lum
            }
        }
        val rows = (height - 4).toDouble()
        for (i in esf.indices) {
            esf[i] /= rows
        }

        // 2. Line Spread Function (LSF = derivative of ESF)
        val lsf = DoubleArray(esf.size - 1)
        var maxLsf = 0.0
        for (i in lsf.indices) {
            lsf[i] = abs(esf[i + 1] - esf[i])
            if (lsf[i] > maxLsf) maxLsf = lsf[i]
        }

        // 3. Estimate MTF50: width of LSF peak at half maximum (FWHM)
        val halfMax = maxLsf / 2.0
        var left = 0
        var right = lsf.size - 1
        for (i in lsf.indices) {
            if (lsf[i] >= halfMax) {
                left = i
                break
            }
        }
        for (i in (lsf.size - 1) downTo 0) {
            if (lsf[i] >= halfMax) {
                right = i
                break
            }
        }
        val fwhm = (right - left + 1).toDouble().coerceAtLeast(1.0)
        // MTF50 roughly inversely proportional to FWHM in spatial domain
        return (1.0 / fwhm).coerceIn(0.05, 0.50)
    }

    /**
     * Calculates Signal-to-Noise Ratio (SNR) in decibels (dB):
     * SNR_dB = 10 * log10(signal_mean^2 / noise_variance).
     */
    fun computeSnrDb(pixels: IntArray): Double {
        if (pixels.isEmpty()) return 0.0

        var sum = 0.0
        for (p in pixels) {
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            sum += (0.2126 * r + 0.7152 * g + 0.0722 * b)
        }
        val mean = sum / pixels.size
        if (mean <= 0.0) return 0.0

        var varSum = 0.0
        for (p in pixels) {
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
            val diff = lum - mean
            varSum += diff * diff
        }
        val variance = (varSum / pixels.size).coerceAtLeast(0.001)

        return 10.0 * log10((mean * mean) / variance)
    }

    /**
     * Evaluates dynamic range recovery:
     * Shadow boost percentage in dark pixels (<40) without clipping highlights (>245).
     */
    fun evaluateDynamicRangeRecovery(
        original: IntArray,
        enhanced: IntArray
    ): Pair<Double, Boolean> {
        var shadowSumOrig = 0.0
        var shadowSumEnh = 0.0
        var shadowCount = 0

        var highlightClippedCount = 0
        var highlightTotalCount = 0

        for (i in original.indices) {
            val pOrig = original[i] and 0xFF
            val pEnh = enhanced[i] and 0xFF

            if (pOrig < 40) {
                shadowSumOrig += pOrig
                shadowSumEnh += pEnh
                shadowCount++
            }
            if (pOrig > 220) {
                highlightTotalCount++
                if (pEnh >= 254) {
                    highlightClippedCount++
                }
            }
        }

        val shadowBoost = if (shadowCount > 0 && shadowSumOrig > 0) {
            ((shadowSumEnh - shadowSumOrig) / shadowSumOrig) * 100.0
        } else 0.0

        val highlightsPreserved = if (highlightTotalCount > 0) {
            (highlightClippedCount.toDouble() / highlightTotalCount) < 0.15
        } else true

        return Pair(shadowBoost, highlightsPreserved)
    }

    /**
     * Evaluates color patch accuracy: computes color error in normalized Euclidean space.
     */
    fun evaluateColorPatchDelta(
        pixels: IntArray,
        targetR: Int,
        targetG: Int,
        targetB: Int
    ): Double {
        if (pixels.isEmpty()) return 0.0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0

        for (p in pixels) {
            sumR += ((p ushr 16) and 0xFF)
            sumG += ((p ushr 8) and 0xFF)
            sumB += (p and 0xFF)
        }
        val meanR = sumR / pixels.size
        val meanG = sumG / pixels.size
        val meanB = sumB / pixels.size

        val dr = meanR - targetR
        val dg = meanG - targetG
        val db = meanB - targetB

        return sqrt(dr * dr + dg * dg + db * db)
    }

    /**
     * Measures algorithmic execution latency over multiple warm iterations.
     */
    inline fun benchmarkLatencyMs(iterations: Int = 10, block: () -> Unit): Double {
        // Warm up
        block()

        val start = System.nanoTime()
        for (i in 0 until iterations) {
            block()
        }
        val totalNs = System.nanoTime() - start
        return (totalNs / iterations.toDouble()) / 1_000_000.0
    }
}
