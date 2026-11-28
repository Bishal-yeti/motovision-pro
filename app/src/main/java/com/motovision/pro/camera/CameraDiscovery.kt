package com.motovision.pro.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Log
import android.util.Range
import android.util.Size
import com.motovision.pro.model.CameraCapabilities
import com.motovision.pro.model.CameraModule
import com.motovision.pro.model.ImageResolution

/**
 * Probes and maps available hardware camera sensors on the device.
 * Dynamically resolves CameraModule types based on lens facing, focal lengths,
 * sensor pixel dimensions, and physical capabilities.
 */
class CameraDiscovery(private val cameraManager: CameraManager) {

    private val capabilitiesMap = mutableMapOf<String, CameraCapabilities>()
    private val moduleToCameraIdMap = mutableMapOf<CameraModule, String>()

    init {
        discoverCameras()
    }

    fun getCapabilities(cameraId: String): CameraCapabilities? = capabilitiesMap[cameraId]

    fun getCapabilities(module: CameraModule): CameraCapabilities? {
        val id = moduleToCameraIdMap[module] ?: return null
        return capabilitiesMap[id]
    }

    fun getCameraIdForModule(module: CameraModule): String? = moduleToCameraIdMap[module]

    fun getAllDiscoveredModules(): List<CameraModule> = moduleToCameraIdMap.keys.toList()

    fun getAllCapabilities(): Map<String, CameraCapabilities> = capabilitiesMap.toMap()

    private fun discoverCameras() {
        capabilitiesMap.clear()
        moduleToCameraIdMap.clear()

        try {
            val cameraIds = cameraManager.cameraIdList
            Log.d(TAG, "Found ${cameraIds.size} total camera IDs: ${cameraIds.joinToString()}")

            val rearCandidates = mutableListOf<Pair<String, CameraCapabilities>>()
            val frontCandidates = mutableListOf<Pair<String, CameraCapabilities>>()

            for (id in cameraIds) {
                try {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val caps = parseCharacteristics(id, chars)
                    capabilitiesMap[id] = caps

                    when (caps.lensFacing) {
                        CameraCharacteristics.LENS_FACING_BACK -> rearCandidates.add(id to caps)
                        CameraCharacteristics.LENS_FACING_FRONT -> frontCandidates.add(id to caps)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error probing camera characteristics for ID $id: ${e.message}")
                }
            }

            // Map Rear cameras first (Main, Ultrawide, Macro)
            if (rearCandidates.isNotEmpty()) {
                // Sort candidates by sensor resolution descending
                val sortedRear = rearCandidates.sortedByDescending {
                    val maxRes = it.second.maxPhotoResolution
                    (maxRes?.width?.toLong() ?: 0L) * (maxRes?.height?.toLong() ?: 0L)
                }

                // 1. Main rear camera is the primary high-res sensor with autofocus
                val mainCandidate = sortedRear.firstOrNull { !it.second.isFixedFocus } ?: sortedRear.first()
                moduleToCameraIdMap[CameraModule.MAIN_REAR] = mainCandidate.first
                Log.i(TAG, "Mapped MAIN_REAR camera to ID ${mainCandidate.first}")

                // Remaining rear candidates
                val remainingRear = sortedRear.filter { it.first != mainCandidate.first }

                for (cand in remainingRear) {
                    val caps = cand.second
                    val focalLength = caps.focalLengths.minOrNull() ?: 3.0f

                    // Ultrawide typically has shorter focal length (< 2.2mm or wide FoV) or 5MP resolution
                    val maxRes = caps.maxPhotoResolution
                    val pixelCount = (maxRes?.width?.toLong() ?: 0L) * (maxRes?.height?.toLong() ?: 0L)

                    if (!moduleToCameraIdMap.containsKey(CameraModule.ULTRAWIDE) &&
                        (focalLength < 2.5f || pixelCount in 4_000_000L..8_000_000L)) {
                        moduleToCameraIdMap[CameraModule.ULTRAWIDE] = cand.first
                        Log.i(TAG, "Mapped ULTRAWIDE camera to ID ${cand.first}")
                    } else if (!moduleToCameraIdMap.containsKey(CameraModule.MACRO) &&
                        (pixelCount in 1_000_000L..3_000_000L || caps.minFocusDistanceDiopters > 20.0f)) {
                        moduleToCameraIdMap[CameraModule.MACRO] = cand.first
                        Log.i(TAG, "Mapped MACRO camera to ID ${cand.first}")
                    }
                }
            }

            // Fallback ensure MAIN_REAR exists if any camera is available
            if (!moduleToCameraIdMap.containsKey(CameraModule.MAIN_REAR) && cameraIds.isNotEmpty()) {
                moduleToCameraIdMap[CameraModule.MAIN_REAR] = cameraIds[0]
            }

            // Map Front camera
            if (frontCandidates.isNotEmpty()) {
                // Main front camera is usually the one with highest resolution
                val bestFront = frontCandidates.maxByOrNull {
                    val maxRes = it.second.maxPhotoResolution
                    (maxRes?.width?.toLong() ?: 0L) * (maxRes?.height?.toLong() ?: 0L)
                }
                if (bestFront != null) {
                    moduleToCameraIdMap[CameraModule.FRONT] = bestFront.first
                    Log.i(TAG, "Mapped FRONT camera to ID ${bestFront.first}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to enumerate cameras: ${e.message}", e)
        }
    }

    private fun parseCharacteristics(id: String, chars: CameraCharacteristics): CameraCapabilities {
        val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
        val level = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
        val levelName = CameraCapabilities.hardwareLevelToString(level)

        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val supportsRaw = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val supportsManualSensor = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)

        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureTimeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val expCompRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val expCompStepRational = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        val expCompStep = expCompStepRational?.toFloat() ?: 0.333f

        val minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0.0f
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(0.0f)
        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()

        // Optical stabilization
        val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
        val supportsOis = oisModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)

        // Video stabilization
        val vStabModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
        val supportsVStab = vStabModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)

        // Sensor pixel array size
        val pixelArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)

        // Output resolutions
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val jpegSizes: List<ImageResolution> = map?.getOutputSizes(ImageFormat.JPEG)?.map { ImageResolution.fromSize(it) } ?: emptyList()
        val yuvSizes: List<ImageResolution> = map?.getOutputSizes(ImageFormat.YUV_420_888)?.map { ImageResolution.fromSize(it) } ?: emptyList()
        val rawSizes: List<ImageResolution> = if (supportsRaw) {
            map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.map { ImageResolution.fromSize(it) } ?: emptyList()
        } else {
            emptyList()
        }

        return CameraCapabilities(
            cameraId = id,
            lensFacing = facing,
            hardwareLevel = level,
            hardwareLevelName = levelName,
            supportsRaw = supportsRaw,
            supportsManualExposure = supportsManualSensor,
            supportsManualFocus = minFocusDist > 0.0f,
            isOpticalStabilizationSupported = supportsOis,
            isVideoStabilizationSupported = supportsVStab,
            isoRange = isoRange,
            exposureTimeRangeNs = exposureTimeRange,
            exposureCompensationRange = expCompRange,
            exposureCompensationStep = expCompStep,
            minFocusDistanceDiopters = minFocusDist,
            supportedJpegResolutions = jpegSizes,
            supportedYuvResolutions = yuvSizes,
            supportedRawResolutions = rawSizes,
            physicalPixelArraySize = pixelArraySize?.let { ImageResolution.fromSize(it) },
            focalLengths = focalLengths,
            availableAfModes = afModes,
            ispAdvertised = IspTuningPlanner.Advertised(
                noiseReduction = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: intArrayOf(),
                edge = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf(),
                hotPixel = chars.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES) ?: intArrayOf(),
                colorAberration = chars.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES) ?: intArrayOf(),
                tonemap = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: intArrayOf(),
                shading = chars.get(CameraCharacteristics.SHADING_AVAILABLE_MODES) ?: intArrayOf(),
                ois = oisModes,
                aeFpsRanges = (chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray())
                    .flatMap { listOf(it.lower, it.upper) }.toIntArray()
            )
        )
    }

    companion object {
        private const val TAG = "CameraDiscovery"
    }
}
