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
                // High-quality JPEG compression (95% to preserve fine micro-textures without compression artifacts)
                enhancedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                out.flush()
            }

            if (enhancedBitmap != bitmap) {
                enhancedBitmap.recycle()
            }
            bitmap.recycle()

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Processed ${profile.title} profile for ${module.displayName} in ${elapsed}ms -> ${outputFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Image enhancement failed: ${e.message}", e)
            inputFile.copyTo(outputFile, overwrite = true)
        }
    }

    /**
     * Applies the computational pipeline to an in-memory Bitmap,
     * applying specialized optical algorithms for Front, Ultrawide, Macro, or Main.
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

        val pixels = IntArray(pixelCount)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val outputPixels = when (module) {
            CameraModule.ULTRAWIDE -> {
                // 1. Correct radial barrel distortion
                val undistorted = UltrawideLensCorrection.correctDistortion(pixels, width, height)
                // 2. Correct lateral chromatic aberration
                val caCorrected = UltrawideLensCorrection.correctChromaticAberration(undistorted, width, height)
                // 3. Apply color science and sharpness tuned to 5MP sensor
                val colorBuffer = IntArray(pixelCount)
                ColorSciencePipeline.processColors(caCorrected, width, height, profile, colorBuffer)
                val sharpened = IntArray(pixelCount)
                DetailSharpnessFilter.enhanceDetails(colorBuffer, width, height, profile.sharpnessWeight * 1.1f, 0.10f, sharpened)
                sharpened
            }
            CameraModule.FRONT -> {
                val blurRadius = if (mode == CaptureMode.PORTRAIT) 8 else 0
                FrontSelfieEngine.processSelfie(pixels, width, height, profile, portraitBlurRadius = blurRadius)
            }
            CameraModule.MACRO -> {
                MacroOptimizationProfile.processMacro(pixels, width, height, profile)
            }
            CameraModule.MAIN_REAR -> {
                if (mode == CaptureMode.PORTRAIT) {
                    FrontSelfieEngine.processSelfie(pixels, width, height, profile, portraitBlurRadius = 8)
                } else {
                    var currentBuffer = pixels

                    // 1. Bilateral Denoising (Edge-Preserving Noise Reduction)
                    if (profile.noiseReductionStrength > 0.05f) {
                        val spatial = 1.0f + profile.noiseReductionStrength * 2.0f
                        val range = 0.04f + profile.noiseReductionStrength * 0.06f
                        val denoisedBuffer = IntArray(pixelCount)
                        BilateralDenoiseFilter.filter(currentBuffer, width, height, spatial, range, denoisedBuffer)
                        currentBuffer = denoisedBuffer
                    }

                    // 2. Color Science & Tone-Mapping Pipeline
                    val colorBuffer = IntArray(pixelCount)
                    ColorSciencePipeline.processColors(currentBuffer, width, height, profile, colorBuffer)
                    currentBuffer = colorBuffer

                    // 3. Detail & Sharpness Engine (Halo-Free Non-Linear Unsharp Masking)
                    if (profile.sharpnessWeight > 0.05f) {
                        val sharpenedBuffer = IntArray(pixelCount)
                        DetailSharpnessFilter.enhanceDetails(
                            currentBuffer,
                            width,
                            height,
                            profile.sharpnessWeight,
                            haloThreshold = 0.10f,
                            sharpenedBuffer
                        )
                        currentBuffer = sharpenedBuffer
                    }
                    currentBuffer
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
