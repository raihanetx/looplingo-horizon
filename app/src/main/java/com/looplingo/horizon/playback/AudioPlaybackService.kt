package com.looplingo.horizon.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes as SystemAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.media.session.MediaButtonReceiver
import androidx.media.app.NotificationCompat.MediaStyle
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes as ExoAudioAttributes
import androidx.media.session.MediaSessionCompat
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.source.ClippingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.looplingo.horizon.R
import com.looplingo.horizon.ui.MainActivity
import kotlinx.coroutines.*

class AudioPlaybackService : LifecycleService() {
    companion object {
        const val CHANNEL_ID = "loop_lingo_audio_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "start"
        const val EXTRA_VIDEO_PATH = "video_path"

        fun startService(context: Context, videoPath: String) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_VIDEO_PATH, videoPath)
            }
            context.startForegroundService(intent)
        }
    }

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var currentVideoPath: String? = null
    private var rangeStartMs: Long = 0
    private var rangeEndMs: Long = C.TIME_UNSET
    private var loopMode: LoopMode = LoopMode.LOOP_INFINITE
    private var loopCount: Int = 0
    private var currentLoopIteration: Int = 0
    private var autoAdvance: Boolean = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupWakeLock()
        setupExoPlayer()
        setupMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_VIDEO_PATH)?.let { startPlayback(it) }
        }
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LoopLingo Audio Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Notifications for audio playback" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun setupWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LoopLingo::PlaybackWakeLock"
        ).apply { setReferenceCounted(false) }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "LoopLingoMediaSession").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(mediaSessionCallback)
        }
        mediaSessionConnector = MediaSessionConnector(mediaSession!!)
    }

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() { exoPlayer?.play(); wakeLock?.acquire(10*60*1000L) }
        override fun onPause() { exoPlayer?.pause(); wakeLock?.release() }
        override fun onStop() { stopSelf() }
    }

    private fun setupExoPlayer() {
        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true))
        }

        val audioAttributes = ExoAudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(audioAttributes, true)
            .build()

        exoPlayer?.addListener(playerListener)
        mediaSessionConnector?.setPlayer(exoPlayer)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> handlePlaybackEnded()
                Player.STATE_READY -> updateNotification()
            }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                wakeLock?.acquire(10*60*1000L)
                startForeground(NOTIFICATION_ID, buildNotification())
            } else wakeLock?.release()
        }
    }

    private fun startPlayback(videoPath: String) {
        currentVideoPath = videoPath
        val mediaSource = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(this))
            .createMediaSource(MediaItem.fromUri(videoPath))

        val source = if (rangeEndMs != C.TIME_UNSET) {
            ClippingMediaSource(mediaSource, rangeStartMs, rangeEndMs)
        } else mediaSource

        exoPlayer?.apply {
            setMediaSource(source)
            prepare()
            repeatMode = if (loopMode != LoopMode.PLAY_ONCE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            play()
        }
    }

    private fun handlePlaybackEnded() {
        currentLoopIteration++
        when (loopMode) {
            LoopMode.LOOP_X_TIMES -> {
                if (currentLoopIteration >= loopCount) {
                    if (autoAdvance) advanceRange() else stopSelf()
                } else restartCurrentRange()
            }
            LoopMode.LOOP_INFINITE -> restartCurrentRange()
            LoopMode.PLAY_ONCE -> if (autoAdvance) advanceRange() else stopSelf()
            else -> restartCurrentRange()
        }
    }

    private fun restartCurrentRange() {
        exoPlayer?.seekTo(rangeStartMs)
        exoPlayer?.play()
    }

    private fun advanceRange() {
        val duration = exoPlayer?.duration ?: return
        val rangeDuration = if (rangeEndMs != C.TIME_UNSET) rangeEndMs - rangeStartMs else duration
        rangeStartMs += rangeDuration
        if (rangeStartMs >= duration) { stopSelf(); return }
        rangeEndMs = if (rangeEndMs != C.TIME_UNSET) rangeEndMs + rangeDuration else C.TIME_UNSET
        if (rangeEndMs > duration) rangeEndMs = duration
        currentLoopIteration = 0
        startPlayback(currentVideoPath!!)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LoopLingo")
            .setContentText("Playing: ${currentVideoPath?.substringAfterLast("/")}")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(MediaStyle().setMediaSession(mediaSession?.sessionToken))
            .addAction(android.R.drawable.ic_media_previous, "Previous", null)
            .addAction(
                if (exoPlayer?.isPlaying == true) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                "Play/Pause", null
            )
            .addAction(android.R.drawable.ic_media_next, "Next", null)
            .build()
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        exoPlayer?.release()
        exoPlayer = null
        mediaSession?.release()
        wakeLock?.release()
    }
}

enum class LoopMode { PLAY_ONCE, LOOP_X_TIMES, LOOP_INFINITE, FLOW, AUTO_LOOP, A_B_PIN }
