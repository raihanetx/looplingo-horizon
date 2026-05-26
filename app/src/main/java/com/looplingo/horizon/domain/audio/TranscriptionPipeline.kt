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
        onProgress?.onProgress("[Step 1/2] Transcribing audio…")
        val segments = transcribeAudio(context, apiKey, filePath, language, onProgress)

        if (segments.isEmpty()) {
            throw com.looplingo.horizon.data.remote.SubtitleException("No speech detected for transcription — cannot translate")
        }

        onProgress?.onProgress("[Step 1/2] ✓ %d segments transcribed".format(segments.size))

        onProgress?.onProgress("[Step 2/2] Translating to ${chatTranslator.languageName(targetLanguage)}…")
        val translatedTexts = chatTranslator.translateSegmentsViaChat(apiKey, segments, targetLanguage)

        onProgress?.onProgress("[Step 2/2] ✓ Translation complete!")

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

        Timber.i("═══ TRANSCRIPTION PIPELINE v2.0 ═══")
        Timber.i("Input: %s, Language: %s", filePath.take(80), language)

        onProgress?.onProgress("[Step 0] Checking API key…")
        try {
            validateApiKey(apiKey)
            onProgress?.onProgress("[Step 0] API key valid ✓")
        } catch (e: Exception) {
            val msg = e.message ?: "API key check failed"
            onProgress?.onProgress("[Step 0] ✗ API KEY INVALID: $msg")
            throw com.looplingo.horizon.data.remote.ApiKeyException(msg)
        }

        onProgress?.onProgress("[Step 0] Resolving file…")
        val (sourceFile, cleanupSource) = fileResolver.resolveToReadableFile(context, filePath)

        try {
            if (!sourceFile.exists() || sourceFile.length() == 0L) {
                throw com.looplingo.horizon.data.remote.SubtitleException("Cannot read file: ${sourceFile.name}")
            }

            val sourceSizeMB = sourceFile.length() / (1024.0 * 1024.0)
            val isAudio = audioPreprocessor.isAudioFile(sourceFile)
            onProgress?.onProgress("[Step 0] File: ${sourceFile.name} (%.2fMB, %s)".format(
                sourceSizeMB, if (isAudio) "AUDIO" else "VIDEO"))

            onProgress?.onProgress("[Step 1] Pre-processing to 16KHz mono AAC…")
            Timber.i("Step 1: Pre-processing to 16KHz mono AAC")

            val preprocessed = audioPreprocessor.preProcessTo16kHzMonoAac(context, sourceFile)
            if (preprocessed != null) {
                val ppSizeKB = preprocessed.length() / 1024.0
                onProgress?.onProgress("[Step 1] Pre-processed: %.1fKB (16KHz mono AAC)".format(ppSizeKB))
                Timber.i("Pre-processed: %.1fKB (was %.2fMB)", ppSizeKB, sourceSizeMB)

                if (preprocessed.length() <= com.looplingo.horizon.data.remote.WhisperApiClient.GROQ_MAX_FILE_SIZE) {
                    onProgress?.onProgress("[Step 1] Sending to Whisper (%.1fKB)…".format(ppSizeKB))
                    try {
                        val result = whisperApiClient.callWhisperApi(apiKey, preprocessed, language)
                        if (result.isNotEmpty()) {
                            preprocessed.delete()
                            onProgress?.onProgress("[Step 1] ✓ %d segments!".format(result.size))
                            Timber.i("═══ SUCCESS: %d segments from pre-processed audio ═══", result.size)
                            return@withContext refineSegmentsWithVad(filePath, audioChunker.filterLowQualitySegments(result).kept, onProgress)
                        }
                        onProgress?.onProgress("[Step 1] No speech detected — trying without pre-process…")
                        Timber.w("Pre-processed: no speech, trying raw extraction")
                    } catch (e: com.looplingo.horizon.data.remote.ApiKeyException) {
                        preprocessed.delete()
                        throw e
                    } catch (e: Exception) {
                        onProgress?.onProgress("[Step 1] API error: ${e.message?.take(80)}")
                        Timber.w(e, "Pre-processed audio failed")
                    }
                    preprocessed.delete()
                } else {
                    onProgress?.onProgress("[Step 1] Pre-processed file too large (%.1fKB) → chunking".format(ppSizeKB))
                    val result = chunkedTranscriber.chunkAndTranscribe(context, apiKey, preprocessed, language, onProgress)
                    preprocessed.delete()
                    if (result.isNotEmpty()) {
                        return@withContext refineSegmentsWithVad(filePath, audioChunker.filterLowQualitySegments(result).kept, onProgress)
                    }
                }
            } else {
                onProgress?.onProgress("[Step 1] Pre-processing failed — trying extraction")
            }

            onProgress?.onProgress("[Step 2] Extracting audio track…")
            Timber.i("Step 2: Extract + pre-process pipeline")

            val extracted = audioPreprocessor.extractAudioTrack(context, sourceFile)
            if (extracted != null) {
                val extractedKB = extracted.length() / 1024.0
                onProgress?.onProgress("[Step 2] Extracted: %.1fKB".format(extractedKB))

                if (extracted.length() <= com.looplingo.horizon.data.remote.WhisperApiClient.GROQ_MAX_FILE_SIZE) {
                    onProgress?.onProgress("[Step 2] Sending extracted audio with correct MIME…")
                    try {
                        val result = whisperApiClient.callWhisperApi(apiKey, extracted, language)
                        if (result.isNotEmpty()) {
                            onProgress?.onProgress("[Step 2] ✓ %d segments from raw extraction!".format(result.size))
                            Timber.i("═══ SUCCESS: %d segments from extracted audio ═══", result.size)
                            extracted.delete()
                            return@withContext refineSegmentsWithVad(filePath, audioChunker.filterLowQualitySegments(result).kept, onProgress)
                        }
                    } catch (e: com.looplingo.horizon.data.remote.ApiKeyException) {
                        extracted.delete()
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "Raw extraction send failed")
                    }
                }

                onProgress?.onProgress("[Step 2] Pre-processing extracted audio…")
                val ppExtracted = audioPreprocessor.preProcessTo16kHzMonoAac(context, extracted)

                if (ppExtracted != null) {
                    extracted.delete()
                    val ppSizeKB = ppExtracted.length() / 1024.0
                    onProgress?.onProgress("[Step 2] Pre-processed: %.1fKB".format(ppSizeKB))

                    if (ppExtracted.length() <= com.looplingo.horizon.data.remote.WhisperApiClient.GROQ_MAX_FILE_SIZE) {
                        try {
                            val result = whisperApiClient.callWhisperApi(apiKey, ppExtracted, language)
                            ppExtracted.delete()
                            if (result.isNotEmpty()) {
                                onProgress?.onProgress("[Step 2] ✓ %d segments!".format(result.size))
                                return@withContext refineSegmentsWithVad(filePath, audioChunker.filterLowQualitySegments(result).kept, onProgress)
                            }
                        } catch (e: com.looplingo.horizon.data.remote.ApiKeyException) {
                            ppExtracted.delete()
                            throw e
                        } catch (e: Exception) {
                            Timber.w(e, "Pre-processed extraction failed")
                        }
                        ppExtracted.delete()
                    } else {
                        val result = chunkedTranscriber.chunkAndTranscribe(context, apiKey, ppExtracted, language, onProgress)
                        ppExtracted.delete()
                        if (result.isNotEmpty()) return@withContext refineSegmentsWithVad(filePath, audioChunker.filterLowQualitySegments(result).kept, onProgress)
                    }
                } else {
                    if (extracted.exists()) {
                        val result = chunkedTranscriber.chunkAndTranscribe(context, apiKey, extracted, language, onProgress)
                        extracted.delete()
                        if (result.isNotEmpty()) return@withContext refineSegmentsWithVad(filePath, result, onProgress)
                    }
                }
            }

            onProgress?.onProgress("[Step 3] FALLBACK: Decoding to 16KHz mono WAV + normalize…")
            Timber.i("Step 3: FALLBACK — 16KHz mono WAV with normalization")

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
            var lastDroppedPcmStats: com.looplingo.horizon.data.remote.PcmAnalysisResult? = null
            for (chunk in limitedChunks) {
                val stats = wavProcessor.analyzeWavPcm(chunk.file)
                if (stats.meanAbsSample < 10 && stats.nonZeroPercent < 1.0) {
                    droppedSilentChunks++
                    lastDroppedPcmStats = stats
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
                val pcmInfo = lastDroppedPcmStats?.let { " PCM: meanAbs=${"%.1f".format(it.meanAbsSample)}, nonZero=${"%.1f".format(it.nonZeroPercent)}%" } ?: ""
                throw com.looplingo.horizon.data.remote.SubtitleException("All audio chunks were silent — no speech in this file.$pcmInfo")
            }

            val result = chunkedTranscriber.transcribeChunksWithOverlap(apiKey, normalizedChunks, language, onProgress)
            if (result.isEmpty()) {
                val lastResp = whisperApiClient.getLastWhisperResponse()
                val fallbackText = extractTextFromWhisperResponse(lastResp)
                if (fallbackText.isNotBlank()) {
                    Timber.w("Transcription returned empty segments but Whisper response has text — creating fallback segment from: %s", fallbackText.take(80))
                    val fallbackSegment = com.looplingo.horizon.data.remote.Segment(
                        id = 0,
                        text = fallbackText.trim(),
                        startSec = 0.0,
                        endSec = normalizedChunks.firstOrNull()?.durationSec ?: 0.0,
                        noSpeechProb = 0.0,
                        avgLogprob = 0.0
                    )
                    return@withContext listOf(fallbackSegment)
                }
                val chunkDiag = chunkedTranscriber.lastDiagnostics
                val pcmInfo = if (droppedSilentChunks > 0) {
                    val s = lastDroppedPcmStats
                    " Dropped $droppedSilentChunks silent chunks" + (s?.let { " (meanAbs=${"%.1f".format(it.meanAbsSample)}, nonZero=${"%.1f".format(it.nonZeroPercent)}%)" } ?: "")
                } else ""
                val detail = buildString {
                    append("No speech detected after all pipeline steps (3/3 WAV fallback).")
                    append(" File: ${sourceFile.name} (%.2fMB)".format(sourceSizeMB))
                    append(" Chunks: ${normalizedChunks.size}")
                    if (chunkDiag.isNotBlank()) append(" Transcriber: $chunkDiag")
                    append(" LastAPI: ${lastResp.take(200)}")
                    if (pcmInfo.isNotBlank()) append(pcmInfo)
                }
                throw com.looplingo.horizon.data.remote.SubtitleException(detail)
            }

            Timber.i("═══ SUCCESS (fallback): %d segments ═══", result.size)
            refineSegmentsWithVad(filePath, audioChunker.filterLowQualitySegments(result).kept, onProgress)

        } finally {
            cleanupSource()
        }
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
