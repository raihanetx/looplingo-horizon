package com.looplingo.horizon.repository

import com.looplingo.horizon.data.dao.LoopTemplateDao
import com.looplingo.horizon.data.entity.LoopTemplateEntity
import com.looplingo.horizon.data.entity.LoopTemplateRangeEntity
import com.looplingo.horizon.model.LoopTemplate
import com.looplingo.horizon.model.LoopTemplateRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing loop templates.
 *
 * Bridges between the domain model [LoopTemplate] and the Room
 * persistence layer [LoopTemplateEntity] / [LoopTemplateRangeEntity].
 */
@Singleton
class LoopTemplateRepository @Inject constructor(
    private val loopTemplateDao: LoopTemplateDao
) {

    /**
     * Get all templates for a video as a Flow for reactive UI updates.
     */
    fun getTemplatesForVideoFlow(videoPath: String): Flow<List<LoopTemplate>> {
        return loopTemplateDao.getTemplatesForVideoFlow(videoPath).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    /**
     * Get all templates for a video (one-shot query).
     */
    suspend fun getTemplatesForVideo(videoPath: String): List<LoopTemplate> {
        return withContext(Dispatchers.IO) {
            try {
                loopTemplateDao.getTemplatesForVideo(videoPath).map { entity ->
                    val ranges = loopTemplateDao.getRangesForTemplate(entity.id)
                    entity.toDomain(ranges)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to get templates for video: %s", videoPath)
                emptyList()
            }
        }
    }

    /**
     * Save a template (insert or update). Also saves any associated ranges.
     * Returns the database ID of the saved template.
     */
    suspend fun saveTemplate(template: LoopTemplate): Long {
        return withContext(Dispatchers.IO) {
            try {
                val entityId = loopTemplateDao.insertTemplate(template.toEntity())

                // Save ranges if this is a time_range template
                if (template.type == "time_range" && template.ranges.isNotEmpty()) {
                    // Delete old ranges first
                    loopTemplateDao.deleteRangesForTemplate(entityId)

                    // Insert new ranges with the correct templateId
                    val rangeEntities = template.ranges.map { range ->
                        range.toEntity(templateId = entityId)
                    }
                    loopTemplateDao.insertRanges(rangeEntities)
                }

                Timber.i("Saved loop template: %s (id=%d, type=%s)", template.name, entityId, template.type)
                entityId
            } catch (e: Exception) {
                Timber.e(e, "Failed to save template: %s", template.name)
                -1L
            }
        }
    }

    /**
     * Delete a template by ID. Cascading deletes will remove associated ranges.
     */
    suspend fun deleteTemplate(templateId: Long) {
        withContext(Dispatchers.IO) {
            try {
                loopTemplateDao.deleteRangesForTemplate(templateId)
                loopTemplateDao.deleteTemplateById(templateId)
                Timber.i("Deleted loop template id=%d", templateId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete template id=%d", templateId)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // MAPPING HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private fun LoopTemplateEntity.toDomain(ranges: List<LoopTemplateRangeEntity> = emptyList()): LoopTemplate {
        return LoopTemplate(
            id = id,
            videoPath = videoPath,
            name = name,
            type = type,
            defaultLoopCount = defaultLoopCount,
            ranges = ranges.map { it.toDomain() },
            createdAt = createdAt
        )
    }

    private fun LoopTemplateRangeEntity.toDomain(): LoopTemplateRange {
        return LoopTemplateRange(
            id = id,
            templateId = templateId,
            startMs = startMs,
            endMs = endMs,
            loopCount = loopCount
        )
    }

    private fun LoopTemplate.toEntity(): LoopTemplateEntity {
        return LoopTemplateEntity(
            id = if (id > 0) id else 0,
            videoPath = videoPath,
            name = name,
            type = type,
            defaultLoopCount = defaultLoopCount,
            createdAt = createdAt
        )
    }

    private fun LoopTemplateRange.toEntity(templateId: Long): LoopTemplateRangeEntity {
        return LoopTemplateRangeEntity(
            id = if (id > 0) id else 0,
            templateId = templateId,
            startMs = startMs,
            endMs = endMs,
            loopCount = loopCount
        )
    }
}
