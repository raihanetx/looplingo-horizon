package com.looplingo.horizon.util

import android.content.Context
import android.provider.MediaStore
import com.looplingo.horizon.data.entity.VideoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object FileScanner {
    suspend fun scanVideos(context: Context): List<VideoEntity> = withContext(Dispatchers.IO) {
        val videoList = mutableListOf<VideoEntity>()
        val projection = arrayOf(
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED
        )

        val query = context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
        )

        query?.use { cursor ->
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val path = cursor.getString(pathCol)
                if (File(path).exists()) {
                    videoList.add(
                        VideoEntity(
                            path = path,
                            title = cursor.getString(nameCol),
                            duration = cursor.getLong(durationCol),
                            size = cursor.getLong(sizeCol),
                            lastModified = cursor.getLong(modifiedCol) * 1000
                        )
                    )
                }
            }
        }
        videoList.distinctBy { it.path }
    }
}
