package com.aicreatorstudio.app.ui.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.aicreatorstudio.app.settings.ThemeMode

// ============================================================================
// Main theme entry point
//
// "Dark Studio" is the product's primary identity: the editor opens in the
// premium dark look users expect from a video tool. The light scheme is a
// faithful fallback for users who force it in system settings.
//
// Motion: components should read [LocalMotionEnabled] and skip decorative or
// infinite animation when it is false (system animator duration scale = 0).
// State changes must still be perceivable, so this only gates *decoration*,
// never feedback that carries meaning on its own.
// ============================================================================

/** True when the platform allows animation. Mirrors Android's remove-animations setting. */
val LocalMotionEnabled = staticCompositionLocalOf { true }

private val DarkColorScheme = darkColorScheme(
    primary               = AccentPrimary,
    onPrimary             = Color.White,
    primaryContainer      = Color(0xFF241A4A),
    onPrimaryContainer    = AccentBright,

    secondary             = AccentSecondary,
    onSecondary           = Color.White,
    secondaryContainer    = Color(0xFF241A4A),
    onSecondaryContainer  = Color(0xFFE3DFFF),

    tertiary              = AccentCyan,
    onTertiary            = Color.Black,
    tertiaryContainer     = Color(0xFF0A3A4A),
    onTertiaryContainer   = Color(0xFFCAF5FF),

    background            = CinematicBlack,
    onBackground          = LabelPrimary,

    surface               = CinematicSurface,
    onSurface             = LabelPrimary,
    surfaceVariant        = CinematicElevated,
    onSurfaceVariant      = LabelSecondary,
    surfaceTint           = AccentPrimary,

    error                 = StatusError,
    onError               = Color(0xFF3B0A0A),
    errorContainer        = StatusErrorContainer,
    onErrorContainer      = Color(0xFFFFDAD6),

    outline               = BorderSubtle,
    outlineVariant        = Color(0xFF232A36)
)

private val LightColorScheme = lightColorScheme(
    primary               = iOSBlue,
    onPrimary             = Color.White,
    primaryContainer      = Color(0xFFD1E4FF),
    onPrimaryContainer    = Color(0xFF001A41),

    secondary             = iOSIndigo,
    onSecondary           = Color.White,
    secondaryContainer    = Color(0xFFE3DFFF),
    onSecondaryContainer  = Color(0xFF1A0066),

    tertiary              = iOSCyan,
    onTertiary            = Color.Black,
    tertiaryContainer     = Color(0xFFCAF5FF),
    onTertiaryContainer   = Color(0xFF001F29),

    background            = iOSLightBackground,
    onBackground          = iOSLightLabelPrimary,

    surface               = iOSLightSurface,
    onSurface             = iOSLightLabelPrimary,
    surfaceVariant        = iOSLightSurfaceHigh,
    onSurfaceVariant      = iOSLightLabelSecondary,
    surfaceTint           = iOSBlue,

    error                 = Color(0xFFBA1A1A),
    onError               = Color.White,
    errorContainer        = Color(0xFFFFDAD6),
    onErrorContainer      = Color(0xFF410002),

    outline               = Color(0x993C3C43),
    outlineVariant        = Color(0x4D3C3C43)
)

/**
 * Reads the platform "animator duration scale". 0f means the user asked the
 * system to remove animations; decorative motion is then suppressed while
 * meaningful state feedback stays intact.
 */
@Composable
private fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) != 0f
        }.getOrDefault(true)
    }
}

@Composable
fun AICreatorStudioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val motionEnabled = rememberMotionEnabled()

    CompositionLocalProvider(LocalMotionEnabled provides motionEnabled) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = Typography,
            shapes      = AppShapes,
            content     = content
        )
    }
}

@Composable
fun AICreatorStudioTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.DARK  -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    AICreatorStudioTheme(darkTheme = isDark, content = content)
}
