package com.looplingo.horizon.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.looplingo.horizon.data.entity.LoopTemplateEntity
import com.looplingo.horizon.data.entity.LoopTemplateRangeEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for CRUD operations on loop templates and their ranges.
 */
@Dao
interface LoopTemplateDao {

    // ══════════════════════════════════════════════════════════════════════
    // TEMPLATE CRUD
    // ══════════════════════════════════════════════════════════════════════

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: LoopTemplateEntity): Long

    @Delete
    suspend fun deleteTemplate(template: LoopTemplateEntity)

    @Query("DELETE FROM loop_templates WHERE id = :templateId")
    suspend fun deleteTemplateById(templateId: Long)

    @Query("SELECT * FROM loop_templates WHERE videoPath = :videoPath ORDER BY createdAt DESC")
    suspend fun getTemplatesForVideo(videoPath: String): List<LoopTemplateEntity>

    @Query("SELECT * FROM loop_templates WHERE videoPath = :videoPath ORDER BY createdAt DESC")
    fun getTemplatesForVideoFlow(videoPath: String): Flow<List<LoopTemplateEntity>>

    @Query("SELECT * FROM loop_templates WHERE id = :templateId")
    suspend fun getTemplateById(templateId: Long): LoopTemplateEntity?

    @Query("DELETE FROM loop_templates WHERE videoPath = :videoPath")
    suspend fun deleteTemplatesForVideo(videoPath: String)

    // ══════════════════════════════════════════════════════════════════════
    // RANGE CRUD
    // ══════════════════════════════════════════════════════════════════════

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRanges(ranges: List<LoopTemplateRangeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRange(range: LoopTemplateRangeEntity): Long

    @Query("SELECT * FROM loop_template_ranges WHERE templateId = :templateId ORDER BY startMs ASC")
    suspend fun getRangesForTemplate(templateId: Long): List<LoopTemplateRangeEntity>

    @Query("DELETE FROM loop_template_ranges WHERE templateId = :templateId")
    suspend fun deleteRangesForTemplate(templateId: Long)
}
