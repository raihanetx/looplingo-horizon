package com.looplingo.horizon.ui.loop

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
import com.looplingo.horizon.ui.loop.components.LoopForm
import com.looplingo.horizon.ui.loop.components.LoopListItem

@Composable
fun LoopPanel(
    videoPath: String,
    currentPositionMs: Long,
    viewModel: LoopViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(videoPath) {
        viewModel.loadTimestamps(videoPath)
    }

    LaunchedEffect(currentPositionMs) {
        viewModel.setCurrentPosition(currentPositionMs)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (uiState.isFormVisible) {
            LoopForm(
                rangeStartMs = uiState.rangeStartMs,
                rangeEndMs = uiState.rangeEndMs,
                loopCount = uiState.loopCount,
                currentPositionMs = uiState.currentPositionMs,
                onStartChange = viewModel::updateRangeStart,
                onEndChange = viewModel::updateRangeEnd,
                onCountChange = viewModel::updateLoopCount,
                onSave = viewModel::saveLoop,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        if (uiState.savedTimestamps.isEmpty() && !uiState.isLoading) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No saved loops yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFF999999)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.savedTimestamps) { timestamp ->
                    LoopListItem(
                        timestamp = timestamp,
                        onDelete = { viewModel.deleteTimestamp(timestamp) }
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
