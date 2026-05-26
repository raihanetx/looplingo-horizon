package com.looplingo.horizon.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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

    @ColumnInfo(name = "templateId")
    val templateId: Long,

    @ColumnInfo(name = "startMs")
    val startMs: Long,

    @ColumnInfo(name = "endMs")
    val endMs: Long,

    @ColumnInfo(name = "loopCount")
    val loopCount: Int
)
