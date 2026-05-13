package com.looplingo.horizon

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * Application class for LoopLingo Horizon.
 *
 * Annotated with @HiltAndroidApp to trigger Hilt's code generation
 * and set up the application-level dependency container.
 *
 * Initializes:
 *  - Timber logging (DebugTree in debug builds, ProductionCrashTree in release)
 */
@HiltAndroidApp
class HorizonApp : Application() {

    override fun onCreate() {
        super.onCreate()
        setupLogging()
        // Clean up old temp files from transcription cache
        com.looplingo.horizon.api.GroqApiClient.cleanupTempFiles(this)
        if (BuildConfig.DEBUG) {
            Timber.i("LoopLingo Horizon application initialized")
        }
    }

    /**
     * Configures Timber logging:
     *  - Debug builds: Verbose logging to Logcat with thread info
     *  - Release builds: Warnings and errors logged to Logcat for crash diagnosis
     */
    private fun setupLogging() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Timber.d("Timber DebugTree planted — debug logging active")
        } else {
            // Production: plant a tree that logs warnings/errors to Logcat
            // (ProGuard strips d/v/i but keeps w/e)
            Timber.plant(ProductionCrashTree())
        }
    }

    /**
     * Production logging tree — only logs warnings and errors.
     * Debug/verbose/info logs are stripped by ProGuard rules.
     */
    private class ProductionCrashTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority >= Log.WARN) {
                // In production, just log to Android Logcat for crash reporting
                // ProGuard already strips Timber.d/v/i calls
                if (t != null) {
                    Log.println(priority, tag ?: "LoopLingo", "$message\n${Log.getStackTraceString(t)}")
                } else {
                    Log.println(priority, tag ?: "LoopLingo", message)
                }
            }
        }
    }
}
