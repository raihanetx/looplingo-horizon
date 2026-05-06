package com.looplingo.horizon.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.looplingo.horizon.R
import com.looplingo.horizon.ui.MainActivity

class AudioPlaybackService : LifecycleService() {
    companion object {
        const val CHANNEL_ID = "loop_lingo_audio_channel"
        const val NOTIFICATION_ID = 1
    }

    private var exoPlayer: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupExoPlayer()
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

    private fun setupExoPlayer() {
        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true))
        }
        exoPlayer = ExoPlayer.Builder(this).setTrackSelector(trackSelector).build()
    }

    private fun startForegroundService() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LoopLingo")
            .setContentText("Audio playing")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
    }
}
