package com.looplingo.horizon.data.remote

import com.google.gson.annotations.SerializedName
import java.io.File

data class Segment(
    val id: Int,
    val text: String,
    @SerializedName("start")
    val startSec: Double,
    @SerializedName("end")
    val endSec: Double,
    val noSpeechProb: Double = 0.0,
    val avgLogprob: Double = 0.0
) {
    val startMs: Long get() = Math.round(startSec * 1000)
    val endMs: Long get() = Math.round(endSec * 1000)
}

fun interface ProgressCallback {
    fun onProgress(step: String)
}

open class SubtitleException(message: String) : Exception(message)

class ApiKeyException(message: String) : SubtitleException(message)

internal data class TranscriptionResponse(
    val segments: List<SegmentJson>? = null,
    val text: String? = null,
    val error: ErrorJson? = null
)

internal data class SegmentJson(
    val id: Int,
    val text: String,
    val start: Double,
    val end: Double,
    @SerializedName("no_speech_prob")
    val noSpeechProb: Double? = null,
    @SerializedName("avg_logprob")
    val avgLogprob: Double? = null
)

internal data class ErrorJson(
    val message: String? = null,
    val type: String? = null
)

internal data class AudioChunk(
    val file: File,
    val startTimeSec: Double,
    val durationSec: Double
)

data class TranscriptionWithTranslation(
    val segments: List<Segment>,
    val translatedTexts: Map<Int, String>,  // segment.id -> translated text
    val sourceLanguage: String,
    val targetLanguage: String
)

internal data class ChatCompletionResponse(
    val choices: List<ChatChoice>? = null
)

internal data class ChatChoice(
    val message: ChatMessage? = null
)

internal data class ChatMessage(
    val content: String? = null
)

internal data class ChunkResult(
    val chunkIdx: Int,
    val chunk: AudioChunk,
    val segments: List<Segment>,
    val error: Exception?
)

internal data class PcmAnalysisResult(
    val fileBytes: Long,
    val pcmDataBytes: Int,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val totalSamples: Int,
    val minSample: Int,
    val maxSample: Int,
    val meanAbsSample: Double,
    val nonZeroPercent: Double,
    val hasAudio: Boolean
) {
    fun summary() = "file=${fileBytes}B, pcm=${pcmDataBytes}B, " +
        "${sampleRate}Hz, ${channels}ch, ${bitsPerSample}bit, " +
        "samples=$totalSamples, range=[$minSample..$maxSample], " +
        "meanAbs=${"%.1f".format(meanAbsSample)}, " +
        "nonZero=${"%.1f".format(nonZeroPercent)}%"
}
