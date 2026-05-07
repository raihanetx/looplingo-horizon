package com.looplingo.horizon.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores the user-defined playback rule for a specific video.
 * There is a 1:1 relationship between a video and its playback rule.
 *
 * Using [videoPath] as the primary key guarantees at most one rule per video.
 *
 * [loopMode] is stored as the enum name string (e.g., "LOOP_INFINITE") rather
 * than an ordinal, so the data is immune to enum reordering in future versions.
 */
@Entity(tableName = "playback_rules")
data class PlaybackRuleEntity(
    @PrimaryKey
    val videoPath: String,
    val startAction: Int,        // 0 = AUTO_PLAY, 1 = WAIT_MANUAL — stored as Int for Room compatibility
    val rangeStartMs: Long,      // Start of playback range in ms (0 = beginning)
    val rangeEndMs: Long,        // End of playback range in ms (-1 = entire duration)
    val loopMode: String,        // LoopMode.name — immune to enum reordering
    val loopCount: Int,          // Number of loops (relevant for LOOP_X_TIMES and AUTO_LOOP)
    val autoAdvance: Boolean     // Whether to auto-advance to next video after loop completes
)
