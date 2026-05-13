package com.looplingo.horizon.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt module for providing repository instances.
 *
 * All repositories now use @Inject constructor with @Singleton,
 * so Hilt can create them automatically. Their dependencies
 * (VideoDao, FileScanner, @ApplicationContext Context,
 * SubtitleScanner, TranscriptionDao, PlaybackRuleDao) are all
 * already provided by other Hilt modules (DatabaseModule, or
 * @Inject constructor on FileScanner/SubtitleScanner).
 *
 * This module is kept empty for future use if non-injectable
 * dependencies need to be provided.
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule
