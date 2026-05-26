package com.looplingo.horizon.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playback_rules")
data class PlaybackRuleEntity(
    @PrimaryKey
    val videoPath: String,
    val rangeStartMs: Long = 0L,
    val rangeEndMs: Long = -1L,
    val loopCount: Int = 1,
    val speed: Float = 1.0f
)
