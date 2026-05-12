package com.looplingo.horizon.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Client for the Groq Whisper API — speech-to-text transcription.
 *
 * Uses OkHttp for HTTP requests and Gson for JSON parsing.
 * The API key is NOT hardcoded — it must be provided at runtime
 * from BuildConfig.GROQ_API_KEY (which reads from local.properties or env var).
 */
class GroqApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * A single transcription segment with timing information.
     */
    data class Segment(
        val id: Int,
        val text: String,
        @SerializedName("start")
        val startSec: Double,
        @SerializedName("end")
        val endSec: Double
    ) {
        /** Start time in milliseconds. */
        val startMs: Long get() = (startSec * 1000).toLong()

        /** End time in milliseconds. */
        val endMs: Long get() = (endSec * 1000).toLong()
    }

    // ── JSON response models ──────────────────────────────────────────

    private data class TranscriptionResponse(
        val segments: List<SegmentJson>? = null,
        val text: String? = null,
        val error: ErrorJson? = null
    )

    private data class SegmentJson(
        val id: Int,
        val text: String,
        val start: Double,
        val end: Double
    )

    private data class ErrorJson(
        val message: String? = null,
        val type: String? = null
    )

    /**
     * Transcribe an audio/video file using the Groq Whisper API.
     *
     * @param apiKey The Groq API key (from BuildConfig.GROQ_API_KEY, never hardcoded).
     * @param filePath Path to the audio/video file on device.
     * @return List of timed segments, or empty list on failure.
     */
    suspend fun transcribeAudio(apiKey: String, filePath: String): List<Segment> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                Timber.w("Groq API key is blank — cannot transcribe")
                return@withContext emptyList()
            }

            val file = File(filePath)
            if (!file.exists()) {
                Timber.w("File not found for transcription: %s", filePath)
                return@withContext emptyList()
            }

            try {
                val mediaType = guessMediaType(file.name)
                val fileBody = file.asRequestBody(mediaType.toMediaType())

                val multipartBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.name, fileBody)
                    .addFormDataPart("model", "whisper-large-v3")
                    .addFormDataPart("response_format", "verbose_json")
                    .addFormDataPart("timestamp_granularities[]", "segment")
                    .build()

                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/audio/transcriptions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(multipartBody)
                    .build()

                Timber.d("Sending transcription request to Groq API for: %s", file.name)

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (!response.isSuccessful || responseBody.isNullOrBlank()) {
                    Timber.e("Groq API error: %d — %s", response.code, responseBody?.take(200))
                    return@withContext emptyList()
                }

                val transcription = parseTranscriptionResponse(responseBody)

                if (transcription.error != null) {
                    Timber.e("Groq API returned error: %s", transcription.error.message)
                    return@withContext emptyList()
                }

                val segments = transcription.segments?.map { segJson ->
                    Segment(
                        id = segJson.id,
                        text = segJson.text.trim(),
                        startSec = segJson.start,
                        endSec = segJson.end
                    )
                } ?: emptyList()

                Timber.i("Groq transcription complete: %d segments", segments.size)
                segments
            } catch (e: Exception) {
                Timber.e(e, "Failed to transcribe audio via Groq API")
                emptyList()
            }
        }

    private fun parseTranscriptionResponse(json: String): TranscriptionResponse {
        return try {
            gson.fromJson(json, TranscriptionResponse::class.java)
                ?: TranscriptionResponse(error = ErrorJson(message = "Null response"))
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse Groq API response")
            TranscriptionResponse(error = ErrorJson(message = "Parse error: ${e.message}"))
        }
    }

    /**
     * Guess the MIME type from the file extension.
     * Groq Whisper supports mp3, mp4, m4a, wav, webm, and others.
     */
    private fun guessMediaType(fileName: String): String {
        return when (fileName.substringAfterLast(".", "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "mp4", "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "webm" -> "audio/webm"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            else -> "application/octet-stream"
        }
    }
}
