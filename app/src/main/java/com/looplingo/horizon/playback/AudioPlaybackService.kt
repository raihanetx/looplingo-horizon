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
 * Simplified loop logic:
 *  - No loop modes. Behavior is determined by PlaybackConfig values:
 *    - Normal: no A-B range, loopCount=1 → play full video once
 *    - A-B Loop: A→B segment, loop N times, then continue to end of video
 *    - Full Loop: no A-B range, loopCount>1 → loop entire video N times
 *  - Speed control via ExoPlayer.setPlaybackParameters()
 *  - A-B position monitor polls every 200ms to detect when playback reaches B
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

        private const val WAKELOCK_FINITE_TIMEOUT_MS = 4 * 60 * 60 * 1000L
        private const val WAKELOCK_RENEWAL_INTERVAL_MS = 60 * 60 * 1000L
        private const val AB_PIN_POLL_INTERVAL_MS = 200L
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2000L

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

    private var abPinMonitorJob: Job? = null
    private var wakeLockRenewalJob: Job? = null

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

        if (intent == null) return START_STICKY

        when (intent.action) {
            ACTION_START -> {
                val videoPath = intent.getStringExtra(EXTRA_VIDEO_PATH)
                if (videoPath.isNullOrBlank()) return START_STICKY
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
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
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
        } catch (e: Exception) {
            Timber.e(e, "Error during service cleanup")
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

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                acquireWakeLock()
            } else {
                cancelWakeLockRenewal()
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

        currentLoopIteration = 0
        abLoopCompleted = false
        retryAttemptCount = 0
        cancelAbPinMonitor()
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

        // Start A-B position monitor if A-B loop is configured
        if (currentConfig.hasABLoop) {
            startAbPinMonitor()
        }

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
                cancelAbPinMonitor()
                // The video will naturally reach STATE_ENDED again when it finishes
                // At that point, we advance to next video
                // But wait — STATE_ENDED means the current playback ended.
                // Since we were monitoring A-B, STATE_ENDED won't fire until the full video ends.
                // We need to continue playing from current position to the end.
                // However, if STATE_ENDED fired, the full video has ended.
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
    // A-B POSITION MONITOR
    // ══════════════════════════════════════════════════════════════════════

    private fun startAbPinMonitor() {
        cancelAbPinMonitor()
        abPinMonitorJob = serviceScope.launch(Dispatchers.Main) {
            while (isActive) {
                checkABPosition()
                delay(AB_PIN_POLL_INTERVAL_MS)
            }
        }
    }

    private fun cancelAbPinMonitor() {
        abPinMonitorJob?.cancel()
        abPinMonitorJob = null
    }

    /**
     * Check if playback has reached the B marker.
     * When it does:
     *  - Increment iteration counter
     *  - If iterations < loopCount → seek back to A and continue
     *  - If iterations >= loopCount → let playback continue to end of video
     */
    private fun checkABPosition() {
        if (!currentConfig.hasABLoop) return
        if (abLoopCompleted) return

        val currentPosition = exoPlayer?.currentPosition ?: return
        if (currentPosition >= currentConfig.rangeEndMs) {
            currentLoopIteration++
            Timber.d("A-B: reached B at %dms, iteration %d/%d", currentConfig.rangeEndMs, currentLoopIteration, currentConfig.loopCount)

            if (currentLoopIteration < currentConfig.loopCount) {
                // More loops to go → seek back to A
                seekToA()
            } else {
                // Loop complete → continue playing from B to end of video
                Timber.d("A-B loop done, continuing playback from B to end")
                abLoopCompleted = true
                cancelAbPinMonitor()
                // Don't stop — let the video play to the end naturally
                // STATE_ENDED will fire when the full video finishes
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
    // WAKELOCK
    // ══════════════════════════════════════════════════════════════════════

    private fun setupWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LoopLingo::PlaybackWakeLock")
            .apply { setReferenceCounted(false) }
    }

    private fun acquireWakeLock() {
        if (isWakeLockHeld) return
        try {
            wakeLock?.acquire(WAKELOCK_FINITE_TIMEOUT_MS)
            isWakeLockHeld = true
        } catch (e: Exception) { Timber.e(e, "Failed to acquire WakeLock") }
    }

    private fun releaseWakeLock() {
        if (isWakeLockHeld) {
            try { wakeLock?.release() } catch (_: RuntimeException) {}
            isWakeLockHeld = false
        }
    }

    private fun startWakeLockRenewal() { /* simplified — finite timeout is sufficient */ }
    private fun cancelWakeLockRenewal() { wakeLockRenewalJob?.cancel(); wakeLockRenewalJob = null }

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
