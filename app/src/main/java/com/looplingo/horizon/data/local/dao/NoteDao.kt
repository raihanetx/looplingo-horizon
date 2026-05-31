package com.looplingo.horizon.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.looplingo.horizon.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE videoPath = :videoPath ORDER BY createdAt DESC")
    fun getNotesForVideo(videoPath: String): Flow<List<NoteEntity>>

    @Insert
    suspend fun insertNote(note: NoteEntity): Long

    @Delete
    suspend fun deleteNote(note: NoteEntity)

    @Query("DELETE FROM notes WHERE videoPath = :videoPath")
    suspend fun deleteAllForVideo(videoPath: String)
}
