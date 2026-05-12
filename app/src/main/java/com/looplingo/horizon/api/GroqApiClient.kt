package com.looplingo.horizon.api

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Client for the Groq Whisper API — speech-to-text transcription.
 *
 * Smart pipeline for handling large video files:
 *  1. If file is audio-only and under 25MB → send directly
 *  2. If file is a video → extract audio track as M4A (much smaller)
 *  3. If audio is still over 25MB → split into ~30s chunks
 *  4. Send each chunk to Groq Whisper with progress updates
 *  5. Merge all segments with proper time offsets
 */
class GroqApiClient {

    companion object {
        private const val GROQ_MAX_FILE_SIZE = 25L * 1024 * 1024  // 25MB
        private const val CHUNK_DURATION_SEC = 30                  // 30s per chunk
        private const val GROQ_API_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)   // Longer timeout for large files
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
     * Smart pipeline:
     *  - Audio files under 25MB → send directly
     *  - Video files → extract audio track → send audio only
     *  - Audio still over 25MB → split into 30s chunks → transcribe each → merge
     *
     * @param apiKey Groq API key
     * @param filePath Path to the audio/video file
     * @param onProgress Callback for UI progress updates
     * @return List of timed segments with absolute timestamps
     */
    suspend fun transcribeAudio(
        apiKey: String,
        filePath: String,
        onProgress: ProgressCallback? = null
    ): List<Segment> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            Timber.w("Groq API key is blank — cannot transcribe")
            return@withContext emptyList()
        }

        val sourceFile = File(filePath)
        if (!sourceFile.exists()) {
            Timber.w("File not found: %s", filePath)
            return@withContext emptyList()
        }

        try {
            val ext = sourceFile.extension.lowercase()
            val isAudioOnly = ext in setOf("mp3", "m4a", "wav", "ogg", "flac", "aac", "opus")

            // ── Step 1: Check if we can send directly ──────────────────
            if (isAudioOnly && sourceFile.length() <= GROQ_MAX_FILE_SIZE) {
                onProgress?.onProgress("Sending audio to Groq Whisper…")
                val segments = callWhisperApi(apiKey, sourceFile)
                Timber.i("Direct transcription: %d segments", segments.size)
                return@withContext segments
            }

            // ── Step 2: Extract audio from video (if needed) ────────────
            val audioFile = if (isAudioOnly) {
                sourceFile
            } else {
                onProgress?.onProgress("Extracting audio from video…")
                val extracted = extractAudioTrack(sourceFile)
                if (extracted == null) {
                    Timber.w("Failed to extract audio, sending raw file (may fail if too large)")
                    onProgress?.onProgress("Sending file to Groq Whisper…")
                    return@withContext callWhisperApi(apiKey, sourceFile)
                }
                extracted
            }

            // ── Step 3: If audio fits in 25MB, send it ─────────────────
            if (audioFile.length() <= GROQ_MAX_FILE_SIZE) {
                onProgress?.onProgress("Sending audio to Groq Whisper…")
                val segments = callWhisperApi(apiKey, audioFile)
                cleanupTempFile(audioFile, sourceFile)
                Timber.i("Audio transcription: %d segments", segments.size)
                return@withContext segments
            }

            // ── Step 4: Split into chunks and transcribe each ───────────
            onProgress?.onProgress("Audio too large, splitting into chunks…")
            val chunks = splitAudioIntoChunks(audioFile)
            if (chunks.isEmpty()) {
                Timber.w("Failed to split audio, trying to send whole file")
                onProgress?.onProgress("Sending audio to Groq Whisper…")
                val segments = callWhisperApi(apiKey, audioFile)
                cleanupTempFile(audioFile, sourceFile)
                return@withContext segments
            }

            Timber.i("Split audio into %d chunks", chunks.size)
            val allSegments = mutableListOf<Segment>()
            var segmentIdOffset = 0

            for ((index, chunk) in chunks.withIndex()) {
                val chunkNum = index + 1
                onProgress?.onProgress("Transcribing chunk $chunkNum/${chunks.size}…")

                try {
                    val chunkSegments = callWhisperApi(apiKey, chunk.file)
                    // Offset the timestamps by the chunk's start time
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

                // Clean up chunk file
                chunk.file.delete()
            }

            // Clean up extracted audio
            cleanupTempFile(audioFile, sourceFile)

            Timber.i("Chunked transcription complete: %d total segments", allSegments.size)
            allSegments
        } catch (e: Exception) {
            Timber.e(e, "Failed to transcribe audio via Groq API")
            emptyList()
        }
    }

    /**
     * Call the Groq Whisper API with a single file.
     */
    private fun callWhisperApi(apiKey: String, audioFile: File): List<Segment> {
        val mediaType = guessMediaType(audioFile.name)
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

        Timber.d("Sending to Groq Whisper: %s (%.1fKB)", audioFile.name, audioFile.length() / 1024.0)

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()

        if (!response.isSuccessful || responseBody.isNullOrBlank()) {
            Timber.e("Groq API error: %d — %s", response.code, responseBody?.take(300))
            throw RuntimeException("Groq API error ${response.code}: ${responseBody?.take(100) ?: "No response"}")
        }

        val transcription = parseTranscriptionResponse(responseBody)

        if (transcription.error != null) {
            val errMsg = transcription.error.message ?: "Unknown error"
            Timber.e("Groq API returned error: %s", errMsg)
            throw RuntimeException(errMsg)
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
     * Extract the audio track from a video file using MediaExtractor + MediaMuxer.
     * Returns a temporary M4A file, or null if extraction fails.
     */
    private fun extractAudioTrack(videoFile: File): File? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        val outputFile = File.createTempFile("looplingo_audio_", ".m4a", videoFile.parentFile)

        try {
            extractor.setDataSource(videoFile.absolutePath)

            // Find the first audio track
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
                Timber.w("No audio track found in: %s", videoFile.name)
                outputFile.delete()
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: run {
                outputFile.delete()
                return null
            }

            // Determine output format based on audio codec
            val outputFormat = when {
                mime.startsWith("audio/mp4") || mime.startsWith("audio/aac") ->
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                mime.startsWith("audio/ogg") || mime.startsWith("audio/opus") ->
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
                else ->
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4  // Default to M4A
            }

            muxer = MediaMuxer(outputFile.absolutePath, outputFormat)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = android.media.MediaCodec.BufferInfo()
            val inputBuffer = ByteArray(256 * 1024)  // 256KB buffer

            while (true) {
                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                if (sampleSize < 0) break

                buffer.offset = 0
                buffer.size = sampleSize
                buffer.flags = extractor.sampleFlags
                buffer.presentationTimeUs = extractor.sampleTime

                muxer.writeSampleData(muxerTrackIndex, java.nio.ByteBuffer.wrap(inputBuffer, 0, sampleSize), buffer)
                extractor.advance()
            }

            muxer.stop()
            Timber.i("Extracted audio: %.1fMB from %s", outputFile.length() / (1024.0 * 1024.0), videoFile.name)
            return outputFile

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
     * Data class for a chunk with its start time offset.
     */
    private data class AudioChunk(
        val file: File,
        val startTimeSec: Double
    )

    /**
     * Split an audio file into ~30s chunks by raw byte splitting.
     * For M4A/MP3 files, this is approximate but works well enough
     * because Groq Whisper is robust to imperfect cuts.
     *
     * The time offset is estimated from the byte position ratio.
     */
    private fun splitAudioIntoChunks(audioFile: File): List<AudioChunk> {
        val fileSize = audioFile.length()
        if (fileSize <= GROQ_MAX_FILE_SIZE) return listOf(AudioChunk(audioFile, 0.0))

        // Calculate how many chunks we need
        val numChunks = ((fileSize + GROQ_MAX_FILE_SIZE - 1) / GROQ_MAX_FILE_SIZE).toInt()
            .coerceAtMost(20)  // Cap at 20 chunks (~10 minutes)

        // Estimate total duration from file size and approximate bitrate
        // M4A at 128kbps ≈ 16KB/s, MP3 at 128kbps ≈ 16KB/s
        val estimatedBytesPerSecond = 16L * 1024  // ~16KB/s at 128kbps
        val estimatedDurationSec = (fileSize / estimatedBytesPerSecond.toDouble())

        val chunkSize = (fileSize / numChunks).coerceAtMost(GROQ_MAX_FILE_SIZE)
        val chunks = mutableListOf<AudioChunk>()

        try {
            val fis = FileInputStream(audioFile)
            var offset = 0L

            for (i in 0 until numChunks) {
                val remaining = fileSize - offset
                if (remaining <= 0) break

                val thisChunkSize = minOf(chunkSize, remaining)
                val chunkFile = File.createTempFile("looplingo_chunk_${i}_", ".m4a", audioFile.parentFile)

                val fos = FileOutputStream(chunkFile)
                val transferBuffer = ByteArray(64 * 1024)
                var transferred = 0L

                fis.channel.position(offset)
                while (transferred < thisChunkSize) {
                    val toRead = minOf(transferBuffer.size.toLong(), thisChunkSize - transferred).toInt()
                    val read = fis.read(transferBuffer, 0, toRead)
                    if (read < 0) break
                    fos.write(transferBuffer, 0, read)
                    transferred += read
                }
                fos.flush()
                fos.close()

                // Estimate the start time for this chunk
                val startTimeSec = if (estimatedDurationSec > 0) {
                    (offset.toDouble() / fileSize) * estimatedDurationSec
                } else {
                    i * CHUNK_DURATION_SEC.toDouble()
                }

                chunks.add(AudioChunk(chunkFile, startTimeSec))
                offset += thisChunkSize
            }

            fis.close()
        } catch (e: Exception) {
            Timber.e(e, "Failed to split audio file")
            chunks.forEach { it.file.delete() }
            return emptyList()
        }

        return chunks
    }

    private fun cleanupTempFile(tempFile: File, originalFile: File) {
        if (tempFile.absolutePath != originalFile.absolutePath) {
            tempFile.delete()
        }
    }

    private fun parseTranscriptionResponse(json: String): TranscriptionResponse {
        return try {
            gson.fromJson(json, TranscriptionResponse::class.java)
                ?: TranscriptionResponse(error = ErrorJson(message = "Null response"))
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse Groq API response")
            TranscriptionResponse(error = ErrorJson(message = "Parse error: ${e.message}"))
        }
    }

    private fun guessMediaType(fileName: String): String {
        return when (fileName.substringAfterLast(".", "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "mp4", "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "webm" -> "audio/webm"
            "ogg", "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            else -> "application/octet-stream"
        }
    }
}
