package com.looplingo.horizon.domain.audio

import android.content.Context
import android.net.Uri
import com.looplingo.horizon.data.remote.AudioChunk
import com.looplingo.horizon.data.remote.PcmAnalysisResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import timber.log.Timber

@javax.inject.Singleton
class TranscriptionPipeline @javax.inject.Inject constructor(
    private val vadEngine: com.looplingo.horizon.domain.audio.vad.VadEngine,
    private val audioPreprocessor: AudioPreprocessor,
    private val audioChunker: AudioChunker,
    private val chatTranslator: com.looplingo.horizon.data.remote.ChatTranslator,
    private val fileResolver: FileResolver,
    private val whisperApiClient: com.looplingo.horizon.data.remote.WhisperApiClient,
    private val wavProcessor: WavProcessor,
    private val chunkedTranscriber: com.looplingo.horizon.data.remote.ChunkedTranscriber
) {

    companion object {
        private const val GROQ_MODELS_URL = "https://api.groq.com/openai/v1/models"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun getLastWhisperResponse(): String = whisperApiClient.getLastWhisperResponse()

    suspend fun transcribeAndTranslate(
        context: Context,
        apiKey: String,
        filePath: String,
        language: String = "auto",
        targetLanguage: String,
        onProgress: com.looplingo.horizon.data.remote.ProgressCallback? = null
    ): com.looplingo.horizon.data.remote.TranscriptionWithTranslation = withContext(Dispatchers.IO) {
        onProgress?.onProgressUpdate("Transcribing English", 5, "Preparing audio...")
        onProgress?.onProgress("[Step 1/2] Transcribing audio…")
        val segments = transcribeAudio(context, apiKey, filePath, language, onProgress)

        if (segments.isEmpty()) {
            throw com.looplingo.horizon.data.remote.SubtitleException("No speech detected for transcription — cannot translate")
        }

        onProgress?.onProgressUpdate("Transcribing English", 50, "${segments.size} segments found")
        onProgress?.onProgress("[Step 1/2] ✓ %d segments transcribed".format(segments.size))

        onProgress?.onProgressUpdate("Translating to Bangla", 55, "Sending ${segments.size} segments to Chat API...")
        onProgress?.onProgress("[Step 2/2] Translating to ${chatTranslator.languageName(targetLanguage)}…")
        val translatedTexts = chatTranslator.translateSegmentsViaChat(apiKey, segments, targetLanguage, context)

        if (translatedTexts.isEmpty()) {
            Timber.w("Translation returned 0 results for %d segments. Chat API may have failed or returned unparseable content.", segments.size)
            onProgress?.onProgressUpdate("Translating to Bangla", 95, "Warning: 0 translations returned")
        } else {
            Timber.i("Translation returned %d/%d segment translations", translatedTexts.size, segments.size)
            onProgress?.onProgressUpdate("Complete!", 100, "${segments.size} segments, ${translatedTexts.size} translations")
        }

        onProgress?.onProgress("[Step 2/2] ✓ Translation complete! (%d/%d translated)".format(translatedTexts.size, segments.size))

        com.looplingo.horizon.data.remote.TranscriptionWithTranslation(
            segments = segments,
            translatedTexts = translatedTexts,
            sourceLanguage = if (language == "auto") "auto" else language,
            targetLanguage = targetLanguage
        )
    }

    suspend fun transcribeAudio(
        context: Context,
        apiKey: String,
        filePath: String,
        language: String = "auto",
        onProgress: com.looplingo.horizon.data.remote.ProgressCallback? = null
    ): List<com.looplingo.horizon.data.remote.Segment> = withContext(Dispatchers.IO) {
        whisperApiClient.validateInputs(apiKey, filePath)

        Timber.i("═══ TRANSCRIPTION PIPELINE v3.0 ═══")
        Timber.i("Input: %s, Language: %s", filePath.take(80), language)

        onProgress?.onProgress("[Step 0] Checking API key…")
        onProgress?.onProgressUpdate("Transcribing English", 5, "Validating API key...")
        try {
            validateApiKey(apiKey)
            onProgress?.onProgress("[Step 0] API key valid ✓")
        } catch (e: Exception) {
            val msg = e.message ?: "API key check failed"
            onProgress?.onProgress("[Step 0] ✗ API KEY INVALID: $msg")
            throw com.looplingo.horizon.data.remote.ApiKeyException(msg)
        }

        onProgress?.onProgress("[Step 0] Resolving file…")
        onProgress?.onProgressUpdate("Transcribing English", 8, "Resolving audio file...")
        val (sourceFile, cleanupSource) = fileResolver.resolveToReadableFile(context, filePath)

        try {
            if (!sourceFile.exists() || sourceFile.length() == 0L) {
                throw com.looplingo.horizon.data.remote.SubtitleException("Cannot read file: ${sourceFile.name}")
            }

            val sourceSizeMB = sourceFile.length() / (1024.0 * 1024.0)
            val isAudio = audioPreprocessor.isAudioFile(sourceFile)
            onProgress?.onProgress("[Step 0] File: ${sourceFile.name} (%.2fMB, %s)".format(
                sourceSizeMB, if (isAudio) "AUDIO" else "VIDEO"))

            // ── Step 1: Get audio file (extract from video or use directly) ──
            val audioFile: File
            val needsCleanup: Boolean

            if (isAudio) {
                onProgress?.onProgress("[Step 1] Audio file — sending directly")
                audioFile = sourceFile
                needsCleanup = false
            } else {
                onProgress?.onProgress("[Step 1] Extracting audio from video…")
                onProgress?.onProgressUpdate("Transcribing English", 15, "Extracting audio track...")
                val extracted = audioPreprocessor.extractAudioTrack(context, sourceFile)
                if (extracted != null) {
                    audioFile = extracted
                    needsCleanup = true
                    onProgress?.onProgress("[Step 1] Extracted: %.1fKB".format(audioFile.length() / 1024.0))
                } else {
                    onProgress?.onProgress("[Step 1] Extraction failed — trying WAV fallback…")
                    return@withContext fallbackToWav(context, apiKey, sourceFile, sourceSizeMB, language, onProgress)
                }
            }

            // ── Step 2: Send to Whisper ──
            return@withContext sendToWhisper(context, apiKey, audioFile, needsCleanup, sourceSizeMB, language, filePath, onProgress)

        } finally {
            cleanupSource()
        }
    }

    private suspend fun sendToWhisper(
        context: Context,
        apiKey: String,
        audioFile: File,
        needsCleanup: Boolean,
        sourceSizeMB: Double,
        language: String,
        originalFilePath: String,
        onProgress: com.looplingo.horizon.data.remote.ProgressCallback? = null
    ): List<com.looplingo.horizon.data.remote.Segment> {
        try {
            if (audioFile.length() <= com.looplingo.horizon.data.remote.WhisperApiClient.GROQ_MAX_FILE_SIZE) {
                val sizeKB = audioFile.length() / 1024.0
                onProgress?.onProgress("[Step 2] Sending to Whisper (%.1fKB)…".format(sizeKB))
                onProgress?.onProgressUpdate("Transcribing English", 25, "Transcribing audio (%.1fKB)...".format(sizeKB))

                val result = whisperApiClient.callWhisperApi(apiKey, audioFile, language)
                if (needsCleanup) audioFile.delete()

                if (result.isNotEmpty()) {
                    onProgress?.onProgressUpdate("Transcribing English", 45, "${result.size} segments detected")
                    onProgress?.onProgress("[Step 2] ✓ %d segments!".format(result.size))
                    Timber.i("═══ SUCCESS: %d segments ═══", result.size)
                    return refineSegmentsWithVad(originalFilePath, audioChunker.filterLowQualitySegments(result).kept, onProgress)
                }

                onProgress?.onProgress("[Step 2] No speech detected")
                throw com.looplingo.horizon.data.remote.SubtitleException(
                    "No speech detected in audio. File: ${audioFile.name} (%.2fMB)".format(sourceSizeMB)
                )
            } else {
                // File > 25MB — chunk and transcribe
                onProgress?.onProgress("[Step 2] File too large (%.1fMB) → chunking…".format(sourceSizeMB))
                onProgress?.onProgressUpdate("Transcribing English", 20, "Splitting large file into chunks...")
                val result = chunkedTranscriber.chunkAndTranscribe(context, apiKey, audioFile, language, onProgress)
                if (needsCleanup) audioFile.delete()

                if (result.isNotEmpty()) {
                    return refineSegmentsWithVad(originalFilePath, audioChunker.filterLowQualitySegments(result).kept, onProgress)
                }

                throw com.looplingo.horizon.data.remote.SubtitleException(
                    "No speech detected after chunking. File: ${audioFile.name} (%.2fMB)".format(sourceSizeMB)
                )
            }
        } catch (e: com.looplingo.horizon.data.remote.ApiKeyException) {
            if (needsCleanup) audioFile.delete()
            throw e
        } catch (e: com.looplingo.horizon.data.remote.SubtitleException) {
            if (needsCleanup) audioFile.delete()
            throw e
        } catch (e: Exception) {
            if (needsCleanup) audioFile.delete()
            Timber.w(e, "Whisper API call failed")
            throw com.looplingo.horizon.data.remote.SubtitleException("Transcription failed: ${e.message}")
        }
    }

    private suspend fun fallbackToWav(
        context: Context,
        apiKey: String,
        sourceFile: File,
        sourceSizeMB: Double,
        language: String,
        onProgress: com.looplingo.horizon.data.remote.ProgressCallback? = null
    ): List<com.looplingo.horizon.data.remote.Segment> {
        onProgress?.onProgress("[Fallback] Decoding to WAV…")
        onProgress?.onProgressUpdate("Transcribing English", 20, "Decoding audio to WAV format...")

        val wavChunks = wavProcessor.decodeTo16kHzMonoWavChunks(context, sourceFile)
        if (wavChunks.isEmpty()) {
            throw com.looplingo.horizon.data.remote.SubtitleException(
                "Could not decode audio. The format may not be supported. Try MP3, M4A, or MP4."
            )
        }

        val limitedChunks = wavChunks.take(AudioChunker.MAX_CHUNKS)
        wavChunks.drop(AudioChunker.MAX_CHUNKS).forEach { it.file.delete() }

        onProgress?.onProgress("Normalizing audio volume…")
        val normalizedChunks = mutableListOf<AudioChunk>()
        var droppedSilentChunks = 0
        for (chunk in limitedChunks) {
            val stats = wavProcessor.analyzeWavPcm(chunk.file)
            if (stats.meanAbsSample < 10 && stats.nonZeroPercent < 1.0) {
                droppedSilentChunks++
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
            throw com.looplingo.horizon.data.remote.SubtitleException("All audio chunks were silent — no speech in this file.")
        }

        val result = chunkedTranscriber.transcribeChunksWithOverlap(apiKey, normalizedChunks, language, onProgress)
        if (result.isEmpty()) {
            val lastResp = whisperApiClient.getLastWhisperResponse()
            val fallbackText = extractTextFromWhisperResponse(lastResp)
            if (fallbackText.isNotBlank()) {
                val totalDuration = normalizedChunks.sumOf { it.durationSec }
                val sentences = whisperApiClient.splitIntoSentences(fallbackText)
                val timePerChar = if (fallbackText.isNotEmpty() && totalDuration > 0) totalDuration / fallbackText.length else 1.0
                var currentTime = 0.0
                val segmentsToCreate = if (sentences.size > 1) sentences else listOf(fallbackText)
                Timber.w("Empty segments but response has text — creating %d fallback segments", segmentsToCreate.size)
                return segmentsToCreate.mapIndexed { index, sentence ->
                    val sentenceDuration = sentence.length * timePerChar
                    val seg = com.looplingo.horizon.data.remote.Segment(
                        id = index,
                        text = sentence.trim(),
                        startSec = currentTime,
                        endSec = currentTime + sentenceDuration,
                        noSpeechProb = 0.0,
                        avgLogprob = 0.0
                    )
                    currentTime += sentenceDuration
                    seg
                }
            }
            throw com.looplingo.horizon.data.remote.SubtitleException(
                "No speech detected. File: ${sourceFile.name} (%.2fMB), Chunks: ${normalizedChunks.size}".format(sourceSizeMB)
            )
        }

        Timber.i("═══ SUCCESS (WAV fallback): %d segments ═══", result.size)
        return refineSegmentsWithVad(sourceFile.absolutePath, audioChunker.filterLowQualitySegments(result).kept, onProgress)
    }

    suspend fun refineSegmentsWithVad(
        filePath: String,
        segments: List<com.looplingo.horizon.data.remote.Segment>,
        onProgress: com.looplingo.horizon.data.remote.ProgressCallback? = null
    ): List<com.looplingo.horizon.data.remote.Segment> {
        if (segments.isEmpty()) return segments

        return try {
            val refined = vadEngine.refineSegments(filePath, segments, onProgress)

            segments.mapIndexed { idx, original ->
                if (idx < refined.size) {
                    val r = refined[idx]
                    com.looplingo.horizon.data.remote.Segment(
                        id = original.id,
                        text = original.text,
                        startSec = r.vadStartMs / 1000.0,
                        endSec = r.vadEndMs / 1000.0,
                        noSpeechProb = original.noSpeechProb,
                        avgLogprob = original.avgLogprob
                    )
                } else {
                    original
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "VAD refinement failed — using Whisper timestamps as-is")
            segments
        }
    }

    private fun normalizeWavFile(wavFile: File, stats: PcmAnalysisResult): File? {
        try {
            val currentPeak = maxOf(kotlin.math.abs(stats.minSample), kotlin.math.abs(stats.maxSample))
            if (currentPeak >= 20000) return wavFile

            val targetPeak = (32767 * 0.9).toInt()
            val gain = targetPeak.toDouble() / maxOf(currentPeak.toDouble(), 1.0)
            Timber.i("Normalizing: peak=%d → target=%d, gain=%.2fx", currentPeak, targetPeak, gain)

            val raf = RandomAccessFile(wavFile, "rw")
            try {
                if (raf.length() < 44) return null
                raf.seek(34)
                val bitsPerSample = raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8)
                if (bitsPerSample != 16) return wavFile

                var dataOffset = -1L
                var dataSize = 0
                raf.seek(12)
                while (raf.filePointer < raf.length() - 8) {
                    val markerBytes = ByteArray(4)
                    raf.read(markerBytes)
                    val marker = String(markerBytes, Charsets.US_ASCII)
                    val chunkSize = raf.read() or (raf.read() shl 8) or (raf.read() shl 16) or (raf.read() shl 24)
                    if (marker == "data") { dataOffset = raf.filePointer; dataSize = chunkSize; break }
                    raf.skipBytes(if (chunkSize % 2 != 0) chunkSize + 1 else chunkSize)
                }

                if (dataOffset < 0 || dataSize <= 0) return null

                val pcmEnd = minOf(dataOffset + dataSize, raf.length())
                raf.seek(dataOffset)
                while (raf.filePointer + 1 < pcmEnd) {
                    val low = raf.readUnsignedByte()
                    val high = raf.readByte().toInt()
                    val sample = (high shl 8) or low
                    val amplified = (sample * gain).toLong()
                    val clamped = amplified.toInt().coerceIn(-32768, 32767)
                    raf.seek(raf.filePointer - 2)
                    raf.write(clamped and 0xFF)
                    raf.write((clamped shr 8) and 0xFF)
                }

                return wavFile
            } finally { raf.close() }
        } catch (e: Exception) {
            Timber.e(e, "Failed to normalize WAV file")
            return wavFile
        }
    }

    private suspend fun validateApiKey(apiKey: String) {
        Timber.i("Validating API key: %s...%s", apiKey.take(8), apiKey.takeLast(4))

        val request = Request.Builder()
            .url(GROQ_MODELS_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        try {
            val response = kotlinx.coroutines.suspendCancellableCoroutine<okhttp3.Response> { cont ->
                val call = client.newCall(request)
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        cont.resumeWith(Result.failure(e))
                    }
                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        cont.resumeWith(Result.success(response))
                    }
                })
            }

            val code = response.code
            val body = try { response.body?.string()?.take(200) } finally { response.close() }

            if (code == 401) throw com.looplingo.horizon.data.remote.ApiKeyException(
                "API key INVALID (HTTP 401). Get a new key at console.groq.com"
            )
            if (code == 403) throw com.looplingo.horizon.data.remote.ApiKeyException(
                "API key FORBIDDEN/EXPIRED (HTTP 403). Get a new key at console.groq.com"
            )
            if (code !in 200..299) {
                Timber.w("API key check got HTTP %d: %s", code, body)
            } else {
                Timber.i("API key valid (HTTP %d)", code)
            }
        } catch (e: com.looplingo.horizon.data.remote.ApiKeyException) { throw e }
        catch (e: Exception) { Timber.w(e, "Could not validate API key (network issue?)") }
    }

    private fun extractTextFromWhisperResponse(rawResponse: String): String {
        if (rawResponse.isBlank() || rawResponse == "(null)") return ""
        return try {
            val start = rawResponse.indexOf("\"text\"")
            if (start < 0) return ""
            val colonPos = rawResponse.indexOf(':', start)
            if (colonPos < 0) return ""
            val quoteStart = rawResponse.indexOf('"', colonPos + 1)
            if (quoteStart < 0) return ""
            var i = quoteStart + 1
            val sb = StringBuilder()
            while (i < rawResponse.length && rawResponse[i] != '"') {
                if (rawResponse[i] == '\\' && i + 1 < rawResponse.length) {
                    i++
                    when (rawResponse[i]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        else -> { sb.append('\\'); sb.append(rawResponse[i]) }
                    }
                } else {
                    sb.append(rawResponse[i])
                }
                i++
            }
            sb.toString().trim()
        } catch (e: Exception) {
            Timber.w(e, "Failed to extract text from Whisper response")
            ""
        }
    }
}
