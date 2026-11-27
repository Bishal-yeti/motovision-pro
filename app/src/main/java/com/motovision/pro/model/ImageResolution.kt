package com.motovision.pro.model

import android.util.Size

/**
 * Platform-independent representation of image and sensor resolution.
 * Allows safe mathematical calculations, aspect ratio evaluation, and unit testing on JVM.
 */
data class ImageResolution(
    val width: Int,
    val height: Int
) : Comparable<ImageResolution> {

    val pixelCount: Long
        get() = width.toLong() * height.toLong()

    val megapixelString: String
        get() = String.format("%.1f MP", pixelCount / 1_000_000.0)

    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height.toFloat() else 1.0f

    val isFourThree: Boolean
        get() = Math.abs(aspectRatio - (4.0f / 3.0f)) < 0.05f

    val isSixteenNine: Boolean
        get() = Math.abs(aspectRatio - (16.0f / 9.0f)) < 0.05f

    fun toSize(): Size = Size(width, height)

    override fun compareTo(other: ImageResolution): Int {
        return pixelCount.compareTo(other.pixelCount)
    }

    override fun toString(): String = "${width}x${height} ($megapixelString)"

    companion object {
        fun fromSize(size: Size): ImageResolution = ImageResolution(size.width, size.height)
    }
}
