package com.looplingo.horizon.model

/**
 * A single subtitle cue (one line of dialogue/text with timing).
 *
 * Used to display transcript information synced with audio playback.
 * Parsed from .srt or .vtt subtitle files, or from ID3 lyrics tags.
 *
 * @param index The sequential number of this cue (1-based, from the subtitle file)
 * @param startMs Start time in milliseconds
 * @param endMs End time in milliseconds
 * @param text The subtitle text (may contain multiple lines separated by \n)
 */
data class SubtitleCue(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String
) {
    /** Whether this cue should be visible at the given playback position. */
    fun isActiveAt(positionMs: Long): Boolean {
        return positionMs in startMs..endMs
    }

    /** Short timestamp label like "1:23" for display. */
    val startLabel: String
        get() = formatMs(startMs)

    /** Short timestamp label like "1:45" for display. */
    val endLabel: String
        get() = formatMs(endMs)

    companion object {
        /** Format milliseconds to "m:ss" or "h:mm:ss". */
        fun formatMs(ms: Long): String {
            if (ms <= 0) return "0:00"
            val totalSeconds = ms / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                String.format("%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%d:%02d", minutes, seconds)
            }
        }
    }
}
