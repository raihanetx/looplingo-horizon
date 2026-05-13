package com.looplingo.horizon.model

import com.looplingo.horizon.util.TimeUtils

/**
 * A single subtitle cue (one line of dialogue/text with timing).
 *
 * Used to display transcript information synced with audio playback.
 * Parsed from .srt or .vtt subtitle files, from ID3 lyrics tags,
 * or from Whisper transcription segments stored in Room.
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
    /**
     * Whether this cue should be visible at the given playback position.
     *
     * Uses CLOSED range [startMs, endMs] instead of half-open [startMs, endMs).
     *
     * BUG FIX: The previous half-open range (`positionMs in startMs until endMs`) caused
     * a gap at the exact boundary where one cue's endMs equals another's startMs.
     * For example, if Cue A ends at 5000ms and Cue B starts at 5000ms, positionMs=5000
     * would match NEITHER cue — the subtitle would flicker off for one frame.
     *
     * With the closed range, positionMs=5000 matches both cues. When cues are
     * non-overlapping (standard case), only one will match. When they overlap at
     * the boundary, both match, which is correct — the UI should show the later
     * cue (handled by TranscriptRepository's binary search returning the last match).
     */
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
        /** Format milliseconds to "m:ss" or "h:mm:ss". Delegates to shared TimeUtils. */
        fun formatMs(ms: Long): String = TimeUtils.formatMsToTime(ms)
    }
}
