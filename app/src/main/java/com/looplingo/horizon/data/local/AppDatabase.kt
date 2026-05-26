package com.looplingo.horizon.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.looplingo.horizon.data.local.dao.LoopTemplateDao
import com.looplingo.horizon.data.local.dao.PlaybackRuleDao
import com.looplingo.horizon.data.local.dao.SavedTimestampDao
import com.looplingo.horizon.data.local.dao.TranscriptionDao
import com.looplingo.horizon.data.local.dao.VideoDao
import com.looplingo.horizon.data.local.entity.LoopTemplateEntity
import com.looplingo.horizon.data.local.entity.LoopTemplateRangeEntity
import com.looplingo.horizon.data.local.entity.PlaybackRuleEntity
import com.looplingo.horizon.data.local.entity.SavedTimestampEntity
import com.looplingo.horizon.data.local.entity.TranscriptionEntity
import com.looplingo.horizon.data.local.entity.VideoEntity

@Database(
    entities = [
        VideoEntity::class,
        PlaybackRuleEntity::class,
        SavedTimestampEntity::class,
        TranscriptionEntity::class,
        LoopTemplateEntity::class,
        LoopTemplateRangeEntity::class
    ],
    version = 9,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun playbackRuleDao(): PlaybackRuleDao
    abstract fun savedTimestampDao(): SavedTimestampDao
    abstract fun transcriptionDao(): TranscriptionDao
    abstract fun loopTemplateDao(): LoopTemplateDao
}
