package com.looplingo.horizon.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transcriptions",
    indices = [
        Index(value = ["videoPath"], name = "index_transcriptions_videoPath"),
        Index(value = ["videoPath", "segmentStartMs"], name = "index_transcriptions_video_start")
    ]
)
data class TranscriptionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "videoPath")
    val videoPath: String,

    val text: String,

    @ColumnInfo(name = "segmentStartMs")
    val segmentStartMs: Long,

    @ColumnInfo(name = "segmentEndMs")
    val segmentEndMs: Long,

    @ColumnInfo(name = "vadStartMs", defaultValue = "NULL")
    val vadStartMs: Long? = null,

    @ColumnInfo(name = "vadEndMs", defaultValue = "NULL")
    val vadEndMs: Long? = null,

    @ColumnInfo(name = "noSpeechProb", defaultValue = "0.0")
    val noSpeechProb: Double = 0.0,

    @ColumnInfo(name = "avgLogprob", defaultValue = "0.0")
    val avgLogprob: Double = 0.0,

    @ColumnInfo(name = "languageCode", defaultValue = "'auto'")
    val languageCode: String = "auto",

    @ColumnInfo(name = "isTranslation", defaultValue = "0")
    val isTranslation: Boolean = false,

    @ColumnInfo(name = "translatedText", defaultValue = "NULL")
    val translatedText: String? = null,

    @ColumnInfo(name = "translationLanguage", defaultValue = "NULL")
    val translationLanguage: String? = null,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long = System.currentTimeMillis()
)
