package com.motovision.pro.computational

import com.motovision.pro.model.CaptureMode
import com.motovision.pro.model.ProcessingProfile

/**
 * Intelligent Scene Analyzer for Auto Photography.
 * Evaluates scene brightness, dynamic range, and motion to automatically select
 * optimal capture strategies and image-processing profiles.
 */
object SceneAnalyzer {

    data class SceneMetrics(
        val averageLuminance: Float,      // 0.0 (pitch black) to 1.0 (overexposed)
        val dynamicRangeEv: Float,        // estimated dynamic range in EV
        val motionMagnitude: Float,       // estimated frame-to-frame movement (0.0 to 1.0)
        val isLowLight: Boolean,
        val isHighDynamicRange: Boolean,
        val isFastMotion: Boolean,
        val recommendedMode: CaptureMode,
        val recommendedProfile: ProcessingProfile,
        val suggestedIso: Int,
        val suggestedShutterSpeedDenominator: Int
    )

    /**
     * Analyzes image luminance and temporal difference to compute scene characteristics.
     *
     * @param luminanceHistogram 256-bin luminance histogram from live camera stream
     * @param prevLuminance Previous frame downsampled luminance for motion estimation
     * @param currLuminance Current frame downsampled luminance
     */
    fun analyzeScene(
        luminanceHistogram: IntArray,
        prevLuminance: FloatArray? = null,
        currLuminance: FloatArray? = null
    ): SceneMetrics {
        var totalPixels = 0L
        var weightedSum = 0.0

        for (i in 0 until 256) {
            val count = luminanceHistogram[i]
            totalPixels += count
            weightedSum += i * count
        }

        val avgLum = if (totalPixels > 0) (weightedSum / (totalPixels * 255.0)).toFloat() else 0.5f

        // Estimate Dynamic Range: find 5th and 95th percentiles
        val p5Threshold = (totalPixels * 0.05).toLong()
        val p95Threshold = (totalPixels * 0.95).toLong()

        var cum = 0L
        var p5 = 0
        var p95 = 255

        for (i in 0 until 256) {
            cum += luminanceHistogram[i]
            if (p5 == 0 && cum >= p5Threshold) p5 = i
            if (cum >= p95Threshold) {
                p95 = i
                break
            }
        }

        val lumRatio = Math.max(1.0, (p95 + 1.0) / Math.max(1.0, p5.toDouble()))
        val dynamicRangeEv = (Math.log(lumRatio) / Math.log(2.0)).toFloat()

        // Estimate frame-to-frame motion
        var motionMag = 0.0f
        if (prevLuminance != null && currLuminance != null && prevLuminance.size == currLuminance.size) {
            var diffSum = 0.0f
            val len = prevLuminance.size
            for (i in 0 until len step 2) {
                diffSum += Math.abs(prevLuminance[i] - currLuminance[i])
            }
            motionMag = (diffSum / (len / 2)).coerceIn(0.0f, 1.0f)
        }

        val isLowLight = avgLum < 0.22f
        val isHighDynamicRange = dynamicRangeEv > 4.5f
        val isFastMotion = motionMag > 0.12f

        // Intelligent selection of Capture Mode and Processing Profile
        val (recommendedMode, recommendedProfile) = when {
            isLowLight && !isFastMotion -> CaptureMode.NIGHT to ProcessingProfile.BALANCED
            isHighDynamicRange && !isFastMotion -> CaptureMode.HDR to ProcessingProfile.NATURAL
            isFastMotion -> CaptureMode.PHOTO to ProcessingProfile.DETAILED
            else -> CaptureMode.PHOTO to ProcessingProfile.BALANCED
        }

        // Suggest exposure parameters based on brightness
        val (suggestedIso, suggestedShutter) = when {
            avgLum < 0.10f -> 1600 to 15   // 1/15s, ISO 1600
            avgLum < 0.25f -> 800 to 30    // 1/30s, ISO 800
            avgLum < 0.50f -> 200 to 120   // 1/120s, ISO 200
            avgLum < 0.75f -> 100 to 500   // 1/500s, ISO 100
            else -> 100 to 1000            // 1/1000s, ISO 100
        }

        return SceneMetrics(
            averageLuminance = avgLum,
            dynamicRangeEv = dynamicRangeEv,
            motionMagnitude = motionMag,
            isLowLight = isLowLight,
            isHighDynamicRange = isHighDynamicRange,
            isFastMotion = isFastMotion,
            recommendedMode = recommendedMode,
            recommendedProfile = recommendedProfile,
            suggestedIso = suggestedIso,
            suggestedShutterSpeedDenominator = suggestedShutter
        )
    }
}
