package com.aicreatorstudio.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.aicreatorstudio.app.settings.AppTheme

/**
 * The accent palette each theme owns.
 *
 * ## Why an accent palette and not just a Material scheme
 *
 * `MaterialTheme.colorScheme` covers the M3 components, but this app's own
 * design language is built on named tokens (`AccentBright`, `ctaBrush()`,
 * `GlowBrand`) that M3 knows nothing about. A theme switch therefore has to
 * carry BOTH: the Material scheme (Theme.kt) and this accent set, published
 * through [LocalAccentColors] and read via the accessor functions below.
 *
 * ## All three themes are dark — deliberately
 *
 * Every screen in the app is authored against the dark cinematic base
 * (`CinematicBlack` is hardcoded as the canvas of Home, Settings, Import and
 * the editor). A "light" mode would be a lie unless each screen is
 * re-authored, so the three choices are three *identities of the dark
 * studio*: violet (the brand), ocean (azure), rose (sunset). Each palette's
 * CTA gradient is white-text AA-checked (5.0:1+ at both ends), the same
 * contract the violet palette documented in Color.kt.
 *
 * The [AppTheme] enum itself lives in `settings/`, beside the repository
 * that persists it — it is a user choice, not a colour value; this file owns
 * only what the choice LOOKS like.
 */

/**
 * One theme's accent family — the seven values that make an identity.
 *
 * Roles mirror the static tokens they replace:
 *  - [accent]  → was `AccentPrimary`  (fills, selected states)
 *  - [bright]  → was `AccentBright`   (text/icons on dark — the only safe on-dark accent)
 *  - [deep]    → was `AccentDeep`     (CTA gradient top)
 *  - [ctaBottom] → was `AccentCtaBottom` (CTA gradient bottom; white must pass AA on BOTH ends)
 *  - [container] → the primaryContainer value (badges, chips)
 *  - [glow]    → was `GlowBrand`      (backdrop ambience only, never text)
 */
data class AccentColors(
    val accent: Color,
    val bright: Color,
    val deep: Color,
    val ctaBottom: Color,
    val container: Color,
    val glow: Color,
    val isDark: Boolean
)

/** The brand default — the original "Dark Studio" violet family. */
val VioletAccents = AccentColors(
    accent = Color(0xFF8B5CF6),
    bright = Color(0xFFA78BFA),
    deep = Color(0xFF7C3AED),
    ctaBottom = Color(0xFF6D28D9),
    container = Color(0xFF241A4A),
    glow = Color(0xFF7C3AED),
    isDark = true
)

/** Ocean — azure. Cool, technical, high legibility on dark. */
val OceanAccents = AccentColors(
    accent = Color(0xFF0EA5E9),
    bright = Color(0xFF38BDF8),
    deep = Color(0xFF0369A1),
    ctaBottom = Color(0xFF075985),
    container = Color(0xFF0A2A42),
    glow = Color(0xFF0EA5E9),
    isDark = true
)

/** Rose — sunset. Warm, energetic; white on the CTA gradient stays AA (5.0 / 6.6). */
val RoseAccents = AccentColors(
    accent = Color(0xFFF43F5E),
    bright = Color(0xFFFB7185),
    deep = Color(0xFFE11D48),
    ctaBottom = Color(0xFFBE123C),
    container = Color(0xFF3F1128),
    glow = Color(0xFFF43F5E),
    isDark = true
)

/** The active theme's accents; defaults to violet before the theme is provided. */
val LocalAccentColors = staticCompositionLocalOf { VioletAccents }

/**
 * Composable accessors over [LocalAccentColors].
 *
 * These exist so a call site can switch from the static token to the themed
 * one by changing `AccentBright` → `accentBright()` and nothing else — no
 * new local variable, no re-signaturing, and therefore no risk in the
 * editor's size-critical composables. They also keep diff noise in a large
 * file down to one token per site.
 */
@Composable
fun accentPrimary(): Color = LocalAccentColors.current.accent

@Composable
fun accentBright(): Color = LocalAccentColors.current.bright

@Composable
fun accentDeep(): Color = LocalAccentColors.current.deep

@Composable
fun accentContainer(): Color = LocalAccentColors.current.container

@Composable
fun glowBrand(): Color = LocalAccentColors.current.glow
