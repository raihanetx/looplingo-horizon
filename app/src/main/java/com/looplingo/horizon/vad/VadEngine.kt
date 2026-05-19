package com.looplingo.horizon.vad

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.looplingo.horizon.api.GroqApiClient
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
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Voice Activity Detection engine using multi-feature audio analysis.
 *
 * This engine analyzes audio waveforms to detect precise speech boundaries —
 * where each utterance actually starts and ends. It combines multiple acoustic
 * features for robust detection:
 *
 *   1. Short-Time Energy (STE) — energy in small frames detects loud vs quiet
 *   2. Speech Band Energy Ratio — energy in 300-3400Hz (human speech band)
 *   3. Zero-Crossing Rate (ZCR) — speech has characteristic ZCR patterns
 *   4. Spectral Flatness — speech is tonal (low flatness), noise is flat (high flatness)
 *   5. Hysteresis thresholding — prevents rapid on/off switching at boundaries
 *
 * ACCURACY PHILOSOPHY:
 *   We do NOT need general-purpose VAD. We already have Whisper's rough timestamps
 *   telling us approximately where speech is. Our job is to REFINE those boundaries —
 *   find the exact frame where speech starts and ends within a window around each
 *   Whisper segment. This is a much easier problem than general VAD.
 *
 *   For each Whisper segment, we analyze a window from 500ms before its startMs
 *   to 500ms after its endMs. Within that window, we find the precise speech
 *   boundaries using the multi-feature analysis above.
 *
 * DESIGN DECISIONS:
 *   - No external model dependency — works offline, no APK bloat
 *   - Uses FFT for spectral analysis
 *   - Adaptive thresholds based on each segment's own audio characteristics
 *   - Hysteresis prevents boundary jitter from noise
 *   - Post-processing ensures no overlaps between consecutive segments
 */
@Singleton
class VadEngine @Inject constructor(
    private val context: Context
) {

    companion object {
        // ── Audio Processing ──────────────────────────────────────────────
        private const val SAMPLE_RATE = 16000       // 16KHz — Whisper's rate
        private const val FRAME_SIZE_MS = 10        // 10ms per analysis frame
        private const val FRAME_SIZE_SAMPLES = SAMPLE_RATE * FRAME_SIZE_MS / 1000  // 160 samples
        private const val FRAME_SHIFT_MS = 10       // Non-overlapping frames
        private const val FRAME_SHIFT_SAMPLES = SAMPLE_RATE * FRAME_SHIFT_MS / 1000  // 160 samples

        // ── Analysis Window ───────────────────────────────────────────────
        private const val PRE_ROLL_MS = 500L        // Search 500ms before Whisper's startMs
        private const val POST_ROLL_MS = 500L       // Search 500ms after Whisper's endMs

        // ── Speech Frequency Band ─────────────────────────────────────────
        private const val SPEECH_BAND_LOW_HZ = 80    // Lowest fundamental (deep male voice)
        private const val SPEECH_BAND_HIGH_HZ = 4000  // Above highest formant
        private const val SPEECH_ENERGY_RATIO_THRESHOLD = 0.25f

        // ── Hysteresis Thresholds ─────────────────────────────────────────
        private const val SPEECH_ON_SENSITIVITY = 0.35f
        private const val SPEECH_OFF_SENSITIVITY = 0.20f

        // ── Post-Processing ───────────────────────────────────────────────
        private const val MIN_SPEECH_DURATION_MS = 50L
        private const val MIN_SILENCE_DURATION_MS = 80L
        private const val BOUNDARY_PADDING_MS = 30L
        private const val INTER_SEGMENT_GAP_MS = 30L

        // ── FFT ───────────────────────────────────────────────────────────
        private const val FFT_SIZE = 512
        private const val CODEC_TIMEOUT_US = 10_000L
    }

    /**
     * A speech segment detected by VAD.
     */
    data class SpeechSegment(
        val startMs: Long,
        val endMs: Long,
        val confidence: Float,
        val avgEnergy: Float,
        val speechBandRatio: Float
    ) {
        val durationMs: Long get() = endMs - startMs
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
     * Refine Whisper segment timestamps using VAD analysis.
     *
     * Given an audio/video file and Whisper's rough segments, returns
     * refined segments where startMs/endMs precisely match where speech
     * actually starts and ends in the audio.
     */
    suspend fun refineSegments(
        filePath: String,
        segments: List<GroqApiClient.Segment>,
        onProgress: GroqApiClient.ProgressCallback? = null
    ): List<RefinedSegment> = withContext(Dispatchers.IO) {

        if (segments.isEmpty()) return@withContext emptyList()

        Timber.i("═══ VAD REFINEMENT: %d Whisper segments ═══", segments.size)
        onProgress?.onProgress("[VAD] Loading audio for boundary analysis…")

        // Step 1: Decode audio to 16kHz mono PCM
        val pcmData = decodeToPcmFloatArray(filePath)
        if (pcmData == null || pcmData.isEmpty()) {
            Timber.w("VAD: Could not decode audio — keeping Whisper timestamps")
            return@withContext segments.map {
                RefinedSegment(it, it.startMs, it.endMs, 0.5f, "whisper_fallback_decode")
            }
        }

        Timber.i("VAD: Decoded %d samples (%.1fs)", pcmData.size, pcmData.size / SAMPLE_RATE.toFloat())
        onProgress?.onProgress("[VAD] Analyzing speech boundaries…")

        // Step 2: Run full-audio VAD
        val allSpeechSegments = detectAllSpeechSegments(pcmData)
        Timber.i("VAD: Detected %d speech segments", allSpeechSegments.size)

        // Step 3: Align each Whisper segment with nearest VAD segments
        val refined = alignSegments(segments, allSpeechSegments, pcmData)

        // Step 4: Post-process — no overlaps
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

        onProgress?.onProgress("[VAD] ✓ Speech boundaries refined (%d adjusted)".format(adjustedCount))
        postProcessed
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
            Timber.i("VAD: Decoded %d bytes PCM (%dHz %dch → %dHz mono)",
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
    // CORE VAD — Multi-Feature Speech Detection
    // ══════════════════════════════════════════════════════════════════════

    data class FrameFeatures(
        val energy: Float,
        val zeroCrossingRate: Float,
        val speechBandRatio: Float,
        val spectralFlatness: Float,
        val frameIndex: Int
    )

    private fun detectAllSpeechSegments(pcmData: FloatArray): List<SpeechSegment> {
        val numFrames = pcmData.size / FRAME_SHIFT_SAMPLES
        if (numFrames == 0) return emptyList()

        val features = computeFrameFeatures(pcmData, numFrames)
        val speechProb = computeSpeechProbability(features)
        val isSpeech = applyHysteresisThreshold(speechProb)
        val rawSegments = groupConsecutiveSpeechFrames(isSpeech)
        val mergedSegments = mergeShortSilences(rawSegments)
        val filteredSegments = mergedSegments.filter { it.durationMs >= MIN_SPEECH_DURATION_MS }

        return filteredSegments.map { seg ->
            val startFrame = ((seg.startMs / FRAME_SHIFT_MS.toDouble()) * FRAME_SHIFT_SAMPLES).toInt()
            val endFrame = minOf(
                ((seg.endMs / FRAME_SHIFT_MS.toDouble()) * FRAME_SHIFT_SAMPLES).toInt(),
                features.size - 1
            )
            val avgEnergy = if (endFrame >= startFrame) {
                features.slice(startFrame..endFrame).map { it.energy }.average().toFloat()
            } else 0f
            val avgBandRatio = if (endFrame >= startFrame) {
                features.slice(startFrame..endFrame).map { it.speechBandRatio }.average().toFloat()
            } else 0f

            seg.copy(
                avgEnergy = avgEnergy,
                speechBandRatio = avgBandRatio,
                confidence = calculateSegmentConfidence(features, startFrame, endFrame)
            )
        }
    }

    private fun computeFrameFeatures(pcmData: FloatArray, numFrames: Int): List<FrameFeatures> {
        val features = mutableListOf<FrameFeatures>()

        for (i in 0 until numFrames) {
            val start = i * FRAME_SHIFT_SAMPLES
            val end = minOf(start + FRAME_SIZE_SAMPLES, pcmData.size)
            if (end - start < FRAME_SIZE_SAMPLES / 2) break

            val frame = pcmData.copyOfRange(start, end)
            val fftInput = if (frame.size < FFT_SIZE) frame.copyOf(FFT_SIZE) else frame

            val energy = computeRmsEnergy(frame)
            val zcr = computeZeroCrossingRate(frame)
            val (speechBandRatio, spectralFlatness) = computeSpectralFeatures(fftInput)

            features.add(FrameFeatures(
                energy = energy,
                zeroCrossingRate = zcr,
                speechBandRatio = speechBandRatio,
                spectralFlatness = spectralFlatness,
                frameIndex = i
            ))
        }

        return features
    }

    private fun computeRmsEnergy(frame: FloatArray): Float {
        var sumSq = 0.0
        for (s in frame) sumSq += s * s
        return sqrt(sumSq / frame.size).toFloat()
    }

    private fun computeZeroCrossingRate(frame: FloatArray): Float {
        var crossings = 0
        for (i in 1 until frame.size) {
            if ((frame[i] >= 0) != (frame[i - 1] >= 0)) crossings++
        }
        return crossings.toFloat() / (frame.size - 1)
    }

    private fun computeSpectralFeatures(frame: FloatArray): Pair<Float, Float> {
        // Apply Hann window
        val windowed = FloatArray(frame.size)
        for (i in frame.indices) {
            val hann = 0.5 * (1.0 - cos(2.0 * Math.PI * i / (frame.size - 1)))
            windowed[i] = (frame[i] * hann).toFloat()
        }

        val spectrum = fft(windowed)
        val magnitudes = FloatArray(FFT_SIZE / 2)
        for (i in magnitudes.indices) {
            val re = spectrum[2 * i]
            val im = spectrum[2 * i + 1]
            magnitudes[i] = sqrt(re * re + im * im)
        }

        val binHz = SAMPLE_RATE.toFloat() / FFT_SIZE
        val speechLowBin = (SPEECH_BAND_LOW_HZ / binHz).toInt().coerceAtLeast(1)
        val speechHighBin = (SPEECH_BAND_HIGH_HZ / binHz).toInt().coerceAtMost(magnitudes.size - 1)

        var totalEnergy = 0.0
        var speechBandEnergy = 0.0
        for (i in 1 until magnitudes.size) {
            val energy = magnitudes[i] * magnitudes[i]
            totalEnergy += energy
            if (i in speechLowBin..speechHighBin) speechBandEnergy += energy
        }

        val speechBandRatio = if (totalEnergy > 0) (speechBandEnergy / totalEnergy).toFloat() else 0f

        val spectralFlatness = if (magnitudes.size > 1) {
            val nonZeroMags = magnitudes.slice(1 until magnitudes.size).filter { it > 1e-10f }
            if (nonZeroMags.size > 2) {
                val logSum = nonZeroMags.sumOf { ln(it.toDouble()) }
                val geoMean = Math.exp(logSum / nonZeroMags.size)
                val ariMean = nonZeroMags.average()
                if (ariMean > 0) (geoMean / ariMean).toFloat().coerceIn(0f, 1f) else 0f
            } else 0f
        } else 0f

        return Pair(speechBandRatio, spectralFlatness)
    }

    private fun fft(input: FloatArray): FloatArray {
        val n = input.size / 2
        val output = input.copyOf()

        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                var temp = output[2 * i]
                output[2 * i] = output[2 * j]
                output[2 * j] = temp
                temp = output[2 * i + 1]
                output[2 * i + 1] = output[2 * j + 1]
                output[2 * j + 1] = temp
            }
            var k = n / 2
            while (k <= j) {
                j -= k
                k /= 2
            }
            j += k
        }

        var length = 2
        while (length <= n) {
            val angle = -2.0 * Math.PI / length
            val wRe = cos(angle)
            val wIm = sin(angle)

            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until length / 2) {
                    val evenIdx = 2 * (i + k)
                    val oddIdx = 2 * (i + k + length / 2)
                    val tRe = curRe * output[oddIdx] - curIm * output[oddIdx + 1]
                    val tIm = curRe * output[oddIdx + 1] + curIm * output[oddIdx]
                    output[oddIdx] = (output[evenIdx] - tRe).toFloat()
                    output[oddIdx + 1] = (output[evenIdx + 1] - tIm).toFloat()
                    output[evenIdx] = (output[evenIdx] + tRe).toFloat()
                    output[evenIdx + 1] = (output[evenIdx + 1] + tIm).toFloat()
                    val newCurRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = newCurRe
                }
                i += length
            }
            length *= 2
        }

        return output
    }

    private fun computeSpeechProbability(features: List<FrameFeatures>): FloatArray {
        if (features.isEmpty()) return FloatArray(0)

        val energies = features.map { it.energy }
        val maxEnergy = energies.max()
        val noiseFloor = estimateNoiseFloor(energies)
        val energyThreshold = max(noiseFloor * 2.0f, maxEnergy * 0.05f)

        val speechProb = FloatArray(features.size)

        for (i in features.indices) {
            val f = features[i]

            val energyScore = if (f.energy > energyThreshold && maxEnergy > 0) {
                ((f.energy - energyThreshold) / (maxEnergy - energyThreshold)).coerceIn(0f, 1f)
            } else 0f

            val bandScore = if (f.speechBandRatio > SPEECH_ENERGY_RATIO_THRESHOLD) {
                1.0f
            } else {
                f.speechBandRatio / SPEECH_ENERGY_RATIO_THRESHOLD
            }

            val zcrScore = when {
                f.energy < energyThreshold * 0.5f -> 0f
                f.zeroCrossingRate in 0.02f..0.4f -> 1.0f
                f.zeroCrossingRate < 0.02f -> 0.3f
                else -> 0.5f
            }

            val flatnessScore = when {
                f.energy < energyThreshold * 0.5f -> 0f
                f.spectralFlatness < 0.3f -> 1.0f
                f.spectralFlatness < 0.6f -> 0.7f
                else -> 0.3f
            }

            speechProb[i] = energyScore * 0.40f + bandScore * 0.25f + zcrScore * 0.15f + flatnessScore * 0.20f
        }

        // Temporal smoothing (3-frame moving average)
        val smoothed = FloatArray(speechProb.size)
        for (i in speechProb.indices) {
            val prev = if (i > 0) speechProb[i - 1] else speechProb[i]
            val next = if (i < speechProb.size - 1) speechProb[i + 1] else speechProb[i]
            smoothed[i] = prev * 0.25f + speechProb[i] * 0.5f + next * 0.25f
        }

        return smoothed
    }

    private fun estimateNoiseFloor(energies: List<Float>): Float {
        if (energies.isEmpty()) return 0f
        val sorted = energies.sorted()
        val idx = (sorted.size * 0.1).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    private fun applyHysteresisThreshold(speechProb: FloatArray): BooleanArray {
        val isSpeech = BooleanArray(speechProb.size)
        var currentlySpeech = false

        for (i in speechProb.indices) {
            if (!currentlySpeech) {
                if (speechProb[i] >= SPEECH_ON_SENSITIVITY) currentlySpeech = true
            } else {
                if (speechProb[i] < SPEECH_OFF_SENSITIVITY) currentlySpeech = false
            }
            isSpeech[i] = currentlySpeech
        }

        return isSpeech
    }

    private fun groupConsecutiveSpeechFrames(isSpeech: BooleanArray): List<SpeechSegment> {
        val segments = mutableListOf<SpeechSegment>()
        var segStart = -1

        for (i in isSpeech.indices) {
            if (isSpeech[i] && segStart < 0) {
                segStart = i
            } else if (!isSpeech[i] && segStart >= 0) {
                segments.add(SpeechSegment(
                    startMs = segStart.toLong() * FRAME_SHIFT_MS,
                    endMs = i.toLong() * FRAME_SHIFT_MS,
                    confidence = 0.5f, avgEnergy = 0f, speechBandRatio = 0f
                ))
                segStart = -1
            }
        }

        if (segStart >= 0) {
            segments.add(SpeechSegment(
                startMs = segStart.toLong() * FRAME_SHIFT_MS,
                endMs = isSpeech.size.toLong() * FRAME_SHIFT_MS,
                confidence = 0.5f, avgEnergy = 0f, speechBandRatio = 0f
            ))
        }

        return segments
    }

    private fun mergeShortSilences(segments: List<SpeechSegment>): List<SpeechSegment> {
        if (segments.size <= 1) return segments

        val merged = mutableListOf<SpeechSegment>()
        var current = segments[0]

        for (i in 1 until segments.size) {
            val gap = segments[i].startMs - current.endMs
            if (gap < MIN_SILENCE_DURATION_MS) {
                current = current.copy(endMs = segments[i].endMs)
            } else {
                merged.add(current)
                current = segments[i]
            }
        }
        merged.add(current)
        return merged
    }

    private fun calculateSegmentConfidence(
        features: List<FrameFeatures>, startFrame: Int, endFrame: Int
    ): Float {
        if (startFrame > endFrame || endFrame >= features.size) return 0.5f

        val segFeatures = features.slice(startFrame..endFrame)
        if (segFeatures.isEmpty()) return 0.5f

        val avgBandRatio = segFeatures.map { it.speechBandRatio }.average().toFloat()
        val energies = segFeatures.map { it.energy }
        val avgEnergy = energies.average().toFloat()
        val energyStdDev = if (energies.size > 1) {
            val mean = energies.average()
            sqrt(energies.map { (it - mean) * (it - mean) }.average()).toFloat()
        } else 0f
        val energyConsistency = if (avgEnergy > 0) {
            1.0f - (energyStdDev / avgEnergy).coerceIn(0f, 1f)
        } else 0f

        return (avgBandRatio * 0.6f + energyConsistency * 0.4f).coerceIn(0f, 1f)
    }

    // ══════════════════════════════════════════════════════════════════════
    // SEGMENT ALIGNMENT
    // ══════════════════════════════════════════════════════════════════════

    private fun alignSegments(
        whisperSegments: List<GroqApiClient.Segment>,
        vadSegments: List<SpeechSegment>,
        pcmData: FloatArray
    ): List<RefinedSegment> {
        return whisperSegments.map { ws -> alignSingleSegment(ws, vadSegments, pcmData) }
    }

    private fun alignSingleSegment(
        ws: GroqApiClient.Segment,
        vadSegments: List<SpeechSegment>,
        pcmData: FloatArray
    ): RefinedSegment {
        val overlappingVad = vadSegments.filter { vad ->
            vad.startMs < ws.endMs && vad.endMs > ws.startMs
        }

        return when {
            overlappingVad.isEmpty() -> refineWithEnergyAnalysis(ws, pcmData)

            overlappingVad.size == 1 -> {
                val vad = overlappingVad[0]
                RefinedSegment(
                    originalSegment = ws,
                    vadStartMs = refineStartBoundary(ws.startMs, vad.startMs, pcmData),
                    vadEndMs = refineEndBoundary(ws.endMs, vad.endMs, pcmData),
                    confidence = vad.confidence,
                    method = "vad_direct"
                )
            }

            else -> {
                val earliestStart = overlappingVad.minOf { it.startMs }
                val latestEnd = overlappingVad.maxOf { it.endMs }
                val avgConfidence = overlappingVad.map { it.confidence }.average().toFloat()
                RefinedSegment(
                    originalSegment = ws,
                    vadStartMs = refineStartBoundary(ws.startMs, earliestStart, pcmData),
                    vadEndMs = refineEndBoundary(ws.endMs, latestEnd, pcmData),
                    confidence = avgConfidence,
                    method = "vad_multi_merge"
                )
            }
        }
    }

    private fun refineStartBoundary(whisperStartMs: Long, vadStartMs: Long, pcmData: FloatArray): Long {
        if (vadStartMs < whisperStartMs - PRE_ROLL_MS) {
            return findEnergyOnset(pcmData, whisperStartMs, -PRE_ROLL_MS, 200L)
        }
        val paddedStart = (vadStartMs - BOUNDARY_PADDING_MS).coerceAtLeast(0L)
        val energyOnset = findEnergyOnset(pcmData, whisperStartMs, -PRE_ROLL_MS, 200L)
        return maxOf(paddedStart, energyOnset)
    }

    private fun refineEndBoundary(whisperEndMs: Long, vadEndMs: Long, pcmData: FloatArray): Long {
        if (vadEndMs > whisperEndMs + POST_ROLL_MS) {
            return findEnergyOffset(pcmData, whisperEndMs, -200L, POST_ROLL_MS)
        }
        val paddedEnd = vadEndMs + BOUNDARY_PADDING_MS
        val energyOffset = findEnergyOffset(pcmData, whisperEndMs, -200L, POST_ROLL_MS)
        return minOf(paddedEnd, energyOffset)
    }

    private fun refineWithEnergyAnalysis(ws: GroqApiClient.Segment, pcmData: FloatArray): RefinedSegment {
        return RefinedSegment(
            originalSegment = ws,
            vadStartMs = findEnergyOnset(pcmData, ws.startMs, -PRE_ROLL_MS, 200L),
            vadEndMs = findEnergyOffset(pcmData, ws.endMs, -200L, POST_ROLL_MS),
            confidence = 0.3f,
            method = "energy_only"
        )
    }

    private fun findEnergyOnset(
        pcmData: FloatArray, refMs: Long, fromOffset: Long, toOffset: Long
    ): Long {
        val searchStartMs = (refMs + fromOffset).coerceAtLeast(0L)
        val audioDurationMs = pcmData.size.toLong() * 1000 / SAMPLE_RATE
        val searchEndMs = (refMs + toOffset).coerceAtMost(audioDurationMs)

        if (searchStartMs >= searchEndMs) return refMs

        val fineFrameSize = SAMPLE_RATE * 5 / 1000
        val startSample = (searchStartMs * SAMPLE_RATE / 1000).toInt()
        val endSample = (searchEndMs * SAMPLE_RATE / 1000).toInt().coerceAtMost(pcmData.size)

        if (startSample >= endSample) return refMs

        val energies = mutableListOf<Pair<Long, Float>>()
        var pos = startSample
        while (pos + fineFrameSize <= endSample) {
            var sumSq = 0.0
            for (i in 0 until fineFrameSize) {
                sumSq += pcmData[pos + i] * pcmData[pos + i]
            }
            val rms = sqrt(sumSq / fineFrameSize).toFloat()
            val timeMs = pos.toLong() * 1000 / SAMPLE_RATE
            energies.add(Pair(timeMs, rms))
            pos += fineFrameSize / 2
        }

        if (energies.isEmpty()) return refMs

        val sortedEnergies = energies.map { it.second }.sorted()
        val noiseFloorIdx = (sortedEnergies.size * 0.3).toInt().coerceIn(0, sortedEnergies.lastIndex)
        val noiseFloor = sortedEnergies[noiseFloorIdx]
        val threshold = max(noiseFloor * 3.0f, sortedEnergies.last() * 0.08f)

        for ((timeMs, energy) in energies) {
            if (energy > threshold) {
                return (timeMs - 10L).coerceAtLeast(0L)
            }
        }

        return refMs
    }

    private fun findEnergyOffset(
        pcmData: FloatArray, refMs: Long, fromOffset: Long, toOffset: Long
    ): Long {
        val searchStartMs = (refMs + fromOffset).coerceAtLeast(0L)
        val audioDurationMs = pcmData.size.toLong() * 1000 / SAMPLE_RATE
        val searchEndMs = (refMs + toOffset).coerceAtMost(audioDurationMs)

        if (searchStartMs >= searchEndMs) return refMs

        val fineFrameSize = SAMPLE_RATE * 5 / 1000
        val startSample = (searchStartMs * SAMPLE_RATE / 1000).toInt()
        val endSample = (searchEndMs * SAMPLE_RATE / 1000).toInt().coerceAtMost(pcmData.size)

        if (startSample >= endSample) return refMs

        val energies = mutableListOf<Pair<Long, Float>>()
        var pos = startSample
        while (pos + fineFrameSize <= endSample) {
            var sumSq = 0.0
            for (i in 0 until fineFrameSize) {
                sumSq += pcmData[pos + i] * pcmData[pos + i]
            }
            val rms = sqrt(sumSq / fineFrameSize).toFloat()
            val timeMs = pos.toLong() * 1000 / SAMPLE_RATE
            energies.add(Pair(timeMs, rms))
            pos += fineFrameSize / 2
        }

        if (energies.isEmpty()) return refMs

        val sortedEnergies = energies.map { it.second }.sorted()
        val noiseFloorIdx = (sortedEnergies.size * 0.3).toInt().coerceIn(0, sortedEnergies.lastIndex)
        val noiseFloor = sortedEnergies[noiseFloorIdx]
        val threshold = max(noiseFloor * 3.0f, sortedEnergies.last() * 0.08f)

        for (i in energies.lastIndex downTo 0) {
            if (energies[i].second > threshold) {
                return energies[i].first + 10L
            }
        }

        return refMs
    }

    // ══════════════════════════════════════════════════════════════════════
    // POST-PROCESSING
    // ══════════════════════════════════════════════════════════════════════

    private fun postProcessRefinedSegments(segments: List<RefinedSegment>): List<RefinedSegment> {
        val result = segments.toMutableList()

        for (i in result.indices) {
            val seg = result[i]
            var startMs = seg.vadStartMs
            var endMs = seg.vadEndMs

            if (endMs - startMs < MIN_SPEECH_DURATION_MS) {
                startMs = minOf(startMs, seg.originalSegment.startMs)
                endMs = maxOf(endMs, seg.originalSegment.endMs)
            }

            startMs = startMs.coerceIn(
                seg.originalSegment.startMs - PRE_ROLL_MS,
                seg.originalSegment.startMs + 200L
            )
            endMs = endMs.coerceIn(
                seg.originalSegment.endMs - 200L,
                seg.originalSegment.endMs + POST_ROLL_MS
            )

            result[i] = seg.copy(vadStartMs = startMs, vadEndMs = endMs)
        }

        for (i in 1 until result.size) {
            val prev = result[i - 1]
            val curr = result[i]

            if (curr.vadStartMs < prev.vadEndMs + INTER_SEGMENT_GAP_MS) {
                val gapPoint = (prev.vadEndMs + curr.vadStartMs) / 2 - INTER_SEGMENT_GAP_MS / 2
                result[i - 1] = prev.copy(vadEndMs = minOf(prev.vadEndMs, gapPoint))
                result[i] = curr.copy(vadStartMs = maxOf(curr.vadStartMs, gapPoint + INTER_SEGMENT_GAP_MS))
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
