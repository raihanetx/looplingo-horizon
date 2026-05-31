package com.looplingo.horizon.ui.note

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looplingo.horizon.data.local.dao.NoteDao
import com.looplingo.horizon.data.local.entity.NoteEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class NoteViewModel @Inject constructor(
    private val noteDao: NoteDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(NoteUiState())
    val uiState: StateFlow<NoteUiState> = _uiState.asStateFlow()

    private var currentVideoPath: String = ""

    fun loadNotes(videoPath: String) {
        currentVideoPath = videoPath
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                noteDao.getNotesForVideo(videoPath).collect { notes ->
                    _uiState.update {
                        it.copy(
                            notes = notes,
                            isLoading = false
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load notes")
                _uiState.update {
                    it.copy(
                        error = "Failed to load notes: ${e.message}",
                        isLoading = false
                    )
                }
            }
        }
    }

    fun updateNoteText(text: String) {
        _uiState.update { it.copy(noteText = text) }
    }

    fun toggleFormVisibility() {
        _uiState.update { it.copy(isFormVisible = !it.isFormVisible) }
    }

    fun saveNote(currentPositionMs: Long = 0L) {
        val text = _uiState.value.noteText.trim()
        if (text.isEmpty()) {
            _uiState.update { it.copy(error = "Note text cannot be empty") }
            return
        }

        viewModelScope.launch {
            try {
                noteDao.insertNote(
                    NoteEntity(
                        videoPath = currentVideoPath,
                        text = text,
                        timestampMs = currentPositionMs
                    )
                )
                _uiState.update { it.copy(noteText = "") }
                Timber.i("Saved note for: $currentVideoPath")
            } catch (e: Exception) {
                Timber.e(e, "Failed to save note")
                _uiState.update {
                    it.copy(error = "Failed to save note: ${e.message}")
                }
            }
        }
    }

    fun deleteNote(note: NoteEntity) {
        viewModelScope.launch {
            try {
                noteDao.deleteNote(note)
                Timber.i("Deleted note: ${note.id}")
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete note")
                _uiState.update {
                    it.copy(error = "Failed to delete note: ${e.message}")
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
