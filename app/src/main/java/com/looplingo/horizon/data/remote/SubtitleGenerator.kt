package com.looplingo.horizon.data.remote

import android.content.Context
import android.os.Environment
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubtitleGenerator @Inject constructor() {

    // ══════════════════════════════════════════════════════════════════
    // SRT GENERATION
    // ══════════════════════════════════════════════════════════════════

    /**
     * Generate SRT subtitle content from segments.
     * Groq doesn't support srt/vtt response format — we generate it client-side.
     * If translations are provided, each subtitle shows original + translation.
     */
    fun generateSrt(segments: List<Segment>, translations: Map<Int, String> = emptyMap()): String {
        return segments.mapIndexed { index, seg ->
            val start = formatSrtTime(seg.startMs)
            val end = formatSrtTime(seg.endMs)
            val translation = translations[seg.id]
            val text = if (translation != null) "${seg.text.trim()}\n→ $translation" else seg.text.trim()
            "${index + 1}\n$start --> $end\n$text\n"
        }.joinToString("\n")
    }

    /**
     * Generate VTT subtitle content from segments.
     * If translations are provided, each subtitle shows original + translation.
     */
    fun generateVtt(segments: List<Segment>, translations: Map<Int, String> = emptyMap()): String {
        val header = "WEBVTT\n\n"
        val body = segments.mapIndexed { index, seg ->
            val start = formatVttTime(seg.startMs)
            val end = formatVttTime(seg.endMs)
            val translation = translations[seg.id]
            val text = if (translation != null) "${seg.text.trim()}\n→ $translation" else seg.text.trim()
            "${index + 1}\n$start --> $end\n$text\n"
        }.joinToString("\n")
        return header + body
    }

    /**
     * Save SRT file to app-accessible Downloads directory.
     * Returns the saved file path, or null on failure.
     */
    fun saveSrtFile(context: Context, segments: List<Segment>, videoName: String, translations: Map<Int, String> = emptyMap()): String? {
        return try {
            val srtContent = generateSrt(segments, translations)
            val srtName = videoName.substringBeforeLast(".") + ".srt"
            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (downloadsDir != null) {
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val srtFile = File(downloadsDir, srtName)
                FileOutputStream(srtFile).use { it.write(srtContent.toByteArray(Charsets.UTF_8)) }
                Timber.i("SRT saved: %s (%d segments)", srtFile.absolutePath, segments.size)
                srtFile.absolutePath
            } else null
        } catch (e: Exception) {
            Timber.e(e, "Failed to save SRT file")
            null
        }
    }

    private fun formatSrtTime(ms: Long): String {
        val hours = ms / 3_600_000
        val minutes = (ms % 3_600_000) / 60_000
        val seconds = (ms % 60_000) / 1_000
        val millis = ms % 1_000
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }

    private fun formatVttTime(ms: Long): String {
        val hours = ms / 3_600_000
        val minutes = (ms % 3_600_000) / 60_000
        val seconds = (ms % 60_000) / 1_000
        val millis = ms % 1_000
        return "%02d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
    }
}
