package com.looplingo.horizon.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "loop_templates",
    indices = [
        Index(value = ["videoPath"], name = "index_loop_templates_videoPath")
    ]
)
data class LoopTemplateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "videoPath")
    val videoPath: String,

    val name: String,

    val type: String,

    @ColumnInfo(name = "defaultLoopCount", defaultValue = "3")
    val defaultLoopCount: Int = 3,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long = System.currentTimeMillis()
)
