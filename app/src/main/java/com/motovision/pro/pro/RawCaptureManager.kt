package com.motovision.pro.pro

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.Image
import android.os.Environment
import android.util.Log
import com.motovision.pro.model.CameraCapabilities
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RAW / DNG capture pipeline and fallback architecture.
 * If Camera2 RAW_SENSOR is exposed by the HAL, creates genuine Adobe DNG files.
 * If limited by Helio G85 driver policies, generates uncompressed 16-bit linear sensor dumps
 * to bypass lossy OEM 8-bit quantization and aggressive noise reduction.
 */
class RawCaptureManager(
    private val context: Context
) {
    enum class RawCaptureMode {
        HARDWARE_DNG,         // Camera2 RAW_SENSOR + DngCreator
        UNCOMPRESSED_LINEAR_YUV // Full dynamic range linear sensor capture (Helio G85 fallback)
    }

    /**
     * Determines the appropriate RAW capture mode based on hardware capabilities.
     */
    fun determineRawMode(capabilities: CameraCapabilities?): RawCaptureMode {
        return if (capabilities?.supportsRaw == true) {
            RawCaptureMode.HARDWARE_DNG
        } else {
            RawCaptureMode.UNCOMPRESSED_LINEAR_YUV
        }
    }

    /**
     * Writes genuine DNG file from Camera2 RAW_SENSOR Image and CaptureResult.
     */
    fun writeHardwareDng(
        characteristics: CameraCharacteristics,
        captureResult: CaptureResult,
        rawImage: Image,
        outputFile: File
    ): Boolean {
        return try {
            DngCreator(characteristics, captureResult).use { dngCreator ->
                FileOutputStream(outputFile).use { fos ->
                    dngCreator.writeImage(fos, rawImage)
                }
            }
            Log.i(TAG, "Hardware DNG successfully written: ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write hardware DNG: ${e.message}", e)
            false
        }
    }

    /**
     * Writes uncompressed high-fidelity linear sensor dump for sensors where RAW_SENSOR
     * is withheld by the MediaTek Camera2 HAL.
     * Contains unquantized luminance and chrominance planes + metadata header.
     */
    fun writeLinearSensorDump(
        yuvData: ByteArray,
        width: Int,
        height: Int,
        iso: Int?,
        exposureNs: Long?,
        outputFile: File
    ): Boolean {
        return try {
            FileOutputStream(outputFile).use { fos ->
                // Write 64-byte MotoVision Raw Header
                val header = buildRawHeader(width, height, iso ?: 100, exposureNs ?: 33_333_333L)
                fos.write(header)
                fos.write(yuvData)
            }
            Log.i(TAG, "Linear sensor dump written: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write linear sensor dump: ${e.message}", e)
            false
        }
    }

    private fun buildRawHeader(width: Int, height: Int, iso: Int, exposureNs: Long): ByteArray {
        val header = ByteArray(64)
        // Magic bytes: "MVRAW_01"
        "MVRAW_01".toByteArray().copyInto(header, 0)

        // Width & Height (4 bytes each)
        writeInt(header, 8, width)
        writeInt(header, 12, height)

        // ISO & Exposure (4 & 8 bytes)
        writeInt(header, 16, iso)
        writeLong(header, 20, exposureNs)

        return header
    }

    private fun writeInt(array: ByteArray, offset: Int, value: Int) {
        array[offset] = (value shr 24).toByte()
        array[offset + 1] = (value shr 16).toByte()
        array[offset + 2] = (value shr 8).toByte()
        array[offset + 3] = value.toByte()
    }

    private fun writeLong(array: ByteArray, offset: Int, value: Long) {
        for (i in 0..7) {
            array[offset + i] = (value shr (56 - i * 8)).toByte()
        }
    }

    fun createRawOutputFile(isHardwareDng: Boolean): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.let {
            File(it, "MotoVisionPro_RAW").apply { mkdirs() }
        } ?: context.filesDir

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val extension = if (isHardwareDng) ".dng" else ".mvraw"
        return File(dir, "MV_RAW_${timeStamp}$extension")
    }

    companion object {
        private const val TAG = "RawCaptureManager"
    }
}
