package com.motovision.pro.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Premium interactive shutter button with outer progress ring for multi-frame processing,
 * animated press state, and haptic feedback.
 */
class ShutterButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = Color.WHITE
    }

    private val progressRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        color = Color.parseColor("#FFCC00") // Pro Gold accent
        strokeCap = Paint.Cap.ROUND
    }

    private val innerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val ringBounds = RectF()
    private var innerScale = 1.0f
    private var isPressedDown = false
    private var progressFraction = 0.0f
    private var isProcessing = false

    var onShutterClickListener: (() -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = progressRingPaint.strokeWidth + 4f
        ringBounds.set(pad, pad, w - pad, h - pad)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val outerRadius = (Math.min(width, height) / 2f) - (progressRingPaint.strokeWidth + 4f)

        // Draw outer static ring
        canvas.drawCircle(cx, cy, outerRadius, outerRingPaint)

        // Draw processing progress arc if active
        if (isProcessing || progressFraction > 0f) {
            val sweep = 360f * progressFraction
            canvas.drawArc(ringBounds, -90f, sweep, false, progressRingPaint)
        }

        // Draw inner shutter circle with scale animation
        val innerRadius = (outerRadius - 10f) * innerScale
        canvas.drawCircle(cx, cy, innerRadius, innerFillPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || isProcessing) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isPressedDown = true
                animateInnerScale(0.85f)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isPressedDown) {
                    isPressedDown = false
                    animateInnerScale(1.0f)
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onShutterClickListener?.invoke()
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressedDown = false
                animateInnerScale(1.0f)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun animateInnerScale(targetScale: Float) {
        ValueAnimator.ofFloat(innerScale, targetScale).apply {
            duration = 120
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                innerScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setProcessingState(processing: Boolean) {
        isProcessing = processing
        if (!processing) {
            progressFraction = 0f
            innerFillPaint.color = Color.WHITE
        } else {
            innerFillPaint.color = Color.parseColor("#80FFCC00")
        }
        invalidate()
    }

    fun setProgress(progress: Float) {
        progressFraction = progress.coerceIn(0f, 1f)
        invalidate()
    }

    fun setVideoRecording(recording: Boolean) {
        if (recording) {
            innerFillPaint.color = Color.parseColor("#FF4B4B") // Recording Red
            animateInnerScale(0.6f)
        } else {
            innerFillPaint.color = Color.WHITE
            animateInnerScale(1.0f)
        }
        invalidate()
    }
}
