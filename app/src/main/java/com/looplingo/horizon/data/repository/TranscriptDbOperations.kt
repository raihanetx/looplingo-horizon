package com.looplingo.horizon.data.repository

import com.looplingo.horizon.data.local.dao.TranscriptionDao
import com.looplingo.horizon.data.local.entity.TranscriptionEntity
import timber.log.Timber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranscriptDbOperations @Inject constructor(
    private val transcriptionDao: TranscriptionDao
) {

    suspend fun loadTranscriptions(videoPath: String): List<TranscriptionEntity> {
        return withContext(Dispatchers.IO) {
            try {
                transcriptionDao.getTranscriptionsForVideoOnce(videoPath)
            } catch (e: Exception) {
                Timber.e(e, "Failed to load transcriptions from DB for: %s", videoPath)
                emptyList()
            }
        }
    }

    suspend fun loadMetadata(videoPath: String): Pair<String?, String>? {
        return withContext(Dispatchers.IO) {
            try {
                val meta = transcriptionDao.getTranscriptionMetaForVideo(videoPath)
                if (meta == null) return@withContext null
                Pair(meta.translationLanguage, meta.languageCode)
            } catch (e: Exception) {
                Timber.e(e, "Failed to load metadata for: %s", videoPath)
                null
            }
        }
    }

    suspend fun hasTranscriptions(videoPath: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                transcriptionDao.getTranscriptionCountForVideo(videoPath) > 0
            } catch (e: Exception) {
                Timber.e(e, "Failed to check transcriptions for: %s", videoPath)
                false
            }
        }
    }

    suspend fun replaceTranscriptions(videoPath: String, entities: List<TranscriptionEntity>) {
        withContext(Dispatchers.IO) {
            transcriptionDao.replaceTranscriptionsForVideo(videoPath, entities)
        }
    }

    suspend fun cleanupOld(days: Int): Int {
        return withContext(Dispatchers.IO) {
            try {
                val cutoff = System.currentTimeMillis() - (days.toLong() * 24 * 60 * 60 * 1000)
                val deleted = transcriptionDao.deleteOlderThan(cutoff)
                if (deleted > 0) {
                    Timber.i("Cleaned up %d transcriptions older than %d days", deleted, days)
                }
                deleted
            } catch (e: Exception) {
                Timber.e(e, "Failed to cleanup old transcriptions")
                0
            }
        }
    }

    suspend fun deleteOrphaned(): Int {
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
