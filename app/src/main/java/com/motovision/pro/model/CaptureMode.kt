package com.motovision.pro.model

/**
 * Capture modes supported by MotoVision Pro.
 */
enum class CaptureMode(
    val title: String,
    val isVideo: Boolean,
    val supportsProControls: Boolean,
    val requiresMultiFrame: Boolean
) {
    PHOTO(
        title = "PHOTO",
        isVideo = false,
        supportsProControls = false,
        requiresMultiFrame = false
    ),
    NIGHT(
        title = "NIGHT",
        isVideo = false,
        supportsProControls = false,
        requiresMultiFrame = true
    ),
    HDR(
        title = "HDR",
        isVideo = false,
        supportsProControls = false,
        requiresMultiFrame = true
    ),
    PORTRAIT(
        title = "PORTRAIT",
        isVideo = false,
        supportsProControls = false,
        requiresMultiFrame = false
    ),
    PRO(
        title = "PRO",
        isVideo = false,
        supportsProControls = true,
        requiresMultiFrame = false
    ),
    VIDEO(
        title = "VIDEO",
        isVideo = true,
        supportsProControls = true,
        requiresMultiFrame = false
    );
}
