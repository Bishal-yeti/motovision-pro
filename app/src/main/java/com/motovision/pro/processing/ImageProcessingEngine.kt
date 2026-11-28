package com.motovision.pro.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.motovision.pro.computational.HdrExposureFusion
import com.motovision.pro.computational.MultiFrameNightFusion
import com.motovision.pro.model.CameraModule
import com.motovision.pro.model.CaptureMode
import com.motovision.pro.model.ProcessingProfile
import com.motovision.pro.opt.FrontSelfieEngine
import com.motovision.pro.opt.MacroOptimizationProfile
import com.motovision.pro.opt.UltrawideLensCorrection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Master computational image-processing engine.
 * Orchestrates bilateral denoising, edge-aware detail preservation,
 * natural color science, and lens-specific optimizations.
 */
object ImageProcessingEngine {

    private const val TAG = "ImageProcessingEngine"

    /**
     * Enhances an image file from disk and saves the result to the destination file.
     */
    suspend fun processImageFile(
        inputFile: File,
        outputFile: File,
        profile: ProcessingProfile,
        mode: CaptureMode,
        module: CameraModule = CameraModule.MAIN_REAR
    ) = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        try {
            val bitmap = BitmapFactory.decodeFile(inputFile.absolutePath)
                ?: throw IllegalArgumentException("Could not decode image at ${inputFile.absolutePath}")

            val enhancedBitmap = processBitmap(bitmap, profile, mode, module)

            FileOutputStream(outputFile).use { out ->
                // High-quality JPEG compression (98% to preserve fine micro-textures and subtle B&W gradations)
                enhancedBitmap.compress(Bitmap.CompressFormat.JPEG, 98, out)
                out.flush()
            }

            if (enhancedBitmap != bitmap) {
                enhancedBitmap.recycle()
            }
            bitmap.recycle()

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Processed ${profile.title} profile for ${module.displayName} in ${elapsed}ms -> ${outputFile.name}")
        } catch (t: Throwable) {
            Log.e(TAG, "Image enhancement fallback engaged: ${t.message}", t)
            try {
                inputFile.copyTo(outputFile, overwrite = true)
            } catch (copyErr: Throwable) {
                Log.e(TAG, "Fallback file copy failed: ${copyErr.message}")
            }
        }
    }

    /**
     * Applies the computational pipeline to an in-memory Bitmap.
     * Uses a high-efficiency ping-pong buffer pair (bufferA, bufferB) to eliminate
     * 150MB+ of heap allocations on 12.5 MP captures.
     */
    fun processBitmap(
        source: Bitmap,
        profile: ProcessingProfile,
        mode: CaptureMode,
        module: CameraModule = CameraModule.MAIN_REAR
    ): Bitmap {
        val width = source.width
        val height = source.height
        val pixelCount = width * height

        // Two ping-pong buffers for the entire pipeline
        var bufSrc = IntArray(pixelCount)
        var bufDst = IntArray(pixelCount)
        source.getPixels(bufSrc, 0, width, 0, 0, width, height)

        val outputPixels = when (module) {
            CameraModule.ULTRAWIDE -> {
                val undistorted = UltrawideLensCorrection.correctDistortion(bufSrc, width, height)
                val caCorrected = UltrawideLensCorrection.correctChromaticAberration(undistorted, width, height)
                ColorSciencePipeline.processColors(caCorrected, width, height, profile, bufDst)
                DetailSharpnessFilter.enhanceDetails(bufDst, width, height, profile.sharpnessWeight * 1.1f, 0.10f, bufSrc)
                bufSrc
            }
            CameraModule.FRONT -> {
                val blurRadius = if (mode == CaptureMode.PORTRAIT) 8 else 0
                FrontSelfieEngine.processSelfie(bufSrc, width, height, profile, portraitBlurRadius = blurRadius)
            }
            CameraModule.MACRO -> {
                MacroOptimizationProfile.processMacro(bufSrc, width, height, profile)
            }
            CameraModule.MAIN_REAR -> {
                if (mode == CaptureMode.PORTRAIT) {
                    FrontSelfieEngine.processSelfie(bufSrc, width, height, profile, portraitBlurRadius = 8)
                } else {
                    // 1. Bilateral Denoising (Edge-Preserving Noise Reduction)
                    // Scale work to current thermal/memory state (Helio G85 throttles hard when hot):
                    // FULL = 5x5 on all cores, REDUCED = 3x3, MINIMAL = skip (ISP NR still applies).
                    val tier = com.motovision.pro.system.DeviceHealthMonitor.refresh()
                    if (profile.noiseReductionStrength > 0.05f && tier != com.motovision.pro.system.DeviceHealthMonitor.QualityTier.MINIMAL) {
                        val spatial = 1.0f + profile.noiseReductionStrength * 2.0f
                        val range = 0.04f + profile.noiseReductionStrength * 0.06f
                        val radius = if (tier == com.motovision.pro.system.DeviceHealthMonitor.QualityTier.FULL) 2 else 1
                        BilateralDenoiseFilter.filter(bufSrc, width, height, spatial, range, bufDst, radius)
                        // Swap buffers
                        val tmp = bufSrc
                        bufSrc = bufDst
                        bufDst = tmp
                    }

                    // 2. Color Science, Panchromatic B&W & Tone-Mapping Pipeline
                    ColorSciencePipeline.processColors(bufSrc, width, height, profile, bufDst)
                    val tmp = bufSrc
                    bufSrc = bufDst
                    bufDst = tmp

                    // 3. Detail & Sharpness Engine (Halo-Free Non-Linear Unsharp Masking)
                    if (profile.sharpnessWeight > 0.05f) {
                        DetailSharpnessFilter.enhanceDetails(
                            bufSrc,
                            width,
                            height,
                            profile.sharpnessWeight,
                            haloThreshold = 0.10f,
                            bufDst
                        )
                        bufDst
                    } else {
                        bufSrc
                    }
                }
            }
        }

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        resultBitmap.setPixels(outputPixels, 0, width, 0, 0, width, height)
        return resultBitmap
    }

    /**
     * Fuses multiple burst Bitmaps according to CaptureMode (NIGHT or HDR).
     */
    fun processMultiFrameBurst(
        frames: List<Bitmap>,
        profile: ProcessingProfile,
        mode: CaptureMode
    ): Bitmap {
        require(frames.isNotEmpty()) { "Burst frames cannot be empty" }
        if (frames.size == 1) return processBitmap(frames[0], profile, mode)

        val width = frames[0].width
        val height = frames[0].height
        val total = width * height

        val pixelBuffers = frames.map { bmp ->
            val buf = IntArray(total)
            bmp.getPixels(buf, 0, width, 0, 0, width, height)
            buf
        }

        val fusedPixels = when (mode) {
            CaptureMode.NIGHT -> MultiFrameNightFusion.fuseNightBurst(pixelBuffers, width, height, profile)
            CaptureMode.HDR -> HdrExposureFusion.fuseHdrBracket(pixelBuffers, width, height, profile)
            else -> {
                val singleResult = processBitmap(frames[0], profile, mode)
                return singleResult
            }
        }

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        resultBitmap.setPixels(fusedPixels, 0, width, 0, 0, width, height)
        return resultBitmap
    }
}
