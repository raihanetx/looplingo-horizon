package com.looplingo.horizon.data.remote

import android.content.Context
import com.looplingo.horizon.domain.audio.AudioChunker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChunkedTranscriber @Inject constructor(
    private val audioChunker: AudioChunker,
    private val whisperApiClient: WhisperApiClient
) {
    companion object {
        private const val MAX_CONCURRENT_CHUNKS = 3
    }

    private val apiSemaphore = Semaphore(MAX_CONCURRENT_CHUNKS)

    internal suspend fun chunkAndTranscribe(
        context: Context,
        apiKey: String,
        audioFile: File,
        language: String,
        onProgress: ProgressCallback?
    ): List<Segment> = withContext(Dispatchers.IO) {
        onProgress?.onProgress("[Chunk] Splitting audio into chunks with overlap…")

        val chunks = audioChunker.splitAudioIntoChunksWithOverlap(context, audioFile)
        if (chunks.isEmpty()) {
            onProgress?.onProgress("[Chunk] Could not split audio")
            return@withContext emptyList()
        }

        onProgress?.onProgress("[Chunk] %d chunks with %.0fs overlap, transcribing…".format(
            chunks.size, AudioChunker.CHUNK_OVERLAP_SEC))
        Timber.i("Chunking: %d chunks, %.0fs overlap", chunks.size, AudioChunker.CHUNK_OVERLAP_SEC)

        val result = transcribeChunksWithOverlap(apiKey, chunks, language, onProgress)
        if (result.isNotEmpty()) {
            onProgress?.onProgress("[Chunk] ✓ %d segments from %d chunks!".format(result.size, chunks.size))
        } else {
            onProgress?.onProgress("[Chunk] No speech detected in any chunk")
        }
        result
    }

    internal suspend fun transcribeChunksWithOverlap(
        apiKey: String,
        chunks: List<AudioChunk>,
        language: String,
        onProgress: ProgressCallback?
    ): List<Segment> = withContext(Dispatchers.IO) {
        if (chunks.isEmpty()) return@withContext emptyList()

        val allSegments = mutableListOf<Segment>()
        var segmentIdOffset = 0
        var previousTranscript = ""
        var failedChunks = 0
        var emptyChunks = 0
        var failedChunkErrors = mutableListOf<String>()

        val groups = chunks.chunked(MAX_CONCURRENT_CHUNKS)

        for ((groupIdx, group) in groups.withIndex()) {
            val groupStart = groupIdx * MAX_CONCURRENT_CHUNKS + 1
            val groupEnd = minOf(groupStart + group.size - 1, chunks.size)
            onProgress?.onProgress("[Chunks $groupStart-$groupEnd/${chunks.size}] Transcribing in parallel…")

            val groupPrompt = if (previousTranscript.isNotBlank()) {
                val words = previousTranscript.split(" ")
                words.takeLast(180).joinToString(" ")
            } else null

            val results = coroutineScope {
                group.mapIndexed { idx, chunk ->
                    async(Dispatchers.IO) {
                        apiSemaphore.withPermit {
                            val chunkNum = (groupIdx * MAX_CONCURRENT_CHUNKS) + idx + 1
                            try {
                                val prompt = if (idx == 0) groupPrompt else null
                                val segments = whisperApiClient.callWhisperApi(apiKey, chunk.file, language, prompt = prompt)
                                ChunkResult(chunkIdx = chunkNum, chunk = chunk, segments = segments, error = null)
                            } catch (e: Exception) {
                                ChunkResult(chunkIdx = chunkNum, chunk = chunk, segments = emptyList(), error = e)
                            }
                        }
                    }
                }.map { it.await() }
            }

            for (result in results) {
                if (result.error != null) {
                    failedChunks++
                    failedChunkErrors.add("chunk${result.chunkIdx}:${result.error.message?.take(80)}")
                    Timber.e(result.error, "Failed to transcribe chunk %d/%d", result.chunkIdx, chunks.size)
                } else if (result.segments.isEmpty()) {
                    val lastResp = whisperApiClient.getLastWhisperResponse()
                    val fallbackText = extractTextFromResponse(lastResp)
                    if (fallbackText.isNotBlank()) {
                        val sentences = whisperApiClient.splitIntoSentences(fallbackText)
                        val chunkStart = result.chunk.startTimeSec
                        val chunkDuration = result.chunk.durationSec
                        val timePerChar = if (fallbackText.isNotEmpty() && chunkDuration > 0) chunkDuration / fallbackText.length else 1.0
                        var currentTime = chunkStart
                        val segmentsToCreate = if (sentences.size > 1) sentences else listOf(fallbackText)
                        Timber.w("Chunk %d returned empty segments but response has text — creating %d fallback segments", result.chunkIdx, segmentsToCreate.size)
                        for (sentence in segmentsToCreate) {
                            val sentenceDuration = sentence.length * timePerChar
                            allSegments.add(Segment(
                                id = segmentIdOffset,
                                text = sentence.trim(),
                                startSec = currentTime,
                                endSec = currentTime + sentenceDuration,
                                noSpeechProb = 0.0,
                                avgLogprob = 0.0
                            ))
                            segmentIdOffset++
                            currentTime += sentenceDuration
                        }
                        previousTranscript = fallbackText
                    } else {
                        emptyChunks++
                        Timber.w("Chunk %d returned empty segments and no usable text", result.chunkIdx)
                    }
                } else {
                    for (seg in result.segments) {
                        allSegments.add(Segment(
                            id = segmentIdOffset + seg.id,
                            text = seg.text,
                            startSec = seg.startSec + result.chunk.startTimeSec,
                            endSec = seg.endSec + result.chunk.startTimeSec,
                            noSpeechProb = seg.noSpeechProb,
                            avgLogprob = seg.avgLogprob
                        ))
                    }
                    segmentIdOffset += result.segments.size

                    previousTranscript = result.segments.joinToString(" ") { it.text }
                }
                result.chunk.file.delete()
            }
        }

        Timber.i("Transcription: %d segments from %d chunks (%d empty, %d failed)",
            allSegments.size, chunks.size, emptyChunks, failedChunks)

        if (allSegments.isEmpty()) {
            val diag = buildString {
                append("chunks=${chunks.size}, empty=$emptyChunks, failed=$failedChunks")
                if (failedChunkErrors.isNotEmpty()) {
                    append(", errors=[${failedChunkErrors.joinToString("; ")}]")
                }
                val lastResp = whisperApiClient.getLastWhisperResponse().take(200)
                append(", lastAPI=$lastResp")
            }
            Timber.w("Transcription returned empty: %s", diag)
            lastDiagnostics = diag
        }

        val deduped = audioChunker.deduplicateOverlappingSegments(allSegments)
        Timber.i("After deduplication: %d segments (removed %d overlaps)",
            deduped.size, allSegments.size - deduped.size)

        deduped
    }

    @Volatile
    var lastDiagnostics: String = ""
        private set

    private fun extractTextFromResponse(rawResponse: String): String {
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
