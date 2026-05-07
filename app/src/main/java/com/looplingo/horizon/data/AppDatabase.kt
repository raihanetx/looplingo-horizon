package com.looplingo.horizon.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.looplingo.horizon.data.dao.PlaybackRuleDao
import com.looplingo.horizon.data.dao.VideoDao
import com.looplingo.horizon.data.entity.PlaybackRuleEntity
import com.looplingo.horizon.data.entity.VideoEntity

/**
 * Room database for LoopLingo Horizon.
 *
 * Contains two entities:
 *  - [VideoEntity]: Cached video metadata from MediaStore scans
 *  - [PlaybackRuleEntity]: User-defined playback settings per video
 *
 * Schema is exported to `app/schemas/` for migration testing.
 * The database lifecycle is managed by Hilt (see [DatabaseModule]).
 *
 * Migration strategy:
 *  - Version 1→4: Destructive (early versions, no user data at risk)
 *  - Version 5+: Proper migrations must be implemented to preserve user data.
 *    Use `Room.databaseBuilder().addMigrations()` for future version bumps.
 */
@Database(
    entities = [VideoEntity::class, PlaybackRuleEntity::class],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun playbackRuleDao(): PlaybackRuleDao
}
