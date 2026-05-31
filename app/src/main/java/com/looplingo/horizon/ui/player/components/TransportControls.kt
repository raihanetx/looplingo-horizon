package com.looplingo.horizon.ui.player.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.looplingo.horizon.core.TimeUtils

@Composable
fun TransportControls(
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    onPlayPauseClick: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = TimeUtils.formatMsToTime(currentPositionMs),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFCCCCCC)
            )

            Slider(
                value = if (durationMs > 0) currentPositionMs.toFloat() / durationMs.toFloat() else 0f,
                onValueChange = { progress ->
                    val newPosition = (progress * durationMs).toLong()
                    onSeek(newPosition)
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color(0xFF555555)
                )
            )

            Text(
                text = TimeUtils.formatMsToTime(durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFCCCCCC)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPlayPauseClick,
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp)
                )
            }
        }
    }
}
