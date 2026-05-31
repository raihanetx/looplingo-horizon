package com.looplingo.horizon.ui.home

import com.looplingo.horizon.data.local.entity.VideoEntity
import com.looplingo.horizon.data.repository.PlaybackRepository
import com.looplingo.horizon.data.repository.VideoRepository
import com.looplingo.horizon.domain.model.SortOrder
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

class HomeViewModelTest {

    private lateinit var videoRepository: VideoRepository
    private lateinit var playbackRepository: PlaybackRepository
    private lateinit var viewModel: HomeViewModel
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
        Dispatchers.setMain(testDispatcher)

        videoRepository = mockk(relaxed = true)
        playbackRepository = mockk(relaxed = true)

        every { videoRepository.getVideos(any()) } returns flowOf(emptyList())
        every { playbackRepository.getAllConfiguredModesFlow() } returns flowOf(emptyMap())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): HomeViewModel {
        return HomeViewModel(videoRepository, playbackRepository)
    }

    @Test
    fun `initial state is empty`() {
        viewModel = createViewModel()
        assertThat(viewModel.uiState.value.videos).isEmpty()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `videos loaded from repository`() {
        val expectedVideos = listOf(testVideo)
        every { videoRepository.getVideos(any()) } returns flowOf(expectedVideos)

        viewModel = createViewModel()

        assertThat(viewModel.uiState.value.videos).hasSize(1)
        assertThat(viewModel.uiState.value.videos[0].title).isEqualTo("Test Video")
    }

    @Test
    fun `configuredModes updates from flow`() {
        val modes = mapOf("/video.mp4" to "AB")
        every { playbackRepository.getAllConfiguredModesFlow() } returns flowOf(modes)

        viewModel = createViewModel()

        assertThat(viewModel.uiState.value.configuredModes).hasSize(1)
        assertThat(viewModel.uiState.value.configuredModes["/video.mp4"]).isEqualTo("AB")
    }

    @Test
    fun `refreshVideos calls repository`() = runTest {
        coEvery { videoRepository.refreshVideos() } returns Unit
        coEvery { playbackRepository.deleteOrphanedRules() } returns Unit

        viewModel = createViewModel()
        viewModel.refreshVideos()

        coVerify { videoRepository.refreshVideos() }
        coVerify { playbackRepository.deleteOrphanedRules() }
    }

    @Test
    fun `refreshVideos sets error on failure`() = runTest {
        coEvery { videoRepository.refreshVideos() } throws RuntimeException("Scan failed")

        viewModel = createViewModel()
        viewModel.refreshVideos()

        assertThat(viewModel.uiState.value.error).isNotNull()
        assertThat(viewModel.uiState.value.error).contains("Scan failed")
    }

    @Test
    fun `setSortOrder updates sort order`() {
        viewModel = createViewModel()
        viewModel.setSortOrder(SortOrder.TITLE)

        assertThat(viewModel.uiState.value.sortOrder).isEqualTo(SortOrder.TITLE)
    }

    @Test
    fun `updateSearchQuery updates query`() {
        viewModel = createViewModel()
        viewModel.updateSearchQuery("test query")

        assertThat(viewModel.uiState.value.searchQuery).isEqualTo("test query")
    }

    @Test
    fun `toggleSearch toggles visibility`() {
        viewModel = createViewModel()
        assertThat(viewModel.uiState.value.isSearchVisible).isFalse()

        viewModel.toggleSearch()
        assertThat(viewModel.uiState.value.isSearchVisible).isTrue()

        viewModel.toggleSearch()
        assertThat(viewModel.uiState.value.isSearchVisible).isFalse()
    }

    @Test
    fun `clearError sets error to null`() = runTest {
        coEvery { videoRepository.refreshVideos() } throws RuntimeException("fail")
        viewModel = createViewModel()
        viewModel.refreshVideos()
        assertThat(viewModel.uiState.value.error).isNotNull()

        viewModel.clearError()
        assertThat(viewModel.uiState.value.error).isNull()
    }
}
