package com.looplingo.horizon.ui.loop

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looplingo.horizon.data.local.dao.SavedTimestampDao
import com.looplingo.horizon.data.local.entity.SavedTimestampEntity
import com.looplingo.horizon.data.repository.PlaybackRepository
import com.looplingo.horizon.domain.audio.service.AudioPlaybackService
import com.looplingo.horizon.domain.model.PlaybackConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class LoopViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playbackRepository: PlaybackRepository,
    private val savedTimestampDao: SavedTimestampDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoopUiState())
    val uiState: StateFlow<LoopUiState> = _uiState.asStateFlow()

    private var currentVideoPath: String = ""

    fun loadTimestamps(videoPath: String) {
        currentVideoPath = videoPath
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                savedTimestampDao.getTimestampsForVideo(videoPath).collect { timestamps ->
                    _uiState.update {
                        it.copy(
                            savedTimestamps = timestamps,
                            isLoading = false
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load timestamps")
                _uiState.update {
                    it.copy(
                        error = "Failed to load timestamps: ${e.message}",
                        isLoading = false
                    )
                }
            }
        }
    }

    fun updateRangeStart(startMs: Long) {
        _uiState.update { it.copy(rangeStartMs = startMs) }
    }

    fun updateRangeEnd(endMs: Long) {
        _uiState.update { it.copy(rangeEndMs = endMs) }
    }

    fun updateLoopCount(count: Int) {
        _uiState.update { it.copy(loopCount = count.coerceAtLeast(1)) }
    }

    fun toggleFormVisibility() {
        _uiState.update { it.copy(isFormVisible = !it.isFormVisible) }
    }

    fun setCurrentPosition(positionMs: Long) {
        _uiState.update { it.copy(currentPositionMs = positionMs) }
    }

    fun saveLoop() {
        val state = _uiState.value
        if (state.rangeStartMs >= state.rangeEndMs) {
            _uiState.update { it.copy(error = "Start time must be before end time") }
            return
        }

        viewModelScope.launch {
            try {
                val config = PlaybackConfig(
                    videoPath = currentVideoPath,
                    rangeStartMs = state.rangeStartMs,
                    rangeEndMs = state.rangeEndMs,
                    loopCount = state.loopCount
                )
                playbackRepository.saveConfig(config)

                AudioPlaybackService.setABLoop(
                    context,
                    currentVideoPath,
                    state.rangeStartMs,
                    state.rangeEndMs,
                    state.loopCount
                )

                val label = "${formatMs(state.rangeStartMs)}-${formatMs(state.rangeEndMs)}"
                savedTimestampDao.insertTimestamp(
                    SavedTimestampEntity(
                        videoPath = currentVideoPath,
                        label = label,
                        rangeStartMs = state.rangeStartMs,
                        rangeEndMs = state.rangeEndMs,
                        loopCount = state.loopCount
                    )
                )

                _uiState.update {
                    it.copy(
                        rangeStartMs = 0L,
                        rangeEndMs = 0L,
                        loopCount = 1
                    )
                }
                Timber.i("Saved loop for: $currentVideoPath")
            } catch (e: Exception) {
                Timber.e(e, "Failed to save loop")
                _uiState.update {
                    it.copy(error = "Failed to save loop: ${e.message}")
                }
            }
        }
    }

    fun deleteTimestamp(timestamp: SavedTimestampEntity) {
        viewModelScope.launch {
            try {
                savedTimestampDao.deleteById(timestamp.id)
                Timber.i("Deleted timestamp: ${timestamp.label}")
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete timestamp")
                _uiState.update {
                    it.copy(error = "Failed to delete timestamp: ${e.message}")
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun formatMs(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }
}
