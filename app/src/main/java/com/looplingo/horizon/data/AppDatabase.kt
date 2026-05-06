package com.looplingo.horizon.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.looplingo.horizon.data.dao.PlaybackRuleDao
import com.looplingo.horizon.data.dao.VideoDao
import com.looplingo.horizon.data.entity.PlaybackRuleEntity
import com.looplingo.horizon.data.entity.VideoEntity

@Database(entities = [VideoEntity::class, PlaybackRuleEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun playbackRuleDao(): PlaybackRuleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "looplingo-db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
