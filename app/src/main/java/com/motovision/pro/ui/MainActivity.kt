package com.motovision.pro.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Range
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.motovision.pro.R
import com.motovision.pro.camera.CameraEngine
import com.motovision.pro.camera.CaptureController
import com.motovision.pro.databinding.ActivityMainBinding
import com.motovision.pro.model.CameraModule
import com.motovision.pro.model.CaptureMode
import com.motovision.pro.model.CaptureResultData
import com.motovision.pro.model.ProcessingProfile
import com.motovision.pro.pro.FocusPeakingEngine
import com.motovision.pro.pro.ProModeController
import com.motovision.pro.video.VideoConfiguration
import com.motovision.pro.video.VideoRecordingEngine
import java.nio.ByteBuffer

/**
 * Main camera activity providing a professional viewfinder interface,
 * lens switching, Pro mode adjustments, live histogram, and before/after comparison.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraEngine: CameraEngine
    private lateinit var captureController: CaptureController

    private var lastCaptureResult: CaptureResultData? = null
    private val isAnalyzing = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastAnalysisTimestampMs = 0L

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { cameraGranted ->
        if (cameraGranted) {
            setupCamera()
        } else {
            Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { audioGranted ->
        if (!audioGranted) {
            Toast.makeText(this, "Audio permission is recommended for video recording", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraEngine = CameraEngine(this, this, binding.viewFinder)
        captureController = CaptureController(this, cameraEngine, lifecycleScope)

        setupUI()

        // Only CAMERA is mandatory to open viewfinder and capture photos
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setupCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupCamera() {
        captureController.imageProcessorHook = { inputFile, outputFile, profile, mode, module ->
            com.motovision.pro.processing.ImageProcessingEngine.processImageFile(
                inputFile,
                outputFile,
                profile,
                mode,
                module
            )
        }

        cameraEngine.onCameraErrorListener = { errorMessage ->
            runOnUiThread {
                binding.txtStatus.text = errorMessage
                binding.txtStatus.visibility = View.VISIBLE
                Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
            }
        }

        cameraEngine.startCamera {
            runOnUiThread {
                updateLensUi(cameraEngine.currentModule)
                setupProControlsFromCapabilities()
            }
        }

        // Live frame analysis for Histogram and Focus Peaking (throttled)
        cameraEngine.frameAnalysisListener = { imageProxy ->
            computeHistogramAndMetrics(imageProxy)
        }

        // Capture Controller callbacks
        captureController.onStateChanged = { state, message ->
            when (state) {
                CaptureController.State.IDLE -> {
                    binding.btnShutter.setProcessingState(false)
                    binding.txtStatus.visibility = View.GONE
                }
                CaptureController.State.CAPTURING -> {
                    binding.btnShutter.setProcessingState(true)
                    binding.txtStatus.text = message ?: getString(R.string.capturing)
                    binding.txtStatus.visibility = View.VISIBLE
                }
                CaptureController.State.PROCESSING -> {
                    binding.btnShutter.setProcessingState(true)
                    binding.txtStatus.text = message ?: getString(R.string.processing)
                    binding.txtStatus.visibility = View.VISIBLE
                }
                CaptureController.State.ERROR -> {
                    binding.btnShutter.setProcessingState(false)
                    binding.txtStatus.text = message ?: "Error"
                    binding.txtStatus.visibility = View.VISIBLE
                }
            }
        }

        captureController.onCaptureProgress = { progress ->
            binding.btnShutter.setProgress(progress)
        }

        captureController.onCaptureComplete = { result ->
            lastCaptureResult = result
            val rawMsg = if (result.rawFile != null) " + RAW" else ""
            Toast.makeText(this, "${getString(R.string.saved)}$rawMsg", Toast.LENGTH_SHORT).show()

            // Update thumbnail
            result.processedFile?.let { file ->
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) {
                    binding.btnGalleryThumbnail.setImageBitmap(bmp)
                }
            }
        }

        // Video Recording Engine callbacks
        cameraEngine.videoEngine.onProgressUpdate = { progress ->
            runOnUiThread {
                binding.txtRecordingTimer.text = progress.formattedTime
                if (progress.state == VideoRecordingEngine.RecordingState.RECORDING) {
                    binding.recordingTimerContainer.visibility = View.VISIBLE
                    binding.btnShutter.setProcessingState(true)
                } else {
                    binding.recordingTimerContainer.visibility = View.GONE
                    binding.btnShutter.setProcessingState(false)
                }
            }
        }

        cameraEngine.videoEngine.onRecordingFinalized = { file, success ->
            runOnUiThread {
                binding.recordingTimerContainer.visibility = View.GONE
                binding.btnShutter.setProcessingState(false)
                if (success) {
                    Toast.makeText(this, "Video saved: ${file.name}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        cameraEngine.videoEngine.onError = { error ->
            runOnUiThread {
                binding.recordingTimerContainer.visibility = View.GONE
                binding.btnShutter.setProcessingState(false)
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupUI() {
        // Shutter Button (Photo capture vs Video recording)
        binding.btnShutter.onShutterClickListener = {
            if (cameraEngine.currentMode.isVideo) {
                val state = cameraEngine.videoEngine.currentState
                if (state == VideoRecordingEngine.RecordingState.RECORDING) {
                    cameraEngine.videoEngine.stopRecording()
                } else if (state == VideoRecordingEngine.RecordingState.IDLE) {
                    cameraEngine.videoEngine.startRecording()
                }
            } else {
                captureController.capturePhoto()
            }
        }

        // Video Quick Settings
        binding.btnVideoResolution.setOnClickListener {
            val currentRes = cameraEngine.videoEngine.currentConfig.resolution
            val newRes = if (currentRes == VideoConfiguration.VideoResolution.FHD_1080P) {
                VideoConfiguration.VideoResolution.HD_720P
            } else {
                VideoConfiguration.VideoResolution.FHD_1080P
            }
            cameraEngine.videoEngine.updateConfiguration(
                cameraEngine.videoEngine.currentConfig.copy(resolution = newRes)
            )
            binding.btnVideoResolution.text = newRes.displayName
            cameraEngine.bindCameraUseCases()
        }

        binding.btnVideoBitrate.setOnClickListener {
            val currentBitrate = cameraEngine.videoEngine.currentConfig.bitrateMode
            val newBitrate = if (currentBitrate == VideoConfiguration.BitrateMode.HIGH) {
                VideoConfiguration.BitrateMode.STANDARD
            } else {
                VideoConfiguration.BitrateMode.HIGH
            }
            cameraEngine.videoEngine.updateConfiguration(
                cameraEngine.videoEngine.currentConfig.copy(bitrateMode = newBitrate)
            )
            binding.btnVideoBitrate.text = if (newBitrate == VideoConfiguration.BitrateMode.HIGH) "High (38M)" else "Std (16M)"
        }

        binding.btnVideoEis.setOnClickListener {
            val currentEis = cameraEngine.videoEngine.currentConfig.isEisStabilizationEnabled
            val newEis = !currentEis
            cameraEngine.videoEngine.updateConfiguration(
                cameraEngine.videoEngine.currentConfig.copy(isEisStabilizationEnabled = newEis)
            )
            binding.btnVideoEis.text = if (newEis) "EIS ON" else "EIS OFF"
            binding.btnVideoEis.setTextColor(
                ContextCompat.getColor(this, if (newEis) R.color.mv_primary else R.color.mv_text_secondary)
            )
            cameraEngine.applyHardwareTuning()
        }

        // Camera Switch Button (Flip Rear/Front)
        binding.btnSwitchCamera.setOnClickListener {
            val targetModule = if (cameraEngine.currentModule.isFrontFacing) {
                CameraModule.MAIN_REAR
            } else {
                CameraModule.FRONT
            }
            switchCameraModule(targetModule)
        }

        // Lens Buttons (0.6x, 1x, Macro, Front)
        binding.btnLensWide.setOnClickListener { switchCameraModule(CameraModule.ULTRAWIDE) }
        binding.btnLensMain.setOnClickListener { switchCameraModule(CameraModule.MAIN_REAR) }
        binding.btnLensMacro.setOnClickListener { switchCameraModule(CameraModule.MACRO) }
        binding.btnLensFront.setOnClickListener { switchCameraModule(CameraModule.FRONT) }

        // Mode Carousel Click Listeners
        val modeViews = mapOf(
            binding.modeNight to CaptureMode.NIGHT,
            binding.modeHdr to CaptureMode.HDR,
            binding.modePhoto to CaptureMode.PHOTO,
            binding.modePortrait to CaptureMode.PORTRAIT,
            binding.modePro to CaptureMode.PRO,
            binding.modeVideo to CaptureMode.VIDEO
        )

        modeViews.forEach { (view, mode) ->
            view.setOnClickListener {
                selectCaptureMode(mode, modeViews)
            }
        }

        // Enhancement Profile Selector Button
        binding.btnProfile.setOnClickListener { view ->
            showProfilePopupMenu(view)
        }

        // Gallery / Compare Thumbnail
        binding.btnGalleryThumbnail.setOnClickListener {
            showComparisonView()
        }

        binding.btnCloseComparison.setOnClickListener {
            binding.comparisonContainer.visibility = View.GONE
        }

        // Touch to focus and metering
        binding.viewFinder.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                cameraEngine.triggerFocusAndMetering(event.x, event.y)
            }
            true
        }

        // Info Dialog
        binding.btnInfo.setOnClickListener {
            showCapabilitiesInfoDialog()
        }
    }

    private fun switchCameraModule(module: CameraModule) {
        cameraEngine.switchModule(module) {
            runOnUiThread {
                updateLensUi(module)
                setupProControlsFromCapabilities()
            }
        }
    }

    private fun updateLensUi(activeModule: CameraModule) {
        val defaultColor = ContextCompat.getColor(this, R.color.mv_text_secondary)
        val activeColor = ContextCompat.getColor(this, R.color.mv_primary)

        val lensButtons = listOf(
            binding.btnLensWide to CameraModule.ULTRAWIDE,
            binding.btnLensMain to CameraModule.MAIN_REAR,
            binding.btnLensMacro to CameraModule.MACRO,
            binding.btnLensFront to CameraModule.FRONT
        )

        lensButtons.forEach { (btn, mod) ->
            if (mod == activeModule) {
                btn.setTextColor(activeColor)
                btn.setBackgroundResource(R.drawable.bg_lens_active)
            } else {
                btn.setTextColor(defaultColor)
                btn.setBackgroundResource(0)
            }
        }
    }

    private fun selectCaptureMode(mode: CaptureMode, modeViews: Map<TextView, CaptureMode>) {
        cameraEngine.setCaptureMode(mode)

        val defaultColor = ContextCompat.getColor(this, R.color.mv_text_secondary)
        val activeColor = ContextCompat.getColor(this, R.color.mv_primary)

        modeViews.forEach { (view, m) ->
            if (m == mode) {
                view.setTextColor(activeColor)
                view.textSize = 14f
            } else {
                view.setTextColor(defaultColor)
                view.textSize = 13f
            }
        }

        // Show/hide Pro and Video panels
        if (mode.isVideo) {
            binding.videoControlPanel.visibility = View.VISIBLE
            binding.proControlPanel.visibility = View.GONE
            binding.focusPeakingOverlay.isPeakingEnabled = false
            binding.histogramView.visibility = View.GONE

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else if (mode.supportsProControls) {
            binding.videoControlPanel.visibility = View.GONE
            binding.proControlPanel.visibility = View.VISIBLE
            binding.focusPeakingOverlay.isPeakingEnabled = true
            binding.histogramView.visibility = View.VISIBLE
        } else {
            binding.videoControlPanel.visibility = View.GONE
            binding.proControlPanel.visibility = View.GONE
            binding.focusPeakingOverlay.isPeakingEnabled = false
            binding.histogramView.visibility = View.VISIBLE
        }

        // Update shutter icon for video vs photo
        binding.btnShutter.setVideoRecording(mode.isVideo)
    }

    private fun showProfilePopupMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        ProcessingProfile.values().forEachIndexed { index, profile ->
            popup.menu.add(0, index, index, profile.title)
        }
        popup.setOnMenuItemClickListener { item ->
            val profile = ProcessingProfile.values()[item.itemId]
            cameraEngine.setProcessingProfile(profile)
            binding.btnProfile.text = profile.title
            true
        }
        popup.show()
    }

    private fun setupProControlsFromCapabilities() {
        val caps = cameraEngine.discovery.getCapabilities(cameraEngine.currentModule) ?: return

        // ISO Slider setup
        val isoRange = caps.isoRange ?: Range(100, 3200)
        binding.sliderIso.valueFrom = 0f // 0 = Auto
        binding.sliderIso.valueTo = isoRange.upper.toFloat()
        binding.sliderIso.stepSize = 100f
        binding.sliderIso.value = 0f
        binding.sliderIso.addOnChangeListener { _, value, _ ->
            if (value == 0f) {
                binding.txtProIsoLabel.text = "ISO Auto"
                cameraEngine.manualIso = null
            } else {
                val iso = value.toInt()
                binding.txtProIsoLabel.text = "ISO $iso"
                cameraEngine.manualIso = iso
            }
            cameraEngine.applyHardwareTuning()
        }

        // Shutter Slider setup
        binding.sliderShutter.valueFrom = 0f // 0 = Auto
        binding.sliderShutter.valueTo = 1000f // 1/1000s up to 1/2s
        binding.sliderShutter.stepSize = 10f
        binding.sliderShutter.value = 0f
        binding.sliderShutter.addOnChangeListener { _, value, _ ->
            if (value == 0f) {
                binding.txtProShutterLabel.text = "S Auto"
                cameraEngine.manualExposureTimeNs = null
            } else {
                val denominator = value.toInt().coerceAtLeast(1)
                binding.txtProShutterLabel.text = "1/${denominator}s"
                cameraEngine.manualExposureTimeNs = (1_000_000_000L / denominator)
            }
            cameraEngine.applyHardwareTuning()
        }

        // Focus Slider setup
        if (caps.isFixedFocus) {
            binding.sliderFocus.isEnabled = false
            binding.txtProFocusLabel.text = "Fixed"
        } else {
            binding.sliderFocus.isEnabled = true
            val maxDiopters = caps.minFocusDistanceDiopters.coerceAtLeast(10f)
            binding.sliderFocus.valueFrom = 0f // 0 = Infinity / Auto
            binding.sliderFocus.valueTo = maxDiopters
            binding.sliderFocus.stepSize = 0.5f
            binding.sliderFocus.value = 0f
            binding.sliderFocus.addOnChangeListener { _, value, _ ->
                if (value == 0f) {
                    binding.txtProFocusLabel.text = "AF Auto"
                    cameraEngine.manualFocusDistanceDiopters = null
                } else {
                    binding.txtProFocusLabel.text = String.format("MF %.1fd", value)
                    cameraEngine.manualFocusDistanceDiopters = value
                }
                cameraEngine.applyHardwareTuning()
            }
        }

        // EV Compensation Slider setup
        binding.sliderEv.value = 0.0f
        binding.sliderEv.addOnChangeListener { _, value, _ ->
            val proController = ProModeController(caps)
            cameraEngine.exposureCompensationIndex = proController.calculateExposureCompensationIndex(value)
            binding.txtProEvLabel.text = String.format("EV %+.1f", value)
            cameraEngine.applyHardwareTuning()
        }

        // White Balance Preset Selector
        binding.btnProWb.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            ProModeController.WhiteBalancePreset.values().forEachIndexed { index, preset ->
                popup.menu.add(0, index, index, preset.displayName)
            }
            popup.setOnMenuItemClickListener { item ->
                val preset = ProModeController.WhiteBalancePreset.values()[item.itemId]
                cameraEngine.manualWbPreset = preset
                binding.btnProWb.text = preset.displayName
                cameraEngine.applyHardwareTuning()
                true
            }
            popup.show()
        }

        // Focus Peaking Toggle
        binding.btnTogglePeaking.setOnClickListener {
            val enabled = !binding.focusPeakingOverlay.isPeakingEnabled
            binding.focusPeakingOverlay.isPeakingEnabled = enabled
            binding.btnTogglePeaking.text = if (enabled) "Peaking ON" else "Peaking OFF"
            binding.btnTogglePeaking.setTextColor(
                ContextCompat.getColor(this, if (enabled) R.color.mv_primary else R.color.mv_text_secondary)
            )
        }

        // RAW Capture Toggle
        binding.btnToggleRaw.setOnClickListener {
            val enabled = !cameraEngine.isRawCaptureEnabled
            cameraEngine.isRawCaptureEnabled = enabled
            val isHw = caps.supportsRaw
            binding.btnToggleRaw.text = if (enabled) {
                if (isHw) "RAW (DNG)" else "RAW (Linear)"
            } else {
                "RAW OFF"
            }
            binding.btnToggleRaw.setTextColor(
                ContextCompat.getColor(this, if (enabled) R.color.mv_primary else R.color.mv_text_secondary)
            )
        }
    }

    private fun showComparisonView() {
        val result = lastCaptureResult
        if (result == null || result.processedFile == null || !result.originalFile.exists()) {
            Toast.makeText(this, "Capture a photograph to compare results", Toast.LENGTH_SHORT).show()
            return
        }

        val origBmp = BitmapFactory.decodeFile(result.originalFile.absolutePath)
        val enhBmp = BitmapFactory.decodeFile(result.processedFile.absolutePath)

        if (origBmp != null && enhBmp != null) {
            binding.comparisonView.setBitmaps(origBmp, enhBmp)
            binding.comparisonContainer.visibility = View.VISIBLE
        }
    }

    private fun showCapabilitiesInfoDialog() {
        val caps = cameraEngine.discovery.getCapabilities(cameraEngine.currentModule)
        val message = StringBuilder().apply {
            append("Target Platform: Motorola Moto G23 (Helio G85)\n")
            append("Active Module: ${cameraEngine.currentModule.displayName}\n")
            append("Hardware Level: ${caps?.hardwareLevelName ?: "UNKNOWN"}\n")
            append("RAW Stream: ${if (caps?.supportsRaw == true) "Supported" else "Not exposed (Linear YUV active)"}\n")
            append("Manual Exposure: ${if (caps?.supportsManualExposure == true) "Yes" else "Limited"}\n")
            append("Focus: ${if (caps?.isFixedFocus == true) "Fixed Focus" else "PDAF / Manual"}\n")
            append("Max Resolution: ${caps?.maxPhotoResolution?.width}x${caps?.maxPhotoResolution?.height}\n")
            append("Stabilization: ${if (caps?.isVideoStabilizationSupported == true) "Hardware EIS" else "Software EIS"}\n")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Camera Hardware Profile")
            .setMessage(message.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Extracts luminance and RGB histogram bins from real-time YUV analysis frames.
     * Throttled to max 10 fps with frame dropping to prevent UI thread flooding and ANR.
     */
    private fun computeHistogramAndMetrics(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastAnalysisTimestampMs < 100L) {
            imageProxy.close()
            return
        }

        val showHistogram = binding.histogramView.visibility == View.VISIBLE
        val showPeaking = binding.focusPeakingOverlay.isPeakingEnabled

        if (!showHistogram && !showPeaking) {
            imageProxy.close()
            return
        }

        if (!isAnalyzing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        lastAnalysisTimestampMs = now

        try {
            val yPlane = imageProxy.planes[0]
            val buffer = yPlane.buffer
            val rowStride = yPlane.rowStride
            val pixelStride = yPlane.pixelStride
            val width = imageProxy.width
            val height = imageProxy.height

            // 1. Focus Peaking if enabled
            if (showPeaking) {
                val peakingBmp = FocusPeakingEngine.generatePeakingBitmap(
                    yBuffer = buffer,
                    width = width,
                    height = height,
                    rowStride = rowStride,
                    downsample = 2
                )
                runOnUiThread {
                    binding.focusPeakingOverlay.updatePeakingBitmap(peakingBmp)
                }
            }

            // 2. Real-time Luminance and RGB Histogram if visible
            if (showHistogram) {
                val lumBins = IntArray(256)
                val redBins = IntArray(256)
                val greenBins = IntArray(256)
                val blueBins = IntArray(256)

                val uPlane = imageProxy.planes.getOrNull(1)
                val vPlane = imageProxy.planes.getOrNull(2)

                val step = 8 // Downsample step from 4 to 8 for <0.5ms computation on Helio G85
                for (y in 0 until height step step) {
                    val yRow = y * rowStride
                    for (x in 0 until width step step) {
                        val yIndex = yRow + x * pixelStride
                        if (yIndex < buffer.remaining()) {
                            val lum = buffer.get(yIndex).toInt() and 0xFF
                            lumBins[lum]++

                            if (uPlane != null && vPlane != null) {
                                val uvY = y / 2
                                val uvX = x / 2
                                val uIndex = uvY * uPlane.rowStride + uvX * uPlane.pixelStride
                                val vIndex = uvY * vPlane.rowStride + uvX * vPlane.pixelStride

                                if (uIndex < uPlane.buffer.remaining() && vIndex < vPlane.buffer.remaining()) {
                                    val u = (uPlane.buffer.get(uIndex).toInt() and 0xFF) - 128
                                    val v = (vPlane.buffer.get(vIndex).toInt() and 0xFF) - 128

                                    val r = (lum + 1.402f * v).toInt().coerceIn(0, 255)
                                    val g = (lum - 0.344136f * u - 0.714136f * v).toInt().coerceIn(0, 255)
                                    val b = (lum + 1.772f * u).toInt().coerceIn(0, 255)

                                    redBins[r]++
                                    greenBins[g]++
                                    blueBins[b]++
                                }
                            }
                        }
                    }
                }

                runOnUiThread {
                    binding.histogramView.updateHistogram(lumBins, redBins, greenBins, blueBins)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error computing live histogram & peaking: ${e.message}")
        } finally {
            imageProxy.close()
            isAnalyzing.set(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraEngine.shutdown()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
