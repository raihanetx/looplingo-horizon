package com.looplingo.horizon.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.looplingo.horizon.data.entity.SavedTimestampEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedTimestampDao {

    @Query("SELECT * FROM saved_timestamps WHERE videoPath = :videoPath ORDER BY createdAt DESC")
    fun getTimestampsForVideo(videoPath: String): Flow<List<SavedTimestampEntity>>

    @Query("SELECT * FROM saved_timestamps WHERE videoPath = :videoPath ORDER BY createdAt DESC")
    suspend fun getTimestampsForVideoOnce(videoPath: String): List<SavedTimestampEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTimestamp(timestamp: SavedTimestampEntity): Long

    @Delete
    suspend fun deleteTimestamp(timestamp: SavedTimestampEntity)

    @Query("DELETE FROM saved_timestamps WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM saved_timestamps WHERE videoPath = :videoPath")
    suspend fun deleteAllForVideo(videoPath: String)
}
