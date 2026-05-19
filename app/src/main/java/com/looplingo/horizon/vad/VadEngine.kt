package com.looplingo.horizon.vad

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.looplingo.horizon.api.GroqApiClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Voice Activity Detection engine — Simple Silence Midpoint Detection (v3.0).
 *
 * ARCHITECTURE:
 *
 *   The audio waveform looks like:
 *
 *   --------||||||||||--|||---|||||||||||----------|||||||||-------
 *     silence   speech    speech    speech   silence   speech
 *
 *   Between any two speech segments, there's a silence gap.
 *   We find that gap and place the boundary at its MIDPOINT.
 *   That's it. No neural network, no model file, no extra RAM.
 *
 *   PIPELINE:
 *   1. Decode audio -> 16KHz mono PCM float array
 *   2. For each pair of consecutive Whisper segments:
 *      - Scan the region between them with RMS energy frames
 *      - Find the silence gap
 *      - Boundary = midpoint of the silence gap
 *   3. For first/last segments: use energy onset/offset detection
 *   4. Post-process: no overlaps, minimum gaps
 *
 *   WHY THIS WORKS FOR LANGUAGE LEARNING AUDIO:
 *   - Clean recordings with clear speech/silence boundaries
 *   - No noisy backgrounds that fool energy detection
 *   - The silence gap between dialogues is always clearly visible
 *   - Midpoint of silence = natural, perfect boundary
 *
 *   PREVIOUS APPROACHES (removed):
 *   - v1: Hand-crafted heuristics (energy, ZCR, spectral flatness) — over-engineered
 *   - v2: Silero VAD neural network (2MB model, PyTorch Mobile) — unnecessary complexity
 *   - v3: Simple silence midpoint — exactly what's needed, nothing more
 */
@Singleton
class VadEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        // ── Audio Processing ──────────────────────────────────────────────
        private const val SAMPLE_RATE = 16000       // 16KHz — Whisper's rate
        private const val CODEC_TIMEOUT_US = 10_000L

        // ── Energy Analysis ──────────────────────────────────────────────
        private const val FRAME_SIZE_MS = 10        // 10ms frames for boundary scanning
        private const val FRAME_SIZE_SAMPLES = SAMPLE_RATE * FRAME_SIZE_MS / 1000  // 160 samples
        private const val FINE_FRAME_SIZE_MS = 5    // 5ms frames for onset/offset precision
        private const val FINE_FRAME_SIZE_SAMPLES = SAMPLE_RATE * FINE_FRAME_SIZE_MS / 1000  // 80 samples

        // ── Search Window ────────────────────────────────────────────────
        // How far to search around Whisper's boundaries
        private const val SEARCH_PADDING_MS = 600L  // Search 600ms around boundary region

        // ── Post-Processing ───────────────────────────────────────────────
        private const val MIN_SPEECH_DURATION_MS = 50L
        private const val INTER_SEGMENT_GAP_MS = 80L   // Minimum gap between segments

        // ── Silence Detection ────────────────────────────────────────────
        // A silence gap must be at least this long to be a boundary
        private const val MIN_SILENCE_GAP_MS = 40L
    }

    /**
     * Result of VAD refinement for a single Whisper segment.
     */
    data class RefinedSegment(
        val originalSegment: GroqApiClient.Segment,
        val vadStartMs: Long,
        val vadEndMs: Long,
        val confidence: Float,
        val method: String
    )

    // ══════════════════════════════════════════════════════════════════════
    // MAIN PUBLIC API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Refine Whisper segment timestamps using silence midpoint detection.
     *
     * Given an audio/video file and Whisper's rough segments, returns
     * refined segments where boundaries are placed at the midpoint of
     * the silence gap between consecutive speech segments.
     */
    suspend fun refineSegments(
        filePath: String,
        segments: List<GroqApiClient.Segment>,
        onProgress: GroqApiClient.ProgressCallback? = null
    ): List<RefinedSegment> = withContext(Dispatchers.IO) {

        if (segments.isEmpty()) return@withContext emptyList()

        Timber.i("═══ VAD v3.0 (Silence Midpoint): %d Whisper segments ═══", segments.size)
        onProgress?.onProgress("[VAD] Loading audio for boundary analysis…")

        // Step 1: Decode audio to 16kHz mono PCM
        val pcmData = decodeToPcmFloatArray(filePath)
        if (pcmData == null || pcmData.isEmpty()) {
            Timber.w("VAD: Could not decode audio — keeping Whisper timestamps")
            return@withContext segments.map {
                RefinedSegment(it, it.startMs, it.endMs, 0.5f, "whisper_fallback_decode")
            }
        }

        val audioDurationMs = pcmData.size.toLong() * 1000 / SAMPLE_RATE
        Timber.i("VAD: Decoded %d samples (%.1fs)", pcmData.size, pcmData.size / SAMPLE_RATE.toFloat())
        onProgress?.onProgress("[VAD] Detecting silence boundaries…")

        // Step 2: Find silence midpoints between consecutive segments
        val refined = if (segments.size == 1) {
            // Single segment: refine start and end with energy onset/offset
            listOf(refineSingleSegment(segments[0], pcmData, audioDurationMs))
        } else {
            refineWithSilenceMidpoints(segments, pcmData, audioDurationMs)
        }

        // Step 3: Post-process — no overlaps, sane durations
        val postProcessed = postProcessRefinedSegments(refined)

        // Log comparison
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

    // ══════════════════════════════════════════════════════════════════════
    // SILENCE MIDPOINT DETECTION — The core algorithm
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Refine multiple segments using silence midpoint detection.
     *
     * For each pair of consecutive segments, we find the silence gap
     * between them and place the boundary at the midpoint of that gap.
     *
     * Audio looks like:
     *   --------||||||||||--|||---|||||||||||----------|||||||||-------
     *                              ^gap^
     *                        boundary = midpoint of gap
     */
    private fun refineWithSilenceMidpoints(
        segments: List<GroqApiClient.Segment>,
        pcmData: FloatArray,
        audioDurationMs: Long
    ): List<RefinedSegment> {
        val result = mutableListOf<RefinedSegment>()

        // Find all boundary points between consecutive segments
        // boundaryPoints[i] = the timestamp that separates segment[i] from segment[i+1]
        val boundaryPoints = findSilenceMidpoints(segments, pcmData, audioDurationMs)

        // Build refined segments using the boundary points
        for (i in segments.indices) {
            val seg = segments[i]

            // Start boundary: previous boundary point, or energy onset for first segment
            val startMs = if (i == 0) {
                findSpeechOnset(pcmData, seg.startMs, audioDurationMs)
            } else {
                boundaryPoints[i - 1]
            }

            // End boundary: next boundary point, or energy offset for last segment
            val endMs = if (i == segments.lastIndex) {
                findSpeechOffset(pcmData, seg.endMs, audioDurationMs)
            } else {
                boundaryPoints[i]
            }

            result.add(RefinedSegment(
                originalSegment = seg,
                vadStartMs = startMs,
                vadEndMs = endMs,
                confidence = 0.85f,
                method = "silence_midpoint"
            ))
        }

        return result
    }

    /**
     * Find the midpoint of the silence gap between each pair of consecutive segments.
     *
     * For segments[i] and segments[i+1]:
     * - Define the search region around the boundary between them
     * - Scan that region with RMS energy frames
     * - Find where the silence is
     * - Return the midpoint of the silence as the boundary point
     */
    private fun findSilenceMidpoints(
        segments: List<GroqApiClient.Segment>,
        pcmData: FloatArray,
        audioDurationMs: Long
    ): List<Long> {
        val midpoints = mutableListOf<Long>()

        for (i in 0 until segments.lastIndex) {
            val currentEnd = segments[i].endMs
            val nextStart = segments[i + 1].startMs

            // Define the search region between the two segments
            // Use generous padding to catch the actual silence gap
            val searchStart = (min(currentEnd, nextStart) - SEARCH_PADDING_MS).coerceAtLeast(0L)
            val searchEnd = (max(currentEnd, nextStart) + SEARCH_PADDING_MS).coerceAtMost(audioDurationMs)

            val midpoint = findSilenceGapMidpoint(pcmData, searchStart, searchEnd, audioDurationMs)

            if (midpoint != null) {
                midpoints.add(midpoint)
                Timber.d("VAD: Boundary between seg %d/%d at %dms (silence midpoint)",
                    i, i + 1, midpoint)
            } else {
                // No clear silence found — use the midpoint between the two segments' timestamps
                val fallbackMid = (currentEnd + nextStart) / 2
                midpoints.add(fallbackMid)
                Timber.d("VAD: Boundary between seg %d/%d at %dms (fallback midpoint)",
                    i, i + 1, fallbackMid)
            }
        }

        return midpoints
    }

    /**
     * Find the midpoint of the silence gap in a region of audio.
     *
     * Scans the region with RMS energy frames, finds the longest stretch
     * of silence, and returns its midpoint. This is the "cut point" that
     * the user described: find the gap between speech spikes, cut in the middle.
     *
     * @param pcmData Audio data
     * @param searchStartMs Start of search region
     * @param searchEndMs End of search region
     * @param audioDurationMs Total audio duration
     * @return Midpoint of the silence gap in ms, or null if no silence found
     */
    private fun findSilenceGapMidpoint(
        pcmData: FloatArray,
        searchStartMs: Long,
        searchEndMs: Long,
        audioDurationMs: Long
    ): Long? {
        val startSample = (searchStartMs * SAMPLE_RATE / 1000).toInt()
        val endSample = (searchEndMs * SAMPLE_RATE / 1000).toInt().coerceAtMost(pcmData.size)

        if (startSample >= endSample) return null

        // Compute RMS energy per frame
        val energies = mutableListOf<Pair<Long, Float>>()  // (timeMs, rms)
        var pos = startSample
        while (pos + FRAME_SIZE_SAMPLES <= endSample) {
            var sumSq = 0.0
            for (i in 0 until FRAME_SIZE_SAMPLES) {
                sumSq += pcmData[pos + i] * pcmData[pos + i]
            }
            val rms = sqrt(sumSq / FRAME_SIZE_SAMPLES).toFloat()
            val timeMs = pos.toLong() * 1000 / SAMPLE_RATE
            energies.add(Pair(timeMs, rms))
            pos += FRAME_SIZE_SAMPLES / 2  // 50% overlap for better resolution
        }

        if (energies.isEmpty()) return null

        // Adaptive threshold: separate speech from silence
        val sortedEnergies = energies.map { it.second }.sorted()
        val noiseFloorIdx = (sortedEnergies.size * 0.3).toInt().coerceIn(0, sortedEnergies.lastIndex)
        val noiseFloor = sortedEnergies[noiseFloorIdx]
        val threshold = max(noiseFloor * 3.0f, sortedEnergies.last() * 0.08f)

        // Find silence regions (consecutive frames below threshold)
        val silenceGaps = mutableListOf<Pair<Long, Long>>()  // (startMs, endMs)
        var gapStart: Long? = null

        for ((timeMs, energy) in energies) {
            if (energy <= threshold) {
                // Silence frame
                if (gapStart == null) {
                    gapStart = timeMs
                }
            } else {
                // Speech frame — close any open silence gap
                if (gapStart != null) {
                    val gapEnd = timeMs
                    if (gapEnd - gapStart >= MIN_SILENCE_GAP_MS) {
                        silenceGaps.add(Pair(gapStart, gapEnd))
                    }
                    gapStart = null
                }
            }
        }

        // Close any remaining gap
        if (gapStart != null) {
            val gapEnd = energies.last().first + FRAME_SIZE_MS
            if (gapEnd - gapStart >= MIN_SILENCE_GAP_MS) {
                silenceGaps.add(Pair(gapStart, gapEnd))
            }
        }

        if (silenceGaps.isEmpty()) return null

        // Find the longest silence gap — that's most likely the boundary between dialogues
        val longestGap = silenceGaps.maxByOrNull { it.second - it.first } ?: return null

        // Return the midpoint of the longest silence gap
        val midpoint = (longestGap.first + longestGap.second) / 2
        return midpoint.coerceIn(0L, audioDurationMs)
    }

    /**
     * Refine a single segment using energy onset/offset detection.
     * Used when there's only one Whisper segment.
     */
    private fun refineSingleSegment(
        seg: GroqApiClient.Segment,
        pcmData: FloatArray,
        audioDurationMs: Long
    ): RefinedSegment {
        return RefinedSegment(
            originalSegment = seg,
            vadStartMs = findSpeechOnset(pcmData, seg.startMs, audioDurationMs),
            vadEndMs = findSpeechOffset(pcmData, seg.endMs, audioDurationMs),
            confidence = 0.7f,
            method = "energy_onset_offset"
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // ENERGY ONSET / OFFSET — For first/last segment boundaries
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Find where speech actually starts near Whisper's startMs.
     * Scans backward from the reference point to find the energy onset.
     */
    private fun findSpeechOnset(
        pcmData: FloatArray,
        whisperStartMs: Long,
        audioDurationMs: Long
    ): Long {
        val searchStart = (whisperStartMs - SEARCH_PADDING_MS).coerceAtLeast(0L)
        val searchEnd = (whisperStartMs + 200L).coerceAtMost(audioDurationMs)

        val startSample = (searchStart * SAMPLE_RATE / 1000).toInt()
        val endSample = (searchEnd * SAMPLE_RATE / 1000).toInt().coerceAtMost(pcmData.size)

        if (startSample >= endSample) return whisperStartMs

        // Compute RMS energy per 5ms frame for fine precision
        val energies = mutableListOf<Pair<Long, Float>>()
        var pos = startSample
        while (pos + FINE_FRAME_SIZE_SAMPLES <= endSample) {
            var sumSq = 0.0
            for (i in 0 until FINE_FRAME_SIZE_SAMPLES) {
                sumSq += pcmData[pos + i] * pcmData[pos + i]
            }
            val rms = sqrt(sumSq / FINE_FRAME_SIZE_SAMPLES).toFloat()
            val timeMs = pos.toLong() * 1000 / SAMPLE_RATE
            energies.add(Pair(timeMs, rms))
            pos += FINE_FRAME_SIZE_SAMPLES / 2
        }

        if (energies.isEmpty()) return whisperStartMs

        // Adaptive threshold
        val sortedEnergies = energies.map { it.second }.sorted()
        val noiseFloorIdx = (sortedEnergies.size * 0.3).toInt().coerceIn(0, sortedEnergies.lastIndex)
        val noiseFloor = sortedEnergies[noiseFloorIdx]
        val threshold = max(noiseFloor * 3.0f, sortedEnergies.last() * 0.08f)

        // Find first frame above threshold (forward search from the quiet area)
        for ((timeMs, energy) in energies) {
            if (energy > threshold) {
                return (timeMs - 5L).coerceAtLeast(0L)  // Small padding before onset
            }
        }

        return whisperStartMs
    }

    /**
     * Find where speech actually ends near Whisper's endMs.
     * Scans from the reference point backward to find the energy offset.
     */
    private fun findSpeechOffset(
        pcmData: FloatArray,
        whisperEndMs: Long,
        audioDurationMs: Long
    ): Long {
        val searchStart = (whisperEndMs - 200L).coerceAtLeast(0L)
        val searchEnd = (whisperEndMs + SEARCH_PADDING_MS).coerceAtMost(audioDurationMs)

        val startSample = (searchStart * SAMPLE_RATE / 1000).toInt()
        val endSample = (searchEnd * SAMPLE_RATE / 1000).toInt().coerceAtMost(pcmData.size)

        if (startSample >= endSample) return whisperEndMs

        // Compute RMS energy per 5ms frame
        val energies = mutableListOf<Pair<Long, Float>>()
        var pos = startSample
        while (pos + FINE_FRAME_SIZE_SAMPLES <= endSample) {
            var sumSq = 0.0
            for (i in 0 until FINE_FRAME_SIZE_SAMPLES) {
                sumSq += pcmData[pos + i] * pcmData[pos + i]
            }
            val rms = sqrt(sumSq / FINE_FRAME_SIZE_SAMPLES).toFloat()
            val timeMs = pos.toLong() * 1000 / SAMPLE_RATE
            energies.add(Pair(timeMs, rms))
            pos += FINE_FRAME_SIZE_SAMPLES / 2
        }

        if (energies.isEmpty()) return whisperEndMs

        // Adaptive threshold
        val sortedEnergies = energies.map { it.second }.sorted()
        val noiseFloorIdx = (sortedEnergies.size * 0.3).toInt().coerceIn(0, sortedEnergies.lastIndex)
        val noiseFloor = sortedEnergies[noiseFloorIdx]
        val threshold = max(noiseFloor * 3.0f, sortedEnergies.last() * 0.08f)

        // Find last frame above threshold (backward search)
        for (i in energies.lastIndex downTo 0) {
            if (energies[i].second > threshold) {
                return energies[i].first + 5L  // Small padding after offset
            }
        }

        return whisperEndMs
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUDIO DECODING
    // ══════════════════════════════════════════════════════════════════════

    private fun decodeToPcmFloatArray(filePath: String): FloatArray? {
        val pcmBytes = decodeViaMediaCodec(filePath)
        if (pcmBytes != null && pcmBytes.isNotEmpty()) {
            return pcmBytesToFloatArray(pcmBytes)
        }
        val wavPcm = readWavPcm(filePath)
        if (wavPcm != null && wavPcm.isNotEmpty()) {
            return pcmBytesToFloatArray(wavPcm)
        }
        Timber.w("VAD: All decoding methods failed for: %s", filePath.substringAfterLast("/"))
        return null
    }

    private fun decodeViaMediaCodec(filePath: String): ByteArray? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null

        try {
            extractor.setDataSource(filePath)

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
            if (audioTrackIndex < 0 || inputFormat == null) return null

            val srcMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(audioTrackIndex)

            decoder = MediaCodec.createDecoderByType(srcMime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            var decodedSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var decodedChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var outputFormatChecked = false

            val pcmChunks = mutableListOf<ByteArray>()
            var totalSize = 0L
            var inputDone = false
            var outputDone = false
            val bufferInfo = MediaCodec.BufferInfo()

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val size = extractor.readSampleData(inBuf, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                when {
                    outIdx >= 0 -> {
                        if (!outputFormatChecked) {
                            val fmt = decoder.outputFormat
                            try { decodedSampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) {}
                            try { decodedChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) {}
                            outputFormatChecked = true
                        }
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (bufferInfo.size > 0) {
                            val outBuf = decoder.getOutputBuffer(outIdx)
                            if (outBuf != null) {
                                val chunk = ByteArray(bufferInfo.size)
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                outBuf.get(chunk)
                                pcmChunks.add(chunk)
                                totalSize += chunk.size
                            }
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = decoder.outputFormat
                        try { decodedSampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) {}
                        try { decodedChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) {}
                        outputFormatChecked = true
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* wait */ }
                }
            }

            decoder.stop()
            decoder.release()
            decoder = null

            if (pcmChunks.isEmpty() || totalSize == 0L) return null

            val merged = ByteArray(totalSize.toInt())
            var offset = 0
            for (chunk in pcmChunks) {
                System.arraycopy(chunk, 0, merged, offset, chunk.size)
                offset += chunk.size
            }

            val downsampled = downsamplePcm(merged, decodedSampleRate, decodedChannels, SAMPLE_RATE, 1)
            Timber.i("VAD: Decoded %d bytes PCM (%dHz %dch -> %dHz mono)",
                downsampled.size, decodedSampleRate, decodedChannels, SAMPLE_RATE)
            return downsampled

        } catch (e: Exception) {
            Timber.w(e, "VAD: MediaCodec decode failed")
            return null
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun readWavPcm(filePath: String): ByteArray? {
        return try {
            val file = File(filePath)
            if (!file.exists()) return null

            val raf = RandomAccessFile(file, "r")
            try {
                val riff = ByteArray(4)
                raf.read(riff)
                if (String(riff) != "RIFF") return null
                raf.skipBytes(4)
                val wave = ByteArray(4)
                raf.read(wave)
                if (String(wave) != "WAVE") return null

                var chunkId = ByteArray(4)
                var audioFormat = 0
                var channels = 0
                var sampleRate = 0
                var bitsPerSample = 0

                while (raf.filePointer < raf.length() - 8) {
                    raf.read(chunkId)
                    val sizeBytes = ByteArray(4)
                    raf.read(sizeBytes)
                    val chunkSize = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).int

                    if (String(chunkId) == "fmt ") {
                        audioFormat = raf.readUnsignedShort()
                        channels = raf.readUnsignedShort()
                        sampleRate = ByteBuffer.wrap(ByteArray(4).also { raf.read(it) })
                            .order(ByteOrder.LITTLE_ENDIAN).int
                        raf.skipBytes(4)
                        raf.skipBytes(2)
                        bitsPerSample = raf.readUnsignedShort()
                        if (chunkSize > 16) raf.skipBytes(chunkSize - 16)
                    } else if (String(chunkId) == "data") {
                        val pcmData = ByteArray(minOf(chunkSize.toLong(), raf.length() - raf.filePointer).toInt())
                        raf.read(pcmData)
                        if (bitsPerSample == 16 && sampleRate > 0 && channels > 0) {
                            return downsamplePcm(pcmData, sampleRate, channels, SAMPLE_RATE, 1)
                        }
                        return null
                    } else {
                        raf.skipBytes(chunkSize)
                    }
                }
                null
            } finally {
                raf.close()
            }
        } catch (e: Exception) {
            Timber.w(e, "VAD: WAV read failed")
            null
        }
    }

    private fun downsamplePcm(
        pcmData: ByteArray, srcRate: Int, srcChannels: Int,
        targetRate: Int, targetChannels: Int
    ): ByteArray {
        val srcSamples = ShortArray(pcmData.size / 2)
        ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(srcSamples)

        val monoSamples = if (srcChannels > 1) {
            val monoCount = srcSamples.size / srcChannels
            val mono = ShortArray(monoCount)
            for (i in 0 until monoCount) {
                var sum = 0L
                for (ch in 0 until srcChannels) {
                    sum += srcSamples[i * srcChannels + ch]
                }
                mono[i] = (sum / srcChannels).toShort()
            }
            mono
        } else {
            srcSamples
        }

        val resampled = if (srcRate != targetRate) {
            val ratio = srcRate.toDouble() / targetRate
            val targetLength = (monoSamples.size / ratio).toInt()
            val result = ShortArray(targetLength)
            for (i in 0 until targetLength) {
                val srcPos = i * ratio
                val srcIdx = srcPos.toInt()
                val frac = srcPos - srcIdx
                if (srcIdx + 1 < monoSamples.size) {
                    result[i] = (monoSamples[srcIdx] * (1.0 - frac) + monoSamples[srcIdx + 1] * frac).toInt().toShort()
                } else if (srcIdx < monoSamples.size) {
                    result[i] = monoSamples[srcIdx]
                }
            }
            result
        } else {
            monoSamples
        }

        val resultBytes = ByteArray(resampled.size * 2)
        ByteBuffer.wrap(resultBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(resampled)
        return resultBytes
    }

    private fun pcmBytesToFloatArray(pcmBytes: ByteArray): FloatArray {
        val samples = pcmBytes.size / 2
        val floatArray = FloatArray(samples)
        val buffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until samples) {
            floatArray[i] = buffer.get().toFloat() / 32768.0f
        }
        return floatArray
    }

    // ══════════════════════════════════════════════════════════════════════
    // POST-PROCESSING
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Post-process refined segments to ensure consistency:
     * 1. Ensure minimum duration
     * 2. Remove overlaps between consecutive segments
     * 3. Enforce minimum gap between segments (prevents bleed-through)
     * 4. Ensure endMs > startMs
     */
    private fun postProcessRefinedSegments(segments: List<RefinedSegment>): List<RefinedSegment> {
        val result = segments.toMutableList()

        // Step 1: If VAD made a segment too short, use Whisper's original timestamps
        for (i in result.indices) {
            val seg = result[i]
            if (seg.vadEndMs - seg.vadStartMs < MIN_SPEECH_DURATION_MS) {
                result[i] = seg.copy(
                    vadStartMs = min(seg.vadStartMs, seg.originalSegment.startMs),
                    vadEndMs = max(seg.vadEndMs, seg.originalSegment.endMs)
                )
            }
        }

        // Step 2: Remove overlaps and enforce minimum gap
        // Prefer trimming the END of the earlier segment (less disruptive than cutting the start)
        for (i in 1 until result.size) {
            val prev = result[i - 1]
            val curr = result[i]

            val requiredEnd = curr.vadStartMs - INTER_SEGMENT_GAP_MS

            if (prev.vadEndMs > requiredEnd) {
                val trimmedEnd = min(prev.vadEndMs, requiredEnd)

                if (trimmedEnd - prev.vadStartMs >= MIN_SPEECH_DURATION_MS) {
                    result[i - 1] = prev.copy(vadEndMs = trimmedEnd)
                } else {
                    // Can't trim previous — push current start later
                    val pushedStart = prev.vadEndMs + INTER_SEGMENT_GAP_MS
                    result[i] = curr.copy(vadStartMs = pushedStart)
                }
            }
        }

        // Step 3: Ensure endMs > startMs
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
