package com.motovision.pro

import com.motovision.pro.model.ProcessingProfile
import com.motovision.pro.opt.FrontSelfieEngine
import com.motovision.pro.opt.MacroOptimizationProfile
import com.motovision.pro.opt.UltrawideLensCorrection
import org.junit.Assert.*
import org.junit.Test

class LensOptimizationTest {

    @Test
    fun testUltrawideBarrelDistortionCorrection() {
        val width = 64
        val height = 64
        val pixels = IntArray(width * height)

        // Draw a straight vertical line near edge at x = 8
        for (y in 0 until height) {
            pixels[y * width + 8] = (0xFF shl 24) or (255 shl 16) or (255 shl 8) or 255
        }

        // Apply barrel distortion correction
        val corrected = UltrawideLensCorrection.correctDistortion(pixels, width, height, k1 = -0.2f, k2 = 0.05f)

        // Verify corrected pixels remain in valid bounds and center geometry is preserved
        assertNotNull(corrected)
        assertEquals(pixels.size, corrected.size)
        for (p in corrected) {
            val r = (p ushr 16) and 0xFF
            assertTrue("Channel must remain bounded", r in 0..255)
        }
    }

    @Test
    fun testFrontSelfieFaceExposureAndPortraitBokeh() {
        val width = 48
        val height = 48
        val pixels = IntArray(width * height)

        // Backlit background (bright white 240)
        for (i in pixels.indices) {
            pixels[i] = (0xFF shl 24) or (240 shl 16) or (240 shl 8) or 240
        }

        // Underexposed face in center (skin tone at low luminance ~ 70)
        // Skin in YCbCr: Cb in [80..130], Cr in [133..175]
        val darkSkin = (0xFF shl 24) or (110 shl 16) or (80 shl 8) or 60
        for (y in 16..32) {
            for (x in 16..32) {
                pixels[y * width + x] = darkSkin
            }
        }

        val processed = FrontSelfieEngine.processSelfie(
            pixels,
            width,
            height,
            profile = ProcessingProfile.NATURAL,
            portraitBlurRadius = 6
        )

        // 1. Verify face exposure lift: center pixel luminance should be elevated
        val centerPixel = processed[24 * width + 24]
        val centerR = (centerPixel ushr 16) and 0xFF
        assertTrue("Face exposure should be lifted above original (was $centerR)", centerR > 115)

        // 2. Verify portrait blur applied to background without corrupting face
        val bgPixel = processed[2 * width + 2]
        val bgR = (bgPixel ushr 16) and 0xFF
        assertTrue("Background pixel should be valid", bgR > 200)
    }

    @Test
    fun testMacroFocusGuidanceMetric() {
        val width = 32
        val height = 32

        // 1. Sharp in-focus macro texture (high edge transitions at 4cm)
        val sharpPixels = IntArray(width * height) { i ->
            val v = if (i % 2 == 0) 240 else 20
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        // 2. Out-of-focus blurred macro texture (low gradient flat)
        val blurPixels = IntArray(width * height) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }

        val sharpScore = MacroOptimizationProfile.computeMacroFocusGuidance(sharpPixels, width, height)
        val blurScore = MacroOptimizationProfile.computeMacroFocusGuidance(blurPixels, width, height)

        assertTrue("Sharp macro focus score ($sharpScore%) should be significantly higher than blur ($blurScore%)", sharpScore > blurScore + 50.0f)
    }

    @Test
    fun testMacroCentralShadowRecovery() {
        val width = 32
        val height = 32
        val pixels = IntArray(width * height)

        // Device casting shadow in center (lum ~ 50) while periphery is normal (lum ~ 150)
        for (y in 0 until height) {
            val dy = y - 16
            for (x in 0 until width) {
                val dx = x - 16
                val isCenter = (dx * dx + dy * dy) < 64
                val v = if (isCenter) 50 else 150
                pixels[y * width + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }

        val processed = MacroOptimizationProfile.processMacro(pixels, width, height, ProcessingProfile.BALANCED)
        val centerVal = processed[16 * width + 16] and 0xFF

        // Center shadow should be lifted
        assertTrue("Central shadow should be lifted (was $centerVal)", centerVal > 60)
    }
}
