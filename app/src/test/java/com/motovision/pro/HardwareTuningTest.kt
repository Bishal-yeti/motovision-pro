package com.motovision.pro

import android.hardware.camera2.CaptureRequest
import com.motovision.pro.camera.IspTuningPlanner
import com.motovision.pro.camera.IspTuningPlanner.Advertised
import com.motovision.pro.system.DeviceHealthMonitor
import com.motovision.pro.system.DeviceHealthMonitor.QualityTier
import org.junit.Assert.*
import org.junit.Test

/** Source/unit-test verified only. Says nothing about real Moto G23 HAL behaviour. */
class HardwareTuningTest {

    private val fullAdv = Advertised(
        noiseReduction = intArrayOf(CaptureRequest.NOISE_REDUCTION_MODE_OFF, CaptureRequest.NOISE_REDUCTION_MODE_FAST, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY),
        edge = intArrayOf(CaptureRequest.EDGE_MODE_FAST, CaptureRequest.EDGE_MODE_HIGH_QUALITY),
        hotPixel = intArrayOf(CaptureRequest.HOT_PIXEL_MODE_FAST, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY),
        colorAberration = intArrayOf(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY),
        tonemap = intArrayOf(CaptureRequest.TONEMAP_MODE_FAST, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY),
        aeFpsRanges = intArrayOf(15, 30, 30, 30, 7, 20)
    )

    @Test
    fun stillCapturePrefersQualityModesWhenAdvertised() {
        val p = IspTuningPlanner.plan(fullAdv, stillQuality = true, thermalReduced = false, isVideo = false)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, p.noiseReduction)
        assertEquals(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY, p.hotPixel)
        assertEquals(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY, p.tonemap)
        assertEquals(CaptureRequest.EDGE_MODE_FAST, p.edge) // light ISP edge: own halo-free sharpener runs later
    }

    @Test
    fun neverRequestsModesTheHalDoesNotAdvertise() {
        val adv = Advertised(noiseReduction = intArrayOf(CaptureRequest.NOISE_REDUCTION_MODE_FAST))
        val p = IspTuningPlanner.plan(adv, true, false, false)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_FAST, p.noiseReduction)
        assertNull(p.hotPixel); assertNull(p.tonemap); assertNull(p.colorAberration)
        assertNull(p.opticalStabilization); assertNull(p.aeFpsRange)
        val empty = IspTuningPlanner.plan(Advertised(), true, false, false)
        assertNull(empty.noiseReduction); assertNull(empty.edge)
    }

    @Test
    fun thermalAndVideoFallBackToCheapModes() {
        val hot = IspTuningPlanner.plan(fullAdv, true, thermalReduced = true, isVideo = false)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_FAST, hot.noiseReduction)
        assertNull(hot.tonemap)
        val video = IspTuningPlanner.plan(fullAdv, true, false, isVideo = true)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_FAST, video.noiseReduction)
        assertArrayEquals(intArrayOf(30, 30), video.aeFpsRange)
    }

    @Test
    fun oisOnlyWhenLensHasItAndNotInVideo() {
        val adv = Advertised(ois = intArrayOf(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON))
        assertEquals(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON, IspTuningPlanner.plan(adv, true, false, false).opticalStabilization)
        assertNull(IspTuningPlanner.plan(adv, true, false, true).opticalStabilization)
    }

    @Test
    fun fpsRangeSelection() {
        assertArrayEquals(intArrayOf(15, 30), IspTuningPlanner.pickFpsRange(intArrayOf(15, 30, 24, 30, 7, 20), isVideo = false))
        assertArrayEquals(intArrayOf(24, 30), IspTuningPlanner.pickFpsRange(intArrayOf(15, 30, 24, 30), isVideo = true))
        assertNull(IspTuningPlanner.pickFpsRange(intArrayOf(), false))
    }

    @Test
    fun qualityTierDecisions() {
        assertEquals(QualityTier.FULL, DeviceHealthMonitor.tierFor(0, false, false))
        assertEquals(QualityTier.FULL, DeviceHealthMonitor.tierFor(1, false, false))
        assertEquals(QualityTier.REDUCED, DeviceHealthMonitor.tierFor(2, false, false))
        assertEquals(QualityTier.REDUCED, DeviceHealthMonitor.tierFor(0, true, false))
        assertEquals(QualityTier.REDUCED, DeviceHealthMonitor.tierFor(0, false, true))
        assertEquals(QualityTier.MINIMAL, DeviceHealthMonitor.tierFor(4, false, false))
        assertEquals(QualityTier.MINIMAL, DeviceHealthMonitor.tierFor(2, true, false))
    }
}
