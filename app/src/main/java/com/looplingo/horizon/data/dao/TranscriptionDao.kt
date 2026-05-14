package com.looplingo.horizon.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.looplingo.horizon.data.entity.TranscriptionEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the transcriptions table.
 *
 * Provides CRUD operations and queries for:
 * - Loading transcriptions for a specific video (for playback sync)
 * - Checking if transcriptions exist (to avoid redundant API calls)
 * - Deleting old transcriptions (cache management)
 * - Observing changes reactively via Flow
 */
@Dao
interface TranscriptionDao {

    /**
     * Get all transcription segments for a video, ordered by start time.
     * Returns Flow for reactive UI updates.
     */
    @Query("SELECT * FROM transcriptions WHERE videoPath = :videoPath ORDER BY segmentStartMs ASC")
    fun getTranscriptionsForVideo(videoPath: String): Flow<List<TranscriptionEntity>>

    /**
     * Get all transcription segments for a video (one-shot query).
     * Used when we need segments immediately without Flow overhead.
     */
    @Query("SELECT * FROM transcriptions WHERE videoPath = :videoPath ORDER BY segmentStartMs ASC")
    suspend fun getTranscriptionsForVideoOnce(videoPath: String): List<TranscriptionEntity>

    /**
     * Check if transcriptions exist for a video.
     * Returns the count of segments. Faster than loading all segments.
     */
    @Query("SELECT COUNT(*) FROM transcriptions WHERE videoPath = :videoPath")
    suspend fun getTranscriptionCountForVideo(videoPath: String): Int

    /**
     * Insert a single transcription segment.
     * Uses REPLACE strategy to handle deduplication.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegment(segment: TranscriptionEntity)

    /**
     * Insert multiple transcription segments in a single transaction.
     * Uses REPLACE strategy — if segments already exist for this video,
     * they will be updated. This is important because re-transcription
     * should replace old results.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<TranscriptionEntity>)

    /**
     * Delete all transcriptions for a specific video.
     * Called before inserting new transcriptions to ensure clean state.
     */
    @Query("DELETE FROM transcriptions WHERE videoPath = :videoPath")
    suspend fun deleteTranscriptionsForVideo(videoPath: String)

    /**
     * Delete a single transcription segment by ID.
     */
    @Query("DELETE FROM transcriptions WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Delete all transcriptions older than the specified timestamp.
     * Used for cache cleanup — remove transcriptions older than 30 days.
     */
    @Query("DELETE FROM transcriptions WHERE createdAt < :cutoffTimestamp")
    suspend fun deleteOlderThan(cutoffTimestamp: Long): Int

    /**
     * Delete orphaned transcriptions — segments whose videoPath no longer
     * exists in the videos table. Called during video cache sync.
     */
    @Query("""
        DELETE FROM transcriptions
        WHERE videoPath NOT IN (SELECT path FROM videos)
    """)
    suspend fun deleteOrphanedTranscriptions(): Int

    /**
     * Get total number of transcriptions (for debugging/stats).
     */
    @Query("SELECT COUNT(*) FROM transcriptions")
    suspend fun getTotalCount(): Int
}
