package com.looplingo.horizon.data.repository

import android.content.Context
import com.looplingo.horizon.data.local.dao.VideoDao
import com.looplingo.horizon.data.local.entity.VideoEntity
import com.looplingo.horizon.domain.model.SortOrder
import com.looplingo.horizon.core.FileScanner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoRepository @Inject constructor(
    private val videoDao: VideoDao,
    private val fileScanner: FileScanner,
    @ApplicationContext private val context: Context
) {

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

    suspend fun refreshVideos() = withContext(Dispatchers.IO) {
        try {
            Timber.d("Starting video refresh from MediaStore...")
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

    private suspend fun syncCache(scanned: List<VideoEntity>) {
        try {
            val cached = videoDao.getAllVideos()
            val scannedPaths = scanned.map { it.path }.toSet()
            val cachedPaths = cached.map { it.path }.toSet()

            val toInsert = scanned.filter { it.path !in cachedPaths }
            if (toInsert.isNotEmpty()) {
                videoDao.insertAll(toInsert)
                Timber.d("Inserted %d new videos into cache", toInsert.size)
            }

            val stalePaths = cachedPaths - scannedPaths
            if (stalePaths.isNotEmpty()) {
                videoDao.deleteByPaths(stalePaths.toList())
                Timber.d("Removed %d stale videos from cache", stalePaths.size)
            }

            val toUpdate = scanned.filter { it.path in cachedPaths }
            if (toUpdate.isNotEmpty()) {
                videoDao.insertAll(toUpdate)
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
