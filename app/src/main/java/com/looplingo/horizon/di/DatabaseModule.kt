package com.looplingo.horizon.di

import android.content.Context
import androidx.room.Room
import com.looplingo.horizon.data.AppDatabase
import com.looplingo.horizon.data.dao.PlaybackRuleDao
import com.looplingo.horizon.data.dao.SavedTimestampDao
import com.looplingo.horizon.data.dao.VideoDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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
            .fallbackToDestructiveMigrationFrom(1, 2, 3, 4)
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
}
