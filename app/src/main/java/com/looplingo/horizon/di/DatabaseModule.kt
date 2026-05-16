package com.looplingo.horizon.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.looplingo.horizon.data.AppDatabase
import com.looplingo.horizon.data.dao.LoopTemplateDao
import com.looplingo.horizon.data.dao.PlaybackRuleDao
import com.looplingo.horizon.data.dao.SavedTimestampDao
import com.looplingo.horizon.data.dao.TranscriptionDao
import com.looplingo.horizon.data.dao.VideoDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Migration from v7 → v8: Added LoopTemplateEntity and LoopTemplateRangeEntity
     * tables for the loop template system (dialogue_repeat and time_range templates).
     */
    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `loop_templates` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `videoPath` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `defaultLoopCount` INTEGER NOT NULL DEFAULT 3,
                    `createdAt` INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS `index_loop_templates_videoPath` ON `loop_templates` (`videoPath`)
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `loop_template_ranges` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `templateId` INTEGER NOT NULL,
                    `startMs` INTEGER NOT NULL,
                    `endMs` INTEGER NOT NULL,
                    `loopCount` INTEGER NOT NULL,
                    FOREIGN KEY (`templateId`) REFERENCES `loop_templates` (`id`) ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS `index_loop_template_ranges_templateId` ON `loop_template_ranges` (`templateId`)
            """.trimIndent())
            Timber.i("Migration 7→8: created loop_templates + loop_template_ranges tables")
        }
    }

    /**
     * Migration from v6 → v7: Added translatedText and translationLanguage
     * columns to TranscriptionEntity for storing LLM-translated text
     * (e.g., English → Bangla translation alongside the original transcription).
     */
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `transcriptions` ADD COLUMN `translatedText` TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE `transcriptions` ADD COLUMN `translationLanguage` TEXT DEFAULT NULL")
            Timber.i("Migration 6→7: added translatedText + translationLanguage columns")
        }
    }

    /**
     * Migration from v5 → v6: Added TranscriptionEntity table for
     * persisting Whisper transcription segments. This bridges the gap
     * between GroqApiClient's ephemeral Segment objects and the app's
     * subtitle playback system, eliminating the need to re-transcribe
     * files and enabling offline access to transcriptions.
     */
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `transcriptions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `videoPath` TEXT NOT NULL,
                    `text` TEXT NOT NULL,
                    `segmentStartMs` INTEGER NOT NULL,
                    `segmentEndMs` INTEGER NOT NULL,
                    `noSpeechProb` REAL NOT NULL DEFAULT 0.0,
                    `avgLogprob` REAL NOT NULL DEFAULT 0.0,
                    `languageCode` TEXT NOT NULL DEFAULT 'auto',
                    `isTranslation` INTEGER NOT NULL DEFAULT 0,
                    `createdAt` INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS `index_transcriptions_videoPath` ON `transcriptions` (`videoPath`)
            """.trimIndent())
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS `index_transcriptions_video_start` ON `transcriptions` (`videoPath`, `segmentStartMs`)
            """.trimIndent())
            Timber.i("Migration 5→6: created transcriptions table with indexes")
        }
    }

    /**
     * Migration from v4 → v5: Added SavedTimestampEntity table.
     * If the table already exists (e.g., from a fresh install), this is a no-op.
     */
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `saved_timestamps` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `videoPath` TEXT NOT NULL,
                    `label` TEXT NOT NULL,
                    `rangeStartMs` INTEGER NOT NULL,
                    `rangeEndMs` INTEGER NOT NULL,
                    `loopCount` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS `index_saved_timestamps_videoPath` ON `saved_timestamps` (`videoPath`)
            """.trimIndent())
            Timber.i("Migration 4→5: created saved_timestamps table")
        }
    }

    /**
     * Migration from v3 → v4: Schema was unchanged (version bump for code cleanup).
     * This is a safe no-op — no columns were added, removed, or modified.
     * If a user has v3 data, their data is fully preserved.
     */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // No schema changes between v3 and v4 — version bump only
            Timber.i("Migration 3→4: no schema changes (version bump only)")
        }
    }

    /**
     * Migration from v2 → v3: Schema was unchanged (version bump for code cleanup).
     * This is a safe no-op — no columns were added, removed, or modified.
     */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // No schema changes between v2 and v3 — version bump only
            Timber.i("Migration 2→3: no schema changes (version bump only)")
        }
    }

    /**
     * Migration from v1 → v2: Schema was unchanged (version bump for code cleanup).
     * This is a safe no-op — no columns were added, removed, or modified.
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // No schema changes between v1 and v2 — version bump only
            Timber.i("Migration 1→2: no schema changes (version bump only)")
        }
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "looplingo-db"
        )
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                MIGRATION_7_8
            )
            // REMOVED fallbackToDestructiveMigration() — it silently destroys user data
            // when an unmapped migration path is encountered. All migration paths from
            // v1→v8 are now explicitly defined. If a future version adds a new migration,
            // it MUST be added here explicitly. The app will crash on unknown versions
            // rather than silently losing data — this is intentional (fail-loudly principle).
            .build()
    }

    @Provides
    fun provideVideoDao(database: AppDatabase): VideoDao {
        return database.videoDao()
    }

    @Provides
    fun providePlaybackRuleDao(database: AppDatabase): PlaybackRuleDao {
        return database.playbackRuleDao()
    }

    @Provides
    fun provideSavedTimestampDao(database: AppDatabase): SavedTimestampDao {
        return database.savedTimestampDao()
    }

    @Provides
    fun provideTranscriptionDao(database: AppDatabase): TranscriptionDao {
        return database.transcriptionDao()
    }

    @Provides
    fun provideLoopTemplateDao(database: AppDatabase): LoopTemplateDao {
        return database.loopTemplateDao()
    }
}
