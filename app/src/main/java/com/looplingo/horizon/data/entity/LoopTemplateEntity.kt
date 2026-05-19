package com.looplingo.horizon.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for loop templates.
 *
 * Stores reusable loop configurations for a specific video.
 * Two template types are supported:
 *  - "dialogue_repeat": Repeat all dialogues × N times sequentially
 *  - "time_range": Different loop counts for different time ranges
 */
@Entity(
    tableName = "loop_templates",
    indices = [
        Index(value = ["videoPath"], name = "index_loop_templates_videoPath")
    ]
)
data class LoopTemplateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Path of the source video/audio file. Matches VideoEntity.path. */
    @ColumnInfo(name = "videoPath")
    val videoPath: String,

    /** User-visible name for this template. */
    val name: String,

    /** Template type: "dialogue_repeat" or "time_range". */
    val type: String,

    /** Default loop count for this template. */
    @ColumnInfo(name = "defaultLoopCount", defaultValue = "3")
    val defaultLoopCount: Int = 3,

    /** Timestamp when this template was created. */
    @ColumnInfo(name = "createdAt")
    val createdAt: Long = System.currentTimeMillis()
)
