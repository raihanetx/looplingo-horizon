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
        alpha = 80
    }
    private val trackPlayedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = resources.getColor(R.color.colorPrimary, null)
        alpha = 160
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = resources.getColor(R.color.colorPrimary, null)
    }
    private val thumbGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = resources.getColor(R.color.colorPrimary, null)
        alpha = 50
    }
    private val rectF = RectF()

    private val barWidth: Float = 2f * resources.displayMetrics.density
    private val barGap: Float = 1.2f * resources.displayMetrics.density
    private val trackHeight: Float = 2f * resources.displayMetrics.density
    private val thumbRadius: Float = 4f * resources.displayMetrics.density
    private val thumbGlowRadius: Float = 6.5f * resources.displayMetrics.density

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
        val playedColor = resources.getColor(R.color.waveform_played, null)
        val unplayedColor = resources.getColor(R.color.waveform_unplayed, null)
        val centerY = paddingTop + h / 2f
        val barRadius = barWidth / 2f

        val totalBarsWidth = bars * barWidth + (bars - 1) * barGap
        val startX = paddingLeft + (w - totalBarsWidth) / 2f
        val playedBarCount = (progress * bars / 1000).coerceIn(0, bars)

        // Progress ground bar underneath the waveform
        val trackTop = centerY + h * 0.28f
        val trackBottom = trackTop + trackHeight
        rectF.set(startX, trackTop, startX + totalBarsWidth, trackBottom)
        canvas.drawRoundRect(rectF, trackHeight / 2f, trackHeight / 2f, trackPaint)

        // Played portion of the ground bar
        val playedWidth = totalBarsWidth * progress / 1000f
        if (playedWidth > 0) {
            rectF.set(startX, trackTop, startX + playedWidth, trackBottom)
            canvas.drawRoundRect(rectF, trackHeight / 2f, trackHeight / 2f, trackPlayedPaint)
        }

        // Waveform bars
        for (i in 0 until bars) {
            val barHeightPercent = BAR_HEIGHTS[i]
            val barHeight = (h * barHeightPercent / 100f).coerceAtLeast(barWidth)
            val left = startX + i * (barWidth + barGap)
            val top = centerY - barHeight / 2f
            val right = left + barWidth
            val bottom = top + barHeight

            if (i < playedBarCount) {
                barPaint.color = playedColor
                barPaint.alpha = 255
            } else if (i == playedBarCount) {
                barPaint.color = playedColor
                barPaint.alpha = 200
            } else {
                barPaint.color = unplayedColor
                barPaint.alpha = 120
            }

            rectF.set(left, top, right, bottom)
            canvas.drawRoundRect(rectF, barRadius, barRadius, barPaint)
        }

        // Thumb at current position
        val thumbX = startX + totalBarsWidth * progress / 1000f
        canvas.drawCircle(thumbX, centerY, thumbGlowRadius, thumbGlowPaint)
        canvas.drawCircle(thumbX, centerY, thumbRadius, thumbPaint)
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
