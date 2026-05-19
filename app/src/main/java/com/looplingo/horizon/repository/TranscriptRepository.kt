package com.looplingo.horizon.repository

import com.looplingo.horizon.data.dao.TranscriptionDao
import com.looplingo.horizon.data.entity.TranscriptionEntity
import com.looplingo.horizon.model.SubtitleCue
import com.looplingo.horizon.util.SubtitleScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing subtitle/transcript data.
 *
 * TWO SOURCES OF TRUTH:
 *  1. Subtitle files (.srt, .vtt, .lrc) found by [SubtitleScanner] — external files
 *  2. Whisper transcriptions persisted in Room via [TranscriptionDao] — AI-generated
 *
 * Priority: Subtitle files > Database transcriptions > empty
 *
 * The repository first checks for external subtitle files (fastest, highest quality).
 * If none are found, it falls back to database-stored Whisper transcriptions.
 * This means re-transcription is only needed if the user explicitly requests it.
 *
 * All loaded cues are cached in memory for fast access during playback.
 * The cache is invalidated when new transcriptions are saved.
 */
@Singleton
class TranscriptRepository @Inject constructor(
    private val subtitleScanner: SubtitleScanner,
    private val transcriptionDao: TranscriptionDao
) {

    companion object {
        private const val MAX_CACHE_ENTRIES = 50
    }

    /**
     * Result of loading cached transcription data with metadata.
     * Includes the translation language so the UI can check if the cached
     * translation matches the user's current selection.
     */
    data class CachedTranscriptionData(
        val cues: List<SubtitleCue>,
        val translationLanguage: String?,  // e.g., "bn", "hi", or null if no translation
        val sourceLanguage: String          // e.g., "en", "auto"
    )

    /** In-memory cache of parsed subtitle cues, keyed by video path. Thread-safe. */
    private val cache = ConcurrentHashMap<String, List<SubtitleCue>>()

    /**
     * Load subtitle cues for the given video path.
     *
     * Resolution order:
     *  1. Memory cache (instant)
     *  2. External subtitle files (.srt, .vtt, .lrc)
     *  3. Database transcriptions (from Whisper)
     *
     * Returns an empty list if no subtitles are found from any source.
     */
    fun getSubtitlesForVideo(videoPath: String): List<SubtitleCue> {
        cache[videoPath]?.let { return it }

        // Try external subtitle files first
        val fileCues = subtitleScanner.findSubtitlesForVideo(videoPath)
        if (fileCues.isNotEmpty()) {
            cache[videoPath] = fileCues
            trimCache()
            Timber.i("Cached %d subtitle cues (file) for: %s", fileCues.size, videoPath.substringAfterLast("/"))
            return fileCues
        }

        // Note: Database transcriptions are loaded asynchronously via loadTranscriptionsFromDb()
        // because Room requires coroutine context. The cache will be populated when the
        // caller uses the async method. This synchronous method returns empty for DB-only cases.
        return emptyList()
    }

    /**
     * Async version that also checks database transcriptions.
     * Use this when you need the complete picture including AI-generated transcriptions.
     *
     * @return List of SubtitleCue from the best available source.
     */
    suspend fun getSubtitlesForVideoAsync(videoPath: String): List<SubtitleCue> {
        cache[videoPath]?.let { return it }

        // Try external subtitle files first
        val fileCues = subtitleScanner.findSubtitlesForVideo(videoPath)
        if (fileCues.isNotEmpty()) {
            cache[videoPath] = fileCues
            trimCache()
            return fileCues
        }

        // Try database transcriptions
        return loadTranscriptionsFromDb(videoPath)
    }

    /**
     * Async version that returns transcription data WITH metadata.
     * This is the preferred method for the UI because it includes the
     * translation language, allowing the UI to check if the cached
     * translation matches the user's current selection.
     *
     * If the cache hit comes from an external subtitle file (not DB),
     * translationLanguage and sourceLanguage will be null/"auto".
     */
    suspend fun getSubtitlesWithMetaAsync(videoPath: String): CachedTranscriptionData {
        cache[videoPath]?.let {
            // Memory cache hit — we don't have metadata, load from DB to get it
            // Actually, check DB for metadata too
            val meta = loadTranscriptionMetaFromDb(videoPath)
            return CachedTranscriptionData(
                cues = it,
                translationLanguage = meta?.first,
                sourceLanguage = meta?.second ?: "auto"
            )
        }

        // Try external subtitle files first
        val fileCues = subtitleScanner.findSubtitlesForVideo(videoPath)
        if (fileCues.isNotEmpty()) {
            cache[videoPath] = fileCues
            trimCache()
            return CachedTranscriptionData(cues = fileCues, translationLanguage = null, sourceLanguage = "auto")
        }

        // Try database transcriptions — this gives us both cues AND metadata
        return loadTranscriptionsWithMetaFromDb(videoPath)
    }

    /**
     * Load transcription segments from the Room database and convert to SubtitleCues.
     * This is the bridge between GroqApiClient's Segment objects and the playback system.
     */
    private suspend fun loadTranscriptionsFromDb(videoPath: String): List<SubtitleCue> {
        return withContext(Dispatchers.IO) {
            try {
                val entities = transcriptionDao.getTranscriptionsForVideoOnce(videoPath)
                if (entities.isEmpty()) return@withContext emptyList()

                val cues = entities.mapIndexed { index, entity ->
                    entity.toSubtitleCue(index + 1)
                }
                cache[videoPath] = cues
                trimCache()
                Timber.i("Cached %d transcription cues (DB) for: %s", cues.size, videoPath.substringAfterLast("/"))
                cues
            } catch (e: Exception) {
                Timber.e(e, "Failed to load transcriptions from DB for: %s", videoPath)
                emptyList()
            }
        }
    }

    /**
     * Load transcription metadata (translation language, source language) from DB.
     * Returns null if no transcriptions exist for this video.
     */
    private suspend fun loadTranscriptionMetaFromDb(videoPath: String): Pair<String?, String>? {
        return withContext(Dispatchers.IO) {
            try {
                val meta = transcriptionDao.getTranscriptionMetaForVideo(videoPath)
                if (meta == null) {
                    Timber.d("No transcription metadata in DB for: %s", videoPath.substringAfterLast("/"))
                    return@withContext null
                }
                Pair(meta.translationLanguage, meta.languageCode)
            } catch (e: Exception) {
                Timber.e(e, "Failed to load transcription metadata for: %s", videoPath)
                null
            }
        }
    }

    /**
     * Load transcriptions with metadata from the Room database.
     * Returns both cues AND the translation/source language info.
     */
    private suspend fun loadTranscriptionsWithMetaFromDb(videoPath: String): CachedTranscriptionData {
        return withContext(Dispatchers.IO) {
            try {
                val entities = transcriptionDao.getTranscriptionsForVideoOnce(videoPath)
                if (entities.isEmpty()) {
                    return@withContext CachedTranscriptionData(emptyList(), null, "auto")
                }

                val cues = entities.mapIndexed { index, entity ->
                    entity.toSubtitleCue(index + 1)
                }
                cache[videoPath] = cues
                trimCache()

                // Extract metadata from the first entity (all segments for a video share the same metadata)
                val translationLang = entities.firstOrNull()?.translationLanguage
                val sourceLang = entities.firstOrNull()?.languageCode ?: "auto"

                Timber.i("Cached %d transcription cues (DB) for: %s (translation=%s, source=%s)",
                    cues.size, videoPath.substringAfterLast("/"), translationLang, sourceLang)

                CachedTranscriptionData(cues = cues, translationLanguage = translationLang, sourceLanguage = sourceLang)
            } catch (e: Exception) {
                Timber.e(e, "Failed to load transcriptions from DB for: %s", videoPath)
                CachedTranscriptionData(emptyList(), null, "auto")
            }
        }
    }

    /**
     * Save Whisper transcription segments to the database.
     * This replaces any existing transcriptions for the same video.
     *
     * @param videoPath The video/audio file path (matches VideoEntity.path).
     * @param segments The Whisper transcription segments from GroqApiClient.
     * @param languageCode The language code used for transcription.
     * @param isTranslation Whether this is a translation (any→English).
     * @param translatedTexts Optional map of segment.id → translated text (from LLM).
     * @param translationLanguage Target language code for translation (e.g., "bn").
     */
    suspend fun saveTranscriptions(
        videoPath: String,
        segments: List<com.looplingo.horizon.api.GroqApiClient.Segment>,
        languageCode: String = "auto",
        isTranslation: Boolean = false,
        translatedTexts: Map<Int, String> = emptyMap(),
        translationLanguage: String? = null,
        vadRefinements: Map<Int, Pair<Long, Long>> = emptyMap()  // segment.id → (vadStartMs, vadEndMs)
    ) {
        withContext(Dispatchers.IO) {
            try {
                // Convert segments to entities first
                val entities = segments.map { segment ->
                    val vadData = vadRefinements[segment.id]
                    TranscriptionEntity(
                        videoPath = videoPath,
                        text = segment.text.trim(),
                        segmentStartMs = segment.startMs,
                        segmentEndMs = segment.endMs,
                        vadStartMs = vadData?.first,   // VAD-refined start (null if no VAD)
                        vadEndMs = vadData?.second,     // VAD-refined end (null if no VAD)
                        noSpeechProb = segment.noSpeechProb,
                        avgLogprob = segment.avgLogprob,
                        languageCode = languageCode,
                        isTranslation = isTranslation,
                        translatedText = translatedTexts[segment.id],
                        translationLanguage = translationLanguage
                    )
                }

                // Atomic replace: delete old + insert new in a single transaction.
                // Prevents data loss if the app crashes between delete and insert.
                transcriptionDao.replaceTranscriptionsForVideo(videoPath, entities)

                // Update in-memory cache immediately
                val cues = entities.mapIndexed { index, entity ->
                    entity.toSubtitleCue(index + 1)
                }
                cache[videoPath] = cues
                trimCache()

                Timber.i("Saved %d transcription segments for: %s (lang=%s, translation=%s)",
                    entities.size, videoPath.substringAfterLast("/"), languageCode, isTranslation)
            } catch (e: Exception) {
                Timber.e(e, "Failed to save transcriptions for: %s", videoPath)
            }
        }
    }

    /**
     * Check if transcriptions exist in the database for a video.
     * Returns true if at least one segment is stored.
     */
    suspend fun hasTranscriptionsInDb(videoPath: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                transcriptionDao.getTranscriptionCountForVideo(videoPath) > 0
            } catch (e: Exception) {
                Timber.e(e, "Failed to check transcriptions for: %s", videoPath)
                false
            }
        }
    }

    /**
     * Evict oldest cache entries when the cache exceeds [MAX_CACHE_ENTRIES].
     * Uses synchronized block because the check-then-act pattern (size check → key removal)
     * is not atomic even with ConcurrentHashMap.
     */
    private fun trimCache() {
        synchronized(cache) {
            if (cache.size > MAX_CACHE_ENTRIES) {
                val keysToRemove = cache.keys.toList().take(cache.size - MAX_CACHE_ENTRIES + 10)
                keysToRemove.forEach { cache.remove(it) }
                Timber.d("Trimmed %d entries from subtitle cache (was %d, limit %d)",
                    keysToRemove.size, cache.size + keysToRemove.size, MAX_CACHE_ENTRIES)
            }
        }
    }

    /**
     * Force reload subtitles for a video (e.g., after the user adds a new subtitle file
     * or after new transcriptions are saved).
     */
    fun reloadSubtitles(videoPath: String): List<SubtitleCue> {
        cache.remove(videoPath)
        return getSubtitlesForVideo(videoPath)
    }

    /**
     * Async reload that also checks database.
     */
    suspend fun reloadSubtitlesAsync(videoPath: String): List<SubtitleCue> {
        cache.remove(videoPath)
        return getSubtitlesForVideoAsync(videoPath)
    }

    /**
     * Find the subtitle cue that is active at the given playback position.
     *
     * Uses binary search for efficient lookup in large subtitle files.
     * Returns null if no cue is active at the given position.
     *
     * Note: Uses closed range [startMs, endMs] to match the fixed SubtitleCue.isActiveAt().
     */
    fun getActiveCue(videoPath: String, positionMs: Long): SubtitleCue? {
        val cues = cache[videoPath] ?: return null
        if (cues.isEmpty()) return null

        // Binary search for the cue whose range contains positionMs
        // Uses closed range [startMs, endMs] to match SubtitleCue.isActiveAt()
        var low = 0
        var high = cues.lastIndex
        while (low <= high) {
            val mid = (low + high) / 2
            val cue = cues[mid]
            when {
                positionMs < cue.startMs -> high = mid - 1
                positionMs > cue.endMs -> low = mid + 1
                else -> return cue  // positionMs is within [startMs, endMs]
            }
        }
        return null
    }

    /**
     * Get the index of the currently active cue (for RecyclerView scrolling).
     * Returns -1 if no cue is active.
     */
    fun getActiveCueIndex(videoPath: String, positionMs: Long): Int {
        val cues = cache[videoPath] ?: return -1
        val activeCue = getActiveCue(videoPath, positionMs) ?: return -1
        return cues.indexOf(activeCue)
    }

    /**
     * Check if a video has subtitles available (from any source).
     * For synchronous check, only file-based subtitles are considered.
     */
    fun hasSubtitles(videoPath: String): Boolean {
        return getSubtitlesForVideo(videoPath).isNotEmpty()
    }

    /**
     * Check if subtitles exist from any source (including database).
     */
    suspend fun hasSubtitlesAsync(videoPath: String): Boolean {
        if (hasSubtitles(videoPath)) return true
        return hasTranscriptionsInDb(videoPath)
    }

    /**
     * Clear the subtitle cache to free memory.
     */
    fun clearCache() {
        cache.clear()
    }

    /**
     * Clear cached subtitles for a specific video.
     */
    fun clearCacheForVideo(videoPath: String) {
        cache.remove(videoPath)
    }

    /**
     * Delete all transcriptions older than the specified number of days.
     * Used for periodic cache cleanup.
     */
    suspend fun cleanupOldTranscriptions(olderThanDays: Int = 30): Int {
        return withContext(Dispatchers.IO) {
            try {
                val cutoff = System.currentTimeMillis() - (olderThanDays.toLong() * 24 * 60 * 60 * 1000)
                val deleted = transcriptionDao.deleteOlderThan(cutoff)
                if (deleted > 0) {
                    Timber.i("Cleaned up %d transcriptions older than %d days", deleted, olderThanDays)
                }
                deleted
            } catch (e: Exception) {
                Timber.e(e, "Failed to cleanup old transcriptions")
                0
            }
        }
    }

    /**
     * Delete orphaned transcriptions whose video no longer exists in the cache.
     */
    suspend fun deleteOrphanedTranscriptions(): Int {
        return withContext(Dispatchers.IO) {
            try {
                val deleted = transcriptionDao.deleteOrphanedTranscriptions()
                if (deleted > 0) {
                    Timber.i("Deleted %d orphaned transcriptions", deleted)
                }
                deleted
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete orphaned transcriptions")
                0
            }
        }
    }
}

/**
 * Extension function to convert a TranscriptionEntity to a SubtitleCue.
 * This bridges the Room persistence layer with the playback sync system.
 * If a translation is available, it's appended below the original text.
 */
private fun TranscriptionEntity.toSubtitleCue(index: Int): SubtitleCue {
    val displayText = if (!translatedText.isNullOrBlank()) {
        "$text\n→ $translatedText"
    } else {
        text
    }
    // Use VAD-refined timestamps if available (much more accurate than Whisper's)
    // VAD timestamps reflect actual speech boundaries from audio waveform analysis.
    // Fall back to Whisper timestamps if VAD wasn't run (e.g., old data).
    val effectiveStartMs = vadStartMs ?: segmentStartMs
    val effectiveEndMs = vadEndMs ?: segmentEndMs
    return SubtitleCue(
        index = index,
        startMs = effectiveStartMs,
        endMs = effectiveEndMs,
        text = displayText
    )
}
