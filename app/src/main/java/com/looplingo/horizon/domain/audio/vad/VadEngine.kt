package com.looplingo.horizon.domain.audio.vad

import android.content.Context
import com.looplingo.horizon.data.remote.ProgressCallback
import com.looplingo.horizon.data.remote.Segment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class VadEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioDecoder: AudioDecoder,
    private val silenceRefiner: SilenceRefiner,
    private val speechBoundaryDetector: SpeechBoundaryDetector
) {

    companion object {
        const val SAMPLE_RATE = 16000
    }

    data class RefinedSegment(
        val originalSegment: Segment,
        val vadStartMs: Long,
        val vadEndMs: Long,
        val confidence: Float,
        val method: String
    )

    suspend fun refineSegments(
        filePath: String,
        segments: List<Segment>,
        onProgress: ProgressCallback? = null
    ): List<RefinedSegment> = withContext(Dispatchers.IO) {

        if (segments.isEmpty()) return@withContext emptyList()

        Timber.i("═══ VAD v3.0 (Silence Midpoint): %d Whisper segments ═══", segments.size)
        onProgress?.onProgress("[VAD] Loading audio for boundary analysis…")

        val pcmData = audioDecoder.decodeToPcmFloatArray(filePath)
        if (pcmData == null || pcmData.isEmpty()) {
            Timber.w("VAD: Could not decode audio — keeping Whisper timestamps")
            return@withContext segments.map {
                RefinedSegment(it, it.startMs, it.endMs, 0.5f, "whisper_fallback_decode")
            }
        }

        val audioDurationMs = pcmData.size.toLong() * 1000 / SAMPLE_RATE
        Timber.i("VAD: Decoded %d samples (%.1fs)", pcmData.size, pcmData.size / SAMPLE_RATE.toFloat())
        onProgress?.onProgress("[VAD] Detecting silence boundaries…")

        val refined = if (segments.size == 1) {
            listOf(silenceRefiner.refineSingleSegment(segments[0], pcmData, SAMPLE_RATE, audioDurationMs))
        } else {
            silenceRefiner.refineWithSilenceMidpoints(segments, pcmData, SAMPLE_RATE, audioDurationMs)
        }

        val postProcessed = speechBoundaryDetector.postProcessRefinedSegments(refined)

        var adjustedCount = 0
        var totalStartAdjust = 0L
        var totalEndAdjust = 0L
        for (r in postProcessed) {
            val startDiff = abs(r.vadStartMs - r.originalSegment.startMs)
            val endDiff = abs(r.vadEndMs - r.originalSegment.endMs)
            if (startDiff > 10 || endDiff > 10) adjustedCount++
            totalStartAdjust += startDiff
            totalEndAdjust += endDiff
        }
        Timber.i("VAD: Refined %d/%d segments (avg start: %dms, avg end: %dms adjust)",
            adjustedCount, segments.size,
            if (segments.isNotEmpty()) totalStartAdjust / segments.size else 0,
            if (segments.isNotEmpty()) totalEndAdjust / segments.size else 0)

        onProgress?.onProgress("[VAD] ✓ Boundaries refined ($adjustedCount adjusted)")
        postProcessed
    }
}
