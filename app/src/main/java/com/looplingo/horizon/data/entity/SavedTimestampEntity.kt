package com.looplingo.horizon.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A saved A-B timestamp bookmark for a video.
 * Users can save frequently used loop segments and quickly recall them.
 *
 * Example: "Chorus" from 1:23 to 2:45, loop 3 times
 */
@Entity(
    tableName = "saved_timestamps",
    indices = [Index(value = ["videoPath"])]
)
data class SavedTimestampEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val videoPath: String,
    val label: String,
    val rangeStartMs: Long,
    val rangeEndMs: Long,
    val loopCount: Int = 3,
    val createdAt: Long = System.currentTimeMillis()
)
