package com.looplingo.horizon.data.remote

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit

@javax.inject.Singleton
class ChatTranslator @javax.inject.Inject constructor() {
    companion object {
        private const val GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val TRANSLATION_MODEL = "openai/gpt-oss-20b"

        /** Supported languages for Whisper transcription. */
        val SUPPORTED_LANGUAGES = listOf(
            "auto" to "Auto-detect",
            "bn" to "বাংলা (Bengali)",
            "ja" to "日本語 (Japanese)",
            "en" to "English",
            "hi" to "हिन्दी (Hindi)",
            "ko" to "한국어 (Korean)",
            "zh" to "中文 (Chinese)",
            "ar" to "العربية (Arabic)",
            "es" to "Español (Spanish)",
            "fr" to "Français (French)",
            "de" to "Deutsch (German)",
            "pt" to "Português (Portuguese)",
            "ru" to "Русский (Russian)",
            "th" to "ไทย (Thai)",
            "vi" to "Tiếng Việt (Vietnamese)",
            "tr" to "Türkçe (Turkish)",
            "id" to "Bahasa Indonesia",
            "ta" to "தமிழ் (Tamil)",
            "te" to "తెలుగు (Telugu)",
            "ur" to "اردو (Urdu)"
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * Translate transcription segments using Groq's Chat Completion API.
     * Sends English segments to LLM, gets back Bangla (or other language) translations.
     */
    internal suspend fun translateSegmentsViaChat(
        apiKey: String,
        segments: List<Segment>,
        targetLanguage: String
    ): Map<Int, String> = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) return@withContext emptyMap()

        val targetLangName = languageName(targetLanguage)
        val transcriptText = segments.mapIndexed { idx, seg ->
            "${idx}: ${seg.text.trim()}"
        }.joinToString("\n")

        val systemPrompt = "You are a translator. Translate each numbered line to $targetLangName. Return ONLY a JSON object mapping line numbers to translations. Example: {\"0\": \"translation\", \"1\": \"translation\"}"

        val scaledMaxTokens = minOf(4096 + (segments.size * 50), 16384)

        val requestBodyJson = gson.toJson(mapOf(
            "model" to TRANSLATION_MODEL,
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to transcriptText)
            ),
            "temperature" to 0.1,
            "max_tokens" to scaledMaxTokens
        ))

        val request = Request.Builder()
            .url(GROQ_CHAT_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            response.close()

            if (!response.isSuccessful || responseBody.isNullOrBlank()) {
                Timber.w("Translation API failed: HTTP %d, body: %.500s", response.code, responseBody ?: "null")
                return@withContext emptyMap()
            }

            val chatResponse = gson.fromJson(responseBody, ChatCompletionResponse::class.java)
            val content = chatResponse.choices?.firstOrNull()?.message?.content

            if (content.isNullOrBlank()) {
                Timber.w("Translation API returned empty content. Response: %.500s", responseBody)
                return@withContext emptyMap()
            }

            Timber.i("Translation raw content (%d chars): %.2000s", content.length, content)

            // Parse JSON object
            val jsonStr = extractJsonObject(content)
            var result = mutableMapOf<Int, String>()

            if (jsonStr != null) {
                try {
                    val translations = gson.fromJson(jsonStr, Map::class.java) as? Map<String, Any>
                    if (translations != null) {
                        for ((key, value) in translations) {
                            val idx = key.toIntOrNull() ?: continue
                            if (idx in segments.indices) {
                                result[segments[idx].id] = value.toString()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse JSON: %.500s", jsonStr)
                }
            }

            // Fallback: line-by-line
            if (result.isEmpty()) {
                Timber.w("JSON parsing returned 0, trying line-by-line fallback")
                result = parseLineByLine(content, segments)
            }

            if (result.isEmpty()) {
                Timber.w("All parsing returned 0. Content: %.2000s", content)
            }

            Timber.i("Translated %d/%d segments to %s", result.size, segments.size, targetLangName)
            result
        } catch (e: Exception) {
            Timber.e(e, "Translation API call failed")
            emptyMap()
        }
    }

    /** Get human-readable language name from ISO 639-1 code. */
    internal fun languageName(code: String): String {
        if (code == "none") return "No Translation"
        return SUPPORTED_LANGUAGES.find { it.first == code }?.second ?: code
    }

    /**
     * Extract the first JSON object ({...}) from a string.
     * Some models (e.g. GPT-OSS with harmony format) wrap JSON in
     * chain-of-thought or special tokens. This finds the outermost
     * brace pair and extracts the content between them.
     */
    internal fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start == -1) return null
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun parseLineByLine(content: String, segments: List<Segment>): MutableMap<Int, String> {
        val result = mutableMapOf<Int, String>()
        val lines = content.lines()
        for (line in lines) {
            val trimmed = line.trim()
            // Match patterns like: "0: translation", "0. translation", "[0] translation", "0 - translation"
            val match = Regex("""^(\d+)\s*[:.\-\]]\s*(.+)""").find(trimmed)
            if (match != null) {
                val idx = match.groupValues[1].toIntOrNull()
                val translation = match.groupValues[2].trim().removeSurrounding("\"")
                if (idx != null && idx in segments.indices && translation.isNotBlank()) {
                    result[segments[idx].id] = translation
                }
            }
        }
        Timber.i("Line-by-line fallback parsed %d translations", result.size)
        return result
    }
}
