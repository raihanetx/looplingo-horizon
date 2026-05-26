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
     *
     * This sends the full transcript to the LLM in one request and asks it to
     * translate each segment. The LLM returns a JSON array of translations
     * that we parse and map back to segment IDs.
     *
     * Why not use /v1/audio/translations?
     * - That endpoint only translates TO English
     * - For Bangla, Hindi, etc. we need the chat API
     * - This is also much cheaper than a second Whisper call
     */
    internal suspend fun translateSegmentsViaChat(
        apiKey: String,
        segments: List<Segment>,
        targetLanguage: String
    ): Map<Int, String> = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) return@withContext emptyMap()

        val targetLangName = languageName(targetLanguage)
        val transcriptText = segments.mapIndexed { idx, seg ->
            "[${idx}] ${seg.text.trim()}"
        }.joinToString("\n")

        val systemPrompt = """You are a precise, context-aware translator for language learning content.

STRICT RULES:
1. Preserve the EXACT contextual meaning — every nuance, implication, and subtlety must be captured.
2. Do NOT oversimplify, generalize, or paraphrase. The translation must be as specific as the original.
3. Do NOT lose or merge information. If the source says 3 things, the translation must convey all 3.
4. Read the FULL transcript before translating. Each segment's meaning depends on the surrounding context. Use the conversation flow to resolve ambiguous words.
5. Preserve the speaker's tone, register (formal/casual), and emotional nuance exactly.
6. Idioms and cultural expressions: translate their MEANING in context, not word-by-word. But do NOT replace them with a generic phrase — capture the specific figurative meaning.
7. If a word has multiple meanings, pick the one that fits THIS conversation's context.
8. Do NOT add explanations, notes, or parenthetical clarifications — just the translation itself.

Translate to $targetLangName. Return ONLY a JSON object where keys are segment indices (as strings) and values are the translations.
Example: {"0": "translation", "1": "translation", ...}"""

        val userMessage = transcriptText

        // Scale max_tokens based on segment count to avoid truncation:
        // Each segment needs ~30-50 tokens for translation + JSON overhead.
        val scaledMaxTokens = minOf(
            4096 + (segments.size * 50),
            16384
        )

        val requestBodyJson = gson.toJson(mapOf(
            "model" to TRANSLATION_MODEL,
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userMessage)
            ),
            "temperature" to 0.2,  // Low temperature for precise, deterministic translations (no creativity)
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

            // Parse the chat response
            val chatResponse = gson.fromJson(responseBody, ChatCompletionResponse::class.java)
            val content = chatResponse.choices?.firstOrNull()?.message?.content

            if (content.isNullOrBlank()) {
                Timber.w("Translation API returned empty content. Full response: %.2000s", responseBody)
                return@withContext emptyMap()
            }

            Timber.i("Translation raw content (%d chars): %.1000s", content.length, content)

            // Try parsing as JSON object first
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
                    Timber.w(e, "Failed to parse extracted JSON: %.500s", jsonStr)
                }
            }

            // Fallback: try parsing line-by-line if JSON parsing failed
            if (result.isEmpty()) {
                Timber.w("JSON parsing returned 0 translations, trying line-by-line fallback")
                result = parseLineByLine(content, segments)
            }

            if (result.isEmpty()) {
                Timber.w("Translation parsing returned 0 results. Content was: %.2000s", content)
            }

            Timber.i("Translated %d/%d segments to %s", result.size, segments.size, targetLangName)
            result
        } catch (e: Exception) {
            Timber.e(e, "Translation via chat API failed")
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
