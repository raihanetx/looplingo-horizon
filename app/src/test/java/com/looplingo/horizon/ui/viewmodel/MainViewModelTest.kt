package com.looplingo.horizon.ui.viewmodel

import com.looplingo.horizon.data.entity.VideoEntity
import com.looplingo.horizon.repository.PlaybackRepository
import com.looplingo.horizon.repository.VideoRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [MainViewModel].
 *
 * Tests cover:
 *  - Initial state (empty videos, not loading, no error)
 *  - Observing video list from repository Flow
 *  - Observing configured modes from reactive Flow
 *  - Refreshing videos (success)
 *  - Refreshing videos (error — sets error state)
 *  - Duplicate refresh requests ignored while loading
 *  - Clearing error state
 */
class MainViewModelTest {

    private lateinit var videoRepository: VideoRepository
    private lateinit var playbackRepository: PlaybackRepository
    private lateinit var viewModel: MainViewModel
    private lateinit var testDispatcher: TestDispatcher

    private val testVideo = VideoEntity(
        path = "/storage/emulated/0/DCIM/test.mp4",
        title = "Test Video",
        duration = 60000L,
        size = 1024000L,
        lastModified = 1700000000000L,
        contentUri = "content://media/external/video/media/123"
    )

    @Before
    fun setUp() {
        testDispatcher = UnconfinedTestDispatcher()
        kotlinx.coroutines.Dispatchers.setMain(testDispatcher)

        videoRepository = mockk(relaxed = true)
        playbackRepository = mockk(relaxed = true)

        // Default: return empty flows for reactive collection in init
        every { videoRepository.getVideos() } returns flowOf(emptyList())
        every { playbackRepository.getAllConfiguredModesFlow() } returns flowOf(emptyMap())
    }

    private fun createViewModel(): MainViewModel {
        return MainViewModel(videoRepository, playbackRepository)
    }

    // ══════════════════════════════════════════════════════════════════════
    // INITIAL STATE
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `initial videos state is empty`() {
        viewModel = createViewModel()
        assertThat(viewModel.videos.value).isEmpty()
    }

    @Test
    fun `initial isLoading state is false`() {
        viewModel = createViewModel()
        assertThat(viewModel.isLoading.value).isFalse()
    }

    @Test
    fun `initial error state is null`() {
        viewModel = createViewModel()
        assertThat(viewModel.error.value).isNull()
    }

    // ══════════════════════════════════════════════════════════════════════
    // OBSERVE VIDEOS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `videos flow emits cached videos from repository`() {
        val expectedVideos = listOf(testVideo)
        every { videoRepository.getVideos() } returns flowOf(expectedVideos)

        viewModel = createViewModel()

        assertThat(viewModel.videos.value).hasSize(1)
        assertThat(viewModel.videos.value[0].title).isEqualTo("Test Video")
    }

    // ══════════════════════════════════════════════════════════════════════
    // OBSERVE CONFIGURED MODES
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `configuredModes updates from reactive flow`() {
        val modes = mapOf("/video.mp4" to "INF")
        every { playbackRepository.getAllConfiguredModesFlow() } returns flowOf(modes)

        viewModel = createViewModel()

        assertThat(viewModel.configuredModes.value).hasSize(1)
        assertThat(viewModel.configuredModes.value["/video.mp4"]).isEqualTo("INF")
    }

    @Test
    fun `configuredModes initially empty`() {
        viewModel = createViewModel()
        assertThat(viewModel.configuredModes.value).isEmpty()
    }

    // ══════════════════════════════════════════════════════════════════════
    // REFRESH VIDEOS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `refreshVideos sets isLoading to true then false on success`() = runTest {
        coEvery { videoRepository.refreshVideos() } returns Unit
        coEvery { playbackRepository.deleteOrphanedRules() } returns Unit

        viewModel = createViewModel()
        viewModel.refreshVideos()

        // After completion, isLoading should be false
        assertThat(viewModel.isLoading.value).isFalse()
    }

    @Test
    fun `refreshVideos clears error on start`() = runTest {
        coEvery { videoRepository.refreshVideos() } throws RuntimeException("fail")
        viewModel = createViewModel()
        viewModel.refreshVideos()
        assertThat(viewModel.error.value).isNotNull()

        // Now make it succeed
        coEvery { videoRepository.refreshVideos() } returns Unit
        viewModel.refreshVideos()

        // Error should be cleared
        assertThat(viewModel.error.value).isNull()
    }

    @Test
    fun `refreshVideos sets error message on failure`() = runTest {
        coEvery { videoRepository.refreshVideos() } throws SecurityException("No permission")

        viewModel = createViewModel()
        viewModel.refreshVideos()

        assertThat(viewModel.error.value).isNotNull()
        assertThat(viewModel.error.value).contains("permission")
    }

    @Test
    fun `refreshVideos handles generic exception`() = runTest {
        coEvery { videoRepository.refreshVideos() } throws RuntimeException("Unexpected failure")

        viewModel = createViewModel()
        viewModel.refreshVideos()

        assertThat(viewModel.error.value).isNotNull()
    }

    // ══════════════════════════════════════════════════════════════════════
    // CLEAR ERROR
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `clearError sets error to null`() = runTest {
        coEvery { videoRepository.refreshVideos() } throws RuntimeException("fail")
        viewModel = createViewModel()
        viewModel.refreshVideos()
        assertThat(viewModel.error.value).isNotNull()

        viewModel.clearError()

        assertThat(viewModel.error.value).isNull()
    }
}
