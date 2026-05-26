package com.looplingo.horizon.domain.audio.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.LifecycleService
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.looplingo.horizon.domain.model.PlaybackConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntentHandler @Inject constructor(
    private val playbackController: PlaybackController,
    private val notificationHelper: PlaybackNotificationHelper
) {
    fun onStartCommand(
        intent: Intent?,
        service: LifecycleService,
        getExoPlayer: () -> ExoPlayer?,
        getConfig: () -> PlaybackConfig,
        setConfig: (PlaybackConfig) -> Unit,
        getVideoPaths: () -> List<String>,
        getCurrentVideoIndex: () -> Int,
        setCurrentLoopIteration: (Int) -> Unit,
        setABLoopCompleted: (Boolean) -> Unit,
        loadVideoPaths: suspend () -> Unit,
        startPlayback: suspend (String) -> Unit,
        waitForPlayerReady: suspend (Long) -> Boolean,
        scheduleAbCheck: () -> Unit,
        cancelAbMonitor: () -> Unit,
        updateNotification: () -> Unit,
        serviceScope: CoroutineScope,
        stopSelf: () -> Unit
    ): Int {
        if (intent?.action == AudioPlaybackService.ACTION_START) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    service.startForeground(
                        AudioPlaybackService.NOTIFICATION_ID,
                        notificationHelper.buildLoadingNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    service.startForeground(AudioPlaybackService.NOTIFICATION_ID, notificationHelper.buildLoadingNotification())
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to call startForeground()")
            }
        }

        if (intent == null) return Service.START_NOT_STICKY

        when (intent.action) {
            AudioPlaybackService.ACTION_START -> {
                val videoPath = intent.getStringExtra(AudioPlaybackService.EXTRA_VIDEO_PATH)
                if (videoPath.isNullOrBlank()) return Service.START_NOT_STICKY
                serviceScope.launch {
                    try {
                        loadVideoPaths()
                        startPlayback(videoPath)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to start playback")
                        notificationHelper.updateNotificationWithError("Failed to start playback: ${e.message}")
                    }
                }
            }
            AudioPlaybackService.ACTION_SEEK_TO -> {
                val videoPath = intent.getStringExtra(AudioPlaybackService.EXTRA_VIDEO_PATH)
                val seekMs = intent.getLongExtra(AudioPlaybackService.EXTRA_SEEK_POSITION_MS, 0)
                if (!videoPath.isNullOrBlank() && seekMs >= 0) {
                    handleSeekRequest(videoPath, seekMs, getExoPlayer, getConfig, loadVideoPaths, startPlayback, waitForPlayerReady, serviceScope)
                }
            }
            AudioPlaybackService.ACTION_SET_SPEED -> {
                handleSpeedChange(
                    intent.getFloatExtra(AudioPlaybackService.EXTRA_SPEED, 1.0f),
                    getExoPlayer, getConfig, setConfig, updateNotification
                )
            }
            AudioPlaybackService.ACTION_SET_AB_LOOP -> {
                val videoPath = intent.getStringExtra(AudioPlaybackService.EXTRA_VIDEO_PATH)
                val rangeStartMs = intent.getLongExtra(AudioPlaybackService.EXTRA_RANGE_START_MS, 0L)
                val rangeEndMs = intent.getLongExtra(AudioPlaybackService.EXTRA_RANGE_END_MS, -1L)
                val loopCount = intent.getIntExtra(AudioPlaybackService.EXTRA_LOOP_COUNT, 1)
                if (!videoPath.isNullOrBlank()) {
                    handleABLoopChange(
                        videoPath, rangeStartMs, rangeEndMs, loopCount,
                        getExoPlayer, getConfig, setConfig,
                        setCurrentLoopIteration, setABLoopCompleted,
                        scheduleAbCheck, cancelAbMonitor, updateNotification
                    )
                }
            }
            AudioPlaybackService.ACTION_SEEK_FORWARD -> {
                getExoPlayer()?.let { playbackController.handleSeekForward(it, intent.getLongExtra(AudioPlaybackService.EXTRA_SEEK_OFFSET_MS, 5000L)) }
            }
            AudioPlaybackService.ACTION_SEEK_BACKWARD -> {
                getExoPlayer()?.let { playbackController.handleSeekBackward(it, intent.getLongExtra(AudioPlaybackService.EXTRA_SEEK_OFFSET_MS, 5000L)) }
            }
            AudioPlaybackService.ACTION_TOGGLE_PLAYBACK -> {
                getExoPlayer()?.let { playbackController.togglePlayback(it) }
                updateNotification()
            }
            AudioPlaybackService.ACTION_STOP -> stopSelf()
        }
        return Service.START_NOT_STICKY
    }

    private fun handleSpeedChange(
        speed: Float,
        getExoPlayer: () -> ExoPlayer?,
        getConfig: () -> PlaybackConfig,
        setConfig: (PlaybackConfig) -> Unit,
        updateNotification: () -> Unit
    ) {
        try {
            val clampedSpeed = speed.coerceIn(0.25f, 2.0f)
            getExoPlayer()?.playbackParameters = PlaybackParameters(clampedSpeed)
            setConfig(getConfig().copy(speed = clampedSpeed))
            Timber.d("Speed changed to %.2fx during playback", clampedSpeed)
            updateNotification()
        } catch (e: Exception) {
            Timber.e(e, "Failed to change speed")
        }
    }

    private fun handleABLoopChange(
        videoPath: String, rangeStartMs: Long, rangeEndMs: Long, loopCount: Int,
        getExoPlayer: () -> ExoPlayer?,
        getConfig: () -> PlaybackConfig,
        setConfig: (PlaybackConfig) -> Unit,
        setCurrentLoopIteration: (Int) -> Unit,
        setABLoopCompleted: (Boolean) -> Unit,
        scheduleAbCheck: () -> Unit,
        cancelAbMonitor: () -> Unit,
        updateNotification: () -> Unit
    ) {
        if (videoPath != getConfig().videoPath) return
        try {
            val newConfig = getConfig().copy(rangeStartMs = rangeStartMs, rangeEndMs = rangeEndMs, loopCount = loopCount)
            setConfig(newConfig)
            setCurrentLoopIteration(0)
            setABLoopCompleted(false)
            if (newConfig.hasABLoop) {
                val currentPos = getExoPlayer()?.currentPosition ?: 0L
                if (currentPos < newConfig.rangeStartMs || currentPos >= newConfig.rangeEndMs) {
                    getExoPlayer()?.seekTo(newConfig.rangeStartMs)
                }
                scheduleAbCheck()
            } else {
                cancelAbMonitor()
            }
            Timber.d("A-B loop changed during playback: A=%dms, B=%dms, loop=%d", rangeStartMs, rangeEndMs, loopCount)
            updateNotification()
        } catch (e: Exception) {
            Timber.e(e, "Failed to change A-B loop")
        }
    }

    private fun handleSeekRequest(
        videoPath: String, positionMs: Long,
        getExoPlayer: () -> ExoPlayer?,
        getConfig: () -> PlaybackConfig,
        loadVideoPaths: suspend () -> Unit,
        startPlayback: suspend (String) -> Unit,
        waitForPlayerReady: suspend (Long) -> Boolean,
        serviceScope: CoroutineScope
    ) {
        if (videoPath == getConfig().videoPath && getExoPlayer() != null) {
            try {
                getExoPlayer()?.seekTo(positionMs)
                getExoPlayer()?.play()
                Timber.d("Seek to %dms for current video", positionMs)
            } catch (e: Exception) {
                Timber.e(e, "Failed to seek")
            }
        } else {
            serviceScope.launch {
                try {
                    loadVideoPaths()
                    startPlayback(videoPath)
                    val ready = waitForPlayerReady(5000L)
                    getExoPlayer()?.seekTo(positionMs)
                    if (!ready) Timber.w("Player didn't become ready within 5s — seeking anyway")
                    else Timber.d("Started new video and seeked to %dms", positionMs)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to start and seek")
                }
            }
        }
    }
}
