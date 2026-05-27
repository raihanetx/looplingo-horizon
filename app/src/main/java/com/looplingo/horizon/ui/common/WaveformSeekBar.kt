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

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = resources.getColor(R.color.waveform_unplayed, null)
        alpha = 60
    }
    private val progressTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = resources.getColor(R.color.waveform_played, null)
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = resources.getColor(R.color.colorPrimary, null)
    }
    private val thumbGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = resources.getColor(R.color.colorPrimary, null)
        alpha = 40
    }
    private val rectF = RectF()

    private val thinBarWidth: Float = 3f * resources.displayMetrics.density
    private val barGap: Float = 1.5f * resources.displayMetrics.density
    private val trackHeight: Float = 2f * resources.displayMetrics.density
    private val thumbRadius: Float = 5f * resources.displayMetrics.density
    private val thumbGlowRadius: Float = 8f * resources.displayMetrics.density

    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 1000)
            invalidate()
        }

    var onSeekListener: ((progress: Int) -> Unit)? = null

    fun setWaveformData(heights: IntArray) {
        // ignored — using default waveform pattern
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return

        val bars = BAR_HEIGHTS.size
        val barWidth = thinBarWidth
        val gap = barGap

        val playedColor = resources.getColor(R.color.waveform_played, null)
        val unplayedColor = resources.getColor(R.color.waveform_unplayed, null)
        val centerY = paddingTop + h * 0.42f
        val barRadius = barWidth / 2f

        val totalBarsWidth = bars * barWidth + (bars - 1) * gap
        val startX = paddingLeft + (w - totalBarsWidth) / 2f

        val playedBarCount = (progress * bars / 1000).coerceIn(0, bars)

        for (i in 0 until bars) {
            val barHeightPercent = BAR_HEIGHTS[i]
            val barHeight = (h * 0.7f * barHeightPercent / 100f).coerceAtLeast(barWidth)
            val left = startX + i * (barWidth + gap)
            val top = centerY - barHeight / 2f
            val right = left + barWidth
            val bottom = top + barHeight

            if (i < playedBarCount) {
                barPaint.color = playedColor
                barPaint.alpha = 255
            } else if (i == playedBarCount) {
                barPaint.color = playedColor
                barPaint.alpha = 180
            } else {
                barPaint.color = unplayedColor
                barPaint.alpha = 130
            }

            rectF.set(left, top, right, bottom)
            canvas.drawRoundRect(rectF, barRadius, barRadius, barPaint)
        }

        // Progress track line below bars
        val trackY = paddingTop + h * 0.82f
        val trackLeft = startX
        val trackRight = startX + totalBarsWidth
        val trackRectTop = trackY - trackHeight / 2f
        val trackRectBottom = trackY + trackHeight / 2f

        // Unplayed track
        rectF.set(trackLeft, trackRectTop, trackRight, trackRectBottom)
        canvas.drawRoundRect(rectF, trackHeight / 2f, trackHeight / 2f, trackPaint)

        // Played track
        val progressX = trackLeft + (trackRight - trackLeft) * progress / 1000f
        rectF.set(trackLeft, trackRectTop, progressX, trackRectBottom)
        canvas.drawRoundRect(rectF, trackHeight / 2f, trackHeight / 2f, progressTrackPaint)

        // Thumb glow
        canvas.drawCircle(progressX, trackY, thumbGlowRadius, thumbGlowPaint)

        // Thumb handle
        canvas.drawCircle(progressX, trackY, thumbRadius, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val x = event.x - paddingLeft
                val viewW = width - paddingLeft - paddingRight
                if (viewW > 0) {
                    progress = ((x / viewW * 1000).toInt()).coerceIn(0, 1000)
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

    companion object {
        val BAR_HEIGHTS = intArrayOf(
            10, 15, 20, 30, 45, 55, 35, 20, 15, 25,
            40, 60, 80, 95, 75, 50, 30, 45, 70, 85,
            95, 70, 45, 60, 80, 65, 40, 50, 70, 85,
            60, 35, 20, 15, 25, 40, 60, 50, 30, 20,
            10, 15, 25, 15, 10
        )
    }
}
