package com.looplingo.horizon.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notes",
    indices = [Index(value = ["videoPath"])]
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoPath: String,
    val text: String,
    val timestampMs: Long,
    val createdAt: Long = System.currentTimeMillis()
)
