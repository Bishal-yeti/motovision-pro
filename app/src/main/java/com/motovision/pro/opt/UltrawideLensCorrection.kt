package com.motovision.pro.opt

import kotlin.math.sqrt

/**
 * Optical Distortion and Chromatic Aberration Correction for the 5 MP Ultrawide Module (~118° FoV).
 * Implements radial Brown-Conrady barrel distortion correction and peripheral lateral color fringing mitigation.
 */
object UltrawideLensCorrection {

    /**
     * Corrects optical barrel distortion using Brown-Conrady radial polynomial.
     *
     * @param pixels Input image ARGB array
     * @param width Image width
     * @param height Image height
     * @param k1 Primary radial distortion coefficient (typically -0.15 to -0.25 for wide angle)
     * @param k2 Secondary radial distortion coefficient (typically 0.02 to 0.08)
     */
    fun correctDistortion(
        pixels: IntArray,
        width: Int,
        height: Int,
        k1: Float = -0.18f,
        k2: Float = 0.04f,
        output: IntArray = IntArray(pixels.size)
    ): IntArray {
        val cx = width / 2.0f
        val cy = height / 2.0f
        val maxR = sqrt(cx * cx + cy * cy)

        val threadCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val rowsPerChunk = Math.ceil(height.toDouble() / threadCount).toInt()

        val threads = Array(threadCount) { threadIdx ->
            val startY = threadIdx * rowsPerChunk
            val endY = Math.min(startY + rowsPerChunk, height)

            Thread {
                for (y in startY until endY) {
                    val dy = (y - cy) / maxR
                    val rowOffset = y * width

                    for (x in 0 until width) {
                        val dx = (x - cx) / maxR
                        val r2 = dx * dx + dy * dy
                        val r4 = r2 * r2

                        // Radial distortion factor
                        val factor = 1.0f + k1 * r2 + k2 * r4

                        val srcX = cx + dx * factor * maxR
                        val srcY = cy + dy * factor * maxR

                        output[rowOffset + x] = bilinearSample(pixels, width, height, srcX, srcY)
                    }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        return output
    }

    /**
     * Lateral Chromatic Aberration Mitigation.
     * Slightly scales the Red and Blue channels radially relative to Green at the frame edges.
     */
    fun correctChromaticAberration(
        pixels: IntArray,
        width: Int,
        height: Int,
        caRedScale: Float = 1.0015f,
        caBlueScale: Float = 0.9985f,
        output: IntArray = IntArray(pixels.size)
    ): IntArray {
        val cx = width / 2.0f
        val cy = height / 2.0f

        for (y in 0 until height) {
            val dy = y - cy
            val rowOffset = y * width

            for (x in 0 until width) {
                val dx = x - cx
                val origPixel = pixels[rowOffset + x]
                val a = (origPixel ushr 24) and 0xFF
                val g = (origPixel ushr 8) and 0xFF // Green channel remains unshifted anchor

                // Sample Red channel slightly inward/outward
                val srcRx = cx + dx * caRedScale
                val srcRy = cy + dy * caRedScale
                val pR = bilinearSample(pixels, width, height, srcRx, srcRy)
                val r = (pR ushr 16) and 0xFF

                // Sample Blue channel
                val srcBx = cx + dx * caBlueScale
                val srcBy = cy + dy * caBlueScale
                val pB = bilinearSample(pixels, width, height, srcBx, srcBy)
                val b = pB and 0xFF

                output[rowOffset + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        return output
    }

    private fun bilinearSample(pixels: IntArray, width: Int, height: Int, x: Float, y: Float): Int {
        val x0 = Math.floor(x.toDouble()).toInt().coerceIn(0, width - 1)
        val y0 = Math.floor(y.toDouble()).toInt().coerceIn(0, height - 1)
        val x1 = (x0 + 1).coerceIn(0, width - 1)
        val y1 = (y0 + 1).coerceIn(0, height - 1)

        val fx = x - x0
        val fy = y - y0
        val invFx = 1.0f - fx
        val invFy = 1.0f - fy

        val p00 = pixels[y0 * width + x0]
        val p10 = pixels[y0 * width + x1]
        val p01 = pixels[y1 * width + x0]
        val p11 = pixels[y1 * width + x1]

        val a = (p00 ushr 24) and 0xFF

        val r = (
            ((p00 ushr 16) and 0xFF) * invFx * invFy +
            ((p10 ushr 16) and 0xFF) * fx * invFy +
            ((p01 ushr 16) and 0xFF) * invFx * fy +
            ((p11 ushr 16) and 0xFF) * fx * fy
        ).toInt().coerceIn(0, 255)

        val g = (
            ((p00 ushr 8) and 0xFF) * invFx * invFy +
            ((p10 ushr 8) and 0xFF) * fx * invFy +
            ((p01 ushr 8) and 0xFF) * invFx * fy +
            ((p11 ushr 8) and 0xFF) * fx * fy
        ).toInt().coerceIn(0, 255)

        val b = (
            (p00 and 0xFF) * invFx * invFy +
            (p10 and 0xFF) * fx * invFy +
            (p01 and 0xFF) * invFx * fy +
            (p11 and 0xFF) * fx * fy
        ).toInt().coerceIn(0, 255)

        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
