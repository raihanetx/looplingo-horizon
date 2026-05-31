package com.looplingo.horizon.ui.loop

import android.content.Context
import com.looplingo.horizon.data.local.dao.SavedTimestampDao
import com.looplingo.horizon.data.local.entity.SavedTimestampEntity
import com.looplingo.horizon.data.repository.PlaybackRepository
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

class LoopViewModelTest {

    private lateinit var context: Context
    private lateinit var playbackRepository: PlaybackRepository
    private lateinit var savedTimestampDao: SavedTimestampDao
    private lateinit var viewModel: LoopViewModel
    private lateinit var testDispatcher: TestDispatcher

    private val testTimestamp = SavedTimestampEntity(
        id = 1,
        videoPath = "/storage/emulated/0/DCIM/test.mp4",
        label = "0:00-1:00",
        rangeStartMs = 0L,
        rangeEndMs = 60000L,
        loopCount = 3,
        createdAt = System.currentTimeMillis()
    )

    @Before
    fun setUp() {
        testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        context = mockk(relaxed = true)
        playbackRepository = mockk(relaxed = true)
        savedTimestampDao = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): LoopViewModel {
        return LoopViewModel(context, playbackRepository, savedTimestampDao)
    }

    @Test
    fun `initial state is empty`() {
        viewModel = createViewModel()
        assertThat(viewModel.uiState.value.savedTimestamps).isEmpty()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `loadTimestamps loads from dao`() {
        val timestamps = listOf(testTimestamp)
        every { savedTimestampDao.getTimestampsForVideo(any()) } returns flowOf(timestamps)

        viewModel = createViewModel()
        viewModel.loadTimestamps("/test/video.mp4")

        assertThat(viewModel.uiState.value.savedTimestamps).hasSize(1)
        assertThat(viewModel.uiState.value.savedTimestamps[0].label).isEqualTo("0:00-1:00")
    }

    @Test
    fun `updateRangeStart updates start time`() {
        viewModel = createViewModel()
        viewModel.updateRangeStart(5000L)

        assertThat(viewModel.uiState.value.rangeStartMs).isEqualTo(5000L)
    }

    @Test
    fun `updateRangeEnd updates end time`() {
        viewModel = createViewModel()
        viewModel.updateRangeEnd(60000L)

        assertThat(viewModel.uiState.value.rangeEndMs).isEqualTo(60000L)
    }

    @Test
    fun `updateLoopCount updates count`() {
        viewModel = createViewModel()
        viewModel.updateLoopCount(5)

        assertThat(viewModel.uiState.value.loopCount).isEqualTo(5)
    }

    @Test
    fun `updateLoopCount coerces minimum to 1`() {
        viewModel = createViewModel()
        viewModel.updateLoopCount(0)

        assertThat(viewModel.uiState.value.loopCount).isEqualTo(1)
    }

    @Test
    fun `toggleFormVisibility toggles`() {
        viewModel = createViewModel()
        assertThat(viewModel.uiState.value.isFormVisible).isTrue()

        viewModel.toggleFormVisibility()
        assertThat(viewModel.uiState.value.isFormVisible).isFalse()
    }

    @Test
    fun `deleteTimestamp calls dao`() = runTest {
        coEvery { savedTimestampDao.deleteById(any()) } returns Unit

        viewModel = createViewModel()
        viewModel.deleteTimestamp(testTimestamp)

        coVerify { savedTimestampDao.deleteById(1) }
    }

    @Test
    fun `clearError sets error to null`() {
        viewModel = createViewModel()
        viewModel.clearError()

        assertThat(viewModel.uiState.value.error).isNull()
    }
}
