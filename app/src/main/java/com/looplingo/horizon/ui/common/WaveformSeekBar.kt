package com.looplingo.horizon.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.looplingo.horizon.R

class WaveformSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.waveform_played, null)
        style = Paint.Style.FILL
    }

    private val unplayedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.waveform_unplayed, null)
        style = Paint.Style.FILL
    }

    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 1000)
            invalidate()
        }

    private var waveHeights: IntArray = DEFAULT_WAVEFORM

    private val barGapPx: Int = (2 * resources.displayMetrics.density).toInt()

    private val barRadiusPx: Float = 2f * resources.displayMetrics.density

    var onSeekListener: ((progress: Int) -> Unit)? = null

    fun setWaveformData(heights: IntArray) {
        waveHeights = heights
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return

        val bars = waveHeights.size
        if (bars == 0) return

        val totalGapWidth = (bars - 1) * barGapPx
        val barWidth = ((w - totalGapWidth) / bars).coerceAtLeast(1)

        val playedBarCount = if (progress >= 1000) bars else (progress * bars / 1000)

        for (i in 0 until bars) {
            val barHeightPercent = waveHeights[i].coerceIn(0, 100)
            val barHeight = (h * barHeightPercent / 100f).coerceAtLeast(barWidth.toFloat())
            val left = paddingLeft + i * (barWidth + barGapPx).toFloat()
            val top = paddingTop + (h - barHeight) / 2f
            val right = left + barWidth
            val bottom = top + barHeight

            val paint = if (i < playedBarCount) playedPaint else unplayedPaint
            canvas.drawRoundRect(left, top, right, bottom, barRadiusPx, barRadiusPx, paint)
        }
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

    companion object {
        val DEFAULT_WAVEFORM = intArrayOf(
            25, 45, 15, 60, 75, 40, 30, 50, 85, 60,
            45, 70, 95, 55, 30, 50, 75, 90, 65, 40,
            55, 80, 50, 35, 40, 60, 30, 45, 20, 35
        )
    }
}
