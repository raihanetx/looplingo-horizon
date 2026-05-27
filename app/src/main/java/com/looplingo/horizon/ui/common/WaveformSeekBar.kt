package com.looplingo.horizon.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.looplingo.horizon.R

class WaveformSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val trackRect = RectF()
    private val thumbRadiusPx: Float = 6f * resources.displayMetrics.density
    private val trackHeightPx: Float = 4f * resources.displayMetrics.density

    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 1000)
            invalidate()
        }

    var onSeekListener: ((progress: Int) -> Unit)? = null

    fun setWaveformData(heights: IntArray) {
        // ignored — using simple bar style
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return

        val centerY = paddingTop + h / 2f
        val trackLeft = paddingLeft.toFloat()
        val trackRight = paddingLeft + w.toFloat()

        val playedColor = resources.getColor(R.color.waveform_played, null)
        val unplayedColor = resources.getColor(R.color.waveform_unplayed, null)

        val progressFraction = progress / 1000f
        val progressX = paddingLeft + w * progressFraction

        // Draw track (unplayed portion)
        trackPaint.color = unplayedColor
        trackRect.set(progressX, centerY - trackHeightPx / 2f, trackRight, centerY + trackHeightPx / 2f)
        canvas.drawRoundRect(trackRect, trackHeightPx / 2f, trackHeightPx / 2f, trackPaint)

        // Draw progress (played portion)
        progressPaint.color = playedColor
        trackRect.set(trackLeft, centerY - trackHeightPx / 2f, progressX, centerY + trackHeightPx / 2f)
        canvas.drawRoundRect(trackRect, trackHeightPx / 2f, trackHeightPx / 2f, progressPaint)

        // Draw thumb
        thumbPaint.color = playedColor
        canvas.drawCircle(progressX, centerY, thumbRadiusPx, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val x = event.x - paddingLeft
                val w = width - paddingLeft - paddingRight
                if (w > 0) {
                    progress = ((x / w * 1000).toInt()).coerceIn(0, 1000)
                    onSeekListener?.invoke(progress)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
