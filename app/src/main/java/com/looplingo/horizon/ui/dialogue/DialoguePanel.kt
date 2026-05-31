package com.looplingo.horizon.ui.dialogue

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looplingo.horizon.ui.dialogue.components.DialogueListItem

@Composable
fun DialoguePanel(
    videoPath: String,
    currentPositionMs: Long,
    viewModel: DialogueViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(videoPath) {
        viewModel.loadSubtitles(videoPath)
    }

    LaunchedEffect(currentPositionMs) {
        viewModel.updateCurrentSubtitle(currentPositionMs)
    }

    LaunchedEffect(uiState.currentSubtitleIndex) {
        if (uiState.currentSubtitleIndex >= 0) {
            listState.animateScrollToItem(
                index = uiState.currentSubtitleIndex,
                scrollOffset = -100
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (uiState.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary
            )
        } else if (uiState.subtitles.isEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "No subtitles available",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Generate subtitles to see dialogue",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(uiState.subtitles) { index, subtitle ->
                    DialogueListItem(
                        subtitle = subtitle,
                        isCurrent = index == uiState.currentSubtitleIndex,
                        showTranslation = uiState.translationLanguage != null,
                        onClick = { /* Handle subtitle click - seek to position */ }
                    )
                }
            }
        }

        uiState.error?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(top = 8.dp)
            )
        }
    }
}
