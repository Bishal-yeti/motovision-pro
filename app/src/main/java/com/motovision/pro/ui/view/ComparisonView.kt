package com.motovision.pro.ui.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Interactive split-screen comparison view displaying Original vs MotoVision Pro Enhanced images.
 * Dragging the divider horizontally reveals the comparative detail, color, and noise improvements.
 */
class ComparisonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var originalBitmap: Bitmap? = null
    private var enhancedBitmap: Bitmap? = null

    private var splitPosition = 0.5f // 0.0 to 1.0

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFCC00")
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private val srcRect = Rect()
    private val dstRect = Rect()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun setBitmaps(original: Bitmap, enhanced: Bitmap) {
        originalBitmap = original
        enhancedBitmap = enhanced
        postInvalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                splitPosition = (event.x / width).coerceIn(0.05f, 0.95f)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val orig = originalBitmap ?: return
        val enh = enhancedBitmap ?: return

        val w = width
        val h = height
        val splitX = (w * splitPosition).toInt()

        // 1. Draw Enhanced Image on the right half
        canvas.save()
        canvas.clipRect(splitX, 0, w, h)
        srcRect.set(0, 0, enh.width, enh.height)
        dstRect.set(0, 0, w, h)
        canvas.drawBitmap(enh, srcRect, dstRect, bitmapPaint)
        canvas.drawText("MotoVision Pro", splitX + 24f, 64f, textPaint)
        canvas.restore()

        // 2. Draw Original Image on the left half
        canvas.save()
        canvas.clipRect(0, 0, splitX, h)
        srcRect.set(0, 0, orig.width, orig.height)
        dstRect.set(0, 0, w, h)
        canvas.drawBitmap(orig, srcRect, dstRect, bitmapPaint)
        canvas.drawText("Original", 24f, 64f, textPaint)
        canvas.restore()

        // 3. Draw divider line and handle
        canvas.drawLine(splitX.toFloat(), 0f, splitX.toFloat(), h.toFloat(), dividerPaint)
        canvas.drawCircle(splitX.toFloat(), h / 2f, 24f, dividerPaint)
    }
}
