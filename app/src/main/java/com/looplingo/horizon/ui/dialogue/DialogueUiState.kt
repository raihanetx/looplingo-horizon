package com.looplingo.horizon.ui.dialogue

import com.looplingo.horizon.domain.model.SubtitleCue

data class DialogueUiState(
    val subtitles: List<SubtitleCue> = emptyList(),
    val currentSubtitleIndex: Int = -1,
    val translationLanguage: String? = null,
    val sourceLanguage: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)
