package com.looplingo.horizon.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looplingo.horizon.data.local.entity.VideoEntity
import com.looplingo.horizon.data.repository.PlaybackRepository
import com.looplingo.horizon.data.repository.VideoRepository
import com.looplingo.horizon.domain.model.SortOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _allVideos = MutableStateFlow<List<VideoEntity>>(emptyList())

    init {
        observeVideos()
        observeConfiguredModes()
        observeSearchQuery()
    }

    private fun observeVideos() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            videoRepository.getVideos(_uiState.value.sortOrder)
                .catch { e ->
                    Timber.e(e, "Error loading videos")
                    _uiState.update {
                        it.copy(
                            error = "Failed to load videos: ${e.message}",
                            isLoading = false
                        )
                    }
                }
                .collect { videos ->
                    _allVideos.value = videos
                    _uiState.update {
                        it.copy(
                            videos = videos,
                            isLoading = false
                        )
                    }
                }
        }
    }

    private fun observeConfiguredModes() {
        viewModelScope.launch {
            playbackRepository.getAllConfiguredModesFlow()
                .catch { e ->
                    Timber.e(e, "Error collecting configured modes")
                }
                .collect { modes ->
                    _uiState.update { it.copy(configuredModes = modes) }
                }
        }
    }

    private fun observeSearchQuery() {
        viewModelScope.launch {
            combine(_allVideos, _uiState) { videos, state ->
                if (state.searchQuery.isBlank()) videos
                else videos.filter { video ->
                    video.title.contains(state.searchQuery, ignoreCase = true) ||
                    video.path.contains(state.searchQuery, ignoreCase = true)
                }
            }.collect { filtered ->
                _uiState.update { it.copy(videos = filtered) }
            }
        }
    }

    fun refreshVideos() {
        if (_uiState.value.isLoading) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                videoRepository.refreshVideos()
                playbackRepository.deleteOrphanedRules()
                Timber.i("Video refresh completed")
            } catch (e: SecurityException) {
                _uiState.update {
                    it.copy(
                        error = "Storage permission required to scan videos",
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = "Failed to scan videos: ${e.message}",
                        isLoading = false
                    )
                }
            }
        }
    }

    fun setSortOrder(order: SortOrder) {
        if (_uiState.value.sortOrder == order) return
        _uiState.update { it.copy(sortOrder = order) }
        observeVideos()
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query.trim()) }
    }

    fun toggleSearch() {
        _uiState.update { it.copy(isSearchVisible = !it.isSearchVisible) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
