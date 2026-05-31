package com.looplingo.horizon.ui.player

import com.looplingo.horizon.domain.model.PlaybackConfig

data class PlayerUiState(
    val videoPath: String = "",
    val videoTitle: String = "",
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val playbackConfig: PlaybackConfig = PlaybackConfig(videoPath = ""),
    val currentTab: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null
)
