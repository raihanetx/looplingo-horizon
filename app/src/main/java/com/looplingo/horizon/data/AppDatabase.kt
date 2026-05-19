package com.looplingo.horizon.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.looplingo.horizon.data.dao.LoopTemplateDao
import com.looplingo.horizon.data.dao.PlaybackRuleDao
import com.looplingo.horizon.data.dao.SavedTimestampDao
import com.looplingo.horizon.data.dao.TranscriptionDao
import com.looplingo.horizon.data.dao.VideoDao
import com.looplingo.horizon.data.entity.LoopTemplateEntity
import com.looplingo.horizon.data.entity.LoopTemplateRangeEntity
import com.looplingo.horizon.data.entity.PlaybackRuleEntity
import com.looplingo.horizon.data.entity.SavedTimestampEntity
import com.looplingo.horizon.data.entity.TranscriptionEntity
import com.looplingo.horizon.data.entity.VideoEntity

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
