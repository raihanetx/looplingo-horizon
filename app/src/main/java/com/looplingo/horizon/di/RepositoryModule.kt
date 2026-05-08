package com.looplingo.horizon.di

import android.content.Context
import com.looplingo.horizon.data.dao.PlaybackRuleDao
import com.looplingo.horizon.data.dao.SavedTimestampDao
import com.looplingo.horizon.data.dao.VideoDao
import com.looplingo.horizon.repository.PlaybackRepository
import com.looplingo.horizon.repository.TranscriptRepository
import com.looplingo.horizon.repository.VideoRepository
import com.looplingo.horizon.util.FileScanner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideVideoRepository(
        videoDao: VideoDao,
        fileScanner: FileScanner,
        @ApplicationContext context: Context
    ): VideoRepository {
        return VideoRepository(videoDao, fileScanner, context)
    }

    @Provides
    @Singleton
    fun providePlaybackRepository(
        playbackRuleDao: PlaybackRuleDao
    ): PlaybackRepository {
        return PlaybackRepository(playbackRuleDao)
    }

    @Provides
    @Singleton
    fun provideTranscriptRepository(
        subtitleScanner: com.looplingo.horizon.util.SubtitleScanner
    ): TranscriptRepository {
        return TranscriptRepository(subtitleScanner)
    }
}
