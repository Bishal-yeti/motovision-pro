package com.motovision.pro.pro

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.util.Range
import android.util.Rational
import com.motovision.pro.model.CameraCapabilities

/**
 * Manages manual exposure, focus, white balance, and RAW capture parameters for Professional Mode.
 * Translates standard photographic units (Kelvin, EV, Shutter fractions) into Camera2 HAL instructions.
 */
class ProModeController(
    private val capabilities: CameraCapabilities?
) {
    enum class WhiteBalancePreset(val displayName: String, val awbMode: Int, val kelvin: Int) {
        AUTO("Auto WB", CaptureRequest.CONTROL_AWB_MODE_AUTO, 5500),
        DAYLIGHT("Daylight (5500K)", CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, 5500),
        CLOUDY("Cloudy (6500K)", CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, 6500),
        SHADE("Shade (7500K)", CaptureRequest.CONTROL_AWB_MODE_SHADE, 7500),
        TUNGSTEN("Tungsten (3200K)", CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT, 3200),
        FLUORESCENT("Fluorescent (4000K)", CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT, 4000),
        CUSTOM_KELVIN("Custom Kelvin", CaptureRequest.CONTROL_AWB_MODE_OFF, 5500)
    }

    var manualIso: Int? = null
    var manualExposureTimeNs: Long? = null
    var manualFocusDistanceDiopters: Float? = null
    var exposureCompensationEv: Float = 0.0f
    var activeWbPreset: WhiteBalancePreset = WhiteBalancePreset.AUTO
    var customKelvin: Int = 5500
    var isFocusPeakingEnabled: Boolean = false
    var isRawCaptureEnabled: Boolean = false

    // Shutter speed options commonly used in mobile photography
    val standardShutterDenominators = listOf(
        10000, 8000, 4000, 2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4, 2, 1
    )

    /**
     * Converts shutter speed fraction to nanoseconds for SENSOR_EXPOSURE_TIME.
     */
    fun denominatorToExposureNs(denominator: Int): Long {
        return (1_000_000_000L / denominator.coerceAtLeast(1))
    }

    /**
     * Formats exposure time in nanoseconds to photographic fraction string.
     */
    fun exposureNsToDisplayString(exposureNs: Long): String {
        if (exposureNs >= 1_000_000_000L) {
            val seconds = exposureNs / 1_000_000_000.0
            return String.format("%.1fs", seconds)
        }
        val denominator = (1_000_000_000L / exposureNs.coerceAtLeast(1L)).toInt()
        return "1/${denominator}s"
    }

    /**
     * Clamps target ISO to supported hardware range.
     */
    fun clampIso(targetIso: Int): Int {
        val lower = try { capabilities?.isoRange?.lower ?: 100 } catch (e: Exception) { 100 } ?: 100
        val upper = try { capabilities?.isoRange?.upper ?: 3200 } catch (e: Exception) { 3200 } ?: 3200
        return targetIso.coerceIn(lower, upper)
    }

    /**
     * Converts EV offset (-2.0 to +2.0) into Camera2 exposure compensation index.
     */
    fun calculateExposureCompensationIndex(ev: Float): Int {
        val stepFloat = capabilities?.exposureCompensationStep?.takeIf { it > 0f } ?: (1f / 3f)
        val index = Math.round(ev / stepFloat)

        val lower = try { capabilities?.exposureCompensationRange?.lower ?: -6 } catch (e: Exception) { -6 } ?: -6
        val upper = try { capabilities?.exposureCompensationRange?.upper ?: 6 } catch (e: Exception) { 6 } ?: 6
        return index.coerceIn(lower, upper)
    }

    /**
     * Converts distance in centimeters to lens focus diopters ($1 / d$).
     * For example, 10 cm = 0.1 m -> 10.0 diopters. Infinity = 0.0 diopters.
     */
    fun distanceCmToDiopters(distanceCm: Float): Float {
        if (distanceCm <= 0f) return 0.0f
        val meters = distanceCm / 100f
        val diopters = 1.0f / meters
        val maxDiopters = capabilities?.minFocusDistanceDiopters?.takeIf { it > 0f } ?: 10.0f
        return diopters.coerceIn(0.0f, maxDiopters)
    }

    /**
     * Computes ColorGains for a manual color temperature in Kelvin (2000K to 10000K).
     * Uses the Tanner-Helland blackbody approximation model.
     */
    fun kelvinToColorGains(kelvin: Int): com.motovision.pro.model.ColorGains {
        val temp = (kelvin.coerceIn(2000, 10000)) / 100.0

        // Red channel estimation
        val red = if (temp <= 66.0) {
            255.0
        } else {
            val r = temp - 60.0
            329.698727446 * Math.pow(r, -0.1332047592)
        }.coerceIn(0.0, 255.0)

        // Green channel estimation
        val green = if (temp <= 66.0) {
            99.4708025861 * Math.log(temp) - 161.1195681661
        } else {
            val g = temp - 60.0
            288.1221695283 * Math.pow(g, -0.0755148492)
        }.coerceIn(0.0, 255.0)

        // Blue channel estimation
        val blue = if (temp >= 66.0) {
            255.0
        } else if (temp <= 19.0) {
            0.0
        } else {
            val b = temp - 10.0
            138.5177312231 * Math.log(b) - 305.0447927307
        }.coerceIn(0.0, 255.0)

        // Convert to relative channel gains (normalized relative to Green)
        val normG = (green / 255.0).coerceAtLeast(0.01)
        val rGain = ((red / 255.0) / normG).toFloat().coerceIn(1.0f, 4.0f)
        val bGain = ((blue / 255.0) / normG).toFloat().coerceIn(1.0f, 4.0f)

        return com.motovision.pro.model.ColorGains(rGain, 1.0f, 1.0f, bGain)
    }

    fun kelvinToRggbGains(kelvin: Int): RggbChannelVector {
        return kelvinToColorGains(kelvin).toRggbChannelVector()
    }

    /**
     * Determines if the active camera module supports true RAW_SENSOR capture.
     */
    fun isHardwareRawSupported(): Boolean {
        return capabilities?.supportsRaw == true
    }
}
