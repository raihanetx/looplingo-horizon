package com.looplingo.horizon.domain.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import com.looplingo.horizon.data.remote.AudioChunk
import java.io.File
import java.nio.ByteBuffer
import timber.log.Timber

@javax.inject.Singleton
class AudioChunker @javax.inject.Inject constructor(
    private val audioPreprocessor: AudioPreprocessor
) {
    companion object {
        internal const val MAX_CHUNKS = 60
        private const val CHUNK_DURATION_SEC = 300.0
        internal const val CHUNK_OVERLAP_SEC = 15.0
        private const val MAX_NO_SPEECH_PROB = 0.6
    }

    internal fun splitAudioIntoChunksWithOverlap(
        context: Context,
        sourceFile: File
    ): List<AudioChunk> {
        val extractor = MediaExtractor()
        val chunks = mutableListOf<AudioChunk>()

        try {
            extractor.setDataSource(sourceFile.absolutePath)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            var audioMime = ""
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    audioMime = mime
                    break
                }
            }
            if (audioTrackIndex < 0 || audioFormat == null) return emptyList()

            extractor.selectTrack(audioTrackIndex)

            val totalDurationUs = try {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } catch (_: Exception) { -1L }
            if (totalDurationUs <= 0) return emptyList()

            val chunkDurationUs = (CHUNK_DURATION_SEC * 1_000_000).toLong()
            val overlapUs = (CHUNK_OVERLAP_SEC * 1_000_000).toLong()
            val stepUs = chunkDurationUs - overlapUs
            val numChunks = maxOf(1, ((totalDurationUs - overlapUs + stepUs - 1) / stepUs).toInt())
            val actualChunks = minOf(numChunks, MAX_CHUNKS)

            Timber.i("Overlap chunking: total=%.1fs, chunk=%.0fs, overlap=%.0fs, step=%.0fs, %d chunks",
                totalDurationUs / 1_000_000.0, CHUNK_DURATION_SEC, CHUNK_OVERLAP_SEC,
                stepUs / 1_000_000.0, actualChunks)

            val outputFormat = when {
                audioMime.contains("aac") || audioMime.contains("mp4a") -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                audioMime.contains("ogg") || audioMime.contains("opus") -> MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
                else -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }
            val outputExt = when (outputFormat) {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 -> "m4a"
                MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG -> "ogg"
                else -> "m4a"
            }

            val buffer = ByteBuffer.allocate(256 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            for (chunkIdx in 0 until actualChunks) {
                val chunkStartUs = chunkIdx.toLong() * stepUs
                val chunkEndUs = minOf(chunkStartUs + chunkDurationUs, totalDurationUs)

                extractor.seekTo(chunkStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                val chunkFile = File.createTempFile("looplingo_chunk_${chunkIdx}_", ".$outputExt", context.cacheDir)
                var muxer: MediaMuxer? = null

                try {
                    muxer = MediaMuxer(chunkFile.absolutePath, outputFormat)
                    val muxerTrackIndex = muxer.addTrack(audioFormat!!)
                    muxer.start()

                    var sampleCount = 0
                    var lastPts = chunkStartUs

                    while (true) {
                        buffer.clear()
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break
                        val pts = extractor.sampleTime
                        if (pts >= chunkEndUs) break

                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.flags = extractor.sampleFlags
                        bufferInfo.presentationTimeUs = pts

                        muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                        sampleCount++
                        lastPts = pts
                        extractor.advance()
                    }

                    muxer.stop()

                    val chunkDuration = (lastPts - chunkStartUs) / 1_000_000.0
                    chunks.add(AudioChunk(
                        file = chunkFile,
                        startTimeSec = chunkStartUs / 1_000_000.0,
                        durationSec = if (chunkDuration > 0) chunkDuration else CHUNK_DURATION_SEC
                    ))

                    Timber.d("Chunk %d/%d: %.1fs-%.1fs, %d samples, %.1fKB",
                        chunkIdx + 1, actualChunks,
                        chunkStartUs / 1_000_000.0, chunkEndUs / 1_000_000.0,
                        sampleCount, chunkFile.length() / 1024.0)

                } catch (e: Exception) {
                    Timber.e(e, "Failed to create chunk %d", chunkIdx)
                    chunkFile.delete()
                } finally {
                    try { muxer?.release() } catch (_: Exception) {}
                }
            }

            return chunks
        } catch (e: Exception) {
            Timber.e(e, "Failed to split audio into overlapping chunks")
            chunks.forEach { it.file.delete() }
            return emptyList()
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    internal fun deduplicateOverlappingSegments(segments: List<com.looplingo.horizon.data.remote.Segment>): List<com.looplingo.horizon.data.remote.Segment> {
        if (segments.size <= 1) return segments

        val sorted = segments.sortedBy { it.startSec }
        val result = mutableListOf<com.looplingo.horizon.data.remote.Segment>()
        var idCounter = 0

        var i = 0
        while (i < sorted.size) {
            val current = sorted[i]

            var mergedCount = 0
            var bestEnd = current.endSec
            var bestText = current.text
            var bestLogprob = current.avgLogprob
            var bestNoSpeechProb = current.noSpeechProb

            var j = i + 1
            while (j < sorted.size && sorted[j].startSec < bestEnd) {
                val next = sorted[j]
                val textSimilarity = computeTextSimilarity(current.text, next.text)

                if (textSimilarity > 0.6) {
                    if (next.avgLogprob > bestLogprob) {
                        bestText = next.text
                        bestEnd = maxOf(bestEnd, next.endSec)
                        bestLogprob = next.avgLogprob
                        bestNoSpeechProb = next.noSpeechProb
                    }
                    mergedCount++
                    j++
                } else {
                    break
                }
            }

            result.add(com.looplingo.horizon.data.remote.Segment(
                id = idCounter++,
                text = bestText.trim(),
                startSec = current.startSec,
                endSec = bestEnd,
                noSpeechProb = bestNoSpeechProb,
                avgLogprob = bestLogprob
            ))
            i += 1 + mergedCount
        }

        return result
    }

    internal fun computeTextSimilarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        val wordsA = a.lowercase().split(Regex("\\s+")).toSet()
        val wordsB = b.lowercase().split(Regex("\\s+")).toSet()
        val intersection = wordsA.intersect(wordsB).size
        val union = wordsA.union(wordsB).size
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    internal data class FilterResult(
        val kept: List<com.looplingo.horizon.data.remote.Segment>,
        val droppedCount: Int,
        val maxDroppedNoSpeechProb: Double
    )

    internal fun filterLowQualitySegments(segments: List<com.looplingo.horizon.data.remote.Segment>): FilterResult {
        val filtered = segments.filter { it.noSpeechProb < MAX_NO_SPEECH_PROB }
        val dropped = segments.filter { it.noSpeechProb >= MAX_NO_SPEECH_PROB }
        val maxDropped = dropped.maxOfOrNull { it.noSpeechProb } ?: 0.0
        if (dropped.isNotEmpty()) {
            Timber.i("Filtered %d low-quality segments (no_speech_prob > %.1f, max=%.2f)",
                dropped.size, MAX_NO_SPEECH_PROB, maxDropped)
        }
        return FilterResult(
            kept = filtered,
            droppedCount = dropped.size,
            maxDroppedNoSpeechProb = maxDropped
        )
    }
}
