package com.looplingo.horizon.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looplingo.horizon.model.LoopMode
import com.looplingo.horizon.model.PlaybackConfig
import com.looplingo.horizon.model.PlaybackConfigValidator
import com.looplingo.horizon.model.StartAction
import com.looplingo.horizon.repository.PlaybackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for the Playback Settings screen.
 *
 * Loads any existing saved config for the selected video and allows
 * the user to modify and persist it via [PlaybackRepository].
 *
 * All config updates go through [PlaybackConfigValidator] before being
 * persisted to ensure data integrity.
 *
 * Error states:
 *  - [saveError]: Non-null if the last save attempt failed
 *  - [isSaved]: True if the last save attempt succeeded
 */
@HiltViewModel
class PlaybackSettingsViewModel @Inject constructor(
    private val playbackRepository: PlaybackRepository
) : ViewModel() {

    private val _config = MutableStateFlow(
        PlaybackConfig(videoPath = "", loopMode = LoopMode.LOOP_INFINITE)
    )
    val config: StateFlow<PlaybackConfig> = _config.asStateFlow()

    private val _isSaved = MutableStateFlow(false)
    val isSaved: StateFlow<Boolean> = _isSaved.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Load the previously saved config for a video path (if any). */
    fun loadConfigForVideo(videoPath: String) {
        if (_isLoading.value) return

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val saved = playbackRepository.getConfigForVideo(videoPath)
                if (saved != null) {
                    _config.value = saved
                    Timber.d("Loaded saved config for %s: mode=%s", videoPath, saved.loopMode)
                } else {
                    _config.value = _config.value.copy(videoPath = videoPath)
                    Timber.d("No saved config for %s — using defaults", videoPath)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load config for %s", videoPath)
                _config.value = _config.value.copy(videoPath = videoPath)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** Update individual config fields and emit the new state. */
    fun updateConfig(
        loopMode: LoopMode? = null,
        loopCount: Int? = null,
        rangeStartMs: Long? = null,
        rangeEndMs: Long? = null,
        startAction: StartAction? = null,
        autoAdvance: Boolean? = null
    ) {
        _config.value = _config.value.copy(
            loopMode = loopMode ?: _config.value.loopMode,
            loopCount = loopCount ?: _config.value.loopCount,
            rangeStartMs = rangeStartMs ?: _config.value.rangeStartMs,
            rangeEndMs = rangeEndMs ?: _config.value.rangeEndMs,
            startAction = startAction ?: _config.value.startAction,
            autoAdvance = autoAdvance ?: _config.value.autoAdvance
        )
    }

    /**
     * Validate the current config without saving.
     * Returns the validation result so the UI can show immediate feedback.
     */
    fun validateCurrentConfig(): PlaybackConfigValidator.ValidationResult {
        return PlaybackConfigValidator.validate(_config.value)
    }

    /** Persist the current config to Room. Handles success/failure via state flows. */
    fun saveConfig() {
        val configToSave = _config.value

        // Validate before saving
        val validationResult = PlaybackConfigValidator.validate(configToSave)
        if (!validationResult.isValid) {
            val errorMessage = validationResult.errors.joinToString("; ")
            Timber.w("Config validation failed before save: %s", errorMessage)
            // Sanitize and save the corrected version
            val sanitized = PlaybackConfigValidator.sanitize(configToSave)
            _config.value = sanitized
            performSave(sanitized)
        } else {
            performSave(configToSave)
        }
    }

    private fun performSave(config: PlaybackConfig) {
        viewModelScope.launch {
            _saveError.value = null
            try {
                val success = playbackRepository.saveConfig(config)
                if (success) {
                    _isSaved.value = true
                    Timber.i("Playback config saved successfully for %s", config.videoPath)
                } else {
                    _saveError.value = "Failed to save settings — database error"
                    Timber.e("PlaybackRepository.saveConfig returned false for %s", config.videoPath)
                }
            } catch (e: Exception) {
                val errorMsg = "Failed to save settings: ${e.message}"
                _saveError.value = errorMsg
                Timber.e(e, "Exception while saving playback config")
            }
        }
    }

    /** Clear the save error state. */
    fun clearSaveError() {
        _saveError.value = null
    }

    /** Reset the saved state (e.g., when user starts editing again). */
    fun resetSavedState() {
        _isSaved.value = false
        _saveError.value = null
    }
}
