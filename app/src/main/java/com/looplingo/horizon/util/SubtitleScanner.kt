package com.looplingo.horizon.util

import android.content.Context
import android.provider.MediaStore
import com.looplingo.horizon.model.SubtitleCue
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scans the device for subtitle files matching a given audio/video file.
 *
 * Matching strategy:
 *  1. **Same name, different extension**: If the audio file is "movie.mp4",
 *     look for "movie.srt", "movie.vtt", "movie.lrc" in the same directory.
 *  2. **Subdirectory scan**: Also check a "subtitles/" subfolder.
 *  3. **MediaStore query**: Search MediaStore for subtitle files with matching names.
 *
 * This keeps it simple for personal use — just place the subtitle file
 * next to your audio file with the same name, and it will be found automatically.
 */
@Singleton
class SubtitleScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Supported subtitle file extensions, in order of preference. */
    private val subtitleExtensions = listOf("srt", "vtt", "lrc")

    /**
     * Find and parse subtitle cues for the given video/audio file path.
     *
     * Returns an empty list if no subtitle file is found or if parsing fails.
     * The cues are sorted by start time.
     */
    fun findSubtitlesForVideo(videoPath: String): List<SubtitleCue> {
        // Try finding a subtitle file by same-name matching
        val subtitleContent = findSubtitleFile(videoPath)
        if (subtitleContent != null) {
            return subtitleContent
        }

        // Try MediaStore query
        return findSubtitlesViaMediaStore(videoPath)
    }

    /**
     * Look for subtitle files with the same base name as the video file.
     */
    private fun findSubtitleFile(videoPath: String): List<SubtitleCue>? {
        val videoFile = File(videoPath)
        val videoDir = videoFile.parentFile ?: return null
        val baseName = videoFile.nameWithoutExtension

        // Check same directory
        for (ext in subtitleExtensions) {
            val subtitleFile = File(videoDir, "$baseName.$ext")
            if (subtitleFile.exists() && subtitleFile.canRead()) {
                Timber.i("Found subtitle file: %s", subtitleFile.absolutePath)
                return parseSubtitleFile(subtitleFile, ext)
            }
        }

        // Check subtitles/ subdirectory
        val subtitlesDir = File(videoDir, "subtitles")
        if (subtitlesDir.exists() && subtitlesDir.isDirectory) {
            for (ext in subtitleExtensions) {
                val subtitleFile = File(subtitlesDir, "$baseName.$ext")
                if (subtitleFile.exists() && subtitleFile.canRead()) {
                    Timber.i("Found subtitle file in sub/: %s", subtitleFile.absolutePath)
                    return parseSubtitleFile(subtitleFile, ext)
                }
            }
        }

        return null
    }

    /**
     * Query MediaStore for subtitle files matching the video's base name.
     * This catches cases where the file is in a different directory.
     */
    private fun findSubtitlesViaMediaStore(videoPath: String): List<SubtitleCue> {
        val baseName = File(videoPath).nameWithoutExtension
        val results = mutableListOf<SubtitleCue>()

        try {
            val uri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.MIME_TYPE
            )

            // Build a selection for subtitle file extensions
            val extensionConditions = subtitleExtensions.joinToString(" OR ") {
                "${MediaStore.Files.FileColumns.DATA} LIKE '%$baseName.$it'"
            }
            val selection = "($extensionConditions)"

            context.contentResolver.query(uri, projection, selection, null, null)?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)

                while (cursor.moveToNext()) {
                    val filePath = cursor.getString(dataColumn)
                    val ext = filePath.substringAfterLast('.', "").lowercase()
                    if (ext in subtitleExtensions) {
                        val file = File(filePath)
                        if (file.exists() && file.canRead()) {
                            Timber.i("Found subtitle via MediaStore: %s", filePath)
                            val parsed = parseSubtitleFile(file, ext)
                            if (parsed.isNotEmpty()) {
                                results.addAll(parsed)
                                break  // Use the first found match
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to query MediaStore for subtitles")
        }

        return results
    }

    /**
     * Parse a subtitle file based on its extension.
     */
    private fun parseSubtitleFile(file: File, extension: String): List<SubtitleCue> {
        return try {
            val content = file.readText(Charsets.UTF_8)
            when (extension) {
                "srt" -> SubtitleParser.parseSrt(content)
                "vtt" -> SubtitleParser.parseVtt(content)
                "lrc" -> SubtitleParser.parseLrc(content)
                else -> {
                    Timber.w("Unknown subtitle extension: %s", extension)
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse subtitle file: %s", file.absolutePath)
            emptyList()
        }
    }

    /**
     * Parse subtitle content from an InputStream (for content:// URIs).
     */
    fun parseSubtitleFromStream(inputStream: InputStream, extension: String): List<SubtitleCue> {
        return try {
            val content = inputStream.bufferedReader(Charsets.UTF_8).readText()
            when (extension) {
                "srt" -> SubtitleParser.parseSrt(content)
                "vtt" -> SubtitleParser.parseVtt(content)
                "lrc" -> SubtitleParser.parseLrc(content)
                else -> emptyList()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse subtitle from stream")
            emptyList()
        }
    }
}
