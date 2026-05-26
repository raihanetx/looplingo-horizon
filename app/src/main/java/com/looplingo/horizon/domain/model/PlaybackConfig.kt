package com.looplingo.horizon.domain.model

data class PlaybackConfig(
    val videoPath: String,
    val rangeStartMs: Long = 0L,
    val rangeEndMs: Long = -1L,
    val loopCount: Int = 1,
    val speed: Float = 1.0f
) {
    val hasABLoop: Boolean
        get() = rangeEndMs > 0L && rangeEndMs > rangeStartMs

    val willLoop: Boolean
        get() = loopCount > 1

    val isNormalPlayback: Boolean
        get() = !hasABLoop && loopCount <= 1

    val displayBadge: String
        get() = when {
            isNormalPlayback -> ""
            hasABLoop && loopCount > 1 -> "AB×$loopCount"
            hasABLoop -> "AB"
            loopCount > 1 -> "x$loopCount"
            else -> ""
        }
}

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

    fun closestTo(speed: Float): Preset {
        return ALL.minByOrNull { kotlin.math.abs(it.speed - speed) } ?: DEFAULT
    }
}
