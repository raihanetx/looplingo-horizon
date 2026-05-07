package com.looplingo.horizon.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.looplingo.horizon.data.entity.VideoEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for the [VideoEntity] table.
 *
 * Provides CRUD operations for the video cache, which is populated
 * from MediaStore scans and serves as the single source of truth
 * for the UI layer via [getAllVideosFlow].
 */
@Dao
interface VideoDao {

    /**
     * Returns all cached videos as a one-shot list, sorted by last modified (newest first).
     * Use this for one-time reads (e.g., loading video paths in the service).
     */
    @Query("SELECT * FROM videos ORDER BY lastModified DESC")
    suspend fun getAllVideos(): List<VideoEntity>

    /**
     * Returns all cached videos as a reactive [Flow], sorted by last modified (newest first).
     * Use this for observing changes in the UI layer (MainViewModel).
     * The Flow emits a new list whenever the underlying table changes.
     */
    @Query("SELECT * FROM videos ORDER BY lastModified DESC")
    fun getAllVideosFlow(): Flow<List<VideoEntity>>

    /**
     * Returns all cached videos sorted by title alphabetically (A-Z).
     */
    @Query("SELECT * FROM videos ORDER BY title COLLATE NOCASE ASC")
    fun getAllVideosSortedByTitle(): Flow<List<VideoEntity>>

    /**
     * Returns all cached videos sorted by duration (longest first).
     */
    @Query("SELECT * FROM videos ORDER BY duration DESC")
    fun getAllVideosSortedByDuration(): Flow<List<VideoEntity>>

    /**
     * Returns all cached videos sorted by file size (largest first).
     */
    @Query("SELECT * FROM videos ORDER BY size DESC")
    fun getAllVideosSortedBySize(): Flow<List<VideoEntity>>

    /**
     * Inserts or replaces a single video entry.
     * Uses [OnConflictStrategy.REPLACE] so that an existing entry with the same
     * [VideoEntity.path] is updated rather than causing a conflict.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVideo(video: VideoEntity)

    /**
     * Inserts or replaces a batch of video entries.
     * This is more efficient than calling [insertVideo] in a loop
     * because Room wraps the batch in a single transaction.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(videos: List<VideoEntity>)

    /**
     * Deletes all video entries whose paths are in the given set.
     * More efficient than calling [deleteByPath] in a loop because
     * Room wraps the batch in a single transaction.
     */
    @Query("DELETE FROM videos WHERE path IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    /**
     * Deletes all video entries from the cache.
     * Use sparingly — typically only during database migration or testing.
     */
    @Query("DELETE FROM videos")
    suspend fun clearAll()

    /**
     * Atomically replaces the entire video cache with a fresh scan result.
     *
     * This is performed in a single transaction so the UI never sees an
     * intermediate empty state. Old videos not present in [videos] are removed,
     * and existing videos are updated with fresh metadata.
     *
     * Should be called after a full MediaStore rescan completes.
     */
    @Transaction
    suspend fun replaceAll(videos: List<VideoEntity>) {
        clearAll()
        insertAll(videos)
    }

    /**
     * Look up the content:// URI for a video by its file path.
     * Returns null if no video with the given path exists.
     * More efficient than loading all videos for a single lookup.
     */
    @Query("SELECT contentUri FROM videos WHERE path = :videoPath LIMIT 1")
    suspend fun getContentUriForPath(videoPath: String): String?
}
