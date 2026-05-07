package com.looplingo.horizon.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.looplingo.horizon.data.entity.PlaybackRuleEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for the [PlaybackRuleEntity] table.
 *
 * Each video can have at most one playback rule (1:1 relationship).
 * The [videoPath] field serves as both the foreign key to the video
 * and the primary key of this table.
 */
@Dao
interface PlaybackRuleDao {

    /**
     * Returns the playback rule for the given [videoPath], or null if none exists.
     * The LIMIT 1 clause is defensive — there should never be more than one
     * rule per video due to the primary key constraint.
     */
    @Query("SELECT * FROM playback_rules WHERE videoPath = :videoPath LIMIT 1")
    suspend fun getRuleForVideo(videoPath: String): PlaybackRuleEntity?

    /**
     * Inserts or replaces a playback rule.
     * Uses [OnConflictStrategy.REPLACE] so that updating an existing rule
     * for the same video is seamless.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: PlaybackRuleEntity)

    /**
     * Deletes the playback rule for the given [videoPath].
     * Used when the user wants to reset a video's settings back to defaults.
     */
    @Query("DELETE FROM playback_rules WHERE videoPath = :videoPath")
    suspend fun deleteRuleByVideoPath(videoPath: String)

    /**
     * Returns all playback rules as a one-shot query.
     * Used for loading initial badge data.
     */
    @Query("SELECT * FROM playback_rules")
    suspend fun getAllRules(): List<PlaybackRuleEntity>

    /**
     * Returns all playback rules as a reactive [Flow].
     * The Flow emits a new list whenever any rule is inserted, updated, or deleted,
     * ensuring that loop mode badges in the video list update in real-time.
     */
    @Query("SELECT * FROM playback_rules")
    fun getAllRulesFlow(): Flow<List<PlaybackRuleEntity>>

    /**
     * Deletes playback rules for videos that no longer exist in the videos table.
     * This prevents orphaned rules from accumulating when video files are
     * deleted from the device but their rules remain in the database.
     *
     * Should be called periodically (e.g., after a media scan completes).
     */
    @Query("""
        DELETE FROM playback_rules
        WHERE videoPath NOT IN (SELECT path FROM videos)
    """)
    suspend fun deleteOrphanedRules(): Int
}
