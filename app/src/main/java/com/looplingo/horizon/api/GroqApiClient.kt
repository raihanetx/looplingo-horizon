package com.looplingo.horizon.api

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Client for the Groq Whisper API — speech-to-text transcription.
 *
 * Simplified pipeline (v1.11.0):
 *  1. Resolve the file path — handle both file:// and content:// URIs
 *  2. If file is under 25MB → send directly (Groq Whisper handles MP4, WebM, etc.)
 *  3. If file is over 25MB → extract audio track, then chunk if still over 25MB
 *  4. Send each chunk to Groq Whisper with progress updates
 *  5. Merge all segments with proper time offsets
 *
 * Key insight: Groq Whisper natively supports MP4 (video containers), WebM, and
 * many other formats. We only need to extract audio when the file is too large
 * to send directly. This eliminates the fragile MediaExtractor+MediaMuxer pipeline
 * for most common use cases.
 *
 * IMPORTANT: This class requires a [Context] for scoped storage access.
 * On Android 10+ (API 29+), raw file paths may be inaccessible via java.io.File,
 * but content:// URIs work fine through ContentResolver.
 */
class GroqApiClient {

    companion object {
        private const val GROQ_MAX_FILE_SIZE = 25L * 1024 * 1024  // 25MB
        private const val GROQ_API_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * A single transcription segment with timing information.
     */
    data class Segment(
        val id: Int,
        val text: String,
        @SerializedName("start")
        val startSec: Double,
        @SerializedName("end")
        val endSec: Double
    ) {
        val startMs: Long get() = (startSec * 1000).toLong()
        val endMs: Long get() = (endSec * 1000).toLong()
    }

    /** Progress callback for UI updates */
    fun interface ProgressCallback {
        fun onProgress(step: String)
    }

    /**
     * Thrown when subtitle generation fails with a specific reason.
     * The fragment can show this message to the user.
     */
    class SubtitleException(message: String) : Exception(message)

    // ── JSON response models ──────────────────────────────────────────

    private data class TranscriptionResponse(
        val segments: List<SegmentJson>? = null,
        val text: String? = null,
        val error: ErrorJson? = null
    )

    private data class SegmentJson(
        val id: Int,
        val text: String,
        val start: Double,
        val end: Double
    )

    private data class ErrorJson(
        val message: String? = null,
        val type: String? = null
    )

    /**
     * Transcribe an audio/video file using the Groq Whisper API.
     *
     * Simplified pipeline:
     *  1. Resolve the file — handle both file:// and content:// URIs
     *  2. File under 25MB → send directly (Whisper handles video containers!)
     *  3. File over 25MB → extract audio → chunk if needed → transcribe each
     *
     * @param context Android context for ContentResolver access
     * @param apiKey Groq API key
     * @param filePath File path or content:// URI to the audio/video file
     * @param onProgress Callback for UI progress updates
     * @return List of timed segments with absolute timestamps
     * @throws SubtitleException if transcription fails with a known reason
     */
    suspend fun transcribeAudio(
        context: Context,
        apiKey: String,
        filePath: String,
        onProgress: ProgressCallback? = null
    ): List<Segment> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw SubtitleException("Groq API key is blank — enter your API key first")
        }

        if (filePath.isBlank()) {
            throw SubtitleException("No file selected — go back and pick an audio/video file")
        }

        // ── Step 0: Resolve to a readable file ────────────────────────
        onProgress?.onProgress("Preparing audio file…")
        val (sourceFile, cleanupSource) = resolveToReadableFile(context, filePath)

        try {
            if (!sourceFile.exists() || sourceFile.length() == 0L) {
                throw SubtitleException("Cannot read file: ${sourceFile.name}. " +
                    "Try selecting the file again or check storage permissions.")
            }

            Timber.i("Starting transcription: %s (%.1fMB)",
                sourceFile.name, sourceFile.length() / (1024.0 * 1024.0))

            // ── Step 1: If file fits in 25MB, send directly ────────────
            // Groq Whisper natively supports MP4 (with video), WebM, MP3, WAV, etc.
            // No need to extract audio for small files!
            if (sourceFile.length() <= GROQ_MAX_FILE_SIZE) {
                onProgress?.onProgress("Sending to Groq Whisper…")
                val segments = callWhisperApi(apiKey, sourceFile)
                Timber.i("Direct transcription: %d segments", segments.size)

                if (segments.isEmpty()) {
                    throw SubtitleException("No speech detected in this audio. " +
                        "Make sure the file contains clear speech.")
                }
                return@withContext segments
            }

            // ── Step 2: File is too large — extract audio track ─────────
            onProgress?.onProgress("File too large (%.1fMB), extracting audio…"
                .format(sourceFile.length() / (1024.0 * 1024.0)))

            val audioFile = extractAudioTrack(sourceFile, context)
            if (audioFile == null) {
                // Extraction failed — try sending the first 25MB as a last resort
                Timber.w("Audio extraction failed, sending first 25MB of file")
                onProgress?.onProgress("Sending partial file to Groq Whisper…")
                val truncatedFile = truncateFile(sourceFile, GROQ_MAX_FILE_SIZE)
                try {
                    val segments = callWhisperApi(apiKey, truncatedFile)
                    if (segments.isEmpty()) {
                        throw SubtitleException("No speech detected. The file may be too large " +
                            "or the audio format may not be supported.")
                    }
                    return@withContext segments
                } finally {
                    if (truncatedFile.absolutePath != sourceFile.absolutePath) {
                        truncatedFile.delete()
                    }
                }
            }

            try {
                // ── Step 3: If extracted audio fits, send it ────────────
                if (audioFile.length() <= GROQ_MAX_FILE_SIZE) {
                    onProgress?.onProgress("Sending audio to Groq Whisper…")
                    val segments = callWhisperApi(apiKey, audioFile)
                    Timber.i("Audio transcription: %d segments", segments.size)

                    if (segments.isEmpty()) {
                        throw SubtitleException("No speech detected in this audio. " +
                            "Make sure the file contains clear speech.")
                    }
                    return@withContext segments
                }

                // ── Step 4: Chunk the extracted audio and transcribe each ──
                onProgress?.onProgress("Splitting audio into chunks…")
                val chunks = chunkAudioByTime(audioFile, context)
                if (chunks.isEmpty()) {
                    throw SubtitleException("Failed to split audio file. " +
                        "Try a shorter video or audio file.")
                }

                val result = transcribeChunks(apiKey, chunks, onProgress)
                if (result.isEmpty()) {
                    throw SubtitleException("No speech detected across all audio chunks. " +
                        "Make sure the file contains clear speech.")
                }
                result
            } finally {
                // Clean up extracted audio temp file
                if (audioFile.absolutePath != sourceFile.absolutePath) {
                    audioFile.delete()
                }
            }
        } finally {
            cleanupSource()
        }
    }

    /**
     * Resolve a file path or content:// URI to a readable File.
     *
     * On Android 10+ (scoped storage), raw file paths from MediaStore.DATA
     * may not be accessible via java.io.File. This method:
     *  - If the path is a content:// URI → copies the content to a temp file via ContentResolver
     *  - If the file exists and is readable → uses it directly
     *  - If the file doesn't exist via File API → tries to resolve via MediaStore
     *
     * Returns the File and a cleanup function to delete the temp file when done.
     */
    private fun resolveToReadableFile(context: Context, filePath: String): Pair<File, () -> Unit> {
        // Case 1: It's a content:// URI — copy to temp file
        if (filePath.startsWith("content://")) {
            Timber.i("Resolving content:// URI to temp file")
            val tempFile = copyContentUriToTempFile(context, filePath)
            return Pair(tempFile) { tempFile.delete() }
        }

        // Case 2: It's a file path that we can access directly
        val directFile = File(filePath)
        if (directFile.exists() && directFile.canRead()) {
            Timber.i("Using file path directly: %s", filePath)
            return Pair(directFile) { /* no cleanup needed */ }
        }

        // Case 3: File path is not accessible (scoped storage) — try to resolve via MediaStore
        Timber.w("File not accessible via File API, trying content resolver: %s", filePath)
        try {
            val contentUri = resolvePathToContentUri(context, filePath)
            if (contentUri != null) {
                val tempFile = copyContentUriToTempFile(context, contentUri)
                return Pair(tempFile) { tempFile.delete() }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to resolve path to content URI")
        }

        // Case 4: Last resort — return the File anyway (will fail with a clear error message)
        Timber.w("Could not resolve file: %s", filePath)
        return Pair(directFile) { /* nothing to clean up */ }
    }

    /**
     * Copy content from a content:// URI to a temporary file.
     * Uses the actual file extension based on MIME type for proper format detection.
     */
    private fun copyContentUriToTempFile(context: Context, contentUri: String): File {
        val uri = Uri.parse(contentUri)

        // Determine the proper extension from the MIME type
        val ext = guessExtensionFromUri(context, uri) ?: "mp4"

        val tempFile = File.createTempFile("looplingo_input_", ".$ext", context.cacheDir)

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, 64 * 1024)
                }
            } ?: throw SubtitleException("Cannot open content URI: $contentUri")
        } catch (e: SubtitleException) {
            tempFile.delete()
            throw e
        } catch (e: Exception) {
            tempFile.delete()
            throw SubtitleException("Failed to read file from storage: ${e.message}")
        }

        Timber.i("Copied content URI to temp file: %s (%.1fKB)",
            tempFile.name, tempFile.length() / 1024.0)

        return tempFile
    }

    /**
     * Try to guess the file extension from a content URI using ContentResolver.
     */
    private fun guessExtensionFromUri(context: Context, uri: Uri): String? {
        return try {
            val mimeType = context.contentResolver.getType(uri)
            when {
                mimeType == null -> null
                mimeType.startsWith("audio/mpeg") -> "mp3"
                mimeType.startsWith("audio/mp4") || mimeType.startsWith("audio/aac") -> "m4a"
                mimeType.startsWith("audio/ogg") || mimeType.startsWith("audio/opus") -> "ogg"
                mimeType.startsWith("audio/wav") -> "wav"
                mimeType.startsWith("audio/flac") -> "flac"
                mimeType.startsWith("audio/webm") -> "webm"
                mimeType.startsWith("audio/3gpp") -> "3gp"
                mimeType.startsWith("video/mp4") -> "mp4"
                mimeType.startsWith("video/3gpp") -> "3gp"
                mimeType.startsWith("video/webm") -> "webm"
                mimeType.startsWith("video/x-matroska") -> "mkv"
                else -> mimeType.substringAfterLast('/')
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Try to resolve a raw file path to a content:// URI using MediaStore.
     * Checks both Video and Audio tables, plus the Files table as a fallback.
     */
    private fun resolvePathToContentUri(context: Context, filePath: String): String? {
        // Try Video MediaStore
        try {
            val projection = arrayOf(android.provider.MediaStore.Video.Media._ID)
            val selection = "${android.provider.MediaStore.Video.Media.DATA} = ?"
            val selectionArgs = arrayOf(filePath)

            context.contentResolver.query(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID)
                    val id = cursor.getLong(idColumn)
                    return "${android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI}/$id"
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to query Video MediaStore for path")
        }

        // Try Audio MediaStore
        try {
            val projection = arrayOf(android.provider.MediaStore.Audio.Media._ID)
            val selection = "${android.provider.MediaStore.Audio.Media.DATA} = ?"
            val selectionArgs = arrayOf(filePath)

            context.contentResolver.query(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID)
                    val id = cursor.getLong(idColumn)
                    return "${android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI}/$id"
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to query Audio MediaStore for path")
        }

        // Try Files MediaStore (broadest search)
        try {
            val filesUri = android.provider.MediaStore.Files.getContentUri("external")
            val projection = arrayOf(android.provider.MediaStore.Files.FileColumns._ID)
            val selection = "${android.provider.MediaStore.Files.FileColumns.DATA} = ?"
            val selectionArgs = arrayOf(filePath)

            context.contentResolver.query(
                filesUri, projection, selection, selectionArgs, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Files.FileColumns._ID)
                    val id = cursor.getLong(idColumn)
                    return "$filesUri/$id"
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to query Files MediaStore for path")
        }

        return null
    }

    /**
     * Extract the audio track from a video file using MediaExtractor + MediaMuxer.
     * Returns a temporary audio file, or null if extraction fails.
     *
     * This is only used when the source file is too large to send directly.
     */
    private fun extractAudioTrack(sourceFile: File, context: Context): File? {
        val extractor = android.media.MediaExtractor()
        var muxer: android.media.MediaMuxer? = null
        val outputFile = File.createTempFile("looplingo_audio_", ".m4a", context.cacheDir)

        try {
            extractor.setDataSource(sourceFile.absolutePath)

            // Find the first audio track
            var audioTrackIndex = -1
            var audioFormat: android.media.MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                Timber.w("No audio track found in: %s", sourceFile.name)
                outputFile.delete()
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(android.media.MediaFormat.KEY_MIME) ?: run {
                outputFile.delete()
                return null
            }

            Timber.i("Found audio track: %s in %s", mime, sourceFile.name)

            // Determine output format based on audio codec
            val outputFormat = when {
                mime.startsWith("audio/mp4") || mime.startsWith("audio/aac") ->
                    android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                mime.startsWith("audio/ogg") || mime.startsWith("audio/opus") ->
                    android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
                else ->
                    android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }

            val outputExt = when (outputFormat) {
                android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG -> ".ogg"
                else -> ".m4a"
            }

            // Rename file with correct extension
            if (outputExt != ".m4a") {
                val renamedFile = File(outputFile.parent, outputFile.nameWithoutExtension + outputExt)
                outputFile.renameTo(renamedFile)
                val finalFile = renamedFile

                muxer = android.media.MediaMuxer(finalFile.absolutePath, outputFormat)
                val muxerTrackIndex = muxer.addTrack(audioFormat)
                muxer.start()

                val buffer = android.media.MediaCodec.BufferInfo()
                val inputBuffer = java.nio.ByteBuffer.allocate(256 * 1024)

                while (true) {
                    inputBuffer.clear()
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) break

                    buffer.offset = 0
                    buffer.size = sampleSize
                    buffer.flags = extractor.sampleFlags
                    buffer.presentationTimeUs = extractor.sampleTime

                    inputBuffer.position(0)
                    inputBuffer.limit(sampleSize)
                    muxer.writeSampleData(muxerTrackIndex, inputBuffer, buffer)
                    extractor.advance()
                }

                muxer.stop()
                Timber.i("Extracted audio: %.1fMB from %s", finalFile.length() / (1024.0 * 1024.0), sourceFile.name)
                return finalFile
            } else {
                muxer = android.media.MediaMuxer(outputFile.absolutePath, outputFormat)
                val muxerTrackIndex = muxer.addTrack(audioFormat)
                muxer.start()

                val buffer = android.media.MediaCodec.BufferInfo()
                val inputBuffer = java.nio.ByteBuffer.allocate(256 * 1024)

                while (true) {
                    inputBuffer.clear()
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) break

                    buffer.offset = 0
                    buffer.size = sampleSize
                    buffer.flags = extractor.sampleFlags
                    buffer.presentationTimeUs = extractor.sampleTime

                    inputBuffer.position(0)
                    inputBuffer.limit(sampleSize)
                    muxer.writeSampleData(muxerTrackIndex, inputBuffer, buffer)
                    extractor.advance()
                }

                muxer.stop()
                Timber.i("Extracted audio: %.1fMB from %s", outputFile.length() / (1024.0 * 1024.0), sourceFile.name)
                return outputFile
            }

        } catch (e: Exception) {
            Timber.e(e, "Failed to extract audio from video")
            outputFile.delete()
            return null
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Chunk an audio file by time using MediaExtractor + MediaMuxer.
     * Each chunk is approximately 30 seconds long.
     */
    private data class AudioChunk(
        val file: File,
        val startTimeSec: Double
    )

    private fun chunkAudioByTime(audioFile: File, context: Context): List<AudioChunk> {
        val extractor = android.media.MediaExtractor()
        val chunks = mutableListOf<AudioChunk>()
        val chunkDurationUs = 30L * 1000 * 1000  // 30 seconds

        try {
            extractor.setDataSource(audioFile.absolutePath)

            // Find the audio track
            var audioTrackIndex = -1
            var audioFormat: android.media.MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                Timber.w("No audio track found for chunking: %s", audioFile.name)
                return emptyList()
            }

            val mime = audioFormat.getString(android.media.MediaFormat.KEY_MIME) ?: return emptyList()
            val durationUs = try {
                audioFormat.getLong(android.media.MediaFormat.KEY_DURATION)
            } catch (e: Exception) {
                -1L
            }

            if (durationUs <= 0) {
                Timber.w("Could not determine audio duration for chunking")
                return emptyList()
            }

            val numChunks = ((durationUs + chunkDurationUs - 1) / chunkDurationUs).toInt()
                .coerceIn(1, 30)

            Timber.i("Audio duration: %.1fs, splitting into %d chunks",
                durationUs / 1_000_000.0, numChunks)

            val outputFormat = when {
                mime.startsWith("audio/mp4") || mime.startsWith("audio/aac") ->
                    android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                mime.startsWith("audio/ogg") || mime.startsWith("audio/opus") ->
                    android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
                else -> android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }

            val ext = when (outputFormat) {
                android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG -> ".ogg"
                else -> ".m4a"
            }

            extractor.selectTrack(audioTrackIndex)

            // Read all samples into memory
            val allSamples = mutableListOf<SampleData>()
            extractor.seekTo(0, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val inputBuffer = java.nio.ByteBuffer.allocate(256 * 1024)
            while (true) {
                inputBuffer.clear()
                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                if (sampleSize < 0) break

                val data = ByteArray(sampleSize)
                inputBuffer.position(0)
                inputBuffer.get(data)

                allSamples.add(SampleData(
                    data = data,
                    presentationTimeUs = extractor.sampleTime,
                    flags = extractor.sampleFlags
                ))
                extractor.advance()
            }

            Timber.d("Read %d audio samples for chunking", allSamples.size)

            if (allSamples.isEmpty()) {
                Timber.w("No audio samples found")
                return emptyList()
            }

            // Split samples into chunks by time
            for (chunkIndex in 0 until numChunks) {
                val chunkStartUs = chunkIndex * chunkDurationUs
                val chunkEndUs = (chunkIndex + 1) * chunkDurationUs

                val chunkSamples = allSamples.filter {
                    it.presentationTimeUs >= chunkStartUs && it.presentationTimeUs < chunkEndUs
                }

                if (chunkSamples.isEmpty()) continue

                val chunkFile = File.createTempFile("looplingo_chunk_${chunkIndex}_", ext, context.cacheDir)
                var muxer: android.media.MediaMuxer? = null

                try {
                    muxer = android.media.MediaMuxer(chunkFile.absolutePath, outputFormat)
                    val muxerTrackIndex = muxer.addTrack(audioFormat!!)
                    muxer.start()

                    val bufferInfo = android.media.MediaCodec.BufferInfo()

                    for (sample in chunkSamples) {
                        val buf = java.nio.ByteBuffer.allocate(sample.data.size)
                        buf.put(sample.data)
                        buf.flip()

                        bufferInfo.offset = 0
                        bufferInfo.size = sample.data.size
                        bufferInfo.flags = sample.flags
                        bufferInfo.presentationTimeUs = sample.presentationTimeUs - chunkStartUs

                        muxer.writeSampleData(muxerTrackIndex, buf, bufferInfo)
                    }

                    muxer.stop()

                    val startTimeSec = chunkStartUs / 1_000_000.0
                    chunks.add(AudioChunk(chunkFile, startTimeSec))

                    Timber.d("Chunk %d: %d samples, %.1fKB, starts at %.1fs",
                        chunkIndex, chunkSamples.size, chunkFile.length() / 1024.0, startTimeSec)

                } catch (e: Exception) {
                    Timber.e(e, "Failed to create chunk %d", chunkIndex)
                    chunkFile.delete()
                } finally {
                    try { muxer?.release() } catch (_: Exception) {}
                }
            }

        } catch (e: Exception) {
            Timber.e(e, "Chunk splitting failed")
            chunks.forEach { it.file.delete() }
            return emptyList()
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }

        return chunks
    }

    private data class SampleData(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int
    )

    /**
     * Truncate a file to a maximum size by copying the first N bytes.
     * This is a last-resort fallback when audio extraction fails.
     */
    private fun truncateFile(sourceFile: File, maxSize: Long): File {
        if (sourceFile.length() <= maxSize) return sourceFile

        val truncatedFile = File.createTempFile("looplingo_trunc_", ".${sourceFile.extension}", sourceFile.parentFile)
        try {
            java.io.FileInputStream(sourceFile).use { input ->
                java.io.FileOutputStream(truncatedFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = maxSize
                    while (remaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = input.read(buffer, 0, toRead)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
        } catch (e: Exception) {
            truncatedFile.delete()
            return sourceFile  // Fallback to original
        }

        return truncatedFile
    }

    /**
     * Transcribe a list of chunks and merge results.
     */
    private suspend fun transcribeChunks(
        apiKey: String,
        chunks: List<AudioChunk>,
        onProgress: ProgressCallback?
    ): List<Segment> = withContext(Dispatchers.IO) {
        Timber.i("Transcribing %d chunks", chunks.size)
        val allSegments = mutableListOf<Segment>()
        var segmentIdOffset = 0

        for ((index, chunk) in chunks.withIndex()) {
            val chunkNum = index + 1
            onProgress?.onProgress("Transcribing chunk $chunkNum/${chunks.size}…")

            try {
                val chunkSegments = callWhisperApi(apiKey, chunk.file)
                val offsetSec = chunk.startTimeSec
                for (seg in chunkSegments) {
                    allSegments.add(
                        Segment(
                            id = segmentIdOffset + seg.id,
                            text = seg.text,
                            startSec = seg.startSec + offsetSec,
                            endSec = seg.endSec + offsetSec
                        )
                    )
                }
                segmentIdOffset += chunkSegments.size
                Timber.d("Chunk %d: %d segments (offset +%.1fs)", chunkNum, chunkSegments.size, offsetSec)
            } catch (e: Exception) {
                Timber.e(e, "Failed to transcribe chunk %d", chunkNum)
            }

            chunk.file.delete()
        }

        Timber.i("Chunked transcription complete: %d total segments", allSegments.size)
        allSegments
    }

    /**
     * Call the Groq Whisper API with a single file.
     *
     * Groq Whisper supports these formats: flac, m4a, mp3, mp4, mpeg, mpga,
     * oga, ogg, wav, webm. MP4 includes video containers — no need to extract
     * audio first for files under 25MB.
     */
    private fun callWhisperApi(apiKey: String, audioFile: File): List<Segment> {
        val mediaType = guessMediaType(audioFile.name)
        Timber.i("Sending to Groq Whisper: %s as %s (%.1fKB)",
            audioFile.name, mediaType, audioFile.length() / 1024.0)

        val fileBody = audioFile.asRequestBody(mediaType.toMediaType())

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, fileBody)
            .addFormDataPart("model", "whisper-large-v3")
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "segment")
            .build()

        val request = Request.Builder()
            .url(GROQ_API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(multipartBody)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()

        if (!response.isSuccessful || responseBody.isNullOrBlank()) {
            val errorDetail = responseBody?.take(500) ?: "No response body"
            Timber.e("Groq API error: %d — %s", response.code, errorDetail)
            throw RuntimeException("Groq API error ${response.code}: ${responseBody?.take(200) ?: "No response"}")
        }

        Timber.d("Groq API response (%d bytes): %s", responseBody.length, responseBody.take(300))

        val transcription = parseTranscriptionResponse(responseBody)

        if (transcription.error != null) {
            val errMsg = transcription.error.message ?: "Unknown error"
            Timber.e("Groq API returned error: %s", errMsg)
            throw RuntimeException(errMsg)
        }

        // If segments are null but text exists, create a single segment
        if (transcription.segments.isNullOrEmpty() && !transcription.text.isNullOrBlank()) {
            Timber.i("No segments returned but got full text — creating single segment")
            return listOf(Segment(id = 0, text = transcription.text.trim(), startSec = 0.0, endSec = 0.0))
        }

        return transcription.segments?.map { segJson ->
            Segment(
                id = segJson.id,
                text = segJson.text.trim(),
                startSec = segJson.start,
                endSec = segJson.end
            )
        } ?: emptyList()
    }

    /**
     * Guess the MIME type for a file based on its extension.
     *
     * IMPORTANT: Groq Whisper supports MP4 files (including video containers).
     * We use "video/mp4" for .mp4 files because that's what they actually are,
     * and Groq handles them correctly. For audio-only containers (.m4a), we
     * use "audio/mp4".
     */
    private fun guessMediaType(fileName: String): String {
        return when (fileName.substringAfterLast(".", "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"       // MP4 may contain video — Groq handles it
            "m4a" -> "audio/mp4"       // M4A is audio-only MP4
            "wav" -> "audio/wav"
            "webm" -> "video/webm"     // WebM may contain video — Groq handles it
            "ogg" -> "audio/ogg"
            "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            "3gp" -> "video/3gpp"
            "mkv" -> "video/x-matroska"
            "mpeg" -> "video/mpeg"
            else -> "application/octet-stream"
        }
    }

    private fun parseTranscriptionResponse(json: String): TranscriptionResponse {
        return try {
            gson.fromJson(json, TranscriptionResponse::class.java)
                ?: TranscriptionResponse(error = ErrorJson(message = "Null response"))
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse Groq API response: %s", json.take(200))
            TranscriptionResponse(error = ErrorJson(message = "Parse error: ${e.message}"))
        }
    }
}
