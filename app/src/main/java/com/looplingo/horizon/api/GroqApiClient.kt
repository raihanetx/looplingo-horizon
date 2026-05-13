package com.looplingo.horizon.api

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Environment
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
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Client for the Groq Whisper API — speech-to-text transcription.
 *
 * Architecture (v1.14.0 — Probe-First + Multi-Strategy):
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
 *   │  • Log file size, MIME type, first bytes for diagnostics  │
 *   └──────────┬───────────────────────────────────────────────┘
 *              │
 *              ▼
 *   ┌──────────────────────────────────────────────────────────┐
 *   │  Step 2: PROBE — Send raw source file directly            │
 *   │  • If file is under 25MB, send it AS-IS to Whisper        │
 *   │  • This tests: API key works + file is valid audio/video   │
 *   │  • If probe succeeds → we know the source file IS good     │
 *   │  • If probe fails → the problem is the source file/API     │
 *   │  WHY: Before chunking 100+ times, verify the basics work.  │
 *   └──────────┬───────────────────────────────────────────────┘
 *              │
 *              ▼
 *   ┌──────────────────────────────────────────────────────────┐
 *   │  Step 3: If probe worked → chunk and transcribe all       │
 *   │  If probe failed → try WAV extraction as fallback          │
 *   │  • Decode audio via MediaCodec → PCM → WAV chunks          │
 *   │  • Send first WAV chunk as probe                          │
 *   │  • If WAV probe works → continue with rest of chunks      │
 *   │  • Limit: max 30 chunks (not 107!)                        │
 *   └──────────┬───────────────────────────────────────────────┘
 *              │
 *              ▼
 *   ┌──────────────────────────────────────────────────────────┐
 *   │  Step 4: SAVE DEBUG WAV to Downloads (first chunk only)   │
 *   │  • User can play the WAV file to verify it's real audio   │
 *   │  • Helps diagnose: "Is the extraction actually working?"   │
 *   └────────────────┬─────────────────────────────────────────┘
 *                    │
 *                    ▼
 *   ┌──────────────────────────────────────────────────────────┐
 *   │  Step 5: RETURN MERGED SEGMENTS                           │
 *   │  • All segments have absolute timestamps from original     │
 *   └──────────────────────────────────────────────────────────┘
 */
class GroqApiClient {

    companion object {
        // Groq Whisper API limits
        private const val GROQ_MAX_FILE_SIZE = 25L * 1024 * 1024  // 25MB hard limit
        private const val CHUNK_DURATION_SEC = 15.0               // 15 seconds per chunk
        private const val MAX_CHUNKS = 30                         // Don't create 107 chunks!

        private const val GROQ_API_URL = "https://api.groq.com/openai/v1/audio/transcriptions"

        // WAV format constants
        private const val WAV_BITS_PER_SAMPLE = 16
        private const val WAV_AUDIO_FORMAT_PCM = 1

        // MediaCodec timeout
        private const val CODEC_TIMEOUT_US = 10_000L  // 10ms
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
     * v1.14.0 — Probe-First strategy:
     *  1. Resolve file
     *  2. PROBE: Send raw source file directly (if under 25MB)
     *  3. If probe works → done! If file is large → chunk the rest
     *  4. If probe fails → try WAV extraction fallback
     *  5. Save debug WAV for user verification
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

        Timber.i("═══ STARTING TRANSCRIPTION PIPELINE v1.14.0 ═══")
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
            Timber.i("Step 1: source file = %s (%.2fMB)", sourceFile.name, sourceSizeMB)

            // Log file diagnostics
            logFileDiagnostics(sourceFile)

            // ── Step 2: PROBE — Try sending raw source file directly ───
            if (sourceFile.length() <= GROQ_MAX_FILE_SIZE) {
                onProgress?.onProgress("Testing: sending file directly to Whisper…")
                Timber.i("Step 2: PROBE — sending raw file directly (%.2fMB)", sourceSizeMB)

                try {
                    val probeResult = callWhisperApi(apiKey, sourceFile)
                    if (probeResult.isNotEmpty()) {
                        Timber.i("═══ PROBE SUCCESS: %d segments from raw file! ═══", probeResult.size)
                        return@withContext probeResult
                    } else {
                        Timber.w("Probe: raw file was accepted by Whisper but no speech detected")
                        // File was valid but no speech — this could mean:
                        // 1. The file genuinely has no speech
                        // 2. The audio is too quiet
                        // Try chunking to see if later parts have speech
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Probe: raw file failed — will try WAV extraction fallback")
                    // Don't throw yet — try WAV extraction as fallback
                }
            } else {
                Timber.i("Step 2: File too large (%.2fMB) for direct send, will chunk", sourceSizeMB)
            }

            // ── Step 3: WAV extraction fallback ────────────────────────
            onProgress?.onProgress("Extracting audio to WAV format…")
            Timber.i("Step 3: Decoding audio to PCM/WAV chunks")

            val chunks = decodeAndCreateWavChunks(context, sourceFile)
            if (chunks.isEmpty()) {
                throw SubtitleException(
                    "Could not decode audio from this file. " +
                    "The audio format may not be supported by your device. " +
                    "Try an MP3, M4A, or MP4 file."
                )
            }

            // Limit chunks to MAX_CHUNKS
            val limitedChunks = if (chunks.size > MAX_CHUNKS) {
                Timber.w("Too many chunks (%d), limiting to %d", chunks.size, MAX_CHUNKS)
                chunks.dropLast(chunks.size - MAX_CHUNKS).also { dropped ->
                    // Clean up dropped chunks
                    chunks.drop(MAX_CHUNKS).forEach { it.file.delete() }
                }
            } else {
                chunks
            }

            Timber.i("Created %d WAV chunks", limitedChunks.size)
            limitedChunks.forEachIndexed { i, chunk ->
                Timber.d("  Chunk %d: %.2fKB, %.1fs-%.1fs",
                    i + 1, chunk.file.length() / 1024.0,
                    chunk.startTimeSec, chunk.startTimeSec + chunk.durationSec)
            }

            // ── Step 3a: Validate PCM data ─────────────────────────────
            val firstChunk = limitedChunks.first()
            val pcmStats = analyzeWavPcm(firstChunk.file)
            Timber.i("PCM stats for chunk 1: %s", pcmStats.summary())

            if (!pcmStats.hasAudio) {
                // Clean up and try raw file as last resort
                limitedChunks.forEach { it.file.delete() }

                if (sourceFile.length() <= GROQ_MAX_FILE_SIZE) {
                    Timber.w("PCM is silent! Trying raw source file as absolute last resort")
                    onProgress?.onProgress("PCM was silent — trying raw file as last resort…")
                    try {
                        val lastResort = callWhisperApi(apiKey, sourceFile)
                        if (lastResort.isNotEmpty()) return@withContext lastResort
                    } catch (_: Exception) {}
                }

                throw SubtitleException(
                    "The decoded audio appears to be completely SILENT. " +
                    "This means the audio extraction is not working for this file. " +
                    "PCM analysis: ${pcmStats.summary()}. " +
                    "Try a different file format (MP3 or M4A work best)."
                )
            }

            // ── Step 3b: Probe first WAV chunk ─────────────────────────
            if (limitedChunks.size > 2) {
                onProgress?.onProgress("Testing WAV chunk 1…")
                Timber.i("Step 3b: Probe — sending first WAV chunk to Whisper")

                try {
                    val wavProbeResult = callWhisperApi(apiKey, firstChunk.file)
                    Timber.i("WAV probe result: %d segments", wavProbeResult.size)
                    // Even if 0 segments, the API call worked — continue with all chunks
                } catch (e: Exception) {
                    // WAV probe failed — this is serious
                    limitedChunks.forEach { it.file.delete() }
                    throw SubtitleException(
                        "WAV chunk was rejected by Whisper: ${e.message}. " +
                        "The audio extraction produced invalid data. " +
                        "PCM stats: ${pcmStats.summary()}"
                    )
                }
            }

            // ── Step 4: Save debug WAV for user verification ───────────
            saveDebugWavToDownloads(context, firstChunk.file)

            // ── Step 5: Transcribe all chunks ──────────────────────────
            val result = transcribeChunks(apiKey, limitedChunks, onProgress)

            if (result.isEmpty()) {
                throw SubtitleException(
                    "No speech detected in any chunk. " +
                    "The audio plays fine in the app but Whisper can't transcribe it. " +
                    "PCM analysis: ${pcmStats.summary()}. " +
                    "A debug WAV was saved to your Downloads folder — " +
                    "try playing it to verify the audio extraction worked."
                )
            }

            Timber.i("═══ TRANSCRIPTION COMPLETE: %d segments ═══", result.size)
            result

        } finally {
            cleanupSource()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // FILE DIAGNOSTICS
    // ══════════════════════════════════════════════════════════════════

    /**
     * Log diagnostic info about the source file.
     * Helps debug "No speech detected" by showing what we're working with.
     */
    private fun logFileDiagnostics(file: File) {
        Timber.i("═══ FILE DIAGNOSTICS ═══")
        Timber.i("  Name: %s", file.name)
        Timber.i("  Size: %d bytes (%.2fMB)", file.length(), file.length() / (1024.0 * 1024.0))
        Timber.i("  Extension: %s", file.extension)
        Timber.i("  Can read: %s", file.canRead())

        // Read first 16 bytes as hex
        try {
            val bytes = file.inputStream().use { it.readNBytes(16) }
            val hex = bytes.joinToString(" ") { "%02x".format(it) }
            Timber.i("  First 16 bytes: %s", hex)

            // Identify file format from magic bytes
            val format = when {
                bytes.size >= 4 && String(bytes, 0, 4) == "RIFF" -> "WAV (RIFF)"
                bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFB.toByte() -> "MP3"
                bytes.size >= 4 && String(bytes, 0, 4) == "OggS" -> "OGG"
                bytes.size >= 4 && String(bytes, 0, 3) == "ID3" -> "MP3 (ID3)"
                bytes.size >= 8 && String(bytes, 4, 4) == "ftyp" -> "MP4/M4A (ftyp)"
                bytes.size >= 4 && String(bytes, 0, 4) == "fLaC" -> "FLAC"
                bytes.size >= 4 && bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() -> "WebM/MKV"
                else -> "Unknown format"
            }
            Timber.i("  Detected format: %s", format)
        } catch (e: Exception) {
            Timber.w(e, "  Could not read first bytes")
        }

        // Try to get audio track info via MediaExtractor
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            Timber.i("  Tracks: %d", extractor.trackCount)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: "unknown"
                val sampleRate = try { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) { -1 }
                val channels = try { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) { -1 }
                val duration = try { format.getLong(MediaFormat.KEY_DURATION) / 1000 } catch (_: Exception) { -1L }
                val bitrate = try { format.getInteger(MediaFormat.KEY_BIT_RATE) } catch (_: Exception) { -1 }
                Timber.i("    Track %d: %s, %dHz, %dch, %dms, %dbps",
                    i, mime, sampleRate, channels, duration, bitrate)
            }
            extractor.release()
        } catch (e: Exception) {
            Timber.w(e, "  Could not probe with MediaExtractor")
        }
        Timber.i("═══ END FILE DIAGNOSTICS ═══")
    }

    // ══════════════════════════════════════════════════════════════════
    // PCM ANALYSIS — Detailed stats for debugging
    // ══════════════════════════════════════════════════════════════════

    /**
     * Result of PCM analysis — contains detailed statistics
     * about the audio data in a WAV file.
     */
    private data class PcmAnalysisResult(
        val fileBytes: Long,
        val pcmDataBytes: Int,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val totalSamples: Int,
        val minSample: Int,
        val maxSample: Int,
        val meanAbsSample: Double,
        val nonZeroPercent: Double,
        val hasAudio: Boolean
    ) {
        fun summary(): String {
            return "file=${fileBytes}B, pcm=${pcmDataBytes}B, " +
                "${sampleRate}Hz, ${channels}ch, ${bitsPerSample}bit, " +
                "samples=$totalSamples, " +
                "range=[${minSample}..${maxSample}], " +
                "meanAbs=${"%.1f".format(meanAbsSample)}, " +
                "nonZero=${"%.1f".format(nonZeroPercent)}%, " +
                "hasAudio=$hasAudio"
        }
    }

    /**
     * Analyze the PCM data in a WAV file and return detailed statistics.
     * This is crucial for diagnosing "No speech detected" — if the PCM
     * stats show all zeros, the extraction is producing silence.
     */
    private fun analyzeWavPcm(wavFile: File): PcmAnalysisResult {
        try {
            val data = wavFile.readBytes()
            if (data.size < 44) {
                return PcmAnalysisResult(
                    fileBytes = wavFile.length(), pcmDataBytes = 0,
                    sampleRate = 0, channels = 0, bitsPerSample = 0,
                    totalSamples = 0, minSample = 0, maxSample = 0,
                    meanAbsSample = 0.0, nonZeroPercent = 0.0, hasAudio = false
                )
            }

            // Parse WAV header
            val channels = littleEndianToShort(data, 22)
            val sampleRate = littleEndianToInt(data, 24)
            val bitsPerSample = littleEndianToShort(data, 34)

            // Find "data" chunk
            var dataOffset = -1
            var dataSize = 0
            var offset = 12
            while (offset < data.size - 8) {
                val marker = try { String(data, offset, 4, Charsets.US_ASCII) } catch (_: Exception) { break }
                val chunkSize = littleEndianToInt(data, offset + 4)
                if (marker == "data") {
                    dataOffset = offset + 8
                    dataSize = chunkSize
                    break
                }
                offset += 8 + chunkSize
                if (chunkSize % 2 != 0) offset++
            }

            if (dataOffset < 0 || dataSize <= 0) {
                Timber.w("WAV has no data chunk! Header: %s",
                    data.take(44).joinToString(" ") { "%02x".format(it) })
                return PcmAnalysisResult(
                    fileBytes = wavFile.length(), pcmDataBytes = 0,
                    sampleRate = sampleRate, channels = channels, bitsPerSample = bitsPerSample,
                    totalSamples = 0, minSample = 0, maxSample = 0,
                    meanAbsSample = 0.0, nonZeroPercent = 0.0, hasAudio = false
                )
            }

            val pcmEnd = minOf(dataOffset + dataSize, data.size)
            val bytesPerSample = bitsPerSample / 8
            val totalSamples = (pcmEnd - dataOffset) / bytesPerSample

            // Analyze samples — check up to 5000 samples spread across the data
            val samplesToCheck = minOf(5000, totalSamples)
            val step = if (totalSamples > samplesToCheck) totalSamples / samplesToCheck else 1

            var minSample = Int.MAX_VALUE
            var maxSample = Int.MIN_VALUE
            var sumAbs = 0L
            var nonZeroCount = 0

            for (i in 0 until samplesToCheck) {
                val sampleOffset = dataOffset + (i * step * bytesPerSample)
                if (sampleOffset + bytesPerSample > pcmEnd) break

                val sample = if (bytesPerSample == 2) {
                    val low = data[sampleOffset].toInt() and 0xFF
                    val high = data[sampleOffset + 1].toInt()
                    (high shl 8) or low
                } else {
                    data[sampleOffset].toInt() and 0xFF
                }

                if (sample < minSample) minSample = sample
                if (sample > maxSample) maxSample = sample
                sumAbs += kotlin.math.abs(sample)
                if (kotlin.math.abs(sample) > 10) nonZeroCount++
            }

            val meanAbs = if (samplesToCheck > 0) sumAbs.toDouble() / samplesToCheck else 0.0
            val nonZeroPct = if (samplesToCheck > 0) nonZeroCount * 100.0 / samplesToCheck else 0.0

            // Audio is present if: >1% non-zero AND mean absolute value > 50
            // (50 out of 32768 is ~0.15% of full range — very quiet but present)
            val hasAudio = nonZeroPct >= 1.0 && meanAbs >= 50.0

            return PcmAnalysisResult(
                fileBytes = wavFile.length(),
                pcmDataBytes = dataSize,
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = bitsPerSample,
                totalSamples = totalSamples,
                minSample = if (minSample == Int.MAX_VALUE) 0 else minSample,
                maxSample = if (maxSample == Int.MIN_VALUE) 0 else maxSample,
                meanAbsSample = meanAbs,
                nonZeroPercent = nonZeroPct,
                hasAudio = hasAudio
            )

        } catch (e: Exception) {
            Timber.e(e, "Failed to analyze WAV PCM")
            return PcmAnalysisResult(
                fileBytes = wavFile.length(), pcmDataBytes = 0,
                sampleRate = 0, channels = 0, bitsPerSample = 0,
                totalSamples = 0, minSample = 0, maxSample = 0,
                meanAbsSample = 0.0, nonZeroPercent = 0.0, hasAudio = false
            )
        }
    }

    /** Read a 2-byte little-endian short from a byte array. */
    private fun littleEndianToShort(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    // ══════════════════════════════════════════════════════════════════
    // SAVE DEBUG WAV TO DOWNLOADS
    // ══════════════════════════════════════════════════════════════════

    /**
     * Save the first WAV chunk to the Downloads folder so the user
     * can play it and verify the audio extraction is working.
     */
    private fun saveDebugWavToDownloads(context: Context, wavFile: File) {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            val debugFile = File(downloadsDir, "looplingo_debug_chunk1.wav")
            wavFile.inputStream().use { input ->
                FileOutputStream(debugFile).use { output ->
                    input.copyTo(output)
                }
            }
            Timber.i("Debug WAV saved to: %s (%.2fKB)", debugFile.absolutePath, debugFile.length() / 1024.0)
        } catch (e: Exception) {
            Timber.w(e, "Could not save debug WAV to Downloads")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // STEP 1: RESOLVE FILE PATH
    // ══════════════════════════════════════════════════════════════════

    private fun resolveToReadableFile(context: Context, filePath: String): Pair<File, () -> Unit> {
        if (filePath.startsWith("content://")) {
            Timber.i("Resolving content:// URI to temp file")
            val tempFile = copyContentUriToTempFile(context, filePath)
            return Pair(tempFile) { tempFile.delete() }
        }

        val directFile = File(filePath)
        if (directFile.exists() && directFile.canRead()) {
            Timber.i("Using file path directly: %s", filePath)
            return Pair(directFile) { /* no cleanup */ }
        }

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

        Timber.w("Could not resolve file: %s", filePath)
        return Pair(directFile) { /* nothing to clean up */ }
    }

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

    private fun resolvePathToContentUri(context: Context, filePath: String): String? {
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
    // STEP 3: DECODE AUDIO TO PCM AND CREATE WAV CHUNKS
    // ══════════════════════════════════════════════════════════════════

    /**
     * Decode the audio track from a media file to raw PCM using MediaCodec,
     * then split the PCM into ~15-second WAV chunks.
     *
     * Memory-efficient: writes WAV chunks on-the-fly as PCM is decoded.
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

            val srcSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val srcDurationUs = try { inputFormat.getLong(MediaFormat.KEY_DURATION) } catch (_: Exception) { -1L }
            Timber.i("Source audio: %s, %dHz, %dch, %.1fs",
                mime, srcSampleRate, srcChannels,
                if (srcDurationUs > 0) srcDurationUs / 1_000_000.0 else -1.0)

            // Create and configure decoder
            decoder = MediaCodec.createDecoderByType(mime)

            // Create output format with explicit PCM configuration
            // This ensures MediaCodec outputs 16-bit PCM
            val outputFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_RAW, srcSampleRate, srcChannels
            )
            outputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, MediaFormat.ENCODING_PCM_16BIT)

            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            var outputSampleRate = srcSampleRate
            var outputChannels = srcChannels
            var outputFormatChecked = false

            val chunkBuffer = mutableListOf<ByteArray>()
            var chunkBufferSize = 0L
            val bytesPerSecond = srcSampleRate.toLong() * srcChannels * 2  // 16-bit = 2 bytes
            val chunkSizeBytes = (bytesPerSecond * chunkDurationSec).toLong()
            var chunkIndex = 0
            var totalPcmBytesDecoded = 0L
            var chunkStartTimeBytes = 0L

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
                                decoder.queueInputBuffer(
                                    inputBufferIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
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

                when {
                    outputBufferIndex >= 0 -> {
                        // Check output format on first real output
                        if (!outputFormatChecked) {
                            val outFormat = decoder.outputFormat
                            try { outputSampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) {}
                            try { outputChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) {}
                            try {
                                val pcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                Timber.i("Decoder PCM encoding: %d (2=16bit, 3=8bit, 4=float)", pcmEncoding)
                            } catch (_: Exception) {}
                            outputFormatChecked = true
                            Timber.i("Decoder output: %dHz, %dch", outputSampleRate, outputChannels)
                        }

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }

                        if (bufferInfo.size > 0) {
                            val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                            if (outputBuffer != null) {
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

                                    chunkStartTimeBytes += chunkBufferSize
                                    chunkIndex++
                                    chunkBuffer.clear()
                                    chunkBufferSize = 0L

                                    // Stop if we've reached max chunks
                                    if (chunks.size >= MAX_CHUNKS) {
                                        Timber.i("Reached max chunks (%d), stopping decode", MAX_CHUNKS)
                                        break
                                    }
                                }
                            }
                        }

                        decoder.releaseOutputBuffer(outputBufferIndex, false)

                    }
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = decoder.outputFormat
                        try { outputSampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) {}
                        try { outputChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) {}
                        try {
                            val pcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            Timber.i("Output format changed — PCM encoding: %d", pcmEncoding)
                        } catch (_: Exception) {}
                        outputFormatChecked = true
                        Timber.i("Output format changed: %dHz, %dch", outputSampleRate, outputChannels)
                    }
                    outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* wait */ }
                }
            }

            // ── Drain remaining PCM as the last chunk ──────────────────
            if (chunkBuffer.isNotEmpty() && chunkBufferSize > 0) {
                val startTimeSec = chunkStartTimeBytes.toDouble() / bytesPerSecond
                val durationSec = chunkBufferSize.toDouble() / bytesPerSecond

                if (durationSec >= 0.5 && chunks.size < MAX_CHUNKS) {
                    val chunkFile = writeWavFile(
                        chunkBuffer, outputSampleRate, outputChannels, context, chunkIndex
                    )
                    chunks.add(AudioChunk(chunkFile, startTimeSec, durationSec))
                }
            }

            val totalDurationSec = totalPcmBytesDecoded.toDouble() / bytesPerSecond
            Timber.i("Decode complete: %d chunks, %.1fs total, %d bytes PCM",
                chunks.size, totalDurationSec, totalPcmBytesDecoded)

            return chunks

        } catch (e: Exception) {
            Timber.e(e, "Failed to decode audio from %s", sourceFile.name)
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
        val fileSize = 36 + dataSize  // RIFF chunk size = total - 8

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

    private fun intToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun shortToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
    }

    private fun littleEndianToInt(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    // ══════════════════════════════════════════════════════════════════
    // STEP 5: TRANSCRIBE CHUNKS
    // ══════════════════════════════════════════════════════════════════

    private suspend fun transcribeChunks(
        apiKey: String,
        chunks: List<AudioChunk>,
        onProgress: ProgressCallback?
    ): List<Segment> = withContext(Dispatchers.IO) {
        val allSegments = mutableListOf<Segment>()
        var segmentIdOffset = 0
        var failedChunks = 0
        var emptyChunks = 0

        for ((index, chunk) in chunks.withIndex()) {
            val chunkNum = index + 1
            onProgress?.onProgress("Transcribing chunk $chunkNum/${chunks.size}…")

            try {
                val chunkSegments = callWhisperApi(apiKey, chunk.file)
                val offsetSec = chunk.startTimeSec

                if (chunkSegments.isEmpty()) {
                    emptyChunks++
                    Timber.d("Chunk %d/%d: no speech (time %.1fs-%.1fs)",
                        chunkNum, chunks.size, offsetSec, offsetSec + chunk.durationSec)
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
                    Timber.d("Chunk %d/%d: %d segments (offset +%.1fs)",
                        chunkNum, chunks.size, chunkSegments.size, offsetSec)
                }

            } catch (e: Exception) {
                failedChunks++
                Timber.e(e, "Failed to transcribe chunk %d/%d", chunkNum, chunks.size)
            }

            // Clean up chunk file after processing (except first chunk if debugging)
            chunk.file.delete()
        }

        Timber.i("Transcription: %d segments from %d chunks (%d empty, %d failed)",
            allSegments.size, chunks.size, emptyChunks, failedChunks)

        allSegments
    }

    // ══════════════════════════════════════════════════════════════════
    // WHISPER API CALL
    // ══════════════════════════════════════════════════════════════════

    /**
     * Call the Groq Whisper API with a single audio file.
     * Logs full request and response for debugging.
     */
    private fun callWhisperApi(apiKey: String, audioFile: File): List<Segment> {
        val mediaType = guessAudioMediaType(audioFile.name)
        val fileSizeKB = audioFile.length() / 1024.0

        Timber.i("→ Whisper API: %s (%.2fKB) as %s", audioFile.name, fileSizeKB, mediaType)

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

        Timber.d("← Whisper API response: HTTP %d, %d bytes", response.code, responseBody.length)
        Timber.d("← Response body (first 500 chars): %s", responseBody.take(500))

        val transcription = parseTranscriptionResponse(responseBody)

        if (transcription.error != null) {
            val errMsg = transcription.error.message ?: "Unknown error"
            Timber.e("← Whisper API error in response: %s", errMsg)
            throw RuntimeException(errMsg)
        }

        // No segments but has text → create single segment
        if (transcription.segments.isNullOrEmpty() && !transcription.text.isNullOrBlank()) {
            Timber.i("← No segments but got full text: \"%s\"", transcription.text.take(80))
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
        if (segments.isNotEmpty()) {
            segments.forEach { seg ->
                Timber.d("  Segment %d: [%.1f-%.1f] \"%s\"", seg.id, seg.startSec, seg.endSec, seg.text.take(50))
            }
        }

        return segments
    }

    /**
     * Guess the MIME type based on file extension.
     * For WAV files, use audio/wav. For raw source files, use their native type.
     */
    private fun guessAudioMediaType(fileName: String): String {
        return when (fileName.substringAfterLast(".", "").lowercase()) {
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "mp4" -> "video/mp4"           // Video MP4 — Whisper accepts these
            "ogg" -> "audio/ogg"
            "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            "3gp" -> "video/3gpp"
            "webm" -> "audio/webm"
            "mkv" -> "video/x-matroska"
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
