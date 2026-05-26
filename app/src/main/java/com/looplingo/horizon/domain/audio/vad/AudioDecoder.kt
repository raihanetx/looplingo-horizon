package com.looplingo.horizon.domain.audio.vad

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioDecoder @Inject constructor() {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CODEC_TIMEOUT_US = 10_000L
    }

    internal fun decodeToPcmFloatArray(filePath: String): FloatArray? {
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

    internal fun decodeViaMediaCodec(filePath: String): ByteArray? {
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

    internal fun readWavPcm(filePath: String): ByteArray? {
        return try {
            val file = File(filePath)
            if (!file.exists()) return null

            val raf = java.io.RandomAccessFile(file, "r")
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

    internal fun downsamplePcm(
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

    internal fun pcmBytesToFloatArray(pcmBytes: ByteArray): FloatArray {
        val samples = pcmBytes.size / 2
        val floatArray = FloatArray(samples)
        val buffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until samples) {
            floatArray[i] = buffer.get().toFloat() / 32768.0f
        }
        return floatArray
    }
}
