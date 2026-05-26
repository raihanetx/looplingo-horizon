package com.looplingo.horizon.core

import android.content.Context
import android.provider.MediaStore
import com.looplingo.horizon.data.local.entity.VideoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileScanner @Inject constructor() {

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

        val distinct = videoList.distinctBy { it.path }
        if (distinct.size < videoList.size) {
            Timber.w("Removed %d duplicate path entries from video scan results", videoList.size - distinct.size)
        }
        distinct
    }

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

        val distinct = audioList.distinctBy { it.path }
        if (distinct.size < audioList.size) {
            Timber.w("Removed %d duplicate path entries from audio scan results", audioList.size - distinct.size)
        }
        distinct
    }
}
