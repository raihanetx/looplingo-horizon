package com.looplingo.horizon.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import com.looplingo.horizon.R
import com.looplingo.horizon.model.PlaybackConfig
import com.looplingo.horizon.model.PlaybackConfigValidator
import com.looplingo.horizon.model.SpeedPresets
import com.looplingo.horizon.repository.PlaybackRepository
import com.looplingo.horizon.repository.VideoRepository
import com.looplingo.horizon.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service for audio playback.
 *
 * Optimized for battery life and smooth performance:
 *  - No loop modes. Behavior is determined by PlaybackConfig values:
 *    - Normal: no A-B range, loopCount=1 → play full video once
 *    - A-B Loop: A→B segment, loop N times, then continue to end of video
 *    - Full Loop: no A-B range, loopCount>1 → loop entire video N times
 *  - Speed control via ExoPlayer.setPlaybackParameters()
 *  - A-B position monitor uses Handler-based scheduling instead of polling
 *    — only checks when playback is near the B marker, zero CPU waste
 *  - WakeLock released on pause (not just renewal cancelled)
 *  - START_NOT_STICKY — no zombie service restarts
 */
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

        private const val WAKELOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L  // 6 hours — for long study sessions
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2000L

        // A-B monitoring: check interval increases as we get closer to B
        // Optimized for minimal CPU wake-ups during long background playback
        private const val AB_CHECK_FAR_MS = 10_000L     // >10s away: check every 10s (was 5s)
        private const val AB_CHECK_MID_MS = 3000L        // 3-10s away: check every 3s
        private const val AB_CHECK_NEAR_MS = 500L        // <3s away: check every 500ms (precise)
        private const val AB_NEAR_THRESHOLD_MS = 3000L   // Switch to frequent checks within 3s of B (was 5s)
        private const val AB_MID_THRESHOLD_MS = 10_000L  // Switch to medium checks within 10s
        private const val AB_DIALOGUE_NEAR_THRESHOLD_MS = 2000L // Dialogue mode: 2s threshold for frequent checks
        private const val CUE_BOUNDARY_GAP_MS = 30L   // Small gap before next cue's startMs — VAD provides precise boundaries
        private const val DEFAULT_DIALOGUE_PAUSE_MS = 1000L  // 1 second pause — like a human pause between sentences

        // ══════════════════════════════════════════════════════════════════════
        // STATIC PLAYBACK STATE — readable by UI for transcript sync
        // These are updated by the service and read by PlaybackSettingsFragment
        // for real-time subtitle highlighting. Uses @Volatile for thread safety.
        // ══════════════════════════════════════════════════════════════════════
        @Volatile
        var currentPositionMs: Long = 0L
            private set

        @Volatile
        var isPlaying: Boolean = false
            private set

        @Volatile
        var currentVideoPath: String = ""
            private set

        @Volatile
        var durationMs: Long = 0L
            private set

        /** Update static playback state — called from the service instance. Should not be called externally. */
        internal fun updateState(playing: Boolean, position: Long, videoPath: String, duration: Long = durationMs) {
            isPlaying = playing
            currentPositionMs = position
            currentVideoPath = videoPath
            durationMs = duration
        }

        /** Reset static state when service stops. */
        fun resetState() {
            isPlaying = false
            currentPositionMs = 0L
            currentVideoPath = ""
            durationMs = 0L
        }

        fun startService(context: Context, videoPath: String) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_VIDEO_PATH, videoPath)
            }
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start AudioPlaybackService")
            }
        }

        /**
         * Request the service to seek to a specific position.
         * Used by the transcript UI when the user taps a subtitle line.
         */
        fun seekToPosition(context: Context, videoPath: String, positionMs: Long) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_SEEK_TO
                putExtra(EXTRA_VIDEO_PATH, videoPath)
                putExtra(EXTRA_SEEK_POSITION_MS, positionMs)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to send seek request")
            }
        }

        /**
         * Change playback speed in real-time without restarting the service.
         */
        fun setSpeed(context: Context, speed: Float) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_SET_SPEED
                putExtra(EXTRA_SPEED, speed)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to send speed change request")
            }
        }

        /**
         * Change A-B loop range in real-time without restarting the service.
         * This allows try-before-save: preview the loop before committing.
         */
        fun setABLoop(
            context: Context,
            videoPath: String,
            rangeStartMs: Long,
            rangeEndMs: Long,
            loopCount: Int
        ) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_SET_AB_LOOP
                putExtra(EXTRA_VIDEO_PATH, videoPath)
                putExtra(EXTRA_RANGE_START_MS, rangeStartMs)
                putExtra(EXTRA_RANGE_END_MS, rangeEndMs)
                putExtra(EXTRA_LOOP_COUNT, loopCount)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to send A-B loop change request")
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * Seek forward by the given offset in milliseconds.
         * Used by the transport controls in the mini player.
         */
        fun seekForward(context: Context, offsetMs: Long = 5000L) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_SEEK_FORWARD
                putExtra(EXTRA_SEEK_OFFSET_MS, offsetMs)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to send seek forward request")
            }
        }

        /**
         * Seek backward by the given offset in milliseconds.
         * Used by the transport controls in the mini player.
         */
        fun seekBackward(context: Context, offsetMs: Long = 5000L) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_SEEK_BACKWARD
                putExtra(EXTRA_SEEK_OFFSET_MS, offsetMs)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to send seek backward request")
            }
        }

        /**
         * Toggle play/pause — static helper for UI components.
         * Sends a toggle broadcast intent to the running service.
         */
        fun togglePlayback(context: Context) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_TOGGLE_PLAYBACK
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to send toggle playback request")
            }
        }
    }

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isWakeLockHeld = false
    private var serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var currentConfig: PlaybackConfig = PlaybackConfig(videoPath = "")
    private var currentLoopIteration: Int = 0
    private var currentVideoIndex: Int = -1
    private var videoPaths: List<String> = emptyList()
    private var videoTitles: Map<String, String> = emptyMap()
    private var retryAttemptCount: Int = 0
    private var abLoopCompleted: Boolean = false
    private var isHandlingPlaybackEnded: Boolean = false  // Guard against re-entrant calls
    private var isReceiverRegistered: Boolean = false     // Guard against double unregistration

    // Handler-based A-B monitoring (replaces wasteful coroutine polling)
    private val abHandler = Handler(Looper.getMainLooper())
    private var abCheckRunnable: Runnable? = null

    // Position update handler for transcript sync
    // Uses adaptive interval: 1s when playing normally, 500ms when A-B loop active
    // Reduces CPU wake-ups by 50% during normal background playback
    private val positionHandler = Handler(Looper.getMainLooper())
    private var positionUpdateRunnable: Runnable? = null

    @Inject lateinit var playbackRepository: PlaybackRepository
    @Inject lateinit var videoRepository: VideoRepository

    private val notificationActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_TOGGLE_PLAYBACK -> togglePlayback()
                ACTION_PLAY_NEXT -> playNext()
                ACTION_PLAY_PREVIOUS -> playPrevious()
                ACTION_STOP -> stopSelf()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            setupWakeLock()
            setupExoPlayer()
            setupMediaSession()
            registerNotificationReceiver()
            Timber.i("AudioPlaybackService created")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize AudioPlaybackService")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_START) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Android 14+ requires explicit foreground service type
                    startForeground(
                        NOTIFICATION_ID,
                        buildLoadingNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, buildLoadingNotification())
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to call startForeground()")
            }
        }

        // Return NOT_STICKY — if the OS kills us, don't auto-restart as a zombie
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_START -> {
                val videoPath = intent.getStringExtra(EXTRA_VIDEO_PATH)
                if (videoPath.isNullOrBlank()) return START_NOT_STICKY
                serviceScope.launch {
                    try {
                        loadVideoPaths()
                        startPlayback(videoPath)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to start playback")
                        updateNotificationWithError("Failed to start playback: ${e.message}")
                    }
                }
            }
            ACTION_SEEK_TO -> {
                val videoPath = intent.getStringExtra(EXTRA_VIDEO_PATH)
                val seekMs = intent.getLongExtra(EXTRA_SEEK_POSITION_MS, 0)
                if (!videoPath.isNullOrBlank() && seekMs >= 0) {
                    handleSeekRequest(videoPath, seekMs)
                }
            }
            ACTION_SET_SPEED -> {
                val speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f)
                handleSpeedChange(speed)
            }
            ACTION_SET_AB_LOOP -> {
                val videoPath = intent.getStringExtra(EXTRA_VIDEO_PATH)
                val rangeStartMs = intent.getLongExtra(EXTRA_RANGE_START_MS, 0L)
                val rangeEndMs = intent.getLongExtra(EXTRA_RANGE_END_MS, -1L)
                val loopCount = intent.getIntExtra(EXTRA_LOOP_COUNT, 1)
                if (!videoPath.isNullOrBlank()) {
                    handleABLoopChange(videoPath, rangeStartMs, rangeEndMs, loopCount)
                }
            }
            ACTION_SEEK_FORWARD -> {
                val offsetMs = intent.getLongExtra(EXTRA_SEEK_OFFSET_MS, 5000L)
                handleSeekForward(offsetMs)
            }
            ACTION_SEEK_BACKWARD -> {
                val offsetMs = intent.getLongExtra(EXTRA_SEEK_OFFSET_MS, 5000L)
                handleSeekBackward(offsetMs)
            }
            ACTION_TOGGLE_PLAYBACK -> togglePlayback()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private var isCleanedUp = false
    private var isABSeeking = false  // Guard against WakeLock release during A-B seek transitions

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        cleanup()
        stopSelf()
    }

    private fun cleanup() {
        if (isCleanedUp) return
        isCleanedUp = true
        try {
            cancelAbMonitor()
            cancelPositionUpdate()
            unregisterNotificationReceiver()
            serviceJob.cancel()
            exoPlayer?.removeListener(playerListener)
            exoPlayer?.release()
            exoPlayer = null
            mediaSession?.release()
            mediaSession = null
            releaseWakeLock()
            // Reset static state
            resetState()
        } catch (e: Exception) {
            Timber.e(e, "Error during service cleanup")
        }
    }

    /**
     * Handle a live speed change request.
     * Applies immediately to the running ExoPlayer without restarting.
     */
    private fun handleSpeedChange(speed: Float) {
        try {
            val clampedSpeed = speed.coerceIn(0.25f, 2.0f)
            exoPlayer?.playbackParameters = androidx.media3.common.PlaybackParameters(clampedSpeed)
            currentConfig = currentConfig.copy(speed = clampedSpeed)
            Timber.d("Speed changed to %.2fx during playback", clampedSpeed)
            updateNotification()
        } catch (e: Exception) {
            Timber.e(e, "Failed to change speed")
        }
    }

    /**
     * Handle a live A-B loop change request.
     * Applies immediately to the running playback without restarting.
     * Seeks to point A if the current position is outside the new range.
     */
    private fun handleABLoopChange(videoPath: String, rangeStartMs: Long, rangeEndMs: Long, loopCount: Int) {
        if (videoPath != currentConfig.videoPath) return

        try {
            val newConfig = currentConfig.copy(
                rangeStartMs = rangeStartMs,
                rangeEndMs = rangeEndMs,
                loopCount = loopCount
            )
            currentConfig = newConfig
            currentLoopIteration = 0
            abLoopCompleted = false

            // If A-B loop is set and player is past the range, seek to A
            if (newConfig.hasABLoop) {
                val currentPos = exoPlayer?.currentPosition ?: 0L
                if (currentPos < newConfig.rangeStartMs || currentPos >= newConfig.rangeEndMs) {
                    exoPlayer?.seekTo(newConfig.rangeStartMs)
                }
                scheduleAbCheck()
            } else {
                cancelAbMonitor()
            }

            Timber.d(
                "A-B loop changed during playback: A=%dms, B=%dms, loop=%d",
                rangeStartMs, rangeEndMs, loopCount
            )
            updateNotification()
        } catch (e: Exception) {
            Timber.e(e, "Failed to change A-B loop")
        }
    }

    private fun handleSeekRequest(videoPath: String, positionMs: Long) {
        if (videoPath == currentConfig.videoPath && exoPlayer != null) {
            // Same video — just seek
            try {
                exoPlayer?.seekTo(positionMs)
                exoPlayer?.play()
                Timber.d("Seek to %dms for current video", positionMs)
            } catch (e: Exception) {
                Timber.e(e, "Failed to seek")
            }
        } else {
            // Different video — start it and wait for player to be ready before seeking
            serviceScope.launch {
                try {
                    loadVideoPaths()
                    startPlayback(videoPath)
                    // Wait for player to reach STATE_READY using listener-based approach
                    // instead of polling. This is more efficient and avoids unnecessary
                    // CPU wake-ups. Timeout after 5 seconds.
                    val ready = waitForPlayerReady(timeoutMs = 5000L)
                    if (ready) {
                        exoPlayer?.seekTo(positionMs)
                        Timber.d("Started new video and seeked to %dms", positionMs)
                    } else {
                        Timber.w("Player didn't become ready within 5s — seeking anyway")
                        exoPlayer?.seekTo(positionMs)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to start and seek")
                }
            }
        }
    }

    /**
     * Suspend function that waits for ExoPlayer to reach STATE_READY.
     * Uses a Player.Listener callback instead of a polling loop.
     * Returns true if the player became ready within the timeout, false otherwise.
     */
    private suspend fun waitForPlayerReady(timeoutMs: Long): Boolean {
        val player = exoPlayer ?: return false
        if (player.playbackState == Player.STATE_READY) return true

        return try {
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
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

    // ══════════════════════════════════════════════════════════════════════
    // EXOPLAYER SETUP
    // ══════════════════════════════════════════════════════════════════════

    private fun setupExoPlayer() {
        val trackSelector = DefaultTrackSelector(this@AudioPlaybackService).apply {
            setParameters(buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true))
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        exoPlayer?.addListener(playerListener)
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession.Builder(this, exoPlayer!!)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(
                            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().build()
                        )
                        .build()
                }
            })
            .build()
    }

    // ══════════════════════════════════════════════════════════════════════
    // PLAYER LISTENER
    // ══════════════════════════════════════════════════════════════════════

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> handlePlaybackEnded()
                Player.STATE_READY -> {
                    Timber.d("Player ready — duration: %d ms", exoPlayer?.duration ?: -1)
                    retryAttemptCount = 0
                    updateNotification()
                }
                Player.STATE_BUFFERING -> updateNotification()
            }
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            // Update static state for transcript sync
            updateState(playing, currentPositionMs, currentVideoPath)
            if (playing) {
                acquireWakeLock()
                // Clear A-B seeking flag once playback resumes after a seek
                isABSeeking = false
            } else {
                // Don't release WakeLock during A-B seek transitions.
                // When ExoPlayer seeks from B back to A, it briefly pauses which
                // triggers this callback. Without the guard, the WakeLock is released
                // and re-acquired on every loop iteration, causing a brief CPU sleep
                // gap during long study sessions.
                if (!isABSeeking) {
                    releaseWakeLock()
                }
            }
            updateNotification()
        }

        override fun onPlayerError(error: PlaybackException) {
            Timber.e(error, "ExoPlayer error")
            handlePlayerError(error)
        }
    }

    private fun handlePlayerError(error: PlaybackException) {
        retryAttemptCount++
        if (retryAttemptCount <= MAX_RETRY_ATTEMPTS) {
            val backoffDelay = RETRY_DELAY_MS * (1L shl (retryAttemptCount - 1))
            serviceScope.launch {
                delay(backoffDelay)
                if (isActive) {
                    try { exoPlayer?.prepare(); exoPlayer?.play() }
                    catch (e: Exception) { Timber.e(e, "Retry failed") }
                }
            }
        } else {
            updateNotificationWithError("Playback failed after $MAX_RETRY_ATTEMPTS attempts")
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // PLAYBACK CONTROL — SIMPLIFIED LOOP LOGIC
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun startPlayback(videoPath: String) {
        Timber.i("Starting playback for: %s", videoPath)

        // Update static state for transcript sync
        updateState(isPlaying, currentPositionMs, videoPath)

        currentLoopIteration = 0
        abLoopCompleted = false
        retryAttemptCount = 0
        cancelAbMonitor()
        currentVideoIndex = videoPaths.indexOf(videoPath)

        if (currentVideoIndex < 0) {
            loadVideoPaths()
            currentVideoIndex = videoPaths.indexOf(videoPath)
        }

        val savedConfig = try {
            playbackRepository.getConfigForVideo(videoPath)
        } catch (e: Exception) { null }

        currentConfig = savedConfig ?: PlaybackConfig(videoPath = videoPath)
        currentConfig = PlaybackConfigValidator.sanitize(currentConfig)

        Timber.d(
            "Config: A=%dms, B=%dms, loop=%d, speed=%.2f, isNormal=%s",
            currentConfig.rangeStartMs, currentConfig.rangeEndMs,
            currentConfig.loopCount, currentConfig.speed, currentConfig.isNormalPlayback
        )

        val playbackUri = resolvePlaybackUri(videoPath)

        try {
            val mediaSource = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(this))
                .createMediaSource(MediaItem.fromUri(playbackUri))

            exoPlayer?.apply {
                setMediaSource(mediaSource)
                prepare()
                repeatMode = Player.REPEAT_MODE_OFF

                // Apply speed
                val params = androidx.media3.common.PlaybackParameters(currentConfig.speed)
                playbackParameters = params

                // Seek to A if A-B loop is set
                if (currentConfig.hasABLoop && currentConfig.rangeStartMs > 0) {
                    seekTo(currentConfig.rangeStartMs)
                }

                play()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to set up ExoPlayer")
            updateNotificationWithError("Cannot play this file: ${e.message}")
            return
        }

        // Start smart A-B monitor if A-B loop is configured
        if (currentConfig.hasABLoop) {
            scheduleAbCheck()
        }

        // Start position updates for transcript sync
        startPositionUpdate()

        updateNotification()
    }

    /**
     * Core loop logic — called when playback reaches STATE_ENDED.
     *
     * Decision tree:
     *  - Normal playback (no loop): play next video or stop
     *  - A-B Loop (already completed via monitor): continue from B to end, then advance
     *  - A-B Loop (STATE_ENDED before monitor caught B): increment, restart at A or continue
     *  - Full Loop (no A-B, loopCount>1): if iterations < loopCount → restart from 0; else next/stop
     *
     * Bug fix: no longer double-counts iterations. The A-B monitor already increments
     * currentLoopIteration when it catches position >= B, so we only increment here
     * when the A-B monitor hasn't already handled it.
     */
    private fun handlePlaybackEnded() {
        // Guard against re-entrant calls — STATE_ENDED can fire multiple times
        // if we seek back to A and the player transitions through ENDED again
        if (isHandlingPlaybackEnded) return
        isHandlingPlaybackEnded = true

        try {
            if (currentConfig.isNormalPlayback) {
                // No looping configured — just play next or stop
                advanceToNextVideo()
                return
            }

            if (currentConfig.hasABLoop) {
                if (abLoopCompleted) {
                    // A-B loop was already completed by the monitor, and the video has now
                    // played from B to the actual end — advance to next video
                    Timber.d("A-B loop done, video played from B to end — advancing")
                    advanceToNextVideo()
                } else {
                    // STATE_ENDED fired before A-B monitor caught B (e.g. short video or overshoot)
                    // DON'T increment currentLoopIteration here — the A-B monitor already
                    // incremented it when it caught position >= B. If we increment again,
                    // we double-count the iteration and the loop ends prematurely.
                    // Instead, just seek back to A and let the monitor handle counting.
                    if (currentLoopIteration < currentConfig.loopCount) {
                        Timber.d("A-B loop: STATE_ENDED before monitor, iteration %d/%d — seeking to A",
                            currentLoopIteration + 1, currentConfig.loopCount)
                        cancelAbMonitor()
                        seekToA()
                        scheduleAbCheck()
                    } else {
                        // Loop count reached — continue from B to end instead of skipping
                        Timber.d("A-B loop completed after %d iterations, continuing from B to end", currentConfig.loopCount)
                        abLoopCompleted = true
                        cancelAbMonitor()
                        exoPlayer?.seekTo(currentConfig.rangeEndMs)
                        exoPlayer?.play()
                    }
                }
            } else {
                // Full video loop (no A-B range)
                currentLoopIteration++
                if (currentLoopIteration < currentConfig.loopCount) {
                    Timber.d("Full loop iteration %d/%d", currentLoopIteration, currentConfig.loopCount)
                    exoPlayer?.seekTo(0)
                    exoPlayer?.play()
                } else {
                    Timber.d("Full loop completed after %d iterations", currentConfig.loopCount)
                    advanceToNextVideo()
                }
            }
        } finally {
            isHandlingPlaybackEnded = false
        }
    }

    private fun seekToA() {
        try {
            // Mark that we're seeking for A-B loop so the WakeLock isn't
            // released during the brief pause between B→A transitions
            isABSeeking = true
            exoPlayer?.seekTo(currentConfig.rangeStartMs)
            exoPlayer?.play()
        } catch (e: Exception) {
            isABSeeking = false
            Timber.e(e, "Failed to seek to A position")
        }
        updateNotification()
    }

    /**
     * Pause for 1 second after dialogue ends, then seek back to A and resume.
     *
     * This creates a natural pause — like a human takes a breath after saying
     * something — before repeating the dialogue. The pause ensures:
     * 1. No next-dialogue words bleed into the current one
     * 2. The listener has time to process what they just heard
     * 3. The repeat feels natural, not jarring
     */
    private fun pauseForDialogueRepeat() {
        abHandler.postDelayed({
            try {
                isABSeeking = true
                exoPlayer?.seekTo(currentConfig.rangeStartMs)
                exoPlayer?.play()
                Timber.d("Dialogue repeat: seeking to A (%dms) and resuming", currentConfig.rangeStartMs)
            } catch (e: Exception) {
                isABSeeking = false
                Timber.e(e, "Failed to resume after dialogue pause")
            }
            scheduleAbCheck()
            updateNotification()
        }, DIALOGUE_PAUSE_MS)
    }

    private fun advanceToNextVideo() {
        if (videoPaths.isEmpty()) { stopSelf(); return }

        if (currentVideoIndex < videoPaths.size - 1) {
            val nextPath = videoPaths[currentVideoIndex + 1]
            serviceScope.launch {
                try { startPlayback(nextPath) }
                catch (e: Exception) { Timber.e(e, "Failed to advance"); stopSelf() }
            }
        } else {
            stopSelf()
        }
    }

    private fun playPrevious() {
        if (videoPaths.isEmpty()) return
        if (currentVideoIndex > 0) {
            val prevPath = videoPaths[currentVideoIndex - 1]
            serviceScope.launch {
                try { startPlayback(prevPath) } catch (e: Exception) { Timber.e(e, "Failed") }
            }
        } else {
            currentLoopIteration = 0
            try { exoPlayer?.seekTo(0); exoPlayer?.play() } catch (_: Exception) {}
        }
    }

    private fun playNext() { advanceToNextVideo() }

    private fun togglePlayback() {
        try {
            if (exoPlayer?.isPlaying == true) exoPlayer?.pause() else exoPlayer?.play()
        } catch (e: Exception) {
            Timber.e(e, "Failed to toggle playback")
        }
        updateNotification()
    }

    /**
     * Handle a seek-forward request from transport controls.
     * Seeks forward by the given offset, clamped to the video duration.
     */
    private fun handleSeekForward(offsetMs: Long) {
        try {
            val currentPos = exoPlayer?.currentPosition ?: 0L
            val duration = exoPlayer?.duration ?: 0L
            val newPos = (currentPos + offsetMs).coerceAtMost(if (duration > 0) duration else Long.MAX_VALUE)
            exoPlayer?.seekTo(newPos)
            Timber.d("Seek forward %dms → %dms", offsetMs, newPos)
        } catch (e: Exception) {
            Timber.e(e, "Failed to seek forward")
        }
    }

    /**
     * Handle a seek-backward request from transport controls.
     * Seeks backward by the given offset, clamped to position 0.
     */
    private fun handleSeekBackward(offsetMs: Long) {
        try {
            val currentPos = exoPlayer?.currentPosition ?: 0L
            val newPos = (currentPos - offsetMs).coerceAtLeast(0L)
            exoPlayer?.seekTo(newPos)
            Timber.d("Seek backward %dms → %dms", offsetMs, newPos)
        } catch (e: Exception) {
            Timber.e(e, "Failed to seek backward")
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // DIALOGUE AUTO-LOOP — Sequential A-B loop across all cues
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Handle a dialogue auto-loop request.
     *
     * Parses the JSON cues, optionally filters by selected indices, sets up the
     * auto-loop state, and starts playing from the first cue.
     *
     * SMART BOUNDARY: Instead of blindly using Whisper's endMs (which overlaps
     * into the next dialogue's audio), we use the NEXT cue's startMs as the
     * upper boundary. This guarantees zero bleed-through — the current dialogue
     * ends BEFORE the next one starts, with a 150ms gap for safety.
     *
     * @param videoPath Video path to match against current playback
     * @param cuesJson JSON array of SubtitleCue objects
     * @param loopCount Number of times to repeat each dialogue
     * @param pauseMs Silence gap in ms between repetitions (default 1000ms = 1 second)
     * @param selectedIndices Optional array of cue indices to include (null = all)
     */
    private fun handleDialogueAutoLoop(
        videoPath: String, cuesJson: String, loopCount: Int,
        pauseMs: Long = 1000L, selectedIndices: IntArray? = null
    ) {
        if (videoPath != currentConfig.videoPath) return

        try {
            // Parse cues from JSON — keep ALL cues for boundary calculation
            val jsonArray = org.json.JSONArray(cuesJson)
            val allCues = mutableListOf<SubtitleCue>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                allCues.add(SubtitleCue(
                    index = obj.getInt("index"),
                    startMs = obj.getLong("startMs"),
                    endMs = obj.getLong("endMs"),
                    text = obj.getString("text")
                ))
            }

            if (allCues.isEmpty()) {
                Timber.w("Dialogue auto-loop: no cues provided")
                return
            }

            // Filter cues by selected indices if provided
            val cues = if (selectedIndices != null && selectedIndices.isNotEmpty()) {
                val indexSet = selectedIndices.toSet()
                allCues.filterIndexed { idx, _ -> idx in indexSet }
            } else {
                allCues
            }

            if (cues.isEmpty()) {
                Timber.w("Dialogue auto-loop: no cues after filtering by selected indices")
                return
            }

            dialogueAutoLoopCues = cues
            // Keep ALL cues for boundary calculation (even if not all are selected for looping)
            dialogueAutoLoopAllCues = allCues
            dialogueAutoLoopCount = loopCount.coerceAtLeast(1)
            dialogueAutoLoopPauseMs = pauseMs.coerceIn(200L, 5000L)
            dialogueAutoLoopCurrentIndex = 0
            dialogueAutoLoopCurrentIteration = 0
            isDialogueAutoLoopActive = true
            isDialoguePauseActive = false

            // Calculate smart boundary for the first cue
            val firstCue = cues[0]
            val safeEnd = calculateSafeEndMs(firstCue, allCues)

            currentConfig = currentConfig.copy(
                rangeStartMs = firstCue.startMs,
                rangeEndMs = safeEnd,
                loopCount = loopCount
            )
            currentLoopIteration = 0
            abLoopCompleted = false

            // Seek to the start of the first cue
            exoPlayer?.seekTo(firstCue.startMs)
            exoPlayer?.play()
            scheduleAbCheck()

            Timber.i("Dialogue auto-loop started: %d cues, x%d each, %dms pause, first cue end: %dms→%dms (smart boundary)",
                cues.size, loopCount, dialogueAutoLoopPauseMs, firstCue.endMs, safeEnd)
            updateNotification()
        } catch (e: Exception) {
            Timber.e(e, "Failed to set up dialogue auto-loop")
        }
    }

    /**
     * Calculate the safe end time for a dialogue cue.
     *
     * VAD-ENHANCED: With VAD refinement, segment endMs already reflects the
     * actual speech boundary from audio waveform analysis. The old 150ms gap
     * is no longer needed — VAD ensures endMs stops exactly where speech stops.
     *
     * We still use the next cue's startMs as an upper safety boundary to
     * guarantee zero overlap, but with a much smaller gap (30ms) since VAD
     * has already trimmed the endMs to the precise speech offset.
     *
     * If VAD was not run (old data), the larger inherent imprecision in
     * Whisper's endMs means the next-cue boundary is even more important.
     */
    private fun calculateSafeEndMs(cue: SubtitleCue, allCues: List<SubtitleCue>): Long {
        // Find the next cue in the full list (not filtered) by start time
        val nextCue = allCues
            .filter { it.startMs > cue.startMs }
            .minByOrNull { it.startMs }

        val safeEnd = if (nextCue != null) {
            // Use next cue's startMs as upper boundary — this is the REAL guarantee
            // that no next-dialogue audio will bleed through
            val boundaryFromNext = nextCue.startMs - CUE_BOUNDARY_GAP_MS
            // Take the earlier of: Whisper's endMs or next cue's start - gap
            // This handles cases where Whisper's endMs is correct (no overlap)
            minOf(cue.endMs, boundaryFromNext)
        } else {
            // Last cue — no next cue to reference, use Whisper's endMs
            cue.endMs
        }

        // Ensure end is after start (minimum 200ms dialogue duration)
        return safeEnd.coerceAtLeast(cue.startMs + 200L)
    }

    /**
     * Advance to the next dialogue cue in the auto-loop sequence.
     * Called by the A-B monitor when a cue has completed its loops.
     * If all cues are done, stops the auto-loop and continues normal playback.
     */
    private fun advanceToNextDialogueCue() {
        if (!isDialogueAutoLoopActive || dialogueAutoLoopCues.isEmpty()) return

        dialogueAutoLoopCurrentIndex++
        if (dialogueAutoLoopCurrentIndex >= dialogueAutoLoopCues.size) {
            // All dialogues done — stop auto-loop and continue playback
            isDialogueAutoLoopActive = false
            cancelAbMonitor()
            Timber.i("Dialogue auto-loop completed: all %d cues done", dialogueAutoLoopCues.size)
            return
        }

        val nextCue = dialogueAutoLoopCues[dialogueAutoLoopCurrentIndex]
        val safeEnd = calculateSafeEndMs(nextCue, dialogueAutoLoopAllCues)

        currentConfig = currentConfig.copy(
            rangeStartMs = nextCue.startMs,
            rangeEndMs = safeEnd,
            loopCount = dialogueAutoLoopCount
        )
        currentLoopIteration = 0
        abLoopCompleted = false

        // Silence gap before advancing to next dialogue — like a human pause.
        // Player is already paused by checkABPositionAndReschedule.
        if (dialogueAutoLoopPauseMs > 0) {
            isDialoguePauseActive = true
            Timber.d("Dialogue pause: %dms silence before advancing to next cue", dialogueAutoLoopPauseMs)
            abHandler.postDelayed({
                try {
                    isDialoguePauseActive = false
                    isABSeeking = true
                    exoPlayer?.seekTo(nextCue.startMs)
                    exoPlayer?.play()
                    scheduleAbCheck()
                    Timber.d("Dialogue auto-loop: advancing to cue %d/%d", dialogueAutoLoopCurrentIndex + 1, dialogueAutoLoopCues.size)
                } catch (e: Exception) {
                    isABSeeking = false
                    isDialoguePauseActive = false
                    Timber.e(e, "Failed to advance after pause")
                }
            }, dialogueAutoLoopPauseMs)
        } else {
            isABSeeking = true
            exoPlayer?.seekTo(nextCue.startMs)
            exoPlayer?.play()
            scheduleAbCheck()
            Timber.d("Dialogue auto-loop: advancing to cue %d/%d", dialogueAutoLoopCurrentIndex + 1, dialogueAutoLoopCues.size)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // A-B POSITION MONITOR — SMART SCHEDULING (no polling)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Smart A-B monitoring using Handler instead of coroutine polling.
     *
     * Instead of checking every 200ms (18,000 wake-ups/hour), this approach:
     *  - Checks every 5 seconds when far from the B marker
     *  - Switches to every 500ms when within 5 seconds of B
     *  - This reduces wake-ups by ~90% for typical usage
     *
     * Why Handler instead of coroutine delay?
     *  - Handler.postDelayed is more lightweight than launching a coroutine
     *  - No coroutine scope overhead
     *  - Automatically runs on the main thread (where ExoPlayer lives)
     *  - Easier to cancel — just removeCallbacks
     */
    private fun scheduleAbCheck() {
        cancelAbMonitor()
        abCheckRunnable = Runnable { checkABPositionAndReschedule() }
        // First check after 1 second (not immediate — player is still buffering)
        abHandler.postDelayed(abCheckRunnable!!, 1000L)
    }

    private fun cancelAbMonitor() {
        abCheckRunnable?.let { abHandler.removeCallbacks(it) }
        abCheckRunnable = null
    }

    /**
     * Check if playback has reached the B marker, then schedule the next check.
     *
     * Adaptive scheduling:
     *  - Far from B (>5s): next check in 5 seconds (minimal CPU)
     *  - Near B (<5s): next check in 500ms (precise loop detection)
     */
    /**
     * Dialogue pause duration — 1 second of silence after each dialogue ends,
     * like a human takes a pause after saying something. This prevents the
     * next dialogue's words from bleeding into the current one.
     */
    private val DIALOGUE_PAUSE_MS = 1000L

    private fun checkABPositionAndReschedule() {
        if (!currentConfig.hasABLoop || abLoopCompleted) return

        val currentPosition = exoPlayer?.currentPosition ?: return
        val distanceToB = currentConfig.rangeEndMs - currentPosition

        if (currentPosition >= currentConfig.rangeEndMs) {
            // Reached B — immediately pause to prevent next dialogue from bleeding in.
            // The user wants: dialogue ends → clean cut → 1 second silence → repeat.
            // Like a human takes a pause after saying something.
            exoPlayer?.pause()
            currentLoopIteration++
            Timber.d("A-B: reached B at %dms, iteration %d/%d — pausing for %dms",
                currentConfig.rangeEndMs, currentLoopIteration, currentConfig.loopCount, DIALOGUE_PAUSE_MS)

            if (currentLoopIteration < currentConfig.loopCount) {
                // More loops → wait 1 second of silence, then seek back to A and resume
                pauseForDialogueRepeat()
            } else {
                // Loop complete → wait 1 second then continue from B to end
                Timber.d("A-B loop done after %d iterations — pausing then continuing", currentConfig.loopCount)
                abHandler.postDelayed({
                    abLoopCompleted = true
                    isABSeeking = true
                    exoPlayer?.seekTo(currentConfig.rangeEndMs)
                    exoPlayer?.play()
                }, DIALOGUE_PAUSE_MS)
            }
            return
        }

        // Schedule next check with 3-tier adaptive interval
        val nextDelay = when {
            distanceToB <= AB_NEAR_THRESHOLD_MS -> AB_CHECK_NEAR_MS   // <3s: precise checks
            distanceToB <= AB_MID_THRESHOLD_MS -> AB_CHECK_MID_MS     // 3-10s: medium checks
            else -> AB_CHECK_FAR_MS                                      // >10s: rare checks
        }

        abCheckRunnable = Runnable { checkABPositionAndReschedule() }
        abHandler.postDelayed(abCheckRunnable!!, nextDelay)
    }

    // ══════════════════════════════════════════════════════════════════════
    // POSITION UPDATE — For transcript sync in UI
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Start periodic position updates for the transcript sync feature.
     *
     * Adaptive interval for minimal resource usage:
     * - 1000ms during normal playback (no A-B loop active)
     * - 500ms when A-B loop is active (need precise subtitle sync)
     *
     * This reduces Handler wake-ups by 50% during long background sessions
     * (1,800/hour instead of 3,600/hour) with zero impact on subtitle sync
     * quality since A-B loops are the only case needing sub-second precision.
     */
    private fun startPositionUpdate() {
        cancelPositionUpdate()
        positionUpdateRunnable = object : Runnable {
            override fun run() {
                try {
                    val pos = exoPlayer?.currentPosition ?: 0L
                    val dur = exoPlayer?.duration ?: 0L
                    val playing = exoPlayer?.isPlaying == true
                    updateState(playing, pos, currentVideoPath, dur)
                } catch (_: Exception) {
                    // Keep last known state
                }
                // Adaptive: faster updates when A-B loop active, slower for normal playback
                val interval = if (currentConfig.hasABLoop && !abLoopCompleted) 500L else 1000L
                positionHandler.postDelayed(this, interval)
            }
        }
        positionHandler.post(positionUpdateRunnable!!)
    }

    private fun cancelPositionUpdate() {
        positionUpdateRunnable?.let { positionHandler.removeCallbacks(it) }
        positionUpdateRunnable = null
    }

    // ══════════════════════════════════════════════════════════════════════
    // VIDEO PATHS LOADING
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun loadVideoPaths() {
        try {
            val videos = videoRepository.getVideos().first()
            if (videos.isEmpty()) {
                Timber.w("No videos found in repository — retrying with fresh scan")
            }
            videoPaths = videos.map { it.path }
            videoTitles = videos.associate { it.path to it.title }
        } catch (e: NoSuchElementException) {
            Timber.w(e, "Video list Flow was empty — using empty paths")
            videoPaths = emptyList()
            videoTitles = emptyMap()
        } catch (e: Exception) {
            Timber.e(e, "Failed to load video paths")
            videoPaths = emptyList()
            videoTitles = emptyMap()
        }
    }

    private suspend fun resolvePlaybackUri(videoPath: String): String {
        return try {
            videoRepository.getContentUriForPath(videoPath) ?: videoPath
        } catch (_: Exception) { videoPath }
    }

    // ══════════════════════════════════════════════════════════════════════
    // WAKELOCK — Battery Optimized
    // ══════════════════════════════════════════════════════════════════════

    private fun setupWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HorizonLoop::PlaybackWakeLock")
            .apply { setReferenceCounted(false) }
    }

    private fun acquireWakeLock() {
        if (isWakeLockHeld) return
        try {
            wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
            isWakeLockHeld = true
        } catch (e: Exception) { Timber.e(e, "Failed to acquire WakeLock") }
    }

    private fun releaseWakeLock() {
        if (isWakeLockHeld) {
            try { wakeLock?.release() } catch (_: RuntimeException) {}
            isWakeLockHeld = false
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // NOTIFICATION
    // ══════════════════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildLoadingNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_loading))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(MediaNotificationCompat.MediaStyle())
            .build()
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val previousIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_PLAY_PREVIOUS).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIntent = PendingIntent.getBroadcast(
            this, 1, Intent(ACTION_TOGGLE_PLAYBACK).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextIntent = PendingIntent.getBroadcast(
            this, 2, Intent(ACTION_PLAY_NEXT).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getBroadcast(
            this, 3, Intent(ACTION_STOP).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isPlaying = exoPlayer?.isPlaying == true
        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseLabel = if (isPlaying) getString(R.string.notification_pause) else getString(R.string.notification_play)

        val title = videoTitles[currentConfig.videoPath]
            ?: currentConfig.videoPath.substringAfterLast("/", "Unknown")

        val speedLabel = SpeedPresets.closestTo(currentConfig.speed).label
        val loopInfo = when {
            currentConfig.isNormalPlayback -> ""
            currentConfig.hasABLoop -> "AB loop ${currentLoopIteration + 1}/${currentConfig.loopCount}"
            currentConfig.loopCount > 1 -> "Loop ${currentLoopIteration + 1}/${currentConfig.loopCount}"
            else -> ""
        }
        val contentText = "$speedLabel $loopInfo".trim()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSubText(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_skip_previous, getString(R.string.notification_previous), previousIntent)
            .addAction(playPauseIcon, playPauseLabel, toggleIntent)
            .addAction(R.drawable.ic_skip_next, getString(R.string.notification_next), nextIntent)
            .addAction(R.drawable.ic_close, getString(R.string.notification_stop), stopIntent)
            .setDeleteIntent(stopIntent)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
                    .setCancelButtonIntent(stopIntent)
            )
            .build()
    }

    private fun updateNotification() {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) { Timber.e(e, "Failed to update notification") }
    }

    private fun updateNotificationWithError(errorMessage: String?) {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_error_title))
                .setContentText(errorMessage ?: getString(R.string.notification_error_unknown))
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(false)
                .build()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) { Timber.e(e, "Failed to update notification with error") }
    }

    // ══════════════════════════════════════════════════════════════════════
    // BROADCAST RECEIVER
    // ══════════════════════════════════════════════════════════════════════

    private fun registerNotificationReceiver() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_TOGGLE_PLAYBACK)
            addAction(ACTION_PLAY_NEXT)
            addAction(ACTION_PLAY_PREVIOUS)
            addAction(ACTION_STOP)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(notificationActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(notificationActionReceiver, filter)
            }
            isReceiverRegistered = true
        } catch (e: Exception) { Timber.e(e, "Failed to register receiver") }
    }

    private fun unregisterNotificationReceiver() {
        if (!isReceiverRegistered) return
        try { unregisterReceiver(notificationActionReceiver) }
        catch (_: IllegalArgumentException) {}
        catch (e: Exception) { Timber.w(e, "Error unregistering receiver") }
        isReceiverRegistered = false
    }
}
