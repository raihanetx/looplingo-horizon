package com.looplingo.horizon.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
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
import com.looplingo.horizon.model.LoopMode
import com.looplingo.horizon.model.PlaybackConfig
import com.looplingo.horizon.model.PlaybackConfigValidator
import com.looplingo.horizon.model.StartAction
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
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that plays audio extracted from video files using Media3 ExoPlayer.
 *
 * Key design decisions:
 *  - ExoPlayer repeatMode is always REPEAT_MODE_OFF. All looping is handled
 *    manually via onPlaybackStateChanged(STATE_ENDED) so we have full control
 *    over iteration counting, A-B ranges, and auto-advance logic.
 *  - WakeLock is tracked with an [isWakeLockHeld] flag to prevent double-release crashes.
 *    For LOOP_INFINITE mode, WakeLock uses an indefinite timeout (renewed every hour).
 *    For finite modes, WakeLock uses a 4-hour safety timeout.
 *  - Playback config is loaded from Room via [PlaybackRepository] on each new video
 *    and validated with [PlaybackConfigValidator] before use.
 *  - A-B Pin mode uses a periodic coroutine to monitor playback position since
 *    ExoPlayer's STATE_ENDED only fires at the end of the full file.
 *  - Notification uses Media3 MediaStyle with MediaSession for lock screen controls,
 *    Android Auto compatibility, and proper system integration.
 *  - Migrated from ExoPlayer 2.x (com.google.android.exoplayer2) to
 *    Media3 (androidx.media3) for long-term support and active development.
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
        const val EXTRA_VIDEO_PATH = "video_path"

        // WakeLock management constants
        private const val WAKELOCK_FINITE_TIMEOUT_MS = 4 * 60 * 60 * 1000L  // 4 hours
        private const val WAKELOCK_RENEWAL_INTERVAL_MS = 60 * 60 * 1000L    // 1 hour

        // A-B Pin polling interval — 200ms is responsive without burning CPU
        private const val AB_PIN_POLL_INTERVAL_MS = 200L

        // Max automatic retries on ExoPlayer error before giving up
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2000L

        fun startService(context: Context, videoPath: String) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_VIDEO_PATH, videoPath)
            }
            try {
                context.startForegroundService(intent)
            } catch (e: IllegalStateException) {
                Timber.e(e, "Cannot start foreground service — app may be in background restriction")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start AudioPlaybackService")
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    // ── Player & System ──────────────────────────────────────────────────
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isWakeLockHeld = false
    private var serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    // ── Playback State ───────────────────────────────────────────────────
    private var currentConfig: PlaybackConfig = PlaybackConfig(videoPath = "")
    private var currentLoopIteration: Int = 0
    private var currentVideoIndex: Int = -1
    private var videoPaths: List<String> = emptyList()
    private var videoTitles: Map<String, String> = emptyMap()
    private var retryAttemptCount: Int = 0

    // ── Coroutine Jobs (for cancellation) ────────────────────────────────
    private var abPinMonitorJob: Job? = null
    private var wakeLockRenewalJob: Job? = null

    // ── Repositories (Hilt-injected) ─────────────────────────────────────
    @Inject
    lateinit var playbackRepository: PlaybackRepository

    @Inject
    lateinit var videoRepository: VideoRepository

    // ── Broadcast Receiver for notification actions ───────────────────────
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

        // CRITICAL: Android requires startForeground() within 5 seconds of
        // startForegroundService(). We must call it immediately, even before
        // the coroutine finishes loading the config. Show a "loading" notification.
        if (intent?.action == ACTION_START) {
            try {
                startForeground(NOTIFICATION_ID, buildLoadingNotification())
                Timber.d("startForeground() called immediately to meet 5-second requirement")
            } catch (e: Exception) {
                Timber.e(e, "Failed to call startForeground() — service may be killed")
            }
        }

        if (intent == null) {
            Timber.w("onStartCommand called with null intent — service restarted by system")
            return START_STICKY
        }

        when (intent.action) {
            ACTION_START -> {
                val videoPath = intent.getStringExtra(EXTRA_VIDEO_PATH)
                if (videoPath.isNullOrBlank()) {
                    Timber.w("ACTION_START received with null/blank videoPath — ignoring")
                    return START_STICKY
                }
                Timber.d("ACTION_START for: %s", videoPath)
                serviceScope.launch {
                    try {
                        loadVideoPaths()
                        startPlayback(videoPath)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to start playback for: %s", videoPath)
                        updateNotificationWithError("Failed to start playback: ${e.message}")
                    }
                }
            }
            ACTION_STOP -> {
                Timber.d("ACTION_STOP received")
                stopSelf()
            }
            else -> {
                Timber.w("Unknown action received: %s", intent.action)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Timber.i("Task removed by user — stopping playback service")
        cleanup()
        stopSelf()
    }

    private fun cleanup() {
        try {
            cancelAbPinMonitor()
            cancelWakeLockRenewal()
            unregisterNotificationReceiver()
            serviceJob.cancel()
            exoPlayer?.removeListener(playerListener)
            exoPlayer?.release()
            exoPlayer = null
            mediaSession?.release()
            mediaSession = null
            releaseWakeLock()
            Timber.i("AudioPlaybackService cleaned up")
        } catch (e: Exception) {
            Timber.e(e, "Error during service cleanup")
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // EXOPLAYER SETUP (Media3)
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
            .setHandleAudioBecomingNoisy(true)   // Pause when headphones disconnect
            .build()

        exoPlayer?.addListener(playerListener)
        Timber.d("Media3 ExoPlayer initialized with video track disabled")
    }

    // ══════════════════════════════════════════════════════════════════════
    // MEDIA SESSION — enables lock screen, Bluetooth, and Android Auto controls
    // Uses Media3 MediaSession (replaces MediaSessionCompat + MediaSessionConnector)
    // ══════════════════════════════════════════════════════════════════════

    private fun setupMediaSession() {
        mediaSession = MediaSession.Builder(this, exoPlayer!!)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    // Allow all controllers to connect with full playback control
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(
                            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                                .build()
                        )
                        .build()
                }
            })
            .build()

        Timber.d("Media3 MediaSession initialized and connected to ExoPlayer")
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
                    retryAttemptCount = 0  // Reset retry counter on successful preparation
                    updateNotification()
                }
                Player.STATE_BUFFERING -> {
                    Timber.d("Player buffering...")
                    updateNotification()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                acquireWakeLock()
                updateNotification()
            } else {
                cancelWakeLockRenewal()
                updateNotification()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Timber.e(error, "ExoPlayer error: errorCode=%d, message=%s", error.errorCode, error.message)
            handlePlayerError(error)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ERROR RECOVERY
    // ══════════════════════════════════════════════════════════════════════

    private fun handlePlayerError(error: PlaybackException) {
        retryAttemptCount++
        Timber.w("Player error (attempt %d/%d): %s", retryAttemptCount, MAX_RETRY_ATTEMPTS, error.message)

        if (retryAttemptCount <= MAX_RETRY_ATTEMPTS) {
            val backoffDelay = RETRY_DELAY_MS * (1L shl (retryAttemptCount - 1))
            Timber.i("Retrying playback in %d ms...", backoffDelay)
            serviceScope.launch {
                delay(backoffDelay)
                if (isActive) {
                    try {
                        exoPlayer?.prepare()
                        exoPlayer?.play()
                    } catch (e: Exception) {
                        Timber.e(e, "Retry attempt %d failed", retryAttemptCount)
                    }
                }
            }
        } else {
            Timber.e("Max retry attempts (%d) reached — giving up on current video", MAX_RETRY_ATTEMPTS)
            updateNotificationWithError("Playback failed after $MAX_RETRY_ATTEMPTS attempts: ${error.message}")

            if (currentConfig.autoAdvance && currentVideoIndex < videoPaths.size - 1) {
                Timber.i("Auto-advancing to next video after error")
                serviceScope.launch {
                    delay(1000)
                    advanceToNextVideo()
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // PLAYBACK CONTROL
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun startPlayback(videoPath: String) {
        Timber.i("Starting playback for: %s", videoPath)

        currentLoopIteration = 0
        retryAttemptCount = 0
        cancelAbPinMonitor()
        currentVideoIndex = videoPaths.indexOf(videoPath)

        if (currentVideoIndex < 0) {
            Timber.w("Video path not found in loaded list — reloading paths")
            loadVideoPaths()
            currentVideoIndex = videoPaths.indexOf(videoPath)
        }

        val savedConfig = try {
            playbackRepository.getConfigForVideo(videoPath)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load playback config from Room — using defaults")
            null
        }

        currentConfig = savedConfig ?: PlaybackConfig(videoPath = videoPath)

        val validationResult = PlaybackConfigValidator.validate(currentConfig)
        if (!validationResult.isValid) {
            Timber.w("Invalid playback config: %s — applying corrections", validationResult.errors)
            currentConfig = PlaybackConfigValidator.sanitize(currentConfig)
        }

        Timber.d(
            "Playback config: mode=%s, loopCount=%d, range=[%d,%d]ms, startAction=%s, autoAdvance=%s",
            currentConfig.loopMode, currentConfig.loopCount,
            currentConfig.rangeStartMs, currentConfig.rangeEndMs,
            currentConfig.startAction, currentConfig.autoAdvance
        )

        val playbackUri = resolvePlaybackUri(videoPath)
        Timber.d("Using URI for playback: %s", playbackUri)

        try {
            val mediaSource = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(this))
                .createMediaSource(MediaItem.fromUri(playbackUri))

            exoPlayer?.apply {
                setMediaSource(mediaSource)
                prepare()
                repeatMode = Player.REPEAT_MODE_OFF

                if (currentConfig.loopMode == LoopMode.A_B_PIN && currentConfig.rangeStartMs > 0) {
                    seekTo(currentConfig.rangeStartMs)
                }

                if (currentConfig.startAction == StartAction.AUTO_PLAY) {
                    play()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to set up ExoPlayer media source for: %s", playbackUri)
            updateNotificationWithError("Cannot play this file: ${e.message}")
            return
        }

        if (currentConfig.loopMode == LoopMode.A_B_PIN && currentConfig.rangeEndMs > 0) {
            startAbPinMonitor()
        }

        updateNotification()
    }

    private suspend fun resolvePlaybackUri(videoPath: String): String {
        return try {
            val contentUri = videoRepository.getContentUriForPath(videoPath)
            if (!contentUri.isNullOrEmpty()) {
                Timber.d("Using content URI from Room: %s", contentUri)
                contentUri
            } else {
                Timber.d("No content URI in Room, using raw path")
                videoPath
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to query Room for URI resolution — using raw path")
            videoPath
        }
    }

    private fun handlePlaybackEnded() {
        currentLoopIteration++
        Timber.d(
            "Playback ended. loopMode=%s, iteration=%d, loopCount=%d, autoAdvance=%s",
            currentConfig.loopMode, currentLoopIteration, currentConfig.loopCount, currentConfig.autoAdvance
        )

        when (currentConfig.loopMode) {
            LoopMode.PLAY_ONCE -> {
                if (currentConfig.autoAdvance) advanceToNextVideo() else stopSelf()
            }
            LoopMode.LOOP_X_TIMES -> {
                if (currentLoopIteration >= currentConfig.loopCount) {
                    if (currentConfig.autoAdvance) advanceToNextVideo() else stopSelf()
                } else {
                    restartCurrentRange()
                }
            }
            LoopMode.LOOP_INFINITE -> restartCurrentRange()
            LoopMode.FLOW -> advanceToNextVideo()
            LoopMode.AUTO_LOOP -> {
                if (currentLoopIteration >= currentConfig.loopCount) {
                    advanceToNextVideo()
                } else {
                    restartCurrentRange()
                }
            }
            LoopMode.A_B_PIN -> {
                restartCurrentRange()
            }
        }
    }

    private fun restartCurrentRange() {
        val seekPosition = when {
            currentConfig.loopMode == LoopMode.A_B_PIN && currentConfig.rangeStartMs > 0 -> currentConfig.rangeStartMs
            currentConfig.rangeStartMs > 0 -> currentConfig.rangeStartMs
            else -> 0L
        }
        Timber.d("Restarting range at position: %d ms (iteration %d)", seekPosition, currentLoopIteration)

        try {
            exoPlayer?.seekTo(seekPosition)
            exoPlayer?.play()
        } catch (e: Exception) {
            Timber.e(e, "Failed to restart playback range")
        }
        updateNotification()
    }

    private fun advanceToNextVideo() {
        if (videoPaths.isEmpty()) {
            Timber.w("No video paths loaded — cannot advance")
            stopSelf()
            return
        }

        if (currentVideoIndex < videoPaths.size - 1) {
            val nextPath = videoPaths[currentVideoIndex + 1]
            Timber.i("Advancing to next video [%d → %d]: %s", currentVideoIndex, currentVideoIndex + 1, nextPath)
            serviceScope.launch {
                try {
                    startPlayback(nextPath)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to advance to next video")
                    stopSelf()
                }
            }
        } else {
            Timber.i("Reached end of video list (index %d of %d), stopping service", currentVideoIndex, videoPaths.size)
            stopSelf()
        }
    }

    private fun playPrevious() {
        if (videoPaths.isEmpty()) {
            Timber.w("No video paths loaded — cannot go to previous")
            return
        }

        if (currentVideoIndex > 0) {
            val prevPath = videoPaths[currentVideoIndex - 1]
            Timber.i("Playing previous video [%d → %d]: %s", currentVideoIndex, currentVideoIndex - 1, prevPath)
            serviceScope.launch {
                try {
                    startPlayback(prevPath)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to play previous video")
                }
            }
        } else {
            Timber.d("At start of list, restarting current video")
            currentLoopIteration = 0
            try {
                exoPlayer?.seekTo(0)
                exoPlayer?.play()
            } catch (e: Exception) {
                Timber.e(e, "Failed to restart current video")
            }
        }
    }

    private fun playNext() {
        advanceToNextVideo()
    }

    private fun togglePlayback() {
        try {
            if (exoPlayer?.isPlaying == true) {
                exoPlayer?.pause()
                Timber.d("Playback paused by user")
            } else {
                exoPlayer?.play()
                Timber.d("Playback resumed by user")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to toggle playback state")
        }
        updateNotification()
    }

    // ══════════════════════════════════════════════════════════════════════
    // A-B PIN POSITION MONITOR
    // ══════════════════════════════════════════════════════════════════════

    private fun startAbPinMonitor() {
        cancelAbPinMonitor()
        Timber.d("Starting A-B Pin position monitor (interval: %d ms)", AB_PIN_POLL_INTERVAL_MS)

        abPinMonitorJob = serviceScope.launch(Dispatchers.Main) {
            while (isActive) {
                checkABPinPosition()
                delay(AB_PIN_POLL_INTERVAL_MS)
            }
        }
    }

    private fun cancelAbPinMonitor() {
        abPinMonitorJob?.cancel()
        abPinMonitorJob = null
    }

    private fun checkABPinPosition() {
        if (currentConfig.loopMode != LoopMode.A_B_PIN) return
        if (currentConfig.rangeEndMs <= 0) return

        val currentPosition = exoPlayer?.currentPosition ?: return
        if (currentPosition >= currentConfig.rangeEndMs) {
            Timber.d("A-B Pin: reached B marker at %d ms (current: %d ms), iteration %d", currentConfig.rangeEndMs, currentPosition, currentLoopIteration)
            currentLoopIteration++

            if (currentConfig.loopCount > 0 && currentLoopIteration >= currentConfig.loopCount) {
                cancelAbPinMonitor()
                if (currentConfig.autoAdvance) advanceToNextVideo() else stopSelf()
            } else {
                restartCurrentRange()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // VIDEO PATHS LOADING
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun loadVideoPaths() {
        try {
            val videos = videoRepository.getVideos().first()
            videoPaths = videos.map { it.path }
            videoTitles = videos.associate { it.path to it.title }
            Timber.d("Loaded %d video paths for navigation", videoPaths.size)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load video paths from repository")
            videoPaths = emptyList()
            videoTitles = emptyMap()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // WAKELOCK
    // ══════════════════════════════════════════════════════════════════════

    private fun setupWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LoopLingo::PlaybackWakeLock"
        ).apply { setReferenceCounted(false) }
        Timber.d("WakeLock initialized")
    }

    private fun acquireWakeLock() {
        if (isWakeLockHeld) {
            if (currentConfig.loopMode == LoopMode.LOOP_INFINITE) {
                startWakeLockRenewal()
            }
            return
        }

        try {
            if (currentConfig.loopMode == LoopMode.LOOP_INFINITE) {
                wakeLock?.acquire()
                startWakeLockRenewal()
                Timber.d("WakeLock acquired (indefinite for LOOP_INFINITE)")
            } else {
                wakeLock?.acquire(WAKELOCK_FINITE_TIMEOUT_MS)
                Timber.d("WakeLock acquired (timeout: %d ms)", WAKELOCK_FINITE_TIMEOUT_MS)
            }
            isWakeLockHeld = true
        } catch (e: Exception) {
            Timber.e(e, "Failed to acquire WakeLock")
        }
    }

    private fun releaseWakeLock() {
        cancelWakeLockRenewal()
        if (isWakeLockHeld) {
            try {
                wakeLock?.release()
            } catch (e: RuntimeException) {
                Timber.w(e, "WakeLock was already released — safe to ignore")
            }
            isWakeLockHeld = false
            Timber.d("WakeLock released")
        }
    }

    private fun startWakeLockRenewal() {
        cancelWakeLockRenewal()
        wakeLockRenewalJob = serviceScope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(WAKELOCK_RENEWAL_INTERVAL_MS)
                if (isWakeLockHeld && exoPlayer?.isPlaying == true) {
                    Timber.d("Renewing WakeLock for LOOP_INFINITE mode")
                    try {
                        wakeLock?.release()
                        isWakeLockHeld = false
                        wakeLock?.acquire()
                        isWakeLockHeld = true
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to renew WakeLock — re-acquiring")
                        try {
                            wakeLock?.acquire()
                            isWakeLockHeld = true
                        } catch (e2: Exception) {
                            Timber.e(e2, "Failed to re-acquire WakeLock after renewal failure")
                        }
                    }
                }
            }
        }
    }

    private fun cancelWakeLockRenewal() {
        wakeLockRenewalJob?.cancel()
        wakeLockRenewalJob = null
    }

    // ══════════════════════════════════════════════════════════════════════
    // NOTIFICATION — MediaStyle with playback controls
    // Uses Media3 session token for MediaStyle integration
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

    /**
     * Builds a minimal "loading" notification shown immediately when the service
     * starts, before the config is loaded and playback begins.
     */
    private fun buildLoadingNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_loading))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(
                NotificationCompat.MediaStyle()
            )
            .build()
    }

    /**
     * Builds the media-style notification with playback controls.
     * Uses Media3 MediaSession for lock screen and Android Auto integration.
     * Shows: track title, loop mode, iteration counter, play/pause/next/previous.
     */
    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val previousIntent = PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_PLAY_PREVIOUS).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = PendingIntent.getBroadcast(
            this, 1,
            Intent(ACTION_TOGGLE_PLAYBACK).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = PendingIntent.getBroadcast(
            this, 2,
            Intent(ACTION_PLAY_NEXT).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getBroadcast(
            this, 3,
            Intent(ACTION_STOP).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isPlaying = exoPlayer?.isPlaying == true
        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseLabel = if (isPlaying) getString(R.string.notification_pause) else getString(R.string.notification_play)

        val title = videoTitles[currentConfig.videoPath]
            ?: currentConfig.videoPath.substringAfterLast("/", "Unknown")
        val modeLabel = currentConfig.loopMode.displayBadge
        val iterationInfo = if (currentConfig.loopMode != LoopMode.PLAY_ONCE && currentConfig.loopMode != LoopMode.FLOW) {
            " (loop ${currentLoopIteration + 1})"
        } else {
            ""
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("$modeLabel$iterationInfo")
            .setSubText(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Media actions in compact view (play/pause in the middle)
            .addAction(R.drawable.ic_skip_previous, getString(R.string.notification_previous), previousIntent)
            .addAction(playPauseIcon, playPauseLabel, toggleIntent)
            .addAction(R.drawable.ic_skip_next, getString(R.string.notification_next), nextIntent)
            // Dismiss action for when paused
            .addAction(R.drawable.ic_close, getString(R.string.notification_stop), stopIntent)
            .setDeleteIntent(stopIntent)
            // MediaStyle: show 3 actions in compact view, link to MediaSession
            .setStyle(
                NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
                    .setCancelButtonIntent(stopIntent)
            )
            .build()
    }

    private fun updateNotification() {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Timber.e(e, "Failed to update notification")
        }
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
        } catch (e: Exception) {
            Timber.e(e, "Failed to update notification with error state")
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // BROADCAST RECEIVER
    // ══════════════════════════════════════════════════════════════════════

    private fun registerNotificationReceiver() {
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
        } catch (e: Exception) {
            Timber.e(e, "Failed to register notification action receiver")
        }
    }

    private fun unregisterNotificationReceiver() {
        try {
            unregisterReceiver(notificationActionReceiver)
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "Receiver was not registered — safe to ignore")
        } catch (e: Exception) {
            Timber.w(e, "Unexpected error unregistering receiver")
        }
    }
}
