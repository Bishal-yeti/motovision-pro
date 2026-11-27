package com.motovision.pro.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.motovision.pro.model.CameraCapabilities
import com.motovision.pro.model.CameraModule
import com.motovision.pro.model.CaptureMode
import com.motovision.pro.model.ProcessingProfile
import com.motovision.pro.pro.ProModeController
import com.motovision.pro.video.VideoRecordingEngine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Core camera controller integrating Jetpack CameraX with low-level Camera2 Interoperability.
 * Controls lifecycle binding, lens switching, capture requests, and hardware parameter injection.
 */
class CameraEngine(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val discovery = CameraDiscovery(cameraManager)

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    var imageCapture: ImageCapture? = null
        private set
    private var imageAnalysis: ImageAnalysis? = null

    val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    val analysisExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    val videoEngine: VideoRecordingEngine = VideoRecordingEngine(context, cameraExecutor)

    var currentModule: CameraModule = CameraModule.MAIN_REAR
        private set
    var currentMode: CaptureMode = CaptureMode.PHOTO
        private set
    var currentProfile: ProcessingProfile = ProcessingProfile.BALANCED
        private set

    // Pro Controls State
    var manualIso: Int? = null
    var manualExposureTimeNs: Long? = null
    var manualFocusDistanceDiopters: Float? = null
    var exposureCompensationIndex: Int = 0
    var manualWbPreset: ProModeController.WhiteBalancePreset = ProModeController.WhiteBalancePreset.AUTO
    var manualKelvin: Int = 5500
    var isRawCaptureEnabled: Boolean = false

    // Frame analysis callback for histogram and peaking
    var frameAnalysisListener: ((ImageProxy) -> Unit)? = null

    init {
        val initialModule = discovery.getAllDiscoveredModules().firstOrNull() ?: CameraModule.MAIN_REAR
        currentModule = initialModule
    }

    fun startCamera(onReady: (() -> Unit)? = null) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindCameraUseCases()
                onReady?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize CameraProvider: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchModule(module: CameraModule, onComplete: (() -> Unit)? = null) {
        if (currentModule == module) return
        currentModule = module
        bindCameraUseCases()
        onComplete?.invoke()
    }

    fun setCaptureMode(mode: CaptureMode) {
        if (currentMode == mode) return
        currentMode = mode
        // In Pro mode, apply manual settings; in other modes, reset manual overrides
        if (!mode.supportsProControls) {
            manualIso = null
            manualExposureTimeNs = null
            manualFocusDistanceDiopters = null
            exposureCompensationIndex = 0
        }
        bindCameraUseCases()
    }

    fun setProcessingProfile(profile: ProcessingProfile) {
        currentProfile = profile
        applyHardwareTuning()
    }

    /**
     * Binds CameraX Preview, ImageCapture, and ImageAnalysis use cases with Camera2 Interop.
     */
    @SuppressLint("UnsafeOptInUsageError")
    fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val selector = selectCameraForModule(currentModule)

        // 1. Configure Preview
        val previewBuilder = Preview.Builder()
        val camera2PreviewExtender = Camera2Interop.Extender(previewBuilder)
        configureCamera2CaptureSession(camera2PreviewExtender)

        preview = previewBuilder.build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        if (currentMode.isVideo) {
            val videoCapture = videoEngine.buildVideoCapture()
            try {
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    videoCapture
                )
                applyHardwareTuning()
                Log.i(TAG, "Camera bound successfully for Video module $currentModule")
            } catch (e: Exception) {
                Log.e(TAG, "Video use case binding failed for $currentModule: ${e.message}", e)
            }
            return
        }

        // 2. Configure ImageCapture
        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(previewView.display?.rotation ?: android.view.Surface.ROTATION_0)

        // Set optimal resolution (e.g. 12.5 MP for Main Rear JN1)
        val caps = discovery.getCapabilities(currentModule)
        val targetSize = caps?.optimal12_5MPResolution ?: caps?.maxPhotoResolution
        if (targetSize != null) {
            captureBuilder.setTargetResolution(targetSize.toSize())
        }

        val camera2CaptureExtender = Camera2Interop.Extender(captureBuilder)
        configureCamera2CaptureSession(camera2CaptureExtender)

        imageCapture = captureBuilder.build()

        // 3. Configure ImageAnalysis for Live Histogram, Peaking, Scene Metrics
        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    frameAnalysisListener?.invoke(imageProxy) ?: imageProxy.close()
                }
            }

        try {
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                imageCapture,
                imageAnalysis
            )
            applyHardwareTuning()
            Log.i(TAG, "Camera bound successfully for module $currentModule (mode: $currentMode)")
        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed for $currentModule: ${e.message}", e)
        }
    }

    /**
     * Injects low-level Camera2 CaptureRequest options.
     * Overrides stock aggressive noise reduction and artificial unsharp edge filtering.
     */
    @SuppressLint("UnsafeOptInUsageError")
    private fun <T> configureCamera2CaptureSession(extender: Camera2Interop.Extender<T>) {
        // Suppress destructive stock smearing and halo edge processing
        extender.setCaptureRequestOption(
            CaptureRequest.NOISE_REDUCTION_MODE,
            CaptureRequest.NOISE_REDUCTION_MODE_FAST
        )
        extender.setCaptureRequestOption(
            CaptureRequest.EDGE_MODE,
            CaptureRequest.EDGE_MODE_FAST
        )

        // Video stabilization if supported
        val caps = discovery.getCapabilities(currentModule)
        if (caps?.isVideoStabilizationSupported == true) {
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            )
        }

        // Pro Mode manual controls
        if (currentMode.supportsProControls) {
            if (manualIso != null && manualExposureTimeNs != null) {
                extender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_OFF
                )
                extender.setCaptureRequestOption(
                    CaptureRequest.SENSOR_SENSITIVITY,
                    manualIso!!
                )
                extender.setCaptureRequestOption(
                    CaptureRequest.SENSOR_EXPOSURE_TIME,
                    manualExposureTimeNs!!
                )
            }
            if (manualFocusDistanceDiopters != null && !caps!!.isFixedFocus) {
                extender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF
                )
                extender.setCaptureRequestOption(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    manualFocusDistanceDiopters!!
                )
            }
        }
    }

    /**
     * Dynamically updates live camera controls via Camera2CameraControl.
     */
    @SuppressLint("UnsafeOptInUsageError")
    fun applyHardwareTuning() {
        val cam = camera ?: return
        val cameraControl = cam.cameraControl
        val camera2Control = Camera2CameraControl.from(cameraControl)

        val optionsBuilder = CaptureRequestOptions.Builder()

        // Set exposure compensation
        cameraControl.setExposureCompensationIndex(exposureCompensationIndex)

        // Apply Pro mode manual overrides if configured
        if (currentMode.supportsProControls) {
            if (manualIso != null && manualExposureTimeNs != null) {
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_OFF
                )
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.SENSOR_SENSITIVITY,
                    manualIso!!
                )
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.SENSOR_EXPOSURE_TIME,
                    manualExposureTimeNs!!
                )
            } else {
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON
                )
            }

            val caps = discovery.getCapabilities(currentModule)
            if (manualFocusDistanceDiopters != null && caps?.isFixedFocus == false) {
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF
                )
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    manualFocusDistanceDiopters!!
                )
            } else if (caps?.isFixedFocus == false) {
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
            }
        } else {
            // Automatic 3A mode
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )
            val caps = discovery.getCapabilities(currentModule)
            if (caps?.isFixedFocus == false) {
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
            }
        }

        // White balance tuning (Pro mode manual WB / Kelvin vs Auto)
        val caps = discovery.getCapabilities(currentModule)
        if (currentMode.supportsProControls && manualWbPreset != ProModeController.WhiteBalancePreset.AUTO) {
            if (manualWbPreset == ProModeController.WhiteBalancePreset.CUSTOM_KELVIN) {
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_OFF
                )
                val proController = ProModeController(caps)
                val gains = proController.kelvinToRggbGains(manualKelvin)
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_FAST
                )
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_GAINS,
                    gains
                )
            } else {
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    manualWbPreset.awbMode
                )
            }
        } else {
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
        }

        // Video stabilization when in video mode
        if (currentMode.isVideo && videoEngine.currentConfig.isEisStabilizationEnabled) {
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            )
        }

        // Noise reduction tuning based on profile
        val noiseMode = when (currentProfile) {
            ProcessingProfile.NATURAL -> CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL
            ProcessingProfile.BALANCED, ProcessingProfile.DETAILED -> CaptureRequest.NOISE_REDUCTION_MODE_FAST
            ProcessingProfile.VIBRANT -> CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
        }
        optionsBuilder.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, noiseMode)

        // Edge enhancement tuning: stock edge mode off to let MotoVision Pro's sharpness engine do it cleanly
        optionsBuilder.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)

        camera2Control.setCaptureRequestOptions(optionsBuilder.build())
    }

    /**
     * Resolves the proper CameraSelector for a CameraModule.
     */
    private fun selectCameraForModule(module: CameraModule): CameraSelector {
        val targetId = discovery.getCameraIdForModule(module)

        return if (targetId != null) {
            CameraSelector.Builder()
                .addCameraFilter { cameraInfos ->
                    cameraInfos.filter { info ->
                        @SuppressLint("UnsafeOptInUsageError")
                        val id = Camera2CameraInfo.from(info).cameraId
                        id == targetId
                    }
                }
                .build()
        } else {
            if (module.isFrontFacing) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
        }
    }

    fun triggerFocusAndMetering(x: Float, y: Float) {
        val cam = camera ?: return
        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    fun setZoom(linearZoom: Float) {
        camera?.cameraControl?.setLinearZoom(linearZoom.coerceIn(0.0f, 1.0f))
    }

    fun shutdown() {
        videoEngine.stopRecording()
        cameraExecutor.shutdown()
        analysisExecutor.shutdown()
        cameraProvider?.unbindAll()
    }

    companion object {
        private const val TAG = "CameraEngine"
    }
}
