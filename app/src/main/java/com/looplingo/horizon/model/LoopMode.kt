package com.looplingo.horizon.model

/**
 * Defines the different looping behaviours available for audio playback.
 *
 * PLAY_ONCE    – Play the selected range once and stop.
 * LOOP_X_TIMES – Repeat the selected range a user-specified number of times.
 * LOOP_INFINITE– Repeat the selected range indefinitely until manually stopped.
 * FLOW         – Play once, then auto-advance to the next video in the list.
 * AUTO_LOOP    – Loop the current video N times, then auto-advance to the next.
 * A_B_PIN      – Loop a specific A→B segment within the video.
 */
enum class LoopMode {
    PLAY_ONCE,
    LOOP_X_TIMES,
    LOOP_INFINITE,
    FLOW,
    AUTO_LOOP,
    A_B_PIN;

    /** Whether this mode uses the [PlaybackConfig.loopCount] field. */
    val usesLoopCount: Boolean
        get() = this == LOOP_X_TIMES || this == AUTO_LOOP || this == A_B_PIN

    /** Whether this mode loops (and thus may need the A-B pin monitor or restart logic). */
    val isLooping: Boolean
        get() = this != PLAY_ONCE && this != FLOW

    /**
     * Sensible default loop count when the user switches to this mode.
     * - LOOP_X_TIMES / AUTO_LOOP: 3 is more useful than 1 (which is effectively PLAY_ONCE)
     * - A_B_PIN: 1 segment by default; user can increase if needed
     * - Other modes: 1 (loopCount is irrelevant for these)
     */
    val defaultLoopCount: Int
        get() = when (this) {
            LOOP_X_TIMES -> 3
            AUTO_LOOP -> 3
            A_B_PIN -> 1
            else -> 1
        }

    /** Whether this mode automatically advances to the next video when done. */
    val autoAdvancesByDefault: Boolean
        get() = this == FLOW

    /** Short badge text for display in video list items. Uses safe ASCII-compatible characters. */
    val displayBadge: String
        get() = when (this) {
            PLAY_ONCE -> "1x"
            LOOP_X_TIMES -> "Nx"
            LOOP_INFINITE -> "INF"
            FLOW -> "FLW"
            AUTO_LOOP -> "AL"
            A_B_PIN -> "AB"
        }
}
