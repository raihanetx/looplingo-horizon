package com.looplingo.horizon.core

import android.content.Context
import android.provider.MediaStore
import com.looplingo.horizon.domain.model.SubtitleCue
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubtitleScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val subtitleExtensions = listOf("srt", "vtt", "lrc")

    fun findSubtitlesForVideo(videoPath: String): List<SubtitleCue> {
        val subtitleContent = findSubtitleFile(videoPath)
        if (subtitleContent != null) {
            return subtitleContent
        }

        return findSubtitlesViaMediaStore(videoPath)
    }

    private fun findSubtitleFile(videoPath: String): List<SubtitleCue>? {
        val videoFile = File(videoPath)
        val videoDir = videoFile.parentFile ?: return null
        val baseName = videoFile.nameWithoutExtension

        for (ext in subtitleExtensions) {
            val subtitleFile = File(videoDir, "$baseName.$ext")
            try {
                if (subtitleFile.exists() && subtitleFile.canRead()) {
                    Timber.i("Found subtitle file: %s", subtitleFile.absolutePath)
                    return parseSubtitleFile(subtitleFile, ext)
                }
            } catch (e: SecurityException) {
                Timber.d("Scoped storage blocked direct access to: %s", subtitleFile.absolutePath)
                val result = findSubtitleViaContentResolver(videoDir, baseName, ext)
                if (result != null) return result
            }
        }

        val subtitlesDir = File(videoDir, "subtitles")
        try {
            if (subtitlesDir.exists() && subtitlesDir.isDirectory) {
                for (ext in subtitleExtensions) {
                    val subtitleFile = File(subtitlesDir, "$baseName.$ext")
                    if (subtitleFile.exists() && subtitleFile.canRead()) {
                        Timber.i("Found subtitle file in sub/: %s", subtitleFile.absolutePath)
                        return parseSubtitleFile(subtitleFile, ext)
                    }
                }
            }
        } catch (e: SecurityException) {
            Timber.d("Scoped storage blocked access to subtitles subdirectory")
        }

        return null
    }

    private fun findSubtitleViaContentResolver(
        directory: File,
        baseName: String,
        extension: String
    ): List<SubtitleCue>? {
        return try {
            val dirPath = directory.absolutePath
            val fileName = "$baseName.$extension"
            val uri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(MediaStore.Files.FileColumns.DATA)
            val selection = "${MediaStore.Files.FileColumns.DATA} = ?"
            val selectionArgs = arrayOf("$dirPath/$fileName")

            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                    val filePath = cursor.getString(dataColumn)
                    Timber.i("Found subtitle via ContentResolver fallback: %s", filePath)
                    try {
                        val fileUri = MediaStore.Files.getContentUri("external", cursor.getLong(
                            cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                        ))
                        context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                            val ext = filePath.substringAfterLast('.', "").lowercase()
                            val parsed = parseSubtitleFromStream(inputStream, ext)
                            if (parsed.isNotEmpty()) parsed else null
                        }
                    } catch (e: Exception) {
                        Timber.d(e, "ContentResolver stream failed, trying direct File access")
                        val file = File(filePath)
                        if (file.exists() && file.canRead()) {
                            val ext = filePath.substringAfterLast('.', "").lowercase()
                            return@use parseSubtitleFile(file, ext)
                        }
                    }
                    null
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Timber.d(e, "ContentResolver fallback failed for %s.%s", baseName, extension)
            null
        }
    }

    private fun findSubtitlesViaMediaStore(videoPath: String): List<SubtitleCue> {
        val baseName = File(videoPath).nameWithoutExtension
        val results = mutableListOf<SubtitleCue>()

        try {
            val uri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns._ID
            )

            val escapedBaseName = baseName
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
            val extensionConditions = subtitleExtensions.joinToString(" OR ") {
                "${MediaStore.Files.FileColumns.DATA} LIKE '%$escapedBaseName.$it' ESCAPE '\\'"
            }
            val selection = "($extensionConditions)"

            context.contentResolver.query(uri, projection, selection, null, null)?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)

                while (cursor.moveToNext()) {
                    val filePath = cursor.getString(dataColumn)
                    val ext = filePath.substringAfterLast('.', "").lowercase()
                    val fileId = cursor.getLong(idColumn)
                    if (ext in subtitleExtensions) {
                        try {
                            val fileUri = MediaStore.Files.getContentUri("external", fileId)
                            context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                                val parsed = parseSubtitleFromStream(inputStream, ext)
                                if (parsed.isNotEmpty()) {
                                    Timber.i("Found subtitle via MediaStore+ContentResolver: %s", filePath)
                                    results.addAll(parsed)
                                }
                            }
                            if (results.isNotEmpty()) break
                        } catch (e: SecurityException) {
                            Timber.d("Scoped storage blocked access to MediaStore result: %s", filePath)
                            try {
                                val file = File(filePath)
                                if (file.exists() && file.canRead()) {
                                    Timber.i("Found subtitle via MediaStore+File: %s", filePath)
                                    val parsed = parseSubtitleFile(file, ext)
                                    if (parsed.isNotEmpty()) {
                                        results.addAll(parsed)
                                        break
                                    }
                                }
                            } catch (_: SecurityException) {}
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to query MediaStore for subtitles")
        }

        return results
    }

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
