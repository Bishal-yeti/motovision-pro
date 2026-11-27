package com.motovision.pro.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.os.Environment
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.motovision.pro.model.CaptureMode
import com.motovision.pro.model.CaptureResultData
import com.motovision.pro.model.ProcessingProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Orchestrates image capture, file storage, EXIF metadata retention,
 * and dispatching to the computational enhancement pipeline.
 */
class CaptureController(
    private val context: Context,
    private val cameraEngine: CameraEngine,
    private val scope: CoroutineScope
) {
    enum class State {
        IDLE,
        CAPTURING,
        PROCESSING,
        ERROR
    }

    var currentState: State = State.IDLE
        private set

    var onStateChanged: ((State, String?) -> Unit)? = null
    var onCaptureProgress: ((Float) -> Unit)? = null
    var onCaptureComplete: ((CaptureResultData) -> Unit)? = null

    // Image processor hook (injected in Milestone 3)
    var imageProcessorHook: ((File, File, ProcessingProfile, CaptureMode, com.motovision.pro.model.CameraModule) -> Unit)? = null

    val rawCaptureManager = com.motovision.pro.pro.RawCaptureManager(context)

    fun capturePhoto() {
        if (currentState != State.IDLE) {
            Log.w(TAG, "Capture ignored, current state is $currentState")
            return
        }

        val imageCapture = cameraEngine.imageCapture
        if (imageCapture == null) {
            updateState(State.ERROR, "Camera not ready")
            return
        }

        updateState(State.CAPTURING, "Acquiring photo…")
        onCaptureProgress?.invoke(0.1f)

        val outputDirectory = getOutputDirectory()
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val originalFile = File(outputDirectory, "MV_ORIG_${timeStamp}.jpg")
        val processedFile = File(outputDirectory, "MV_ENHANCED_${timeStamp}.jpg")

        val outputOptions = ImageCapture.OutputFileOptions.Builder(originalFile).build()

        imageCapture.takePicture(
            outputOptions,
            cameraEngine.cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    onCaptureProgress?.invoke(0.5f)
                    updateState(State.PROCESSING, "Applying ${cameraEngine.currentProfile.title} enhancement…")

                    scope.launch(Dispatchers.Default) {
                        try {
                            // Apply computational enhancement if hook is set, or preserve original
                            if (imageProcessorHook != null) {
                                imageProcessorHook?.invoke(
                                    originalFile,
                                    processedFile,
                                    cameraEngine.currentProfile,
                                    cameraEngine.currentMode,
                                    cameraEngine.currentModule
                                )
                            } else {
                                // Default fallback: copy original to processed destination
                                originalFile.copyTo(processedFile, overwrite = true)
                            }

                            // Preserve and write comprehensive EXIF metadata
                            preserveExifMetadata(originalFile, processedFile)

                            // Save RAW / uncompressed linear sensor dump if RAW toggle is active
                            var savedRawFile: File? = null
                            if (cameraEngine.isRawCaptureEnabled) {
                                val caps = cameraEngine.discovery.getCapabilities(cameraEngine.currentModule)
                                val isHw = caps?.supportsRaw == true
                                val rawTarget = rawCaptureManager.createRawOutputFile(isHw)
                                val success = rawCaptureManager.writeLinearSensorDump(
                                    yuvData = originalFile.readBytes(),
                                    width = caps?.maxPhotoResolution?.width ?: 4080,
                                    height = caps?.maxPhotoResolution?.height ?: 3060,
                                    iso = cameraEngine.manualIso,
                                    exposureNs = cameraEngine.manualExposureTimeNs,
                                    outputFile = rawTarget
                                )
                                if (success) {
                                    savedRawFile = rawTarget
                                }
                            }

                            val result = CaptureResultData(
                                originalFile = originalFile,
                                processedFile = if (processedFile.exists()) processedFile else null,
                                captureMode = cameraEngine.currentMode,
                                profile = cameraEngine.currentProfile,
                                cameraModule = cameraEngine.currentModule,
                                iso = cameraEngine.manualIso,
                                exposureTimeNs = cameraEngine.manualExposureTimeNs,
                                rawFile = savedRawFile
                            )

                            withContext(Dispatchers.Main) {
                                onCaptureProgress?.invoke(1.0f)
                                updateState(State.IDLE, null)
                                onCaptureComplete?.invoke(result)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Processing failed: ${e.message}", e)
                            withContext(Dispatchers.Main) {
                                updateState(State.ERROR, "Processing error: ${e.message}")
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Image capture failed: ${exception.message}", exception)
                    ContextCompat.getMainExecutor(context).execute {
                        updateState(State.ERROR, exception.localizedMessage)
                    }
                }
            }
        )
    }

    private fun preserveExifMetadata(source: File, destination: File) {
        if (!source.exists() || !destination.exists()) return
        try {
            val srcExif = ExifInterface(source.absolutePath)
            val dstExif = ExifInterface(destination.absolutePath)

            // Copy essential capture tags
            val tags = arrayOf(
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_DATETIME_DIGITIZED,
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.TAG_FLASH,
                ExifInterface.TAG_FOCAL_LENGTH,
                ExifInterface.TAG_WHITE_BALANCE,
                ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                ExifInterface.TAG_F_NUMBER
            )

            for (tag in tags) {
                val value = srcExif.getAttribute(tag)
                if (value != null) {
                    dstExif.setAttribute(tag, value)
                }
            }

            // Write custom software identifier
            dstExif.setAttribute(ExifInterface.TAG_SOFTWARE, "MotoVision Pro Computational Engine")
            dstExif.setAttribute(ExifInterface.TAG_MAKE, "Motorola")
            dstExif.setAttribute(ExifInterface.TAG_MODEL, "Moto G23 (MotoVision Pro)")

            dstExif.saveAttributes()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy EXIF metadata: ${e.message}")
        }
    }

    private fun getOutputDirectory(): File {
        val mediaDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.let {
            File(it, "MotoVisionPro").apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists()) mediaDir else context.filesDir
    }

    private fun updateState(newState: State, message: String?) {
        currentState = newState
        ContextCompat.getMainExecutor(context).execute {
            onStateChanged?.invoke(newState, message)
        }
    }

    companion object {
        private const val TAG = "CaptureController"
    }
}
