package com.aicreatorstudio.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.aicreatorstudio.app.ui.components.ComingSoonScreen
import com.aicreatorstudio.app.ui.components.t
import com.aicreatorstudio.app.ui.screens.editor.EditorScreen
import com.aicreatorstudio.app.ui.screens.home.HomeScreen
import com.aicreatorstudio.app.ui.screens.importvideo.ImportVideoScreen
import com.aicreatorstudio.app.ui.screens.settings.SettingsScreen
import com.aicreatorstudio.app.ui.screens.splash.SplashScreen

/**
 * Root navigation graph.
 *
 * The splash is the new [startDestination]. After its hold duration it
 * calls [SplashScreen]'s `onFinished`, which navigates to HOME and
 * pops the splash off the back stack so pressing back from HOME exits
 * the app rather than returning to the splash.
 *
 * Home and Import Video are fully built. [AppDestinations.NEW_PROJECT],
 * [AppDestinations.SETTINGS], and [AppDestinations.PROJECT_DETAIL] are
 * wired up now as [ComingSoonScreen] placeholders so navigation is
 * complete ahead of their real implementation — building those screens
 * later means adding a composable body here, not restructuring the graph.
 */
@Composable
fun AppNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = AppDestinations.SPLASH
    ) {
        composable(AppDestinations.SPLASH) {
            SplashScreen(
                onFinished = {
                    navController.navigate(AppDestinations.HOME) {
                        popUpTo(AppDestinations.SPLASH) { inclusive = true }
                    }
                }
            )
        }

        composable(AppDestinations.HOME) {
            HomeScreen(
                onCreateProjectClick = { navController.navigate(AppDestinations.IMPORT_VIDEO) },
                onImportVideoClick = { navController.navigate(AppDestinations.IMPORT_VIDEO) },
                onSettingsClick = { navController.navigate(AppDestinations.SETTINGS) },
                onProjectClick = { projectId ->
                    navController.navigate(AppDestinations.projectDetailRoute(projectId))
                },
                // 2026-10-20 — a capability tile opens the matching tool
                // in the editor. The screen decides WHICH project (it
                // owns the list); the graph only knows how to navigate.
                onOpenAiTool = { projectId, capability ->
                    navController.navigate(
                        AppDestinations.projectDetailToolRoute(projectId, capability.panel.name)
                    )
                }
            )
        }

        composable(AppDestinations.IMPORT_VIDEO) {
            ImportVideoScreen(
                onBackClick = { navController.popBackStack() },
                onProjectSaved = { projectId ->
                    navController.navigate(AppDestinations.projectDetailRoute(projectId)) {
                        popUpTo(AppDestinations.IMPORT_VIDEO) { inclusive = true }
                    }
                }
            )
        }

        composable(AppDestinations.NEW_PROJECT) {
            ComingSoonScreen(
                title = t("پروژه جدید"),
                icon = Icons.Filled.Add,
                message = t("ابزار ساخت پروژه به‌زودی اضافه می‌شود."),
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(AppDestinations.SETTINGS) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(
            route = AppDestinations.PROJECT_DETAIL,
            arguments = listOf(navArgument(AppDestinations.PROJECT_ID_ARG) { type = NavType.StringType })
        ) {
            EditorDestination(onBackClick = { navController.popBackStack() })
        }

        // 2026-10-20 — the same editor, opened straight into an AI tool
        // (the home screen's capability tiles use this). The tool is a
        // PATH argument: Navigation only fills query arguments when the
        // route pattern itself declares a query string, so a query
        // argument here would silently never arrive.
        composable(
            route = AppDestinations.PROJECT_DETAIL_TOOL,
            arguments = listOf(
                navArgument(AppDestinations.PROJECT_ID_ARG) { type = NavType.StringType },
                navArgument(AppDestinations.TOOL_ARG) { type = NavType.StringType }
            )
        ) { entry ->
            EditorDestination(
                onBackClick = { navController.popBackStack() },
                initialTool = entry.arguments?.getString(AppDestinations.TOOL_ARG)
            )
        }
    }
}

/**
 * The editor destination, shared by the plain route and the
 * tool-deep-link route.
 */
@Composable
private fun EditorDestination(
    onBackClick: () -> Unit,
    initialTool: String? = null
) {
    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Ltr
    ) {
        EditorScreen(
            onBackClick = onBackClick,
            initialTool = initialTool
        )
    }
}
