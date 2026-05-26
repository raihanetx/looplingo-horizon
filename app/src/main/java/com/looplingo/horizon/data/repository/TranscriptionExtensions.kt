package com.looplingo.horizon.data.repository

import com.looplingo.horizon.domain.model.SubtitleCue
import com.looplingo.horizon.data.local.entity.TranscriptionEntity

internal fun TranscriptionEntity.toSubtitleCue(index: Int): SubtitleCue {
    val displayText = if (!translatedText.isNullOrBlank()) {
        "$text\n→ $translatedText"
    } else {
        text
    }
    val effectiveStartMs = vadStartMs ?: segmentStartMs
    val effectiveEndMs = vadEndMs ?: segmentEndMs
    return SubtitleCue(
        index = index,
        startMs = effectiveStartMs,
        endMs = effectiveEndMs,
        text = displayText
    )
}
