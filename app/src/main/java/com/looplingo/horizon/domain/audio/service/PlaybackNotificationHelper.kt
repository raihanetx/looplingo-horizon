package com.looplingo.horizon.domain.audio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import com.looplingo.horizon.R
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

interface NotificationCallbacks {
    fun onTogglePlayback()
    fun onNext()
    fun onPrevious()
    fun onStop()
}

@Singleton
class PlaybackNotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val CHANNEL_ID = "loop_lingo_audio_channel"
        private const val NOTIFICATION_ID = 1
    }

    private var notificationActionReceiver: BroadcastReceiver? = null
    private var isReceiverRegistered: Boolean = false

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_description)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun buildLoadingNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            context, 0, Intent(context, com.looplingo.horizon.ui.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notification_loading))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle())
            .build()
    }

    fun buildNotification(
        isPlaying: Boolean,
        title: String,
        artist: String,
        thumbnail: Bitmap?
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            context, 0, Intent(context, com.looplingo.horizon.ui.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val previousIntent = PendingIntent.getBroadcast(
            context, 0, Intent(AudioPlaybackService.ACTION_PLAY_PREVIOUS).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIntent = PendingIntent.getBroadcast(
            context, 1, Intent(AudioPlaybackService.ACTION_TOGGLE_PLAYBACK).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextIntent = PendingIntent.getBroadcast(
            context, 2, Intent(AudioPlaybackService.ACTION_PLAY_NEXT).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getBroadcast(
            context, 3, Intent(AudioPlaybackService.ACTION_STOP).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseLabel = if (isPlaying) context.getString(R.string.notification_pause) else context.getString(R.string.notification_play)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText(context.getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_skip_previous, context.getString(R.string.notification_previous), previousIntent)
            .addAction(playPauseIcon, playPauseLabel, toggleIntent)
            .addAction(R.drawable.ic_skip_next, context.getString(R.string.notification_next), nextIntent)
            .addAction(R.drawable.ic_close, context.getString(R.string.notification_stop), stopIntent)
            .setDeleteIntent(stopIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
                    .setCancelButtonIntent(stopIntent)
            )
            .build()
    }

    fun updateNotification(isPlaying: Boolean, title: String, artist: String, thumbnail: Bitmap?) {
        try {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification(isPlaying, title, artist, thumbnail))
        } catch (e: Exception) { Timber.e(e, "Failed to update notification") }
    }

    fun updateNotificationWithError(errorMessage: String?) {
        try {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.notification_error_title))
                .setContentText(errorMessage ?: context.getString(R.string.notification_error_unknown))
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(false)
                .build()
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) { Timber.e(e, "Failed to update notification with error") }
    }

    fun registerNotificationReceiver(service: Context, callbacks: NotificationCallbacks) {
        if (isReceiverRegistered) return
        notificationActionReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    AudioPlaybackService.ACTION_TOGGLE_PLAYBACK -> callbacks.onTogglePlayback()
                    AudioPlaybackService.ACTION_PLAY_NEXT -> callbacks.onNext()
                    AudioPlaybackService.ACTION_PLAY_PREVIOUS -> callbacks.onPrevious()
                    AudioPlaybackService.ACTION_STOP -> callbacks.onStop()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(AudioPlaybackService.ACTION_TOGGLE_PLAYBACK)
            addAction(AudioPlaybackService.ACTION_PLAY_NEXT)
            addAction(AudioPlaybackService.ACTION_PLAY_PREVIOUS)
            addAction(AudioPlaybackService.ACTION_STOP)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                service.registerReceiver(notificationActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                service.registerReceiver(notificationActionReceiver, filter)
            }
            isReceiverRegistered = true
        } catch (e: Exception) { Timber.e(e, "Failed to register receiver") }
    }

    fun unregisterNotificationReceiver(service: Context) {
        if (!isReceiverRegistered) return
        try { service.unregisterReceiver(notificationActionReceiver) }
        catch (_: IllegalArgumentException) {}
        catch (e: Exception) { Timber.w(e, "Error unregistering receiver") }
        isReceiverRegistered = false
    }
}
