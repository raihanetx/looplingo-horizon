package com.looplingo.horizon.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looplingo.horizon.ui.dialogue.DialoguePanel
import com.looplingo.horizon.ui.loop.LoopPanel
import com.looplingo.horizon.ui.note.NotePanel
import com.looplingo.horizon.ui.player.components.HeaderBar
import com.looplingo.horizon.ui.player.components.TabNavigationBar
import com.looplingo.horizon.ui.player.components.TransportControls

@Composable
fun PlayerScreen(
    videoPath: String,
    videoTitle: String,
    onNavigateBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(videoPath) {
        viewModel.initialize(videoPath, videoTitle)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        HeaderBar(
            title = uiState.videoTitle,
            onBackClick = onNavigateBack
        )

        TabNavigationBar(
            selectedTab = uiState.currentTab,
            onTabSelected = viewModel::setCurrentTab
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (uiState.currentTab) {
                0 -> CleanPanel(uiState)
                1 -> DialoguePanel(
                    videoPath = videoPath,
                    currentPositionMs = uiState.currentPositionMs
                )
                2 -> LoopPanel(
                    videoPath = videoPath,
                    currentPositionMs = uiState.currentPositionMs
                )
                3 -> NotePanel(videoPath = videoPath)
            }
        }

        TransportControls(
            isPlaying = uiState.isPlaying,
            currentPositionMs = uiState.currentPositionMs,
            durationMs = uiState.durationMs,
            onPlayPauseClick = { viewModel.togglePlayback() },
            onSeek = { positionMs -> viewModel.seekTo(positionMs) }
        )
    }
}

@Composable
private fun CleanPanel(uiState: PlayerUiState) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Clean View",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Subtitles will appear here",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
