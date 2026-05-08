package com.looplingo.horizon.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looplingo.horizon.data.dao.SavedTimestampDao
import com.looplingo.horizon.data.entity.SavedTimestampEntity
import com.looplingo.horizon.model.PlaybackConfig
import com.looplingo.horizon.model.PlaybackConfigValidator
import com.looplingo.horizon.model.SpeedPresets
import com.looplingo.horizon.model.SubtitleCue
import com.looplingo.horizon.playback.AudioPlaybackService
import com.looplingo.horizon.repository.PlaybackRepository
import com.looplingo.horizon.repository.TranscriptRepository
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
    private val savedTimestampDao: SavedTimestampDao,
    private val transcriptRepository: TranscriptRepository
) : ViewModel() {

    private val _config = MutableStateFlow(PlaybackConfig(videoPath = ""))
    val config: StateFlow<PlaybackConfig> = _config.asStateFlow()

    private val _isSaved = MutableStateFlow(false)
    val isSaved: StateFlow<Boolean> = _isSaved.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    private val _savedTimestamps = MutableStateFlow<List<SavedTimestampEntity>>(emptyList())
    val savedTimestamps: StateFlow<List<SavedTimestampEntity>> = _savedTimestamps.asStateFlow()

    /** Subtitle cues for the current video. */
    private val _subtitleCues = MutableStateFlow<List<SubtitleCue>>(emptyList())
    val subtitleCues: StateFlow<List<SubtitleCue>> = _subtitleCues.asStateFlow()

    /** Index of the currently active subtitle cue (-1 if none). */
    private val _activeCueIndex = MutableStateFlow(-1)
    val activeCueIndex: StateFlow<Int> = _activeCueIndex.asStateFlow()

    /** Whether the video currently playing in the service matches this settings screen. */
    private val _isCurrentlyPlaying = MutableStateFlow(false)
    val isCurrentlyPlaying: StateFlow<Boolean> = _isCurrentlyPlaying.asStateFlow()

    /** Current playback position in ms (updated via polling when playing). */
    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

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

        // Load subtitles for this video
        loadSubtitles(videoPath)
    }

    private fun loadSubtitles(videoPath: String) {
        viewModelScope.launch {
            try {
                val cues = transcriptRepository.getSubtitlesForVideo(videoPath)
                _subtitleCues.value = cues
                if (cues.isNotEmpty()) {
                    Timber.i("Loaded %d subtitle cues for: %s", cues.size, videoPath.substringAfterLast("/"))
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load subtitles")
                _subtitleCues.value = emptyList()
            }
        }
    }

    /**
     * Update the active subtitle cue based on the current playback position.
     * Called from the UI layer which polls the MediaController position.
     */
    fun updatePlaybackPosition(positionMs: Long, isPlaying: Boolean) {
        _playbackPositionMs.value = positionMs
        _isCurrentlyPlaying.value = isPlaying

        val cues = _subtitleCues.value
        if (cues.isEmpty()) return

        // Find active cue using binary search logic
        var activeIndex = -1
        for (i in cues.indices) {
            if (positionMs in cues[i].startMs..cues[i].endMs) {
                activeIndex = i
                break
            }
        }
        _activeCueIndex.value = activeIndex
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

    override fun onCleared() {
        super.onCleared()
        // Clear subtitle cache for this video to free memory
        if (_config.value.videoPath.isNotBlank()) {
            transcriptRepository.clearCacheForVideo(_config.value.videoPath)
        }
    }
}
