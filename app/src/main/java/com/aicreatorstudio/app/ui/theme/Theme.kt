package com.aicreatorstudio.app.ui.theme

import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.aicreatorstudio.app.settings.AppTheme

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

private fun darkScheme(accents: AccentColors) = darkColorScheme(
    primary               = accents.accent,
    onPrimary             = Color.White,
    primaryContainer      = accents.container,
    onPrimaryContainer    = accents.bright,

    secondary             = accents.accent,
    onSecondary           = Color.White,
    secondaryContainer    = accents.container,
    onSecondaryContainer  = accents.bright,

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
    surfaceTint           = accents.accent,

    error                 = StatusError,
    onError               = Color(0xFF3B0A0A),
    errorContainer        = StatusErrorContainer,
    onErrorContainer      = Color(0xFFFFDAD6),

    outline               = BorderSubtle,
    outlineVariant        = Color(0xFF232A36)
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

/**
 * 2026-11-01 — the theme parameter is now an [AppTheme] (one of three
 * selectable identities), not a dark/light/system switch. All three are
 * dark-authored — see the KDoc on [AppTheme] for why light was retired —
 * so one dark scheme shape is parameterised by the theme's accent family,
 * and the same family is published through [LocalAccentColors] for the
 * app's own tokens.
 */
@Composable
fun AICreatorStudioTheme(
    theme: AppTheme = AppTheme.VIOLET_NIGHT,
    content: @Composable () -> Unit
) {
    val accents = when (theme) {
        AppTheme.VIOLET_NIGHT -> VioletAccents
        AppTheme.OCEAN -> OceanAccents
        AppTheme.ROSE -> RoseAccents
    }
    val motionEnabled = rememberMotionEnabled()

    CompositionLocalProvider(
        LocalMotionEnabled provides motionEnabled,
        LocalAccentColors provides accents
    ) {
        MaterialTheme(
            colorScheme = darkScheme(accents),
            typography  = Typography,
            shapes      = AppShapes,
            content     = content
        )
    }
}
