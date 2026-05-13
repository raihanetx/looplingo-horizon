package com.looplingo.horizon.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looplingo.horizon.api.GroqApiClient
import com.looplingo.horizon.data.dao.SavedTimestampDao
import com.looplingo.horizon.data.entity.SavedTimestampEntity
import com.looplingo.horizon.model.PlaybackConfig
import com.looplingo.horizon.model.PlaybackConfigValidator
import com.looplingo.horizon.model.SubtitleCue
import com.looplingo.horizon.repository.PlaybackRepository
import com.looplingo.horizon.repository.TranscriptRepository
import com.looplingo.horizon.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for playback settings screen.
 *
 * Manages A-B loop configuration (with try-before-save), speed control (instant apply),
 * subtitle/transcription generation, and persistence of Whisper transcriptions.
 */
@HiltViewModel
class PlaybackSettingsViewModel @Inject constructor(
    private val playbackRepository: PlaybackRepository,
    private val savedTimestampDao: SavedTimestampDao,
    private val transcriptRepository: TranscriptRepository
) : ViewModel() {

    /** Check if transcriptions exist in the database for a video. */
    suspend fun hasTranscriptions(videoPath: String): Boolean {
        return transcriptRepository.hasTranscriptionsInDb(videoPath)
    }

    /** Get transcription cues from the database for a video. */
    suspend fun getTranscriptionCues(videoPath: String): List<SubtitleCue> {
        return transcriptRepository.getSubtitlesForVideoAsync(videoPath)
    }

    private val _config = MutableStateFlow(PlaybackConfig(videoPath = ""))
    val config: StateFlow<PlaybackConfig> = _config.asStateFlow()

    private val _isSaved = MutableStateFlow(false)
    val isSaved: StateFlow<Boolean> = _isSaved.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    private val _savedTimestamps = MutableStateFlow<List<SavedTimestampEntity>>(emptyList())
    val savedTimestamps: StateFlow<List<SavedTimestampEntity>> = _savedTimestamps.asStateFlow()

    /** Job for the timestamps collection so we can cancel and restart on video change. */
    private var timestampsCollectionJob: Job? = null

    fun loadConfigForVideo(videoPath: String) {
        // Cancel previous timestamps collection before starting a new one
        // BUG FIX: Previously, only timestampsCollectionJob was cancelled.
        // Now we also cancel on re-load to prevent Flow leak from previous video.
        timestampsCollectionJob?.cancel()

        viewModelScope.launch {
            try {
                val saved = playbackRepository.getConfigForVideo(videoPath)
                _config.value = saved ?: PlaybackConfig(videoPath = videoPath)
            } catch (e: Exception) {
                Timber.e(e, "Failed to load config")
                _config.value = PlaybackConfig(videoPath = videoPath)
            }

            // Load saved timestamps for this video — only one collector active at a time
            timestampsCollectionJob = viewModelScope.launch {
                try {
                    savedTimestampDao.getTimestampsForVideo(videoPath).collect { timestamps ->
                        _savedTimestamps.value = timestamps
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to load saved timestamps")
                }
            }
        }
    }

    /**
     * Save Whisper transcription segments to the database via TranscriptRepository.
     * This persists the segments so they survive app restarts and can be used
     * for playback sync without re-transcribing.
     */
    fun saveTranscription(
        videoPath: String,
        segments: List<GroqApiClient.Segment>,
        languageCode: String = "auto",
        isTranslation: Boolean = false,
        translatedTexts: Map<Int, String> = emptyMap(),
        translationLanguage: String? = null
    ) {
        viewModelScope.launch {
            transcriptRepository.saveTranscriptions(
                videoPath, segments, languageCode, isTranslation,
                translatedTexts, translationLanguage
            )
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
                // Check for duplicate timestamps with the same video path, range, and loop count
                val existing = savedTimestampDao.getTimestampsForVideoOnce(config.videoPath)
                val isDuplicate = existing.any {
                    it.rangeStartMs == config.rangeStartMs &&
                    it.rangeEndMs == config.rangeEndMs &&
                    it.loopCount == config.loopCount
                }
                if (!isDuplicate) {
                    savedTimestampDao.insertTimestamp(
                        SavedTimestampEntity(
                            videoPath = config.videoPath,
                            label = label,
                            rangeStartMs = config.rangeStartMs,
                            rangeEndMs = config.rangeEndMs,
                            loopCount = config.loopCount
                        )
                    )
                }
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

    /**
     * BUG FIX: Cancel Flow collection jobs on ViewModel clear.
     * Previously, the timestampsCollectionJob was never cancelled when the
     * ViewModel was cleared, causing a Flow leak that continued to collect
     * Room changes even after the UI was destroyed.
     */
    override fun onCleared() {
        super.onCleared()
        timestampsCollectionJob?.cancel()
    }

    private fun formatMs(ms: Long): String = TimeUtils.formatMsToTime(ms)
}
