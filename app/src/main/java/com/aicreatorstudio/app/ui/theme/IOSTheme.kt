package com.aicreatorstudio.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ============================================================================
// iOS-style ColorScheme
// Maps Material 3 roles to the iOS HIG reference palette so any M3 component
// automatically picks up the Apple feel (e.g. TopAppBar surfaces, Switch
// tracks, ProgressIndicator tints, etc.).
// ============================================================================

private val IOSLightScheme = lightColorScheme(
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

    error                 = Color(0xFFFF3B30),
    onError               = Color.White,
    errorContainer        = Color(0xFFFFDAD6),
    onErrorContainer      = Color(0xFF410002),

    outline               = Color(0x993C3C43),  // iOS separator
    outlineVariant        = Color(0x4D3C3C43)   // iOS opaque separator
)

private val IOSDarkScheme = darkColorScheme(
    primary               = iOSBlue,
    onPrimary             = Color.White,
    primaryContainer      = Color(0xFF003366),
    onPrimaryContainer    = Color(0xFFD1E4FF),

    secondary             = iOSIndigo,
    onSecondary           = Color.White,
    secondaryContainer    = Color(0xFF1A0066),
    onSecondaryContainer  = Color(0xFFE3DFFF),

    tertiary              = iOSCyan,
    onTertiary            = Color.Black,
    tertiaryContainer     = Color(0xFF004D63),
    onTertiaryContainer   = Color(0xFFCAF5FF),

    background            = iOSBackground,
    onBackground          = LabelPrimary,

    surface               = iOSSurface,
    onSurface             = LabelPrimary,
    surfaceVariant        = iOSSurfaceVariant,
    onSurfaceVariant      = LabelSecondary,
    surfaceTint           = iOSBlue,

    error                 = iOSError,
    onError               = iOSOnError,
    errorContainer        = iOSErrorContainer,
    onErrorContainer      = Color(0xFFFFDAD6),

    outline               = Hairline,
    outlineVariant        = HairlineSubtle
)

@Composable
fun IOSTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) IOSDarkScheme else IOSLightScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = IOSTypography,
        shapes      = IOSShapes,
        content     = content
    )
}
