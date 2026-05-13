package com.looplingo.horizon.api

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
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
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Client for the Groq Whisper API — speech-to-text transcription.
 *
 * Architecture (v1.12.0 — Chunk-First Design):
 *
 *   ┌──────────────────────────────────────────────────────────┐
 *   │  USER CLICKS "Generate Subtitles"                        │
 *   └──────────┬───────────────────────────────────────────────┘
 *              │
 *              ▼
 *   ┌──────────────────────────────────────────────────────────┐
 *   │  Step 1: RESOLVE FILE                                    │
 *   │  • content:// URI → copy to temp file via ContentResolver │
 *   │  • File path accessible → use directly                    │
 *   │  • File path not accessible → query MediaStore            │
 *   └──────────┬───────────────────────────────────────────────┘
 *              │
 *              ▼
 *   ┌──────────────────────────────────────────────────────────┐
 *   │  Step 2: ALWAYS EXTRACT AUDIO                            │
 *   │  • Video MP4/WebM → extract audio track → .m4a/.ogg      │
 *   │  • Audio-only file → use directly if under 5MB            │
 *   │  • Audio-only but large → still extract to normalize       │
 *   │  WHY: Whisper works best with audio-only files.            │
 *   │       Video containers can cause "No speech detected".     │
 *   └──────────┬───────────────────────────────────────────────┘
 *              │
 *              ▼
 *   ┌──────────────────────────────────────────────────────────┐
 *   │  Step 3: CHUNK INTO TINY PIECES (1-2MB each)             │
 *   │  • Each chunk ≈ 10-15 seconds of audio                    │
 *   │  • Each chunk is a COMPLETE, VALID audio file              │
 *   │  • Format-aware: AAC→M4A, Opus→OGG                        │
 *   │  WHY: Small chunks are more reliably processed by Whisper. │
 *   │       Large files cause timeouts and "No speech detected". │
 *   └──────────┬───────────────────────────────────────────────┘
 *              │
 *              ▼
 *   ┌──────────────────────────────────────────────────────────┐
 *   │  Step 4: SEND EACH CHUNK TO WHISPER                       │
 *   │  • Content-Type: audio/mpeg (mp3), audio/mp4 (m4a), etc.  │
 *   │  • Each chunk returns segments with relative timestamps    │
 *   │  • Merge all segments with absolute time offsets           │
 *   └──────────┬───────────────────────────────────────────────┘
 *              │
 *              ▼
 *   ┌──────────────────────────────────────────────────────────┐
 *   │  Step 5: RETURN MERGED SEGMENTS                           │
 *   │  • All segments have absolute timestamps from original file│
 *   │  • Ready for dialogue navigation and looping               │
 *   └──────────────────────────────────────────────────────────┘
 *
 * IMPORTANT: This class requires a [Context] for scoped storage access.
 * On Android 10+ (API 29+), raw file paths may be inaccessible via java.io.File,
 * but content:// URIs work fine through ContentResolver.
 */
class GroqApiClient {

    companion object {
        // Groq Whisper API limits
        private const val GROQ_MAX_FILE_SIZE = 25L * 1024 * 1024  // 25MB hard limit
        private const val CHUNK_TARGET_SIZE = 1L * 1024 * 1024    // 1MB target per chunk (~10-15s audio)
        private const val CHUNK_MAX_DURATION_US = 15L * 1000 * 1000  // 15 seconds max per chunk
        private const val MIN_CHUNK_DURATION_US = 5L * 1000 * 1000   // 5 seconds min per chunk

        private const val GROQ_API_URL = "https://api.groq.com/openai/v1/audio/transcriptions"

        // Audio-only extensions — files that don't need extraction
        private val AUDIO_ONLY_EXTENSIONS = setOf("mp3", "m4a", "wav", "ogg", "flac", "aac", "opus", "wma")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // ── Public data classes ──────────────────────────────────────────

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

    fun interface ProgressCallback {
        fun onProgress(step: String)
    }

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

    // ── Chunk data class ──────────────────────────────────────────────

    private data class AudioChunk(
        val file: File,
        val startTimeSec: Double,
        val durationSec: Double
    )

    private data class SampleData(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int
    )

    // ══════════════════════════════════════════════════════════════════
    // MAIN ENTRY POINT
    // ══════════════════════════════════════════════════════════════════

    /**
     * Transcribe an audio/video file using the Groq Whisper API.
     *
     * Chunk-first pipeline:
     *  1. Resolve file path / content URI → readable temp file
     *  2. ALWAYS extract audio (never send video containers to Whisper)
     *  3. Split audio into tiny chunks (~1-2MB each, ~10-15 seconds)
     *  4. Send each chunk to Whisper
     *  5. Merge all segments with proper time offsets
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

        Timber.i("═══ STARTING TRANSCRIPTION PIPELINE ═══")
        Timber.i("Input: %s", filePath.take(100))

        // ── Step 1: Resolve to a readable file ────────────────────────
        onProgress?.onProgress("Preparing audio file…")
        val (sourceFile, cleanupSource) = resolveToReadableFile(context, filePath)

        try {
            if (!sourceFile.exists() || sourceFile.length() == 0L) {
                throw SubtitleException("Cannot read file: ${sourceFile.name}. " +
                    "Try selecting the file again or check storage permissions.")
            }

            val sourceSizeMB = sourceFile.length() / (1024.0 * 1024.0)
            Timber.i("Step 1 done: source file = %s (%.2fMB)", sourceFile.name, sourceSizeMB)

            // ── Step 2: ALWAYS extract audio ──────────────────────────
            // Whisper works best with audio-only files. Video containers
            // (MP4 with video track) often cause "No speech detected".
            val ext = sourceFile.extension.lowercase()
            val isAudioOnly = ext in AUDIO_ONLY_EXTENSIONS

            val audioFile: File = if (isAudioOnly && sourceFile.length() <= 5L * 1024 * 1024) {
                // Small audio-only file → use directly (no extraction needed)
                Timber.i("Step 2: small audio file (%.2fMB), using directly", sourceSizeMB)
                sourceFile
            } else {
                // Video or large audio → extract audio track
                onProgress?.onProgress("Extracting audio track…")
                Timber.i("Step 2: extracting audio from %s (%.2fMB)", sourceFile.name, sourceSizeMB)

                val extracted = extractAudioTrack(context, sourceFile)
                if (extracted != null) {
                    val extractedMB = extracted.length() / (1024.0 * 1024.0)
                    Timber.i("Step 2 done: extracted audio = %s (%.2fMB)", extracted.name, extractedMB)

                    // Validate: extracted file should be smaller than source
                    if (extracted.length() >= sourceFile.length()) {
                        Timber.w("Extracted audio is larger than source — possible extraction issue")
                    }

                    extracted
                } else {
                    // Extraction failed — as last resort, try sending raw file if small enough
                    if (sourceFile.length() <= GROQ_MAX_FILE_SIZE) {
                        Timber.w("Audio extraction failed, trying raw file as last resort")
                        sourceFile
                    } else {
                        throw SubtitleException(
                            "Could not extract audio from this file. " +
                            "The format may not be supported. Try a different file."
                        )
                    }
                }
            }

            // ── Step 3: Split into tiny chunks ─────────────────────────
            onProgress?.onProgress("Splitting audio into small chunks…")
            Timber.i("Step 3: chunking %s (%.2fMB)", audioFile.name, audioFile.length() / (1024.0 * 1024.0))

            val chunks = splitAudioIntoSmallChunks(context, audioFile)
            if (chunks.isEmpty()) {
                // Chunking failed — try sending the whole file if it's small enough
                if (audioFile.length() <= GROQ_MAX_FILE_SIZE) {
                    Timber.w("Chunking failed, sending whole file as fallback")
                    onProgress?.onProgress("Sending audio to Whisper…")
                    val segments = callWhisperApi(apiKey, audioFile)
                    if (segments.isEmpty()) {
                        throw SubtitleException("No speech detected in this audio. " +
                            "Make sure the file contains clear speech.")
                    }
                    return@withContext segments
                } else {
                    throw SubtitleException("Could not split audio file. " +
                        "Try a shorter video or audio file.")
                }
            }

            Timber.i("Step 3 done: %d chunks created", chunks.size)
            chunks.forEachIndexed { i, chunk ->
                Timber.d("  Chunk %d: %.2fMB, %.1fs-%.1fs",
                    i + 1, chunk.file.length() / (1024.0 * 1024.0),
                    chunk.startTimeSec, chunk.startTimeSec + chunk.durationSec)
            }

            // ── Step 4: Send each chunk to Whisper ─────────────────────
            val result = transcribeChunks(apiKey, chunks, onProgress)

            if (result.isEmpty()) {
                throw SubtitleException("No speech detected across all %d audio chunks. ".format(chunks.size) +
                    "Make sure the file contains clear speech.")
            }

            Timber.i("═══ TRANSCRIPTION COMPLETE: %d segments ═══", result.size)
            result

        } finally {
            cleanupSource()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // STEP 1: RESOLVE FILE PATH
    // ══════════════════════════════════════════════════════════════════

    /**
     * Resolve a file path or content:// URI to a readable File.
     */
    private fun resolveToReadableFile(context: Context, filePath: String): Pair<File, () -> Unit> {
        // Case 1: content:// URI → copy to temp file
        if (filePath.startsWith("content://")) {
            Timber.i("Resolving content:// URI to temp file")
            val tempFile = copyContentUriToTempFile(context, filePath)
            return Pair(tempFile) { tempFile.delete() }
        }

        // Case 2: File path accessible directly
        val directFile = File(filePath)
        if (directFile.exists() && directFile.canRead()) {
            Timber.i("Using file path directly: %s", filePath)
            return Pair(directFile) { /* no cleanup */ }
        }

        // Case 3: File path not accessible (scoped storage) → try MediaStore
        Timber.w("File not accessible via File API, trying MediaStore: %s", filePath)
        try {
            val contentUri = resolvePathToContentUri(context, filePath)
            if (contentUri != null) {
                val tempFile = copyContentUriToTempFile(context, contentUri)
                return Pair(tempFile) { tempFile.delete() }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to resolve path via MediaStore")
        }

        // Case 4: Last resort
        Timber.w("Could not resolve file: %s", filePath)
        return Pair(directFile) { /* nothing to clean up */ }
    }

    /**
     * Copy content from a content:// URI to a temp file with the correct extension.
     */
    private fun copyContentUriToTempFile(context: Context, contentUri: String): File {
        val uri = Uri.parse(contentUri)
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

        Timber.i("Copied content URI → %s (%.2fKB)", tempFile.name, tempFile.length() / 1024.0)
        return tempFile
    }

    private fun guessExtensionFromUri(context: Context, uri: Uri): String? {
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
                    mimeType.startsWith("video/x-matroska") -> "mkv"
                    else -> mimeType.substringAfterLast('/')
                }
            }
        } catch (e: Exception) { null }
    }

    /**
     * Resolve a raw file path to a content:// URI via MediaStore.
     * Checks Video, Audio, and Files tables.
     */
    private fun resolvePathToContentUri(context: Context, filePath: String): String? {
        // Try Video
        try {
            val proj = arrayOf(android.provider.MediaStore.Video.Media._ID)
            val sel = "${android.provider.MediaStore.Video.Media.DATA} = ?"
            context.contentResolver.query(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                proj, sel, arrayOf(filePath), null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID))
                    return "${android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI}/$id"
                }
            }
        } catch (_: Exception) {}

        // Try Audio
        try {
            val proj = arrayOf(android.provider.MediaStore.Audio.Media._ID)
            val sel = "${android.provider.MediaStore.Audio.Media.DATA} = ?"
            context.contentResolver.query(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                proj, sel, arrayOf(filePath), null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID))
                    return "${android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI}/$id"
                }
            }
        } catch (_: Exception) {}

        // Try Files
        try {
            val filesUri = android.provider.MediaStore.Files.getContentUri("external")
            val proj = arrayOf(android.provider.MediaStore.Files.FileColumns._ID)
            val sel = "${android.provider.MediaStore.Files.FileColumns.DATA} = ?"
            context.contentResolver.query(filesUri, proj, sel, arrayOf(filePath), null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.Files.FileColumns._ID))
                    return "$filesUri/$id"
                }
            }
        } catch (_: Exception) {}

        return null
    }

    // ══════════════════════════════════════════════════════════════════
    // STEP 2: EXTRACT AUDIO TRACK
    // ══════════════════════════════════════════════════════════════════

    /**
     * Extract the audio track from a media file using MediaExtractor + MediaMuxer.
     *
     * Always produces a proper audio-only file:
     *  - AAC/MP4A codecs → .m4a (MPEG-4 container)
     *  - Opus/Vorbis/OGG codecs → .ogg (OGG container)
     *
     * Returns null if extraction fails (e.g., no audio track found).
     */
    private fun extractAudioTrack(context: Context, sourceFile: File): File? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var outputFile: File? = null

        try {
            extractor.setDataSource(sourceFile.absolutePath)

            // Find the first audio track
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    Timber.i("Found audio track %d: %s", i, mime)
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                Timber.w("No audio track found in: %s", sourceFile.name)
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null

            // Determine output format
            val outputFormat: Int
            val outputExt: String

            when {
                mime.startsWith("audio/mp4") || mime.startsWith("audio/aac") -> {
                    outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                    outputExt = ".m4a"
                }
                mime.startsWith("audio/ogg") || mime.startsWith("audio/opus") -> {
                    outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
                    outputExt = ".ogg"
                }
                else -> {
                    // Default to M4A — works for most codecs
                    outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                    outputExt = ".m4a"
                }
            }

            outputFile = File.createTempFile("looplingo_audio_", outputExt, context.cacheDir)

            muxer = MediaMuxer(outputFile.absolutePath, outputFormat)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            // Write all audio samples
            val buffer = MediaCodec.BufferInfo()
            val inputBuffer = ByteBuffer.allocate(256 * 1024)
            var sampleCount = 0

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
                sampleCount++
                extractor.advance()
            }

            muxer.stop()

            if (sampleCount == 0) {
                Timber.w("No audio samples written — empty audio track?")
                outputFile.delete()
                return null
            }

            val durationUs = audioFormat.getLong(MediaFormat.KEY_DURATION)
            val durationSec = if (durationUs > 0) durationUs / 1_000_000.0 else -1.0

            Timber.i("Audio extraction complete: %d samples, %.1fs, %.2fMB → %s",
                sampleCount, durationSec, outputFile.length() / (1024.0 * 1024.0), outputFile.name)

            return outputFile

        } catch (e: Exception) {
            Timber.e(e, "Failed to extract audio from %s", sourceFile.name)
            outputFile?.delete()
            return null
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // STEP 3: SPLIT INTO SMALL CHUNKS
    // ══════════════════════════════════════════════════════════════════

    /**
     * Split an audio file into small chunks (~1-2MB each, ~10-15 seconds).
     *
     * Each chunk is a COMPLETE, VALID audio file with proper headers
     * that Whisper can process independently.
     *
     * Strategy:
     *  - Determine audio bitrate from file size and duration
     *  - Calculate chunk duration to target ~1MB per chunk
     *  - Use MediaExtractor + MediaMuxer to create valid chunks
     *  - Each chunk starts at a sync frame for clean decoding
     */
    private fun splitAudioIntoSmallChunks(context: Context, audioFile: File): List<AudioChunk> {
        // If the file is small enough, just return it as a single chunk
        if (audioFile.length() <= GROQ_MAX_FILE_SIZE &&
            audioFile.length() <= 2L * CHUNK_TARGET_SIZE) {
            Timber.i("Audio is small enough (%.2fMB), no chunking needed",
                audioFile.length() / (1024.0 * 1024.0))
            return listOf(AudioChunk(audioFile, 0.0, 0.0))
        }

        val extractor = MediaExtractor()
        val chunks = mutableListOf<AudioChunk>()

        try {
            extractor.setDataSource(audioFile.absolutePath)

            // Find audio track
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                Timber.w("No audio track for chunking")
                return emptyList()
            }

            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return emptyList()

            // Get duration
            val durationUs = try { audioFormat.getLong(MediaFormat.KEY_DURATION) } catch (_: Exception) { -1L }
            if (durationUs <= 0) {
                Timber.w("Cannot determine audio duration for chunking")
                return emptyList()
            }

            val totalDurationSec = durationUs / 1_000_000.0
            val fileSizeBytes = audioFile.length()

            // Calculate chunk duration based on bitrate
            // bitrate = fileSize / duration (in bytes/sec)
            // chunkDuration = targetSize / bitrate
            val bytesPerSec = fileSizeBytes / totalDurationSec
            var chunkDurationUs = ((CHUNK_TARGET_SIZE / bytesPerSec) * 1_000_000.0).toLong()
                .coerceIn(MIN_CHUNK_DURATION_US, CHUNK_MAX_DURATION_US)

            val numChunks = ((durationUs + chunkDurationUs - 1) / chunkDurationUs).toInt().coerceIn(1, 60)

            // Recalculate exact chunk duration based on number of chunks
            chunkDurationUs = (durationUs / numChunks).coerceIn(MIN_CHUNK_DURATION_US, CHUNK_MAX_DURATION_US)

            Timber.i("Chunking plan: %.1fs total, %d chunks, ~%.1fs each, ~%.2fMB target",
                totalDurationSec, numChunks, chunkDurationUs / 1_000_000.0,
                CHUNK_TARGET_SIZE / (1024.0 * 1024.0))

            // Determine output format
            val outputFormat: Int
            val ext: String
            when {
                mime.startsWith("audio/mp4") || mime.startsWith("audio/aac") -> {
                    outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                    ext = ".m4a"
                }
                mime.startsWith("audio/ogg") || mime.startsWith("audio/opus") -> {
                    outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
                    ext = ".ogg"
                }
                else -> {
                    outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                    ext = ".m4a"
                }
            }

            extractor.selectTrack(audioTrackIndex)

            // Read ALL samples into memory for precise splitting
            val allSamples = mutableListOf<SampleData>()
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val inputBuffer = ByteBuffer.allocate(256 * 1024)
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

            // Create chunks
            for (chunkIndex in 0 until numChunks) {
                val chunkStartUs = chunkIndex * chunkDurationUs
                val chunkEndUs = (chunkIndex + 1) * chunkDurationUs

                val chunkSamples = allSamples.filter {
                    it.presentationTimeUs >= chunkStartUs && it.presentationTimeUs < chunkEndUs
                }

                if (chunkSamples.isEmpty()) continue

                val chunkFile = File.createTempFile("looplingo_chunk_${chunkIndex}_", ext, context.cacheDir)
                var muxer: MediaMuxer? = null

                try {
                    muxer = MediaMuxer(chunkFile.absolutePath, outputFormat)
                    val muxerTrackIndex = muxer.addTrack(audioFormat!!)
                    muxer.start()

                    val bufferInfo = MediaCodec.BufferInfo()

                    for (sample in chunkSamples) {
                        val buf = ByteBuffer.allocate(sample.data.size)
                        buf.put(sample.data)
                        buf.flip()

                        bufferInfo.offset = 0
                        bufferInfo.size = sample.data.size
                        bufferInfo.flags = sample.flags
                        // Timestamps relative to chunk start
                        bufferInfo.presentationTimeUs = sample.presentationTimeUs - chunkStartUs

                        muxer.writeSampleData(muxerTrackIndex, buf, bufferInfo)
                    }

                    muxer.stop()

                    val startTimeSec = chunkStartUs / 1_000_000.0
                    val chunkDurationSec = chunkSamples.last().presentationTimeUs / 1_000_000.0 -
                        chunkSamples.first().presentationTimeUs / 1_000_000.0

                    chunks.add(AudioChunk(chunkFile, startTimeSec, chunkDurationSec))

                    Timber.d("Chunk %d/%d: %d samples, %.2fMB, %.1fs-%.1fs",
                        chunkIndex + 1, numChunks, chunkSamples.size,
                        chunkFile.length() / (1024.0 * 1024.0),
                        startTimeSec, startTimeSec + chunkDurationSec)

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

    // ══════════════════════════════════════════════════════════════════
    // STEP 4: SEND CHUNKS TO WHISPER
    // ══════════════════════════════════════════════════════════════════

    /**
     * Transcribe each chunk and merge results with proper time offsets.
     */
    private suspend fun transcribeChunks(
        apiKey: String,
        chunks: List<AudioChunk>,
        onProgress: ProgressCallback?
    ): List<Segment> = withContext(Dispatchers.IO) {
        val allSegments = mutableListOf<Segment>()
        var segmentIdOffset = 0
        var failedChunks = 0

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
                failedChunks++
                Timber.e(e, "Failed to transcribe chunk %d/%d", chunkNum, chunks.size)
            }

            // Clean up chunk file
            if (chunks.size > 1) {
                chunk.file.delete()
            }
        }

        if (failedChunks > 0) {
            Timber.w("%d/%d chunks failed transcription", failedChunks, chunks.size)
        }

        Timber.i("Chunked transcription: %d segments from %d chunks (%d failed)",
            allSegments.size, chunks.size, failedChunks)

        allSegments
    }

    // ══════════════════════════════════════════════════════════════════
    // WHISPER API CALL
    // ══════════════════════════════════════════════════════════════════

    /**
     * Call the Groq Whisper API with a single audio file.
     *
     * The file MUST be audio-only (not a video container).
     * Supported formats: mp3, m4a, wav, ogg, flac, aac
     */
    private fun callWhisperApi(apiKey: String, audioFile: File): List<Segment> {
        val mediaType = guessAudioMediaType(audioFile.name)
        Timber.i("→ Whisper API: %s as %s (%.2fKB)",
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
            Timber.e("← Whisper API error: HTTP %d — %s", response.code, errorDetail)
            throw RuntimeException("Groq API error ${response.code}: ${responseBody?.take(200) ?: "No response"}")
        }

        Timber.d("← Whisper API response (%d bytes): %s", responseBody.length, responseBody.take(300))

        val transcription = parseTranscriptionResponse(responseBody)

        if (transcription.error != null) {
            val errMsg = transcription.error.message ?: "Unknown error"
            Timber.e("← Whisper API error: %s", errMsg)
            throw RuntimeException(errMsg)
        }

        // No segments but has text → create single segment
        if (transcription.segments.isNullOrEmpty() && !transcription.text.isNullOrBlank()) {
            Timber.i("← No segments but got full text: \"%s\"", transcription.text.take(50))
            return listOf(Segment(id = 0, text = transcription.text.trim(), startSec = 0.0, endSec = 0.0))
        }

        val segments = transcription.segments?.map { segJson ->
            Segment(
                id = segJson.id,
                text = segJson.text.trim(),
                startSec = segJson.start,
                endSec = segJson.end
            )
        } ?: emptyList()

        Timber.i("← Whisper API returned %d segments", segments.size)
        return segments
    }

    /**
     * Guess the MIME type for an audio file based on its extension.
     *
     * IMPORTANT: Only audio-only MIME types. Video containers must be
     * extracted to audio-only before calling Whisper.
     */
    private fun guessAudioMediaType(fileName: String): String {
        return when (fileName.substringAfterLast(".", "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"         // Audio-only MP4
            "mp4" -> "audio/mp4"         // If somehow still MP4, try as audio
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            "3gp" -> "audio/3gpp"
            "webm" -> "audio/webm"       // If audio-only WebM
            else -> "application/octet-stream"
        }
    }

    private fun parseTranscriptionResponse(json: String): TranscriptionResponse {
        return try {
            gson.fromJson(json, TranscriptionResponse::class.java)
                ?: TranscriptionResponse(error = ErrorJson(message = "Null response"))
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse Groq response: %s", json.take(200))
            TranscriptionResponse(error = ErrorJson(message = "Parse error: ${e.message}"))
        }
    }
}
