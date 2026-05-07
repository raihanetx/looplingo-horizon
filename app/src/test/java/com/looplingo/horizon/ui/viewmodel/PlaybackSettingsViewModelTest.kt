package com.looplingo.horizon.ui.viewmodel

import com.looplingo.horizon.model.LoopMode
import com.looplingo.horizon.model.PlaybackConfig
import com.looplingo.horizon.model.StartAction
import com.looplingo.horizon.repository.PlaybackRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PlaybackSettingsViewModel].
 *
 * Tests cover:
 *  - Initial state
 *  - Loading config for a video (saved and default)
 *  - Updating individual config fields
 *  - Saving config (success and failure)
 *  - Validation via validateCurrentConfig()
 *  - Error state management (saveError, clearSaveError, resetSavedState)
 */
class PlaybackSettingsViewModelTest {

    private lateinit var repository: PlaybackRepository
    private lateinit var viewModel: PlaybackSettingsViewModel

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = mockk(relaxed = true)
        viewModel = PlaybackSettingsViewModel(repository)
    }

    // ══════════════════════════════════════════════════════════════════════
    // INITIAL STATE
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `initial config has empty videoPath and LOOP_INFINITE`() {
        val config = viewModel.config.value
        assertThat(config.videoPath).isEmpty()
        assertThat(config.loopMode).isEqualTo(LoopMode.LOOP_INFINITE)
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
            loopMode = LoopMode.LOOP_X_TIMES,
            loopCount = 5,
            autoAdvance = true
        )
        coEvery { repository.getConfigForVideo("/video.mp4") } returns savedConfig

        viewModel.loadConfigForVideo("/video.mp4")

        assertThat(viewModel.config.value.loopMode).isEqualTo(LoopMode.LOOP_X_TIMES)
        assertThat(viewModel.config.value.loopCount).isEqualTo(5)
        assertThat(viewModel.config.value.autoAdvance).isTrue()
    }

    @Test
    fun `loadConfigForVideo without saved config uses defaults with videoPath`() = runTest {
        coEvery { repository.getConfigForVideo("/new.mp4") } returns null

        viewModel.loadConfigForVideo("/new.mp4")

        assertThat(viewModel.config.value.videoPath).isEqualTo("/new.mp4")
        assertThat(viewModel.config.value.loopMode).isEqualTo(LoopMode.LOOP_INFINITE)
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
    fun `updateConfig with loopMode updates only loopMode`() {
        viewModel.updateConfig(loopMode = LoopMode.FLOW)

        assertThat(viewModel.config.value.loopMode).isEqualTo(LoopMode.FLOW)
        // Other fields should remain at their default values
        assertThat(viewModel.config.value.loopCount).isEqualTo(1)
    }

    @Test
    fun `updateConfig with loopCount updates only loopCount`() {
        viewModel.updateConfig(loopCount = 10)

        assertThat(viewModel.config.value.loopCount).isEqualTo(10)
    }

    @Test
    fun `updateConfig with rangeStartMs and rangeEndMs`() {
        viewModel.updateConfig(rangeStartMs = 5000L, rangeEndMs = 30000L)

        assertThat(viewModel.config.value.rangeStartMs).isEqualTo(5000L)
        assertThat(viewModel.config.value.rangeEndMs).isEqualTo(30000L)
    }

    @Test
    fun `updateConfig with autoAdvance`() {
        viewModel.updateConfig(autoAdvance = true)

        assertThat(viewModel.config.value.autoAdvance).isTrue()
    }

    @Test
    fun `updateConfig with startAction`() {
        viewModel.updateConfig(startAction = StartAction.WAIT_MANUAL)

        assertThat(viewModel.config.value.startAction).isEqualTo(StartAction.WAIT_MANUAL)
    }

    @Test
    fun `updateConfig with null parameters preserves existing values`() {
        viewModel.updateConfig(loopMode = LoopMode.A_B_PIN, loopCount = 7)
        viewModel.updateConfig(autoAdvance = true)  // Only update autoAdvance

        assertThat(viewModel.config.value.loopMode).isEqualTo(LoopMode.A_B_PIN)
        assertThat(viewModel.config.value.loopCount).isEqualTo(7)
        assertThat(viewModel.config.value.autoAdvance).isTrue()
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
        assertThat(viewModel.saveError.value).contains("database error")
    }

    @Test
    fun `saveConfig on exception sets saveError with exception message`() = runTest {
        coEvery { repository.saveConfig(any()) } throws RuntimeException("Disk full")

        viewModel.saveConfig()

        assertThat(viewModel.saveError.value).contains("Disk full")
    }

    @Test
    fun `saveConfig sanitizes invalid config before saving`() = runTest {
        viewModel.updateConfig(loopMode = LoopMode.LOOP_X_TIMES, loopCount = 0)
        coEvery { repository.saveConfig(any()) } returns true

        viewModel.saveConfig()

        // The config should be sanitized — loopCount clamped to 1
        assertThat(viewModel.config.value.loopCount).isEqualTo(1)
    }

    // ══════════════════════════════════════════════════════════════════════
    // VALIDATE CURRENT CONFIG
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `validateCurrentConfig returns valid for good config`() = runTest {
        // Return a valid saved config so videoPath is not blank
        coEvery { repository.getConfigForVideo("/video.mp4") } returns PlaybackConfig(
            videoPath = "/video.mp4",
            loopMode = LoopMode.LOOP_INFINITE
        )
        viewModel.loadConfigForVideo("/video.mp4")
        viewModel.updateConfig(loopMode = LoopMode.LOOP_INFINITE)

        val result = viewModel.validateCurrentConfig()
        assertThat(result.isValid).isTrue()
    }

    @Test
    fun `validateCurrentConfig returns invalid for A_B_PIN without end`() = runTest {
        coEvery { repository.getConfigForVideo("/video.mp4") } returns PlaybackConfig(
            videoPath = "/video.mp4",
            loopMode = LoopMode.A_B_PIN,
            rangeEndMs = -1L
        )
        viewModel.loadConfigForVideo("/video.mp4")
        viewModel.updateConfig(
            loopMode = LoopMode.A_B_PIN,
            rangeEndMs = -1L
        )

        val result = viewModel.validateCurrentConfig()
        assertThat(result.isValid).isFalse()
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

    @Test
    fun `resetSavedState clears both isSaved and saveError`() = runTest {
        coEvery { repository.saveConfig(any()) } returns true
        viewModel.saveConfig()
        assertThat(viewModel.isSaved.value).isTrue()

        viewModel.resetSavedState()

        assertThat(viewModel.isSaved.value).isFalse()
        assertThat(viewModel.saveError.value).isNull()
    }
}
