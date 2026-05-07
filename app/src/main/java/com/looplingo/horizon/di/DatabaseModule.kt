package com.looplingo.horizon.di

import android.content.Context
import androidx.room.Room
import com.looplingo.horizon.data.AppDatabase
import com.looplingo.horizon.data.dao.PlaybackRuleDao
import com.looplingo.horizon.data.dao.VideoDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that provides database-related dependencies.
 *
 * The [AppDatabase] is constructed here (not via a companion object singleton)
 * so that Hilt fully owns the lifecycle and can properly manage the singleton scope.
 *
 * All DAOs are extracted from the database instance, which is safe because
 * Room's DAO implementations are thread-safe and don't hold mutable state.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "looplingo-db"
        )
            // Destructive migration for legacy versions (1-3) only.
            // These early versions had no real users, so data loss is acceptable.
            // For version 5+, proper migrations MUST be implemented.
            .fallbackToDestructiveMigrationFrom(1, 2, 3)
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
}
