package com.looplingo.horizon.api

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
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
 * Architecture (v1.13.0 — PCM/WAV Pipeline):
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
 *   │  Step 2: DECODE AUDIO TO PCM USING MediaCodec            │
 *   │  • MediaExtractor reads compressed audio from source      │
 *   │  • MediaCodec decodes to raw 16-bit PCM                   │
 *   │  • PCM data is split into ~15-second WAV chunks           │
 *   │  • Each WAV chunk is a complete, valid audio file          │
 *   │  WHY: WAV is universally supported. MediaMuxer-produced   │
 *   │       M4A/OGG files were causing "No speech detected"     │
 *   │       because Whisper couldn't decode them properly.       │
 *   └──────────┬───────────────────────────────────────────────┘
 *              │
 *              ▼
 *   ┌──────────────────────────────────────────────────────────┐
 *   │  Step 3: VALIDATE PCM DATA                               │
 *   │  • Check that PCM contains non-zero samples               │
 *   │  • If all silence → abort early with clear error          │
 *   │  WHY: Catch extraction issues before wasting API calls.    │
 *   └──────────┬───────────────────────────────────────────────┘
 *              │
 *              ▼
 *   ┌──────────────────────────────────────────────────────────┐
 *   │  Step 4: SEND EACH WAV CHUNK TO WHISPER                  │
 *   │  • Content-Type: audio/wav (universally supported)        │
 *   │  • Pre-flight check: send first chunk, verify it works    │
 *   │  • If pre-flight fails → abort with clear error           │
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
        private const val CHUNK_DURATION_SEC = 15.0               // 15 seconds per WAV chunk

        private const val GROQ_API_URL = "https://api.groq.com/openai/v1/audio/transcriptions"

        // WAV format constants
        private const val WAV_BITS_PER_SAMPLE = 16
        private const val WAV_AUDIO_FORMAT_PCM = 1

        // MediaCodec timeout
        private const val CODEC_TIMEOUT_US = 10_000L  // 10ms
        private const val CODEC_DRAIN_TIMEOUT_US = 50_000L  // 50ms for draining

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

    // ══════════════════════════════════════════════════════════════════
    // MAIN ENTRY POINT
    // ══════════════════════════════════════════════════════════════════

    /**
     * Transcribe an audio/video file using the Groq Whisper API.
     *
     * PCM/WAV pipeline (v1.13.0):
     *  1. Resolve file path / content URI → readable temp file
     *  2. Decode audio to PCM using MediaCodec → split into WAV chunks
     *  3. Validate PCM data (check for non-zero samples)
     *  4. Send each WAV chunk to Whisper
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

        Timber.i("═══ STARTING TRANSCRIPTION PIPELINE v1.13.0 ═══")
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

            // ── Step 2: Decode audio to PCM and create WAV chunks ──────
            onProgress?.onProgress("Decoding audio to PCM…")
            Timber.i("Step 2: decoding audio from %s (%.2fMB)", sourceFile.name, sourceSizeMB)

            val chunks = decodeAndCreateWavChunks(context, sourceFile)
            if (chunks.isEmpty()) {
                throw SubtitleException(
                    "Could not decode audio from this file. " +
                    "The format may not be supported. Try a different file."
                )
            }

            Timber.i("Step 2 done: %d WAV chunks created", chunks.size)
            chunks.forEachIndexed { i, chunk ->
                Timber.d("  Chunk %d: %.2fKB, %.1fs-%.1fs",
                    i + 1, chunk.file.length() / 1024.0,
                    chunk.startTimeSec, chunk.startTimeSec + chunk.durationSec)
            }

            // ── Step 3: Validate first chunk's PCM data ────────────────
            onProgress?.onProgress("Validating audio data…")
            val firstChunk = chunks.first()
            val hasNonZeroAudio = validateWavHasAudio(firstChunk.file)
            if (!hasNonZeroAudio) {
                // Clean up chunks before throwing
                chunks.forEach { it.file.delete() }
                throw SubtitleException(
                    "The decoded audio appears to be silent. " +
                    "This usually means the audio extraction failed. " +
                    "Try a different file format (MP3, M4A, or MP4 with clear audio)."
                )
            }
            Timber.i("Step 3 done: PCM validation passed — audio contains non-zero samples")

            // ── Step 4: Pre-flight check — send first chunk to verify ──
            if (chunks.size > 1) {
                onProgress?.onProgress("Pre-flight check: testing chunk 1…")
                Timber.i("Step 4: pre-flight check with chunk 1")

                try {
                    val preflightSegments = callWhisperApi(apiKey, firstChunk.file)
                    if (preflightSegments.isNotEmpty()) {
                        Timber.i("Pre-flight passed: %d segments from chunk 1", preflightSegments.size)
                    } else {
                        // Pre-flight returned empty — Whisper could decode but found no speech
                        // This might be a quiet intro. Don't abort — continue with all chunks.
                        Timber.w("Pre-flight: chunk 1 had no speech. Continuing with all chunks.")
                    }
                } catch (e: Exception) {
                    // Pre-flight API error — might be auth issue
                    chunks.forEach { it.file.delete() }
                    throw SubtitleException(
                        "Whisper API test failed: ${e.message}. " +
                        "Check your API key and try again."
                    )
                }
            }

            // ── Step 5: Send all chunks to Whisper ─────────────────────
            val result = transcribeChunks(apiKey, chunks, onProgress)

            if (result.isEmpty()) {
                throw SubtitleException(
                    "No speech detected in any of the ${chunks.size} audio chunks. " +
                    "Make sure the file contains clear speech and the audio is not silent."
                )
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
    // STEP 2: DECODE AUDIO TO PCM AND CREATE WAV CHUNKS
    // ══════════════════════════════════════════════════════════════════

    /**
     * Decode the audio track from a media file to raw PCM using MediaCodec,
     * then split the PCM into ~15-second WAV chunks.
     *
     * This is the CORE fix for the "No speech detected" issue.
     * Previous versions used MediaMuxer to create M4A/OGG chunks, which
     * produced files that Whisper couldn't properly decode. By using
     * MediaCodec to decode to raw PCM and writing WAV files instead,
     * we guarantee that Whisper receives a universally supported format.
     *
     * Memory-efficient: writes WAV chunks on-the-fly as PCM is decoded,
     * so we never hold more than one chunk's worth of PCM in memory.
     *
     * @return List of AudioChunk objects, each pointing to a valid WAV file
     */
    private fun decodeAndCreateWavChunks(
        context: Context,
        sourceFile: File,
        chunkDurationSec: Double = CHUNK_DURATION_SEC
    ): List<AudioChunk> {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val chunks = mutableListOf<AudioChunk>()

        try {
            extractor.setDataSource(sourceFile.absolutePath)

            // Find the first audio track
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = format
                    Timber.i("Found audio track %d: %s", i, mime)
                    break
                }
            }

            if (audioTrackIndex < 0 || inputFormat == null) {
                Timber.w("No audio track found in: %s", sourceFile.name)
                return emptyList()
            }

            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            extractor.selectTrack(audioTrackIndex)

            // Log source audio info
            val srcSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val srcDurationUs = try { inputFormat.getLong(MediaFormat.KEY_DURATION) } catch (_: Exception) { -1L }
            Timber.i("Source audio: %s, %dHz, %dch, %.1fs",
                mime, srcSampleRate, srcChannels,
                if (srcDurationUs > 0) srcDurationUs / 1_000_000.0 else -1.0)

            // Create decoder — configure with the source format
            // MediaCodec will output 16-bit PCM by default
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            // Track the decoder's output format (may differ from input format)
            var outputSampleRate = srcSampleRate
            var outputChannels = srcChannels
            var outputFormatChecked = false

            // Chunk buffer: collect PCM data until we have enough for one chunk
            val chunkBuffer = mutableListOf<ByteArray>()
            var chunkBufferSize = 0L
            val bytesPerSecond = srcSampleRate.toLong() * srcChannels * 2  // 16-bit = 2 bytes
            val chunkSizeBytes = (bytesPerSecond * chunkDurationSec).toLong()
            var chunkIndex = 0
            var totalPcmBytesDecoded = 0L
            var chunkStartTimeBytes = 0L  // byte offset where current chunk starts

            var inputDone = false
            var outputDone = false
            val bufferInfo = MediaCodec.BufferInfo()

            while (!outputDone) {
                // ── Feed input to decoder ──────────────────────────────
                if (!inputDone) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                // End of input stream
                                decoder.queueInputBuffer(
                                    inputBufferIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                                Timber.d("Input EOS sent to decoder")
                            } else {
                                decoder.queueInputBuffer(
                                    inputBufferIndex, 0, sampleSize,
                                    extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                // ── Read output from decoder ───────────────────────────
                val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)

                if (outputBufferIndex >= 0) {
                    // Check for output format change
                    if (!outputFormatChecked && bufferInfo.presentationTimeUs >= 0) {
                        val outFormat = decoder.outputFormat
                        try {
                            outputSampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        } catch (_: Exception) {}
                        try {
                            outputChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        } catch (_: Exception) {}
                        outputFormatChecked = true
                        Timber.i("Decoder output format: %dHz, %dch", outputSampleRate, outputChannels)
                    }

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }

                    if (bufferInfo.size > 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null) {
                            // Copy PCM data from the output buffer
                            val pcmData = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.get(pcmData)

                            chunkBuffer.add(pcmData)
                            chunkBufferSize += pcmData.size
                            totalPcmBytesDecoded += pcmData.size

                            // If we've collected enough for a chunk, write it
                            if (chunkBufferSize >= chunkSizeBytes) {
                                val startTimeSec = chunkStartTimeBytes.toDouble() / bytesPerSecond
                                val durationSec = chunkBufferSize.toDouble() / bytesPerSecond

                                val chunkFile = writeWavFile(
                                    chunkBuffer, outputSampleRate, outputChannels, context, chunkIndex
                                )
                                chunks.add(AudioChunk(chunkFile, startTimeSec, durationSec))

                                Timber.d("WAV chunk %d: %.1fs-%.1fs, %.2fKB",
                                    chunkIndex + 1, startTimeSec, startTimeSec + durationSec,
                                    chunkFile.length() / 1024.0)

                                chunkStartTimeBytes += chunkBufferSize
                                chunkIndex++
                                chunkBuffer.clear()
                                chunkBufferSize = 0L
                            }
                        }
                    }

                    decoder.releaseOutputBuffer(outputBufferIndex, false)

                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outFormat = decoder.outputFormat
                    try {
                        outputSampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    } catch (_: Exception) {}
                    try {
                        outputChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } catch (_: Exception) {}
                    outputFormatChecked = true
                    Timber.i("Decoder output format changed: %dHz, %dch", outputSampleRate, outputChannels)

                } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // No output available yet — continue feeding input
                }
            }

            // ── Drain remaining PCM data as the last chunk ─────────────
            if (chunkBuffer.isNotEmpty() && chunkBufferSize > 0) {
                val startTimeSec = chunkStartTimeBytes.toDouble() / bytesPerSecond
                val durationSec = chunkBufferSize.toDouble() / bytesPerSecond

                // Only write if the chunk has at least 0.5 seconds of audio
                if (durationSec >= 0.5) {
                    val chunkFile = writeWavFile(
                        chunkBuffer, outputSampleRate, outputChannels, context, chunkIndex
                    )
                    chunks.add(AudioChunk(chunkFile, startTimeSec, durationSec))

                    Timber.d("WAV chunk %d (final): %.1fs-%.1fs, %.2fKB",
                        chunkIndex + 1, startTimeSec, startTimeSec + durationSec,
                        chunkFile.length() / 1024.0)
                } else {
                    Timber.d("Last chunk too short (%.1fs), skipping", durationSec)
                }
            }

            val totalDurationSec = totalPcmBytesDecoded.toDouble() / bytesPerSecond
            Timber.i("Decode complete: %d chunks, %.1fs total, %d bytes PCM",
                chunks.size, totalDurationSec, totalPcmBytesDecoded)

            return chunks

        } catch (e: Exception) {
            Timber.e(e, "Failed to decode audio from %s", sourceFile.name)
            // Clean up any chunks we created before the error
            chunks.forEach { it.file.delete() }
            return emptyList()
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // WAV FILE WRITER
    // ══════════════════════════════════════════════════════════════════

    /**
     * Write a list of PCM byte arrays as a single WAV file.
     *
     * WAV format is the simplest audio container — just a 44-byte header
     * followed by raw PCM samples. No codec, no container overhead,
     * universally supported by every audio decoder including Whisper.
     *
     * WAV header layout (44 bytes):
     *   0-3:   "RIFF"
     *   4-7:   File size - 8 (little-endian)
     *   8-11:  "WAVE"
     *   12-15: "fmt "
     *   16-19: fmt chunk size (16 for PCM)
     *   20-21: Audio format (1 = PCM)
     *   22-23: Number of channels
     *   24-27: Sample rate
     *   28-31: Byte rate (sampleRate * channels * bitsPerSample / 8)
     *   32-33: Block align (channels * bitsPerSample / 8)
     *   34-35: Bits per sample (16)
     *   36-39: "data"
     *   40-43: Data size (little-endian)
     *   44+:   PCM data
     */
    private fun writeWavFile(
        pcmChunks: List<ByteArray>,
        sampleRate: Int,
        channels: Int,
        context: Context,
        chunkIndex: Int
    ): File {
        val dataSize = pcmChunks.sumOf { it.size }
        val byteRate = sampleRate * channels * WAV_BITS_PER_SAMPLE / 8
        val blockAlign = channels * WAV_BITS_PER_SAMPLE / 8
        val fileSize = 36 + dataSize

        val outputFile = File.createTempFile("looplingo_wav_${chunkIndex}_", ".wav", context.cacheDir)

        FileOutputStream(outputFile).use { fos ->
            val out = java.io.BufferedOutputStream(fos, 32 * 1024)

            // RIFF header
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            out.write(intToLittleEndian(fileSize))
            out.write("WAVE".toByteArray(Charsets.US_ASCII))

            // fmt sub-chunk
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            out.write(intToLittleEndian(16))  // fmt chunk size
            out.write(shortToLittleEndian(WAV_AUDIO_FORMAT_PCM))  // PCM format
            out.write(shortToLittleEndian(channels))
            out.write(intToLittleEndian(sampleRate))
            out.write(intToLittleEndian(byteRate))
            out.write(shortToLittleEndian(blockAlign))
            out.write(shortToLittleEndian(WAV_BITS_PER_SAMPLE))

            // data sub-chunk
            out.write("data".toByteArray(Charsets.US_ASCII))
            out.write(intToLittleEndian(dataSize))

            // PCM data
            for (chunk in pcmChunks) {
                out.write(chunk)
            }

            out.flush()
        }

        return outputFile
    }

    /** Convert an Int to a 4-byte little-endian array. */
    private fun intToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    /** Convert a Short value to a 2-byte little-endian array. */
    private fun shortToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // STEP 3: PCM VALIDATION
    // ══════════════════════════════════════════════════════════════════

    /**
     * Validate that a WAV file contains non-zero audio samples.
     *
     * Reads the PCM data section of the WAV file and checks a sampling
     * of samples for non-zero values. If all checked samples are zero,
     * the audio is effectively silent and Whisper will return
     * "No speech detected".
     *
     * This catches cases where the audio extraction/decoding produced
     * silence instead of actual audio content.
     */
    private fun validateWavHasAudio(wavFile: File): Boolean {
        try {
            val data = wavFile.readBytes()

            // WAV header is 44 bytes. PCM data starts at offset 44.
            // But verify by reading the "data" chunk marker.
            if (data.size < 44) {
                Timber.w("WAV file too small: %d bytes", data.size)
                return false
            }

            // Find the "data" chunk — search for the marker
            var dataOffset = -1
            var dataSize = 0
            var offset = 12  // Skip RIFF header
            while (offset < data.size - 8) {
                val marker = String(data, offset, 4, Charsets.US_ASCII)
                val chunkSize = littleEndianToInt(data, offset + 4)
                if (marker == "data") {
                    dataOffset = offset + 8
                    dataSize = chunkSize
                    break
                }
                offset += 8 + chunkSize
                // Chunks must be word-aligned
                if (chunkSize % 2 != 0) offset++
            }

            if (dataOffset < 0 || dataSize <= 0) {
                Timber.w("Could not find data chunk in WAV file")
                return false
            }

            // Check a sampling of PCM samples for non-zero values
            // 16-bit PCM: each sample is 2 bytes (little-endian)
            val pcmEnd = minOf(dataOffset + dataSize, data.size)
            val totalSamples = (pcmEnd - dataOffset) / 2
            val samplesToCheck = minOf(1000, totalSamples)
            val step = if (totalSamples > samplesToCheck) totalSamples / samplesToCheck else 1
            var nonZeroCount = 0

            for (i in 0 until samplesToCheck) {
                val sampleOffset = dataOffset + (i * step * 2)
                if (sampleOffset + 1 >= pcmEnd) break

                // Read 16-bit little-endian sample
                val low = data[sampleOffset].toInt() and 0xFF
                val high = data[sampleOffset + 1].toInt()
                val sample = (high shl 8) or low

                // Check if sample is non-zero (with a small threshold to ignore DC offset)
                if (kotlin.math.abs(sample) > 10) {  // Threshold: ~0.03% of 16-bit range
                    nonZeroCount++
                }
            }

            val nonZeroPercent = if (samplesToCheck > 0) nonZeroCount * 100.0 / samplesToCheck else 0.0
            Timber.i("PCM validation: %.1f%% of samples are non-zero (%d/%d checked)",
                nonZeroPercent, nonZeroCount, samplesToCheck)

            // At least 1% of samples should be non-zero for real speech
            return nonZeroPercent >= 1.0

        } catch (e: Exception) {
            Timber.e(e, "Failed to validate WAV file")
            return false
        }
    }

    /** Read a 4-byte little-endian integer from a byte array. */
    private fun littleEndianToInt(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    // ══════════════════════════════════════════════════════════════════
    // STEP 4: TRANSCRIBE CHUNKS
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
        var emptyChunks = 0  // Chunks that returned no speech

        for ((index, chunk) in chunks.withIndex()) {
            val chunkNum = index + 1
            onProgress?.onProgress("Transcribing chunk $chunkNum/${chunks.size}…")

            try {
                val chunkSegments = callWhisperApi(apiKey, chunk.file)
                val offsetSec = chunk.startTimeSec

                if (chunkSegments.isEmpty()) {
                    emptyChunks++
                    Timber.d("Chunk %d: no speech detected (time %.1fs-%.1fs)",
                        chunkNum, offsetSec, offsetSec + chunk.durationSec)
                } else {
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
                }

            } catch (e: Exception) {
                failedChunks++
                Timber.e(e, "Failed to transcribe chunk %d/%d", chunkNum, chunks.size)
            }

            // Clean up chunk file after processing
            chunk.file.delete()
        }

        if (emptyChunks > 0) {
            Timber.w("%d/%d chunks had no speech (might be silent sections)", emptyChunks, chunks.size)
        }
        if (failedChunks > 0) {
            Timber.w("%d/%d chunks failed with API error", failedChunks, chunks.size)
        }

        Timber.i("Chunked transcription: %d segments from %d chunks (%d empty, %d failed)",
            allSegments.size, chunks.size, emptyChunks, failedChunks)

        allSegments
    }

    // ══════════════════════════════════════════════════════════════════
    // WHISPER API CALL
    // ══════════════════════════════════════════════════════════════════

    /**
     * Call the Groq Whisper API with a single audio file.
     *
     * In v1.13.0, the file is always a WAV file produced by our
     * PCM decoder. WAV is universally supported and doesn't have
     * the container/codec issues that M4A/OGG files had.
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
     * In v1.13.0, most files will be WAV (from our PCM decoder).
     * Other formats are kept as fallbacks for direct sending.
     */
    private fun guessAudioMediaType(fileName: String): String {
        return when (fileName.substringAfterLast(".", "").lowercase()) {
            "wav" -> "audio/wav"             // Primary format in v1.13.0
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "mp4" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            "3gp" -> "audio/3gpp"
            "webm" -> "audio/webm"
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
