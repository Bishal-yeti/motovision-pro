package com.motovision.pro.model

import android.hardware.camera2.CameraCharacteristics
import android.util.Range
import android.util.Size

/**
 * Holds verified runtime capabilities discovered via Camera2 CameraCharacteristics.
 * Ensures the app never hardcodes unsupported features or assumes advertised megapixels.
 */
data class CameraCapabilities(
    val cameraId: String,
    val lensFacing: Int,
    val hardwareLevel: Int,
    val hardwareLevelName: String,
    val supportsRaw: Boolean,
    val supportsManualExposure: Boolean,
    val supportsManualFocus: Boolean,
    val isOpticalStabilizationSupported: Boolean,
    val isVideoStabilizationSupported: Boolean,
    val isoRange: Range<Int>?,
    val exposureTimeRangeNs: Range<Long>?,
    val exposureCompensationRange: Range<Int>?,
    val exposureCompensationStep: Float,
    val minFocusDistanceDiopters: Float,
    val supportedJpegResolutions: List<ImageResolution>,
    val supportedYuvResolutions: List<ImageResolution>,
    val supportedRawResolutions: List<ImageResolution>,
    val physicalPixelArraySize: ImageResolution?,
    val focalLengths: FloatArray,
    val availableAfModes: IntArray,
    /** ISP modes the HAL advertises; drives hardware tuning so unsupported modes are never requested. */
    val ispAdvertised: com.motovision.pro.camera.IspTuningPlanner.Advertised = com.motovision.pro.camera.IspTuningPlanner.Advertised()
) {
    val isFixedFocus: Boolean
        get() = minFocusDistanceDiopters == 0.0f

    val maxPhotoResolution: ImageResolution?
        get() = supportedJpegResolutions.maxOrNull()

    val optimal12_5MPResolution: ImageResolution?
        get() = supportedJpegResolutions.find { it.width == 4080 && it.height == 3060 }
            ?: supportedJpegResolutions.find { it.width == 4000 && it.height == 3000 }
            ?: maxPhotoResolution

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CameraCapabilities
        if (cameraId != other.cameraId) return false
        if (lensFacing != other.lensFacing) return false
        if (hardwareLevel != other.hardwareLevel) return false
        return true
    }

    override fun hashCode(): Int {
        var result = cameraId.hashCode()
        result = 31 * result + lensFacing
        result = 31 * result + hardwareLevel
        return result
    }

    companion object {
        fun hardwareLevelToString(level: Int): String = when (level) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN ($level)"
        }
    }
}
