package com.looplingo.horizon.data.remote

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

@javax.inject.Singleton
class ChatTranslator @javax.inject.Inject constructor() {
    companion object {
        private const val GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val TRANSLATION_MODEL = "openai/gpt-oss-20b"
        private const val CHUNK_SIZE = 20 // segments per chunk

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
     * Step 1: Save English segments to a temp file for debugging.
     * Step 2: Send to LLM in chunks if needed.
     * Step 3: Parse Bangla translations from response.
     */
    internal suspend fun translateSegmentsViaChat(
        apiKey: String,
        segments: List<Segment>,
        targetLanguage: String,
        context: Context? = null
    ): Map<Int, String> = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) return@withContext emptyMap()

        val targetLangName = languageName(targetLanguage)

        // Step 1: Save English text to temp file
        val englishFile = File(context?.cacheDir ?: File("/tmp"), "english_segments.txt")
        val englishText = segments.mapIndexed { idx, seg ->
            "${idx}: ${seg.text.trim()}"
        }.joinToString("\n")
        englishFile.writeText(englishText)
        Timber.i("Saved %d English segments to %s", segments.size, englishFile.absolutePath)

        // Step 2: Split into chunks and translate each
        val chunks = segments.chunked(CHUNK_SIZE)
        Timber.i("Translating %d segments in %d chunks (chunk size=%d) to %s",
            segments.size, chunks.size, CHUNK_SIZE, targetLangName)

        val allTranslations = mutableMapOf<Int, String>()

        for ((chunkIdx, chunk) in chunks.withIndex()) {
            val chunkStart = chunkIdx * CHUNK_SIZE
            Timber.i("Processing chunk %d/%d (segments %d-%d)",
                chunkIdx + 1, chunks.size, chunkStart, chunkStart + chunk.size - 1)

            val chunkText = chunk.mapIndexed { idx, seg ->
                "${idx}: ${seg.text.trim()}"
            }.joinToString("\n")

            val translation = translateChunk(apiKey, chunkText, chunk, targetLangName)
            allTranslations.putAll(translation)

            Timber.i("Chunk %d/%d: got %d/%d translations",
                chunkIdx + 1, chunks.size, translation.size, chunk.size)
        }

        // Step 3: Save Bangla translations to temp file for debugging
        val banglaFile = File(context?.cacheDir ?: File("/tmp"), "bangla_translations.txt")
        val banglaText = allTranslations.entries.sortedBy { it.key }.joinToString("\n") { (segId, trans) ->
            "$segId: $trans"
        }
        banglaFile.writeText(banglaText)
        Timber.i("Saved %d Bangla translations to %s", allTranslations.size, banglaFile.absolutePath)

        Timber.i("TOTAL: Translated %d/%d segments to %s", allTranslations.size, segments.size, targetLangName)
        allTranslations
    }

    private fun translateChunk(
        apiKey: String,
        chunkText: String,
        segments: List<Segment>,
        targetLangName: String
    ): Map<Int, String> {
        val systemPrompt = "Translate each numbered line to $targetLangName. Return ONLY a JSON object like {\"0\":\"translation\",\"1\":\"translation\"}"

        val requestBodyJson = gson.toJson(mapOf(
            "model" to TRANSLATION_MODEL,
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to chunkText)
            ),
            "temperature" to 0.1,
            "max_tokens" to 4096
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
                Timber.w("API failed: HTTP %d, body: %.500s", response.code, responseBody ?: "null")
                return emptyMap()
            }

            val chatResponse = gson.fromJson(responseBody, ChatCompletionResponse::class.java)
            val content = chatResponse.choices?.firstOrNull()?.message?.content

            if (content.isNullOrBlank()) {
                Timber.w("API returned empty content. Response: %.500s", responseBody)
                return emptyMap()
            }

            Timber.i("LLM response (%d chars): %.2000s", content.length, content)

            // Try JSON parsing
            val jsonStr = extractJsonObject(content)
            var result = mutableMapOf<Int, String>()

            if (jsonStr != null) {
                try {
                    @Suppress("UNCHECKED_CAST")
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
                    Timber.w(e, "JSON parse failed: %.500s", jsonStr)
                }
            }

            // Fallback: line-by-line
            if (result.isEmpty()) {
                result = parseLineByLine(content, segments)
            }

            if (result.isEmpty()) {
                Timber.w("Could not parse any translations. Content: %.2000s", content)
            }

            return result
        } catch (e: Exception) {
            Timber.e(e, "Translation API call failed")
            return emptyMap()
        }
    }

    internal fun languageName(code: String): String {
        if (code == "none") return "No Translation"
        return SUPPORTED_LANGUAGES.find { it.first == code }?.second ?: code
    }

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
        for (line in content.lines()) {
            val trimmed = line.trim()
            val match = Regex("""^(\d+)\s*[:.\-\]]\s*(.+)""").find(trimmed)
            if (match != null) {
                val idx = match.groupValues[1].toIntOrNull()
                val translation = match.groupValues[2].trim().removeSurrounding("\"")
                if (idx != null && idx in segments.indices && translation.isNotBlank()) {
                    result[segments[idx].id] = translation
                }
            }
        }
        Timber.i("Line-by-line fallback: %d translations", result.size)
        return result
    }
}
