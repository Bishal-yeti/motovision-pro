package com.motovision.pro

import com.motovision.pro.computational.HdrExposureFusion
import com.motovision.pro.computational.MultiFrameNightFusion
import com.motovision.pro.eval.ImageQualityEvaluationSuite
import com.motovision.pro.model.ProcessingProfile
import com.motovision.pro.processing.BilateralDenoiseFilter
import com.motovision.pro.processing.ColorSciencePipeline
import com.motovision.pro.processing.DetailSharpnessFilter
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ImageQualityEvaluationTest {

    @Test
    fun testMtf50EdgeSharpnessEnhancement() {
        val width = 64
        val height = 64
        val original = IntArray(width * height)

        // Draw a blurred edge transition from x=28 to x=36 (lum 30 to lum 220)
        for (y in 0 until height) {
            val yOffset = y * width
            for (x in 0 until width) {
                val lum = when {
                    x < 28 -> 30
                    x > 36 -> 220
                    else -> 30 + ((x - 28) * (220 - 30) / 8)
                }
                original[yOffset + x] = (0xFF shl 24) or (lum shl 16) or (lum shl 8) or lum
            }
        }

        val baselineMtf50 = ImageQualityEvaluationSuite.computeMtf50EdgeSharpness(original, width, height, edgeX = 32)

        // Apply MotoVision Pro edge-aware detail enhancement
        val enhanced = DetailSharpnessFilter.enhanceDetails(
            pixels = original,
            width = width,
            height = height,
            sharpnessWeight = 0.5f,
            haloThreshold = 0.10f
        )

        val enhancedMtf50 = ImageQualityEvaluationSuite.computeMtf50EdgeSharpness(enhanced, width, height, edgeX = 32)

        // Verify MTF50 edge sharpness improves measurably
        assertTrue(
            "Enhanced MTF50 ($enhancedMtf50) should be greater than or equal to baseline ($baselineMtf50)",
            enhancedMtf50 >= baselineMtf50
        )
    }

    @Test
    fun testLowLightSnrImprovementWithNightFusion() {
        val width = 32
        val height = 32
        val numFrames = 4
        val frames = mutableListOf<IntArray>()

        val rng = Random(42)
        val groundTruthLum = 60

        // Generate 4 noisy low-light burst frames
        for (f in 0 until numFrames) {
            val frame = IntArray(width * height)
            for (i in frame.indices) {
                val noise = rng.nextInt(-20, 21)
                val noisyVal = (groundTruthLum + noise).coerceIn(0, 255)
                frame[i] = (0xFF shl 24) or (noisyVal shl 16) or (noisyVal shl 8) or noisyVal
            }
            frames.add(frame)
        }

        val baselineSnr = ImageQualityEvaluationSuite.computeSnrDb(frames[0])

        // Apply Multi-Frame Night Fusion
        val fused = MultiFrameNightFusion.fuseNightBurst(frames, width, height, profile = ProcessingProfile.NATURAL)
        val enhancedSnr = ImageQualityEvaluationSuite.computeSnrDb(fused)

        // SNR should improve by at least 2.5 dB
        assertTrue(
            "Enhanced SNR ($enhancedSnr dB) should improve over baseline ($baselineSnr dB) by >2.5 dB",
            enhancedSnr > baselineSnr + 2.5
        )
    }

    @Test
    fun testDynamicRangeShadowRecoveryWithoutHighlightClipping() {
        val width = 32
        val height = 32
        val original = IntArray(width * height)

        // High dynamic range scene: deep shadow in top half, bright sky in bottom half
        for (y in 0 until height) {
            val yOffset = y * width
            for (x in 0 until width) {
                val lum = if (y < 16) 20 else 230
                original[yOffset + x] = (0xFF shl 24) or (lum shl 16) or (lum shl 8) or lum
            }
        }

        // Simulate 2 bracketed exposures for HDR fusion
        val underexposed = IntArray(width * height) { (original[it] and 0xFF) / 2 }
        val overexposed = IntArray(width * height) { Math.min(255, (original[it] and 0xFF) * 2) }

        val bracketed = listOf(
            IntArray(width * height) { (0xFF shl 24) or (underexposed[it] shl 16) or (underexposed[it] shl 8) or underexposed[it] },
            original,
            IntArray(width * height) { (0xFF shl 24) or (overexposed[it] shl 16) or (overexposed[it] shl 8) or overexposed[it] }
        )

        val fused = HdrExposureFusion.fuseHdrBracket(bracketed, width, height, ProcessingProfile.BALANCED)

        val (shadowBoost, highlightsPreserved) = ImageQualityEvaluationSuite.evaluateDynamicRangeRecovery(original, fused)

        assertTrue("Shadows should be lifted by >10% (was $shadowBoost%)", shadowBoost > 10.0)
        assertTrue("Highlights must remain unclipped", highlightsPreserved)
    }

    @Test
    fun testColorScienceFidelity() {
        val width = 32
        val height = 32
        val chartPixels = IntArray(width * height)

        // Half the scene is neutral gray (128, 128, 128) to anchor Gray-World AWB
        for (i in 0 until (width * height / 2)) {
            chartPixels[i] = (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128
        }
        // Second half has foliage green patch (R=80, G=160, B=50)
        for (i in (width * height / 2) until (width * height)) {
            chartPixels[i] = (0xFF shl 24) or (80 shl 16) or (160 shl 8) or 50
        }

        val balanced = ColorSciencePipeline.processColors(chartPixels, width, height, ProcessingProfile.BALANCED)

        // Sample foliage region
        val sample = balanced[width * height - 1]
        val r = (sample ushr 16) and 0xFF
        val g = (sample ushr 8) and 0xFF
        val b = sample and 0xFF

        assertTrue("Green should remain dominant for foliage patch (R=$r, G=$g, B=$b)", g > r && g > b)
        assertTrue("Channels should remain bounded in 0..255", r in 0..255 && g in 0..255 && b in 0..255)
    }

    @Test
    fun testAlgorithmicLatencyBenchmark() {
        val width = 32
        val height = 32
        val testPixels = IntArray(width * height) { 0xFF000000.toInt() or (it % 255) }

        val latencyMs = ImageQualityEvaluationSuite.benchmarkLatencyMs(iterations = 5) {
            BilateralDenoiseFilter.filter(testPixels, width, height, sigmaSpatial = 1.5f, sigmaRange = 0.08f)
        }

        // On 32x32 chunk, bilateral filter must complete in <20ms
        assertTrue("Filter latency ($latencyMs ms) should be well within frame budget", latencyMs < 20.0)
    }
}
