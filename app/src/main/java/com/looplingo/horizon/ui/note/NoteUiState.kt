package com.looplingo.horizon.ui.note

import com.looplingo.horizon.data.local.entity.NoteEntity

data class NoteUiState(
    val notes: List<NoteEntity> = emptyList(),
    val isFormVisible: Boolean = true,
    val noteText: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)
