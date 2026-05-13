package com.looplingo.horizon.api

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
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
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Client for the Groq Whisper API — speech-to-text transcription.
 *
 * Architecture (v1.17.0 — Simple Pipeline):
 *
 *   PREVIOUS APPROACH (v1.10–v1.16) was overcomplicated:
 *   Video → MediaCodec → PCM → WAV → Normalize → Send to Whisper
 *   This decoded to raw PCM, created huge WAV files, and was slow.
 *
 *   ENGINEERING INSIGHT: Why not just extract the audio track as-is?
 *   - Groq Whisper accepts: mp3, mp4, m4a, wav, ogg, flac, webm
 *   - No need to decode to PCM! Just pull the audio track out of the video.
 *   - MediaMuxer can extract audio WITHOUT re-encoding (just demuxing).
 *   - Result: 10-50x smaller files, 5-10x faster, simpler code.
 *
 *   NEW PIPELINE (3 steps, try each in order):
 *
 *   Step 1: ALREADY AUDIO? → Send directly to Whisper
 *     If the input file is already an audio file (MP3, M4A, etc.) and
 *     under 25MB, just send it. No processing needed.
 *
 *   Step 2: VIDEO? → Extract audio track as M4A/MP3 → Send to Whisper
 *     Use MediaMuxer to pull out just the audio track. No re-encoding.
 *     The audio stays in its original compressed format (AAC/MP3).
 *     Most audio tracks are well under 25MB.
 *     If too large, split into time-based chunks using seek+extract.
 *
 *   Step 3 (FALLBACK): PCM NORMALIZATION → WAV → Send to Whisper
 *     Only if the above fails (quiet audio that Whisper can't detect).
 *     Decode to PCM, amplify, write WAV. This is the nuclear option.
 *
 *   Why this is better:
 *   - Whisper's own FFmpeg decoder is battle-tested and handles volume
 *     normalization internally much better than our MediaCodec pipeline
 *   - Compressed audio (M4A) is 10-50x smaller than WAV — faster uploads
 *   - No quality loss from re-encoding
 *   - Simpler code = fewer bugs
 */
class GroqApiClient {

    companion object {
        private const val GROQ_MAX_FILE_SIZE = 25L * 1024 * 1024
        private const val MAX_CHUNKS = 5  // For testing: only process 5 chunks
        private const val CHUNK_DURATION_SEC = 30.0  // 30s per chunk for muxer-based splitting

        private const val GROQ_API_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val GROQ_MODELS_URL = "https://api.groq.com/openai/v1/models"

        // WAV format constants (fallback only)
        private const val WAV_BITS_PER_SAMPLE = 16
        private const val WAV_AUDIO_FORMAT_PCM = 1

        // PCM normalization (fallback only)
        private const val NORMALIZATION_TARGET = 0.9
        private const val MIN_PEAK_FOR_NORMALIZATION = 500

        // MediaCodec timeout (fallback only)
        private const val CODEC_TIMEOUT_US = 10_000L

        /** Audio MIME types that Whisper accepts directly */
        private val WHISPER_AUDIO_MIMES = setOf(
            "audio/mpeg", "audio/mp4", "audio/aac", "audio/ogg",
            "audio/opus", "audio/wav", "audio/flac", "audio/webm",
            "audio/3gpp", "audio/x-matroska"
        )

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

    open class SubtitleException(message: String) : Exception(message)

    /** Thrown when the API key is invalid/expired — no point retrying other steps */
    class ApiKeyException(message: String) : SubtitleException(message)

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

        Timber.i("═══ STARTING TRANSCRIPTION PIPELINE v1.18.0 ═══")
        Timber.i("Input: %s", filePath.take(100))
        Timber.i("Language: %s", language)

        // ── Step 0a: Validate API key before doing any work ───────────
        onProgress?.onProgress("[Step 0] Checking API key…")
        try {
            validateApiKey(apiKey)
            onProgress?.onProgress("[Step 0] API key is valid ✓")
        } catch (e: Exception) {
            val errMsg = e.message ?: "API key check failed"
            onProgress?.onProgress("[Step 0] ✗ API KEY INVALID: $errMsg")
            throw ApiKeyException(errMsg)
        }

        // ── Step 0b: Resolve to a readable file ────────────────────────
        onProgress?.onProgress("[Step 0] Resolving file path…")
        val (sourceFile, cleanupSource) = resolveToReadableFile(context, filePath)

        try {
            if (!sourceFile.exists() || sourceFile.length() == 0L) {
                throw SubtitleException("Cannot read file: ${sourceFile.name}. " +
                    "Try selecting the file again or check storage permissions.")
            }

            val sourceSizeMB = sourceFile.length() / (1024.0 * 1024.0)
            val isAudio = isAudioFile(sourceFile)
            onProgress?.onProgress("[Step 0] File ready: ${sourceFile.name} (%.2fMB, %s)".format(
                sourceSizeMB, if (isAudio) "AUDIO" else "VIDEO"))
            Timber.i("Source file: %s (%.2fMB, isAudio=%b)", sourceFile.name, sourceSizeMB, isAudio)
            logFileDiagnostics(sourceFile)

            // ── Step 1: ALREADY AUDIO? Send directly ──────────────────
            if (isAudio && sourceFile.length() <= GROQ_MAX_FILE_SIZE) {
                val mediaType = guessAudioMediaType(sourceFile.name)
                onProgress?.onProgress("[Step 1] Audio file — sending directly as $mediaType (%.2fMB)…".format(sourceSizeMB))
                Timber.i("Step 1: File is already audio (%s, %s) — sending directly (%.2fMB)",
                    sourceFile.extension, mediaType, sourceSizeMB)

                try {
                    val result = callWhisperApi(apiKey, sourceFile, language)
                    if (result.isNotEmpty()) {
                        onProgress?.onProgress("[Step 1] ✓ SUCCESS! %d segments from direct audio".format(result.size))
                        Timber.i("═══ SUCCESS: %d segments from direct audio! ═══", result.size)
                        return@withContext result
                    }
                    onProgress?.onProgress("[Step 1] Whisper returned no speech — trying extraction…")
                    Timber.w("Direct audio: no speech detected, trying extraction")
                } catch (e: ApiKeyException) {
                    // API key issue — don't try other steps, fail immediately
                    onProgress?.onProgress("[Step 1] ✗ API KEY ERROR: ${e.message}")
                    throw e
                } catch (e: Exception) {
                    onProgress?.onProgress("[Step 1] Failed: ${e.message?.take(100)}")
                    Timber.w(e, "Direct audio failed, trying extraction")
                }
            } else if (!isAudio) {
                onProgress?.onProgress("[Step 1] Video file detected — need to extract audio track")
            } else {
                onProgress?.onProgress("[Step 1] Audio too large (%.2fMB > 25MB) — will chunk".format(sourceSizeMB))
            }

            // ── Step 2: EXTRACT AUDIO TRACK (no re-encode) ────────────
            onProgress?.onProgress("[Step 2] Extracting audio track (no re-encoding)…")
            Timber.i("Step 2: Extracting audio track with MediaMuxer (no re-encoding)")

            val extractedAudio = extractAudioTrack(context, sourceFile)
            if (extractedAudio != null) {
                val extractedKB = extractedAudio.length() / 1024.0
                onProgress?.onProgress("[Step 2] Extracted: ${extractedAudio.name} (%.1fKB)".format(extractedKB))
                Timber.i("Extracted audio: %s (%.2fKB)", extractedAudio.name, extractedKB)

                if (extractedAudio.length() <= GROQ_MAX_FILE_SIZE) {
                    // Audio fits in one request — send it all at once
                    val sendMediaType = guessAudioMediaType(extractedAudio.name)
                    onProgress?.onProgress("[Step 2] Sending to Whisper as $sendMediaType (%.1fKB)…".format(extractedKB))
                    try {
                        val result = callWhisperApi(apiKey, extractedAudio, language)
                        if (result.isNotEmpty()) {
                            onProgress?.onProgress("[Step 2] ✓ SUCCESS! %d segments from extracted audio".format(result.size))
                            Timber.i("═══ SUCCESS: %d segments from extracted audio! ═══", result.size)
                            extractedAudio.delete()
                            return@withContext result
                        }
                        onProgress?.onProgress("[Step 2] No speech detected — trying chunks…")
                        Timber.w("Extracted audio: no speech detected, trying with chunks")
                    } catch (e: ApiKeyException) {
                        extractedAudio.delete()
                        throw e  // Don't retry with bad API key
                    } catch (e: Exception) {
                        onProgress?.onProgress("[Step 2] API error: ${e.message?.take(100)}")
                        Timber.w(e, "Full extracted audio failed, trying chunks")
                    }
                }

                // Audio too large or single request failed — split into time-based chunks
                onProgress?.onProgress("[Step 2b] Splitting audio into time-based chunks…")
                val chunks = splitAudioIntoChunks(context, sourceFile)
                if (chunks.isNotEmpty()) {
                    onProgress?.onProgress("[Step 2b] Created %d chunks, transcribing…".format(chunks.size))
                    val result = transcribeChunks(apiKey, chunks, language, onProgress)
                    extractedAudio.delete()
                    if (result.isNotEmpty()) {
                        onProgress?.onProgress("[Step 2b] ✓ SUCCESS! %d segments from %d chunks".format(result.size, chunks.size))
                        Timber.i("═══ SUCCESS: %d segments from %d chunks! ═══", result.size, chunks.size)
                        return@withContext result
                    }
                    onProgress?.onProgress("[Step 2b] No speech detected in any chunk")
                    Timber.w("Chunks: no speech detected in any chunk")
                }
                extractedAudio.delete()
            } else {
                onProgress?.onProgress("[Step 2] Audio extraction FAILED — trying PCM fallback")
            }

            // ── Step 3 (FALLBACK): PCM NORMALIZATION ──────────────────
            onProgress?.onProgress("[Step 3] FALLBACK: Decoding to PCM + normalizing…")
            Timber.i("Step 3: FALLBACK — decoding to PCM with normalization")

            val wavChunks = decodeAndCreateWavChunks(context, sourceFile)
            if (wavChunks.isEmpty()) {
                throw SubtitleException(
                    "Could not decode audio from this file. " +
                    "The audio format may not be supported. Try an MP3, M4A, or MP4 file."
                )
            }

            val limitedChunks = wavChunks.take(MAX_CHUNKS)
            wavChunks.drop(MAX_CHUNKS).forEach { it.file.delete() }

            Timber.i("Created %d WAV chunks for normalization fallback", limitedChunks.size)

            // Normalize each chunk
            onProgress?.onProgress("Normalizing audio volume…")
            val normalizedChunks = mutableListOf<AudioChunk>()
            for (chunk in limitedChunks) {
                val stats = analyzeWavPcm(chunk.file)
                Timber.d("Chunk pre-norm: %s", stats.summary())

                if (stats.meanAbsSample < 10 && stats.nonZeroPercent < 1.0) {
                    Timber.d("Chunk at %.1fs is silent, skipping", chunk.startTimeSec)
                    chunk.file.delete()
                    continue
                }

                val normalizedFile = normalizeWavFile(chunk.file, stats)
                if (normalizedFile != null && normalizedFile != chunk.file) {
                    chunk.file.delete()
                }
                normalizedChunks.add(chunk.copy(file = normalizedFile ?: chunk.file))
            }

            if (normalizedChunks.isEmpty()) {
                throw SubtitleException(
                    "All audio chunks were silent. This file may not contain audible speech."
                )
            }

            // Save debug WAV
            saveDebugFile(context, normalizedChunks.first().file, "looplingo_debug_normalized.wav")

            val result = transcribeChunks(apiKey, normalizedChunks, language, onProgress)
            if (result.isEmpty()) {
                val lastResp = lastWhisperResponseRaw.take(200)
                onProgress?.onProgress("")
                onProgress?.onProgress("═══ DIAGNOSTICS ═══")
                onProgress?.onProgress("All 3 steps failed (direct, extracted, PCM)")
                onProgress?.onProgress("Last API response: $lastResp")
                onProgress?.onProgress("File: ${sourceFile.name} (%.2fMB)".format(sourceSizeMB))
                onProgress?.onProgress("")
                onProgress?.onProgress("POSSIBLE CAUSES:")
                onProgress?.onProgress("1. Audio might be truly silent (no speech)")
                onProgress?.onProgress("2. Language set to 'auto' — try Bengali/Japanese/etc")
                onProgress?.onProgress("3. M4A extraction may be corrupt — check Downloads/")
                onProgress?.onProgress("4. Audio codec not supported by Whisper")
                throw SubtitleException(
                    "No speech detected in any step. See debug log for details. " +
                    "Try: 1) Select language manually 2) Try a different file 3) Check debug files in Downloads/"
                )
            }

            Timber.i("═══ TRANSCRIPTION COMPLETE (fallback): %d segments ═══", result.size)
            result

        } finally {
            cleanupSource()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // STEP 1 CHECK: Is this already an audio file?
    // ══════════════════════════════════════════════════════════════════

    private fun isAudioFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        if (ext in listOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "wma", "3gp")) {
            return true
        }

        // Check by probing the file format
        return try {
            val bytes = file.inputStream().use { it.readNBytes(12) }
            when {
                bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFB.toByte() -> true  // MP3
                bytes.size >= 3 && bytes[0] == 0x49.toByte() && bytes[1] == 0x44.toByte() && bytes[2] == 0x33.toByte() -> true  // MP3 ID3
                bytes.size >= 4 && String(bytes, 0, 4) == "OggS" -> true  // OGG
                bytes.size >= 4 && String(bytes, 0, 4) == "fLaC" -> true  // FLAC
                bytes.size >= 4 && String(bytes, 0, 4) == "RIFF" -> true  // WAV
                // MP4/M4A: check for "ftyp" at offset 4
                bytes.size >= 8 && String(bytes, 4, 4) == "ftyp" -> {
                    // It's an MP4 container — could be audio-only or video
                    // Check with MediaExtractor if it has video tracks
                    !hasVideoTrack(file)
                }
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun hasVideoTrack(file: File): Boolean {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    extractor.release()
                    return true
                }
            }
            extractor.release()
            false
        } catch (e: Exception) {
            false
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // STEP 2: EXTRACT AUDIO TRACK using MediaMuxer (no re-encoding)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Extract just the audio track from a video file using MediaMuxer.
     *
     * This is the SMART way: no re-encoding, just demuxing.
     * The audio stays in its original compressed format (AAC, MP3, etc.)
     * and gets written to an M4A container.
     *
     * Why this works:
     * - MediaMuxer reads compressed frames directly from the source
     * - No decoding/encoding = no quality loss, 10-50x faster than PCM
     * - Result is a small compressed file (M4A) that Whisper accepts
     * - A typical 30-min video's audio track is only ~15-30MB in AAC
     */
    private fun extractAudioTrack(context: Context, sourceFile: File): File? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            extractor.setDataSource(sourceFile.absolutePath)

            // Find the audio track
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

            val audioMime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(audioTrackIndex)

            // Determine output format based on audio codec
            val outputFormat = when {
                audioMime.contains("aac") || audioMime.contains("mp4a") -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                audioMime.contains("mpeg") || audioMime.contains("mp3") -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                audioMime.contains("ogg") || audioMime.contains("opus") -> MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
                audioMime.contains("amr") -> MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP
                else -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4  // Default to MP4 container
            }

            val outputExt = when (outputFormat) {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 -> "m4a"
                MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG -> "ogg"
                MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP -> "3gp"
                else -> "m4a"
            }

            val outputFile = File.createTempFile("looplingo_audio_", ".$outputExt", context.cacheDir)

            muxer = MediaMuxer(outputFile.absolutePath, outputFormat)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            // Copy all audio samples from extractor to muxer
            val buffer = ByteBuffer.allocate(256 * 1024)  // 256KB buffer
            val bufferInfo = MediaCodec.BufferInfo()
            var sampleCount = 0

            while (true) {
                buffer.clear()  // Reset buffer position before each read
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

            val durationSec = try {
                audioFormat.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0
            } catch (_: Exception) { -1.0 }

            Timber.i("Extracted audio: %d samples, %.1fs, %s → %s (%.2fKB)",
                sampleCount, durationSec, audioMime, outputFile.name, outputFile.length() / 1024.0)

            if (outputFile.length() == 0L) {
                outputFile.delete()
                return null
            }

            // Save debug copy of extracted audio
            saveDebugFile(context, outputFile, "looplingo_debug_extracted.$outputExt")

            return outputFile

        } catch (e: Exception) {
            Timber.e(e, "Failed to extract audio track with MediaMuxer")
            return null
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}  // safe even if stop() was called
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // STEP 2b: SPLIT AUDIO INTO CHUNKS using seek + MediaMuxer
    // ══════════════════════════════════════════════════════════════════

    /**
     * Split audio into time-based chunks for files over 25MB.
     * Uses MediaExtractor seek to jump to each chunk's start time,
     * then MediaMuxer to write compressed frames for that duration.
     *
     * No re-encoding — just extracting portions of the compressed stream.
     */
    private fun splitAudioIntoChunks(
        context: Context,
        sourceFile: File,
        chunkDurationSec: Double = CHUNK_DURATION_SEC
    ): List<AudioChunk> {
        val extractor = MediaExtractor()
        val chunks = mutableListOf<AudioChunk>()

        try {
            extractor.setDataSource(sourceFile.absolutePath)

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

            if (audioTrackIndex < 0 || audioFormat == null) return emptyList()

            val audioMime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            extractor.selectTrack(audioTrackIndex)

            val totalDurationUs = try {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } catch (_: Exception) { -1L }

            if (totalDurationUs <= 0) {
                Timber.w("Cannot determine audio duration for chunking")
                return emptyList()
            }

            val chunkDurationUs = (chunkDurationSec * 1_000_000).toLong()
            val numChunks = ((totalDurationUs + chunkDurationUs - 1) / chunkDurationUs).toInt()
            val actualChunks = minOf(numChunks, MAX_CHUNKS)

            Timber.i("Splitting: total=%.1fs, chunk=%.0fs, %d chunks (limit %d)",
                totalDurationUs / 1_000_000.0, chunkDurationSec, numChunks, actualChunks)

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

            val buffer = ByteBuffer.allocate(256 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            for (chunkIdx in 0 until actualChunks) {
                val chunkStartUs = chunkIdx.toLong() * chunkDurationUs
                val chunkEndUs = minOf(chunkStartUs + chunkDurationUs, totalDurationUs)

                // Seek to the start of this chunk
                buffer.clear()  // Reset buffer before seeking
                extractor.seekTo(chunkStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                val chunkFile = File.createTempFile("looplingo_chunk_${chunkIdx}_", ".$outputExt", context.cacheDir)
                var muxer: MediaMuxer? = null

                try {
                    muxer = MediaMuxer(chunkFile.absolutePath, outputFormat)
                    val muxerTrackIndex = muxer.addTrack(audioFormat!!)
                    muxer.start()

                    var sampleCount = 0
                    var lastPts = chunkStartUs

                    while (true) {
                        buffer.clear()  // Reset buffer before each read
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break  // End of stream

                        val pts = extractor.sampleTime
                        if (pts >= chunkEndUs) break  // Past this chunk's boundary

                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.flags = extractor.sampleFlags
                        bufferInfo.presentationTimeUs = pts

                        muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                        sampleCount++
                        lastPts = pts
                        extractor.advance()
                    }

                    muxer.stop()

                    val chunkDuration = (lastPts - chunkStartUs) / 1_000_000.0
                    chunks.add(AudioChunk(
                        file = chunkFile,
                        startTimeSec = chunkStartUs / 1_000_000.0,
                        durationSec = if (chunkDuration > 0) chunkDuration else chunkDurationSec
                    ))

                    Timber.d("Chunk %d/%d: %.1fs-%.1fs, %d samples, %.1fKB",
                        chunkIdx + 1, actualChunks,
                        chunkStartUs / 1_000_000.0, chunkEndUs / 1_000_000.0,
                        sampleCount, chunkFile.length() / 1024.0)

                } catch (e: Exception) {
                    Timber.e(e, "Failed to create chunk %d", chunkIdx)
                    chunkFile.delete()
                } finally {
                    try { muxer?.release() } catch (_: Exception) {}
                }
            }

            Timber.i("Created %d audio chunks via MediaMuxer", chunks.size)
            return chunks

        } catch (e: Exception) {
            Timber.e(e, "Failed to split audio into chunks")
            chunks.forEach { it.file.delete() }
            return emptyList()
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // STEP 3 (FALLBACK): PCM DECODE + NORMALIZE + WAV
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
                    break
                }
            }

            if (audioTrackIndex < 0 || inputFormat == null) return emptyList()

            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            extractor.selectTrack(audioTrackIndex)

            val srcSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

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

                                    if (chunks.size >= MAX_CHUNKS) break
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
                    }
                    outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* wait */ }
                }
            }

            // Drain remaining PCM
            if (chunkBuffer.isNotEmpty() && chunkBufferSize > 0 && chunks.size < MAX_CHUNKS) {
                val startTimeSec = chunkStartTimeBytes.toDouble() / bytesPerSecond
                val durationSec = chunkBufferSize.toDouble() / bytesPerSecond
                if (durationSec >= 0.5) {
                    val chunkFile = writeWavFile(
                        chunkBuffer, outputSampleRate, outputChannels, context, chunkIndex
                    )
                    chunks.add(AudioChunk(chunkFile, startTimeSec, durationSec))
                }
            }

            Timber.i("PCM decode: %d chunks, %d bytes", chunks.size, totalPcmBytesDecoded)
            return chunks

        } catch (e: Exception) {
            Timber.e(e, "Failed to decode audio to PCM")
            chunks.forEach { it.file.delete() }
            return emptyList()
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // WAV FILE WRITER (fallback only)
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
    // PCM NORMALIZATION (fallback only)
    // ══════════════════════════════════════════════════════════════════

    private fun normalizeWavFile(wavFile: File, stats: PcmAnalysisResult): File? {
        try {
            val currentPeak = maxOf(kotlin.math.abs(stats.minSample), kotlin.math.abs(stats.maxSample))
            if (currentPeak >= 20000) {
                Timber.i("Audio already loud (peak=%d), no normalization needed", currentPeak)
                return wavFile
            }

            val targetPeak = (32767 * NORMALIZATION_TARGET).toInt()
            val gain = targetPeak.toDouble() / maxOf(currentPeak.toDouble(), 1.0)
            Timber.i("Normalizing: peak=%d → target=%d, gain=%.2fx", currentPeak, targetPeak, gain)

            val data = wavFile.readBytes()
            if (data.size < 44) return null

            val bitsPerSample = littleEndianToShort(data, 34)
            if (bitsPerSample != 16) return wavFile

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
            var clipCount = 0
            var i = dataOffset
            while (i + 1 < pcmEnd) {
                val low = data[i].toInt() and 0xFF
                val high = data[i + 1].toInt()
                val sample = (high shl 8) or low
                val amplified = (sample * gain).toLong()
                val clamped = amplified.toInt().coerceIn(-32768, 32767)
                if (clamped.toLong() != amplified) clipCount++
                data[i] = (clamped and 0xFF).toByte()
                data[i + 1] = (clamped shr 8).toByte()
                i += 2
            }

            wavFile.writeBytes(data)

            if (clipCount > 0) {
                Timber.d("Normalization: %d samples clipped (%.2f%%)",
                    clipCount, clipCount * 100.0 / (dataSize / 2))
            }

            Timber.i("Normalization complete: gain=%.2fx", gain)
            return wavFile

        } catch (e: Exception) {
            Timber.e(e, "Failed to normalize WAV file")
            return wavFile
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // PCM ANALYSIS (fallback only)
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

            return PcmAnalysisResult(
                fileBytes = wavFile.length(), pcmDataBytes = dataSize,
                sampleRate = sampleRate, channels = channels, bitsPerSample = bitsPerSample,
                totalSamples = totalSamples,
                minSample = if (minSample == Int.MAX_VALUE) 0 else minSample,
                maxSample = if (maxSample == Int.MIN_VALUE) 0 else maxSample,
                meanAbsSample = meanAbs, nonZeroPercent = nonZeroPct,
                hasAudio = nonZeroPct >= 1.0 && meanAbs >= 50.0
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
    // FILE RESOLUTION
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
                Timber.i("    Track %d: %s, %dHz, %dch, %dms", i, mime, sr, ch, dur)
            }
            extractor.release()
        } catch (e: Exception) {
            Timber.w(e, "  Could not probe with MediaExtractor")
        }
        Timber.i("═══ END FILE DIAGNOSTICS ═══")
    }

    // ══════════════════════════════════════════════════════════════════
    // DEBUG FILE SAVE
    // ══════════════════════════════════════════════════════════════════

    private fun saveDebugFile(context: Context, sourceFile: File, debugName: String) {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            val debugFile = File(downloadsDir, debugName)
            sourceFile.inputStream().use { input ->
                FileOutputStream(debugFile).use { output -> input.copyTo(output) }
            }
            Timber.i("Debug file saved to: %s (%.2fKB)", debugFile.absolutePath, debugFile.length() / 1024.0)
        } catch (e: Exception) {
            Timber.w(e, "Could not save debug file to Downloads")
        }
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
            onProgress?.onProgress("[Chunk $chunkNum/${chunks.size}] Sending to Whisper…")

            try {
                val chunkSegments = callWhisperApi(apiKey, chunk.file, language)
                val offsetSec = chunk.startTimeSec

                if (chunkSegments.isEmpty()) {
                    emptyChunks++
                    Timber.d("Chunk %d/%d: no speech (time %.1fs)",
                        chunkNum, chunks.size, offsetSec)
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
    // API KEY VALIDATION
    // ══════════════════════════════════════════════════════════════════

    /**
     * Quick check if the API key is valid by hitting the /models endpoint.
     * This avoids wasting time extracting audio only to discover the key is dead.
     */
    private fun validateApiKey(apiKey: String) {
        Timber.i("Validating API key: %s...%s", apiKey.take(8), apiKey.takeLast(4))

        val request = Request.Builder()
            .url(GROQ_MODELS_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string()?.take(200) ?: ""

            if (response.code == 401) {
                throw ApiKeyException(
                    "API key is INVALID (HTTP 401). The key was rejected.\n" +
                    "Fix: Go to console.groq.com → API Keys → Create new key."
                )
            }
            if (response.code == 403) {
                throw ApiKeyException(
                    "API key is FORBIDDEN/EXPIRED (HTTP 403). This key no longer works.\n" +
                    "Fix: Go to console.groq.com → API Keys → Create new key.\n" +
                    "Response: $body"
                )
            }
            if (!response.isSuccessful) {
                Timber.w("API key check got HTTP %d: %s", response.code, body)
                // Don't throw for other errors — key might be fine, server issue
            } else {
                Timber.i("API key is valid (HTTP %d)", response.code)
            }
        } catch (e: ApiKeyException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not validate API key (network issue?)")
            // Don't block — might be a temporary network issue
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // WHISPER API CALL
    // ══════════════════════════════════════════════════════════════════

    private var lastWhisperResponseRaw: String = ""

    private fun callWhisperApi(apiKey: String, audioFile: File, language: String = "auto"): List<Segment> {
        val mediaType = guessAudioMediaType(audioFile.name)
        Timber.i("→ Whisper API: %s (%.2fKB) as %s, lang=%s",
            audioFile.name, audioFile.length() / 1024.0, mediaType, language)

        // For M4A files: try sending as .mp4 extension with video/mp4 MIME type
        // Groq's FFmpeg decoder handles .mp4 better than .m4a in some cases
        val effectiveFileName = if (audioFile.extension.lowercase() == "m4a") {
            audioFile.nameWithoutExtension + ".mp4"
        } else {
            audioFile.name
        }
        val effectiveMediaType = if (audioFile.extension.lowercase() == "m4a") {
            "video/mp4"  // Groq FFmpeg handles video/mp4 for MP4 containers better
        } else {
            mediaType
        }

        Timber.i("  Effective: filename=%s, MIME=%s", effectiveFileName, effectiveMediaType)

        val fileBody = audioFile.asRequestBody(effectiveMediaType.toMediaType())

        val multipartBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", effectiveFileName, fileBody)
            .addFormDataPart("model", "whisper-large-v3")
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "segment")

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

        // ALWAYS log the full response for debugging
        lastWhisperResponseRaw = responseBody?.take(1000) ?: "(null)"
        Timber.i("← Whisper API: HTTP %d, body=%s", response.code, lastWhisperResponseRaw.take(300))

        if (!response.isSuccessful || responseBody.isNullOrBlank()) {
            val errorDetail = responseBody?.take(500) ?: "No response body"
            Timber.e("← Whisper API error: HTTP %d — %s", response.code, errorDetail)

            // Specific exception types for API key issues — these should NOT be retried
            val userMessage = when (response.code) {
                401 -> "API key is INVALID (HTTP 401). The server rejected your key.\n" +
                    "Fix: Go to console.groq.com → API Keys → Create a new key."
                403 -> "API key is FORBIDDEN/EXPIRED (HTTP 403). This key no longer works.\n" +
                    "Fix: Go to console.groq.com → API Keys → Create a new key.\n" +
                    "Server response: $errorDetail"
                429 -> "Rate limit exceeded (HTTP 429). Wait a moment and try again."
                413 -> "Audio file too large for Groq API (max 25MB). File: ${audioFile.name} (%.1fKB)".format(audioFile.length() / 1024.0)
                else -> "Groq API error HTTP ${response.code}: $errorDetail"
            }
            if (response.code == 401 || response.code == 403) {
                throw ApiKeyException(userMessage)
            }
            throw RuntimeException(userMessage)
        }

        val transcription = parseTranscriptionResponse(responseBody)

        if (transcription.error != null) {
            val errMsg = transcription.error.message ?: "Unknown error"
            Timber.e("← Whisper API error in response body: %s", errMsg)
            throw RuntimeException(errMsg)
        }

        // Log exactly what Whisper returned
        if (transcription.segments.isNullOrEmpty() && transcription.text.isNullOrBlank()) {
            Timber.w("← Whisper returned EMPTY: no text, no segments")
            Timber.w("← Full response: %s", responseBody.take(500))
        } else if (transcription.segments.isNullOrEmpty()) {
            Timber.i("← Whisper returned TEXT only (no segments): \"%s\"", transcription.text?.take(100))
        } else {
            Timber.i("← Whisper returned %d segments, text=\"%s\"", transcription.segments.size, transcription.text?.take(80))
        }

        if (transcription.segments.isNullOrEmpty() && !transcription.text.isNullOrBlank()) {
            return listOf(Segment(id = 0, text = transcription.text.trim(), startSec = 0.0, endSec = 0.0))
        }

        val segments = transcription.segments?.map { segJson ->
            Segment(id = segJson.id, text = segJson.text.trim(), startSec = segJson.start, endSec = segJson.end)
        } ?: emptyList()

        return segments
    }

    /** Get the last raw Whisper API response for debug logging */
    fun getLastWhisperResponse(): String = lastWhisperResponseRaw

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
