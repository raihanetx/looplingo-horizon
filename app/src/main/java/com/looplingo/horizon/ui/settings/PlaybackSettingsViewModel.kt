package com.looplingo.horizon.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looplingo.horizon.data.remote.GroqApiClient
import com.looplingo.horizon.data.remote.Segment
import com.looplingo.horizon.data.local.dao.SavedTimestampDao
import com.looplingo.horizon.data.local.entity.SavedTimestampEntity
import com.looplingo.horizon.domain.model.PlaybackConfig
import com.looplingo.horizon.data.repository.CachedTranscriptionData
import com.looplingo.horizon.domain.model.PlaybackConfigValidator
import com.looplingo.horizon.domain.model.SubtitleCue
import com.looplingo.horizon.data.repository.PlaybackRepository
import com.looplingo.horizon.data.repository.TranscriptRepository
import com.looplingo.horizon.core.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class PlaybackSettingsViewModel @Inject constructor(
    private val playbackRepository: PlaybackRepository,
    private val savedTimestampDao: SavedTimestampDao,
    private val transcriptRepository: TranscriptRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val KEY_CURRENT_TAB = "currentTab"
        const val TAB_CLEAN = 0
        const val TAB_TALK = 1
        const val TAB_LOOP = 2
        const val TAB_NOTES = 3
    }

    private val _currentTab = MutableStateFlow(savedStateHandle[KEY_CURRENT_TAB] ?: TAB_CLEAN)
    val currentTab: StateFlow<Int> = _currentTab.asStateFlow()

    fun setCurrentTab(tab: Int) {
        _currentTab.value = tab
        savedStateHandle[KEY_CURRENT_TAB] = tab
    }

    suspend fun hasTranscriptions(videoPath: String): Boolean {
        return transcriptRepository.hasTranscriptionsInDb(videoPath)
    }

    suspend fun getTranscriptionCues(videoPath: String): List<SubtitleCue> {
        return transcriptRepository.getSubtitlesForVideoAsync(videoPath)
    }

    suspend fun getTranscriptionCuesWithMeta(videoPath: String): CachedTranscriptionData {
        return transcriptRepository.getSubtitlesWithMetaAsync(videoPath)
    }

    private val _config = MutableStateFlow(PlaybackConfig(videoPath = ""))
    val config: StateFlow<PlaybackConfig> = _config.asStateFlow()

    private val _isSaved = MutableStateFlow(false)
    val isSaved: StateFlow<Boolean> = _isSaved.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    private val _savedTimestamps = MutableStateFlow<List<SavedTimestampEntity>>(emptyList())
    val savedTimestamps: StateFlow<List<SavedTimestampEntity>> = _savedTimestamps.asStateFlow()

    private var timestampsCollectionJob: Job? = null

    fun loadConfigForVideo(videoPath: String) {
        timestampsCollectionJob?.cancel()

        viewModelScope.launch {
            try {
                val saved = playbackRepository.getConfigForVideo(videoPath)
                _config.value = saved ?: PlaybackConfig(videoPath = videoPath)
            } catch (e: Exception) {
                Timber.e(e, "Failed to load config")
                _config.value = PlaybackConfig(videoPath = videoPath)
            }

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

    fun saveTranscription(
        videoPath: String,
        segments: List<Segment>,
        languageCode: String = "auto",
        isTranslation: Boolean = false,
        translatedTexts: Map<Int, String> = emptyMap(),
        translationLanguage: String? = null
    ) {
        viewModelScope.launch {
            try {
                transcriptRepository.saveTranscriptions(
                    videoPath, segments, languageCode, isTranslation,
                    translatedTexts, translationLanguage
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to save transcription for %s", videoPath)
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

    override fun onCleared() {
        super.onCleared()
        timestampsCollectionJob?.cancel()
    }

    private fun formatMs(ms: Long): String = TimeUtils.formatMsToTime(ms)
}
