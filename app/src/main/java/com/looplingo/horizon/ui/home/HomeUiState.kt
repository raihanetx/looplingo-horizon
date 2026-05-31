package com.looplingo.horizon.ui.home

import com.looplingo.horizon.data.local.entity.VideoEntity
import com.looplingo.horizon.domain.model.SortOrder

data class HomeUiState(
    val videos: List<VideoEntity> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val sortOrder: SortOrder = SortOrder.DATE,
    val searchQuery: String = "",
    val isSearchVisible: Boolean = false,
    val configuredModes: Map<String, String> = emptyMap()
)
