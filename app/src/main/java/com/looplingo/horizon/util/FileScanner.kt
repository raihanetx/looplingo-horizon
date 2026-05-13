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
 * Scans the device for media files (video and audio) using MediaStore.
 *
 * Implemented as an injectable singleton so it can be mocked in tests.
 * The [scanVideosList] method queries both MediaStore Video and Audio tables
 * and returns a list of [VideoEntity] objects representing all media files
 * found on the device.
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

    /**
     * Scans both video and audio files from MediaStore and returns a merged,
     * deduplicated list. Kept as [scanVideosList] for backward compatibility.
     */
    suspend fun scanVideosList(context: Context): List<VideoEntity> = withContext(Dispatchers.IO) {
        val videoList = scanVideoFiles(context)
        val audioList = scanAudioFiles(context)
        val merged = (videoList + audioList).distinctBy { it.path }
        if (merged.size < videoList.size + audioList.size) {
            Timber.w("Removed %d duplicate path entries from merged scan results",
                videoList.size + audioList.size - merged.size)
        }
        Timber.i("Scan complete: %d media files found (%d video + %d audio, merged & deduped)",
            merged.size, videoList.size, audioList.size)
        merged
    }

    /**
     * Scan video files from MediaStore.Video.
     */
    private suspend fun scanVideoFiles(context: Context): List<VideoEntity> = withContext(Dispatchers.IO) {
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
                Timber.w("MediaStore Video query returned null — content provider unavailable")
                return@withContext emptyList()
            }

            query.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)

                Timber.d("MediaStore Video query returned %d rows", cursor.count)

                while (cursor.moveToNext()) {
                    try {
                        val path = cursor.getString(pathCol)
                        if (path.isNullOrBlank()) continue

                        val id = cursor.getLong(idCol)
                        if (id <= 0) {
                            Timber.w("Skipping video entry with invalid ID: %d", id)
                            continue
                        }

                        val contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            .buildUpon()
                            .appendPath(id.toString())
                            .build()
                            .toString()

                        val title = cursor.getString(nameCol)
                        val duration = cursor.getLong(durationCol).coerceAtLeast(0)
                        val size = cursor.getLong(sizeCol).coerceAtLeast(0)
                        val lastModified = cursor.getLong(modifiedCol) * 1000

                        if (title.isNullOrBlank() && path.isBlank()) {
                            Timber.w("Skipping video entry with no title and no path (id=%d)", id)
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
                        Timber.w(e, "Skipping video entry due to unexpected error")
                    }
                }
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Permission denied when querying MediaStore Video — user must grant READ_MEDIA_VIDEO")
        } catch (e: IllegalStateException) {
            Timber.e(e, "MediaStore Video cursor in invalid state — possibly closed prematurely")
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error scanning videos from MediaStore")
        }

        // Deduplicate by path (defensive)
        val distinct = videoList.distinctBy { it.path }
        if (distinct.size < videoList.size) {
            Timber.w("Removed %d duplicate path entries from video scan results", videoList.size - distinct.size)
        }
        distinct
    }

    /**
     * Scan audio-only files (MP3, M4A, FLAC, OGG, WAV, etc.) from MediaStore.Audio.
     * Returns them as [VideoEntity] objects for unified handling in the app.
     */
    private suspend fun scanAudioFiles(context: Context): List<VideoEntity> = withContext(Dispatchers.IO) {
        val audioList = mutableListOf<VideoEntity>()

        try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_MODIFIED
            )

            val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"

            val query = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )

            if (query == null) {
                Timber.w("MediaStore Audio query returned null — content provider unavailable")
                return@withContext emptyList()
            }

            query.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)

                Timber.d("MediaStore Audio query returned %d rows", cursor.count)

                while (cursor.moveToNext()) {
                    try {
                        val path = cursor.getString(pathCol)
                        if (path.isNullOrBlank()) continue

                        val id = cursor.getLong(idCol)
                        if (id <= 0) {
                            Timber.w("Skipping audio entry with invalid ID: %d", id)
                            continue
                        }

                        val contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                            .buildUpon()
                            .appendPath(id.toString())
                            .build()
                            .toString()

                        val title = cursor.getString(nameCol)
                        val duration = cursor.getLong(durationCol).coerceAtLeast(0)
                        val size = cursor.getLong(sizeCol).coerceAtLeast(0)
                        val lastModified = cursor.getLong(modifiedCol) * 1000

                        if (title.isNullOrBlank() && path.isBlank()) {
                            Timber.w("Skipping audio entry with no title and no path (id=%d)", id)
                            continue
                        }

                        audioList.add(
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
                        Timber.w(e, "Skipping audio entry — invalid column data")
                    } catch (e: Exception) {
                        Timber.w(e, "Skipping audio entry due to unexpected error")
                    }
                }
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Permission denied when querying MediaStore Audio — user must grant READ_MEDIA_AUDIO")
        } catch (e: IllegalStateException) {
            Timber.e(e, "MediaStore Audio cursor in invalid state — possibly closed prematurely")
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error scanning audio from MediaStore")
        }

        // Deduplicate by path (defensive)
        val distinct = audioList.distinctBy { it.path }
        if (distinct.size < audioList.size) {
            Timber.w("Removed %d duplicate path entries from audio scan results", audioList.size - distinct.size)
        }
        distinct
    }
}
