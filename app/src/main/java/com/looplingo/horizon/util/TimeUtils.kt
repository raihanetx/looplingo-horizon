package com.looplingo.horizon.util

import java.util.concurrent.TimeUnit

/**
 * Shared time formatting utilities used across the app.
 *
 * Extracts the duplicated formatMsToTime() logic from:
 *  - SubtitleCue.formatMs()
 *  - PlaybackSettingsFragment.formatMsToTime()
 *  - MainFragment.formatMsToTime()
 *  - PlaybackSettingsViewModel.formatMs()
 *
 * All implementations now delegate to these shared functions for consistency.
 */
object TimeUtils {

    /**
     * Format milliseconds to a human-readable time string.
     *
     * Examples:
     *  - 0 → "0:00"
     *  - 65000 → "1:05"
     *  - 3661000 → "1:01:01"
     *
     * @param ms Time in milliseconds
     * @return Formatted string like "m:ss" or "h:mm:ss"
     */
    fun formatMsToTime(ms: Long): String {
        // Handle negative values from ExoPlayer during seeking — treat as 0
        if (ms <= 0) return "0:00"
        val totalSeconds = ms / 1000
        val hours = TimeUnit.SECONDS.toHours(totalSeconds)
        val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }
}
