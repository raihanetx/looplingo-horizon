package com.looplingo.horizon.domain.audio.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import com.looplingo.horizon.data.repository.PlaybackRepository
import com.looplingo.horizon.data.repository.VideoRepository
import com.looplingo.horizon.domain.model.PlaybackConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackController @Inject constructor(
    private val playbackRepository: PlaybackRepository,
    private val videoRepository: VideoRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var abMonitorJob: Job? = null
    private var positionUpdateJob: Job? = null

    private var isHandlingPlaybackEnded: Boolean = false

    internal fun handlePlaybackEnded(
        player: ExoPlayer,
        config: PlaybackConfig,
        currentLoopIteration: Int,
        abLoopCompleted: Boolean,
        onLoop: (newIteration: Int) -> Unit,
        onComplete: () -> Unit
    ) {
        if (isHandlingPlaybackEnded) return
        isHandlingPlaybackEnded = true

        try {
            if (config.isNormalPlayback) {
                onComplete()
                return
            }

            if (config.hasABLoop) {
                if (abLoopCompleted) {
                    onComplete()
                } else {
                    if (currentLoopIteration < config.loopCount) {
                        onLoop(currentLoopIteration)
                    } else {
                        player.seekTo(config.rangeEndMs)
                        player.play()
                    }
                }
            } else {
                val newIteration = currentLoopIteration + 1
                if (newIteration < config.loopCount) {
                    player.seekTo(0)
                    player.play()
                    onLoop(newIteration)
                } else {
                    onComplete()
                }
            }
        } finally {
            isHandlingPlaybackEnded = false
        }
    }

    internal fun seekToA(player: ExoPlayer, config: PlaybackConfig) {
        try {
            player.seekTo(config.rangeStartMs)
            player.play()
        } catch (e: Exception) {
            Timber.e(e, "Failed to seek to A position")
        }
    }

    internal fun pauseForDialogueRepeat(
        player: ExoPlayer,
        config: PlaybackConfig,
        pauseMs: Long = 1000L,
        onResumed: () -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            try {
                player.seekTo(config.rangeStartMs)
                player.play()
                Timber.d("Dialogue repeat: seeking to A (%dms) and resuming", config.rangeStartMs)
            } catch (e: Exception) {
                Timber.e(e, "Failed to resume after dialogue pause")
            }
            onResumed()
        }, pauseMs)
    }

    internal fun advanceToNextVideo(
        context: Context,
        player: ExoPlayer,
        playlist: List<String>,
        currentIndex: Int,
        onPlayVideo: (String) -> Unit
    ) {
        if (playlist.isEmpty()) {
            AudioPlaybackService.stopService(context)
            return
        }

        if (currentIndex < playlist.size - 1) {
            val nextPath = playlist[currentIndex + 1]
            onPlayVideo(nextPath)
        } else {
            AudioPlaybackService.stopService(context)
        }
    }

    internal fun playPrevious(
        player: ExoPlayer,
        playlist: List<String>,
        currentIndex: Int,
        onPlayVideo: (String) -> Unit
    ) {
        if (playlist.isEmpty()) return
        if (currentIndex > 0) {
            val prevPath = playlist[currentIndex - 1]
            onPlayVideo(prevPath)
        } else {
            try {
                player.seekTo(0)
                player.play()
            } catch (_: Exception) {}
        }
    }

    internal fun playNext(
        context: Context,
        player: ExoPlayer,
        playlist: List<String>,
        currentIndex: Int,
        onPlayVideo: (String) -> Unit
    ) {
        advanceToNextVideo(context, player, playlist, currentIndex, onPlayVideo)
    }

    internal fun togglePlayback(player: ExoPlayer) {
        try {
            if (player.isPlaying) player.pause() else player.play()
        } catch (e: Exception) {
            Timber.e(e, "Failed to toggle playback")
        }
    }

    internal fun handleSeekForward(player: ExoPlayer, offsetMs: Long) {
        try {
            val currentPos = player.currentPosition
            val duration = player.duration
            val newPos = (currentPos + offsetMs).coerceAtMost(if (duration > 0) duration else Long.MAX_VALUE)
            player.seekTo(newPos)
            Timber.d("Seek forward %dms → %dms", offsetMs, newPos)
        } catch (e: Exception) {
            Timber.e(e, "Failed to seek forward")
        }
    }

    internal fun handleSeekBackward(player: ExoPlayer, offsetMs: Long) {
        try {
            val currentPos = player.currentPosition
            val newPos = (currentPos - offsetMs).coerceAtLeast(0L)
            player.seekTo(newPos)
            Timber.d("Seek backward %dms → %dms", offsetMs, newPos)
        } catch (e: Exception) {
            Timber.e(e, "Failed to seek backward")
        }
    }

    internal fun startPositionUpdate(
        player: ExoPlayer,
        hasABLoop: Boolean,
        abLoopCompleted: Boolean,
        onUpdate: (playing: Boolean, position: Long, duration: Long) -> Unit
    ) {
        cancelPositionUpdate()
        positionUpdateJob = scope.launch {
            while (isActive) {
                try {
                    val pos = player.currentPosition
                    val dur = player.duration
                    val playing = player.isPlaying
                    onUpdate(playing, pos, dur)
                } catch (_: Exception) {
                }
                val interval = if (hasABLoop && !abLoopCompleted) 500L else 1000L
                delay(interval)
            }
        }
    }

    internal fun cancelPositionUpdate() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }
}
