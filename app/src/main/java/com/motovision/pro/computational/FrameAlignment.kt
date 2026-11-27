package com.motovision.pro.computational

/**
 * Fast, hierarchical translational frame alignment for multi-frame burst fusion.
 * Aligns hand-held camera jitter across successive burst frames using 2D cross-correlation
 * on downsampled luminance representations.
 */
object FrameAlignment {

    data class Offset(val dx: Int, val dy: Int, val correlation: Float)

    /**
     * Estimates integer pixel translation (dx, dy) of target relative to reference frame.
     *
     * @param refPixels ARGB reference image
     * @param targetPixels ARGB target image to align
     * @param width Image width
     * @param height Image height
     * @param maxSearchRadius Maximum translation search radius in pixels (e.g., 16 to 32)
     */
    fun computeOffset(
        refPixels: IntArray,
        targetPixels: IntArray,
        width: Int,
        height: Int,
        maxSearchRadius: Int = 16
    ): Offset {
        // Downsample factor to keep alignment blazing fast on Helio G85
        val downsample = 4
        val dw = width / downsample
        val dh = height / downsample

        val refLum = downsampleLuminance(refPixels, width, height, downsample, dw, dh)
        val targetLum = downsampleLuminance(targetPixels, width, height, downsample, dw, dh)

        val dsRadius = (maxSearchRadius / downsample).coerceIn(2, 8)

        var bestDx = 0
        var bestDy = 0
        var minSad = Float.MAX_VALUE

        // Search window using Sum of Absolute Differences (SAD)
        for (dy in -dsRadius..dsRadius) {
            for (dx in -dsRadius..dsRadius) {
                var sad = 0.0f
                var count = 0

                // Sample central 60% region to avoid boundary effects
                val startY = (dh * 0.2f).toInt()
                val endY = (dh * 0.8f).toInt()
                val startX = (dw * 0.2f).toInt()
                val endX = (dw * 0.8f).toInt()

                for (y in startY until endY step 2) {
                    val ty = y + dy
                    if (ty !in 0 until dh) continue
                    val refRow = y * dw
                    val targetRow = ty * dw

                    for (x in startX until endX step 2) {
                        val tx = x + dx
                        if (tx !in 0 until dw) continue

                        val diff = refLum[refRow + x] - targetLum[targetRow + tx]
                        sad += Math.abs(diff)
                        count++
                    }
                }

                if (count > 0) {
                    val normSad = sad / count
                    if (normSad < minSad) {
                        minSad = normSad
                        bestDx = dx
                        bestDy = dy
                    }
                }
            }
        }

        // Scale offset back to full resolution
        val finalDx = bestDx * downsample
        val finalDy = bestDy * downsample

        return Offset(finalDx, finalDy, minSad)
    }

    private fun downsampleLuminance(
        pixels: IntArray,
        width: Int,
        height: Int,
        downsample: Int,
        dw: Int,
        dh: Int
    ): FloatArray {
        val lum = FloatArray(dw * dh)
        for (y in 0 until dh) {
            val srcY = y * downsample
            val srcRow = srcY * width
            val dstRow = y * dw
            for (x in 0 until dw) {
                val srcX = x * downsample
                val p = pixels[srcRow + srcX]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                lum[dstRow + x] = (r * 0.299f + g * 0.587f + b * 0.114f) / 255.0f
            }
        }
        return lum
    }

    /**
     * Warps a target pixel buffer by (dx, dy) offset, clamping borders.
     */
    fun warp(
        pixels: IntArray,
        width: Int,
        height: Int,
        offset: Offset,
        output: IntArray = IntArray(pixels.size)
    ): IntArray {
        val dx = offset.dx
        val dy = offset.dy

        if (dx == 0 && dy == 0) {
            System.arraycopy(pixels, 0, output, 0, pixels.size)
            return output
        }

        for (y in 0 until height) {
            val srcY = (y + dy).coerceIn(0, height - 1)
            val srcRow = srcY * width
            val dstRow = y * width
            for (x in 0 until width) {
                val srcX = (x + dx).coerceIn(0, width - 1)
                output[dstRow + x] = pixels[srcRow + srcX]
            }
        }
        return output
    }
}
