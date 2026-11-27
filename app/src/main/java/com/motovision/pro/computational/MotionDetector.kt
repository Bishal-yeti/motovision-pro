package com.motovision.pro.computational

/**
 * Robust Motion Detector and De-Ghosting Engine.
 * Evaluates photometric distance between aligned burst frames and produces a smooth
 * continuous confidence mask [0.0 = static scene, 1.0 = fast moving subject].
 */
object MotionDetector {

    /**
     * Generates a float motion mask of size width * height.
     * Values near 0.0 indicate high temporal consistency (full multi-frame integration).
     * Values near 1.0 indicate moving subjects (revert to reference frame to eliminate ghosting).
     *
     * @param refPixels Aligned reference frame ARGB
     * @param alignedPixels Aligned auxiliary frame ARGB
     * @param width Image width
     * @param height Image height
     * @param noiseTolerance Noise margin (typically 0.04 to 0.08)
     */
    fun computeMotionMask(
        refPixels: IntArray,
        alignedPixels: IntArray,
        width: Int,
        height: Int,
        noiseTolerance: Float = 0.06f,
        outputMask: FloatArray = FloatArray(refPixels.size)
    ): FloatArray {
        val total = refPixels.size

        for (i in 0 until total) {
            val pRef = refPixels[i]
            val rRef = (pRef ushr 16) and 0xFF
            val gRef = (pRef ushr 8) and 0xFF
            val bRef = pRef and 0xFF

            val pAlg = alignedPixels[i]
            val rAlg = (pAlg ushr 16) and 0xFF
            val gAlg = (pAlg ushr 8) and 0xFF
            val bAlg = pAlg and 0xFF

            // Normalized Euclidean color difference
            val dr = (rRef - rAlg) / 255.0f
            val dg = (gRef - gAlg) / 255.0f
            val db = (bRef - bAlg) / 255.0f
            val diff = Math.sqrt((dr * dr + dg * dg + db * db) / 3.0).toFloat()

            // Soft non-linear transition above noise floor
            val motion = if (diff <= noiseTolerance) {
                0.0f
            } else {
                val excess = diff - noiseTolerance
                // Sigmoidal ramping from 0.0 to 1.0
                (excess * 5.0f).coerceIn(0.0f, 1.0f)
            }

            outputMask[i] = motion
        }

        return outputMask
    }
}
