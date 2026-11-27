package com.motovision.pro.processing

import com.motovision.pro.model.ProcessingProfile

/**
 * Natural Color Science Pipeline for Motorola Moto G23.
 * Corrects MediaTek ISP color biases:
 * - Neutralizes cold/blue and unnatural yellow-green tint via Gray-World AWB
 * - Preserves natural skin tones and skin texture (skin locus protection)
 * - Calibrates foliage greens toward realistic botanical chlorophyl
 * - Balances red channel to prevent sensor clipping / posterization
 * - Applies soft-knee tone curves for natural contrast and cloud highlight retention
 */
object ColorSciencePipeline {

    fun processColors(
        pixels: IntArray,
        width: Int,
        height: Int,
        profile: ProcessingProfile,
        output: IntArray = IntArray(pixels.size)
    ): IntArray {
        // Step 1: Estimate scene white balance gains via Gray-World algorithm
        val (gainR, gainG, gainB) = estimateAwbGains(pixels, width, height)

        val contrast = profile.contrastBoost
        val saturation = profile.saturationScale
        val rolloff = profile.highlightRolloff

        val threadCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val rowsPerChunk = Math.ceil(height.toDouble() / threadCount).toInt()

        val threads = Array(threadCount) { threadIdx ->
            val startY = threadIdx * rowsPerChunk
            val endY = Math.min(startY + rowsPerChunk, height)

            Thread {
                for (y in startY until endY) {
                    val rowOffset = y * width
                    for (x in 0 until width) {
                        val idx = rowOffset + x
                        val origPixel = pixels[idx]
                        val a = (origPixel ushr 24) and 0xFF
                        var r = (origPixel ushr 16) and 0xFF
                        var g = (origPixel ushr 8) and 0xFF
                        var b = origPixel and 0xFF

                        // 1. Convert to linear RGB and apply AWB gains
                        var linR = ColorLut.sRgbToLinear[r] * gainR
                        var linG = ColorLut.sRgbToLinear[g] * gainG
                        var linB = ColorLut.sRgbToLinear[b] * gainB

                        // Dedicated Custom Black/White Filter Path
                        if (profile.isMonochrome) {
                            // Panchromatic Spectral Response (emulating fine-art monochrome film):
                            // 35% Red + 50% Green + 15% Blue provides rich midtone separation,
                            // smooth skin tones, and dynamic cloud contrast.
                            val monoLin = (linR * 0.35f + linG * 0.50f + linB * 0.15f).coerceAtLeast(0f)
                            val toneMono = ColorLut.applySoftKneeToneCurve(monoLin, contrast, rolloff)
                            val finalMono = ColorLut.linearToSrgb(toneMono)
                            output[idx] = (a shl 24) or (finalMono shl 16) or (finalMono shl 8) or finalMono
                            continue
                        }

                        // 2. Compute Luminance and Chrominance in YCbCr
                        val normR = (linR * 255f).coerceIn(0f, 255f)
                        val normG = (linG * 255f).coerceIn(0f, 255f)
                        val normB = (linB * 255f).coerceIn(0f, 255f)

                        val Y = 0.299f * normR + 0.587f * normG + 0.114f * normB
                        val Cb = 128f - 0.168736f * normR - 0.331264f * normG + 0.5f * normB
                        val Cr = 128f + 0.5f * normR - 0.418688f * normG - 0.081312f * normB

                        // 3. Skin Tone Detection and Protection
                        // Human skin locus in YCbCr: Cb in [80..130], Cr in [133..175]
                        val isSkin = (Cb in 80f..130f) && (Cr in 133f..175f) && (Y > 30f)

                        // 4. Foliage Greens Detection and Calibration
                        // Green dominant: G > R + 15 and G > B + 15
                        val isGreenFoliage = (normG > normR + 15f) && (normG > normB + 15f)

                        // 5. Apply Saturation Tuning
                        val effectiveSaturation = when {
                            isSkin -> 1.0f // Protect skin: zero artificial saturation boost
                            isGreenFoliage && profile == ProcessingProfile.VIBRANT -> 1.05f // Prevent neon greens
                            else -> saturation
                        }

                        if (Math.abs(effectiveSaturation - 1.0f) > 0.01f) {
                            val lumNorm = Y / 255f
                            linR = (lumNorm + (linR - lumNorm) * effectiveSaturation).coerceAtLeast(0f)
                            linG = (lumNorm + (linG - lumNorm) * effectiveSaturation).coerceAtLeast(0f)
                            linB = (lumNorm + (linB - lumNorm) * effectiveSaturation).coerceAtLeast(0f)
                        }

                        // 6. Realistic Greens Calibration (shift toxic yellow-greens to natural leaf tones)
                        if (isGreenFoliage) {
                            // Slightly warm the greens and roll off excess luminance
                            linG *= 0.96f
                            linR *= 1.03f
                        }

                        // 7. Tone Mapping (Soft-Knee S-Curve with Highlight Rolloff)
                        val toneR = ColorLut.applySoftKneeToneCurve(linR, contrast, rolloff)
                        val toneG = ColorLut.applySoftKneeToneCurve(linG, contrast, rolloff)
                        val toneB = ColorLut.applySoftKneeToneCurve(linB, contrast, rolloff)

                        // 8. Convert back to 8-bit sRGB
                        val finalR = ColorLut.linearToSrgb(toneR)
                        val finalG = ColorLut.linearToSrgb(toneG)
                        val finalB = ColorLut.linearToSrgb(toneB)

                        output[idx] = (a shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
                    }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        return output
    }

    /**
     * Estimates RGB channel gains for neutral white balance using a robust Gray-World algorithm.
     */
    private fun estimateAwbGains(pixels: IntArray, width: Int, height: Int): Triple<Float, Float, Float> {
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0L

        val step = 4 // Subsample for fast estimation
        val total = pixels.size

        var i = 0
        while (i < total) {
            val pixel = pixels[i]
            val r = (pixel ushr 16) and 0xFF
            val g = (pixel ushr 8) and 0xFF
            val b = pixel and 0xFF

            // Exclude extreme specular highlights (> 250) and deep noise shadows (< 10)
            if (r in 10..250 && g in 10..250 && b in 10..250) {
                sumR += r
                sumG += g
                sumB += b
                count++
            }
            i += step
        }

        if (count == 0L) return Triple(1.0f, 1.0f, 1.0f)

        val avgR = sumR.toDouble() / count
        val avgG = sumG.toDouble() / count
        val avgB = sumB.toDouble() / count

        val avgGray = (avgR + avgG + avgB) / 3.0

        // Clamp gains to realistic limits [0.85 .. 1.25] to prevent over-correcting warm sunsets
        val gainR = (avgGray / avgR).toFloat().coerceIn(0.85f, 1.25f)
        val gainG = (avgGray / avgG).toFloat().coerceIn(0.90f, 1.15f)
        val gainB = (avgGray / avgB).toFloat().coerceIn(0.85f, 1.25f)

        return Triple(gainR, gainG, gainB)
    }
}
