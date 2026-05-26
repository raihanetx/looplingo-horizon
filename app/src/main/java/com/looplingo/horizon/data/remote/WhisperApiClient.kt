package com.looplingo.horizon.data.remote

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

@javax.inject.Singleton
class WhisperApiClient @javax.inject.Inject constructor() {
    companion object {
        private const val GROQ_API_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val GROQ_TRANSLATION_URL = "https://api.groq.com/openai/v1/audio/translations"
        private const val GROQ_MODEL = "whisper-large-v3"
        internal const val GROQ_MAX_FILE_SIZE = 25L * 1024 * 1024  // 25MB
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    @Volatile
    private var lastWhisperResponseRaw: String = ""

    internal suspend fun callWhisperApi(
        apiKey: String,
        audioFile: File,
        language: String = "auto",
        prompt: String? = null,
        isTranslation: Boolean = false
    ): List<Segment> {
        val (effectiveFileName, effectiveMediaType) = getCorrectMediaType(audioFile)

        Timber.i("→ Whisper API: %s (%.2fKB) as %s, lang=%s, prompt=%s, translate=%s",
            effectiveFileName, audioFile.length() / 1024.0, effectiveMediaType,
            language, prompt?.take(30), isTranslation)

        val fileBody = audioFile.asRequestBody(effectiveMediaType.toMediaType())

        val apiUrl = if (isTranslation) GROQ_TRANSLATION_URL else GROQ_API_URL

        val multipartBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", effectiveFileName, fileBody)
            .addFormDataPart("model", GROQ_MODEL)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "segment")

        if (!isTranslation && language.isNotBlank() && language != "auto") {
            multipartBuilder.addFormDataPart("language", language)
        }

        if (!prompt.isNullOrBlank()) {
            val cleanPrompt = prompt.replace("\n", " ").take(1000)
            multipartBuilder.addFormDataPart("prompt", cleanPrompt)
        }

        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(multipartBuilder.build())
            .build()

        val response = kotlinx.coroutines.suspendCancellableCoroutine<okhttp3.Response> { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    cont.resumeWith(Result.failure(e))
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    cont.resumeWith(Result.success(response))
                }
            })
        }

        val responseCode = response.code
        val responseBody = try { response.body?.string() } finally { response.close() }

        lastWhisperResponseRaw = responseBody?.take(1000) ?: "(null)"
        Timber.i("← Whisper API: HTTP %d, body=%s", responseCode, lastWhisperResponseRaw.take(300))

        if (responseCode !in 200..299 || responseBody.isNullOrBlank()) {
            val errorDetail = responseBody?.take(500) ?: "No response body"
            Timber.e("← Whisper API error: HTTP %d — %s", responseCode, errorDetail)

            val userMessage = when (responseCode) {
                401 -> "API key INVALID (HTTP 401). Get a new key at console.groq.com"
                403 -> "API key FORBIDDEN (HTTP 403). Get a new key at console.groq.com"
                429 -> "Rate limit exceeded (HTTP 429). Wait a moment and try again."
                413 -> "File too large for Groq API (max 25MB). File: ${audioFile.name}"
                else -> "Groq API error HTTP $responseCode: $errorDetail"
            }
            if (responseCode == 401 || responseCode == 403) throw ApiKeyException(userMessage)
            throw RuntimeException(userMessage)
        }

        val transcription = parseTranscriptionResponse(responseBody)

        if (transcription.error != null) {
            val errMsg = transcription.error.message ?: "Unknown error"
            Timber.e("← Error in response body: %s", errMsg)
            throw RuntimeException(errMsg)
        }

        if (transcription.segments.isNullOrEmpty() && transcription.text.isNullOrBlank()) {
            Timber.w("← Whisper returned EMPTY: no text, no segments")
        } else if (transcription.segments.isNullOrEmpty()) {
            Timber.i("← Whisper returned TEXT only: \"%s\"", transcription.text?.take(80))
        } else {
            Timber.i("← Whisper returned %d segments", transcription.segments.size)
        }

        if (transcription.segments.isNullOrEmpty() && !transcription.text.isNullOrBlank()) {
            Timber.w("← Whisper returned text without segments — creating single segment from text")
            return listOf(Segment(
                id = 0,
                text = transcription.text.trim(),
                startSec = 0.0,
                endSec = 0.0,
                noSpeechProb = 0.0,
                avgLogprob = 0.0
            ))
        }

        return transcription.segments?.map { segJson ->
            Segment(
                id = segJson.id,
                text = segJson.text.trim(),
                startSec = segJson.start,
                endSec = segJson.end,
                noSpeechProb = segJson.noSpeechProb ?: 0.0,
                avgLogprob = segJson.avgLogprob ?: 0.0
            )
        } ?: emptyList()
    }

    internal fun getCorrectMediaType(audioFile: File): Pair<String, String> {
        val ext = audioFile.extension.lowercase()
        return when (ext) {
            "m4a" -> Pair(audioFile.name, "audio/mp4")
            "mp4" -> Pair(audioFile.name, "audio/mp4")
            "mp3" -> Pair(audioFile.name, "audio/mpeg")
            "wav" -> Pair(audioFile.name, "audio/wav")
            "ogg" -> Pair(audioFile.name, "audio/ogg")
            "flac" -> Pair(audioFile.name, "audio/flac")
            "webm" -> Pair(audioFile.name, "audio/webm")
            "aac" -> Pair(audioFile.name, "audio/aac")
            "3gp" -> Pair(audioFile.name, "audio/3gpp")
            "mpeg" -> Pair(audioFile.name, "audio/mpeg")
            "mpga" -> Pair(audioFile.name, "audio/mpeg")
            "opus" -> Pair(audioFile.name, "audio/ogg")
            else -> {
                Timber.w("Unknown audio extension: .$ext — defaulting to audio/mp4")
                Pair(audioFile.name, "audio/mp4")
            }
        }
    }

    internal fun parseTranscriptionResponse(json: String): TranscriptionResponse {
        return try {
            gson.fromJson(json, TranscriptionResponse::class.java)
                ?: TranscriptionResponse(text = json.take(100))
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse Whisper response as JSON")
            TranscriptionResponse(text = json.take(200))
        }
    }

    internal fun validateInputs(apiKey: String, filePath: String) {
        if (apiKey.isBlank()) throw SubtitleException("Enter your Groq API key first")
        if (filePath.isBlank()) throw SubtitleException("No file selected — pick an audio/video file")
    }

    fun getLastWhisperResponse(): String = lastWhisperResponseRaw
}