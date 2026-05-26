package com.looplingo.horizon.domain.model

import com.looplingo.horizon.core.TimeUtils

data class SubtitleCue(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String
) {
    fun isActiveAt(positionMs: Long): Boolean {
        return positionMs in startMs..endMs
    }

    val startLabel: String
        get() = formatMs(startMs)

    val endLabel: String
        get() = formatMs(endMs)

    fun splitOriginalAndTranslation(): Pair<String, String?> {
        val separator = "\n→ "
        return if (text.contains(separator)) {
            val original = text.substringBefore(separator)
            val translation = text.substringAfter(separator)
            Pair(original, translation)
        } else {
            Pair(text, null)
        }
    }

    val originalText: String
        get() = splitOriginalAndTranslation().first

    val translationText: String?
        get() = splitOriginalAndTranslation().second

    val hasTranslation: Boolean
        get() = text.contains("\n→ ")

    companion object {
        fun formatMs(ms: Long): String = TimeUtils.formatMsToTime(ms)
    }
}
