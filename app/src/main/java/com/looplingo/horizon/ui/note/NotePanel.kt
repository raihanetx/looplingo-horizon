package com.looplingo.horizon.ui.note

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looplingo.horizon.ui.note.components.NoteForm
import com.looplingo.horizon.ui.note.components.NoteListItem

@Composable
fun NotePanel(
    videoPath: String,
    viewModel: NoteViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(videoPath) {
        viewModel.loadNotes(videoPath)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (uiState.isFormVisible) {
            NoteForm(
                noteText = uiState.noteText,
                onTextChange = viewModel::updateNoteText,
                onSave = { viewModel.saveNote() },
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        if (uiState.notes.isEmpty() && !uiState.isLoading) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No notes yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFF999999)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.notes) { note ->
                    NoteListItem(
                        note = note,
                        onDelete = { viewModel.deleteNote(note) }
                    )
                }
            }
        }

        uiState.error?.let { error ->
            Text(
                text = error,
                color = Color(0xFFFF5252),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
