package com.looplingo.horizon.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.looplingo.horizon.ui.home.HomeScreen
import com.looplingo.horizon.ui.player.PlayerScreen

object Routes {
    const val HOME = "home"
    const val PLAYER = "player/{videoPath}/{videoTitle}"

    fun player(videoPath: String, videoTitle: String): String {
        return "player/${videoPath}/${videoTitle}"
    }
}

@Composable
fun AppNavGraph(
    navController: NavHostController,
    startDestination: String = Routes.HOME
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onVideoClick = { video ->
                    navController.navigate(
                        Routes.player(video.path, video.title)
                    )
                }
            )
        }

        composable(
            route = Routes.PLAYER,
            arguments = listOf(
                navArgument("videoPath") { type = NavType.StringType },
                navArgument("videoTitle") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val videoPath = backStackEntry.arguments?.getString("videoPath") ?: ""
            val videoTitle = backStackEntry.arguments?.getString("videoTitle") ?: ""
            PlayerScreen(
                videoPath = videoPath,
                videoTitle = videoTitle,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
