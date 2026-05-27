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
        private const val FALLBACK_MODEL = "llama-3.1-8b-instant"
        private const val CHUNK_SIZE = 15

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

    internal suspend fun translateSegmentsViaChat(
        apiKey: String,
        segments: List<Segment>,
        targetLanguage: String,
        context: Context? = null
    ): Map<Int, String> = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) return@withContext emptyMap()

        val targetLangName = languageName(targetLanguage)

        val englishFile = File(context?.cacheDir ?: File("/tmp"), "english_segments.txt")
        val englishText = segments.mapIndexed { idx, seg ->
            "${idx}: ${seg.text.trim()}"
        }.joinToString("\n")
        englishFile.writeText(englishText)
        Timber.i("Saved %d English segments to %s", segments.size, englishFile.absolutePath)

        val chunks = segments.chunked(CHUNK_SIZE)
        Timber.i("=== CHAT TRANSLATOR v2 === Model=%s, Segments=%d, Chunks=%d, Target=%s",
            TRANSLATION_MODEL, segments.size, chunks.size, targetLangName)
        com.looplingo.horizon.ui.settings.ProcessLogger.log("ChatTranslator", "=== TRANSLATION START ===")
        com.looplingo.horizon.ui.settings.ProcessLogger.log("ChatTranslator", "Model=$TRANSLATION_MODEL, Segments=${segments.size}, Chunks=${chunks.size}, Target=$targetLangName")

        val allTranslations = mutableMapOf<Int, String>()

        for ((chunkIdx, chunk) in chunks.withIndex()) {
            val chunkStart = chunkIdx * CHUNK_SIZE
            Timber.i("Chunk %d/%d: segments %d-%d (%d items)",
                chunkIdx + 1, chunks.size, chunkStart, chunkStart + chunk.size - 1, chunk.size)
            com.looplingo.horizon.ui.settings.ProcessLogger.log("ChatTranslator", "Chunk ${chunkIdx+1}/${chunks.size}: segments $chunkStart-${chunkStart + chunk.size - 1} (${chunk.size} items)")

            val chunkText = chunk.mapIndexed { idx, seg ->
                "${idx}: ${seg.text.trim()}"
            }.joinToString("\n")

            val translation = translateChunk(apiKey, chunkText, chunk, targetLangName, chunkIdx + 1, chunks.size)
            allTranslations.putAll(translation)

            Timber.i("Chunk %d/%d result: %d/%d translations",
                chunkIdx + 1, chunks.size, translation.size, chunk.size)
            com.looplingo.horizon.ui.settings.ProcessLogger.log("ChatTranslator", "Chunk ${chunkIdx+1}/${chunks.size} result: ${translation.size}/${chunk.size} translations")
        }

        val banglaFile = File(context?.cacheDir ?: File("/tmp"), "bangla_translations.txt")
        val banglaText = allTranslations.entries.sortedBy { it.key }.joinToString("\n") { (segId, trans) ->
            "$segId: $trans"
        }
        banglaFile.writeText(banglaText)
        Timber.i("Saved %d Bangla translations to %s", allTranslations.size, banglaFile.absolutePath)

        Timber.i("=== TRANSLATION COMPLETE: %d/%d translated to %s ===",
            allTranslations.size, segments.size, targetLangName)
        allTranslations
    }

    private fun translateChunk(
        apiKey: String,
        chunkText: String,
        segments: List<Segment>,
        targetLangName: String,
        chunkNum: Int,
        totalChunks: Int
    ): Map<Int, String> {
        val primary = callChatApi(apiKey, chunkText, TRANSLATION_MODEL, targetLangName, chunkNum, totalChunks)
        if (primary != null) return parseTranslationResponse(primary, segments, chunkNum, totalChunks)

        Timber.w("Chunk %d/%d: Primary model '%s' failed, trying fallback '%s'",
            chunkNum, totalChunks, TRANSLATION_MODEL, FALLBACK_MODEL)
        val fallback = callChatApi(apiKey, chunkText, FALLBACK_MODEL, targetLangName, chunkNum, totalChunks)
        if (fallback != null) return parseTranslationResponse(fallback, segments, chunkNum, totalChunks)

        Timber.e("Chunk %d/%d: BOTH models failed!", chunkNum, totalChunks)
        return emptyMap()
    }

    private fun callChatApi(
        apiKey: String,
        chunkText: String,
        model: String,
        targetLangName: String,
        chunkNum: Int,
        totalChunks: Int
    ): String? {
        val systemPrompt = """You are a translator. Translate each numbered line to $targetLangName.
Return ONLY a JSON object mapping line numbers to translations.
Example: {"0":"translation","1":"translation"}
Do NOT include any explanation or markdown. ONLY the JSON object."""

        val requestBodyJson = gson.toJson(mapOf(
            "model" to model,
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

        return try {
            Timber.i("Chunk %d/%d: Calling Groq (model=%s)...", chunkNum, totalChunks, model)
            com.looplingo.horizon.ui.settings.ProcessLogger.log("API", "Chunk $chunkNum/$totalChunks: Calling Groq Chat API (model=$model)...")
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            response.close()

            if (!response.isSuccessful || responseBody.isNullOrBlank()) {
                Timber.e("Chunk %d/%d: %s FAILED — HTTP %d, body: %.500s",
                    chunkNum, totalChunks, model, response.code, responseBody ?: "null")
                com.looplingo.horizon.ui.settings.ProcessLogger.log("API", "Chunk $chunkNum/$totalChunks: $model FAILED — HTTP ${response.code}")
                com.looplingo.horizon.ui.settings.ProcessLogger.log("API", "Response: ${(responseBody ?: "null").take(300)}")
                return null
            }

            val content = extractContentFromJson(responseBody)

            if (content.isNullOrBlank()) {
                Timber.w("Chunk %d/%d: %s returned empty content", chunkNum, totalChunks, model)
                com.looplingo.horizon.ui.settings.ProcessLogger.log("API", "Chunk $chunkNum/$totalChunks: $model returned EMPTY content!")
                com.looplingo.horizon.ui.settings.ProcessLogger.log("API", "Raw response: ${responseBody.take(500)}")
                return null
            }

            Timber.i("Chunk %d/%d: %s responded (%d chars): %.500s",
                chunkNum, totalChunks, model, content.length, content)
            com.looplingo.horizon.ui.settings.ProcessLogger.log("API", "Chunk $chunkNum/$totalChunks: $model responded OK (${content.length} chars)")
            com.looplingo.horizon.ui.settings.ProcessLogger.log("API", "Response: ${content.take(200)}")
            content
        } catch (e: Exception) {
            Timber.e(e, "Chunk %d/%d: %s EXCEPTION: %s", chunkNum, totalChunks, model, e.message)
            com.looplingo.horizon.ui.settings.ProcessLogger.log("API", "Chunk $chunkNum/$totalChunks: $model EXCEPTION: ${e.message?.take(200)}")
            null
        }
    }

    private fun parseTranslationResponse(
        content: String,
        segments: List<Segment>,
        chunkNum: Int,
        totalChunks: Int
    ): Map<Int, String> {
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
                Timber.i("Chunk %d/%d: JSON parsed %d translations", chunkNum, totalChunks, result.size)
                com.looplingo.horizon.ui.settings.ProcessLogger.log("Parse", "Chunk $chunkNum/$totalChunks: JSON parsed ${result.size} translations")
            } catch (e: Exception) {
                Timber.w(e, "Chunk %d/%d: JSON parse failed for: %.500s", chunkNum, totalChunks, jsonStr)
                com.looplingo.horizon.ui.settings.ProcessLogger.log("Parse", "Chunk $chunkNum/$totalChunks: JSON parse FAILED: ${e.message?.take(100)}")
            }
        } else {
            Timber.w("Chunk %d/%d: No JSON object found in response", chunkNum, totalChunks)
            com.looplingo.horizon.ui.settings.ProcessLogger.log("Parse", "Chunk $chunkNum/$totalChunks: No JSON object found in response!")
        }

        if (result.isEmpty()) {
            result = parseLineByLine(content, segments)
            Timber.i("Chunk %d/%d: Line-by-line fallback got %d translations", chunkNum, totalChunks, result.size)
            com.looplingo.horizon.ui.settings.ProcessLogger.log("Parse", "Chunk $chunkNum/$totalChunks: Line-by-line fallback got ${result.size} translations")
        }

        if (result.isEmpty()) {
            Timber.w("Chunk %d/%d: ZERO translations parsed! Content: %.2000s", chunkNum, totalChunks, content)
            com.looplingo.horizon.ui.settings.ProcessLogger.log("Parse", "Chunk $chunkNum/$totalChunks: ZERO translations parsed!")
        }

        return result
    }

    internal fun languageName(code: String): String {
        if (code == "none") return "No Translation"
        return SUPPORTED_LANGUAGES.find { it.first == code }?.second ?: code
    }

    private fun extractContentFromJson(responseBody: String): String? {
        return try {
            val json = org.json.JSONObject(responseBody)
            val choices = json.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val message = choices.getJSONObject(0).optJSONObject("message") ?: return null
            message.optString("content", null)
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse response JSON manually")
            null
        }
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
        return result
    }
}
