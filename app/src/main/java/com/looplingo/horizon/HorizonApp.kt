package com.looplingo.horizon

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * Application class for LoopLingo Horizon.
 *
 * Annotated with @HiltAndroidApp to trigger Hilt's code generation
 * and set up the application-level dependency container.
 *
 * Initializes:
 *  - Timber logging (DebugTree in debug builds, no-op in release)
 */
@HiltAndroidApp
class HorizonApp : Application() {

    override fun onCreate() {
        super.onCreate()
        setupLogging()
        Timber.i("LoopLingo Horizon application initialized")
    }

    /**
     * Configures Timber logging:
     *  - Debug builds: Verbose logging to Logcat with thread info
     *  - Release builds: No logging (could be extended to log to crashlytics/file)
     */
    private fun setupLogging() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Timber.d("Timber DebugTree planted — debug logging active")
        }
        // In a production app, you would plant a CrashlyticsTree or FileTree here:
        // else {
        //     Timber.plant(CrashlyticsTree())
        // }
    }
}
