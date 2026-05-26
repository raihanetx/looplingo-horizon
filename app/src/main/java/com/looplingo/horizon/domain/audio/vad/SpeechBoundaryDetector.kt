package com.looplingo.horizon.domain.audio.vad

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@Singleton
class SpeechBoundaryDetector @Inject constructor() {

    companion object {
        private const val FINE_FRAME_SIZE_MS = 5
        private const val SEARCH_PADDING_MS = 600L
        private const val MIN_SPEECH_DURATION_MS = 50L
        private const val INTER_SEGMENT_GAP_MS = 80L
    }

    internal fun findSpeechOnset(
        pcmData: FloatArray,
        sampleRate: Int,
        searchStartSample: Int,
        segmentStartMs: Long
    ): Long {
        val fineFrameSizeSamples = sampleRate * FINE_FRAME_SIZE_MS / 1000
        val searchEndMs = (segmentStartMs + 200L)
        val endSample = (searchEndMs * sampleRate / 1000).toInt().coerceAtMost(pcmData.size)

        if (searchStartSample >= endSample) return segmentStartMs

        val energies = mutableListOf<Pair<Long, Float>>()
        var pos = searchStartSample
        while (pos + fineFrameSizeSamples <= endSample) {
            var sumSq = 0.0
            for (i in 0 until fineFrameSizeSamples) {
                sumSq += pcmData[pos + i] * pcmData[pos + i]
            }
            val rms = sqrt(sumSq / fineFrameSizeSamples).toFloat()
            val timeMs = pos.toLong() * 1000 / sampleRate
            energies.add(Pair(timeMs, rms))
            pos += fineFrameSizeSamples / 2
        }

        if (energies.isEmpty()) return segmentStartMs

        val sortedEnergies = energies.map { it.second }.sorted()
        val noiseFloorIdx = (sortedEnergies.size * 0.3).toInt().coerceIn(0, sortedEnergies.lastIndex)
        val noiseFloor = sortedEnergies[noiseFloorIdx]
        val threshold = max(noiseFloor * 3.0f, sortedEnergies.last() * 0.08f)

        for ((timeMs, energy) in energies) {
            if (energy > threshold) {
                return (timeMs - 5L).coerceAtLeast(0L)
            }
        }

        return segmentStartMs
    }

    internal fun findSpeechOffset(
        pcmData: FloatArray,
        sampleRate: Int,
        searchStartSample: Int,
        segmentEndMs: Long
    ): Long {
        val fineFrameSizeSamples = sampleRate * FINE_FRAME_SIZE_MS / 1000
        val searchEndMs = (segmentEndMs + SEARCH_PADDING_MS)
        val endSample = (searchEndMs * sampleRate / 1000).toInt().coerceAtMost(pcmData.size)

        if (searchStartSample >= endSample) return segmentEndMs

        val energies = mutableListOf<Pair<Long, Float>>()
        var pos = searchStartSample
        while (pos + fineFrameSizeSamples <= endSample) {
            var sumSq = 0.0
            for (i in 0 until fineFrameSizeSamples) {
                sumSq += pcmData[pos + i] * pcmData[pos + i]
            }
            val rms = sqrt(sumSq / fineFrameSizeSamples).toFloat()
            val timeMs = pos.toLong() * 1000 / sampleRate
            energies.add(Pair(timeMs, rms))
            pos += fineFrameSizeSamples / 2
        }

        if (energies.isEmpty()) return segmentEndMs

        val sortedEnergies = energies.map { it.second }.sorted()
        val noiseFloorIdx = (sortedEnergies.size * 0.3).toInt().coerceIn(0, sortedEnergies.lastIndex)
        val noiseFloor = sortedEnergies[noiseFloorIdx]
        val threshold = max(noiseFloor * 3.0f, sortedEnergies.last() * 0.08f)

        for (i in energies.lastIndex downTo 0) {
            if (energies[i].second > threshold) {
                return energies[i].first + 5L
            }
        }

        return segmentEndMs
    }

    internal fun postProcessRefinedSegments(
        segments: List<VadEngine.RefinedSegment>
    ): List<VadEngine.RefinedSegment> {
        val result = segments.toMutableList()

        for (i in result.indices) {
            val seg = result[i]
            if (seg.vadEndMs - seg.vadStartMs < MIN_SPEECH_DURATION_MS) {
                result[i] = seg.copy(
                    vadStartMs = min(seg.vadStartMs, seg.originalSegment.startMs),
                    vadEndMs = max(seg.vadEndMs, seg.originalSegment.endMs)
                )
            }
        }

        for (i in 1 until result.size) {
            val prev = result[i - 1]
            val curr = result[i]

            val requiredEnd = curr.vadStartMs - INTER_SEGMENT_GAP_MS

            if (prev.vadEndMs > requiredEnd) {
                val trimmedEnd = min(prev.vadEndMs, requiredEnd)

                if (trimmedEnd - prev.vadStartMs >= MIN_SPEECH_DURATION_MS) {
                    result[i - 1] = prev.copy(vadEndMs = trimmedEnd)
                } else {
                    val pushedStart = prev.vadEndMs + INTER_SEGMENT_GAP_MS
                    result[i] = curr.copy(vadStartMs = pushedStart)
                }
            }
        }

        for (i in result.indices) {
            if (result[i].vadEndMs <= result[i].vadStartMs) {
                result[i] = result[i].copy(
                    vadStartMs = result[i].originalSegment.startMs,
                    vadEndMs = result[i].originalSegment.endMs
                )
            }
        }

        return result
    }
}
