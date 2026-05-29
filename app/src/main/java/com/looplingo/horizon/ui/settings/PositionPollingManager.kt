package com.looplingo.horizon.ui.settings

import android.os.Handler
import android.os.Looper
import com.looplingo.horizon.data.remote.Segment
import com.looplingo.horizon.databinding.FragmentPlaybackSettingsBinding
import com.looplingo.horizon.domain.audio.service.AudioPlaybackService
import timber.log.Timber

class PositionPollingManager(
    private val binding: FragmentPlaybackSettingsBinding,
    private val playbackUIHelper: PlaybackUIHelper,
    private val videoPath: String,
    private val getDialogueSegments: () -> List<Segment>,
    private val isCleanCycling: () -> Boolean,
    private val isAudioOnly: () -> Boolean,
    private val showDialogueOnClean: (Int) -> Unit,
    private val resetCleanView: () -> Unit,
    private val onActiveSegmentChanged: (Int) -> Unit = {}
) {
    private val positionHandler = Handler(Looper.getMainLooper())
    private var positionPollingRunnable: Runnable? = null
    private val POSITION_POLL_INTERVAL_MS = 500L
    private var lastActiveSegmentIndex = -1

    fun startPositionPolling() {
        stopPositionPolling()
        positionPollingRunnable = object : Runnable {
            override fun run() {
                try {
                    val isCurrentlyPlaying = AudioPlaybackService.isPlaying &&
                        AudioPlaybackService.currentVideoPath == videoPath

                    val title = if (AudioPlaybackService.currentVideoPath.isNotBlank()) {
                        AudioPlaybackService.currentVideoPath.substringAfterLast("/").substringBeforeLast(".")
                    } else {
                        videoPath.substringAfterLast("/").substringBeforeLast(".")
                    }

                    val position = AudioPlaybackService.currentPositionMs
                    val duration = AudioPlaybackService.durationMs
                    val progress = if (duration > 0) ((position * 1000) / duration).toInt().coerceIn(0, 1000) else 0

                    playbackUIHelper.updateNowPlayingState(
                        binding = binding,
                        isPlaying = isCurrentlyPlaying,
                        title = title,
                        currentPositionMs = position,
                        durationMs = duration,
                        waveformProgress = progress
                    )

                    val dialogueSegments = getDialogueSegments()
                    if (dialogueSegments.isNotEmpty() && !isCleanCycling() && !isAudioOnly()) {
                        val currentSegment = dialogueSegments.find { position >= it.startMs && position < it.endMs }
                        val segIndex = if (currentSegment != null) dialogueSegments.indexOf(currentSegment) else -1
                        if (segIndex != lastActiveSegmentIndex) {
                            lastActiveSegmentIndex = segIndex
                            if (segIndex >= 0) {
                                showDialogueOnClean(segIndex)
                            }
                            onActiveSegmentChanged(segIndex)
                        }
                    } else if (dialogueSegments.isEmpty()) {
                        if (lastActiveSegmentIndex != -1) {
                            lastActiveSegmentIndex = -1
                            onActiveSegmentChanged(-1)
                        }
                        resetCleanView()
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Position polling error")
                }
                positionHandler.postDelayed(this, POSITION_POLL_INTERVAL_MS)
            }
        }
        positionHandler.post(positionPollingRunnable!!)
    }

    fun stopPositionPolling() {
        positionPollingRunnable?.let { positionHandler.removeCallbacks(it) }
        positionPollingRunnable = null
    }
}
