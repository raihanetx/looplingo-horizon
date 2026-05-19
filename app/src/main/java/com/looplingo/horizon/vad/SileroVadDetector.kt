package com.looplingo.horizon.vad

import android.content.Context
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * Silero VAD (Voice Activity Detection) neural network wrapper using PyTorch Mobile.
 *
 * Silero VAD is a pre-trained deep learning model specifically designed for
 * speech detection. It's trained on 6,000+ hours of multilingual speech data
 * and achieves >95% accuracy on standard VAD benchmarks — far surpassing
 * hand-crafted heuristic approaches.
 *
 * WHY SILOO VAD OVER THE PREVIOUS CUSTOM VAD:
 *   The previous VadEngine used hand-tuned heuristics (energy, ZCR, spectral
 *   flatness) with fixed weights and thresholds. While this works ~70-80% of
 *   the time, it fails on:
 *   - Quiet speech with low energy but clear formant structure
 *   - Background noise with high energy but non-speech spectral patterns
 *   - Rapid speech transitions where fixed thresholds can't adapt
 *   - Non-speech sounds (claps, clicks) that trigger energy-based VAD
 *
 *   Silero VAD's neural network has learned these distinctions from thousands
 *   of hours of real-world audio. Its LSTM layer captures temporal patterns
 *   (speech has characteristic temporal structure), and its fully-connected
 *   layers learn spectral patterns that distinguish speech from noise.
 *
 * ARCHITECTURE:
 *   - Input: 16KHz mono audio, processed in 512-sample (32ms) chunks
 *   - Model: Deep Dense Network with LSTM for temporal context
 *   - Output: Speech probability (0.0 to 1.0) per chunk
 *   - Internal state: Model maintains LSTM hidden/cell state automatically
 *
 * PYTORCH MOBILE:
 *   We use PyTorch Mobile (pytorch_android_lite) instead of ONNX Runtime because:
 *   1. Silero VAD is natively a PyTorch model — no export artifacts or compatibility issues
 *   2. The model maintains internal LSTM state, which is easier with PyTorch's Module API
 *   3. PyTorch Mobile is optimized for mobile inference
 *   4. The model file is only ~2.2MB in TorchScript format
 *
 * USAGE:
 *   1. Create instance (loads model from assets)
 *   2. Call detectSpeechSegments() with 16KHz mono PCM float array
 *   3. Get back a list of speech segments with start/end times and confidence
 *   4. Call close() when done to release PyTorch module
 */
class SileroVadDetector(private val context: Context) {

    companion object {
        private const val MODEL_FILENAME = "silero_vad.pt"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SIZE = 512          // 512 samples at 16KHz = 32ms per chunk
        private const val CHUNK_DURATION_MS = CHUNK_SIZE.toLong() * 1000 / SAMPLE_RATE  // 32ms

        // Speech probability thresholds
        // Silero VAD produces well-calibrated probabilities.
        // These thresholds are based on the official Silero VAD documentation:
        //   - 0.5 is the default and works well for most cases
        //   - 0.35 is more sensitive (catches quiet speech, fewer false negatives)
        //   - 0.65 is more specific (fewer false positives, might miss quiet speech)
        private const val SPEECH_ON_THRESHOLD = 0.50f
        private const val SPEECH_OFF_THRESHOLD = 0.35f   // Hysteresis: off threshold lower than on

        // Minimum segment duration — filter out very short detections (<100ms)
        private const val MIN_SPEECH_SEGMENT_MS = 100L

        // Minimum silence duration between speech segments to count as a gap
        private const val MIN_SILENCE_GAP_MS = 80L

        // Padding: extend speech segments slightly to avoid cutting off
        // the very beginning/end of speech (consonants can be very short)
        private const val START_PADDING_MS = 30L
        private const val END_PADDING_MS = 30L
    }

    /**
     * A speech segment detected by Silero VAD.
     */
    data class VadSegment(
        val startMs: Long,
        val endMs: Long,
        val confidence: Float,
        val avgProbability: Float
    ) {
        val durationMs: Long get() = endMs - startMs
    }

    private var module: Module? = null
    private var isInitialized = false
    private var initializationFailed = false

    /**
     * Initialize the PyTorch module with the Silero VAD model.
     * Called lazily on first use to avoid slowing down app startup.
     */
    private fun ensureInitialized(): Boolean {
        if (isInitialized) return true
        if (initializationFailed) return false

        return try {
            val modelPath = copyModelToCache()
            if (modelPath == null) {
                Timber.e("Silero VAD: Failed to copy model to cache")
                initializationFailed = true
                return false
            }

            module = Module.load(modelPath)
            isInitialized = true
            Timber.i("Silero VAD: PyTorch model loaded successfully")
            true
        } catch (e: Exception) {
            Timber.e(e, "Silero VAD: Failed to initialize PyTorch module")
            initializationFailed = true
            false
        }
    }

    /**
     * Copy the TorchScript model from assets to cache directory.
     * PyTorch Mobile needs a file path, not an asset input stream.
     */
    private fun copyModelToCache(): String? {
        return try {
            val cacheFile = File(context.cacheDir, "silero_vad_$MODEL_FILENAME")
            // Only copy if not already cached (model doesn't change)
            if (!cacheFile.exists() || cacheFile.length() == 0L) {
                context.assets.open(MODEL_FILENAME).use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            cacheFile.absolutePath
        } catch (e: Exception) {
            Timber.e(e, "Silero VAD: Failed to copy model from assets")
            null
        }
    }

    /**
     * Detect speech segments in 16KHz mono PCM audio using Silero VAD.
     *
     * This is the primary method — it runs the neural network on the entire
     * audio and returns precise speech segments.
     *
     * @param pcmData Float array of 16KHz mono audio samples, normalized to [-1.0, 1.0]
     * @return List of speech segments with timing and confidence
     */
    fun detectSpeechSegments(pcmData: FloatArray): List<VadSegment> {
        if (!ensureInitialized()) {
            Timber.w("Silero VAD: Not initialized — returning empty segments")
            return emptyList()
        }

        if (pcmData.size < CHUNK_SIZE) {
            Timber.w("Silero VAD: Audio too short (%d samples, need %d)", pcmData.size, CHUNK_SIZE)
            return emptyList()
        }

        val currentModule = module ?: return emptyList()

        try {
            val numChunks = pcmData.size / CHUNK_SIZE
            val speechProbabilities = FloatArray(numChunks)

            // Reset the model's internal state (LSTM hidden/cell states)
            // This is done by calling reset_states() on the model
            try {
                currentModule.runMethod("reset_states")
            } catch (e: Exception) {
                // reset_states might not be available in all versions — that's OK
                Timber.d(e, "Silero VAD: reset_states not available (non-critical)")
            }

            // Process audio chunk by chunk through the neural network
            for (i in 0 until numChunks) {
                val offset = i * CHUNK_SIZE
                val chunk = pcmData.copyOfRange(offset, offset + CHUNK_SIZE)

                // Create input tensor: shape [1, 512]
                val inputTensor = Tensor.fromBlob(chunk, longArrayOf(1, CHUNK_SIZE.toLong()))

                // Run inference: model(audio_chunk, sample_rate)
                // The model maintains internal LSTM state automatically
                // Note: IValue.from() requires Long, not Int, for integer values
                val output = currentModule.forward(
                    IValue.from(inputTensor),
                    IValue.from(SAMPLE_RATE.toLong())
                )

                // Extract speech probability from output tensor [1, 1]
                val outputTensor = output.toTensor()
                val probs = outputTensor.dataAsFloatArray
                if (probs.isNotEmpty()) {
                    speechProbabilities[i] = probs[0]
                }
            }

            // Convert probabilities to speech segments using hysteresis thresholding
            return probabilitiesToSegments(speechProbabilities)

        } catch (e: OutOfMemoryError) {
            Timber.e(e, "Silero VAD: OOM during inference — audio may be too long")
            return emptyList()
        } catch (e: Exception) {
            Timber.e(e, "Silero VAD: Error during inference")
            return emptyList()
        }
    }

    /**
     * Convert per-chunk speech probabilities into discrete speech segments.
     *
     * Uses hysteresis thresholding to prevent rapid on/off switching:
     *   - Speech ON when probability rises above SPEECH_ON_THRESHOLD (0.50)
     *   - Speech OFF when probability drops below SPEECH_OFF_THRESHOLD (0.35)
     *
     * This is the same principle as the old VAD, but with much better
     * probabilities from the neural network.
     */
    private fun probabilitiesToSegments(probabilities: FloatArray): List<VadSegment> {
        if (probabilities.isEmpty()) return emptyList()

        // Step 1: Apply hysteresis threshold to get binary speech decisions
        val isSpeech = BooleanArray(probabilities.size)
        var currentlySpeech = false

        for (i in probabilities.indices) {
            if (!currentlySpeech) {
                if (probabilities[i] >= SPEECH_ON_THRESHOLD) {
                    currentlySpeech = true
                }
            } else {
                if (probabilities[i] < SPEECH_OFF_THRESHOLD) {
                    currentlySpeech = false
                }
            }
            isSpeech[i] = currentlySpeech
        }

        // Step 2: Group consecutive speech chunks into segments
        val rawSegments = mutableListOf<VadSegment>()
        var segStart = -1
        var segProbSum = 0f
        var segProbCount = 0

        for (i in isSpeech.indices) {
            if (isSpeech[i] && segStart < 0) {
                segStart = i
                segProbSum = probabilities[i]
                segProbCount = 1
            } else if (isSpeech[i] && segStart >= 0) {
                segProbSum += probabilities[i]
                segProbCount++
            } else if (!isSpeech[i] && segStart >= 0) {
                val startMs = segStart.toLong() * CHUNK_DURATION_MS
                val endMs = i.toLong() * CHUNK_DURATION_MS
                val avgProb = if (segProbCount > 0) segProbSum / segProbCount else 0f
                rawSegments.add(VadSegment(
                    startMs = startMs,
                    endMs = endMs,
                    confidence = avgProb,
                    avgProbability = avgProb
                ))
                segStart = -1
                segProbSum = 0f
                segProbCount = 0
            }
        }

        // Handle segment that extends to end of audio
        if (segStart >= 0) {
            val startMs = segStart.toLong() * CHUNK_DURATION_MS
            val endMs = isSpeech.size.toLong() * CHUNK_DURATION_MS
            val avgProb = if (segProbCount > 0) segProbSum / segProbCount else 0f
            rawSegments.add(VadSegment(
                startMs = startMs,
                endMs = endMs,
                confidence = avgProb,
                avgProbability = avgProb
            ))
        }

        // Step 3: Merge segments with short gaps between them
        val mergedSegments = mergeShortGaps(rawSegments)

        // Step 4: Filter out very short segments (likely false positives)
        val filteredSegments = mergedSegments.filter { it.durationMs >= MIN_SPEECH_SEGMENT_MS }

        // Step 5: Add padding to segment boundaries
        val paddedSegments = filteredSegments.map { seg ->
            seg.copy(
                startMs = (seg.startMs - START_PADDING_MS).coerceAtLeast(0L),
                endMs = seg.endMs + END_PADDING_MS
            )
        }

        // Step 6: Ensure no overlapping segments after padding
        val nonOverlapping = removeOverlaps(paddedSegments)

        Timber.i("Silero VAD: %d segments from %d chunks (avg prob: %.2f)",
            nonOverlapping.size, probabilities.size,
            if (probabilities.isNotEmpty()) probabilities.average().toFloat() else 0f)

        return nonOverlapping
    }

    /**
     * Merge segments with silence gaps shorter than MIN_SILENCE_GAP_MS.
     * Short pauses within continuous speech (e.g., between words) should
     * not split a segment.
     */
    private fun mergeShortGaps(segments: List<VadSegment>): List<VadSegment> {
        if (segments.size <= 1) return segments

        val merged = mutableListOf<VadSegment>()
        var current = segments[0]

        for (i in 1 until segments.size) {
            val gap = segments[i].startMs - current.endMs
            if (gap < MIN_SILENCE_GAP_MS) {
                // Merge: extend current segment to include this one
                val newProbSum = current.avgProbability * current.durationMs +
                        segments[i].avgProbability * segments[i].durationMs
                val newDuration = (segments[i].endMs - current.startMs)
                current = current.copy(
                    endMs = segments[i].endMs,
                    confidence = maxOf(current.confidence, segments[i].confidence),
                    avgProbability = newProbSum / newDuration.coerceAtLeast(1L)
                )
            } else {
                merged.add(current)
                current = segments[i]
            }
        }
        merged.add(current)
        return merged
    }

    /**
     * Remove overlaps between consecutive segments (can happen after padding).
     * Splits at the midpoint of the overlap.
     */
    private fun removeOverlaps(segments: List<VadSegment>): List<VadSegment> {
        if (segments.size <= 1) return segments

        val result = segments.toMutableList()
        for (i in 1 until result.size) {
            val prev = result[i - 1]
            val curr = result[i]
            if (curr.startMs < prev.endMs) {
                val gapPoint = (prev.endMs + curr.startMs) / 2
                result[i - 1] = prev.copy(endMs = gapPoint)
                result[i] = curr.copy(startMs = gapPoint)
            }
        }
        return result
    }

    /**
     * Release PyTorch module resources.
     * Call when the detector is no longer needed.
     */
    fun close() {
        try {
            module?.destroy()
            module = null
            isInitialized = false
            Timber.i("Silero VAD: Resources released")
        } catch (e: Exception) {
            Timber.w(e, "Silero VAD: Error closing module")
        }
    }
}
