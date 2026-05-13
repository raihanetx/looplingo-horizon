package com.looplingo.horizon.model

/**
 * Simplified playback configuration for a single video.
 *
 * Logic:
 *  - No loop mode enum. The behavior is determined by the config values:
 *    - If rangeEndMs <= 0 AND loopCount <= 1 → play full video once (normal playback)
 *    - If rangeEndMs > 0 AND loopCount > 0 → loop the A→B segment N times,
 *      then continue playing the rest of the video
 *    - If rangeEndMs <= 0 AND loopCount > 1 → loop the entire video N times
 *
 * Speed presets available: 0.25x, 0.5x, 0.75x, 0.9x, 1.0x, 1.25x, 1.5x, 2.0x
 * Default speed is 1.0x (normal). Maximum speed is 2.0x.
 */
data class PlaybackConfig(
    val videoPath: String,
    val rangeStartMs: Long = 0L,
    val rangeEndMs: Long = -1L,
    val loopCount: Int = 1,
    val speed: Float = 1.0f
) {
    /** Whether this config has an A-B loop range set. */
    val hasABLoop: Boolean
        get() = rangeEndMs > 0L && rangeEndMs > rangeStartMs

    /** Whether this config will actually loop (repeat playback).
     *  A-B with loopCount=1 plays the segment once (no repetition),
     *  so it's not truly "looping" — just range-restricted playback. */
    val willLoop: Boolean
        get() = loopCount > 1

    /** Whether this is just normal full-video playback (no looping, no range). */
    val isNormalPlayback: Boolean
        get() = !hasABLoop && loopCount <= 1

    /** Short badge text for the video list. */
    val displayBadge: String
        get() = when {
            isNormalPlayback -> ""
            hasABLoop && loopCount > 1 -> "AB×$loopCount"
            hasABLoop -> "AB"
            loopCount > 1 -> "x$loopCount"
            else -> ""
        }
}

/**
 * Speed presets for playback.
 * The user can select from these fixed values.
 */
object SpeedPresets {
    data class Preset(val label: String, val speed: Float)

    val ALL = listOf(
        Preset("0.25x", 0.25f),
        Preset("0.5x", 0.5f),
        Preset("0.75x", 0.75f),
        Preset("0.9x", 0.9f),
        Preset("1x", 1.0f),
        Preset("1.25x", 1.25f),
        Preset("1.5x", 1.5f),
        Preset("2x", 2.0f),
    )

    val DEFAULT = Preset("1x", 1.0f)

    /** Find the closest preset for a given speed value. */
    fun closestTo(speed: Float): Preset {
        return ALL.minByOrNull { kotlin.math.abs(it.speed - speed) } ?: DEFAULT
    }
}
