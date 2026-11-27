package com.motovision.pro.ui.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

/**
 * Transparent overlay view that displays edge-detected focus peaking highlights
 * for precision manual focusing and close-range macro photography.
 */
class FocusPeakingOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var peakingBitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect()

    var isPeakingEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) {
                peakingBitmap = null
            }
            postInvalidate()
        }

    fun updatePeakingBitmap(bitmap: Bitmap?) {
        if (!isPeakingEnabled) {
            bitmap?.recycle()
            return
        }
        val oldBmp = peakingBitmap
        peakingBitmap = bitmap
        if (oldBmp != null && oldBmp != bitmap && !oldBmp.isRecycled) {
            oldBmp.recycle()
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isPeakingEnabled) return
        val bmp = peakingBitmap ?: return

        srcRect.set(0, 0, bmp.width, bmp.height)
        dstRect.set(0, 0, width, height)
        canvas.drawBitmap(bmp, srcRect, dstRect, paint)
    }
}
