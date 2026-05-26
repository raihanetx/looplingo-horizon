package com.looplingo.horizon.core

import java.util.Locale
import java.util.concurrent.TimeUnit

object TimeUtils {

    fun parseTimeToMs(text: String): Long {
        if (text.isBlank()) return 0L
        return try {
            val parts = text.trim().split(":")
            when (parts.size) {
                1 -> (parts[0].toLongOrNull() ?: 0L) * 1000L
                2 -> (parts[0].toLongOrNull() ?: 0L) * 60_000L + (parts[1].toLongOrNull() ?: 0L) * 1000L
                3 -> (parts[0].toLongOrNull() ?: 0L) * 3_600_000L +
                     (parts[1].toLongOrNull() ?: 0L) * 60_000L +
                     (parts[2].toLongOrNull() ?: 0L) * 1000L
                else -> 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    fun formatMsToTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSeconds = ms / 1000
        val hours = TimeUnit.SECONDS.toHours(totalSeconds)
        val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }
}
