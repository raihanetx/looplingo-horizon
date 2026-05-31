package com.looplingo.horizon.ui.loop

import com.looplingo.horizon.data.local.entity.SavedTimestampEntity

data class LoopUiState(
    val rangeStartMs: Long = 0L,
    val rangeEndMs: Long = 0L,
    val loopCount: Int = 1,
    val savedTimestamps: List<SavedTimestampEntity> = emptyList(),
    val isFormVisible: Boolean = true,
    val currentPositionMs: Long = 0L,
    val isLoading: Boolean = false,
    val error: String? = null
)
