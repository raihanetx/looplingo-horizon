package com.looplingo.horizon.domain.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

@javax.inject.Singleton
class FileResolver @javax.inject.Inject constructor() {

    internal fun resolveToReadableFile(context: Context, filePath: String): Pair<File, () -> Unit> {
        if (filePath.startsWith("content://")) {
            val uri = Uri.parse(filePath)
            try {
                val extracted = extractAudioFromContentUri(context, uri)
                if (extracted != null) {
                    Timber.i("Resolved content:// URI via audio extraction: %.1fKB", extracted.length() / 1024.0)
                    return Pair(extracted) { extracted.delete() }
                }
            } catch (e: Exception) {
                Timber.w(e, "Audio extraction from content URI failed, falling back to full copy")
            }
            val tempFile = copyContentUriToTempFile(context, filePath)
            return Pair(tempFile) { tempFile.delete() }
        }

        val directFile = File(filePath)
        if (directFile.exists() && directFile.canRead()) {
            return Pair(directFile) { /* no cleanup */ }
        }

        try {
            val contentUri = resolvePathToContentUri(context, filePath)
            if (contentUri != null) {
                val tempFile = copyContentUriToTempFile(context, contentUri)
                return Pair(tempFile) { tempFile.delete() }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to resolve path via MediaStore")
        }

        return Pair(directFile) { /* nothing to clean up */ }
    }

    internal fun extractAudioFromContentUri(context: Context, uri: Uri): File? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            extractor.setDataSource(context, uri, null)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    Timber.i("Found audio track %d: %s in content URI", i, mime)
                    break
                }
            }
            if (audioTrackIndex < 0 || audioFormat == null) return null

            val audioMime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(audioTrackIndex)

            val outputFormat = when {
                audioMime.contains("aac") || audioMime.contains("mp4a") -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                audioMime.contains("mpeg") || audioMime.contains("mp3") -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                audioMime.contains("ogg") || audioMime.contains("opus") -> MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
                else -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }
            val outputExt = when (outputFormat) {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 -> "m4a"
                MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG -> "ogg"
                else -> "m4a"
            }

            val outputFile = File.createTempFile("looplingo_uri_", ".$outputExt", context.cacheDir)
            muxer = MediaMuxer(outputFile.absolutePath, outputFormat)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = ByteBuffer.allocate(256 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            var sampleCount = 0

            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.flags = extractor.sampleFlags
                bufferInfo.presentationTimeUs = extractor.sampleTime

                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                sampleCount++
                extractor.advance()
            }

            muxer.stop()

            if (outputFile.length() == 0L) {
                outputFile.delete()
                return null
            }

            Timber.i("Extracted audio from content URI: %d samples, %s (%.1fKB)",
                sampleCount, audioMime, outputFile.length() / 1024.0)
            return outputFile

        } catch (e: Exception) {
            Timber.w(e, "Failed to extract audio from content URI")
            return null
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    internal fun copyContentUriToTempFile(context: Context, contentUri: String): File {
        val uri = Uri.parse(contentUri)
        val ext = guessExtensionFromUri(context, uri) ?: "mp4"
        val tempFile = File.createTempFile("looplingo_input_", ".$ext", context.cacheDir)

        try {
            val sourceSize = try {
                context.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: 0L
            } catch (_: Exception) { 0L }
            if (sourceSize > 100 * 1024 * 1024) {
                Timber.w("Copying large file from content URI: %.1fMB — audio extraction failed, this may be slow",
                    sourceSize / (1024.0 * 1024.0))
            }

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output, 64 * 1024) }
            } ?: throw com.looplingo.horizon.data.remote.SubtitleException("Cannot open content URI: $contentUri")
        } catch (e: com.looplingo.horizon.data.remote.SubtitleException) {
            tempFile.delete()
            throw e
        } catch (e: Exception) {
            tempFile.delete()
            throw com.looplingo.horizon.data.remote.SubtitleException("Failed to read file from storage: ${e.message}")
        }

        Timber.i("Copied content URI → %s (%.2fKB)", tempFile.name, tempFile.length() / 1024.0)
        return tempFile
    }

    internal fun guessExtensionFromUri(context: Context, uri: Uri): String? {
        return try {
            when (val mimeType = context.contentResolver.getType(uri)) {
                null -> null
                else -> when {
                    mimeType.startsWith("audio/mpeg") -> "mp3"
                    mimeType.startsWith("audio/mp4") -> "m4a"
                    mimeType.startsWith("audio/aac") -> "m4a"
                    mimeType.startsWith("audio/ogg") -> "ogg"
                    mimeType.startsWith("audio/opus") -> "ogg"
                    mimeType.startsWith("audio/wav") -> "wav"
                    mimeType.startsWith("audio/flac") -> "flac"
                    mimeType.startsWith("audio/webm") -> "webm"
                    mimeType.startsWith("audio/3gpp") -> "3gp"
                    mimeType.startsWith("video/mp4") -> "mp4"
                    mimeType.startsWith("video/3gpp") -> "3gp"
                    mimeType.startsWith("video/webm") -> "webm"
                    else -> mimeType.substringAfterLast('/')
                }
            }
        } catch (e: Exception) { null }
    }

    internal fun resolvePathToContentUri(context: Context, filePath: String): String? {
        try {
            val proj = arrayOf(android.provider.MediaStore.Video.Media._ID)
            val selection = "${android.provider.MediaStore.Video.Media.DATA} = ?"
            val selArgs = arrayOf(filePath)

            context.contentResolver.query(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                proj, selection, selArgs, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID))
                    return android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                    ).toString()
                }
            }

            context.contentResolver.query(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(android.provider.MediaStore.Audio.Media._ID),
                "${android.provider.MediaStore.Audio.Media.DATA} = ?",
                arrayOf(filePath), null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID))
                    return android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                    ).toString()
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to resolve path to content URI")
        }
        return null
    }
}
