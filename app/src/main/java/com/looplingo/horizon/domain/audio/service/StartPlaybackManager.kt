package com.looplingo.horizon.domain.audio.service

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.looplingo.horizon.data.repository.PlaybackRepository
import com.looplingo.horizon.data.repository.VideoRepository
import com.looplingo.horizon.domain.model.PlaybackConfig
import com.looplingo.horizon.domain.model.PlaybackConfigValidator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StartPlaybackManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playbackRepository: PlaybackRepository,
    private val videoRepository: VideoRepository,
    private val playbackController: PlaybackController,
    private val notificationHelper: PlaybackNotificationHelper
) {
    var retryAttemptCount: Int = 0

    companion object {
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2000L
    }

    suspend fun loadVideoPaths(): Pair<List<String>, Map<String, String>> {
        return try {
            val videos = videoRepository.getVideos().first()
            videos.map { it.path } to videos.associate { it.path to it.title }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load video paths")
            emptyList<String>() to emptyMap()
        }
    }

    suspend fun resolvePlaybackUri(videoPath: String): String {
        return try {
            videoRepository.getContentUriForPath(videoPath) ?: videoPath
        } catch (_: Exception) { videoPath }
    }

    suspend fun waitForPlayerReady(getExoPlayer: () -> ExoPlayer?, timeoutMs: Long): Boolean {
        val player = getExoPlayer() ?: return false
        if (player.playbackState == Player.STATE_READY) return true
        return try {
            withContext(Dispatchers.Main) {
                withTimeoutOrNull(timeoutMs) {
                    suspendCancellableCoroutine { cont ->
                        val listener = object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_READY) {
                                    player.removeListener(this)
                                    cont.resume(true) {}
                                }
                            }
                        }
                        player.addListener(listener)
                        cont.invokeOnCancellation { player.removeListener(listener) }
                    }
                } ?: false
            }
        } catch (e: Exception) {
            Timber.w(e, "Error waiting for player ready")
            false
        }
    }

    suspend fun startPlayback(
        videoPath: String,
        getExoPlayer: () -> ExoPlayer?,
        getConfig: () -> PlaybackConfig,
        setConfig: (PlaybackConfig) -> Unit,
        getVideoPaths: () -> List<String>,
        setCurrentVideoIndex: (Int) -> Unit,
        setCurrentLoopIteration: (Int) -> Unit,
        setABLoopCompleted: (Boolean) -> Unit,
        updateState: (Boolean, Long, String, Long) -> Unit,
        scheduleAbCheck: () -> Unit,
        cancelAbMonitor: () -> Unit,
        updateNotification: () -> Unit
    ) {
        Timber.i("Starting playback for: %s", videoPath)
        updateState(false, 0L, videoPath, 0L)
        setCurrentLoopIteration(0)
        setABLoopCompleted(false)
        retryAttemptCount = 0
        cancelAbMonitor()
        var videoIndex = getVideoPaths().indexOf(videoPath)
        if (videoIndex < 0) {
            videoIndex = 0
        }
        setCurrentVideoIndex(videoIndex)

        val savedConfig = try { playbackRepository.getConfigForVideo(videoPath) } catch (_: Exception) { null }
        val config = PlaybackConfigValidator.sanitize(savedConfig ?: PlaybackConfig(videoPath = videoPath))
        setConfig(config)

        val playbackUri = resolvePlaybackUri(videoPath)

        try {
            val mediaSource = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
                .createMediaSource(MediaItem.fromUri(playbackUri))

            getExoPlayer()?.apply {
                setMediaSource(mediaSource)
                prepare()
                repeatMode = Player.REPEAT_MODE_OFF
                playbackParameters = androidx.media3.common.PlaybackParameters(config.speed)
                if (config.hasABLoop && config.rangeStartMs > 0) {
                    seekTo(config.rangeStartMs)
                }
                play()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to set up ExoPlayer")
            notificationHelper.updateNotificationWithError("Cannot play this file: ${e.message}")
            return
        }

        if (config.hasABLoop) {
            scheduleAbCheck()
        }

        getExoPlayer()?.let { player ->
            playbackController.startPositionUpdate(player, config.hasABLoop, false) { playing, position, duration ->
                updateState(playing, position, videoPath, duration)
            }
        }

        updateNotification()
    }

    fun handlePlayerError(
        getExoPlayer: () -> ExoPlayer?,
        serviceScope: CoroutineScope,
        notificationHelper: PlaybackNotificationHelper
    ) {
        retryAttemptCount++
        if (retryAttemptCount <= MAX_RETRY_ATTEMPTS) {
            val backoffDelay = RETRY_DELAY_MS * (1L shl (retryAttemptCount - 1))
            serviceScope.launch {
                delay(backoffDelay)
                if (isActive) {
                    try {
                        getExoPlayer()?.prepare()
                        getExoPlayer()?.play()
                    } catch (e: Exception) { Timber.e(e, "Retry failed") }
                }
            }
        } else {
            notificationHelper.updateNotificationWithError("Playback failed after $MAX_RETRY_ATTEMPTS attempts")
        }
    }

    fun handlePlaybackEnded(
        getExoPlayer: () -> ExoPlayer?,
        getConfig: () -> PlaybackConfig,
        getCurrentLoopIteration: () -> Int,
        setCurrentLoopIteration: (Int) -> Unit,
        getABLoopCompleted: () -> Boolean,
        getVideoPaths: () -> List<String>,
        getCurrentVideoIndex: () -> Int,
        cancelAbMonitor: () -> Unit,
        scheduleAbCheck: () -> Unit,
        setABSeeking: (Boolean) -> Unit,
        updateNotification: () -> Unit,
        serviceScope: CoroutineScope,
        startPlayback: suspend (String) -> Unit,
        stopSelf: () -> Unit
    ) {
        val player = getExoPlayer() ?: return
        val config = getConfig()
        playbackController.handlePlaybackEnded(
            player, config, getCurrentLoopIteration(), getABLoopCompleted(),
            onLoop = { newIteration ->
                setCurrentLoopIteration(newIteration)
                cancelAbMonitor()
                setABSeeking(true)
                playbackController.seekToA(player, config)
                scheduleAbCheck()
                updateNotification()
            },
            onComplete = {
                playbackController.advanceToNextVideo(context, player, getVideoPaths(), getCurrentVideoIndex()) { path ->
                    serviceScope.launch {
                        try { startPlayback(path) }
                        catch (e: Exception) { Timber.e(e, "Failed to advance"); stopSelf() }
                    }
                }
            }
        )
    }
}
