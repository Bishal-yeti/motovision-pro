package com.motovision.pro

import com.motovision.pro.computational.FrameAlignment
import com.motovision.pro.computational.HdrExposureFusion
import com.motovision.pro.computational.MotionDetector
import com.motovision.pro.computational.MultiFrameNightFusion
import com.motovision.pro.computational.SceneAnalyzer
import com.motovision.pro.model.CaptureMode
import com.motovision.pro.model.ProcessingProfile
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class ComputationalPhotographyTest {

    @Test
    fun testFrameAlignmentAndWarp() {
        val width = 64
        val height = 64
        val refPixels = IntArray(width * height)

        // Create high-contrast geometric cross pattern
        for (y in 0 until height) {
            for (x in 0 until width) {
                val isCross = (x in 28..36) || (y in 28..36)
                val v = if (isCross) 220 else 40
                refPixels[y * width + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }

        // Shift target by (+4, +4)
        val shiftX = 4
        val shiftY = 4
        val targetPixels = IntArray(width * height)
        for (y in 0 until height) {
            val srcY = (y - shiftY).coerceIn(0, height - 1)
            for (x in 0 until width) {
                val srcX = (x - shiftX).coerceIn(0, width - 1)
                targetPixels[y * width + x] = refPixels[srcY * width + srcX]
            }
        }

        val offset = FrameAlignment.computeOffset(refPixels, targetPixels, width, height, maxSearchRadius = 16)
        assertEquals("dx should be detected accurately", shiftX, offset.dx)
        assertEquals("dy should be detected accurately", shiftY, offset.dy)

        // Verify that warping target by detected offset restores alignment
        val warped = FrameAlignment.warp(targetPixels, width, height, offset)
        val centerValRef = refPixels[32 * width + 32] and 0xFF
        val centerValWarped = warped[32 * width + 32] and 0xFF
        assertEquals("Warped center should match reference center", centerValRef, centerValWarped)
    }

    @Test
    fun testMotionDetectorGhostingPrevention() {
        val width = 32
        val height = 32
        val refPixels = IntArray(width * height) { (0xFF shl 24) or (100 shl 16) or (100 shl 8) or 100 }
        val alignedPixels = refPixels.clone()

        // Place a moving object in the auxiliary frame (circle at center)
        for (y in 12..20) {
            for (x in 12..20) {
                alignedPixels[y * width + x] = (0xFF shl 24) or (240 shl 16) or (240 shl 8) or 240
            }
        }

        val motionMask = MotionDetector.computeMotionMask(refPixels, alignedPixels, width, height)

        // Background region (x=2, y=2) must have zero motion
        assertEquals("Background should have 0 motion", 0.0f, motionMask[2 * width + 2], 0.01f)

        // Object region (x=16, y=16) must have high motion confidence
        assertTrue("Moving subject should have high motion weight", motionMask[16 * width + 16] > 0.8f)
    }

    @Test
    fun testMultiFrameNightFusionNoiseReduction() {
        val width = 48
        val height = 48
        val total = width * height
        val frameCount = 4
        val rng = Random(123)

        val frames = mutableListOf<IntArray>()
        for (f in 0 until frameCount) {
            val buf = IntArray(total)
            for (i in 0 until total) {
                val base = 40 // low-light flat scene
                val noise = rng.nextInt(21) - 10
                val v = (base + noise).coerceIn(0, 255)
                buf[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            frames.add(buf)
        }

        // Measure input noise variance on frame 0
        var sum = 0.0
        var sqSum = 0.0
        for (v in frames[0]) {
            val lum = (v and 0xFF).toDouble()
            sum += lum
            sqSum += lum * lum
        }
        val inVariance = (sqSum / total) - Math.pow(sum / total, 2.0)

        // Fuse burst
        val fused = MultiFrameNightFusion.fuseNightBurst(frames, width, height, ProcessingProfile.NATURAL)

        // Measure output variance
        sum = 0.0
        sqSum = 0.0
        for (v in fused) {
            val lum = (v and 0xFF).toDouble()
            sum += lum
            sqSum += lum * lum
        }
        val outVariance = (sqSum / total) - Math.pow(sum / total, 2.0)

        // Temporal averaging across 4 frames reduces noise variance by ~50%+
        assertTrue("Output variance ($outVariance) must be lower than input ($inVariance)", outVariance < inVariance * 0.55)
    }

    @Test
    fun testHdrExposureFusion() {
        val width = 32
        val height = 32
        val total = width * height

        // 1. Underexposed frame: dark shadows, well-exposed highlight
        val under = IntArray(total) { (0xFF shl 24) or (20 shl 16) or (20 shl 8) or 20 }
        // Highlight in center (sky window)
        for (y in 12..20) {
            for (x in 12..20) under[y * width + x] = (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128
        }

        // 2. Overexposed frame: well-exposed shadows, blown out highlight (255)
        val over = IntArray(total) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        for (y in 12..20) {
            for (x in 12..20) over[y * width + x] = (0xFF shl 24) or (255 shl 16) or (255 shl 8) or 255
        }

        val fused = HdrExposureFusion.fuseHdrBracket(listOf(under, over), width, height, ProcessingProfile.NATURAL)

        val shadowVal = fused[2 * width + 2] and 0xFF
        val highlightVal = fused[16 * width + 16] and 0xFF

        // Shadows should not be crushed (< 30) and highlights should not be clipped (255)
        assertTrue("Shadow detail should be recovered (was $shadowVal)", shadowVal > 35)
        assertTrue("Highlight detail should be retained without clipping (was $highlightVal)", highlightVal < 250)
    }

    @Test
    fun testSceneAnalyzerDecisions() {
        // Test Low-Light Histogram
        val darkHist = IntArray(256)
        darkHist[15] = 1000
        darkHist[30] = 800
        val darkMetrics = SceneAnalyzer.analyzeScene(darkHist)
        assertTrue(darkMetrics.isLowLight)
        assertEquals(CaptureMode.NIGHT, darkMetrics.recommendedMode)
        assertTrue(darkMetrics.suggestedIso >= 800)

        // Test High Dynamic Range Histogram
        val hdrHist = IntArray(256)
        hdrHist[5] = 500   // deep shadows
        hdrHist[128] = 200 // midtones
        hdrHist[250] = 500 // bright highlights
        val hdrMetrics = SceneAnalyzer.analyzeScene(hdrHist)
        assertTrue(hdrMetrics.isHighDynamicRange)
        assertEquals(CaptureMode.HDR, hdrMetrics.recommendedMode)

        // Test Fast Motion Scene
        val prevLum = FloatArray(100) { 0.2f }
        val currLum = FloatArray(100) { if (it % 2 == 0) 0.8f else 0.2f }
        val normalHist = IntArray(256) { if (it in 100..150) 10 else 0 }
        val motionMetrics = SceneAnalyzer.analyzeScene(normalHist, prevLum, currLum)
        assertTrue(motionMetrics.isFastMotion)
        assertEquals(CaptureMode.PHOTO, motionMetrics.recommendedMode)
        assertEquals(ProcessingProfile.DETAILED, motionMetrics.recommendedProfile)
    }
}
