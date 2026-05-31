package com.looplingo.horizon.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import com.looplingo.horizon.ui.navigation.AppNavGraph
import com.looplingo.horizon.ui.theme.HorizonTheme
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            HorizonTheme {
                val navController = rememberNavController()
                AppNavGraph(navController = navController)
            }
        }

        Timber.d("MainActivity created with Compose Navigation")
    }
}
