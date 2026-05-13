package com.looplingo.horizon.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.looplingo.horizon.data.entity.PlaybackRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackRuleDao {

    @Query("SELECT * FROM playback_rules WHERE videoPath = :videoPath LIMIT 1")
    suspend fun getRuleForVideo(videoPath: String): PlaybackRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: PlaybackRuleEntity)

    @Query("DELETE FROM playback_rules WHERE videoPath = :videoPath")
    suspend fun deleteRuleByVideoPath(videoPath: String)

    @Query("SELECT * FROM playback_rules")
    suspend fun getAllRules(): List<PlaybackRuleEntity>

    @Query("SELECT * FROM playback_rules")
    fun getAllRulesFlow(): Flow<List<PlaybackRuleEntity>>

    @Query("""
        DELETE FROM playback_rules
        WHERE videoPath NOT IN (SELECT path FROM videos)
    """)
    suspend fun deleteOrphanedRules(): Int
}
