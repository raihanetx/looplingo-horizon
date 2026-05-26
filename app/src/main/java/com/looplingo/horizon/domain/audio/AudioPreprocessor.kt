package com.looplingo.horizon.domain.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer

@javax.inject.Singleton
class AudioPreprocessor @javax.inject.Inject constructor(
    private val wavProcessor: WavProcessor
) {
    companion object {
        private const val TARGET_SAMPLE_RATE = 16000
        private const val TARGET_CHANNELS = 1
        private const val TARGET_BITRATE = 64000
        private const val TARGET_MIME = "audio/mp4a-latm"
        private const val NORMALIZATION_TARGET = 0.9
        private const val CODEC_TIMEOUT_US = 10_000L
    }

    internal fun preProcessTo16kHzMonoAac(context: Context, sourceFile: File): File? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

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
            if (audioTrackIndex < 0 || inputFormat == null) return null

            val srcMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(audioTrackIndex)

            decoder = MediaCodec.createDecoderByType(srcMime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            val dec = decoder!!

            var decodedSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var decodedChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var outputFormatChecked = false

            val pcmBuffer = mutableListOf<ByteArray>()
            var pcmBufferSize = 0L
            val targetBytesPerSec = TARGET_SAMPLE_RATE.toLong() * TARGET_CHANNELS * 2

            var inputDone = false
            var outputDone = false
            val bufferInfo = MediaCodec.BufferInfo()
            var totalPcmSamples = 0L

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
                            Timber.i("Decoded: %dHz, %dch → target: %dHz, %dch",
                                decodedSampleRate, decodedChannels, TARGET_SAMPLE_RATE, TARGET_CHANNELS)
                        }

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (bufferInfo.size > 0) {
                            val outBuf = dec.getOutputBuffer(outIdx)
                            if (outBuf != null) {
                                val pcmData = ByteArray(bufferInfo.size)
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                outBuf.get(pcmData)

                                val downsampled = wavProcessor.downsamplePcm(pcmData, decodedSampleRate, decodedChannels,
                                    TARGET_SAMPLE_RATE, TARGET_CHANNELS)
                                pcmBuffer.add(downsampled)
                                pcmBufferSize += downsampled.size
                                totalPcmSamples += downsampled.size / 2

                                if (pcmBufferSize > 150L * 1024 * 1024) {
                                    Timber.w("PCM buffer exceeded 150MB — too large for in-memory encoding, falling back")
                                    dec.stop()
                                    dec.release()
                                    decoder = null
                                    return null
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

            dec.stop()
            dec.release()
            decoder = null

            if (pcmBuffer.isEmpty() || pcmBufferSize == 0L) {
                Timber.w("No PCM data decoded for pre-processing")
                return null
            }

            Timber.i("Decoded + downsampled: %d bytes of 16KHz mono PCM", pcmBufferSize)

            val outputFile = File.createTempFile("looplingo_pp_", ".m4a", context.cacheDir)

            val encoderFormat = MediaFormat.createAudioFormat(TARGET_MIME, TARGET_SAMPLE_RATE, TARGET_CHANNELS)
            encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, TARGET_BITRATE)
            encoderFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)

            encoder = MediaCodec.createEncoderByType(TARGET_MIME)
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val enc = encoder!!

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerTrackIndex = -1
            var muxerStarted = false

            val inputChunks = wavProcessor.flattenPcmBuffer(pcmBuffer)
            pcmBuffer.clear()

            var inputOffset = 0
            var encoderInputDone = false
            var encoderOutputDone = false
            var sampleCount = 0
            val encBufferInfo = MediaCodec.BufferInfo()

            while (!encoderOutputDone) {
                if (!encoderInputDone) {
                    val inIdx = enc.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = enc.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val remaining = inputChunks.size - inputOffset
                            if (remaining <= 0) {
                                enc.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                encoderInputDone = true
                            } else {
                                val size = minOf(remaining, inBuf.capacity())
                                inBuf.clear()
                                inBuf.put(inputChunks, inputOffset, size)
                                enc.queueInputBuffer(inIdx, 0, size,
                                    (inputOffset.toLong() / targetBytesPerSec) * 1_000_000, 0)
                                inputOffset += size
                            }
                        }
                    }
                }

                val outIdx = enc.dequeueOutputBuffer(encBufferInfo, CODEC_TIMEOUT_US)
                when {
                    outIdx >= 0 -> {
                        if (encBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderOutputDone = true
                        }

                        if (!muxerStarted && encBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            val encFormat = enc.outputFormat
                            muxerTrackIndex = muxer.addTrack(encFormat)
                            muxer.start()
                            muxerStarted = true
                        }

                        if (encBufferInfo.size > 0 && muxerStarted) {
                            val outBuf = enc.getOutputBuffer(outIdx)
                            if (outBuf != null) {
                                outBuf.position(encBufferInfo.offset)
                                outBuf.limit(encBufferInfo.offset + encBufferInfo.size)
                                muxer.writeSampleData(muxerTrackIndex, outBuf, encBufferInfo)
                                sampleCount++
                            }
                        }
                        enc.releaseOutputBuffer(outIdx, false)
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            val encFormat = enc.outputFormat
                            muxerTrackIndex = muxer.addTrack(encFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* wait */ }
                }
            }

            enc.stop()
            enc.release()
            encoder = null

            if (muxerStarted) muxer.stop()
            muxer.release()
            muxer = null

            if (outputFile.length() == 0L) {
                outputFile.delete()
                return null
            }

            Timber.i("Pre-processed: %s (%.1fKB, %d AAC frames)",
                outputFile.name, outputFile.length() / 1024.0, sampleCount)

            return outputFile

        } catch (e: Exception) {
            Timber.e(e, "Failed to pre-process to 16KHz mono AAC")
            return null
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    internal fun isAudioFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        if (ext in listOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "wma", "3gp")) {
            return true
        }
        return try {
            val bytes = file.inputStream().use { it.readNBytes(12) }
            when {
                bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFB.toByte() -> true
                bytes.size >= 3 && bytes[0] == 0x49.toByte() && bytes[1] == 0x44.toByte() && bytes[2] == 0x33.toByte() -> true
                bytes.size >= 4 && String(bytes, 0, 4) == "OggS" -> true
                bytes.size >= 4 && String(bytes, 0, 4) == "fLaC" -> true
                bytes.size >= 4 && String(bytes, 0, 4) == "RIFF" -> true
                bytes.size >= 8 && String(bytes, 4, 4) == "ftyp" -> !hasVideoTrack(file)
                else -> false
            }
        } catch (e: Exception) { false }
    }

    internal fun hasVideoTrack(file: File): Boolean {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    extractor.release()
                    return true
                }
            }
            extractor.release()
            false
        } catch (e: Exception) { false }
    }

    internal fun extractAudioTrack(context: Context, sourceFile: File): File? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            extractor.setDataSource(sourceFile.absolutePath)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    Timber.i("Found audio track %d: %s", i, mime)
                    break
                }
            }
            if (audioTrackIndex < 0 || audioFormat == null) return null

            val audioMime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(audioTrackIndex)

            val outputFormat = when {
                audioMime.contains("aac") || audioMime.contains("mp4a") -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                audioMime.contains("mpeg") || audioMime.contains("mp3") -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                audioMime.contains("ogg") || audioMime.contains("opus") -> MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
                audioMime.contains("amr") -> MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP
                else -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }
            val outputExt = when (outputFormat) {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 -> "m4a"
                MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG -> "ogg"
                MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP -> "3gp"
                else -> "m4a"
            }

            val outputFile = File.createTempFile("looplingo_audio_", ".$outputExt", context.cacheDir)
            muxer = MediaMuxer(outputFile.absolutePath, outputFormat)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = ByteBuffer.allocate(256 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            var sampleCount = 0

            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.flags = extractor.sampleFlags
                bufferInfo.presentationTimeUs = extractor.sampleTime

                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                sampleCount++
                extractor.advance()
            }

            muxer.stop()

            if (outputFile.length() == 0L) {
                outputFile.delete()
                return null
            }

            Timber.i("Extracted audio: %d samples, %s (%.2fKB)",
                sampleCount, audioMime, outputFile.length() / 1024.0)
            return outputFile

        } catch (e: Exception) {
            Timber.e(e, "Failed to extract audio track")
            return null
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }
}
