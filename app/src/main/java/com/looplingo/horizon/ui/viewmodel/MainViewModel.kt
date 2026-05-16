package com.looplingo.horizon.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looplingo.horizon.data.entity.VideoEntity
import com.looplingo.horizon.model.PlaybackConfig
import com.looplingo.horizon.model.PlaybackConfigValidator
import com.looplingo.horizon.model.SortOrder
import com.looplingo.horizon.repository.PlaybackRepository
import com.looplingo.horizon.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

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

    // Search & filter state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // All videos from DB (unfiltered)
    private val _allVideos = MutableStateFlow<List<VideoEntity>>(emptyList())

    private var videosCollectionJob: Job? = null

    init {
        observeVideos()

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

        // Combine allVideos + searchQuery → filtered videos
        viewModelScope.launch {
            combine(_allVideos, _searchQuery) { videos, query ->
                if (query.isBlank()) videos
                else videos.filter { video ->
                    video.title.contains(query, ignoreCase = true) ||
                    video.path.contains(query, ignoreCase = true)
                }
            }.collect { filtered ->
                _videos.value = filtered
            }
        }
    }

    private fun observeVideos() {
        videosCollectionJob?.cancel()
        videosCollectionJob = viewModelScope.launch {
            videoRepository.getVideos(_sortOrder.value)
                .catch { e ->
                    Timber.e(e, "Error collecting videos Flow")
                    _error.value = "Failed to load video list: ${e.message}"
                }
                .collect { cachedList ->
                    _allVideos.value = cachedList
                    Timber.d("Video list updated: %d videos", cachedList.size)
                    if (cachedList.isNotEmpty() && _error.value != null) {
                        _error.value = null
                    }
                }
        }
    }

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

    fun setSortOrder(order: SortOrder) {
        if (_sortOrder.value == order) return
        _sortOrder.value = order
        Timber.i("Sort order changed to: %s", order.name)
        observeVideos()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query.trim()
    }

    fun clearSearch() {
        _searchQuery.value = ""
    }

    fun clearError() {
        _error.value = null
    }

    suspend fun savePlaybackConfig(
        videoPath: String,
        rangeStartMs: Long,
        rangeEndMs: Long,
        loopCount: Int
    ) {
        if (videoPath.isBlank()) return

        val config = PlaybackConfig(
            videoPath = videoPath,
            rangeStartMs = rangeStartMs,
            rangeEndMs = rangeEndMs,
            loopCount = loopCount
        )
        val sanitized = if (!PlaybackConfigValidator.isValid(config)) {
            PlaybackConfigValidator.sanitize(config)
        } else {
            config
        }
        try {
            playbackRepository.saveConfig(sanitized)
            Timber.i("Saved playback config from mini player: %s", videoPath.substringAfterLast("/"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            Timber.d("Save playback config cancelled")
            throw e
        }
    }
}
