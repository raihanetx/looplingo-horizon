package com.looplingo.horizon.ui.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looplingo.horizon.data.repository.PlaybackRepository
import com.looplingo.horizon.domain.audio.service.AudioPlaybackService
import com.looplingo.horizon.domain.model.PlaybackConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playbackRepository: PlaybackRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var isInitialized = false

    fun initialize(videoPath: String, videoTitle: String) {
        if (isInitialized) return
        isInitialized = true

        _uiState.update {
            it.copy(
                videoPath = videoPath,
                videoTitle = videoTitle
            )
        }

        loadPlaybackConfig(videoPath)
        startPositionPolling()
    }

    private fun loadPlaybackConfig(videoPath: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val config = playbackRepository.getConfigForVideo(videoPath)
                _uiState.update {
                    it.copy(
                        playbackConfig = config ?: PlaybackConfig(videoPath = videoPath),
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load playback config")
                _uiState.update {
                    it.copy(
                        error = "Failed to load config: ${e.message}",
                        isLoading = false
                    )
                }
            }
        }
    }

    private fun startPositionPolling() {
        viewModelScope.launch {
            while (isActive) {
                val servicePlaying = AudioPlaybackService.isPlaying
                val servicePosition = AudioPlaybackService.currentPositionMs
                val serviceDuration = AudioPlaybackService.durationMs

                _uiState.update {
                    it.copy(
                        isPlaying = servicePlaying,
                        currentPositionMs = servicePosition,
                        durationMs = serviceDuration
                    )
                }

                delay(500L)
            }
        }
    }

    fun setCurrentTab(tab: Int) {
        _uiState.update { it.copy(currentTab = tab) }
    }

    fun togglePlayback() {
        AudioPlaybackService.togglePlayback(context)
    }

    fun seekTo(positionMs: Long) {
        val videoPath = _uiState.value.videoPath
        AudioPlaybackService.seekToPosition(context, videoPath, positionMs)
    }

    fun updatePlaybackConfig(config: PlaybackConfig) {
        _uiState.update { it.copy(playbackConfig = config) }
    }

    fun savePlaybackConfig() {
        val config = _uiState.value.playbackConfig
        viewModelScope.launch {
            try {
                playbackRepository.saveConfig(config)
                if (config.hasABLoop) {
                    AudioPlaybackService.setABLoop(
                        context,
                        config.videoPath,
                        config.rangeStartMs,
                        config.rangeEndMs,
                        config.loopCount
                    )
                } else {
                    AudioPlaybackService.clearABLoop(context, config.videoPath)
                }
                Timber.i("Saved playback config for: ${config.videoPath}")
            } catch (e: Exception) {
                Timber.e(e, "Failed to save playback config")
                _uiState.update {
                    it.copy(error = "Failed to save config: ${e.message}")
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
    }
}
