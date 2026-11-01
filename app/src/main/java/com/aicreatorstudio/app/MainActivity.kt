package com.aicreatorstudio.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import com.aicreatorstudio.app.settings.SettingsViewModel
import com.aicreatorstudio.app.ui.navigation.AppNavGraph
import com.aicreatorstudio.app.ui.theme.AICreatorStudioTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single activity host for the whole app. Screens are added as Compose
 * destinations in [AppNavGraph] rather than as new Activities, which keeps
 * navigation, state, and theming consistent as more screens are added.
 *
 * The cold-start splash is provided by the system SplashScreen API:
 *   - The launcher activity theme (`Theme.AICreatorStudio.Launcher`)
 *     paints the deep dark background and the app logo *before* any
 *     code in this Activity has had a chance to run, so there is no
 *     white flash on cold start.
 *   - [installSplashScreen] wires up the framework's splash view and
 *     swaps in `Theme.AICreatorStudio` once Compose takes over.
 *   - The Compose-side [com.aicreatorstudio.app.ui.screens.splash.SplashScreen]
 *     then animates the logo, wordmark, and tagline before navigating
 *     to the home screen.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        // 2026-09-09 LANGUAGE FIX — wrap the base context with the
        // persisted in-app language (default PERSIAN) BEFORE any view
        // or Compose text resource is resolved. Changing the language
        // in Settings persists the tag and recreates this Activity.
        super.attachBaseContext(
            com.aicreatorstudio.app.settings.AppLanguage.applyToContext(newBase)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen must run before super.onCreate so the
        // framework can attach the splash view to the activity window.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // 2026-09-09 — seed the in-process string table with the
        // (possibly overridden) locale so com.t() resolves correctly
        // for this Activity instance.
        com.aicreatorstudio.app.settings.LocalizedStrings.seed(this)
        enableEdgeToEdge()
        val pendingCrash = CrashReporter.takePendingCrash(this)
        setContent {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val settings by settingsViewModel.settings.collectAsState()

            AICreatorStudioTheme(theme = settings.theme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    AppNavGraph(navController = navController)

                    if (pendingCrash != null) {
                        val context = LocalContext.current
                        var showCrashDialog by remember { mutableStateOf(true) }
                        if (showCrashDialog) {
                            AlertDialog(
                                onDismissRequest = { showCrashDialog = false },
                                title = { Text("گزارش آخرین کرش") },
                                text = {
                                    Text(
                                        text = pendingCrash,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier
                                            .height(300.dp)
                                            .verticalScroll(rememberScrollState())
                                    )
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, pendingCrash)
                                        }
                                        context.startActivity(
                                            Intent.createChooser(send, "اشتراک‌گذاری گزارش کرش")
                                        )
                                    }) {
                                        Text("اشتراک‌گذاری")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showCrashDialog = false }) {
                                        Text("بستن")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
