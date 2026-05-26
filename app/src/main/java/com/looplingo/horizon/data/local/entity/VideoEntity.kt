package com.looplingo.horizon.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "videos",
    indices = [
        Index(value = ["title"]),
        Index(value = ["lastModified"])
    ]
)
data class VideoEntity(
    @PrimaryKey
    val path: String,
    val title: String,
    val duration: Long,
    val size: Long,
    val lastModified: Long,
    val contentUri: String = ""
)
