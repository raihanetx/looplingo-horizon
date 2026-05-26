package com.looplingo.horizon.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.looplingo.horizon.data.local.entity.VideoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {

    @Query("SELECT * FROM videos ORDER BY lastModified DESC")
    suspend fun getAllVideos(): List<VideoEntity>

    @Query("SELECT * FROM videos ORDER BY lastModified DESC")
    fun getAllVideosFlow(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos ORDER BY title COLLATE NOCASE ASC")
    fun getAllVideosSortedByTitle(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos ORDER BY duration DESC")
    fun getAllVideosSortedByDuration(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos ORDER BY size DESC")
    fun getAllVideosSortedBySize(): Flow<List<VideoEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVideo(video: VideoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(videos: List<VideoEntity>)

    @Query("DELETE FROM videos WHERE path IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    @Query("DELETE FROM videos")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceAll(videos: List<VideoEntity>) {
        clearAll()
        insertAll(videos)
    }

    @Query("SELECT contentUri FROM videos WHERE path = :videoPath LIMIT 1")
    suspend fun getContentUriForPath(videoPath: String): String?
}
