package com.motovision.pro.camera

import android.hardware.camera2.CaptureRequest

/**
 * Chooses ISP (image signal processor) pipeline modes strictly from what the camera HAL
 * advertises in its CameraCharacteristics. Nothing is requested blindly: a mode that the
 * HAL does not list is never sent, so the request can't be rejected or silently ignored.
 *
 * Pure Kotlin on plain ints so it is fully unit-testable without a device.
 */
object IspTuningPlanner {

    /** Mode values to inject; null means "leave the HAL default alone". */
    data class IspModes(
        val noiseReduction: Int?,
        val edge: Int?,
        val hotPixel: Int?,
        val colorAberration: Int?,
        val tonemap: Int?,
        val shading: Int?,
        val opticalStabilization: Int?,
        val aeFpsRange: IntArray?
    )

    data class Advertised(
        val noiseReduction: IntArray = intArrayOf(),
        val edge: IntArray = intArrayOf(),
        val hotPixel: IntArray = intArrayOf(),
        val colorAberration: IntArray = intArrayOf(),
        val tonemap: IntArray = intArrayOf(),
        val shading: IntArray = intArrayOf(),
        val ois: IntArray = intArrayOf(),
        /** Flattened [lo0, hi0, lo1, hi1, ...] AE target fps ranges. */
        val aeFpsRanges: IntArray = intArrayOf()
    )

    /**
     * @param stillQuality true for still capture (prefer quality modes), false for
     *        preview/video (prefer low latency modes).
     * @param thermalReduced when the device is hot, fall back to cheaper ISP modes to
     *        reduce power and heat.
     * @param monochromeLook when the creative filter is monochrome, chroma NR is moot.
     */
    fun plan(
        adv: Advertised,
        stillQuality: Boolean,
        thermalReduced: Boolean,
        isVideo: Boolean
    ): IspModes {
        val wantQuality = stillQuality && !thermalReduced && !isVideo

        val nr = pick(
            adv.noiseReduction,
            if (wantQuality) listOf(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
            else listOf(CaptureRequest.NOISE_REDUCTION_MODE_FAST, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        )

        // Edge enhancement is deliberately kept light: our own halo-free sharpening runs
        // later, so a heavy ISP unsharp mask would double-sharpen and create halos.
        val edge = pick(
            adv.edge,
            listOf(CaptureRequest.EDGE_MODE_FAST, CaptureRequest.EDGE_MODE_OFF)
        )

        val hot = pick(
            adv.hotPixel,
            if (wantQuality) listOf(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY, CaptureRequest.HOT_PIXEL_MODE_FAST)
            else listOf(CaptureRequest.HOT_PIXEL_MODE_FAST)
        )

        val aberration = pick(
            adv.colorAberration,
            if (wantQuality) listOf(
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST
            ) else listOf(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST)
        )

        val tonemap = if (wantQuality) pick(adv.tonemap, listOf(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)) else null

        val shading = pick(
            adv.shading,
            if (wantQuality) listOf(CaptureRequest.SHADING_MODE_HIGH_QUALITY, CaptureRequest.SHADING_MODE_FAST)
            else listOf(CaptureRequest.SHADING_MODE_FAST)
        )

        // Only ever enable OIS if the lens genuinely has it. Video EIS and OIS are mutually
        // exclusive on many HALs, so OIS is skipped for video.
        val ois = if (!isVideo && adv.ois.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON))
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON else null

        return IspModes(nr, edge, hot, aberration, tonemap, shading, ois, pickFpsRange(adv.aeFpsRanges, isVideo))
    }

    private fun pick(available: IntArray, preference: List<Int>): Int? =
        preference.firstOrNull { available.contains(it) }

    /**
     * Prefers a fixed 30 fps range (steady exposure timing, no AE-driven fps hunting), or the
     * highest-fps range as a fallback. Fixed ranges give a steadier preview and keep
     * ISP frame pacing predictable on mid-range chips.
     */
    fun pickFpsRange(flat: IntArray, isVideo: Boolean): IntArray? {
        if (flat.size < 2) return null
        val ranges = (0 until flat.size / 2).map { intArrayOf(flat[it * 2], flat[it * 2 + 1]) }
        ranges.firstOrNull { it[0] == 30 && it[1] == 30 }?.let { return it }
        // Photo preview: allow dropping to 15 fps in dim light so AE can use longer exposures.
        if (!isVideo) ranges.firstOrNull { it[0] == 15 && it[1] == 30 }?.let { return it }
        return ranges.filter { it[1] <= 30 }.maxByOrNull { it[1] * 1000 + it[0] }
    }
}
