package com.looplingo.horizon.domain.audio.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LifecycleService
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import com.looplingo.horizon.domain.model.PlaybackConfig
import com.looplingo.horizon.domain.model.SpeedPresets
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class AudioPlaybackService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "loop_lingo_audio_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.looplingo.horizon.START"
        const val ACTION_PLAY_NEXT = "com.looplingo.horizon.PLAY_NEXT"
        const val ACTION_PLAY_PREVIOUS = "com.looplingo.horizon.PLAY_PREVIOUS"
        const val ACTION_TOGGLE_PLAYBACK = "com.looplingo.horizon.TOGGLE_PLAYBACK"
        const val ACTION_STOP = "com.looplingo.horizon.STOP"
        const val ACTION_SEEK_TO = "com.looplingo.horizon.SEEK_TO"
        const val ACTION_SET_SPEED = "com.looplingo.horizon.SET_SPEED"
        const val ACTION_SET_AB_LOOP = "com.looplingo.horizon.SET_AB_LOOP"
        const val ACTION_SEEK_FORWARD = "com.looplingo.horizon.SEEK_FORWARD"
        const val ACTION_SEEK_BACKWARD = "com.looplingo.horizon.SEEK_BACKWARD"
        const val EXTRA_VIDEO_PATH = "video_path"
        const val EXTRA_SEEK_POSITION_MS = "seek_position_ms"
        const val EXTRA_SEEK_OFFSET_MS = "seek_offset_ms"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_RANGE_START_MS = "range_start_ms"
        const val EXTRA_RANGE_END_MS = "range_end_ms"
        const val EXTRA_LOOP_COUNT = "loop_count"

        private const val AB_CHECK_FAR_MS = 10_000L
        private const val AB_CHECK_MID_MS = 3000L
        private const val AB_CHECK_NEAR_MS = 500L
        private const val AB_NEAR_THRESHOLD_MS = 3000L
        private const val AB_MID_THRESHOLD_MS = 10_000L
        private const val DIALOGUE_PAUSE_MS = 1000L

        @Volatile var currentPositionMs: Long = 0L; private set
        @Volatile var isPlaying: Boolean = false; private set
        @Volatile var currentVideoPath: String = ""; private set
        @Volatile var durationMs: Long = 0L; private set

        internal fun updateState(playing: Boolean, position: Long, videoPath: String, duration: Long = durationMs) {
            isPlaying = playing; currentPositionMs = position; currentVideoPath = videoPath; durationMs = duration
        }

        fun resetState() { isPlaying = false; currentPositionMs = 0L; currentVideoPath = ""; durationMs = 0L }

        fun startService(context: Context, videoPath: String) {
            context.startForegroundService(Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_START; putExtra(EXTRA_VIDEO_PATH, videoPath)
            })
        }

        fun seekToPosition(context: Context, videoPath: String, positionMs: Long) {
            context.startService(Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_SEEK_TO; putExtra(EXTRA_VIDEO_PATH, videoPath); putExtra(EXTRA_SEEK_POSITION_MS, positionMs)
            })
        }

        fun setSpeed(context: Context, speed: Float) {
            context.startService(Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_SET_SPEED; putExtra(EXTRA_SPEED, speed)
            })
        }

        fun setABLoop(context: Context, videoPath: String, rangeStartMs: Long, rangeEndMs: Long, loopCount: Int) {
            context.startService(Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_SET_AB_LOOP; putExtra(EXTRA_VIDEO_PATH, videoPath)
                putExtra(EXTRA_RANGE_START_MS, rangeStartMs); putExtra(EXTRA_RANGE_END_MS, rangeEndMs); putExtra(EXTRA_LOOP_COUNT, loopCount)
            })
        }

        fun clearABLoop(context: Context, videoPath: String) {
            setABLoop(context, videoPath, 0L, -1L, 1)
        }

        fun stopService(context: Context) {
            context.startService(Intent(context, AudioPlaybackService::class.java).apply { action = ACTION_STOP })
        }

        fun seekForward(context: Context, offsetMs: Long = 5000L) {
            context.startService(Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_SEEK_FORWARD; putExtra(EXTRA_SEEK_OFFSET_MS, offsetMs)
            })
        }

        fun seekBackward(context: Context, offsetMs: Long = 5000L) {
            context.startService(Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_SEEK_BACKWARD; putExtra(EXTRA_SEEK_OFFSET_MS, offsetMs)
            })
        }

        fun togglePlayback(context: Context) {
            context.startService(Intent(context, AudioPlaybackService::class.java).apply { action = ACTION_TOGGLE_PLAYBACK })
        }
    }

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var currentConfig: PlaybackConfig = PlaybackConfig(videoPath = "")
    var currentLoopIteration: Int = 0
    var currentVideoIndex: Int = -1
    var videoPaths: List<String> = emptyList()
    var videoTitles: Map<String, String> = emptyMap()
    var abLoopCompleted: Boolean = false
    var isABSeeking = false
    private val abHandler = Handler(Looper.getMainLooper())
    private var abCheckRunnable: Runnable? = null
    private var isCleanedUp = false

    @Inject lateinit var notificationHelper: PlaybackNotificationHelper
    @Inject lateinit var playbackController: PlaybackController
    @Inject lateinit var wakeLockManager: WakeLockManager
    @Inject lateinit var intentHandler: IntentHandler
    @Inject lateinit var startPlaybackManager: StartPlaybackManager
    @Inject lateinit var dialogueLoopManager: DialogueLoopManager

    override fun onCreate() {
        super.onCreate()
        try {
            notificationHelper.createNotificationChannel()
            wakeLockManager.setup(this)
            setupExoPlayer()
            setupMediaSession()
            notificationHelper.registerNotificationReceiver(this, object : NotificationCallbacks {
                override fun onTogglePlayback() { exoPlayer?.let { playbackController.togglePlayback(it) }; updateNotification() }
                override fun onNext() {
                    exoPlayer?.let { player ->
                        playbackController.playNext(this@AudioPlaybackService, player, videoPaths, currentVideoIndex) { path ->
                            serviceScope.launch { try { startPlayback(path) } catch (e: Exception) { Timber.e(e, "Failed to play next"); stopSelf() } }
                        }
                    }
                }
                override fun onPrevious() {
                    exoPlayer?.let { player ->
                        playbackController.playPrevious(player, videoPaths, currentVideoIndex) { path ->
                            serviceScope.launch { try { startPlayback(path) } catch (e: Exception) { Timber.e(e, "Failed to play previous") } }
                        }
                        currentLoopIteration = 0; updateNotification()
                    }
                }
                override fun onStop() = stopSelf()
            })
            Timber.i("AudioPlaybackService created")
        } catch (e: Exception) { Timber.e(e, "Failed to initialize AudioPlaybackService"); stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return intentHandler.onStartCommand(
            intent, this,
            getExoPlayer = { exoPlayer },
            getConfig = { currentConfig },
            setConfig = { currentConfig = it },
            getVideoPaths = { videoPaths },
            getCurrentVideoIndex = { currentVideoIndex },
            setCurrentLoopIteration = { currentLoopIteration = it },
            setABLoopCompleted = { abLoopCompleted = it },
            loadVideoPaths = { loadVideoPaths() },
            startPlayback = { path -> startPlayback(path) },
            waitForPlayerReady = { timeout -> startPlaybackManager.waitForPlayerReady({ exoPlayer }, timeout) },
            scheduleAbCheck = { scheduleAbCheck() },
            cancelAbMonitor = { cancelAbMonitor() },
            updateNotification = { updateNotification() },
            serviceScope = serviceScope,
            stopSelf = { stopSelf() }
        )
    }

    override fun onDestroy() { super.onDestroy(); cleanup() }

    override fun onTaskRemoved(rootIntent: Intent?) { super.onTaskRemoved(rootIntent); cleanup(); stopSelf() }

    private fun cleanup() {
        if (isCleanedUp) return
        isCleanedUp = true
        try {
            cancelAbMonitor()
            playbackController.cancelPositionUpdate()
            notificationHelper.unregisterNotificationReceiver(this)
            serviceJob.cancel()
            exoPlayer?.removeListener(playerListener)
            exoPlayer?.release(); exoPlayer = null
            mediaSession?.release(); mediaSession = null
            wakeLockManager.release()
            wakeLockManager.releaseSafely()
            resetState()
        } catch (e: Exception) { Timber.e(e, "Error during service cleanup") }
    }

    private fun setupExoPlayer() {
        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true))
        }
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        exoPlayer = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector).setAudioAttributes(audioAttributes, true).setHandleAudioBecomingNoisy(true)
            .build()
        exoPlayer?.addListener(playerListener)
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession.Builder(this, exoPlayer!!).build()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> {
                    startPlaybackManager.handlePlaybackEnded(
                        getExoPlayer = { exoPlayer },
                        getConfig = { currentConfig },
                        getCurrentLoopIteration = { currentLoopIteration },
                        setCurrentLoopIteration = { currentLoopIteration = it },
                        getABLoopCompleted = { abLoopCompleted },
                        getVideoPaths = { videoPaths },
                        getCurrentVideoIndex = { currentVideoIndex },
                        cancelAbMonitor = { cancelAbMonitor() },
                        scheduleAbCheck = { scheduleAbCheck() },
                        setABSeeking = { isABSeeking = it },
                        updateNotification = { updateNotification() },
                        serviceScope = serviceScope,
                        startPlayback = { path -> startPlayback(path) },
                        stopSelf = { stopSelf() }
                    )
                }
                Player.STATE_READY -> {
                    Timber.d("Player ready — duration: %d ms", exoPlayer?.duration ?: -1)
                    startPlaybackManager.retryAttemptCount = 0
                    updateNotification()
                }
                Player.STATE_BUFFERING -> updateNotification()
            }
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            updateState(playing, currentPositionMs, currentVideoPath)
            if (playing) { wakeLockManager.acquire(); isABSeeking = false }
            else if (!isABSeeking) { wakeLockManager.release() }
            updateNotification()
        }

        override fun onPlayerError(error: PlaybackException) {
            Timber.e(error, "ExoPlayer error")
            startPlaybackManager.handlePlayerError({ exoPlayer }, serviceScope, notificationHelper)
        }
    }

    private fun scheduleAbCheck() {
        cancelAbMonitor()
        abCheckRunnable = Runnable { checkABPositionAndReschedule() }
        abHandler.postDelayed(abCheckRunnable!!, 1000L)
    }

    private fun cancelAbMonitor() {
        abCheckRunnable?.let { abHandler.removeCallbacks(it) }; abCheckRunnable = null
    }

    private fun checkABPositionAndReschedule() {
        if (!currentConfig.hasABLoop || abLoopCompleted) return
        val currentPosition = exoPlayer?.currentPosition ?: return
        val distanceToB = currentConfig.rangeEndMs - currentPosition

        if (currentPosition >= currentConfig.rangeEndMs) {
            exoPlayer?.pause()
            currentLoopIteration++
            Timber.d("A-B: reached B at %dms, iteration %d/%d — pausing for %dms",
                currentConfig.rangeEndMs, currentLoopIteration, currentConfig.loopCount, DIALOGUE_PAUSE_MS)
            if (currentLoopIteration < currentConfig.loopCount) {
                exoPlayer?.let { player ->
                    playbackController.pauseForDialogueRepeat(player, currentConfig, DIALOGUE_PAUSE_MS) {
                        scheduleAbCheck(); updateNotification()
                    }
                    isABSeeking = true
                }
            } else {
                Timber.d("A-B loop done after %d iterations — pausing then continuing", currentConfig.loopCount)
                abHandler.postDelayed({
                    abLoopCompleted = true; isABSeeking = true
                    exoPlayer?.seekTo(currentConfig.rangeEndMs); exoPlayer?.play()
                }, DIALOGUE_PAUSE_MS)
            }
            return
        }

        val nextDelay = when {
            distanceToB <= AB_NEAR_THRESHOLD_MS -> AB_CHECK_NEAR_MS
            distanceToB <= AB_MID_THRESHOLD_MS -> AB_CHECK_MID_MS
            else -> AB_CHECK_FAR_MS
        }
        abCheckRunnable = Runnable { checkABPositionAndReschedule() }
        abHandler.postDelayed(abCheckRunnable!!, nextDelay)
    }

    private suspend fun loadVideoPaths() {
        val result = startPlaybackManager.loadVideoPaths()
        videoPaths = result.first; videoTitles = result.second
    }

    private suspend fun startPlayback(videoPath: String) {
        startPlaybackManager.startPlayback(
            videoPath,
            getExoPlayer = { exoPlayer },
            getConfig = { currentConfig },
            setConfig = { currentConfig = it },
            getVideoPaths = { videoPaths },
            setCurrentVideoIndex = { currentVideoIndex = it },
            setCurrentLoopIteration = { currentLoopIteration = it },
            setABLoopCompleted = { abLoopCompleted = it },
            updateState = { playing, pos, path, dur -> updateState(playing, pos, path, dur) },
            scheduleAbCheck = { scheduleAbCheck() },
            cancelAbMonitor = { cancelAbMonitor() },
            updateNotification = { updateNotification() }
        )
    }

    private fun updateNotification() {
        val isPlaying = exoPlayer?.isPlaying == true
        val title = videoTitles[currentConfig.videoPath] ?: currentConfig.videoPath.substringAfterLast("/", "Unknown")
        val speedLabel = SpeedPresets.closestTo(currentConfig.speed).label
        val loopInfo = when {
            currentConfig.isNormalPlayback -> ""
            currentConfig.hasABLoop -> "AB loop ${currentLoopIteration + 1}/${currentConfig.loopCount}"
            currentConfig.loopCount > 1 -> "Loop ${currentLoopIteration + 1}/${currentConfig.loopCount}"
            else -> ""
        }
        notificationHelper.updateNotification(isPlaying, title, "$speedLabel ${loopInfo}".trim(), null)
    }
}
