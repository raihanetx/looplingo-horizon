package com.looplingo.horizon.model

/**
 * Represents the complete playback configuration for a single video.
 * This is the single source of truth for how a video should be played.
 *
 * @property videoPath      Absolute path or content URI of the video file.
 * @property startAction    Whether to auto-play or wait for manual start.
 * @property rangeStartMs   Start of the playback range in milliseconds (0 = beginning).
 * @property rangeEndMs     End of the playback range in milliseconds (-1 = entire duration).
 * @property loopMode       The looping behaviour to apply.
 * @property loopCount      Number of times to loop (only meaningful when [LoopMode.usesLoopCount] is true).
 * @property autoAdvance    Whether to automatically move to the next video after the loop completes.
 */
data class PlaybackConfig(
    val videoPath: String,
    val startAction: StartAction = StartAction.AUTO_PLAY,
    val rangeStartMs: Long = 0L,
    val rangeEndMs: Long = -1L,
    val loopMode: LoopMode = LoopMode.LOOP_INFINITE,
    val loopCount: Int = 1,
    val autoAdvance: Boolean = false
)

/**
 * Determines how playback begins after a video is loaded.
 */
enum class StartAction(val value: Int) {
    /** Start playing immediately when the video is loaded. */
    AUTO_PLAY(0),
    /** Load the video but wait for the user to press play. */
    WAIT_MANUAL(1);

    companion object {
        /** Converts an integer value back to a [StartAction], or defaults to [AUTO_PLAY]. */
        fun fromValue(value: Int): StartAction =
            entries.find { it.value == value } ?: AUTO_PLAY
    }
}
