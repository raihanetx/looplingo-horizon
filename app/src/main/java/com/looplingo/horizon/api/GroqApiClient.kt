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
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Client for the Groq Whisper API — speech-to-text transcription.
 *
 * Smart pipeline for handling large audio/video files:
 *  1. If file is audio-only and under 25MB → send directly
 *  2. If file is a video → extract audio track as M4A
 *  3. If audio is still over 25MB → split into ~30s chunks using MediaExtractor+MediaMuxer
 *     (format-aware splitting that produces valid playable audio)
 *  4. Send each chunk to Groq Whisper with progress updates
 *  5. Merge all segments with proper time offsets
 */
class GroqApiClient {

    companion object {
        private const val GROQ_MAX_FILE_SIZE = 25L * 1024 * 1024  // 25MB
        private const val CHUNK_DURATION_US = 30L * 1000 * 1000   // 30s in microseconds
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
     *  - Audio still over 25MB → split into 30s chunks using format-aware splitting
     *    → transcribe each → merge with time offsets
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

        Timber.i("Starting transcription: %s (%.1fMB)", sourceFile.name, sourceFile.length() / (1024.0 * 1024.0))

        try {
            val ext = sourceFile.extension.lowercase()
            val isAudioOnly = ext in setOf("mp3", "m4a", "wav", "ogg", "flac", "aac", "opus", "wma", "3gp")

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
                    Timber.w("Failed to extract audio, trying raw file")
                    if (sourceFile.length() <= GROQ_MAX_FILE_SIZE) {
                        onProgress?.onProgress("Sending file to Groq Whisper…")
                        return@withContext callWhisperApi(apiKey, sourceFile)
                    } else {
                        Timber.e("File too large and audio extraction failed")
                        return@withContext emptyList()
                    }
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

            // ── Step 4: Split into format-aware chunks and transcribe each ──
            onProgress?.onProgress("Splitting audio into 30s chunks…")
            val chunks = splitAudioIntoTimedChunks(audioFile)
            if (chunks.isEmpty()) {
                Timber.w("Format-aware split failed, trying byte-level split")
                val byteChunks = splitAudioByBytes(audioFile)
                if (byteChunks.isEmpty()) {
                    Timber.e("All splitting methods failed")
                    cleanupTempFile(audioFile, sourceFile)
                    return@withContext emptyList()
                }
                return@withContext transcribeChunks(apiKey, byteChunks, audioFile, sourceFile, onProgress)
            }

            val result = transcribeChunks(apiKey, chunks, audioFile, sourceFile, onProgress)
            result
        } catch (e: Exception) {
            Timber.e(e, "Failed to transcribe audio via Groq API")
            emptyList()
        }
    }

    /**
     * Transcribe a list of chunks and merge results.
     */
    private suspend fun transcribeChunks(
        apiKey: String,
        chunks: List<AudioChunk>,
        audioFile: File,
        sourceFile: File,
        onProgress: ProgressCallback?
    ): List<Segment> = withContext(Dispatchers.IO) {
        Timber.i("Split audio into %d chunks", chunks.size)
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

            // Clean up chunk file
            chunk.file.delete()
        }

        // Clean up extracted audio
        cleanupTempFile(audioFile, sourceFile)

        Timber.i("Chunked transcription complete: %d total segments", allSegments.size)
        allSegments
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
            Timber.e("Groq API error: %d — %s", response.code, responseBody?.take(500))
            throw RuntimeException("Groq API error ${response.code}: ${responseBody?.take(200) ?: "No response"}")
        }

        Timber.d("Groq API response (%d bytes): %s", responseBody.length, responseBody.take(200))

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
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }

            muxer = MediaMuxer(outputFile.absolutePath, outputFormat)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = android.media.MediaCodec.BufferInfo()
            val inputBuffer = ByteBuffer.allocate(256 * 1024)

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
     * Format-aware audio splitting using MediaExtractor + MediaMuxer.
     *
     * This produces valid, playable audio chunks that Whisper can properly
     * transcribe, unlike byte-splitting which corrupts audio frames.
     *
     * Each chunk is approximately CHUNK_DURATION_US (30s) long.
     */
    private fun splitAudioIntoTimedChunks(audioFile: File): List<AudioChunk> {
        if (audioFile.length() <= GROQ_MAX_FILE_SIZE) {
            return listOf(AudioChunk(audioFile, 0.0))
        }

        val extractor = MediaExtractor()
        val chunks = mutableListOf<AudioChunk>()

        try {
            extractor.setDataSource(audioFile.absolutePath)

            // Find the audio track
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
                Timber.w("No audio track found for chunking: %s", audioFile.name)
                return emptyList()
            }

            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            val durationUs = audioFormat.getLong(MediaFormat.KEY_DURATION)

            if (durationUs <= 0) {
                Timber.w("Could not determine audio duration, falling back")
                return emptyList()
            }

            val totalDurationSec = durationUs / 1_000_000.0
            val numChunks = ((durationUs + CHUNK_DURATION_US - 1) / CHUNK_DURATION_US).toInt()
                .coerceIn(1, 30)

            Timber.i("Audio duration: %.1fs, splitting into %d chunks", totalDurationSec, numChunks)

            val outputFormat = when {
                mime.startsWith("audio/mp4") || mime.startsWith("audio/aac") ->
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                mime.startsWith("audio/ogg") || mime.startsWith("audio/opus") ->
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
                else -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }

            val ext = when (outputFormat) {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG -> ".ogg"
                else -> ".m4a"
            }

            extractor.selectTrack(audioTrackIndex)

            val inputBuffer = ByteBuffer.allocate(256 * 1024)
            val bufferInfo = android.media.MediaCodec.BufferInfo()

            // Read ALL samples into memory first so we can split by time
            val allSamples = mutableListOf<SampleData>()
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

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

            Timber.d("Read %d audio samples", allSamples.size)

            if (allSamples.isEmpty()) {
                Timber.w("No audio samples found")
                return emptyList()
            }

            // Split samples into chunks by time
            for (chunkIndex in 0 until numChunks) {
                val chunkStartUs = chunkIndex * CHUNK_DURATION_US
                val chunkEndUs = (chunkIndex + 1) * CHUNK_DURATION_US

                // Find samples that fall within this chunk's time range
                // Include a small overlap before chunk start for context (up to 500ms before)
                val contextStartUs = (chunkStartUs - 500_000).coerceAtLeast(0)

                val chunkSamples = allSamples.filter {
                    it.presentationTimeUs >= contextStartUs && it.presentationTimeUs < chunkEndUs
                }

                if (chunkSamples.isEmpty()) continue

                // Create the chunk file
                val chunkFile = File.createTempFile("looplingo_chunk_${chunkIndex}_", ext, audioFile.parentFile)
                var muxer: MediaMuxer? = null

                try {
                    muxer = MediaMuxer(chunkFile.absolutePath, outputFormat)
                    val muxerTrackIndex = muxer.addTrack(audioFormat!!)
                    muxer.start()

                    for (sample in chunkSamples) {
                        val buf = ByteBuffer.allocate(sample.data.size)
                        buf.put(sample.data)
                        buf.flip()

                        bufferInfo.offset = 0
                        bufferInfo.size = sample.data.size
                        bufferInfo.flags = sample.flags
                        // Adjust presentation time relative to chunk start
                        bufferInfo.presentationTimeUs = sample.presentationTimeUs - contextStartUs

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
            Timber.e(e, "Format-aware chunk splitting failed")
            chunks.forEach { it.file.delete() }
            return emptyList()
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }

        return chunks
    }

    /**
     * Holds raw sample data for format-aware chunking.
     */
    private data class SampleData(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int
    )

    /**
     * Fallback byte-level splitting when format-aware splitting fails.
     * This is less reliable but works as a last resort.
     */
    private fun splitAudioByBytes(audioFile: File): List<AudioChunk> {
        val fileSize = audioFile.length()
        if (fileSize <= GROQ_MAX_FILE_SIZE) return listOf(AudioChunk(audioFile, 0.0))

        val numChunks = ((fileSize + GROQ_MAX_FILE_SIZE - 1) / GROQ_MAX_FILE_SIZE).toInt()
            .coerceAtMost(20)

        // Try to get duration from MediaExtractor for better time estimation
        var estimatedDurationSec = fileSize / (16.0 * 1024) // fallback: 128kbps ≈ 16KB/s
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(audioFile.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    val dur = format.getLong(MediaFormat.KEY_DURATION)
                    if (dur > 0) {
                        estimatedDurationSec = dur / 1_000_000.0
                    }
                    break
                }
            }
            extractor.release()
        } catch (e: Exception) {
            Timber.w(e, "Could not get audio duration for time estimation")
        }

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

                val startTimeSec = if (estimatedDurationSec > 0) {
                    (offset.toDouble() / fileSize) * estimatedDurationSec
                } else {
                    i * 30.0
                }

                chunks.add(AudioChunk(chunkFile, startTimeSec))
                offset += thisChunkSize
            }

            fis.close()
        } catch (e: Exception) {
            Timber.e(e, "Byte-level splitting failed")
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
            Timber.e(e, "Failed to parse Groq API response: %s", json.take(200))
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
            "3gp" -> "audio/3gpp"
            else -> "application/octet-stream"
        }
    }
}
