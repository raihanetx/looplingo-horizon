package com.looplingo.horizon.model

import timber.log.Timber

/**
 * Validates and sanitizes [PlaybackConfig] instances.
 *
 * Simplified validation for the new A-B loop system:
 *  - rangeStartMs must be >= 0
 *  - rangeEndMs must be > rangeStartMs (if set)
 *  - A-B range must be at least 1 second
 *  - loopCount must be 1-10000
 *  - speed must be one of the valid presets
 */
object PlaybackConfigValidator {

    private const val MIN_LOOP_COUNT = 1
    private const val MAX_LOOP_COUNT = 10_000
    private const val MIN_AB_RANGE_MS = 1000L

    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>
    )

    fun validate(config: PlaybackConfig): ValidationResult {
        val errors = mutableListOf<String>()

        if (config.rangeStartMs < 0) {
            errors.add("Start time cannot be negative")
        }

        if (config.rangeEndMs > 0 && config.rangeEndMs <= config.rangeStartMs) {
            errors.add("End time must be greater than start time")
        }

        if (config.hasABLoop && (config.rangeEndMs - config.rangeStartMs) < MIN_AB_RANGE_MS) {
            errors.add("A-B range must be at least 1 second")
        }

        if (config.loopCount < MIN_LOOP_COUNT) {
            errors.add("Loop count must be at least $MIN_LOOP_COUNT")
        }

        if (config.loopCount > MAX_LOOP_COUNT) {
            errors.add("Loop count cannot exceed $MAX_LOOP_COUNT")
        }

        val validSpeeds = SpeedPresets.ALL.map { it.speed }
        if (validSpeeds.none { kotlin.math.abs(it - config.speed) < 0.001f }) {
            errors.add("Invalid speed value: ${config.speed}")
        }

        if (config.videoPath.isBlank()) {
            errors.add("Video path must not be blank")
        }

        if (errors.isNotEmpty()) {
            Timber.w("PlaybackConfig validation failed: %s", errors.joinToString("; "))
        }

        return ValidationResult(isValid = errors.isEmpty(), errors = errors)
    }

    fun sanitize(config: PlaybackConfig): PlaybackConfig {
        // Cannot sanitize a blank videoPath — return as-is and let the caller handle it
        if (config.videoPath.isBlank()) return config

        var sanitized = config

        if (sanitized.rangeStartMs < 0) {
            sanitized = sanitized.copy(rangeStartMs = 0)
        }

        if (sanitized.rangeEndMs > 0 && sanitized.rangeEndMs <= sanitized.rangeStartMs) {
            sanitized = sanitized.copy(rangeEndMs = -1L)
        }

        if (sanitized.hasABLoop && (sanitized.rangeEndMs - sanitized.rangeStartMs) < MIN_AB_RANGE_MS) {
            sanitized = sanitized.copy(rangeEndMs = sanitized.rangeStartMs + MIN_AB_RANGE_MS)
        }

        sanitized = sanitized.copy(
            loopCount = sanitized.loopCount.coerceIn(MIN_LOOP_COUNT, MAX_LOOP_COUNT)
        )

        val validSpeeds = SpeedPresets.ALL.map { it.speed }
        if (validSpeeds.none { kotlin.math.abs(it - sanitized.speed) < 0.001f }) {
            sanitized = sanitized.copy(speed = SpeedPresets.DEFAULT.speed)
        }

        return sanitized
    }

    fun isValid(config: PlaybackConfig): Boolean = validate(config).isValid
}
