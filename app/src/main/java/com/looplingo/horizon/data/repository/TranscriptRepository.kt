package com.looplingo.horizon.data.repository

import com.looplingo.horizon.data.remote.Segment
import com.looplingo.horizon.data.local.entity.TranscriptionEntity
import com.looplingo.horizon.domain.model.SubtitleCue
import com.looplingo.horizon.core.SubtitleScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class CachedTranscriptionData(
    val cues: List<SubtitleCue>,
    val translationLanguage: String?,
    val sourceLanguage: String
)

@Singleton
class TranscriptRepository @Inject constructor(
    private val subtitleScanner: SubtitleScanner,
    private val cache: TranscriptCache,
    private val db: TranscriptDbOperations
) {

    fun getSubtitlesForVideo(videoPath: String): List<SubtitleCue> {
        cache.get(videoPath)?.let { return it }
        val fileCues = subtitleScanner.findSubtitlesForVideo(videoPath)
        if (fileCues.isNotEmpty()) {
            cache.put(videoPath, fileCues)
            return fileCues
        }
        return emptyList()
    }

    suspend fun getSubtitlesForVideoAsync(videoPath: String): List<SubtitleCue> {
        cache.get(videoPath)?.let { return it }
        val fileCues = subtitleScanner.findSubtitlesForVideo(videoPath)
        if (fileCues.isNotEmpty()) {
            cache.put(videoPath, fileCues)
            return fileCues
        }
        return loadTranscriptionsFromDb(videoPath)
    }

    suspend fun getSubtitlesWithMetaAsync(videoPath: String): CachedTranscriptionData {
        cache.get(videoPath)?.let {
            val meta = db.loadMetadata(videoPath)
            return CachedTranscriptionData(
                cues = it,
                translationLanguage = meta?.first,
                sourceLanguage = meta?.second ?: "auto"
            )
        }
        val fileCues = subtitleScanner.findSubtitlesForVideo(videoPath)
        if (fileCues.isNotEmpty()) {
            cache.put(videoPath, fileCues)
            return CachedTranscriptionData(cues = fileCues, translationLanguage = null, sourceLanguage = "auto")
        }
        return loadTranscriptionsWithMetaFromDb(videoPath)
    }

    suspend fun saveTranscriptions(
        videoPath: String,
        segments: List<Segment>,
        languageCode: String = "auto",
        isTranslation: Boolean = false,
        translatedTexts: Map<Int, String> = emptyMap(),
        translationLanguage: String? = null,
        vadRefinements: Map<Int, Pair<Long, Long>> = emptyMap()
    ) {
        withContext(Dispatchers.IO) {
            try {
                val entities = segments.map { segment ->
                    val vadData = vadRefinements[segment.id]
                    TranscriptionEntity(
                        videoPath = videoPath,
                        text = segment.text.trim(),
                        segmentStartMs = segment.startMs,
                        segmentEndMs = segment.endMs,
                        vadStartMs = vadData?.first,
                        vadEndMs = vadData?.second,
                        noSpeechProb = segment.noSpeechProb,
                        avgLogprob = segment.avgLogprob,
                        languageCode = languageCode,
                        isTranslation = isTranslation,
                        translatedText = translatedTexts[segment.id],
                        translationLanguage = translationLanguage
                    )
                }
                db.replaceTranscriptions(videoPath, entities)
                val cues = entities.mapIndexed { index, entity ->
                    entity.toSubtitleCue(index + 1)
                }
                cache.put(videoPath, cues)
                Timber.i("Saved %d transcription segments for: %s", entities.size, videoPath.substringAfterLast("/"))
            } catch (e: Exception) {
                Timber.e(e, "Failed to save transcriptions for: %s", videoPath)
            }
        }
    }

    suspend fun hasTranscriptionsInDb(videoPath: String): Boolean = db.hasTranscriptions(videoPath)

    fun getActiveCue(videoPath: String, positionMs: Long): SubtitleCue? = cache.getActiveCue(videoPath, positionMs)

    fun getActiveCueIndex(videoPath: String, positionMs: Long): Int = cache.getActiveCueIndex(videoPath, positionMs)

    fun hasSubtitles(videoPath: String): Boolean = getSubtitlesForVideo(videoPath).isNotEmpty()

    suspend fun hasSubtitlesAsync(videoPath: String): Boolean {
        if (hasSubtitles(videoPath)) return true
        return db.hasTranscriptions(videoPath)
    }

    fun reloadSubtitles(videoPath: String): List<SubtitleCue> {
        cache.remove(videoPath)
        return getSubtitlesForVideo(videoPath)
    }

    suspend fun reloadSubtitlesAsync(videoPath: String): List<SubtitleCue> {
        cache.remove(videoPath)
        return getSubtitlesForVideoAsync(videoPath)
    }

    fun clearCache() = cache.clear()

    fun clearCacheForVideo(videoPath: String) = cache.remove(videoPath)

    suspend fun deleteTranscriptionsForVideo(videoPath: String) {
        db.deleteTranscriptionsForVideo(videoPath)
    }

    suspend fun cleanupOldTranscriptions(olderThanDays: Int = 30): Int = db.cleanupOld(olderThanDays)

    suspend fun deleteOrphanedTranscriptions(): Int = db.deleteOrphaned()

    private suspend fun loadTranscriptionsFromDb(videoPath: String): List<SubtitleCue> {
        val entities = db.loadTranscriptions(videoPath)
        if (entities.isEmpty()) return emptyList()
        val cues = entities.mapIndexed { index, entity -> entity.toSubtitleCue(index + 1) }
        cache.put(videoPath, cues)
        return cues
    }

    private suspend fun loadTranscriptionsWithMetaFromDb(videoPath: String): CachedTranscriptionData {
        val entities = db.loadTranscriptions(videoPath)
        if (entities.isEmpty()) return CachedTranscriptionData(emptyList(), null, "auto")
        val cues = entities.mapIndexed { index, entity -> entity.toSubtitleCue(index + 1) }
        cache.put(videoPath, cues)
        val translationLang = entities.firstOrNull()?.translationLanguage
        val sourceLang = entities.firstOrNull()?.languageCode ?: "auto"
        return CachedTranscriptionData(cues = cues, translationLanguage = translationLang, sourceLanguage = sourceLang)
    }
}
