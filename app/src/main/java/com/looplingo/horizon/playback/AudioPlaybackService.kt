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
        const val EXTRA_VIDEO_PATH = "video_path"
        const val EXTRA_SEEK_POSITION_MS = "seek_position_ms"

        private const val WAKELOCK_TIMEOUT_MS = 3 * 60 * 60 * 1000L  // 3 hours (was 4)
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2000L

        // A-B monitoring: check interval increases as we get closer to B
        private const val AB_CHECK_FAR_MS = 5000L       // >5s away: check every 5s
        private const val AB_CHECK_NEAR_MS = 500L       // <5s away: check every 500ms
        private const val AB_NEAR_THRESHOLD_MS = 5000L  // Switch to frequent checks within 5s of B

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

        /** Update static playback state — called from the service instance. */
        fun updateState(playing: Boolean, position: Long, videoPath: String) {
            isPlaying = playing
            currentPositionMs = position
            currentVideoPath = videoPath
        }

        /** Reset static state when service stops. */
        fun resetState() {
            isPlaying = false
            currentPositionMs = 0L
            currentVideoPath = ""
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

        fun stopService(context: Context) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
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

    // Handler-based A-B monitoring (replaces wasteful coroutine polling)
    private val abHandler = Handler(Looper.getMainLooper())
    private var abCheckRunnable: Runnable? = null

    // Position update handler for transcript sync (lightweight, 500ms interval)
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
                startForeground(NOTIFICATION_ID, buildLoadingNotification())
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
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

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
     * Handle a seek request from the transcript UI.
     * If the requested video matches the currently playing video, just seek.
     * Otherwise, start playback of the requested video and seek to the position.
     */
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
            // Different video — start it and seek
            serviceScope.launch {
                try {
                    loadVideoPaths()
                    startPlayback(videoPath)
                    // Wait for player to be ready, then seek
                    delay(500)
                    exoPlayer?.seekTo(positionMs)
                    Timber.d("Started new video and seeked to %dms", positionMs)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to start and seek")
                }
            }
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
            } else {
                // Release WakeLock when paused — saves battery
                releaseWakeLock()
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
     *  - A-B Loop: if iterations < loopCount → restart at A; else continue from B to end
     *  - Full Loop (no A-B, loopCount>1): if iterations < loopCount → restart from 0; else next/stop
     */
    private fun handlePlaybackEnded() {
        currentLoopIteration++

        if (currentConfig.isNormalPlayback) {
            // No looping configured — just play next or stop
            advanceToNextVideo()
            return
        }

        if (currentConfig.hasABLoop) {
            // A-B Loop mode
            if (currentLoopIteration < currentConfig.loopCount) {
                // Still looping the A-B segment
                Timber.d("A-B loop iteration %d/%d", currentLoopIteration, currentConfig.loopCount)
                seekToA()
            } else {
                // A-B loop completed — continue playing from B to end of video
                Timber.d("A-B loop completed after %d iterations, continuing to end", currentConfig.loopCount)
                abLoopCompleted = true
                cancelAbMonitor()
                // The video will naturally reach STATE_ENDED again when it finishes
                advanceToNextVideo()
            }
        } else {
            // Full video loop (no A-B range)
            if (currentLoopIteration < currentConfig.loopCount) {
                Timber.d("Full loop iteration %d/%d", currentLoopIteration, currentConfig.loopCount)
                exoPlayer?.seekTo(0)
                exoPlayer?.play()
            } else {
                Timber.d("Full loop completed after %d iterations", currentConfig.loopCount)
                advanceToNextVideo()
            }
        }
    }

    private fun seekToA() {
        try {
            exoPlayer?.seekTo(currentConfig.rangeStartMs)
            exoPlayer?.play()
        } catch (e: Exception) {
            Timber.e(e, "Failed to seek to A position")
        }
        updateNotification()
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
    private fun checkABPositionAndReschedule() {
        if (!currentConfig.hasABLoop || abLoopCompleted) return

        val currentPosition = exoPlayer?.currentPosition ?: return
        val distanceToB = currentConfig.rangeEndMs - currentPosition

        if (currentPosition >= currentConfig.rangeEndMs) {
            // Reached B — handle the loop iteration
            currentLoopIteration++
            Timber.d("A-B: reached B at %dms, iteration %d/%d", currentConfig.rangeEndMs, currentLoopIteration, currentConfig.loopCount)

            if (currentLoopIteration < currentConfig.loopCount) {
                // More loops → seek back to A and restart monitoring
                seekToA()
                scheduleAbCheck()
            } else {
                // Loop complete → continue from B to end
                Timber.d("A-B loop done, continuing playback from B to end")
                abLoopCompleted = true
                // No more scheduling needed
            }
            return
        }

        // Schedule next check with adaptive interval
        val nextDelay = if (distanceToB <= AB_NEAR_THRESHOLD_MS) {
            AB_CHECK_NEAR_MS   // Near B: check frequently
        } else {
            AB_CHECK_FAR_MS    // Far from B: check rarely
        }

        abCheckRunnable = Runnable { checkABPositionAndReschedule() }
        abHandler.postDelayed(abCheckRunnable!!, nextDelay)
    }

    // ══════════════════════════════════════════════════════════════════════
    // POSITION UPDATE — For transcript sync in UI
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Start periodic position updates for the transcript sync feature.
     * This is a lightweight Handler-based 500ms update that writes the
     * current position to a static volatile field, which the
     * PlaybackSettingsFragment reads for subtitle highlighting.
     *
     * Only active when playback is actually occurring.
     */
    private fun startPositionUpdate() {
        cancelPositionUpdate()
        positionUpdateRunnable = object : Runnable {
            override fun run() {
                try {
                    val pos = exoPlayer?.currentPosition ?: 0L
                    val playing = exoPlayer?.isPlaying == true
                    updateState(playing, pos, currentVideoPath)
                } catch (_: Exception) {
                    // Keep last known state
                }
                positionHandler.postDelayed(this, 500L)
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
            videoPaths = videos.map { it.path }
            videoTitles = videos.associate { it.path to it.title }
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
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LoopLingo::PlaybackWakeLock")
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
        } catch (e: Exception) { Timber.e(e, "Failed to register receiver") }
    }

    private fun unregisterNotificationReceiver() {
        try { unregisterReceiver(notificationActionReceiver) }
        catch (_: IllegalArgumentException) {}
        catch (e: Exception) { Timber.w(e, "Error unregistering receiver") }
    }
}
