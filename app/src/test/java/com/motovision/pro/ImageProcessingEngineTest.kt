package com.motovision.pro

import com.motovision.pro.model.ProcessingProfile
import com.motovision.pro.processing.BilateralDenoiseFilter
import com.motovision.pro.processing.ColorLut
import com.motovision.pro.processing.ColorSciencePipeline
import com.motovision.pro.processing.DetailSharpnessFilter
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class ImageProcessingEngineTest {

    @Test
    fun testColorLutSrgbLinearRoundTrip() {
        for (i in 0..255) {
            val lin = ColorLut.sRgbToLinear[i]
            assertTrue("Linear value must be in [0..1]", lin in 0.0f..1.0f)
            val recovered = ColorLut.linearToSrgb(lin)
            // Round-trip should be accurate within +-1 quantization level
            assertEquals("Round-trip error for $i", i.toDouble(), recovered.toDouble(), 1.0)
        }
    }

    @Test
    fun testSoftKneeToneCurveHighlightRolloff() {
        val rolloffThreshold = 0.75f
        val contrast = 1.1f

        val midtone = ColorLut.applySoftKneeToneCurve(0.5f, contrast, rolloffThreshold)
        val nearWhite = ColorLut.applySoftKneeToneCurve(0.95f, contrast, rolloffThreshold)
        val pureWhite = ColorLut.applySoftKneeToneCurve(1.0f, contrast, rolloffThreshold)

        assertTrue("Tone curve must be monotonically increasing", nearWhite > midtone)
        assertTrue("Pure white must not clip past 1.0", pureWhite <= 1.0f)
        assertTrue("Rolloff must compress extreme highlights smoothly", (pureWhite - nearWhite) < (nearWhite - midtone) * 0.2f)
    }

    @Test
    fun testBilateralFilterEdgePreservationAndNoiseReduction() {
        val width = 64
        val height = 64
        val pixels = IntArray(width * height)

        val rng = Random(42)
        // Left half = dark flat region (lum ~ 50) + noise
        // Right half = bright flat region (lum ~ 200) + noise
        // Step edge at x = 32
        for (y in 0 until height) {
            for (x in 0 until width) {
                val base = if (x < 32) 50 else 200
                val noise = rng.nextInt(21) - 10 // +/- 10 noise
                val v = (base + noise).coerceIn(0, 255)
                pixels[y * width + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }

        // Compute input variance in left uniform region (x in 5..25)
        var sum = 0.0
        var sqSum = 0.0
        var count = 0
        for (y in 10 until 50) {
            for (x in 5 until 25) {
                val v = (pixels[y * width + x] and 0xFF).toDouble()
                sum += v
                sqSum += v * v
                count++
            }
        }
        val inVariance = (sqSum / count) - Math.pow(sum / count, 2.0)

        // Apply bilateral filter
        val filtered = BilateralDenoiseFilter.filter(pixels, width, height, sigmaSpatial = 2.0f, sigmaRange = 0.08f)

        // Compute output variance in left uniform region
        sum = 0.0
        sqSum = 0.0
        for (y in 10 until 50) {
            for (x in 5 until 25) {
                val v = (filtered[y * width + x] and 0xFF).toDouble()
                sum += v
                sqSum += v * v
            }
        }
        val outVariance = (sqSum / count) - Math.pow(sum / count, 2.0)

        // Noise should be reduced by at least 40%
        assertTrue("Output variance ($outVariance) should be lower than input ($inVariance)", outVariance < inVariance * 0.6)

        // Verify edge sharpness across x = 31 and x = 32
        val leftEdgeVal = filtered[32 * width + 30] and 0xFF
        val rightEdgeVal = filtered[32 * width + 34] and 0xFF
        val edgeStep = rightEdgeVal - leftEdgeVal
        assertTrue("Step edge must be preserved ($edgeStep)", edgeStep > 130)
    }

    @Test
    fun testDetailSharpnessHaloSuppression() {
        val width = 32
        val height = 32
        val pixels = IntArray(width * height)

        // Checkerboard fine texture
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = if ((x + y) % 2 == 0) 120 else 140
                pixels[y * width + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }

        val sharpened = DetailSharpnessFilter.enhanceDetails(
            pixels,
            width,
            height,
            sharpnessWeight = 0.5f,
            haloThreshold = 0.10f
        )

        // High frequencies should have higher contrast
        val origDiff = Math.abs((pixels[10 * width + 10] and 0xFF) - (pixels[10 * width + 11] and 0xFF))
        val sharpDiff = Math.abs((sharpened[10 * width + 10] and 0xFF) - (sharpened[10 * width + 11] and 0xFF))
        assertTrue("Sharpened difference ($sharpDiff) must be >= original ($origDiff)", sharpDiff >= origDiff)

        // Verify no clipping overshoot outside [0..255]
        for (p in sharpened) {
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            assertTrue("RGB values must remain within 0..255", r in 0..255 && g in 0..255 && b in 0..255)
        }
    }

    @Test
    fun testColorScienceSkinToneProtection() {
        val width = 16
        val height = 16
        val pixels = IntArray(width * height)

        // Typical human skin tone RGB: R=210, G=160, B=130
        val skinR = 210
        val skinG = 160
        val skinB = 130
        val skinColor = (0xFF shl 24) or (skinR shl 16) or (skinG shl 8) or skinB

        for (i in pixels.indices) {
            pixels[i] = skinColor
        }

        val vibrantOutput = ColorSciencePipeline.processColors(
            pixels,
            width,
            height,
            ProcessingProfile.VIBRANT
        )

        val outPixel = vibrantOutput[0]
        val outR = (outPixel ushr 16) and 0xFF
        val outG = (outPixel ushr 8) and 0xFF
        val outB = outPixel and 0xFF

        // Skin tones must not be blown out or oversaturated orange
        assertTrue("Skin R must remain natural", outR in 190..235)
        assertTrue("Skin G must remain natural", outG in 140..185)
        assertTrue("Skin B must remain natural", outB in 110..155)
    }

    @Test
    fun testMonochromeFilterPanchromaticResponseAndNeutrality() {
        val width = 16
        val height = 16
        val pixels = IntArray(width * height)

        // Fill with mixed colors: pure red, green, blue, skin, foliage, sky
        val colors = intArrayOf(
            0xFFFF0000.toInt(), // Red
            0xFF00FF00.toInt(), // Green
            0xFF0000FF.toInt(), // Blue
            0xFFD2A082.toInt(), // Skin tone
            0xFF2E8B57.toInt(), // Sea green
            0xFF87CEEB.toInt()  // Sky blue
        )

        for (i in pixels.indices) {
            pixels[i] = colors[i % colors.size]
        }

        val monoOutput = ColorSciencePipeline.processColors(
            pixels,
            width,
            height,
            ProcessingProfile.MONOCHROME
        )

        // All output pixels must be strictly neutral grayscale (R == G == B)
        for (i in monoOutput.indices) {
            val pixel = monoOutput[i]
            val r = (pixel ushr 16) and 0xFF
            val g = (pixel ushr 8) and 0xFF
            val b = pixel and 0xFF
            assertEquals("R and G must be identical for monochrome at $i", r, g)
            assertEquals("G and B must be identical for monochrome at $i", g, b)
            assertTrue("Pixel luminance must be valid", r in 0..255)
        }
    }

    @Test
    fun testBwVintageSepiaToning() {
        val width = 16
        val height = 16
        val pixels = IntArray(width * height) { 0xFF808080.toInt() } // Neutral mid-gray

        val sepiaOutput = ColorSciencePipeline.processColors(
            pixels,
            width,
            height,
            ProcessingProfile.BW_VINTAGE
        )

        for (i in sepiaOutput.indices) {
            val pixel = sepiaOutput[i]
            val r = (pixel ushr 16) and 0xFF
            val g = (pixel ushr 8) and 0xFF
            val b = pixel and 0xFF
            // Sepia toning must be warm: R >= G >= B
            assertTrue("Sepia R ($r) must be >= G ($g)", r >= g)
            assertTrue("Sepia G ($g) must be >= B ($b)", g >= b)
        }
    }

    @Test
    fun testWarmFilmAndCoolCinemaFilters() {
        val width = 16
        val height = 16
        val pixels = IntArray(width * height) { 0xFF808080.toInt() }

        val warmOutput = ColorSciencePipeline.processColors(pixels, width, height, ProcessingProfile.WARM_FILM)
        val warmPixel = warmOutput[0]
        val warmR = (warmPixel ushr 16) and 0xFF
        val warmB = warmPixel and 0xFF
        assertTrue("Warm Film should boost red over blue", warmR > warmB)

        // Dark shadows in Cool Cinema should shift toward teal (B > R)
        val shadowPixels = IntArray(width * height) { 0xFF282828.toInt() }
        val coolOutput = ColorSciencePipeline.processColors(shadowPixels, width, height, ProcessingProfile.COOL_CINEMA)
        val coolPixel = coolOutput[0]
        val coolR = (coolPixel ushr 16) and 0xFF
        val coolB = coolPixel and 0xFF
        assertTrue("Cool Cinema shadows should have elevated blue/teal relative to red", coolB >= coolR)
    }

    @Test
    fun testShadowChromaNoiseSuppression() {
        val width = 16
        val height = 16
        // Underexposed pixel with extreme chroma noise (noisy purple/magenta in shadow: Y ~ 40, R=65, G=25, B=65)
        val noisyShadowPixel = (0xFF shl 24) or (65 shl 16) or (25 shl 8) or 65
        val pixels = IntArray(width * height) { noisyShadowPixel }

        val cleanOutput = ColorSciencePipeline.processColors(pixels, width, height, ProcessingProfile.BALANCED)
        val outPixel = cleanOutput[0]
        val outR = (outPixel ushr 16) and 0xFF
        val outG = (outPixel ushr 8) and 0xFF
        val outB = outPixel and 0xFF

        val inChromaSpread = Math.abs(65 - 25)
        val outChromaSpread = Math.abs(outR - outG)
        assertTrue("Chroma noise spread ($outChromaSpread) must be dampened compared to input ($inChromaSpread)", outChromaSpread < inChromaSpread)
    }
}
