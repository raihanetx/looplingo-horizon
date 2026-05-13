package com.looplingo.horizon.repository

import com.looplingo.horizon.data.dao.PlaybackRuleDao
import com.looplingo.horizon.data.entity.PlaybackRuleEntity
import com.looplingo.horizon.model.PlaybackConfig
import com.looplingo.horizon.model.PlaybackConfigValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackRepository @Inject constructor(
    private val playbackRuleDao: PlaybackRuleDao
) {

    suspend fun getConfigForVideo(videoPath: String): PlaybackConfig? {
        return try {
            withContext(Dispatchers.IO) {
                val entity = playbackRuleDao.getRuleForVideo(videoPath)
                entity?.let {
                    val config = PlaybackConfig(
                        videoPath = it.videoPath,
                        rangeStartMs = it.rangeStartMs.coerceAtLeast(0),
                        rangeEndMs = it.rangeEndMs,
                        loopCount = it.loopCount.coerceAtLeast(1),
                        speed = it.speed.coerceIn(0.25f, 2.0f)
                    )
                    PlaybackConfigValidator.sanitize(config)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load playback config for %s", videoPath)
            null
        }
    }

    suspend fun saveConfig(config: PlaybackConfig): Boolean {
        val configToSave = if (!PlaybackConfigValidator.isValid(config)) {
            PlaybackConfigValidator.sanitize(config)
        } else {
            config
        }

        return try {
            withContext(Dispatchers.IO) {
                val entity = PlaybackRuleEntity(
                    videoPath = configToSave.videoPath,
                    rangeStartMs = configToSave.rangeStartMs,
                    rangeEndMs = configToSave.rangeEndMs,
                    loopCount = configToSave.loopCount,
                    speed = configToSave.speed
                )
                playbackRuleDao.insertRule(entity)
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to save playback config")
            false
        }
    }

    suspend fun getAllConfiguredModes(): Map<String, String> {
        return try {
            withContext(Dispatchers.IO) {
                playbackRuleDao.getAllRules().associate { rule ->
                    val config = PlaybackConfig(
                        videoPath = rule.videoPath,
                        rangeStartMs = rule.rangeStartMs,
                        rangeEndMs = rule.rangeEndMs,
                        loopCount = rule.loopCount,
                        speed = rule.speed
                    )
                    rule.videoPath to config.displayBadge
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load configured modes")
            emptyMap()
        }
    }

    fun getAllConfiguredModesFlow(): Flow<Map<String, String>> {
        return playbackRuleDao.getAllRulesFlow()
            .map { rules ->
                rules.associate { rule ->
                    val config = PlaybackConfig(
                        videoPath = rule.videoPath,
                        rangeStartMs = rule.rangeStartMs,
                        rangeEndMs = rule.rangeEndMs,
                        loopCount = rule.loopCount,
                        speed = rule.speed
                    )
                    rule.videoPath to config.displayBadge
                }
            }
            .catch { e ->
                Timber.e(e, "Error in configured modes Flow")
                emit(emptyMap())
            }
    }

    suspend fun deleteOrphanedRules() {
        try {
            withContext(Dispatchers.IO) {
                playbackRuleDao.deleteOrphanedRules()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete orphaned rules")
        }
    }

    suspend fun deleteConfigForVideo(videoPath: String): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                playbackRuleDao.deleteRuleByVideoPath(videoPath)
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete playback config")
            false
        }
    }
}
