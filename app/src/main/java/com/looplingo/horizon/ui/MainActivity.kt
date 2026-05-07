package com.looplingo.horizon.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.looplingo.horizon.R
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

/**
 * Main activity hosting the NavHostFragment for Jetpack Navigation.
 *
 * This is a thin shell — all UI logic lives in [MainFragment] and
 * [PlaybackSettingsFragment]. Each fragment manages its own toolbar.
 * The activity only sets up the navigation host, splash screen, and
 * edge-to-edge display.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen before super.onCreate() for API 31+ compatibility
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display for Android 15+ compatibility.
        // This makes the app draw behind system bars (status bar, navigation bar),
        // creating a modern immersive look. The fragments use fitsSystemWindows="true"
        // to ensure content doesn't overlap with system bars.
        enableEdgeToEdge()

        setContentView(R.layout.activity_main)
        Timber.d("MainActivity created with NavHostFragment")
    }
}
