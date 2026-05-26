package com.looplingo.horizon.data.remote

import android.content.Context
import com.looplingo.horizon.domain.audio.AudioChunker
import com.looplingo.horizon.domain.audio.AudioPreprocessor
import com.looplingo.horizon.domain.audio.FileResolver
import com.looplingo.horizon.domain.audio.TranscriptionPipeline
import com.looplingo.horizon.domain.audio.WavProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Client for the Groq Whisper API — speech-to-text transcription & translation.
 *
 * Architecture (v2.0 — Optimized Pipeline):
 *
 *   RESEARCH-BACKED DESIGN DECISIONS:
 *
 *   1. Groq Whisper downsamples all audio to 16KHz mono internally.
 *      → Pre-process to 16KHz mono AAC = 75% file size reduction, zero accuracy loss.
 *      → 19.5MB at 16KHz mono 64kbps AAC ≈ 41 minutes of audio per request.
 *      → Most files fit in ONE request, no chunking needed.
 *
 *   2. M4A files must use audio/mp4 MIME type (IANA standard).
 *      → Never lie about MIME types — the video/mp4 hack caused "no speech detected".
 *
 *   3. Always specify language parameter when user selects one.
 *      → Whisper analyzes first 30s for language auto-detect — fails on quiet/noisy starts.
 *
 *   4. whisper-large-v3 for maximum accuracy (not turbo).
 *      → 10.3% WER vs 12% for turbo. Worth the cost for language learning.
 *
 *   5. Chunking uses overlap + prompt chaining (Groq cookbook recommendation).
 *      → Prevents word cutoff at boundaries. Previous context as prompt maintains consistency.
 *
 *   6. Parallel chunk processing with rate-limit-aware concurrency.
 *      → 3-5x faster than sequential. Groq allows 20 RPM.
 *
 *   7. verbose_json response with no_speech_prob filtering.
 *      → Remove hallucinated segments where no_speech_prob > 0.6.
 *
 *   8. Translation endpoint (/v1/audio/translations) for any-language → English.
 *      → whisper-large-v3 supports this. App is called "Horizon Loop" — needs translation.
 *
 *   PIPELINE (2 steps, each with pre-processing):
 *
 *   Step 1: AUDIO FILE ≤19.5MB → Pre-process to 16KHz mono → Send to Whisper
 *   Step 2: VIDEO FILE → Extract audio → Pre-process → Send to Whisper
 *            If >19.5MB → Chunk with overlap + prompt chaining → Parallel transcribe
 *
 *   Fallback: If pre-processing fails, try sending extracted audio as-is (correct MIME).
 *   Fallback 2: If that fails, decode to 16KHz mono WAV + normalize → Send.
 *
 *   Resource optimization:
 *   - Pre-processing is a ONE-TIME cost (not during playback)
 *   - 16KHz mono AAC at 64kbps = ~480KB/min → tiny uploads, fast API calls
 *   - No 72MB temp file copies — use MediaExtractor with content:// URIs directly
 *   - Temp files cleaned up immediately after use
 */
@javax.inject.Singleton
class GroqApiClient @javax.inject.Inject constructor(
    private val vadEngine: com.looplingo.horizon.domain.audio.vad.VadEngine,
    private val audioPreprocessor: AudioPreprocessor,
    private val audioChunker: AudioChunker,
    private val chatTranslator: ChatTranslator,
    private val fileResolver: FileResolver,
    private val whisperApiClient: WhisperApiClient,
    private val wavProcessor: WavProcessor,
    private val transcriptionPipeline: TranscriptionPipeline
) {

    companion object {
        /**
         * Clean up old temp files from the cache directory.
         * Safe to call from anywhere — does not require instance state.
         * Call this on app startup to prevent temp file accumulation.
         */
        fun cleanupTempFiles(context: Context) {
            val cacheDir = context.cacheDir
            val now = System.currentTimeMillis()
            val MAX_AGE_MS = 24 * 60 * 60 * 1000L  // 24 hours

            var cleaned = 0
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("looplingo_") && (now - file.lastModified() > MAX_AGE_MS)) {
                    file.delete()
                    cleaned++
                }
            }
            if (cleaned > 0) {
                Timber.i("Cleaned up %d old temp files from cache", cleaned)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // DELEGATED METHODS — TranscriptionPipeline
    // ══════════════════════════════════════════════════════════════════

    suspend fun transcribeAudio(
        context: Context,
        apiKey: String,
        filePath: String,
        language: String = "auto",
        onProgress: ProgressCallback? = null
    ): List<Segment> = transcriptionPipeline.transcribeAudio(context, apiKey, filePath, language, onProgress)

    suspend fun transcribeAndTranslate(
        context: Context,
        apiKey: String,
        filePath: String,
        language: String = "auto",
        targetLanguage: String,
        onProgress: ProgressCallback? = null
    ): TranscriptionWithTranslation = transcriptionPipeline.transcribeAndTranslate(
        context, apiKey, filePath, language, targetLanguage, onProgress
    )

    fun getLastWhisperResponse(): String = transcriptionPipeline.getLastWhisperResponse()

    // ══════════════════════════════════════════════════════════════════
    // TRANSLATION ENDPOINT
    // ══════════════════════════════════════════════════════════════════

    /**
     * Translate audio to English text using Groq Whisper's translation endpoint.
     * Only supported by whisper-large-v3 (not turbo).
     * Translates any language → English.
     */
    suspend fun translateAudio(
        context: Context,
        apiKey: String,
        filePath: String,
        onProgress: ProgressCallback? = null
    ): List<Segment> = withContext(Dispatchers.IO) {
        whisperApiClient.validateInputs(apiKey, filePath)

        onProgress?.onProgress("[Translate] Pre-processing audio…")
        val (sourceFile, cleanupSource) = fileResolver.resolveToReadableFile(context, filePath)

        try {
            val preprocessed = audioPreprocessor.preProcessTo16kHzMonoAac(context, sourceFile)
                ?: audioPreprocessor.extractAudioTrack(context, sourceFile)
                ?: sourceFile.let { if (audioPreprocessor.isAudioFile(it)) it else null }

            if (preprocessed == null) {
                throw SubtitleException("Cannot extract audio for translation")
            }

            onProgress?.onProgress("[Translate] Sending to Groq translation API…")
            Timber.i("Translation: sending %s (%.1fKB)", preprocessed.name, preprocessed.length() / 1024.0)

            val result = whisperApiClient.callWhisperApi(apiKey, preprocessed, "en", isTranslation = true)
            if (preprocessed != sourceFile) preprocessed.delete()

            if (result.isEmpty()) {
                throw SubtitleException("Translation returned no results. The audio may not contain speech.")
            }

            onProgress?.onProgress("[Translate] ✓ %d translated segments!".format(result.size))
            transcriptionPipeline.refineSegmentsWithVad(filePath, audioChunker.filterLowQualitySegments(result).kept, onProgress)
        } finally {
            cleanupSource()
        }
    }

}
