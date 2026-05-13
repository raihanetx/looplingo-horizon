package com.looplingo.horizon.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for persisting Whisper transcription segments.
 *
 * This bridges the gap between GroqApiClient's ephemeral [Segment] objects
 * and the app's subtitle playback system. Transcriptions are saved here
 * so that:
 * 1. Users don't need to re-transcribe the same file (saves API calls)
 * 2. Transcriptions survive app restarts
 * 3. TranscriptRepository can serve them during playback
 *
 * The [videoPath] acts as a foreign-key-like reference to VideoEntity,
 * but we use a soft reference (not enforced FK) to avoid cascading issues
 * with MediaStore scans.
 */
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

    /** Path of the source video/audio file. Matches VideoEntity.path. */
    @ColumnInfo(name = "videoPath")
    val videoPath: String,

    /** Original text of this segment from Whisper. */
    val text: String,

    /** Segment start time in milliseconds (absolute from file start). */
    @ColumnInfo(name = "segmentStartMs")
    val segmentStartMs: Long,

    /** Segment end time in milliseconds (absolute from file start). */
    @ColumnInfo(name = "segmentEndMs")
    val segmentEndMs: Long,

    /** Whisper's no_speech_prob for this segment (0.0-1.0). Higher = likely silence. */
    @ColumnInfo(name = "noSpeechProb", defaultValue = "0.0")
    val noSpeechProb: Double = 0.0,

    /** Whisper's avg_logprob for this segment. Higher = more confident. */
    @ColumnInfo(name = "avgLogprob", defaultValue = "0.0")
    val avgLogprob: Double = 0.0,

    /** Language code used for transcription (e.g., "en", "auto"). */
    @ColumnInfo(name = "languageCode", defaultValue = "'auto'")
    val languageCode: String = "auto",

    /** Whether this is a translation (any→English) vs transcription. */
    @ColumnInfo(name = "isTranslation", defaultValue = "0")
    val isTranslation: Boolean = false,

    /** Translated text for this segment (e.g., Bangla translation of English text). */
    @ColumnInfo(name = "translatedText", defaultValue = "NULL")
    val translatedText: String? = null,

    /** Target language code for the translation (e.g., "bn" for Bangla). */
    @ColumnInfo(name = "translationLanguage", defaultValue = "NULL")
    val translationLanguage: String? = null,

    /** Timestamp when this transcription was created. */
    @ColumnInfo(name = "createdAt")
    val createdAt: Long = System.currentTimeMillis()
)
