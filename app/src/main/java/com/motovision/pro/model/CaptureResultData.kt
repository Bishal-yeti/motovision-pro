package com.motovision.pro.model

import java.io.File

/**
 * Data structure holding the result of an image capture operation,
 * including original file, processed file, and exposure metadata.
 */
data class CaptureResultData(
    val originalFile: File,
    val processedFile: File?,
    val captureMode: CaptureMode,
    val profile: ProcessingProfile,
    val cameraModule: CameraModule,
    val iso: Int?,
    val exposureTimeNs: Long?,
    val timestamp: Long = System.currentTimeMillis(),
    val isMultiFrameFused: Boolean = false,
    val frameCountFused: Int = 1,
    val rawFile: File? = null
)
