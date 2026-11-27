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
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
    var onCameraErrorListener: ((String) -> Unit)? = null

    init {
        // Always default to the primary rear camera module
        currentModule = CameraModule.MAIN_REAR
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
                onCameraErrorListener?.invoke("Camera initialization failed: ${e.localizedMessage}")
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
     * Binds CameraX Preview, ImageCapture, and ImageAnalysis use cases with progressive fallback.
     * Prevents black screen on Helio G85 LIMITED hardware level by trying:
     * Tier 1: Preview + ImageCapture + ImageAnalysis
     * Tier 2: Preview + ImageCapture (if 3-stream concurrent allocation fails on device HAL)
     * Tier 3: Clean Preview + Capture with default camera selector (Safe Base Mode)
     */
    @SuppressLint("UnsafeOptInUsageError")
    fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val selector = selectCameraForModule(currentModule)
        val displayRotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0

        val caps = discovery.getCapabilities(currentModule)
        val targetSize = caps?.optimal12_5MPResolution ?: caps?.maxPhotoResolution

        // 1. Configure Preview with ResolutionSelector (native 4:3 sensor crop)
        val previewResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()

        val previewBuilder = Preview.Builder()
            .setResolutionSelector(previewResolutionSelector)
            .setTargetRotation(displayRotation)

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
                observeCameraState(camera)
                applyHardwareTuning()
                Log.i(TAG, "Camera bound successfully for Video module $currentModule")
            } catch (e: Exception) {
                Log.e(TAG, "Video use case binding failed for $currentModule: ${e.message}", e)
                onCameraErrorListener?.invoke("Video mode failed on device: ${e.localizedMessage}")
            }
            return
        }

        // 2. Configure ImageCapture with explicit high-resolution strategy and 98% JPEG quality
        // Preserves full 12.5 MP (4080x3060) sensor binned output with crisp micro-textures
        val captureResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                if (targetSize != null) {
                    ResolutionStrategy(targetSize.toSize(), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                } else {
                    ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
                }
            )
            .build()

        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(captureResolutionSelector)
            .setJpegQuality(98)
            .setTargetRotation(displayRotation)

        val camera2CaptureExtender = Camera2Interop.Extender(captureBuilder)
        configureCamera2CaptureSession(camera2CaptureExtender)

        imageCapture = captureBuilder.build()

        // 3. Conditional ImageAnalysis: ONLY bind in PRO mode where histogram/peaking is needed.
        // In Photo/Night/Portrait/HDR modes, binding only 2 streams (Preview + Capture) guarantees
        // 100% rock-solid stability on MediaTek Helio G85 without ISP buffer starvation or native crashes.
        val needAnalysis = currentMode.supportsProControls
        if (needAnalysis) {
            val analysisResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()

            imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(analysisResolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        frameAnalysisListener?.invoke(imageProxy) ?: imageProxy.close()
                    }
                }
        } else {
            imageAnalysis = null
        }

        var bound = false

        // If Pro mode needs analysis, attempt Tier 1 (3-stream)
        if (needAnalysis && imageAnalysis != null) {
            try {
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    imageCapture,
                    imageAnalysis
                )
                bound = true
                Log.i(TAG, "Pro Mode: Camera bound with Preview + Capture + Analysis for $currentModule")
            } catch (e: Exception) {
                Log.w(TAG, "Pro 3-stream binding rejected by HAL: ${e.message}. Falling back to 2-stream...")
            }
        }

        // Tier 2: 2-stream mode (Preview + ImageCapture) - guaranteed stable on Helio G85
        if (!bound) {
            try {
                provider.unbindAll()
                imageAnalysis = null
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    imageCapture
                )
                bound = true
                Log.i(TAG, "Camera bound successfully with 2-stream Preview + Capture for $currentModule")
            } catch (e: Exception) {
                Log.w(TAG, "Tier 2 binding failed ($currentModule): ${e.message}. Falling back to Tier 3 Safe Base Mode...")
            }
        }

        // Tier 3: Safe Base Mode with guaranteed standard camera selector and clean builders
        if (!bound) {
            try {
                provider.unbindAll()
                imageAnalysis = null
                val safeSelector = if (currentModule.isFrontFacing) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                val safePreview = Preview.Builder()
                    .setResolutionSelector(previewResolutionSelector)
                    .build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                val safeCapture = ImageCapture.Builder()
                    .setResolutionSelector(captureResolutionSelector)
                    .setJpegQuality(95)
                    .build()
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    safeSelector,
                    safePreview,
                    safeCapture
                )
                preview = safePreview
                imageCapture = safeCapture
                Log.i(TAG, "Tier 3: Safe Base Mode bound successfully for $currentModule")
            } catch (e: Exception) {
                Log.e(TAG, "All camera binding tiers failed for $currentModule: ${e.message}", e)
                onCameraErrorListener?.invoke("Unable to initialize camera sensor: ${e.localizedMessage}")
                return
            }
        }

        observeCameraState(camera)
        applyHardwareTuning()
    }

    private fun observeCameraState(cam: Camera?) {
        cam?.cameraInfo?.cameraState?.observe(lifecycleOwner) { state ->
            when (state.type) {
                CameraState.Type.OPEN -> Log.d(TAG, "Camera sensor opened and active")
                CameraState.Type.CLOSED -> {
                    state.error?.let { err ->
                        Log.w(TAG, "Camera closed with error code ${err.code}: ${err.cause?.message}")
                    }
                }
                else -> Unit
            }
        }
    }

    /**
     * Injects low-level Camera2 CaptureRequest options.
     * Safely guarded to prevent hardware-level rejection on MediaTek Helio G85.
     */
    @SuppressLint("UnsafeOptInUsageError")
    private fun <T> configureCamera2CaptureSession(extender: Camera2Interop.Extender<T>) {
        try {
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
                if (manualFocusDistanceDiopters != null && caps?.isFixedFocus == false) {
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
        } catch (e: Exception) {
            Log.w(TAG, "configureCamera2CaptureSession non-critical: ${e.message}")
        }
    }

    /**
     * Dynamically updates live camera controls via Camera2CameraControl.
     * Guarded with try-catch so an unsupported key never crashes the camera session.
     */
    @SuppressLint("UnsafeOptInUsageError")
    fun applyHardwareTuning() {
        val cam = camera ?: return
        try {
            val cameraControl = cam.cameraControl
            val camera2Control = Camera2CameraControl.from(cameraControl)

            val optionsBuilder = CaptureRequestOptions.Builder()

            // Set exposure compensation safely
            try {
                cameraControl.setExposureCompensationIndex(exposureCompensationIndex)
            } catch (e: Exception) {
                Log.w(TAG, "Exposure compensation non-critical: ${e.message}")
            }

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
                ProcessingProfile.NATURAL -> CaptureRequest.NOISE_REDUCTION_MODE_FAST
                ProcessingProfile.BALANCED, ProcessingProfile.DETAILED -> CaptureRequest.NOISE_REDUCTION_MODE_FAST
                ProcessingProfile.VIBRANT -> CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                ProcessingProfile.MONOCHROME -> CaptureRequest.NOISE_REDUCTION_MODE_FAST
            }
            optionsBuilder.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, noiseMode)

            // Edge enhancement tuning: EDGE_MODE_FAST is guaranteed supported across all Android Camera2 levels
            optionsBuilder.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)

            camera2Control.setCaptureRequestOptions(optionsBuilder.build())
        } catch (e: Exception) {
            Log.w(TAG, "applyHardwareTuning non-critical: ${e.message}")
        }
    }

    /**
     * Resolves the proper CameraSelector for a CameraModule.
     * Uses guaranteed DEFAULT_BACK_CAMERA / DEFAULT_FRONT_CAMERA for primary lenses,
     * and safely validates auxiliary camera IDs against CameraProvider before selecting.
     */
    fun selectCameraForModule(module: CameraModule): CameraSelector {
        if (module == CameraModule.FRONT) {
            return CameraSelector.DEFAULT_FRONT_CAMERA
        }
        if (module == CameraModule.MAIN_REAR) {
            return CameraSelector.DEFAULT_BACK_CAMERA
        }

        // Auxiliary modules (Ultrawide, Macro)
        val targetId = discovery.getCameraIdForModule(module)
        val provider = cameraProvider

        if (targetId != null) {
            val auxSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .addCameraFilter { cameraInfos ->
                    cameraInfos.filter { info ->
                        try {
                            @SuppressLint("UnsafeOptInUsageError")
                            val id = Camera2CameraInfo.from(info).cameraId
                            id == targetId
                        } catch (e: Exception) {
                            false
                        }
                    }
                }
                .build()

            if (provider != null && provider.hasCamera(auxSelector)) {
                return auxSelector
            }
            Log.w(TAG, "Auxiliary camera ID $targetId for $module not exposed to CameraX, falling back to DEFAULT_BACK_CAMERA")
        }

        return CameraSelector.DEFAULT_BACK_CAMERA
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
