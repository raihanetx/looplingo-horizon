package com.looplingo.horizon.util

import com.looplingo.horizon.model.SubtitleCue
import timber.log.Timber
import java.io.BufferedReader
import java.io.StringReader

/**
 * Parses subtitle files (.srt and .vtt) into a list of [SubtitleCue] objects.
 *
 * Supported formats:
 *  - **SRT** (SubRip): Most common subtitle format. Simple index + timestamp + text blocks.
 *  - **VTT** (WebVTT): Web standard subtitle format. Similar to SRT with a header line.
 *
 * Both formats use timestamp syntax like:
 * ```
 * 00:01:23,456 --> 00:01:45,789   (SRT)
 * 00:01:23.456 --> 00:01:45.789   (VTT)
 * ```
 *
 * This parser is intentionally lenient — it skips malformed blocks
 * and continues parsing rather than failing on the entire file.
 * This is important for personal-use subtitle files that may have
 * minor formatting issues.
 */
object SubtitleParser {

    /**
     * Parse an SRT subtitle file content into subtitle cues.
     *
     * SRT format example:
     * ```
     * 1
     * 00:00:01,000 --> 00:00:04,000
     * Hello, how are you?
     *
     * 2
     * 00:00:05,000 --> 00:00:08,000
     * I'm fine, thank you.
     * ```
     */
    fun parseSrt(content: String): List<SubtitleCue> {
        return parseSubtitleContent(content, isVtt = false)
    }

    /**
     * Parse a VTT (WebVTT) subtitle file content into subtitle cues.
     *
     * VTT format example:
     * ```
     * WEBVTT
     *
     * 00:00:01.000 --> 00:00:04.000
     * Hello, how are you?
     *
     * 00:00:05.000 --> 00:00:08.000
     * I'm fine, thank you.
     * ```
     */
    fun parseVtt(content: String): List<SubtitleCue> {
        return parseSubtitleContent(content, isVtt = true)
    }

    /**
     * Parse LRC (lyrics file) content into subtitle cues.
     *
     * LRC format example:
     * ```
     * [00:01.00]Hello, how are you?
     * [00:05.00]I'm fine, thank you.
     * ```
     *
     * Each line has a timestamp and text. End time is estimated
     * as the start time of the next line, or +5 seconds for the last line.
     */
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
                    // Normalize to 3 digits: "5" → 500, "50" → 500, "500" → 500
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

        // Fill in end times: each cue ends when the next one starts (or +5s for last)
        // Uses CLOSED range — endMs equals next cue's startMs (no gap).
        // This matches SubtitleCue.isActiveAt() which uses [startMs, endMs].
        // When positionMs equals the boundary, BOTH cues match at that instant;
        // the binary search in TranscriptRepository returns the correct one.
        for (i in cues.indices) {
            if (i < cues.size - 1) {
                cues[i] = cues[i].copy(endMs = cues[i + 1].startMs)
            } else {
                cues[i] = cues[i].copy(endMs = cues[i].startMs + 5000)
            }
        }

        return cues
    }

    /**
     * Core parsing logic shared between SRT and VTT formats.
     *
     * Both formats share the same block structure:
     *  - Optional index number
     *  - Timestamp line with --> separator
     *  - One or more text lines
     *  - Blank line separator
     *
     * The only differences are:
     *  - VTT uses '.' as millisecond separator, SRT uses ','
     *  - VTT has a "WEBVTT" header line
     *  - VTT may have optional headers after WEBVTT
     */
    private fun parseSubtitleContent(content: String, isVtt: Boolean): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val reader = BufferedReader(StringReader(content))

        // Timestamp pattern: 00:01:23,456 --> 00:01:45,789 (SRT)
        //                    00:01:23.456 --> 00:01:45.789 (VTT)
        // Also handles mm:ss format without hours
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

        // Skip VTT header section (including any metadata headers like NOTE, Style:, Kind:)
        var inVttHeader = isVtt

        reader.forEachLine { line ->
            val trimmed = line.trimEnd()

            // Skip VTT header section — skip all lines until we find a blank line
            // This handles WEBVTT + optional metadata headers (NOTE, Style, Kind, etc.)
            if (inVttHeader) {
                if (trimmed.isBlank()) {
                    inVttHeader = false
                }
                return@forEachLine
            }

            // Check for timestamp line
            val tsMatch = timestampPattern.find(trimmed) ?: shortTimestampPattern.find(trimmed)
            if (tsMatch != null) {
                // Save previous cue if any
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

                // Start new cue
                currentStartMs = parseTimestamp(tsMatch.groupValues[1], separator)
                currentEndMs = parseTimestamp(tsMatch.groupValues[2], separator)
                currentTextLines = mutableListOf()
                return@forEachLine
            }

            // Blank line = end of current block
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

            // Text line (or index number before timestamp)
            if (currentStartMs != null) {
                // We're inside a timed block — add text
                currentTextLines.add(trimmed)
            }
            // If no timestamp seen yet, this is an index number — skip it
        }

        // Handle last cue (file might not end with blank line)
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

    /**
     * Parse a single timestamp like "00:01:23,456" or "01:23,456" into milliseconds.
     *
     * Supported formats:
     *  - hh:mm:ss,mmm (SRT) or hh:mm:ss.mmm (VTT)
     *  - mm:ss,mmm or mm:ss.mmm
     *
     * @param decimalSeparator The regex separator for the decimal part.
     *     For SRT this is "," (literal comma), for VTT this is "\\." (regex for literal dot).
     *     IMPORTANT: String.split(String) uses LITERAL matching, not regex.
     *     So for VTT timestamps like "23.456", we must split on "." not "\\.".
     */
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

            // String.split(String) uses LITERAL matching, not regex.
            // For VTT: decimalSeparator = "\\." (for Regex patterns), but we need "." for split.
            // For SRT: decimalSeparator = "," (same for both Regex and literal split).
            val splitSeparator = if (decimalSeparator == "\\.") "." else decimalSeparator
            val secParts = secondsAndMillis.split(splitSeparator)
            val seconds = secParts[0].toLong()
            val millis = if (secParts.size > 1) {
                // Normalize to 3 digits: "5" → 500, "50" → 500, "500" → 500
                secParts[1].padEnd(3, '0').take(3).toLong()
            } else 0L

            (hours * 3600 + minutes * 60 + seconds) * 1000 + millis
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse timestamp: %s", ts)
            0L
        }
    }
}
