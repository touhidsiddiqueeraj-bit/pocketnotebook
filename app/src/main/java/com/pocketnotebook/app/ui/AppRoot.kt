package com.pocketnotebook.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pocketnotebook.app.ui.screens.ArtifactScreen
import com.pocketnotebook.app.ui.screens.DiagnosticsScreen
import com.pocketnotebook.app.ui.screens.HomeScreen
import com.pocketnotebook.app.ui.screens.ModelsScreen
import com.pocketnotebook.app.ui.screens.NotebookScreen
import com.pocketnotebook.app.ui.screens.OnboardingScreen
import com.pocketnotebook.app.ui.screens.SettingsScreen
import com.pocketnotebook.app.ui.screens.SourceViewerScreen

/** All routes in one place. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val NOTEBOOK = "notebook/{id}"
    const val SOURCE = "source/{id}?chunk={chunkId}"
    const val ARTIFACT = "artifact/{id}"
    const val MODELS = "models"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"

    fun notebook(id: Long) = "notebook/$id"
    fun source(id: Long, chunkId: Long = -1) = "source/$id?chunk=$chunkId"
    fun artifact(id: Long) = "artifact/$id"
}

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val container = rememberContainer()
    val start = if (!container.prefs.onboardDone) Routes.ONBOARDING else Routes.HOME

    NavHost(navController = nav, startDestination = start) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(nav = nav)
        }
        composable(Routes.HOME) {
            HomeScreen(nav = nav)
        }
        composable(
            Routes.NOTEBOOK,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            NotebookScreen(notebookId = id, nav = nav)
        }
        composable(
            Routes.SOURCE,
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("chunkId") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            ),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            val chunkId = entry.arguments?.getLong("chunkId") ?: -1L
            SourceViewerScreen(sourceId = id, highlightChunkId = chunkId, nav = nav)
        }
        composable(
            Routes.ARTIFACT,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            ArtifactScreen(artifactId = id, nav = nav)
        }
        composable(Routes.MODELS) { ModelsScreen(nav = nav) }
        composable(Routes.SETTINGS) { SettingsScreen(nav = nav) }
        composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(nav = nav) }
    }
}
