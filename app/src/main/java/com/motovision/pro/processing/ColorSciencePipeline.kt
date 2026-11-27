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

                            if (profile == ProcessingProfile.BW_VINTAGE) {
                                // Classic silver-gelatin sepia toning
                                val sepiaR = (finalMono * 1.08f).toInt().coerceIn(0, 255)
                                val sepiaG = (finalMono * 0.98f).toInt().coerceIn(0, 255)
                                val sepiaB = (finalMono * 0.80f).toInt().coerceIn(0, 255)
                                output[idx] = (a shl 24) or (sepiaR shl 16) or (sepiaG shl 8) or sepiaB
                            } else {
                                output[idx] = (a shl 24) or (finalMono shl 16) or (finalMono shl 8) or finalMono
                            }
                            continue
                        }

                        // 2. Compute Luminance and Chrominance in YCbCr
                        var normR = (linR * 255f).coerceIn(0f, 255f)
                        var normG = (linG * 255f).coerceIn(0f, 255f)
                        var normB = (linB * 255f).coerceIn(0f, 255f)

                        val Y = 0.299f * normR + 0.587f * normG + 0.114f * normB
                        var Cb = 128f - 0.168736f * normR - 0.331264f * normG + 0.5f * normB
                        var Cr = 128f + 0.5f * normR - 0.418688f * normG - 0.081312f * normB

                        // Shadow Chroma Noise Suppression (removes purple/green color grain in underexposed areas)
                        if (Y < 70f) {
                            val shadowChromaDampen = (Y / 70f).coerceIn(0.25f, 1.0f)
                            Cb = 128f + (Cb - 128f) * shadowChromaDampen
                            Cr = 128f + (Cr - 128f) * shadowChromaDampen

                            // Reconstruct clean RGB with suppressed chroma noise
                            val cleanR = (Y + 1.402f * (Cr - 128f)).coerceIn(0f, 255f)
                            val cleanG = (Y - 0.344136f * (Cb - 128f) - 0.714136f * (Cr - 128f)).coerceIn(0f, 255f)
                            val cleanB = (Y + 1.772f * (Cb - 128f)).coerceIn(0f, 255f)

                            linR = cleanR / 255f
                            linG = cleanG / 255f
                            linB = cleanB / 255f
                            normR = cleanR
                            normG = cleanG
                            normB = cleanB
                        }

                        // 3. Skin Tone Detection and Protection
                        // Human skin locus in YCbCr: Cb in [80..130], Cr in [133..175]
                        val isSkin = (Cb in 80f..130f) && (Cr in 133f..175f) && (Y > 30f)

                        // 4. Foliage Greens Detection and Calibration
                        // Green dominant: G > R + 15 and G > B + 15
                        val isGreenFoliage = (normG > normR + 15f) && (normG > normB + 15f)

                        // 5. Creative Filter Color Grading
                        when (profile) {
                            ProcessingProfile.WARM_FILM -> {
                                // Kodachrome warm golden highlights, rich amber warmth
                                linR *= 1.10f
                                linG *= 1.03f
                                linB *= 0.88f
                            }
                            ProcessingProfile.COOL_CINEMA -> {
                                // Hollywood Teal & Orange: Cool cyan/teal shadows, warm amber highlights
                                if (Y < 128f) {
                                    val shadowFactor = (128f - Y) / 128f
                                    linB *= (1.0f + 0.14f * shadowFactor)
                                    linG *= (1.0f + 0.05f * shadowFactor)
                                    linR *= (1.0f - 0.10f * shadowFactor)
                                } else {
                                    val highlightFactor = (Y - 128f) / 127f
                                    linR *= (1.0f + 0.08f * highlightFactor)
                                    linB *= (1.0f - 0.08f * highlightFactor)
                                }
                            }
                            ProcessingProfile.PORTRAIT_SOFT -> {
                                // Gentle flattering skin glow: delicate warm pastel midtones
                                if (isSkin) {
                                    linR *= 1.04f
                                    linG *= 1.01f
                                    linB *= 0.98f
                                }
                            }
                            else -> { /* Standard profiles */ }
                        }

                        // 6. Apply Saturation Tuning
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

                        // 7. Realistic Greens Calibration (shift toxic yellow-greens to natural leaf tones)
                        if (isGreenFoliage && profile != ProcessingProfile.WARM_FILM && profile != ProcessingProfile.COOL_CINEMA) {
                            // Slightly warm the greens and roll off excess luminance
                            linG *= 0.96f
                            linR *= 1.03f
                        }

                        // 8. Tone Mapping (Soft-Knee S-Curve with Highlight Rolloff)
                        val toneR = ColorLut.applySoftKneeToneCurve(linR, contrast, rolloff)
                        val toneG = ColorLut.applySoftKneeToneCurve(linG, contrast, rolloff)
                        val toneB = ColorLut.applySoftKneeToneCurve(linB, contrast, rolloff)

                        // 9. Convert back to 8-bit sRGB
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
