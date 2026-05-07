package com.looplingo.horizon.repository

import com.looplingo.horizon.data.dao.PlaybackRuleDao
import com.looplingo.horizon.model.LoopMode
import com.looplingo.horizon.model.PlaybackConfig
import com.looplingo.horizon.model.PlaybackConfigValidator
import com.looplingo.horizon.model.StartAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Singleton

/**
 * Single source of truth for playback rules.
 *
 * Each video can have one associated PlaybackRuleEntity stored in Room.
 * The repository converts between the domain model (PlaybackConfig) and
 * the database entity (PlaybackRuleEntity), keeping the UI layer clean.
 *
 * All DB operations are wrapped in try-catch so that a Room failure
 * doesn't crash the app — it returns null/default instead.
 *
 * Configs are validated and sanitized before being returned or persisted,
 * ensuring that even if Room contains stale data from a previous version,
 * the service always receives safe values.
 */
@Singleton
class PlaybackRepository constructor(
    private val playbackRuleDao: PlaybackRuleDao
) {

    /**
     * Load the saved playback config for a given video, or null if none exists
     * or if the database read fails.
     *
     * The returned config is sanitized via [PlaybackConfigValidator] to ensure
     * safe values even if the database contains corrupted or outdated data.
     */
    suspend fun getConfigForVideo(videoPath: String): PlaybackConfig? {
        return try {
            withContext(Dispatchers.IO) {
                val entity = playbackRuleDao.getRuleForVideo(videoPath)
                entity?.let {
                    val config = PlaybackConfig(
                        videoPath = it.videoPath,
                        startAction = StartAction.fromValue(it.startAction),
                        rangeStartMs = it.rangeStartMs.coerceAtLeast(0),
                        rangeEndMs = it.rangeEndMs,
                        loopMode = try {
                            LoopMode.valueOf(it.loopMode)
                        } catch (e: IllegalArgumentException) {
                            Timber.w(e, "Invalid loop mode '%s' in DB — defaulting to LOOP_INFINITE", it.loopMode)
                            LoopMode.LOOP_INFINITE
                        },
                        loopCount = it.loopCount.coerceAtLeast(1),
                        autoAdvance = it.autoAdvance
                    )

                    // Sanitize in case the DB has inconsistent data
                    val sanitized = PlaybackConfigValidator.sanitize(config)
                    if (sanitized != config) {
                        Timber.w("PlaybackConfig for %s was sanitized from DB data", videoPath)
                    }

                    sanitized
                }
            }
        } catch (e: IllegalStateException) {
            Timber.e(e, "Database cursor error loading config for %s", videoPath)
            null
        } catch (e: Exception) {
            Timber.e(e, "Failed to load playback config for %s", videoPath)
            null
        }
    }

    /**
     * Persist a playback config. Replaces any existing rule for the same video.
     *
     * The config is validated before saving. If validation fails, the config
     * is sanitized and saved with corrected values. Returns true if saved
     * successfully, false otherwise.
     */
    suspend fun saveConfig(config: PlaybackConfig): Boolean {
        // Validate and sanitize before persisting
        val validationResult = PlaybackConfigValidator.validate(config)
        val configToSave = if (!validationResult.isValid) {
            Timber.w("Saving sanitized config — validation issues: %s", validationResult.errors)
            PlaybackConfigValidator.sanitize(config)
        } else {
            config
        }

        return try {
            withContext(Dispatchers.IO) {
                val entity = com.looplingo.horizon.data.entity.PlaybackRuleEntity(
                    videoPath = configToSave.videoPath,
                    startAction = configToSave.startAction.value,
                    rangeStartMs = configToSave.rangeStartMs,
                    rangeEndMs = configToSave.rangeEndMs,
                    loopMode = configToSave.loopMode.name,
                    loopCount = configToSave.loopCount.coerceAtLeast(1),
                    autoAdvance = configToSave.autoAdvance
                )
                playbackRuleDao.insertRule(entity)
                Timber.i("Saved playback config for %s (mode=%s, loops=%d)", configToSave.videoPath, configToSave.loopMode, configToSave.loopCount)
            }
            true
        } catch (e: IllegalStateException) {
            Timber.e(e, "Database error saving config for %s", configToSave.videoPath)
            false
        } catch (e: Exception) {
            Timber.e(e, "Failed to save playback config for %s", configToSave.videoPath)
            false
        }
    }

    /**
     * Load all saved playback configs. Returns a map of videoPath → LoopMode
     * for efficient badge display in the video list.
     */
    suspend fun getAllConfiguredModes(): Map<String, String> {
        return try {
            withContext(Dispatchers.IO) {
                playbackRuleDao.getAllRules().associate { rule ->
                    val mode = try {
                        LoopMode.valueOf(rule.loopMode)
                    } catch (e: IllegalArgumentException) {
                        LoopMode.LOOP_INFINITE
                    }
                    rule.videoPath to mode.displayBadge
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load configured modes")
            emptyMap()
        }
    }

    /**
     * Reactive Flow of all configured modes. Emits a new map whenever
     * any playback rule is inserted, updated, or deleted.
     *
     * Use this in ViewModels for real-time badge updates in the video list.
     */
    fun getAllConfiguredModesFlow(): Flow<Map<String, String>> {
        return playbackRuleDao.getAllRulesFlow()
            .map { rules ->
                rules.associate { rule ->
                    val mode = try {
                        LoopMode.valueOf(rule.loopMode)
                    } catch (e: IllegalArgumentException) {
                        LoopMode.LOOP_INFINITE
                    }
                    rule.videoPath to mode.displayBadge
                }
            }
            .catch { e ->
                Timber.e(e, "Error in configured modes Flow")
                emit(emptyMap())
            }
    }

    /**
     * Delete orphaned playback rules (rules for videos that no longer
     * exist in the video cache). Should be called after a media scan
     * completes to prevent stale data from accumulating.
     */
    suspend fun deleteOrphanedRules() {
        try {
            withContext(Dispatchers.IO) {
                val deleted = playbackRuleDao.deleteOrphanedRules()
                if (deleted > 0) {
                    Timber.i("Deleted %d orphaned playback rules", deleted)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete orphaned rules")
        }
    }

    /**
     * Delete the saved config for a video. Returns true if the deletion
     * succeeded, false otherwise. Used when the user wants to reset
     * a video's settings back to defaults.
     */
    suspend fun deleteConfigForVideo(videoPath: String): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                playbackRuleDao.deleteRuleByVideoPath(videoPath)
                Timber.i("Deleted playback config for %s", videoPath)
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete playback config for %s", videoPath)
            false
        }
    }
}
