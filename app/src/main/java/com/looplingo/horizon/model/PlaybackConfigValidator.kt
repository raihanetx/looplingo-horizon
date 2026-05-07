package com.looplingo.horizon.model

import timber.log.Timber

/**
 * Validates and sanitizes [PlaybackConfig] instances to ensure they contain
 * safe, consistent values before being used for playback.
 *
 * This is the single source of truth for all input validation rules.
 * The settings UI ([PlaybackSettingsFragment]) uses [validate] for immediate
 * user feedback, while the service uses [sanitize] as a defensive fallback
 * to correct any invalid data that might have been stored in Room.
 */
object PlaybackConfigValidator {

    // Boundaries
    private const val MIN_LOOP_COUNT = 1
    private const val MAX_LOOP_COUNT = 10_000
    private const val MIN_AB_RANGE_MS = 1000L  // 1 second minimum A-B range

    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>,
        val warnings: List<String> = emptyList()
    )

    /**
     * Validates a [PlaybackConfig] and returns all issues found.
     * Returns [ValidationResult.isValid] = true if no errors were found.
     * Warnings are non-blocking issues that the user should be aware of.
     */
    fun validate(config: PlaybackConfig): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // Loop count validation (only relevant for modes that use it)
        if (config.loopMode.usesLoopCount) {
            if (config.loopCount < MIN_LOOP_COUNT) {
                errors.add("Loop count must be at least $MIN_LOOP_COUNT (got ${config.loopCount})")
            }
            if (config.loopCount > MAX_LOOP_COUNT) {
                errors.add("Loop count cannot exceed $MAX_LOOP_COUNT (got ${config.loopCount})")
            }
        }

        // Range start cannot be negative
        if (config.rangeStartMs < 0) {
            errors.add("Range start cannot be negative (got ${config.rangeStartMs})")
        }

        // Range end validation
        if (config.rangeEndMs > 0 && config.rangeEndMs <= config.rangeStartMs) {
            errors.add("Range end (${config.rangeEndMs}ms) must be greater than range start (${config.rangeStartMs}ms)")
        }

        // A-B Pin specific validation
        if (config.loopMode == LoopMode.A_B_PIN) {
            if (config.rangeEndMs <= 0) {
                errors.add("A-B Pin mode requires a positive end position (got ${config.rangeEndMs})")
            }
            if (config.rangeEndMs > 0 && (config.rangeEndMs - config.rangeStartMs) < MIN_AB_RANGE_MS) {
                errors.add("A-B range must be at least ${MIN_AB_RANGE_MS}ms (got ${config.rangeEndMs - config.rangeStartMs}ms)")
            }
        }

        // Start action must be a valid enum value
        val validStartActions = StartAction.entries.map { it.value }
        if (config.startAction.value !in validStartActions) {
            errors.add("Start action must be 0 (AUTO_PLAY) or 1 (WAIT_MANUAL), got ${config.startAction.value}")
        }

        // Video path must not be blank
        if (config.videoPath.isBlank()) {
            errors.add("Video path must not be blank")
        }

        // ── Cross-field consistency warnings ──────────────────────────

        // LOOP_INFINITE + autoAdvance is contradictory — infinite loop never ends
        if (config.loopMode == LoopMode.LOOP_INFINITE && config.autoAdvance) {
            warnings.add("Auto-advance has no effect with Infinite Loop (it never ends)")
        }

        // PLAY_ONCE + loopCount > 1 is misleading
        if (config.loopMode == LoopMode.PLAY_ONCE && config.loopCount > 1) {
            warnings.add("Loop count is ignored in Play Once mode")
        }

        // FLOW mode ignores loopCount
        if (config.loopMode == LoopMode.FLOW && config.loopCount > 1) {
            warnings.add("Loop count is ignored in Flow mode")
        }

        if (errors.isNotEmpty()) {
            Timber.w("PlaybackConfig validation failed: %s", errors.joinToString("; "))
        }
        if (warnings.isNotEmpty()) {
            Timber.i("PlaybackConfig warnings: %s", warnings.joinToString("; "))
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    /**
     * Sanitizes a [PlaybackConfig] by clamping and correcting invalid values.
     * This is used as a defensive fallback in the service when loading configs
     * from Room that might contain outdated or corrupted data.
     *
     * Unlike [validate], this never returns errors — it silently fixes problems.
     */
    fun sanitize(config: PlaybackConfig): PlaybackConfig {
        var sanitized = config

        // Clamp loop count (only relevant for modes that use it)
        if (sanitized.loopMode.usesLoopCount) {
            if (sanitized.loopCount < MIN_LOOP_COUNT) {
                Timber.w("Sanitizing loop count: %d -> %d", sanitized.loopCount, MIN_LOOP_COUNT)
                sanitized = sanitized.copy(loopCount = MIN_LOOP_COUNT)
            }
            if (sanitized.loopCount > MAX_LOOP_COUNT) {
                Timber.w("Sanitizing loop count: %d -> %d", sanitized.loopCount, MAX_LOOP_COUNT)
                sanitized = sanitized.copy(loopCount = MAX_LOOP_COUNT)
            }
        }

        // Fix negative range start
        if (sanitized.rangeStartMs < 0) {
            Timber.w("Sanitizing range start: %d -> 0", sanitized.rangeStartMs)
            sanitized = sanitized.copy(rangeStartMs = 0)
        }

        // Fix range end <= range start
        if (sanitized.rangeEndMs > 0 && sanitized.rangeEndMs <= sanitized.rangeStartMs) {
            Timber.w("Sanitizing range end: %d -> -1 (disabled)", sanitized.rangeEndMs)
            sanitized = sanitized.copy(rangeEndMs = -1L)
        }

        // Fix A-B Pin without valid end position
        if (sanitized.loopMode == LoopMode.A_B_PIN && sanitized.rangeEndMs <= 0) {
            Timber.w("A-B Pin mode has no valid end position — falling back to LOOP_INFINITE")
            sanitized = sanitized.copy(loopMode = LoopMode.LOOP_INFINITE)
        }

        // Fix A-B range too short
        if (sanitized.loopMode == LoopMode.A_B_PIN &&
            sanitized.rangeEndMs > 0 &&
            (sanitized.rangeEndMs - sanitized.rangeStartMs) < MIN_AB_RANGE_MS
        ) {
            Timber.w("A-B range too short (%d ms) — adjusting end to minimum", sanitized.rangeEndMs - sanitized.rangeStartMs)
            sanitized = sanitized.copy(rangeEndMs = sanitized.rangeStartMs + MIN_AB_RANGE_MS)
        }

        // Fix invalid start action
        val validStartActions = StartAction.entries.map { it.value }
        if (sanitized.startAction.value !in validStartActions) {
            Timber.w("Sanitizing start action: %d -> AUTO_PLAY", sanitized.startAction.value)
            sanitized = sanitized.copy(startAction = StartAction.AUTO_PLAY)
        }

        // Fix LOOP_INFINITE + autoAdvance contradiction
        if (sanitized.loopMode == LoopMode.LOOP_INFINITE && sanitized.autoAdvance) {
            Timber.w("Disabling autoAdvance for LOOP_INFINITE (contradictory)")
            sanitized = sanitized.copy(autoAdvance = false)
        }

        return sanitized
    }

    /**
     * Quick check if a config is valid without collecting error messages.
     */
    fun isValid(config: PlaybackConfig): Boolean = validate(config).isValid
}
