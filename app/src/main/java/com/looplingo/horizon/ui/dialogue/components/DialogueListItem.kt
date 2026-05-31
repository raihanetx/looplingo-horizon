package com.looplingo.horizon.ui.dialogue.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.looplingo.horizon.domain.model.SubtitleCue

@Composable
fun DialogueListItem(
    subtitle: SubtitleCue,
    isCurrent: Boolean,
    showTranslation: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isCurrent) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    val textColor = if (isCurrent) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val translationColor = if (isCurrent) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = subtitle.originalText,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                ),
                color = textColor
            )

            if (showTranslation && subtitle.hasTranslation) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle.translationText ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = translationColor
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${subtitle.startLabel} → ${subtitle.endLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
