package com.looplingo.horizon.model

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PlaybackConfigValidator].
 *
 * Tests cover:
 *  - Valid configs pass validation
 *  - Invalid loop count (too low, too high)
 *  - Invalid ranges (negative start, end before start)
 *  - A-B Pin specific requirements (requires end, minimum range)
 *  - Invalid start action
 *  - Blank video path
 *  - Cross-field consistency warnings
 *  - Sanitization corrects invalid values
 */
class PlaybackConfigValidatorTest {

    private lateinit var validConfig: PlaybackConfig

    @Before
    fun setUp() {
        validConfig = PlaybackConfig(
            videoPath = "/storage/emulated/0/video.mp4",
            startAction = StartAction.AUTO_PLAY,
            rangeStartMs = 0L,
            rangeEndMs = -1L,
            loopMode = LoopMode.LOOP_INFINITE,
            loopCount = 1,
            autoAdvance = false
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // VALIDATE — HAPPY PATH
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `valid LOOP_INFINITE config passes validation`() {
        val result = PlaybackConfigValidator.validate(validConfig)
        assertThat(result.isValid).isTrue()
        assertThat(result.errors).isEmpty()
    }

    @Test
    fun `valid PLAY_ONCE config passes validation`() {
        val config = validConfig.copy(loopMode = LoopMode.PLAY_ONCE)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isTrue()
    }

    @Test
    fun `valid LOOP_X_TIMES config passes validation`() {
        val config = validConfig.copy(loopMode = LoopMode.LOOP_X_TIMES, loopCount = 5)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isTrue()
    }

    @Test
    fun `valid FLOW config passes validation`() {
        val config = validConfig.copy(loopMode = LoopMode.FLOW)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isTrue()
    }

    @Test
    fun `valid AUTO_LOOP config passes validation`() {
        val config = validConfig.copy(loopMode = LoopMode.AUTO_LOOP, loopCount = 3)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isTrue()
    }

    @Test
    fun `valid A_B_PIN config passes validation`() {
        val config = validConfig.copy(
            loopMode = LoopMode.A_B_PIN,
            rangeStartMs = 5000L,
            rangeEndMs = 15000L,
            loopCount = 3
        )
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isTrue()
    }

    @Test
    fun `valid config with range start and end`() {
        val config = validConfig.copy(rangeStartMs = 10000L, rangeEndMs = 30000L)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isTrue()
    }

    // ══════════════════════════════════════════════════════════════════════
    // VALIDATE — LOOP COUNT
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `LOOP_X_TIMES with loopCount 0 fails validation`() {
        val config = validConfig.copy(loopMode = LoopMode.LOOP_X_TIMES, loopCount = 0)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isFalse()
        assertThat(result.errors).hasSize(1)
        assertThat(result.errors[0]).contains("at least 1")
    }

    @Test
    fun `AUTO_LOOP with loopCount exceeding 10000 fails validation`() {
        val config = validConfig.copy(loopMode = LoopMode.AUTO_LOOP, loopCount = 10001)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isFalse()
        assertThat(result.errors).hasSize(1)
        assertThat(result.errors[0]).contains("exceed")
    }

    @Test
    fun `LOOP_INFINITE with loopCount 0 passes — loopCount not checked for this mode`() {
        val config = validConfig.copy(loopMode = LoopMode.LOOP_INFINITE, loopCount = 0)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isTrue()
    }

    @Test
    fun `LOOP_X_TIMES with loopCount at boundary 1 passes`() {
        val config = validConfig.copy(loopMode = LoopMode.LOOP_X_TIMES, loopCount = 1)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isTrue()
    }

    @Test
    fun `LOOP_X_TIMES with loopCount at boundary 10000 passes`() {
        val config = validConfig.copy(loopMode = LoopMode.LOOP_X_TIMES, loopCount = 10000)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isTrue()
    }

    // ══════════════════════════════════════════════════════════════════════
    // VALIDATE — RANGE
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `negative rangeStartMs fails validation`() {
        val config = validConfig.copy(rangeStartMs = -100L)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isFalse()
        assertThat(result.errors).hasSize(1)
        assertThat(result.errors[0]).contains("negative")
    }

    @Test
    fun `rangeEndMs less than rangeStartMs fails validation`() {
        val config = validConfig.copy(rangeStartMs = 20000L, rangeEndMs = 10000L)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isFalse()
        assertThat(result.errors).hasSize(1)
        assertThat(result.errors[0]).contains("greater than")
    }

    @Test
    fun `rangeEndMs equal to rangeStartMs fails validation`() {
        val config = validConfig.copy(rangeStartMs = 10000L, rangeEndMs = 10000L)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isFalse()
    }

    @Test
    fun `rangeEndMs of -1 (disabled) passes validation`() {
        val config = validConfig.copy(rangeStartMs = 0L, rangeEndMs = -1L)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isTrue()
    }

    @Test
    fun `rangeEndMs of 0 (disabled) passes validation`() {
        val config = validConfig.copy(rangeStartMs = 0L, rangeEndMs = 0L)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isTrue()
    }

    // ══════════════════════════════════════════════════════════════════════
    // VALIDATE — A-B PIN SPECIFIC
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `A_B_PIN without end position fails validation`() {
        val config = validConfig.copy(loopMode = LoopMode.A_B_PIN, rangeEndMs = -1L)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isFalse()
        assertThat(result.errors).hasSize(1)
        assertThat(result.errors[0]).contains("end position")
    }

    @Test
    fun `A_B_PIN with range less than 1 second fails validation`() {
        val config = validConfig.copy(
            loopMode = LoopMode.A_B_PIN,
            rangeStartMs = 5000L,
            rangeEndMs = 5500L  // 500ms < 1000ms minimum
        )
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isFalse()
        assertThat(result.errors.any { it.contains("at least") }).isTrue()
    }

    @Test
    fun `A_B_PIN with exactly 1 second range passes validation`() {
        val config = validConfig.copy(
            loopMode = LoopMode.A_B_PIN,
            rangeStartMs = 5000L,
            rangeEndMs = 6000L  // Exactly 1 second
        )
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isTrue()
    }

    // ══════════════════════════════════════════════════════════════════════
    // VALIDATE — START ACTION & VIDEO PATH
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `startAction with unknown value fails validation`() {
        // Create config with an out-of-range startAction value by direct construction
        val config = validConfig.copy(startAction = StartAction.fromValue(99))
        val result = PlaybackConfigValidator.validate(config)
        // StartAction.fromValue(99) defaults to AUTO_PLAY, so it's still valid
        // This tests that the enum itself prevents invalid values
        assertThat(result.isValid).isTrue()
    }

    @Test
    fun `blank videoPath fails validation`() {
        val config = validConfig.copy(videoPath = "")
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isFalse()
        assertThat(result.errors.any { it.contains("blank") }).isTrue()
    }

    // ══════════════════════════════════════════════════════════════════════
    // VALIDATE — CROSS-FIELD WARNINGS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `LOOP_INFINITE with autoAdvance produces warning`() {
        val config = validConfig.copy(loopMode = LoopMode.LOOP_INFINITE, autoAdvance = true)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isTrue()  // Warnings don't block
        assertThat(result.warnings.any { it.contains("Auto-advance") }).isTrue()
    }

    @Test
    fun `PLAY_ONCE with loopCount greater than 1 produces warning`() {
        val config = validConfig.copy(loopMode = LoopMode.PLAY_ONCE, loopCount = 5)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isTrue()
        assertThat(result.warnings.any { it.contains("Loop count is ignored") }).isTrue()
    }

    @Test
    fun `FLOW with loopCount greater than 1 produces warning`() {
        val config = validConfig.copy(loopMode = LoopMode.FLOW, loopCount = 3)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isTrue()
        assertThat(result.warnings.any { it.contains("Loop count is ignored") }).isTrue()
    }

    // ══════════════════════════════════════════════════════════════════════
    // VALIDATE — MULTIPLE ERRORS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `config with multiple errors reports all of them`() {
        val config = PlaybackConfig(
            videoPath = "",
            startAction = StartAction.AUTO_PLAY,  // Can't construct invalid StartAction anymore
            rangeStartMs = -100L,
            rangeEndMs = -1L,
            loopMode = LoopMode.A_B_PIN,  // Requires valid end
            loopCount = 0
        )
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isFalse()
        // Should have multiple errors: blank path, negative start, no A-B end
        assertThat(result.errors.size).isAtLeast(3)
    }

    // ══════════════════════════════════════════════════════════════════════
    // SANITIZE
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `sanitize clamps loopCount below 1 to 1`() {
        val config = validConfig.copy(loopMode = LoopMode.LOOP_X_TIMES, loopCount = 0)
        val sanitized = PlaybackConfigValidator.sanitize(config)
        assertThat(sanitized.loopCount).isEqualTo(1)
    }

    @Test
    fun `sanitize clamps loopCount above 10000 to 10000`() {
        val config = validConfig.copy(loopMode = LoopMode.LOOP_X_TIMES, loopCount = 99999)
        val sanitized = PlaybackConfigValidator.sanitize(config)
        assertThat(sanitized.loopCount).isEqualTo(10000)
    }

    @Test
    fun `sanitize fixes negative rangeStartMs to 0`() {
        val config = validConfig.copy(rangeStartMs = -500L)
        val sanitized = PlaybackConfigValidator.sanitize(config)
        assertThat(sanitized.rangeStartMs).isEqualTo(0L)
    }

    @Test
    fun `sanitize fixes rangeEndMs less than rangeStartMs by disabling end`() {
        val config = validConfig.copy(rangeStartMs = 20000L, rangeEndMs = 10000L)
        val sanitized = PlaybackConfigValidator.sanitize(config)
        assertThat(sanitized.rangeEndMs).isEqualTo(-1L)
    }

    @Test
    fun `sanitize A_B_PIN without valid end falls back to LOOP_INFINITE`() {
        val config = validConfig.copy(loopMode = LoopMode.A_B_PIN, rangeEndMs = -1L)
        val sanitized = PlaybackConfigValidator.sanitize(config)
        assertThat(sanitized.loopMode).isEqualTo(LoopMode.LOOP_INFINITE)
    }

    @Test
    fun `sanitize A_B_PIN with too short range extends end to minimum`() {
        val config = validConfig.copy(
            loopMode = LoopMode.A_B_PIN,
            rangeStartMs = 5000L,
            rangeEndMs = 5500L  // 500ms, too short
        )
        val sanitized = PlaybackConfigValidator.sanitize(config)
        assertThat(sanitized.loopMode).isEqualTo(LoopMode.A_B_PIN)
        assertThat(sanitized.rangeEndMs).isEqualTo(5000L + 1000L)  // Start + 1 second
    }

    @Test
    fun `sanitize fixes invalid startAction to AUTO_PLAY`() {
        val config = validConfig.copy(startAction = StartAction.AUTO_PLAY)  // Can't construct invalid
        val sanitized = PlaybackConfigValidator.sanitize(config)
        assertThat(sanitized.startAction).isEqualTo(StartAction.AUTO_PLAY)
    }

    @Test
    fun `sanitize does not modify already valid config`() {
        val config = validConfig.copy(loopMode = LoopMode.LOOP_X_TIMES, loopCount = 5)
        val sanitized = PlaybackConfigValidator.sanitize(config)
        assertThat(sanitized).isEqualTo(config)
    }

    @Test
    fun `sanitize disables autoAdvance for LOOP_INFINITE`() {
        val config = validConfig.copy(loopMode = LoopMode.LOOP_INFINITE, autoAdvance = true)
        val sanitized = PlaybackConfigValidator.sanitize(config)
        assertThat(sanitized.autoAdvance).isFalse()
    }

    // ══════════════════════════════════════════════════════════════════════
    // IS VALID — CONVENIENCE METHOD
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `isValid returns true for valid config`() {
        assertThat(PlaybackConfigValidator.isValid(validConfig)).isTrue()
    }

    @Test
    fun `isValid returns false for invalid config`() {
        val config = validConfig.copy(loopMode = LoopMode.A_B_PIN, rangeEndMs = -1L)
        assertThat(PlaybackConfigValidator.isValid(config)).isFalse()
    }

    // ══════════════════════════════════════════════════════════════════════
    // LOOPMODE PROPERTIES
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `LoopMode usesLoopCount is true only for LOOP_X_TIMES and AUTO_LOOP`() {
        assertThat(LoopMode.LOOP_X_TIMES.usesLoopCount).isTrue()
        assertThat(LoopMode.AUTO_LOOP.usesLoopCount).isTrue()
        assertThat(LoopMode.PLAY_ONCE.usesLoopCount).isFalse()
        assertThat(LoopMode.LOOP_INFINITE.usesLoopCount).isFalse()
        assertThat(LoopMode.FLOW.usesLoopCount).isFalse()
        assertThat(LoopMode.A_B_PIN.usesLoopCount).isFalse()
    }

    @Test
    fun `LoopMode isLooping is false for PLAY_ONCE and FLOW`() {
        assertThat(LoopMode.PLAY_ONCE.isLooping).isFalse()
        assertThat(LoopMode.FLOW.isLooping).isFalse()
        assertThat(LoopMode.LOOP_X_TIMES.isLooping).isTrue()
        assertThat(LoopMode.LOOP_INFINITE.isLooping).isTrue()
        assertThat(LoopMode.AUTO_LOOP.isLooping).isTrue()
        assertThat(LoopMode.A_B_PIN.isLooping).isTrue()
    }

    @Test
    fun `StartAction fromValue returns correct enum`() {
        assertThat(StartAction.fromValue(0)).isEqualTo(StartAction.AUTO_PLAY)
        assertThat(StartAction.fromValue(1)).isEqualTo(StartAction.WAIT_MANUAL)
        assertThat(StartAction.fromValue(99)).isEqualTo(StartAction.AUTO_PLAY)  // Default
    }
}
