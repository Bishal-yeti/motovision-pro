package com.motovision.pro

import android.hardware.camera2.CameraCharacteristics
import android.util.Range
import android.util.Rational
import com.motovision.pro.model.CameraCapabilities
import com.motovision.pro.model.ImageResolution
import com.motovision.pro.pro.FocusPeakingEngine
import com.motovision.pro.pro.ProModeController
import com.motovision.pro.pro.RawCaptureManager
import com.motovision.pro.video.VideoConfiguration
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VideoAndProModeTest {

    @Test
    fun testVideoBitrateAndCodecCalculation() {
        val fhdHighH264 = VideoConfiguration(
            resolution = VideoConfiguration.VideoResolution.FHD_1080P,
            codec = VideoConfiguration.VideoCodec.H264,
            bitrateMode = VideoConfiguration.BitrateMode.HIGH
        )
        // High Bitrate on 1080p H.264 should be 38 Mbps to prevent stock macroblocking
        assertEquals(38_000_000, fhdHighH264.calculateTargetBitrate())

        val fhdStdH264 = VideoConfiguration(
            resolution = VideoConfiguration.VideoResolution.FHD_1080P,
            codec = VideoConfiguration.VideoCodec.H264,
            bitrateMode = VideoConfiguration.BitrateMode.STANDARD
        )
        assertEquals(16_000_000, fhdStdH264.calculateTargetBitrate())

        val fhdHighHevc = VideoConfiguration(
            resolution = VideoConfiguration.VideoResolution.FHD_1080P,
            codec = VideoConfiguration.VideoCodec.HEVC,
            bitrateMode = VideoConfiguration.BitrateMode.HIGH
        )
        // HEVC is 25% lower bitrate at matching quality (28.5 Mbps)
        assertEquals(28_500_000, fhdHighHevc.calculateTargetBitrate())

        // File size estimation for 60 seconds (1 minute) of 1080p High
        val sizeMb = fhdHighH264.estimateFileSizeMb(60)
        assertTrue("1 minute of 38 Mbps video should be ~270-280 MB", sizeMb in 265f..285f)

        // EIS crop factor
        assertEquals(0.90f, fhdHighH264.getEisCropMarginFactor(), 0.001f)
        val eisOff = fhdHighH264.copy(isEisStabilizationEnabled = false)
        assertEquals(1.0f, eisOff.getEisCropMarginFactor(), 0.001f)
    }

    @Test
    fun testProModeParameterMath() {
        val dummyCaps = CameraCapabilities(
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
            exposureTimeRangeNs = Range(10_000L, 1_000_000_000L),
            exposureCompensationRange = Range(-6, 6),
            exposureCompensationStep = 0.33333334f,
            minFocusDistanceDiopters = 10.0f,
            supportedJpegResolutions = listOf(ImageResolution(4080, 3060)),
            supportedYuvResolutions = listOf(ImageResolution(4080, 3060)),
            supportedRawResolutions = emptyList(),
            physicalPixelArraySize = ImageResolution(4080, 3060),
            focalLengths = floatArrayOf(4.21f),
            availableAfModes = intArrayOf(1, 4)
        )

        val controller = ProModeController(dummyCaps)

        // 1. Shutter speed math
        val exposureNs1000 = controller.denominatorToExposureNs(1000)
        assertEquals(1_000_000L, exposureNs1000)
        assertEquals("1/1000s", controller.exposureNsToDisplayString(1_000_000L))
        assertEquals("1/60s", controller.exposureNsToDisplayString(16_666_666L))
        assertEquals("1.0s", controller.exposureNsToDisplayString(1_000_000_000L))

        // 2. ISO clamping
        assertEquals(100, controller.clampIso(50))
        assertEquals(800, controller.clampIso(800))
        assertEquals(3200, controller.clampIso(6400))

        // 3. EV offset index (step = 1/3 EV)
        assertEquals(0, controller.calculateExposureCompensationIndex(0.0f))
        assertEquals(3, controller.calculateExposureCompensationIndex(1.0f))
        assertEquals(-6, controller.calculateExposureCompensationIndex(-2.0f))
        assertEquals(6, controller.calculateExposureCompensationIndex(3.0f)) // clamped to max +6

        // 4. Focus distance cm to diopters (10cm = 10 diopters, 50cm = 2 diopters, infinity = 0 diopters)
        assertEquals(10.0f, controller.distanceCmToDiopters(10f), 0.01f)
        assertEquals(2.0f, controller.distanceCmToDiopters(50f), 0.01f)
        assertEquals(0.0f, controller.distanceCmToDiopters(0f), 0.01f)
    }

    @Test
    fun testKelvinToRggbGainsConversion() {
        val controller = ProModeController(null)

        // Warm light (Tungsten 3000K): should have high red gain relative to blue
        val warmGains = controller.kelvinToColorGains(3000)
        assertTrue("Warm 3000K red gain (${warmGains.red}) should exceed blue gain (${warmGains.blue})", warmGains.red > warmGains.blue)

        // Daylight (5500K): balanced gains
        val dayGains = controller.kelvinToColorGains(5500)
        assertTrue("Daylight gains should be bounded in normal range", dayGains.red in 1.0f..3.0f)

        // Cool light (Shade / Sky 8000K): should have high blue gain relative to red
        val coolGains = controller.kelvinToColorGains(8000)
        assertTrue("Cool 8000K blue gain (${coolGains.blue}) should exceed red gain (${coolGains.red})", coolGains.blue > coolGains.red)
    }

    @Test
    fun testFocusPeakingEdgeDetection() {
        val width = 16
        val height = 16
        val lum = ByteArray(width * height)

        // Create a sharp high-contrast vertical edge at x = 8 (lum 20 -> lum 230)
        for (y in 0 until height) {
            for (x in 8 until width) {
                lum[y * width + x] = 230.toByte()
            }
        }

        val edges = FocusPeakingEngine.detectPeakingEdges(lum, width, height, threshold = 32)

        // Check that pixels adjacent to the vertical edge at x = 7, 8 are flagged
        var edgeCount = 0
        for (y in 1 until height - 1) {
            if (edges[y * width + 7] || edges[y * width + 8]) {
                edgeCount++
            }
        }
        assertTrue("Sharp edge should be detected along transition line", edgeCount > (height - 4))

        // Check that flat regions away from edge are NOT flagged
        assertFalse("Flat region at x=2 should not be an edge", edges[4 * width + 2])
        assertFalse("Flat region at x=14 should not be an edge", edges[4 * width + 14])
    }

    @Test
    fun testRawModeDeterminationAndLinearDump() {
        // Create temporary test file for raw dump
        val tempDir = File(System.getProperty("java.io.tmpdir"), "mv_test_raw")
        tempDir.mkdirs()
        val rawFile = File(tempDir, "test_linear.mvraw")

        val rawManager = RawCaptureManager(
            context = org.mockito.Mockito.mock(android.content.Context::class.java)
        )

        // Fake sensor dump
        val fakeYuv = ByteArray(512) { (it % 255).toByte() }
        val success = rawManager.writeLinearSensorDump(
            yuvData = fakeYuv,
            width = 4080,
            height = 3060,
            iso = 200,
            exposureNs = 20_000_000L,
            outputFile = rawFile
        )

        assertTrue(success)
        assertTrue(rawFile.exists())
        assertEquals(512 + 64, rawFile.length()) // 64-byte header + 512 bytes payload

        // Verify magic bytes "MVRAW_01" in header
        val headerBytes = ByteArray(8)
        rawFile.inputStream().use { it.read(headerBytes) }
        assertEquals("MVRAW_01", String(headerBytes))

        rawFile.delete()
        tempDir.delete()
    }
}
