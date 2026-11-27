package com.motovision.pro.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Real-time exposure and color histogram view.
 * Displays luminance and RGB channel distribution for Pro Mode and exposure evaluation.
 */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint().apply {
        color = Color.parseColor("#44FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val lumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFFFFFF")
        style = Paint.Style.FILL
    }

    private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FF4444")
        style = Paint.Style.FILL
    }

    private val greenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8044FF44")
        style = Paint.Style.FILL
    }

    private val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#804444FF")
        style = Paint.Style.FILL
    }

    // 256 bins for luminance and RGB
    private var lumBins = IntArray(256)
    private var redBins: IntArray? = null
    private var greenBins: IntArray? = null
    private var blueBins: IntArray? = null
    private var maxCount = 1

    private val path = Path()

    fun updateHistogram(luminance: IntArray, red: IntArray? = null, green: IntArray? = null, blue: IntArray? = null) {
        lumBins = luminance
        redBins = red
        greenBins = green
        blueBins = blue

        var max = 1
        for (i in 0 until 256) {
            if (lumBins[i] > max) max = lumBins[i]
            redBins?.let { if (it[i] > max) max = it[i] }
            greenBins?.let { if (it[i] > max) max = it[i] }
            blueBins?.let { if (it[i] > max) max = it[i] }
        }
        maxCount = max
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Background card
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        canvas.drawRect(0f, 0f, w, h, borderPaint)

        // Draw RGB channels if present
        redBins?.let { drawChannel(canvas, it, redPaint, w, h) }
        greenBins?.let { drawChannel(canvas, it, greenPaint, w, h) }
        blueBins?.let { drawChannel(canvas, it, bluePaint, w, h) }

        // Draw Luminance channel
        drawChannel(canvas, lumBins, lumPaint, w, h)
    }

    private fun drawChannel(canvas: Canvas, bins: IntArray, paint: Paint, w: Float, h: Float) {
        path.reset()
        path.moveTo(0f, h)

        val binWidth = w / 256f
        for (i in 0 until 256) {
            val binH = (bins[i].toFloat() / maxCount) * h
            val x = i * binWidth
            val y = h - binH
            path.lineTo(x, y)
        }
        path.lineTo(w, h)
        path.close()
        canvas.drawPath(path, paint)
    }
}
