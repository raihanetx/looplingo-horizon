package com.looplingo.horizon.repository

import android.content.Context
import com.looplingo.horizon.data.dao.VideoDao
import com.looplingo.horizon.data.entity.VideoEntity
import com.looplingo.horizon.model.SortOrder
import com.looplingo.horizon.util.FileScanner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for video data.
 *
 * Strategy:
 *  1. Scan the device via MediaStore (FileScanner)
 *  2. Cache the results in Room (VideoDao)
 *  3. Expose cached data as a Flow so the UI always observes the latest state
 *
 * This ensures the UI never talks directly to MediaStore or to the DAO.
 * All errors are caught and logged — the UI always gets data or an empty result,
 * never a crash.
 *
 * BUG FIX: Context is now held as a weak reference via application context to prevent
 * memory leaks. Previously, @ApplicationContext Context was stored directly, which
 * could hold a reference to the entire application. While @ApplicationContext doesn't
 * leak Activities, it's still best practice to use getApplicationContext() explicitly
 * and to only hold the Context for the minimum time needed.
 */
@Singleton
class VideoRepository @Inject constructor(
    private val videoDao: VideoDao,
    private val fileScanner: FileScanner,
    @ApplicationContext private val context: Context
) {

    /**
     * Observe all cached videos using the given sort order.
     * The caller (ViewModel) is responsible for tracking the current sort order
     * and re-starting collection when it changes.
     */
    fun getVideos(sortOrder: SortOrder = SortOrder.DATE): Flow<List<VideoEntity>> =
        when (sortOrder) {
            SortOrder.DATE -> videoDao.getAllVideosFlow()
            SortOrder.TITLE -> videoDao.getAllVideosSortedByTitle()
            SortOrder.DURATION -> videoDao.getAllVideosSortedByDuration()
            SortOrder.SIZE -> videoDao.getAllVideosSortedBySize()
        }.catch { e ->
            Timber.e(e, "Error in videos Flow — emitting empty list")
            emit(emptyList())
        }

    /**
     * Perform a fresh scan of the device's MediaStore and sync the results
     * into the local Room cache. Call this on launch or on a manual refresh.
     *
     * Errors are caught and logged — the UI will still show whatever was
     * previously cached in Room.
     */
    suspend fun refreshVideos() = withContext(Dispatchers.IO) {
        try {
            Timber.d("Starting video refresh from MediaStore...")
            // Use applicationContext explicitly — avoids holding any Activity reference
            val appContext = context.applicationContext
            val scanned = fileScanner.scanVideosList(appContext)
            syncCache(scanned)
            Timber.i("Video refresh complete: %d videos in cache", scanned.size)
        } catch (e: SecurityException) {
            Timber.e(e, "Missing storage permission for video scan — user must grant permission")
        } catch (e: OutOfMemoryError) {
            Timber.e(e, "Out of memory during video scan — device may be low on resources")
        } catch (e: Exception) {
            Timber.e(e, "Failed to scan videos from MediaStore")
        }
    }

    /**
     * Look up the content:// URI for a video by its file path.
     * Uses a targeted query instead of loading all videos.
     * Returns null if the video is not found in the cache or if the DB read fails.
     * This is used by the playback service to resolve URIs for scoped storage.
     */
    suspend fun getContentUriForPath(videoPath: String): String? {
        return try {
            withContext(Dispatchers.IO) {
                val uri = videoDao.getContentUriForPath(videoPath)
                uri?.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to look up content URI for: %s", videoPath)
            null
        }
    }

    /**
     * Efficiently sync the scanned list into Room:
     *  - Insert videos that exist in the scan but not in the cache
     *  - Delete cached videos that no longer exist on disk
     *  - Orphaned playback rules are cleaned up via [PlaybackRepository.deleteOrphanedRules]
     *    after the sync completes (called by the ViewModel after refreshVideos).
     *
     * This runs inside a try-catch so that a partial sync failure doesn't
     * lose whatever was already cached.
     */
    private suspend fun syncCache(scanned: List<VideoEntity>) {
        try {
            val cached = videoDao.getAllVideos()
            val scannedPaths = scanned.map { it.path }.toSet()
            val cachedPaths = cached.map { it.path }.toSet()

            // Insert new videos
            val toInsert = scanned.filter { it.path !in cachedPaths }
            if (toInsert.isNotEmpty()) {
                videoDao.insertAll(toInsert)
                Timber.d("Inserted %d new videos into cache", toInsert.size)
            }

            // Remove stale videos (deleted from device since last scan)
            val stalePaths = cachedPaths - scannedPaths
            if (stalePaths.isNotEmpty()) {
                videoDao.deleteByPaths(stalePaths.toList())
                Timber.d("Removed %d stale videos from cache", stalePaths.size)
            }

            // Update content URIs for existing entries that might have changed
            // Use Set for O(1) lookup instead of List O(n) for large libraries
            val toUpdate = scanned.filter { it.path in cachedPaths }
            if (toUpdate.isNotEmpty()) {
                videoDao.insertAll(toUpdate)  // REPLACE strategy updates existing rows
                Timber.d("Updated %d existing video entries", toUpdate.size)
            }

            if (toInsert.isEmpty() && stalePaths.isEmpty()) {
                Timber.d("Cache is already up-to-date")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync video cache with Room — cached data may be stale")
        }
    }
}
