package com.looplingo.horizon.domain.audio.service

import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import com.looplingo.horizon.domain.model.PlaybackConfig
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ABLoopMonitor @Inject constructor() {

    private val abHandler = Handler(Looper.getMainLooper())
    private var abCheckRunnable: Runnable? = null

    companion object {
        private const val AB_CHECK_FAR_MS = 10_000L
        private const val AB_CHECK_MID_MS = 3000L
        private const val AB_CHECK_NEAR_MS = 500L
        private const val AB_NEAR_THRESHOLD_MS = 3000L
        private const val AB_MID_THRESHOLD_MS = 10_000L
        private const val AB_DIALOGUE_NEAR_THRESHOLD_MS = 2000L
    }

    internal fun scheduleAbCheck(
        player: ExoPlayer,
        config: PlaybackConfig,
        isDialogueMode: Boolean = false,
        onLoopComplete: () -> Unit
    ) {
        cancelAbMonitor()

        if (!config.hasABLoop || config.rangeEndMs <= 0) return

        val currentPosition = player.currentPosition
        val endPosition = config.rangeEndMs
        val distanceFromEnd = endPosition - currentPosition
        val nearThreshold = if (isDialogueMode) AB_DIALOGUE_NEAR_THRESHOLD_MS else AB_NEAR_THRESHOLD_MS

        val nextDelay = when {
            distanceFromEnd > AB_MID_THRESHOLD_MS -> AB_CHECK_FAR_MS
            distanceFromEnd > nearThreshold -> AB_CHECK_MID_MS
            distanceFromEnd <= 0L -> 0L
            else -> AB_CHECK_NEAR_MS
        }

        if (nextDelay <= 0) {
            checkABPositionAndReschedule(player, config, isDialogueMode, onLoopComplete)
            return
        }

        abCheckRunnable = Runnable {
            checkABPositionAndReschedule(player, config, isDialogueMode, onLoopComplete)
        }
        abHandler.postDelayed(abCheckRunnable!!, nextDelay)
    }

    internal fun cancelAbMonitor() {
        abCheckRunnable?.let { abHandler.removeCallbacks(it) }
        abCheckRunnable = null
    }

    internal fun checkABPositionAndReschedule(
        player: ExoPlayer,
        config: PlaybackConfig,
        isDialogueMode: Boolean = false,
        onLoopComplete: () -> Unit
    ) {
        val currentPosition = player.currentPosition
        val endPosition = config.rangeEndMs

        if (currentPosition >= endPosition - 100) {
            player.pause()
            if (isDialogueMode && config.loopCount > 1) {
                player.seekTo(config.rangeStartMs)
                player.play()
                scheduleAbCheck(player, config, true, onLoopComplete)
            } else {
                player.seekTo(config.rangeStartMs)
                onLoopComplete()
            }
        } else {
            scheduleAbCheck(player, config, isDialogueMode, onLoopComplete)
        }
    }
}
