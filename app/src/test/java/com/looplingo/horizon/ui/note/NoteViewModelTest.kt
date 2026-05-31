package com.looplingo.horizon.ui.note

import com.looplingo.horizon.data.local.dao.NoteDao
import com.looplingo.horizon.data.local.entity.NoteEntity
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

class NoteViewModelTest {

    private lateinit var noteDao: NoteDao
    private lateinit var viewModel: NoteViewModel
    private lateinit var testDispatcher: TestDispatcher

    private val testNote = NoteEntity(
        id = 1,
        videoPath = "/storage/emulated/0/DCIM/test.mp4",
        text = "Test note content",
        timestampMs = 30000L,
        createdAt = System.currentTimeMillis()
    )

    @Before
    fun setUp() {
        testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        noteDao = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): NoteViewModel {
        return NoteViewModel(noteDao)
    }

    @Test
    fun `initial state is empty`() {
        viewModel = createViewModel()
        assertThat(viewModel.uiState.value.notes).isEmpty()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `loadNotes loads from dao`() {
        val notes = listOf(testNote)
        every { noteDao.getNotesForVideo(any()) } returns flowOf(notes)

        viewModel = createViewModel()
        viewModel.loadNotes("/test/video.mp4")

        assertThat(viewModel.uiState.value.notes).hasSize(1)
        assertThat(viewModel.uiState.value.notes[0].text).isEqualTo("Test note content")
    }

    @Test
    fun `updateNoteText updates text`() {
        viewModel = createViewModel()
        viewModel.updateNoteText("New note text")

        assertThat(viewModel.uiState.value.noteText).isEqualTo("New note text")
    }

    @Test
    fun `toggleFormVisibility toggles`() {
        viewModel = createViewModel()
        assertThat(viewModel.uiState.value.isFormVisible).isTrue()

        viewModel.toggleFormVisibility()
        assertThat(viewModel.uiState.value.isFormVisible).isFalse()
    }

    @Test
    fun `saveNote with empty text sets error`() {
        viewModel = createViewModel()
        viewModel.updateNoteText("")
        viewModel.saveNote()

        assertThat(viewModel.uiState.value.error).isNotNull()
        assertThat(viewModel.uiState.value.error).contains("empty")
    }

    @Test
    fun `saveNote with valid text calls dao`() = runTest {
        coEvery { noteDao.insertNote(any()) } returns 1L

        viewModel = createViewModel()
        viewModel.loadNotes("/test/video.mp4")
        viewModel.updateNoteText("Valid note")
        viewModel.saveNote()

        coVerify { noteDao.insertNote(any()) }
        assertThat(viewModel.uiState.value.noteText).isEmpty()
    }

    @Test
    fun `deleteNote calls dao`() = runTest {
        coEvery { noteDao.deleteNote(any()) } returns Unit

        viewModel = createViewModel()
        viewModel.deleteNote(testNote)

        coVerify { noteDao.deleteNote(testNote) }
    }

    @Test
    fun `clearError sets error to null`() {
        viewModel = createViewModel()
        viewModel.clearError()

        assertThat(viewModel.uiState.value.error).isNull()
    }
}
