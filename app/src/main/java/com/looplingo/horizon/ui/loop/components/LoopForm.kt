package com.looplingo.horizon.ui.loop.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.looplingo.horizon.core.TimeUtils

@Composable
fun LoopForm(
    rangeStartMs: Long,
    rangeEndMs: Long,
    loopCount: Int,
    currentPositionMs: Long,
    onStartChange: (Long) -> Unit,
    onEndChange: (Long) -> Unit,
    onCountChange: (Int) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2A2A2A)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Create Loop",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(15.dp)
            ) {
                OutlinedTextField(
                    value = TimeUtils.formatMsToTime(rangeStartMs),
                    onValueChange = { value ->
                        val ms = parseTimeToMs(value)
                        if (ms != null) onStartChange(ms)
                    },
                    label = { Text("Start") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color(0xFF555555),
                        focusedLabelColor = Color.White,
                        unfocusedLabelColor = Color(0xFF999999),
                        cursorColor = Color.White,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                OutlinedTextField(
                    value = TimeUtils.formatMsToTime(rangeEndMs),
                    onValueChange = { value ->
                        val ms = parseTimeToMs(value)
                        if (ms != null) onEndChange(ms)
                    },
                    label = { Text("End") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color(0xFF555555),
                        focusedLabelColor = Color.White,
                        unfocusedLabelColor = Color(0xFF999999),
                        cursorColor = Color.White,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            }

            Spacer(modifier = Modifier.height(15.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = loopCount.toString(),
                    onValueChange = { value ->
                        val count = value.toIntOrNull()
                        if (count != null) onCountChange(count)
                    },
                    label = { Text("Count") },
                    modifier = Modifier.width(80.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color(0xFF555555),
                        focusedLabelColor = Color.White,
                        unfocusedLabelColor = Color(0xFF999999),
                        cursorColor = Color.White,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                Button(
                    onClick = { onStartChange(currentPositionMs) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF333333),
                        contentColor = Color.White
                    )
                ) {
                    Text("Set Start")
                }

                Button(
                    onClick = { onEndChange(currentPositionMs) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF333333),
                        contentColor = Color.White
                    )
                ) {
                    Text("Set End")
                }

                Button(
                    onClick = onSave,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF1A1A1A)
                    )
                ) {
                    Text("Save")
                }
            }
        }
    }
}

private fun parseTimeToMs(time: String): Long? {
    val parts = time.split(":")
    if (parts.size != 2) return null
    val minutes = parts[0].toLongOrNull() ?: return null
    val seconds = parts[1].toLongOrNull() ?: return null
    return (minutes * 60 + seconds) * 1000
}
