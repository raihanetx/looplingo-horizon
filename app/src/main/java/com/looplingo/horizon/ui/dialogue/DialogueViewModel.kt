package com.looplingo.horizon.ui.dialogue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looplingo.horizon.data.repository.TranscriptRepository
import com.looplingo.horizon.domain.model.SubtitleCue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class DialogueViewModel @Inject constructor(
    private val transcriptRepository: TranscriptRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DialogueUiState())
    val uiState: StateFlow<DialogueUiState> = _uiState.asStateFlow()

    private var currentVideoPath: String = ""

    fun loadSubtitles(videoPath: String) {
        currentVideoPath = videoPath
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val cachedData = transcriptRepository.getSubtitlesWithMetaAsync(videoPath)
                _uiState.update {
                    it.copy(
                        subtitles = cachedData.cues,
                        translationLanguage = cachedData.translationLanguage,
                        sourceLanguage = cachedData.sourceLanguage,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load subtitles")
                _uiState.update {
                    it.copy(
                        error = "Failed to load subtitles: ${e.message}",
                        isLoading = false
                    )
                }
            }
        }
    }

    fun updateCurrentSubtitle(positionMs: Long) {
        val subtitles = _uiState.value.subtitles
        if (subtitles.isEmpty()) return

        val index = transcriptRepository.getActiveCueIndex(currentVideoPath, positionMs)
        if (index != _uiState.value.currentSubtitleIndex) {
            _uiState.update { it.copy(currentSubtitleIndex = index) }
        }
    }

    fun hasSubtitles(): Boolean {
        return _uiState.value.subtitles.isNotEmpty()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
