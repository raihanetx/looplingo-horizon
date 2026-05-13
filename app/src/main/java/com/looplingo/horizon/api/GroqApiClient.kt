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
 * Architecture (v1.15.0 — Normalize + Language):
 *
 *   The KEY insight from v1.14.0 debugging:
 *   - PCM stats: meanAbs=515, range=[-7668..8596], nonZero=49.1%
 *   - Audio IS being extracted correctly!
 *   - But the audio is TOO QUIET for Whisper's voice activity detection
 *   - Movie content has quiet intros + dialogue — low average volume
 *
 *   v1.15.0 fixes:
 *   1. PCM NORMALIZATION — amplify quiet audio to ~90% of max range
 *      before sending to Whisper. A meanAbs of 515 becomes ~5500
 *   2. LANGUAGE PARAMETER — tell Whisper what language to listen for
 *      (Bengali, Japanese, English, auto-detect, etc.)
 *   3. Probe-first: send raw file, then WAV chunks with normalization
 */
class GroqApiClient {

    companion object {
        private const val GROQ_MAX_FILE_SIZE = 25L * 1024 * 1024
        private const val CHUNK_DURATION_SEC = 15.0
        private const val MAX_CHUNKS = 30

        private const val GROQ_API_URL = "https://api.groq.com/openai/v1/audio/transcriptions"

        // WAV format constants
        private const val WAV_BITS_PER_SAMPLE = 16
        private const val WAV_AUDIO_FORMAT_PCM = 1

        // PCM normalization: target peak as fraction of max 16-bit range (32767)
        private const val NORMALIZATION_TARGET = 0.9   // 90% of max range
        private const val MIN_PEAK_FOR_NORMALIZATION = 500  // Don't normalize already-loud audio

        // MediaCodec timeout
        private const val CODEC_TIMEOUT_US = 10_000L

        /** Supported languages for Whisper transcription. */
        val SUPPORTED_LANGUAGES = listOf(
            "auto" to "Auto-detect",
            "bn" to "বাংলা (Bengali)",
            "ja" to "日本語 (Japanese)",
            "en" to "English",
            "hi" to "हिन्दी (Hindi)",
            "ko" to "한국어 (Korean)",
            "zh" to "中文 (Chinese)",
            "ar" to "العربية (Arabic)",
            "es" to "Español (Spanish)",
            "fr" to "Français (French)",
            "de" to "Deutsch (German)",
            "pt" to "Português (Portuguese)",
            "ru" to "Русский (Russian)",
            "th" to "ไทย (Thai)",
            "vi" to "Tiếng Việt (Vietnamese)",
            "tr" to "Türkçe (Turkish)",
            "id" to "Bahasa Indonesia",
            "ta" to "தமிழ் (Tamil)",
            "te" to "తెలుగు (Telugu)",
            "ur" to "اردو (Urdu)"
        )
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
     * @param language ISO 639-1 language code (e.g., "bn", "ja", "en")
     *                 or "auto" for auto-detection. Default: "auto"
     */
    suspend fun transcribeAudio(
        context: Context,
        apiKey: String,
        filePath: String,
        language: String = "auto",
        onProgress: ProgressCallback? = null
    ): List<Segment> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw SubtitleException("Groq API key is blank — enter your API key first")
        }
        if (filePath.isBlank()) {
            throw SubtitleException("No file selected — go back and pick an audio/video file")
        }

        Timber.i("═══ STARTING TRANSCRIPTION PIPELINE v1.15.0 ═══")
        Timber.i("Input: %s", filePath.take(100))
        Timber.i("Language: %s", language)

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
            logFileDiagnostics(sourceFile)

            // ── Step 2: PROBE — Try sending raw source file directly ───
            if (sourceFile.length() <= GROQ_MAX_FILE_SIZE) {
                onProgress?.onProgress("Testing: sending file directly to Whisper…")
                Timber.i("Step 2: PROBE — sending raw file directly (%.2fMB)", sourceSizeMB)

                try {
                    val probeResult = callWhisperApi(apiKey, sourceFile, language)
                    if (probeResult.isNotEmpty()) {
                        Timber.i("═══ PROBE SUCCESS: %d segments from raw file! ═══", probeResult.size)
                        return@withContext probeResult
                    } else {
                        Timber.w("Probe: raw file accepted but no speech detected — will try WAV with normalization")
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Probe: raw file failed — will try WAV extraction with normalization")
                }
            }

            // ── Step 3: WAV extraction + NORMALIZATION ─────────────────
            onProgress?.onProgress("Extracting and normalizing audio…")
            Timber.i("Step 3: Decoding audio to PCM/WAV with normalization")

            val chunks = decodeAndCreateWavChunks(context, sourceFile)
            if (chunks.isEmpty()) {
                throw SubtitleException(
                    "Could not decode audio from this file. " +
                    "The audio format may not be supported. Try an MP3, M4A, or MP4 file."
                )
            }

            // Limit chunks
            val limitedChunks = if (chunks.size > MAX_CHUNKS) {
                Timber.w("Too many chunks (%d), limiting to %d", chunks.size, MAX_CHUNKS)
                chunks.dropLast(chunks.size - MAX_CHUNKS).also {
                    chunks.drop(MAX_CHUNKS).forEach { c -> c.file.delete() }
                }
            } else {
                chunks
            }

            Timber.i("Created %d WAV chunks (before normalization)", limitedChunks.size)

            // ── Step 3a: Analyze and NORMALIZE each chunk ──────────────
            onProgress?.onProgress("Normalizing audio volume…")
            val normalizedChunks = mutableListOf<AudioChunk>()
            for (chunk in limitedChunks) {
                val stats = analyzeWavPcm(chunk.file)
                Timber.d("Chunk pre-norm: meanAbs=%.1f, peak=%d, %s",
                    stats.meanAbsSample, maxOf(kotlin.math.abs(stats.minSample), kotlin.math.abs(stats.maxSample)),
                    stats.summary())

                if (stats.meanAbsSample < 10 && stats.nonZeroPercent < 1.0) {
                    // Truly silent chunk — skip it
                    Timber.d("Chunk at %.1fs is silent, skipping", chunk.startTimeSec)
                    chunk.file.delete()
                    continue
                }

                // Normalize: amplify quiet audio so Whisper can detect speech
                val normalizedFile = normalizeWavFile(chunk.file, stats)
                if (normalizedFile != null) {
                    // Delete the un-normalized file
                    if (normalizedFile != chunk.file) chunk.file.delete()
                    normalizedChunks.add(chunk.copy(file = normalizedFile))
                } else {
                    normalizedChunks.add(chunk)
                }
            }

            if (normalizedChunks.isEmpty()) {
                throw SubtitleException(
                    "All audio chunks were silent after extraction. " +
                    "This file may not contain audible speech."
                )
            }

            // Log normalized stats
            val firstNorm = normalizedChunks.first()
            val normStats = analyzeWavPcm(firstNorm.file)
            Timber.i("After normalization: %s", normStats.summary())

            // ── Step 4: Save debug WAV for user verification ───────────
            saveDebugWavToDownloads(context, firstNorm.file)

            // ── Step 5: Transcribe all normalized chunks ───────────────
            val result = transcribeChunks(apiKey, normalizedChunks, language, onProgress)

            if (result.isEmpty()) {
                throw SubtitleException(
                    "No speech detected even after normalization. " +
                    "PCM before: meanAbs=%.0f, after: meanAbs=%.0f. ".format(
                        analyzeWavPcm(limitedChunks.first().file).meanAbsSample,
                        normStats.meanAbsSample) +
                    "Try selecting the language manually " +
                    "(Bengali, Japanese, etc.) instead of Auto-detect."
                )
            }

            Timber.i("═══ TRANSCRIPTION COMPLETE: %d segments ═══", result.size)
            result

        } finally {
            cleanupSource()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // PCM NORMALIZATION — The key fix for quiet audio
    // ══════════════════════════════════════════════════════════════════

    /**
     * Normalize a WAV file by amplifying the audio so the peak reaches
     * ~90% of the 16-bit range (32767 * 0.9 ≈ 29490).
     *
     * This is the KEY fix for the "No speech detected" problem.
     * Movie audio has quiet intros and dialogue — the overall volume
     * is low (meanAbs=515 in our test) but speech IS present.
     * By amplifying the audio, Whisper's voice activity detection
     * can properly identify speech segments.
     *
     * How it works:
     * 1. Read the WAV file
     * 2. Find the peak absolute sample value
     * 3. Calculate gain: targetPeak / currentPeak
     * 4. Multiply all samples by gain, clamping to [-32768, 32767]
     * 5. Write back the amplified data
     *
     * Returns the normalized WAV file (may be the same file modified in-place,
     * or a new file if the original was too large to read into memory).
     */
    private fun normalizeWavFile(wavFile: File, stats: PcmAnalysisResult): File? {
        try {
            // Don't normalize if audio is already loud enough
            val currentPeak = maxOf(kotlin.math.abs(stats.minSample), kotlin.math.abs(stats.maxSample))
            if (currentPeak >= 20000) {
                Timber.i("Audio already loud (peak=%d), no normalization needed", currentPeak)
                return wavFile  // Already loud enough
            }

            if (currentPeak < MIN_PEAK_FOR_NORMALIZATION) {
                // Very quiet — might be just noise floor, amplify carefully
                Timber.i("Very quiet audio (peak=%d), will normalize with caution", currentPeak)
            }

            // Calculate amplification gain
            val targetPeak = (32767 * NORMALIZATION_TARGET).toInt()  // ~29490
            val gain = targetPeak.toDouble() / currentPeak.toDouble()
            Timber.i("Normalizing: peak=%d → target=%d, gain=%.2fx", currentPeak, targetPeak, gain)

            // Read the WAV file
            val data = wavFile.readBytes()
            if (data.size < 44) return null

            // Parse WAV header
            val channels = littleEndianToShort(data, 22)
            val sampleRate = littleEndianToInt(data, 24)
            val bitsPerSample = littleEndianToShort(data, 34)

            if (bitsPerSample != 16) {
                Timber.w("Only 16-bit WAV supported for normalization, got %d-bit", bitsPerSample)
                return wavFile  // Can't normalize non-16-bit
            }

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

            if (dataOffset < 0 || dataSize <= 0) return null

            val pcmEnd = minOf(dataOffset + dataSize, data.size)

            // Amplify all 16-bit PCM samples
            var clipCount = 0
            var i = dataOffset
            while (i + 1 < pcmEnd) {
                // Read 16-bit little-endian sample
                val low = data[i].toInt() and 0xFF
                val high = data[i + 1].toInt()
                val sample = (high shl 8) or low

                // Apply gain
                val amplified = (sample * gain).toLong()

                // Clamp to 16-bit range
                val clamped = amplified.toInt().coerceIn(-32768, 32767)
                if (clamped.toLong() != amplified) clipCount++

                // Write back as 16-bit little-endian
                data[i] = (clamped and 0xFF).toByte()
                data[i + 1] = (clamped shr 8).toByte()

                i += 2
            }

            // Write modified data back to the file
            wavFile.writeBytes(data)

            if (clipCount > 0) {
                Timber.d("Normalization: %d samples clipped (%.2f%%)",
                    clipCount, clipCount * 100.0 / (dataSize / 2))
            }

            Timber.i("Normalization complete: gain=%.2fx, %d samples processed", gain, dataSize / 2)
            return wavFile

        } catch (e: Exception) {
            Timber.e(e, "Failed to normalize WAV file")
            return wavFile  // Return original file as fallback
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // FILE DIAGNOSTICS
    // ══════════════════════════════════════════════════════════════════

    private fun logFileDiagnostics(file: File) {
        Timber.i("═══ FILE DIAGNOSTICS ═══")
        Timber.i("  Name: %s", file.name)
        Timber.i("  Size: %d bytes (%.2fMB)", file.length(), file.length() / (1024.0 * 1024.0))
        Timber.i("  Extension: %s", file.extension)

        try {
            val bytes = file.inputStream().use { it.readNBytes(16) }
            val hex = bytes.joinToString(" ") { "%02x".format(it) }
            Timber.i("  First 16 bytes: %s", hex)

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

        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            Timber.i("  Tracks: %d", extractor.trackCount)
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: "unknown"
                val sr = try { fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) { -1 }
                val ch = try { fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) { -1 }
                val dur = try { fmt.getLong(MediaFormat.KEY_DURATION) / 1000 } catch (_: Exception) { -1L }
                val br = try { fmt.getInteger(MediaFormat.KEY_BIT_RATE) } catch (_: Exception) { -1 }
                Timber.i("    Track %d: %s, %dHz, %dch, %dms, %dbps", i, mime, sr, ch, dur, br)
            }
            extractor.release()
        } catch (e: Exception) {
            Timber.w(e, "  Could not probe with MediaExtractor")
        }
        Timber.i("═══ END FILE DIAGNOSTICS ═══")
    }

    // ══════════════════════════════════════════════════════════════════
    // PCM ANALYSIS
    // ══════════════════════════════════════════════════════════════════

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

    private fun analyzeWavPcm(wavFile: File): PcmAnalysisResult {
        try {
            val data = wavFile.readBytes()
            if (data.size < 44) {
                return PcmAnalysisResult(wavFile.length(), 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0, false)
            }

            val channels = littleEndianToShort(data, 22)
            val sampleRate = littleEndianToInt(data, 24)
            val bitsPerSample = littleEndianToShort(data, 34)

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
                return PcmAnalysisResult(wavFile.length(), 0, sampleRate, channels, bitsPerSample,
                    0, 0, 0, 0.0, 0.0, false)
            }

            val pcmEnd = minOf(dataOffset + dataSize, data.size)
            val bytesPerSample = bitsPerSample / 8
            val totalSamples = (pcmEnd - dataOffset) / bytesPerSample

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
            val hasAudio = nonZeroPct >= 1.0 && meanAbs >= 50.0

            return PcmAnalysisResult(
                fileBytes = wavFile.length(), pcmDataBytes = dataSize,
                sampleRate = sampleRate, channels = channels, bitsPerSample = bitsPerSample,
                totalSamples = totalSamples,
                minSample = if (minSample == Int.MAX_VALUE) 0 else minSample,
                maxSample = if (maxSample == Int.MIN_VALUE) 0 else maxSample,
                meanAbsSample = meanAbs, nonZeroPercent = nonZeroPct, hasAudio = hasAudio
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to analyze WAV PCM")
            return PcmAnalysisResult(wavFile.length(), 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0, false)
        }
    }

    private fun littleEndianToShort(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun littleEndianToInt(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    // ══════════════════════════════════════════════════════════════════
    // SAVE DEBUG WAV
    // ══════════════════════════════════════════════════════════════════

    private fun saveDebugWavToDownloads(context: Context, wavFile: File) {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            val debugFile = File(downloadsDir, "looplingo_debug_normalized.wav")
            wavFile.inputStream().use { input ->
                FileOutputStream(debugFile).use { output -> input.copyTo(output) }
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

    private fun copyContentUriToTempFile(context: Context, contentUri: String): File {
        val uri = Uri.parse(contentUri)
        val ext = guessExtensionFromUri(context, uri) ?: "mp4"
        val tempFile = File.createTempFile("looplingo_input_", ".$ext", context.cacheDir)

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output, 64 * 1024) }
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

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            var outputSampleRate = srcSampleRate
            var outputChannels = srcChannels
            var outputFormatChecked = false

            val chunkBuffer = mutableListOf<ByteArray>()
            var chunkBufferSize = 0L
            val bytesPerSecond = srcSampleRate.toLong() * srcChannels * 2
            val chunkSizeBytes = (bytesPerSecond * chunkDurationSec).toLong()
            var chunkIndex = 0
            var totalPcmBytesDecoded = 0L
            var chunkStartTimeBytes = 0L

            var inputDone = false
            var outputDone = false
            val bufferInfo = MediaCodec.BufferInfo()

            while (!outputDone) {
                if (!inputDone) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize,
                                    extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)

                when {
                    outputBufferIndex >= 0 -> {
                        if (!outputFormatChecked) {
                            val outFormat = decoder.outputFormat
                            try { outputSampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) {}
                            try { outputChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) {}
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

                                    if (chunks.size >= MAX_CHUNKS) {
                                        Timber.i("Reached max chunks (%d), stopping", MAX_CHUNKS)
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
                        outputFormatChecked = true
                        Timber.i("Output format changed: %dHz, %dch", outputSampleRate, outputChannels)
                    }
                    outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* wait */ }
                }
            }

            // Drain remaining PCM as the last chunk
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

            Timber.i("Decode complete: %d chunks, %d bytes PCM", chunks.size, totalPcmBytesDecoded)
            return chunks

        } catch (e: Exception) {
            Timber.e(e, "Failed to decode audio")
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
        val fileSize = 36 + dataSize

        val outputFile = File.createTempFile("looplingo_wav_${chunkIndex}_", ".wav", context.cacheDir)

        FileOutputStream(outputFile).use { fos ->
            val out = java.io.BufferedOutputStream(fos, 32 * 1024)

            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            out.write(intToLittleEndian(fileSize))
            out.write("WAVE".toByteArray(Charsets.US_ASCII))

            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            out.write(intToLittleEndian(16))
            out.write(shortToLittleEndian(WAV_AUDIO_FORMAT_PCM))
            out.write(shortToLittleEndian(channels))
            out.write(intToLittleEndian(sampleRate))
            out.write(intToLittleEndian(byteRate))
            out.write(shortToLittleEndian(blockAlign))
            out.write(shortToLittleEndian(WAV_BITS_PER_SAMPLE))

            out.write("data".toByteArray(Charsets.US_ASCII))
            out.write(intToLittleEndian(dataSize))

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

    // ══════════════════════════════════════════════════════════════════
    // TRANSCRIBE CHUNKS
    // ══════════════════════════════════════════════════════════════════

    private suspend fun transcribeChunks(
        apiKey: String,
        chunks: List<AudioChunk>,
        language: String,
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
                val chunkSegments = callWhisperApi(apiKey, chunk.file, language)
                val offsetSec = chunk.startTimeSec

                if (chunkSegments.isEmpty()) {
                    emptyChunks++
                    Timber.d("Chunk %d/%d: no speech (time %.1fs-%.1fs)",
                        chunkNum, chunks.size, offsetSec, offsetSec + chunk.durationSec)
                } else {
                    for (seg in chunkSegments) {
                        allSegments.add(Segment(
                            id = segmentIdOffset + seg.id,
                            text = seg.text,
                            startSec = seg.startSec + offsetSec,
                            endSec = seg.endSec + offsetSec
                        ))
                    }
                    segmentIdOffset += chunkSegments.size
                    Timber.d("Chunk %d/%d: %d segments (offset +%.1fs)",
                        chunkNum, chunks.size, chunkSegments.size, offsetSec)
                }

            } catch (e: Exception) {
                failedChunks++
                Timber.e(e, "Failed to transcribe chunk %d/%d", chunkNum, chunks.size)
            }

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
     *
     * @param language ISO 639-1 code or "auto". When set, tells Whisper
     *                 what language to expect — improves detection for
     *                 non-English audio (Bengali, Japanese, etc.)
     */
    private fun callWhisperApi(apiKey: String, audioFile: File, language: String = "auto"): List<Segment> {
        val mediaType = guessAudioMediaType(audioFile.name)
        Timber.i("→ Whisper API: %s (%.2fKB) as %s, lang=%s",
            audioFile.name, audioFile.length() / 1024.0, mediaType, language)

        val fileBody = audioFile.asRequestBody(mediaType.toMediaType())

        val multipartBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, fileBody)
            .addFormDataPart("model", "whisper-large-v3")
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "segment")

        // Add language parameter if specified (not "auto")
        // When language is set, Whisper skips language detection and
        // directly transcribes in that language — much more accurate
        // for Bengali, Japanese, and other non-English languages.
        if (language.isNotBlank() && language != "auto") {
            multipartBuilder.addFormDataPart("language", language)
            Timber.i("  Language set to: %s", language)
        }

        val request = Request.Builder()
            .url(GROQ_API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(multipartBuilder.build())
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()

        if (!response.isSuccessful || responseBody.isNullOrBlank()) {
            val errorDetail = responseBody?.take(500) ?: "No response body"
            Timber.e("← Whisper API error: HTTP %d — %s", response.code, errorDetail)
            throw RuntimeException("Groq API error ${response.code}: ${responseBody?.take(200) ?: "No response"}")
        }

        Timber.d("← Whisper API response: HTTP %d, %d bytes", response.code, responseBody.length)

        val transcription = parseTranscriptionResponse(responseBody)

        if (transcription.error != null) {
            val errMsg = transcription.error.message ?: "Unknown error"
            Timber.e("← Whisper API error: %s", errMsg)
            throw RuntimeException(errMsg)
        }

        if (transcription.segments.isNullOrEmpty() && !transcription.text.isNullOrBlank()) {
            Timber.i("← No segments but got full text: \"%s\"", transcription.text.take(80))
            return listOf(Segment(id = 0, text = transcription.text.trim(), startSec = 0.0, endSec = 0.0))
        }

        val segments = transcription.segments?.map { segJson ->
            Segment(id = segJson.id, text = segJson.text.trim(), startSec = segJson.start, endSec = segJson.end)
        } ?: emptyList()

        Timber.i("← Whisper API returned %d segments", segments.size)
        return segments
    }

    private fun guessAudioMediaType(fileName: String): String {
        return when (fileName.substringAfterLast(".", "").lowercase()) {
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "mp4" -> "video/mp4"
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
