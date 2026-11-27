package com.motovision.pro.model

import android.hardware.camera2.params.RggbChannelVector

/**
 * Platform-independent data class for RGGB color channel gains.
 * Enables pure Kotlin unit testing without Android SDK stubs returning default 0.0f.
 */
data class ColorGains(
    val red: Float,
    val greenEven: Float = 1.0f,
    val greenOdd: Float = 1.0f,
    val blue: Float
) {
    fun toRggbChannelVector(): RggbChannelVector {
        return RggbChannelVector(red, greenEven, greenOdd, blue)
    }
}
