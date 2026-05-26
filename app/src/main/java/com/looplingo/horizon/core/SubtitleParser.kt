package com.looplingo.horizon.core

import com.looplingo.horizon.domain.model.SubtitleCue
import timber.log.Timber
import java.io.BufferedReader
import java.io.StringReader

object SubtitleParser {

    fun parseSrt(content: String): List<SubtitleCue> {
        return parseSubtitleContent(content, isVtt = false)
    }

    fun parseVtt(content: String): List<SubtitleCue> {
        return parseSubtitleContent(content, isVtt = true)
    }

    fun parseLrc(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val lrcPattern = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\](.*)""")

        content.lineSequence().forEachIndexed { _, line ->
            val match = lrcPattern.matchEntire(line.trim()) ?: return@forEachIndexed
            try {
                val minutes = match.groupValues[1].toLong()
                val seconds = match.groupValues[2].toLong()
                val millisStr = match.groupValues[3]
                val millis = if (millisStr.isNotBlank()) {
                    val padded = millisStr.padEnd(3, '0').take(3)
                    padded.toLong()
                } else 0L
                val startMs = (minutes * 60 + seconds) * 1000 + millis
                val text = match.groupValues[4].trim()
                if (text.isNotBlank()) {
                    cues.add(SubtitleCue(index = cues.size + 1, startMs = startMs, endMs = 0L, text = text))
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to parse LRC line: %s", line)
            }
        }

        for (i in cues.indices) {
            if (i < cues.size - 1) {
                cues[i] = cues[i].copy(endMs = cues[i + 1].startMs)
            } else {
                cues[i] = cues[i].copy(endMs = cues[i].startMs + 5000)
            }
        }

        return cues
    }

    private fun parseSubtitleContent(content: String, isVtt: Boolean): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val reader = BufferedReader(StringReader(content))

        val separator = if (isVtt) "\\." else ","
        val timestampPattern = Regex(
            """(\d{1,2}:\d{2}:\d{2}$separator\d{1,3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}$separator\d{1,3})"""
        )
        val shortTimestampPattern = Regex(
            """(\d{1,2}:\d{2}$separator\d{1,3})\s*-->\s*(\d{1,2}:\d{2}$separator\d{1,3})"""
        )

        var currentIndex = 0
        var currentStartMs: Long? = null
        var currentEndMs: Long? = null
        var currentTextLines = mutableListOf<String>()

        var inVttHeader = isVtt

        reader.forEachLine { line ->
            val trimmed = line.trimEnd()

            if (inVttHeader) {
                if (trimmed.isBlank()) {
                    inVttHeader = false
                }
                return@forEachLine
            }

            val tsMatch = timestampPattern.find(trimmed) ?: shortTimestampPattern.find(trimmed)
            if (tsMatch != null) {
                val prevStart = currentStartMs
                val prevEnd = currentEndMs
                if (prevStart != null && prevEnd != null && currentTextLines.isNotEmpty()) {
                    cues.add(
                        SubtitleCue(
                            index = ++currentIndex,
                            startMs = prevStart,
                            endMs = prevEnd,
                            text = currentTextLines.joinToString("\n")
                        )
                    )
                }

                currentStartMs = parseTimestamp(tsMatch.groupValues[1], separator)
                currentEndMs = parseTimestamp(tsMatch.groupValues[2], separator)
                currentTextLines = mutableListOf()
                return@forEachLine
            }

            if (trimmed.isBlank()) {
                val prevStart = currentStartMs
                val prevEnd = currentEndMs
                if (prevStart != null && prevEnd != null && currentTextLines.isNotEmpty()) {
                    cues.add(
                        SubtitleCue(
                            index = ++currentIndex,
                            startMs = prevStart,
                            endMs = prevEnd,
                            text = currentTextLines.joinToString("\n")
                        )
                    )
                }
                currentStartMs = null
                currentEndMs = null
                currentTextLines = mutableListOf()
                return@forEachLine
            }

            if (currentStartMs != null) {
                currentTextLines.add(trimmed)
            }
        }

        val lastStart = currentStartMs
        val lastEnd = currentEndMs
        if (lastStart != null && lastEnd != null && currentTextLines.isNotEmpty()) {
            cues.add(
                SubtitleCue(
                    index = ++currentIndex,
                    startMs = lastStart,
                    endMs = lastEnd,
                    text = currentTextLines.joinToString("\n")
                )
            )
        }

        Timber.d("Parsed %d subtitle cues (format: %s)", cues.size, if (isVtt) "VTT" else "SRT")
        return cues
    }

    private fun parseTimestamp(ts: String, decimalSeparator: String): Long {
        return try {
            val parts = ts.split(":")
            val hours: Long
            val minutes: Long
            val secondsAndMillis: String

            when (parts.size) {
                3 -> {
                    hours = parts[0].toLong()
                    minutes = parts[1].toLong()
                    secondsAndMillis = parts[2]
                }
                2 -> {
                    hours = 0
                    minutes = parts[0].toLong()
                    secondsAndMillis = parts[1]
                }
                else -> return 0L
            }

            val splitSeparator = if (decimalSeparator == "\\.") "." else decimalSeparator
            val secParts = secondsAndMillis.split(splitSeparator)
            val seconds = secParts[0].toLong()
            val millis = if (secParts.size > 1) {
                secParts[1].padEnd(3, '0').take(3).toLong()
            } else 0L

            (hours * 3600 + minutes * 60 + seconds) * 1000 + millis
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse timestamp: %s", ts)
            0L
        }
    }
}
