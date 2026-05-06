package com.looplingo.horizon.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.looplingo.horizon.data.entity.VideoEntity

@Dao
interface VideoDao {
    @Query("SELECT * FROM videos ORDER BY lastModified DESC")
    suspend fun getAllVideos(): List<VideoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVideo(video: VideoEntity)

    @Query("DELETE FROM videos")
    suspend fun clearAll()
}
