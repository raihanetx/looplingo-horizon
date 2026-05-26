package com.looplingo.horizon.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
