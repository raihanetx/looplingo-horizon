package com.looplingo.horizon.domain.audio.service

import android.content.Context
import android.os.PowerManager
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WakeLockManager @Inject constructor() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var isWakeLockHeld = false

    companion object {
        private const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L
    }

    internal fun setup(context: Context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HorizonLoop::PlaybackWakeLock")
            .apply { setReferenceCounted(false) }
    }

    internal fun acquire() {
        if (isWakeLockHeld) return
        try {
            wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
            isWakeLockHeld = true
        } catch (e: Exception) {
            Timber.e(e, "Failed to acquire WakeLock")
        }
    }

    internal fun release() {
        if (isWakeLockHeld) {
            try {
                wakeLock?.release()
            } catch (e: Exception) {
                Timber.w(e, "WakeLock release failed (may already be released)")
            }
            isWakeLockHeld = false
        }
    }

    internal fun releaseSafely() {
        try {
            release()
        } catch (e: Exception) {
            Timber.w(e, "WakeLock safety release failed")
            isWakeLockHeld = false
        }
    }
}
