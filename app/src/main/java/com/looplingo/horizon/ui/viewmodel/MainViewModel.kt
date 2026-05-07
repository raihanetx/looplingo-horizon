package com.looplingo.horizon.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looplingo.horizon.data.entity.VideoEntity
import com.looplingo.horizon.model.SortOrder
import com.looplingo.horizon.repository.PlaybackRepository
import com.looplingo.horizon.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for the main video list screen.
 *
 * Exposes the video list as a StateFlow so the UI can observe it reactively.
 * All MediaStore scanning and Room caching is delegated to [VideoRepository].
 *
 * Sort order is owned by this ViewModel as a [StateFlow] — the repository
 * receives the sort order as a parameter when requesting data, avoiding
 * mutable shared state between layers.
 *
 * Also exposes [configuredModes] — a reactive map of video paths to their loop mode
 * badge strings. This updates automatically when playback rules are saved or deleted,
 * so the video list badges are always current without requiring a manual refresh.
 *
 * Error states are tracked separately so the UI can show appropriate feedback:
 *  - [error]: A human-readable error message, null when there's no error
 *  - [isLoading]: Whether a scan is in progress
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository
) : ViewModel() {

    private val _videos = MutableStateFlow<List<VideoEntity>>(emptyList())
    val videos: StateFlow<List<VideoEntity>> = _videos.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _configuredModes = MutableStateFlow<Map<String, String>>(emptyMap())
    val configuredModes: StateFlow<Map<String, String>> = _configuredModes.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.DATE)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    /** Job for the current videos collection so we can cancel and restart on sort change. */
    private var videosCollectionJob: Job? = null

    init {
        // Start observing the Room cache immediately.
        observeVideos()

        // Observe configured modes reactively — badges update in real-time
        // when playback rules are saved or deleted.
        viewModelScope.launch {
            playbackRepository.getAllConfiguredModesFlow()
                .catch { e ->
                    Timber.e(e, "Error collecting configured modes Flow")
                }
                .collect { modes ->
                    _configuredModes.value = modes
                    Timber.d("Configured modes updated: %d entries", modes.size)
                }
        }
    }

    /**
     * Observe the video list from Room using the current sort order.
     * Cancels any previous collection job before starting a new one.
     * The sort order is passed as a parameter to the repository — the
     * repository does NOT hold mutable sort state.
     */
    private fun observeVideos() {
        videosCollectionJob?.cancel()
        videosCollectionJob = viewModelScope.launch {
            videoRepository.getVideos(_sortOrder.value)
                .catch { e ->
                    Timber.e(e, "Error collecting videos Flow")
                    _error.value = "Failed to load video list: ${e.message}"
                }
                .collect { cachedList ->
                    _videos.value = cachedList
                    Timber.d("Video list updated: %d videos", cachedList.size)
                    if (cachedList.isNotEmpty() && _error.value != null) {
                        _error.value = null
                    }
                }
        }
    }

    /** Trigger a fresh MediaStore scan and sync the results into Room. */
    fun refreshVideos() {
        if (_isLoading.value) {
            Timber.w("Refresh already in progress — ignoring duplicate request")
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                videoRepository.refreshVideos()
                Timber.i("Video refresh completed successfully")
                // Clean up orphaned rules after a fresh scan
                playbackRepository.deleteOrphanedRules()
            } catch (e: SecurityException) {
                val msg = "Storage permission required to scan videos"
                _error.value = msg
                Timber.e(e, msg)
            } catch (e: Exception) {
                val msg = "Failed to scan videos: ${e.message}"
                _error.value = msg
                Timber.e(e, "Error refreshing videos")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** Change the sort order and re-observe the video list. */
    fun setSortOrder(order: SortOrder) {
        if (_sortOrder.value == order) return
        _sortOrder.value = order
        Timber.i("Sort order changed to: %s", order.name)
        observeVideos()
    }

    /** Clear the current error state. Call this when the user dismisses the error. */
    fun clearError() {
        _error.value = null
    }
}
