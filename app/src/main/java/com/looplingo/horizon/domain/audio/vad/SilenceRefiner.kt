package com.looplingo.horizon.domain.audio.vad

import com.looplingo.horizon.data.remote.Segment
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@Singleton
class SilenceRefiner @Inject constructor(
    private val audioDecoder: AudioDecoder,
    private val speechBoundaryDetector: SpeechBoundaryDetector
) {
    companion object {
        private const val FRAME_SIZE_MS = 10
        private const val SEARCH_PADDING_MS = 600L
        private const val MIN_SILENCE_GAP_MS = 40L
    }

    internal fun findSilenceMidpoints(
        refinedPcm: FloatArray,
        sampleRate: Int,
        segments: List<Segment>
    ): List<Long> {
        val audioDurationMs = refinedPcm.size.toLong() * 1000 / sampleRate
        val midpoints = mutableListOf<Long>()

        for (i in 0 until segments.lastIndex) {
            val currentEnd = segments[i].endMs
            val nextStart = segments[i + 1].startMs

            val searchStart = (min(currentEnd, nextStart) - SEARCH_PADDING_MS).coerceAtLeast(0L)
            val searchEnd = (max(currentEnd, nextStart) + SEARCH_PADDING_MS).coerceAtMost(audioDurationMs)

            val startSample = (searchStart * sampleRate / 1000).toInt()
            val endSample = (searchEnd * sampleRate / 1000).toInt().coerceAtMost(refinedPcm.size)

            val midpoint = findSilenceGapMidpoint(refinedPcm, sampleRate, startSample, endSample)

            if (midpoint != null) {
                midpoints.add(midpoint)
                Timber.d("VAD: Boundary between seg %d/%d at %dms (silence midpoint)", i, i + 1, midpoint)
            } else {
                val fallbackMid = (currentEnd + nextStart) / 2
                midpoints.add(fallbackMid)
                Timber.d("VAD: Boundary between seg %d/%d at %dms (fallback midpoint)", i, i + 1, fallbackMid)
            }
        }

        return midpoints
    }

    internal fun findSilenceGapMidpoint(
        pcmData: FloatArray,
        sampleRate: Int,
        startSample: Int,
        endSample: Int
    ): Long? {
        val audioDurationMs = pcmData.size.toLong() * 1000 / sampleRate
        val frameSizeSamples = sampleRate * FRAME_SIZE_MS / 1000

        if (startSample >= endSample) return null

        val energies = mutableListOf<Pair<Long, Float>>()
        var pos = startSample
        while (pos + frameSizeSamples <= endSample) {
            var sumSq = 0.0
            for (i in 0 until frameSizeSamples) {
                sumSq += pcmData[pos + i] * pcmData[pos + i]
            }
            val rms = sqrt(sumSq / frameSizeSamples).toFloat()
            val timeMs = pos.toLong() * 1000 / sampleRate
            energies.add(Pair(timeMs, rms))
            pos += frameSizeSamples / 2
        }

        if (energies.isEmpty()) return null

        val sortedEnergies = energies.map { it.second }.sorted()
        val noiseFloorIdx = (sortedEnergies.size * 0.3).toInt().coerceIn(0, sortedEnergies.lastIndex)
        val noiseFloor = sortedEnergies[noiseFloorIdx]
        val threshold = max(noiseFloor * 3.0f, sortedEnergies.last() * 0.08f)

        val silenceGaps = mutableListOf<Pair<Long, Long>>()
        var gapStart: Long? = null

        for ((timeMs, energy) in energies) {
            if (energy <= threshold) {
                if (gapStart == null) {
                    gapStart = timeMs
                }
            } else {
                if (gapStart != null) {
                    val gapEnd = timeMs
                    if (gapEnd - gapStart >= MIN_SILENCE_GAP_MS) {
                        silenceGaps.add(Pair(gapStart, gapEnd))
                    }
                    gapStart = null
                }
            }
        }

        if (gapStart != null) {
            val gapEnd = energies.last().first + FRAME_SIZE_MS
            if (gapEnd - gapStart >= MIN_SILENCE_GAP_MS) {
                silenceGaps.add(Pair(gapStart, gapEnd))
            }
        }

        if (silenceGaps.isEmpty()) return null

        val longestGap = silenceGaps.maxByOrNull { it.second - it.first } ?: return null
        val midpoint = (longestGap.first + longestGap.second) / 2
        return midpoint.coerceIn(0L, audioDurationMs)
    }

    internal fun refineSingleSegment(
        seg: Segment,
        pcmData: FloatArray,
        sampleRate: Int,
        audioDurationMs: Long
    ): VadEngine.RefinedSegment {
        val onsetSearchStart = ((seg.startMs - SEARCH_PADDING_MS).coerceAtLeast(0L) * sampleRate / 1000).toInt()
        val offsetSearchStart = ((seg.endMs - 200L).coerceAtLeast(0L) * sampleRate / 1000).toInt()

        return VadEngine.RefinedSegment(
            originalSegment = seg,
            vadStartMs = speechBoundaryDetector.findSpeechOnset(pcmData, sampleRate, onsetSearchStart, seg.startMs),
            vadEndMs = speechBoundaryDetector.findSpeechOffset(pcmData, sampleRate, offsetSearchStart, seg.endMs),
            confidence = 0.7f,
            method = "energy_onset_offset"
        )
    }

    internal fun refineWithSilenceMidpoints(
        segments: List<Segment>,
        pcmData: FloatArray,
        sampleRate: Int,
        audioDurationMs: Long
    ): List<VadEngine.RefinedSegment> {
        val result = mutableListOf<VadEngine.RefinedSegment>()
        val boundaryPoints = findSilenceMidpoints(pcmData, sampleRate, segments)

        for (i in segments.indices) {
            val seg = segments[i]

            val startMs = if (i == 0) {
                val searchStart = ((seg.startMs - SEARCH_PADDING_MS).coerceAtLeast(0L) * sampleRate / 1000).toInt()
                speechBoundaryDetector.findSpeechOnset(pcmData, sampleRate, searchStart, seg.startMs)
            } else {
                boundaryPoints[i - 1]
            }

            val endMs = if (i == segments.lastIndex) {
                val searchStart = ((seg.endMs - 200L).coerceAtLeast(0L) * sampleRate / 1000).toInt()
                speechBoundaryDetector.findSpeechOffset(pcmData, sampleRate, searchStart, seg.endMs)
            } else {
                boundaryPoints[i]
            }

            result.add(VadEngine.RefinedSegment(
                originalSegment = seg,
                vadStartMs = startMs,
                vadEndMs = endMs,
                confidence = 0.85f,
                method = "silence_midpoint"
            ))
        }

        return result
    }
}
