package com.looplingo.horizon.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looplingo.horizon.data.dao.SavedTimestampDao
import com.looplingo.horizon.data.entity.SavedTimestampEntity
import com.looplingo.horizon.model.PlaybackConfig
import com.looplingo.horizon.model.PlaybackConfigValidator
import com.looplingo.horizon.model.SpeedPresets
import com.looplingo.horizon.repository.PlaybackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class PlaybackSettingsViewModel @Inject constructor(
    private val playbackRepository: PlaybackRepository,
    private val savedTimestampDao: SavedTimestampDao
) : ViewModel() {

    private val _config = MutableStateFlow(PlaybackConfig(videoPath = ""))
    val config: StateFlow<PlaybackConfig> = _config.asStateFlow()

    private val _isSaved = MutableStateFlow(false)
    val isSaved: StateFlow<Boolean> = _isSaved.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    private val _savedTimestamps = MutableStateFlow<List<SavedTimestampEntity>>(emptyList())
    val savedTimestamps: StateFlow<List<SavedTimestampEntity>> = _savedTimestamps.asStateFlow()

    fun loadConfigForVideo(videoPath: String) {
        viewModelScope.launch {
            try {
                val saved = playbackRepository.getConfigForVideo(videoPath)
                _config.value = saved ?: PlaybackConfig(videoPath = videoPath)
            } catch (e: Exception) {
                Timber.e(e, "Failed to load config")
                _config.value = PlaybackConfig(videoPath = videoPath)
            }

            // Load saved timestamps for this video
            try {
                savedTimestampDao.getTimestampsForVideo(videoPath).collect { timestamps ->
                    _savedTimestamps.value = timestamps
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load saved timestamps")
            }
        }
    }

    fun updateConfig(
        rangeStartMs: Long? = null,
        rangeEndMs: Long? = null,
        loopCount: Int? = null,
        speed: Float? = null
    ) {
        _config.value = _config.value.copy(
            rangeStartMs = rangeStartMs ?: _config.value.rangeStartMs,
            rangeEndMs = rangeEndMs ?: _config.value.rangeEndMs,
            loopCount = loopCount ?: _config.value.loopCount,
            speed = speed ?: _config.value.speed
        )
    }

    fun saveConfig() {
        val configToSave = _config.value
        val sanitized = if (!PlaybackConfigValidator.isValid(configToSave)) {
            PlaybackConfigValidator.sanitize(configToSave)
        } else {
            configToSave
        }
        _config.value = sanitized

        viewModelScope.launch {
            _saveError.value = null
            try {
                val success = playbackRepository.saveConfig(sanitized)
                if (success) {
                    _isSaved.value = true
                    // Auto-save as a timestamp bookmark too
                    if (sanitized.hasABLoop) {
                        saveTimestamp(sanitized)
                    }
                } else {
                    _saveError.value = "Failed to save settings"
                }
            } catch (e: Exception) {
                _saveError.value = "Failed to save: ${e.message}"
            }
        }
    }

    private fun saveTimestamp(config: PlaybackConfig) {
        viewModelScope.launch {
            try {
                val label = "${formatMs(config.rangeStartMs)}-${formatMs(config.rangeEndMs)}"
                savedTimestampDao.insertTimestamp(
                    SavedTimestampEntity(
                        videoPath = config.videoPath,
                        label = label,
                        rangeStartMs = config.rangeStartMs,
                        rangeEndMs = config.rangeEndMs,
                        loopCount = config.loopCount
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to save timestamp")
            }
        }
    }

    fun deleteTimestamp(timestamp: SavedTimestampEntity) {
        viewModelScope.launch {
            try {
                savedTimestampDao.deleteById(timestamp.id)
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete timestamp")
            }
        }
    }

    fun deleteConfig() {
        viewModelScope.launch {
            try {
                playbackRepository.deleteConfigForVideo(_config.value.videoPath)
                _config.value = PlaybackConfig(videoPath = _config.value.videoPath)
                _isSaved.value = true
            } catch (e: Exception) {
                _saveError.value = "Failed to clear settings"
            }
        }
    }

    fun clearSaveError() { _saveError.value = null }

    private fun formatMs(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}
