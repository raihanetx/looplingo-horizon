package com.looplingo.horizon.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.looplingo.horizon.data.local.entity.TranscriptionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptionDao {

    @Query("SELECT * FROM transcriptions WHERE videoPath = :videoPath ORDER BY segmentStartMs ASC")
    fun getTranscriptionsForVideo(videoPath: String): Flow<List<TranscriptionEntity>>

    @Query("SELECT * FROM transcriptions WHERE videoPath = :videoPath ORDER BY segmentStartMs ASC")
    suspend fun getTranscriptionsForVideoOnce(videoPath: String): List<TranscriptionEntity>

    @Query("SELECT COUNT(*) FROM transcriptions WHERE videoPath = :videoPath")
    suspend fun getTranscriptionCountForVideo(videoPath: String): Int

    @Query("SELECT translationLanguage, languageCode FROM transcriptions WHERE videoPath = :videoPath LIMIT 1")
    suspend fun getTranscriptionMetaForVideo(videoPath: String): TranscriptionMeta?

    data class TranscriptionMeta(
        val translationLanguage: String?,
        val languageCode: String
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegment(segment: TranscriptionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<TranscriptionEntity>)

    @Query("DELETE FROM transcriptions WHERE videoPath = :videoPath")
    suspend fun deleteTranscriptionsForVideo(videoPath: String)

    @Transaction
    suspend fun replaceTranscriptionsForVideo(videoPath: String, segments: List<TranscriptionEntity>) {
        deleteTranscriptionsForVideo(videoPath)
        insertSegments(segments)
    }

    @Query("DELETE FROM transcriptions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM transcriptions WHERE createdAt < :cutoffTimestamp")
    suspend fun deleteOlderThan(cutoffTimestamp: Long): Int

    @Query("""
        DELETE FROM transcriptions
        WHERE videoPath NOT IN (SELECT path FROM videos)
    """)
    suspend fun deleteOrphanedTranscriptions(): Int

    @Query("SELECT COUNT(*) FROM transcriptions")
    suspend fun getTotalCount(): Int
}
