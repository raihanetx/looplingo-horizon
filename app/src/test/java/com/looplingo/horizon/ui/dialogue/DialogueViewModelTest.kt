package com.looplingo.horizon.ui.dialogue

import com.looplingo.horizon.data.repository.CachedTranscriptionData
import com.looplingo.horizon.data.repository.TranscriptRepository
import com.looplingo.horizon.domain.model.SubtitleCue
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

class DialogueViewModelTest {

    private lateinit var transcriptRepository: TranscriptRepository
    private lateinit var viewModel: DialogueViewModel
    private lateinit var testDispatcher: TestDispatcher

    private val testCue = SubtitleCue(
        index = 0,
        startMs = 0L,
        endMs = 5000L,
        text = "Hello world"
    )

    @Before
    fun setUp() {
        testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        transcriptRepository = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): DialogueViewModel {
        return DialogueViewModel(transcriptRepository)
    }

    @Test
    fun `initial state is empty`() {
        viewModel = createViewModel()
        assertThat(viewModel.uiState.value.subtitles).isEmpty()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `loadSubtitles loads from repository`() = runTest {
        val cachedData = CachedTranscriptionData(
            cues = listOf(testCue),
            translationLanguage = null,
            sourceLanguage = "en"
        )
        coEvery { transcriptRepository.getSubtitlesWithMetaAsync(any()) } returns cachedData

        viewModel = createViewModel()
        viewModel.loadSubtitles("/test/video.mp4")

        assertThat(viewModel.uiState.value.subtitles).hasSize(1)
        assertThat(viewModel.uiState.value.subtitles[0].text).isEqualTo("Hello world")
        assertThat(viewModel.uiState.value.sourceLanguage).isEqualTo("en")
    }

    @Test
    fun `loadSubtitles sets error on failure`() = runTest {
        coEvery { transcriptRepository.getSubtitlesWithMetaAsync(any()) } throws RuntimeException("Load failed")

        viewModel = createViewModel()
        viewModel.loadSubtitles("/test/video.mp4")

        assertThat(viewModel.uiState.value.error).isNotNull()
        assertThat(viewModel.uiState.value.error).contains("Load failed")
    }

    @Test
    fun `updateCurrentSubtitle updates index`() = runTest {
        val cachedData = CachedTranscriptionData(
            cues = listOf(testCue),
            translationLanguage = null,
            sourceLanguage = "en"
        )
        coEvery { transcriptRepository.getSubtitlesWithMetaAsync(any()) } returns cachedData
        coEvery { transcriptRepository.getActiveCueIndex(any(), any()) } returns 0

        viewModel = createViewModel()
        viewModel.loadSubtitles("/test/video.mp4")
        viewModel.updateCurrentSubtitle(2000L)

        assertThat(viewModel.uiState.value.currentSubtitleIndex).isEqualTo(0)
    }

    @Test
    fun `hasSubtitles returns true when subtitles loaded`() = runTest {
        val cachedData = CachedTranscriptionData(
            cues = listOf(testCue),
            translationLanguage = null,
            sourceLanguage = "en"
        )
        coEvery { transcriptRepository.getSubtitlesWithMetaAsync(any()) } returns cachedData

        viewModel = createViewModel()
        viewModel.loadSubtitles("/test/video.mp4")

        assertThat(viewModel.hasSubtitles()).isTrue()
    }

    @Test
    fun `hasSubtitles returns false when no subtitles`() {
        viewModel = createViewModel()
        assertThat(viewModel.hasSubtitles()).isFalse()
    }

    @Test
    fun `clearError sets error to null`() {
        viewModel = createViewModel()
        viewModel.clearError()

        assertThat(viewModel.uiState.value.error).isNull()
    }
}
