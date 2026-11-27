package com.motovision.pro.model

/**
 * Represents the distinct physical and logical camera modules on the target device
 * (Motorola Moto G23 / MediaTek Helio G85 platform).
 */
enum class CameraModule(
    val displayName: String,
    val zoomFactor: Float,
    val isFrontFacing: Boolean,
    val description: String
) {
    MAIN_REAR(
        displayName = "1x Main",
        zoomFactor = 1.0f,
        isFrontFacing = false,
        description = "50 MP Samsung ISOCELL S5KJN1 (Tetracell 4-in-1, 12.5 MP binned, f/1.8, PDAF)"
    ),
    ULTRAWIDE(
        displayName = "0.6x Wide",
        zoomFactor = 0.6f,
        isFrontFacing = false,
        description = "5 MP Ultrawide (~118° FoV, f/2.2, fixed-focus)"
    ),
    MACRO(
        displayName = "Macro",
        zoomFactor = 1.0f,
        isFrontFacing = false,
        description = "2 MP Macro (f/2.4, fixed-focus at 4 cm)"
    ),
    FRONT(
        displayName = "Selfie",
        zoomFactor = 1.0f,
        isFrontFacing = true,
        description = "16 MP Front Camera (f/2.45, fixed-focus)"
    );

    fun isSecondaryRear(): Boolean = this == ULTRAWIDE || this == MACRO
}
