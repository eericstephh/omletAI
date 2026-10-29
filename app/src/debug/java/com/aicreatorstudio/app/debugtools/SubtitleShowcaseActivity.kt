package com.aicreatorstudio.app.debugtools

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aicreatorstudio.app.debugtools.showcase.SubtitleShowcaseScreen
import com.aicreatorstudio.app.settings.AppLanguage
import com.aicreatorstudio.app.settings.LocalizedStrings
import com.aicreatorstudio.app.ui.theme.AICreatorStudioTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * PHASE 5 — host activity for the debug subtitle showcase.
 *
 * ## Why a second Activity instead of a nav destination
 *
 * A route added to `AppNavGraph` would have to live in `src/main`, i.e. it
 * would ship in the release APK. This class and the screen it hosts live in
 * `src/debug`, so they are compiled into debug variants only — the showcase
 * is out of release builds *structurally*, with no runtime flag to forget.
 *
 * The activity is declared in `src/debug/AndroidManifest.xml` and is
 * deliberately absent from the launcher: it has no icon and no entry point
 * in the product UI.
 *
 * ## Launching it
 *
 * ```
 * adb install -r app/build/outputs/apk/debug/app-debug.apk
 * adb shell am start -n \
 *   com.aicreatorstudio.app.debug/com.aicreatorstudio.app.debugtools.SubtitleShowcaseActivity
 * ```
 *
 * The context is wrapped with the persisted app language, exactly as
 * `MainActivity` does, so the showcase runs under the same locale as the
 * product (Persian by default).
 */
@AndroidEntryPoint
class SubtitleShowcaseActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.applyToContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Same in-process string table seeding as MainActivity, so the
        // showcase resolves strings under the user's locale too.
        LocalizedStrings.seed(this)
        enableEdgeToEdge()
        setContent {
            AICreatorStudioTheme {
                SubtitleShowcaseScreen()
            }
        }
    }
}
