package com.looplingo.horizon.domain.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.looplingo.horizon.data.remote.AudioChunk
import com.looplingo.horizon.data.remote.PcmAnalysisResult
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WavProcessor @Inject constructor() {
    companion object {
        private const val TARGET_SAMPLE_RATE = 16000
        private const val TARGET_CHANNELS = 1
        private const val WAV_BITS_PER_SAMPLE = 16
        private const val WAV_AUDIO_FORMAT_PCM = 1
        private const val CODEC_TIMEOUT_US = 10_000L
    }

    internal fun decodeTo16kHzMonoWavChunks(
        context: Context,
        sourceFile: File,
        chunkDurationSec: Double = 300.0
    ): List<AudioChunk> {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val chunks = mutableListOf<AudioChunk>()

        try {
            extractor.setDataSource(sourceFile.absolutePath)

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
            if (audioTrackIndex < 0 || inputFormat == null) return emptyList()

            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            extractor.selectTrack(audioTrackIndex)

            val srcSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            val dec = decoder!!

            var outputFormatChecked = false
            var decodedSampleRate = srcSampleRate
            var decodedChannels = srcChannels

            val pcmBuffer = mutableListOf<ByteArray>()
            var pcmBufferSize = 0L
            val targetBytesPerSec = TARGET_SAMPLE_RATE.toLong() * TARGET_CHANNELS * 2
            val chunkSizeBytes = (targetBytesPerSec * chunkDurationSec).toLong()
            var chunkIndex = 0
            var chunkStartTimeBytes = 0L

            var inputDone = false
            var outputDone = false
            val bufferInfo = MediaCodec.BufferInfo()

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = dec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val size = extractor.readSampleData(inBuf, 0)
                            if (size < 0) {
                                dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                dec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = dec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                when {
                    outIdx >= 0 -> {
                        if (!outputFormatChecked) {
                            val fmt = dec.outputFormat
                            try { decodedSampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) {}
                            try { decodedChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) {}
                            outputFormatChecked = true
                        }
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (bufferInfo.size > 0) {
                            val outBuf = dec.getOutputBuffer(outIdx)
                            if (outBuf != null) {
                                val pcmData = ByteArray(bufferInfo.size)
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                outBuf.get(pcmData)

                                val downsampled = downsamplePcm(pcmData, decodedSampleRate, decodedChannels,
                                    TARGET_SAMPLE_RATE, TARGET_CHANNELS)
                                pcmBuffer.add(downsampled)
                                pcmBufferSize += downsampled.size

                                if (pcmBufferSize >= chunkSizeBytes) {
                                    val startTimeSec = chunkStartTimeBytes.toDouble() / targetBytesPerSec
                                    val durationSec = pcmBufferSize.toDouble() / targetBytesPerSec
                                    val chunkFile = writeWavFile(pcmBuffer, TARGET_SAMPLE_RATE, TARGET_CHANNELS, context, chunkIndex)
                                    chunks.add(AudioChunk(chunkFile, startTimeSec, durationSec))
                                    chunkStartTimeBytes += pcmBufferSize
                                    chunkIndex++
                                    pcmBuffer.clear()
                                    pcmBufferSize = 0L
                                    if (chunks.size >= 60) break
                                }
                            }
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = dec.outputFormat
                        try { decodedSampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) {}
                        try { decodedChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) {}
                        outputFormatChecked = true
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* wait */ }
                }
            }

            if (pcmBuffer.isNotEmpty() && pcmBufferSize > 0 && chunks.size < 60) {
                val startTimeSec = chunkStartTimeBytes.toDouble() / targetBytesPerSec
                val durationSec = pcmBufferSize.toDouble() / targetBytesPerSec
                if (durationSec >= 0.5) {
                    val chunkFile = writeWavFile(pcmBuffer, TARGET_SAMPLE_RATE, TARGET_CHANNELS, context, chunkIndex)
                    chunks.add(AudioChunk(chunkFile, startTimeSec, durationSec))
                }
            }

            Timber.i("16KHz mono WAV: %d chunks", chunks.size)
            return chunks
        } catch (e: Exception) {
            Timber.e(e, "Failed to decode to 16KHz mono WAV")
            chunks.forEach { it.file.delete() }
            return emptyList()
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
        }
    }

    internal fun writeWavFile(
        pcmChunks: List<ByteArray>,
        sampleRate: Int,
        channels: Int,
        context: Context,
        chunkIndex: Int
    ): File {
        val dataSize = pcmChunks.sumOf { it.size }
        val byteRate = sampleRate * channels * WAV_BITS_PER_SAMPLE / 8
        val blockAlign = channels * WAV_BITS_PER_SAMPLE / 8
        val fileSize = 36 + dataSize

        val outputFile = File.createTempFile("looplingo_wav_${chunkIndex}_", ".wav", context.cacheDir)

        FileOutputStream(outputFile).use { fos ->
            val out = java.io.BufferedOutputStream(fos, 32 * 1024)

            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            out.write(intToLittleEndian(fileSize))
            out.write("WAVE".toByteArray(Charsets.US_ASCII))

            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            out.write(intToLittleEndian(16))
            out.write(shortToLittleEndian(WAV_AUDIO_FORMAT_PCM))
            out.write(shortToLittleEndian(channels))
            out.write(intToLittleEndian(sampleRate))
            out.write(intToLittleEndian(byteRate))
            out.write(shortToLittleEndian(blockAlign))
            out.write(shortToLittleEndian(WAV_BITS_PER_SAMPLE))

            out.write("data".toByteArray(Charsets.US_ASCII))
            out.write(intToLittleEndian(dataSize))

            for (chunk in pcmChunks) { out.write(chunk) }
            out.flush()
        }
        return outputFile
    }

    internal fun analyzeWavPcm(wavFile: File): PcmAnalysisResult {
        try {
            val raf = RandomAccessFile(wavFile, "r")
            try {
                if (raf.length() < 44) return PcmAnalysisResult(wavFile.length(), 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0, false)

                raf.seek(22)
                val channels = raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8)
                raf.seek(24)
                val sampleRate = raf.read() or (raf.read() shl 8) or (raf.read() shl 16) or (raf.read() shl 24)
                raf.seek(34)
                val bitsPerSample = raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8)

                var dataOffset = -1L
                var dataSize = 0
                raf.seek(12)
                while (raf.filePointer < raf.length() - 8) {
                    val markerBytes = ByteArray(4)
                    raf.read(markerBytes)
                    val marker = String(markerBytes, Charsets.US_ASCII)
                    val chunkSize = raf.read() or (raf.read() shl 8) or (raf.read() shl 16) or (raf.read() shl 24)
                    if (marker == "data") { dataOffset = raf.filePointer; dataSize = chunkSize; break }
                    raf.skipBytes(if (chunkSize % 2 != 0) chunkSize + 1 else chunkSize)
                }

                if (dataOffset < 0 || dataSize <= 0)
                    return PcmAnalysisResult(wavFile.length(), 0, sampleRate, channels, bitsPerSample, 0, 0, 0, 0.0, 0.0, false)

                val pcmEnd = minOf(dataOffset + dataSize, raf.length())
                val bytesPerSample = bitsPerSample / 8
                val totalSamples = ((pcmEnd - dataOffset) / bytesPerSample).toInt()
                val samplesToCheck = minOf(5000, totalSamples)
                val step = if (totalSamples > samplesToCheck) totalSamples / samplesToCheck else 1

                var minSample = Int.MAX_VALUE
                var maxSample = Int.MIN_VALUE
                var sumAbs = 0L
                var nonZeroCount = 0

                for (i in 0 until samplesToCheck) {
                    val sampleOffset = dataOffset + (i * step * bytesPerSample)
                    if (sampleOffset + bytesPerSample > pcmEnd) break
                    raf.seek(sampleOffset)
                    val sample = if (bytesPerSample == 2) {
                        val low = raf.readUnsignedByte()
                        val high = raf.readByte().toInt()
                        (high shl 8) or low
                    } else raf.readUnsignedByte()
                    if (sample < minSample) minSample = sample
                    if (sample > maxSample) maxSample = sample
                    sumAbs += kotlin.math.abs(sample)
                    if (kotlin.math.abs(sample) > 10) nonZeroCount++
                }

                val meanAbs = if (samplesToCheck > 0) sumAbs.toDouble() / samplesToCheck else 0.0
                val nonZeroPct = if (samplesToCheck > 0) nonZeroCount * 100.0 / samplesToCheck else 0.0

                return PcmAnalysisResult(
                    fileBytes = wavFile.length(), pcmDataBytes = dataSize,
                    sampleRate = sampleRate, channels = channels, bitsPerSample = bitsPerSample,
                    totalSamples = totalSamples,
                    minSample = if (minSample == Int.MAX_VALUE) 0 else minSample,
                    maxSample = if (maxSample == Int.MIN_VALUE) 0 else maxSample,
                    meanAbsSample = meanAbs, nonZeroPercent = nonZeroPct,
                    hasAudio = nonZeroPct >= 1.0 && meanAbs >= 50.0
                )
            } finally { raf.close() }
        } catch (e: Exception) {
            Timber.e(e, "Failed to analyze WAV PCM")
            return PcmAnalysisResult(wavFile.length(), 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0, false)
        }
    }

    internal fun intToLittleEndian(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    internal fun shortToLittleEndian(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte()
    )

    internal fun downsamplePcm(
        pcm: ByteArray,
        srcSampleRate: Int,
        srcChannels: Int,
        targetSampleRate: Int,
        targetChannels: Int
    ): ByteArray {
        val bytesPerSample = 2
        val srcFrameSize = bytesPerSample * srcChannels
        val srcFrames = pcm.size / srcFrameSize

        if (srcFrames == 0) return ByteArray(0)

        val monoPcm = if (srcChannels > 1) {
            ByteArray(srcFrames * bytesPerSample)
        } else {
            pcm
        }

        if (srcChannels > 1) {
            for (i in 0 until srcFrames) {
                var sum = 0L
                for (ch in 0 until srcChannels) {
                    val offset = i * srcFrameSize + ch * bytesPerSample
                    val low = pcm[offset].toInt() and 0xFF
                    val high = pcm[offset + 1].toInt()
                    val sample = (high shl 8) or low
                    sum += sample
                }
                val avg = (sum / srcChannels).toInt().coerceIn(-32768, 32767)
                monoPcm[i * 2] = (avg and 0xFF).toByte()
                monoPcm[i * 2 + 1] = ((avg shr 8) and 0xFF).toByte()
            }
        }

        if (srcSampleRate == targetSampleRate) {
            return monoPcm
        }

        val srcMonoFrames = monoPcm.size / bytesPerSample
        val ratio = srcSampleRate.toDouble() / targetSampleRate
        val targetFrames = (srcMonoFrames / ratio).toInt()
        if (targetFrames <= 0) return ByteArray(0)

        val result = ByteArray(targetFrames * bytesPerSample)

        for (i in 0 until targetFrames) {
            val srcPos = i * ratio
            val srcIdx = srcPos.toInt()
            val frac = srcPos - srcIdx

            val sample = if (srcIdx + 1 < srcMonoFrames) {
                val s0 = ((monoPcm[srcIdx * 2 + 1].toInt() shl 8) or (monoPcm[srcIdx * 2].toInt() and 0xFF))
                val s1 = ((monoPcm[(srcIdx + 1) * 2 + 1].toInt() shl 8) or (monoPcm[(srcIdx + 1) * 2].toInt() and 0xFF))
                (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767)
            } else if (srcIdx < srcMonoFrames) {
                (monoPcm[srcIdx * 2 + 1].toInt() shl 8) or (monoPcm[srcIdx * 2].toInt() and 0xFF)
            } else {
                0
            }

            result[i * 2] = (sample and 0xFF).toByte()
            result[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }

        return result
    }

    internal fun flattenPcmBuffer(chunks: List<ByteArray>): ByteArray {
        val totalSize = chunks.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.size)
            offset += chunk.size
        }
        return result
    }
}
