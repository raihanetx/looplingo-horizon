package com.looplingo.horizon.model

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PlaybackConfigValidator].
 *
 * Tests cover the simplified A-B loop system:
 *  - Valid configs pass validation
 *  - Invalid loop count (too low, too high)
 *  - Invalid ranges (negative start, end before start, too short)
 *  - Invalid speed values
 *  - Blank video path
 *  - Sanitization corrects invalid values
 */
class PlaybackConfigValidatorTest {

    private lateinit var validConfig: PlaybackConfig

    @Before
    fun setUp() {
        validConfig = PlaybackConfig(
            videoPath = "/storage/emulated/0/video.mp4",
            rangeStartMs = 0L,
            rangeEndMs = -1L,
            loopCount = 1,
            speed = 1.0f
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // VALIDATE — HAPPY PATH
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `normal playback config with default values passes validation`() {
        val result = PlaybackConfigValidator.validate(validConfig)
        assertThat(result.isValid).isTrue()
        assertThat(result.errors).isEmpty()
    }

    @Test
    fun `A-B loop config with valid range and loop count passes validation`() {
        val config = validConfig.copy(
            rangeStartMs = 5000L,
            rangeEndMs = 15000L,
            loopCount = 3
        )
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isTrue()
    }

    @Test
    fun `full video loop config with loopCount greater than 1 passes validation`() {
        val config = validConfig.copy(loopCount = 5)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isTrue()
    }

    @Test
    fun `valid config with custom speed passes validation`() {
        val config = validConfig.copy(speed = 0.75f)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isTrue()
    }

    @Test
    fun `valid config with 0_25x speed passes validation`() {
        val config = validConfig.copy(speed = 0.25f)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isTrue()
    }

    @Test
    fun `valid config with 2x speed passes validation`() {
        val config = validConfig.copy(speed = 2.0f)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isTrue()
    }

    // ══════════════════════════════════════════════════════════════════════
    // VALIDATE — LOOP COUNT
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `loopCount 0 fails validation`() {
        val config = validConfig.copy(loopCount = 0)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isFalse()
        assertThat(result.errors).hasSize(1)
        assertThat(result.errors[0]).contains("at least 1")
    }

    @Test
    fun `loopCount exceeding 10000 fails validation`() {
        val config = validConfig.copy(loopCount = 10001)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isFalse()
        assertThat(result.errors).hasSize(1)
        assertThat(result.errors[0]).contains("exceed")
    }

    @Test
    fun `loopCount at boundary 1 passes`() {
        val config = validConfig.copy(loopCount = 1)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isTrue()
    }

    @Test
    fun `loopCount at boundary 10000 passes`() {
        val config = validConfig.copy(loopCount = 10000)
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
    // VALIDATE — A-B RANGE MINIMUM
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `A-B range less than 1 second fails validation`() {
        val config = validConfig.copy(
            rangeStartMs = 5000L,
            rangeEndMs = 5500L  // 500ms < 1000ms minimum
        )
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isFalse()
        assertThat(result.errors.any { it.contains("at least") }).isTrue()
    }

    @Test
    fun `A-B range of exactly 1 second passes validation`() {
        val config = validConfig.copy(
            rangeStartMs = 5000L,
            rangeEndMs = 6000L  // Exactly 1 second
        )
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isTrue()
    }

    // ══════════════════════════════════════════════════════════════════════
    // VALIDATE — SPEED
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `invalid speed value fails validation`() {
        val config = validConfig.copy(speed = 1.3f)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isFalse()
        assertThat(result.errors.any { it.contains("Invalid speed") }).isTrue()
    }

    @Test
    fun `speed above maximum 2x fails validation`() {
        val config = validConfig.copy(speed = 2.5f)
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isFalse()
    }

    @Test
    fun `all preset speeds pass validation`() {
        for (preset in SpeedPresets.ALL) {
            val config = validConfig.copy(speed = preset.speed)
            val result = PlaybackConfigValidator.validate(config)
            assertThat(result.isValid).isTrue()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // VALIDATE — VIDEO PATH
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `blank videoPath fails validation`() {
        val config = validConfig.copy(videoPath = "")
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isFalse()
        assertThat(result.errors.any { it.contains("blank") }).isTrue()
    }

    // ══════════════════════════════════════════════════════════════════════
    // VALIDATE — MULTIPLE ERRORS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `config with multiple errors reports all of them`() {
        val config = PlaybackConfig(
            videoPath = "",
            rangeStartMs = -100L,
            rangeEndMs = 500L,  // end before start (start is negative, but end is positive and start is negative → end > start)
            loopCount = 0,
            speed = 99f
        )
        val result = PlaybackConfigValidator.validate(config)
        assertThat(result.isValid).isFalse()
        // Should have multiple errors: blank path, negative start, invalid loop count, invalid speed
        assertThat(result.errors.size).isAtLeast(3)
    }

    // ══════════════════════════════════════════════════════════════════════
    // SANITIZE
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `sanitize clamps loopCount below 1 to 1`() {
        val config = validConfig.copy(loopCount = 0)
        val sanitized = PlaybackConfigValidator.sanitize(config)
        assertThat(sanitized.loopCount).isEqualTo(1)
    }

    @Test
    fun `sanitize clamps loopCount above 10000 to 10000`() {
        val config = validConfig.copy(loopCount = 99999)
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
    fun `sanitize fixes too short A-B range by extending end to minimum`() {
        val config = validConfig.copy(
            rangeStartMs = 5000L,
            rangeEndMs = 5500L  // 500ms, too short
        )
        val sanitized = PlaybackConfigValidator.sanitize(config)
        assertThat(sanitized.rangeEndMs).isEqualTo(5000L + 1000L)  // Start + 1 second
    }

    @Test
    fun `sanitize resets invalid speed to default`() {
        val config = validConfig.copy(speed = 1.3f)
        val sanitized = PlaybackConfigValidator.sanitize(config)
        assertThat(sanitized.speed).isEqualTo(SpeedPresets.DEFAULT.speed)
    }

    @Test
    fun `sanitize does not modify already valid config`() {
        val config = validConfig.copy(loopCount = 5, speed = 0.75f)
        val sanitized = PlaybackConfigValidator.sanitize(config)
        assertThat(sanitized).isEqualTo(config)
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
        val config = validConfig.copy(loopCount = 0)
        assertThat(PlaybackConfigValidator.isValid(config)).isFalse()
    }

    // ══════════════════════════════════════════════════════════════════════
    // PLAYBACKCONFIG COMPUTED PROPERTIES
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `hasABLoop is true when rangeEndMs is positive and greater than rangeStartMs`() {
        val config = validConfig.copy(rangeStartMs = 5000L, rangeEndMs = 15000L)
        assertThat(config.hasABLoop).isTrue()
    }

    @Test
    fun `hasABLoop is false when rangeEndMs is negative`() {
        val config = validConfig.copy(rangeStartMs = 0L, rangeEndMs = -1L)
        assertThat(config.hasABLoop).isFalse()
    }

    @Test
    fun `willLoop is true when hasABLoop is true`() {
        val config = validConfig.copy(rangeStartMs = 5000L, rangeEndMs = 15000L, loopCount = 1)
        assertThat(config.willLoop).isTrue()
    }

    @Test
    fun `willLoop is true when loopCount is greater than 1`() {
        val config = validConfig.copy(loopCount = 3)
        assertThat(config.willLoop).isTrue()
    }

    @Test
    fun `isNormalPlayback is true when no loop and loopCount is 1`() {
        val config = validConfig.copy(rangeEndMs = -1L, loopCount = 1)
        assertThat(config.isNormalPlayback).isTrue()
    }

    @Test
    fun `isNormalPlayback is false when A-B loop is set`() {
        val config = validConfig.copy(rangeStartMs = 5000L, rangeEndMs = 15000L, loopCount = 1)
        assertThat(config.isNormalPlayback).isFalse()
    }

    @Test
    fun `displayBadge returns empty for normal playback`() {
        val config = validConfig.copy(rangeEndMs = -1L, loopCount = 1)
        assertThat(config.displayBadge).isEmpty()
    }

    @Test
    fun `displayBadge returns AB for A-B loop`() {
        val config = validConfig.copy(rangeStartMs = 5000L, rangeEndMs = 15000L, loopCount = 1)
        assertThat(config.displayBadge).isEqualTo("AB")
    }

    @Test
    fun `displayBadge returns loop count for full video loop`() {
        val config = validConfig.copy(loopCount = 5)
        assertThat(config.displayBadge).isEqualTo("x5")
    }

    // ══════════════════════════════════════════════════════════════════════
    // SPEED PRESETS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `SpeedPresets ALL contains expected values`() {
        val speeds = SpeedPresets.ALL.map { it.speed }
        assertThat(speeds).containsExactly(0.25f, 0.5f, 0.75f, 0.9f, 1.0f, 1.25f, 1.5f, 2.0f)
    }

    @Test
    fun `SpeedPresets closestTo returns exact match when available`() {
        assertThat(SpeedPresets.closestTo(0.75f).speed).isEqualTo(0.75f)
    }

    @Test
    fun `SpeedPresets closestTo returns nearest preset for non-preset value`() {
        val result = SpeedPresets.closestTo(0.8f)
        assertThat(result.speed).isEqualTo(0.75f)  // 0.75 is closer than 0.9
    }

    @Test
    fun `SpeedPresets DEFAULT is 1x`() {
        assertThat(SpeedPresets.DEFAULT.speed).isEqualTo(1.0f)
    }

    @Test
    fun `SpeedPresets maximum is 2x`() {
        val maxSpeed = SpeedPresets.ALL.maxOf { it.speed }
        assertThat(maxSpeed).isEqualTo(2.0f)
    }

    @Test
    fun `SpeedPresets minimum is 0_25x`() {
        val minSpeed = SpeedPresets.ALL.minOf { it.speed }
        assertThat(minSpeed).isEqualTo(0.25f)
    }
}
