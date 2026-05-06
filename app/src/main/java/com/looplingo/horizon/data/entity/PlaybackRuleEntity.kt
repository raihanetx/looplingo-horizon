package com.looplingo.horizon.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playback_rules")
data class PlaybackRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val videoPath: String,
    val startAction: Int, // 0=AUTO_PLAY, 1=WAIT_MANUAL
    val rangeStartMs: Long,
    val rangeEndMs: Long,
    val loopMode: Int, // LoopMode ordinal
    val loopCount: Int,
    val autoAdvance: Boolean
)
