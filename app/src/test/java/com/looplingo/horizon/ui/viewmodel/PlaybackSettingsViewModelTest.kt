package com.looplingo.horizon.ui.viewmodel

import com.looplingo.horizon.data.dao.SavedTimestampDao
import com.looplingo.horizon.model.PlaybackConfig
import com.looplingo.horizon.model.SpeedPresets
import com.looplingo.horizon.repository.PlaybackRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PlaybackSettingsViewModel].
 *
 * Tests cover the simplified A-B loop system:
 *  - Initial state
 *  - Loading config for a video (saved and default)
 *  - Updating individual config fields (range, loop count, speed)
 *  - Saving config (success and failure)
 *  - Error state management (saveError, clearSaveError)
 */
class PlaybackSettingsViewModelTest {

    private lateinit var repository: PlaybackRepository
    private lateinit var savedTimestampDao: SavedTimestampDao
    private lateinit var viewModel: PlaybackSettingsViewModel

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = mockk(relaxed = true)
        savedTimestampDao = mockk(relaxed = true)
        viewModel = PlaybackSettingsViewModel(repository, savedTimestampDao)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    // ══════════════════════════════════════════════════════════════════════
    // INITIAL STATE
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `initial config has empty videoPath and default values`() {
        val config = viewModel.config.value
        assertThat(config.videoPath).isEmpty()
        assertThat(config.loopCount).isEqualTo(1)
        assertThat(config.speed).isEqualTo(1.0f)
        assertThat(config.rangeEndMs).isEqualTo(-1L)
        assertThat(config.isNormalPlayback).isTrue()
    }

    @Test
    fun `initial isSaved is false`() {
        assertThat(viewModel.isSaved.value).isFalse()
    }

    @Test
    fun `initial saveError is null`() {
        assertThat(viewModel.saveError.value).isNull()
    }

    // ══════════════════════════════════════════════════════════════════════
    // LOAD CONFIG
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `loadConfigForVideo with saved config updates state`() = runTest {
        val savedConfig = PlaybackConfig(
            videoPath = "/video.mp4",
            rangeStartMs = 5000L,
            rangeEndMs = 30000L,
            loopCount = 5,
            speed = 0.75f
        )
        coEvery { repository.getConfigForVideo("/video.mp4") } returns savedConfig

        viewModel.loadConfigForVideo("/video.mp4")

        assertThat(viewModel.config.value.rangeStartMs).isEqualTo(5000L)
        assertThat(viewModel.config.value.rangeEndMs).isEqualTo(30000L)
        assertThat(viewModel.config.value.loopCount).isEqualTo(5)
        assertThat(viewModel.config.value.speed).isEqualTo(0.75f)
    }

    @Test
    fun `loadConfigForVideo without saved config uses defaults with videoPath`() = runTest {
        coEvery { repository.getConfigForVideo("/new.mp4") } returns null

        viewModel.loadConfigForVideo("/new.mp4")

        assertThat(viewModel.config.value.videoPath).isEqualTo("/new.mp4")
        assertThat(viewModel.config.value.loopCount).isEqualTo(1)
        assertThat(viewModel.config.value.speed).isEqualTo(1.0f)
        assertThat(viewModel.config.value.isNormalPlayback).isTrue()
    }

    @Test
    fun `loadConfigForVideo handles repository exception`() = runTest {
        coEvery { repository.getConfigForVideo("/video.mp4") } throws RuntimeException("DB error")

        viewModel.loadConfigForVideo("/video.mp4")

        // Should not crash — videoPath should still be set
        assertThat(viewModel.config.value.videoPath).isEqualTo("/video.mp4")
    }

    // ══════════════════════════════════════════════════════════════════════
    // UPDATE CONFIG
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `updateConfig with rangeStartMs and rangeEndMs`() {
        viewModel.updateConfig(rangeStartMs = 5000L, rangeEndMs = 30000L)

        assertThat(viewModel.config.value.rangeStartMs).isEqualTo(5000L)
        assertThat(viewModel.config.value.rangeEndMs).isEqualTo(30000L)
    }

    @Test
    fun `updateConfig with loopCount updates only loopCount`() {
        viewModel.updateConfig(loopCount = 10)

        assertThat(viewModel.config.value.loopCount).isEqualTo(10)
    }

    @Test
    fun `updateConfig with speed updates only speed`() {
        viewModel.updateConfig(speed = 0.5f)

        assertThat(viewModel.config.value.speed).isEqualTo(0.5f)
    }

    @Test
    fun `updateConfig with 0_25x speed`() {
        viewModel.updateConfig(speed = 0.25f)

        assertThat(viewModel.config.value.speed).isEqualTo(0.25f)
    }

    @Test
    fun `updateConfig with 2x speed`() {
        viewModel.updateConfig(speed = 2.0f)

        assertThat(viewModel.config.value.speed).isEqualTo(2.0f)
    }

    @Test
    fun `updateConfig with null parameters preserves existing values`() {
        viewModel.updateConfig(rangeStartMs = 5000L, loopCount = 7, speed = 0.75f)
        viewModel.updateConfig(loopCount = 10)  // Only update loopCount

        assertThat(viewModel.config.value.rangeStartMs).isEqualTo(5000L)
        assertThat(viewModel.config.value.loopCount).isEqualTo(10)
        assertThat(viewModel.config.value.speed).isEqualTo(0.75f)
    }

    // ══════════════════════════════════════════════════════════════════════
    // SAVE CONFIG
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `saveConfig on success sets isSaved to true`() = runTest {
        coEvery { repository.saveConfig(any()) } returns true

        viewModel.saveConfig()

        assertThat(viewModel.isSaved.value).isTrue()
    }

    @Test
    fun `saveConfig on failure sets saveError`() = runTest {
        coEvery { repository.saveConfig(any()) } returns false

        viewModel.saveConfig()

        assertThat(viewModel.saveError.value).isNotNull()
    }

    @Test
    fun `saveConfig on exception sets saveError with exception message`() = runTest {
        coEvery { repository.saveConfig(any()) } throws RuntimeException("Disk full")

        viewModel.saveConfig()

        assertThat(viewModel.saveError.value).contains("Disk full")
    }

    @Test
    fun `saveConfig sanitizes invalid config before saving`() = runTest {
        viewModel.updateConfig(loopCount = 0)  // Invalid — will be sanitized to 1
        coEvery { repository.saveConfig(any()) } returns true

        viewModel.saveConfig()

        // The config should be sanitized — loopCount clamped to 1
        assertThat(viewModel.config.value.loopCount).isEqualTo(1)
    }

    // ══════════════════════════════════════════════════════════════════════
    // ERROR STATE MANAGEMENT
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `clearSaveError sets saveError to null`() = runTest {
        coEvery { repository.saveConfig(any()) } returns false
        viewModel.saveConfig()
        assertThat(viewModel.saveError.value).isNotNull()

        viewModel.clearSaveError()

        assertThat(viewModel.saveError.value).isNull()
    }

    // ══════════════════════════════════════════════════════════════════════
    // DELETE CONFIG
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `deleteConfig resets to default values`() = runTest {
        coEvery { repository.getConfigForVideo("/video.mp4") } returns PlaybackConfig(
            videoPath = "/video.mp4",
            rangeStartMs = 5000L,
            rangeEndMs = 30000L,
            loopCount = 5,
            speed = 0.75f
        )
        viewModel.loadConfigForVideo("/video.mp4")
        assertThat(viewModel.config.value.loopCount).isEqualTo(5)

        coEvery { repository.deleteConfigForVideo("/video.mp4") } returns true
        viewModel.deleteConfig()

        assertThat(viewModel.config.value.loopCount).isEqualTo(1)
        assertThat(viewModel.config.value.speed).isEqualTo(1.0f)
        assertThat(viewModel.config.value.rangeStartMs).isEqualTo(0L)
        assertThat(viewModel.config.value.rangeEndMs).isEqualTo(-1L)
    }
}
