package com.looplingo.horizon.util

import android.content.Context
import android.provider.MediaStore
import com.looplingo.horizon.data.entity.VideoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scans the device for video files using MediaStore.
 *
 * Implemented as an injectable singleton so it can be mocked in tests.
 * The [scanVideosList] method queries MediaStore and returns a list of
 * [VideoEntity] objects representing all video files found on the device.
 *
 * Safety notes:
 *  - The entire scan is wrapped in try-catch so a MediaStore failure
 *    returns an empty list instead of crashing the app.
 *  - Each individual row is also guarded so one bad entry doesn't kill
 *    the entire scan.
 *  - Builds content:// URIs for scoped storage compatibility on Android 10+.
 *  - Does NOT use File.exists() — on Android 10+ (scoped storage), raw file
 *    paths may be inaccessible even when the file exists. Instead, we trust
 *    MediaStore's results: if it's in the cursor, the file is accessible via
 *    its content:// URI.
 */
@Singleton
class FileScanner @Inject constructor() {

    suspend fun scanVideosList(context: Context): List<VideoEntity> = withContext(Dispatchers.IO) {
        val videoList = mutableListOf<VideoEntity>()

        try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_MODIFIED
            )

            val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"

            val query = context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )

            if (query == null) {
                Timber.w("MediaStore query returned null — content provider unavailable")
                return@withContext emptyList()
            }

            query.use { cursor ->
                // Validate column indices exist before iterating
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)

                Timber.d("MediaStore query returned %d rows", cursor.count)

                while (cursor.moveToNext()) {
                    try {
                        val path = cursor.getString(pathCol)
                        if (path.isNullOrBlank()) continue

                        val id = cursor.getLong(idCol)
                        if (id <= 0) {
                            Timber.w("Skipping entry with invalid ID: %d", id)
                            continue
                        }

                        // Build the content:// URI — this is the primary way to access
                        // the file on Android 10+. Raw file paths may not be accessible.
                        val contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            .buildUpon()
                            .appendPath(id.toString())
                            .build()
                            .toString()

                        val title = cursor.getString(nameCol)
                        val duration = cursor.getLong(durationCol).coerceAtLeast(0)
                        val size = cursor.getLong(sizeCol).coerceAtLeast(0)
                        val lastModified = cursor.getLong(modifiedCol) * 1000  // seconds → ms

                        // Skip entries with obviously invalid data
                        if (title.isNullOrBlank() && path.isBlank()) {
                            Timber.w("Skipping entry with no title and no path (id=%d)", id)
                            continue
                        }

                        videoList.add(
                            VideoEntity(
                                path = path,
                                title = title ?: path.substringAfterLast("/", "Unknown"),
                                duration = duration,
                                size = size,
                                lastModified = lastModified,
                                contentUri = contentUri
                            )
                        )
                    } catch (e: IllegalArgumentException) {
                        Timber.w(e, "Skipping video entry — invalid column data")
                    } catch (e: Exception) {
                        // Log and skip this individual entry — don't kill the whole scan
                        Timber.w(e, "Skipping video entry due to unexpected error")
                    }
                }
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Permission denied when querying MediaStore — user must grant READ_MEDIA_VIDEO")
        } catch (e: IllegalStateException) {
            Timber.e(e, "MediaStore cursor in invalid state — possibly closed prematurely")
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error scanning videos from MediaStore")
        }

        // Deduplicate by path (shouldn't happen, but defensive)
        val distinct = videoList.distinctBy { it.path }
        if (distinct.size < videoList.size) {
            Timber.w("Removed %d duplicate path entries from scan results", videoList.size - distinct.size)
        }

        Timber.i("Scan complete: %d videos found", distinct.size)
        distinct
    }
}
