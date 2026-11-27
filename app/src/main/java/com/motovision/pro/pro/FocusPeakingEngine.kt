package com.motovision.pro.pro

import android.graphics.Bitmap
import android.graphics.Color
import java.nio.ByteBuffer

/**
 * Real-time Focus Peaking Engine for manual focus assistance and close-up macro shots.
 * Computes high-pass spatial gradient across luminance frames to highlight sharp, in-focus edges.
 */
object FocusPeakingEngine {

    const val PEAKING_COLOR_GREEN = 0xFF39FF14.toInt() // Neon green
    const val PEAKING_COLOR_CYAN = 0xFF00E5FF.toInt()  // Cyan accent
    const val DEFAULT_PEAKING_THRESHOLD = 32

    /**
     * Pure algorithmic edge detector suitable for JVM unit testing and performance evaluation.
     * Evaluates horizontal and vertical gradient magnitude:
     * |L(x+1, y) - L(x-1, y)| + |L(x, y+1) - L(x, y-1)| > threshold.
     */
    fun detectPeakingEdges(
        luminance: ByteArray,
        width: Int,
        height: Int,
        threshold: Int = DEFAULT_PEAKING_THRESHOLD
    ): BooleanArray {
        val edges = BooleanArray(width * height)
        for (y in 1 until height - 1) {
            val yOffset = y * width
            val yAbove = (y - 1) * width
            val yBelow = (y + 1) * width

            for (x in 1 until width - 1) {
                val gx = (luminance[yOffset + x + 1].toInt() and 0xFF) - (luminance[yOffset + x - 1].toInt() and 0xFF)
                val gy = (luminance[yBelow + x].toInt() and 0xFF) - (luminance[yAbove + x].toInt() and 0xFF)

                val magnitude = Math.abs(gx) + Math.abs(gy)
                if (magnitude > threshold) {
                    edges[yOffset + x] = true
                }
            }
        }
        return edges
    }

    /**
     * Generates a transparent overlay bitmap with colored peaking pixels at detected edges.
     * Downsamples the input analysis buffer to maintain <3ms processing latency on Helio G85.
     */
    fun generatePeakingBitmap(
        yBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        downsample: Int = 2,
        threshold: Int = DEFAULT_PEAKING_THRESHOLD,
        peakingColor: Int = PEAKING_COLOR_GREEN
    ): Bitmap? {
        val outWidth = width / downsample
        val outHeight = height / downsample
        if (outWidth <= 0 || outHeight <= 0) return null

        val pixels = IntArray(outWidth * outHeight)
        val lum = ByteArray(outWidth * outHeight)

        // Subsample luminance to target grid
        for (y in 0 until outHeight) {
            val srcY = y * downsample
            val srcRow = srcY * rowStride
            val dstRow = y * outWidth
            for (x in 0 until outWidth) {
                val srcX = x * downsample
                val pos = srcRow + srcX
                if (pos < yBuffer.remaining()) {
                    lum[dstRow + x] = yBuffer.get(pos)
                }
            }
        }

        // Detect edges
        for (y in 1 until outHeight - 1) {
            val row = y * outWidth
            val above = (y - 1) * outWidth
            val below = (y + 1) * outWidth

            for (x in 1 until outWidth - 1) {
                val gx = (lum[row + x + 1].toInt() and 0xFF) - (lum[row + x - 1].toInt() and 0xFF)
                val gy = (lum[below + x].toInt() and 0xFF) - (lum[above + x].toInt() and 0xFF)
                val mag = Math.abs(gx) + Math.abs(gy)

                if (mag > threshold) {
                    pixels[row + x] = peakingColor
                } else {
                    pixels[row + x] = Color.TRANSPARENT
                }
            }
        }

        val bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, outWidth, 0, 0, outWidth, outHeight)
        return bitmap
    }
}
