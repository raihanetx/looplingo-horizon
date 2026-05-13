package com.looplingo.horizon.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores the user-defined playback rule for a specific video.
 * 1:1 relationship between a video and its playback rule.
 *
 * Simplified from the old 6-mode system:
 *  - No loopMode enum — behavior is determined by the values
 *  - No startAction — always auto-play
 *  - No autoAdvance — after loop completes, continue playing rest of video
 *  - Added speed control
 */
@Entity(tableName = "playback_rules")
data class PlaybackRuleEntity(
    @PrimaryKey
    val videoPath: String,
    val rangeStartMs: Long = 0L,
    val rangeEndMs: Long = -1L,
    val loopCount: Int = 1,
    val speed: Float = 1.0f
)
