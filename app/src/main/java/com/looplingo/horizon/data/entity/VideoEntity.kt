package com.looplingo.horizon.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a cached video entry in the local database.
 * This is populated from MediaStore scans and serves as the
 * single source of truth for the UI layer.
 *
 * [path] is the primary key (unique on disk).
 * [contentUri] is the MediaStore content:// URI needed for
 * scoped storage access on Android 10+.
 *
 * Indices on [title] and [lastModified] speed up the common
 * sort operations in the video list without requiring full table scans.
 */
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
