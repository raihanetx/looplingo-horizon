package com.looplingo.horizon.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for time-range based loop template ranges.
 *
 * Each row defines a time range within a video with a specific
 * loop count. Used by "time_range" type templates.
 *
 * Example: 0:00-2:00 = 1x (intro), 2:00-45:00 = 5x (main), 45:00-end = 1x (credits)
 */
@Entity(
    tableName = "loop_template_ranges",
    indices = [
        Index(value = ["templateId"], name = "index_loop_template_ranges_templateId")
    ],
    foreignKeys = [
        ForeignKey(
            entity = LoopTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class LoopTemplateRangeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Foreign key reference to the parent template. */
    @ColumnInfo(name = "templateId")
    val templateId: Long,

    /** Start time in milliseconds for this range. */
    @ColumnInfo(name = "startMs")
    val startMs: Long,

    /** End time in milliseconds for this range. -1 means "to end of video". */
    @ColumnInfo(name = "endMs")
    val endMs: Long,

    /** Loop count for this specific time range. */
    @ColumnInfo(name = "loopCount")
    val loopCount: Int
)
