package com.motovision.pro

import android.hardware.camera2.CameraCharacteristics
import android.util.Range
import android.util.Size
import com.motovision.pro.model.CameraCapabilities
import com.motovision.pro.model.CameraModule
import com.motovision.pro.model.CaptureMode
import com.motovision.pro.model.ImageResolution
import com.motovision.pro.model.ProcessingProfile
import org.junit.Assert.*
import org.junit.Test

class CameraBasicsTest {

    @Test
    fun testCameraModuleProperties() {
        assertEquals("1x Main", CameraModule.MAIN_REAR.displayName)
        assertFalse(CameraModule.MAIN_REAR.isFrontFacing)
        assertTrue(CameraModule.FRONT.isFrontFacing)

        assertEquals("0.6x Wide", CameraModule.ULTRAWIDE.displayName)
        assertEquals(0.6f, CameraModule.ULTRAWIDE.zoomFactor, 0.001f)
        assertTrue(CameraModule.ULTRAWIDE.isSecondaryRear())

        assertEquals("Macro", CameraModule.MACRO.displayName)
        assertTrue(CameraModule.MACRO.isSecondaryRear())
        assertFalse(CameraModule.MAIN_REAR.isSecondaryRear())
    }

    @Test
    fun testCaptureModeProperties() {
        assertFalse(CaptureMode.PHOTO.isVideo)
        assertFalse(CaptureMode.PHOTO.supportsProControls)
        assertFalse(CaptureMode.PHOTO.requiresMultiFrame)

        assertTrue(CaptureMode.NIGHT.requiresMultiFrame)
        assertTrue(CaptureMode.HDR.requiresMultiFrame)
        assertTrue(CaptureMode.PRO.supportsProControls)
        assertTrue(CaptureMode.VIDEO.isVideo)
    }

    @Test
    fun testProcessingProfiles() {
        val natural = ProcessingProfile.NATURAL
        val balanced = ProcessingProfile.BALANCED
        val detailed = ProcessingProfile.DETAILED
        val vibrant = ProcessingProfile.VIBRANT

        assertTrue(natural.sharpnessWeight < balanced.sharpnessWeight)
        assertTrue(balanced.sharpnessWeight < detailed.sharpnessWeight)

        assertEquals(1.0f, natural.contrastBoost, 0.001f)
        assertEquals(1.0f, natural.saturationScale, 0.001f)
        assertTrue(vibrant.saturationScale > 1.1f)
    }

    @Test
    fun testHardwareLevelToString() {
        assertEquals("LEGACY", CameraCapabilities.hardwareLevelToString(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY))
        assertEquals("LIMITED", CameraCapabilities.hardwareLevelToString(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED))
        assertEquals("FULL", CameraCapabilities.hardwareLevelToString(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL))
        assertEquals("LEVEL_3", CameraCapabilities.hardwareLevelToString(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3))
    }

    @Test
    fun testCameraCapabilitiesResolutionSelection() {
        val supportedSizes = listOf(
            ImageResolution(1920, 1080),
            ImageResolution(4000, 3000), // 12.0 MP
            ImageResolution(4080, 3060), // 12.5 MP Tetracell binned
            ImageResolution(8160, 6144)  // 50 MP full remosaic
        )

        val caps = CameraCapabilities(
            cameraId = "0",
            lensFacing = CameraCharacteristics.LENS_FACING_BACK,
            hardwareLevel = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED,
            hardwareLevelName = "LIMITED",
            supportsRaw = false,
            supportsManualExposure = true,
            supportsManualFocus = true,
            isOpticalStabilizationSupported = false,
            isVideoStabilizationSupported = true,
            isoRange = Range(100, 3200),
            exposureTimeRangeNs = Range(100_000L, 1_000_000_000L),
            exposureCompensationRange = Range(-6, 6),
            exposureCompensationStep = 0.333f,
            minFocusDistanceDiopters = 10.0f,
            supportedJpegResolutions = supportedSizes,
            supportedYuvResolutions = supportedSizes.dropLast(1),
            supportedRawResolutions = emptyList(),
            physicalPixelArraySize = ImageResolution(8160, 6144),
            focalLengths = floatArrayOf(4.26f),
            availableAfModes = intArrayOf(
                CameraCharacteristics.CONTROL_AF_MODE_AUTO,
                CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        )

        assertFalse(caps.isFixedFocus)
        assertEquals(ImageResolution(8160, 6144), caps.maxPhotoResolution)
        assertEquals(ImageResolution(4080, 3060), caps.optimal12_5MPResolution)
        assertTrue(ImageResolution(4080, 3060).isFourThree)
        assertTrue(ImageResolution(1920, 1080).isSixteenNine)
    }
}
