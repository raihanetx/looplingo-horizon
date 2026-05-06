package com.looplingo.horizon.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.looplingo.horizon.data.entity.PlaybackRuleEntity

@Dao
interface PlaybackRuleDao {
    @Query("SELECT * FROM playback_rules WHERE videoPath = :videoPath LIMIT 1")
    suspend fun getRuleForVideo(videoPath: String): PlaybackRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: PlaybackRuleEntity)
}
