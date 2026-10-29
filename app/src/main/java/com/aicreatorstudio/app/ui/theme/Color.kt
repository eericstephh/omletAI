package com.aicreatorstudio.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// --- CTA gradient: the ONLY gradient allowed under white text ---
// White passes AA at both ends (5.70 / 7.10). Never put white text on
// AccentBright (#A78BFA, 2.72:1) — use it for text/icons on dark instead.
fun ctaBrush(): Brush = Brush.verticalGradient(listOf(AccentDeep, AccentCtaBottom))

// ============================================================================
// "Dark Studio" — Omelet design tokens (2026-10 refinement).
//
// Direction: cinematic, precise, calm. A near-neutral charcoal-ink base with
// only a faint cool tint, one violet brand family, and restrained cyan for
// genuine informational accents. Decoration is carried by spacing, hairlines
// and typography — not by glow.
//
// Every value below was contrast-checked against the three surfaces
// (AppBackground / SurfacePrimary / SurfaceElevated) at 2026-10. Measured
// WCAG ratios, worst case on the LIGHTEST surface (Elevated):
//   TextPrimary   #F5F7FB  15.2:1
//   TextSecondary #B0B8C8   8.2:1
//   TextMuted     #8993A5   5.3:1
//   AccentBright  #A78BFA   6.0:1
//   AccentCyan    #22D3EE   (info only — 9.4:1 on Elevated)
//   StatusError   #F87171   6.4:1
//   StatusSuccess #34D399   8.9:1
//   StatusWarning #FBBF24  10.3:1
// Rules:
//   - White text ONLY on the CTA gradient (AccentDeep→AccentCtaBottom) and on
//     StatusError's deep variant. Never on AccentBright.
//   - LabelMuted (TextMuted) is the floor for body-ish text; LabelFaint is
//     decorative only and must never carry meaning.
//   - Prefer BorderSubtle (solid) for panel/card edges; Hairline (translucent
//     white) is reserved for edges that sit over video or glass.
// ============================================================================

// --- Core surfaces (charcoal-ink, faintly cool) ---
val CinematicBlack    = Color(0xFF0B0D12)  // App background
val CinematicSurface  = Color(0xFF141821)  // Panels, cards, toolbars
val CinematicElevated = Color(0xFF1B202B)  // Elevated surfaces
val CinematicHighest  = Color(0xFF242B38)  // Highest elevation

/** Recessed wells: timeline troughs, empty slots, video letterbox. */
val SurfaceSunken     = Color(0xFF08090D)
/** Inset content areas sitting one step below a card. */
val SurfaceInset      = Color(0xFF10141C)

// --- Borders ---
// Solid, low-key edges for panels and cards (the brief's #2B3240).
val BorderSubtle      = Color(0xFF2B3240)
val BorderStrong      = Color(0xFF3A4354)
// Translucent white edges — ONLY over video / glass, never on flat panels.
val Hairline          = Color(0x1FFFFFFF)  // rgba(255,255,255,0.12) — 1dp glass edge
val HairlineSubtle    = Color(0x12FFFFFF)  // rgba(255,255,255,0.07)

// --- Glass / translucent layers ---
// Apple-subtle: one real backdrop-blur per screen max (top bars / sheets via
// GlassChrome); everything else is a static translucent tint + hairline +
// top highlight. Never stack blurs.
val GlassUltraThin    = Color(0xB8141821)  // ~72% — faint scrim
val GlassThin         = Color(0xC4141821)  // ~77% — compact controls
val GlassRegular      = Color(0xD9141821)  // ~85% — toolbars, sheets
val GlassThick        = Color(0xE6141821)  // ~90% — prominent overlays

// --- Text colors ---
val LabelPrimary      = Color(0xFFF5F7FB)  // Near-white — titles, body
val LabelSecondary    = Color(0xFFB0B8C8)  // Supporting copy — 8.2:1 on Elevated
val LabelTertiary     = Color(0xFF8993A5)  // Muted — 5.3:1 on Elevated
val LabelQuaternary   = Color(0xFF5B6472)  // Faintest — decorative only, never body text
val LabelToolbar      = Color(0xFFE2E8F0)  // Toolbar/chip labels

// Semantic aliases for the text ramp (prefer these in new code).
val TextPrimary       = LabelPrimary
val TextSecondary     = LabelSecondary
val TextMuted         = LabelTertiary
val TextFaint         = LabelQuaternary
val TextDisabled      = Color(0xFF5B6472)

// --- Accent / brand colors (violet duotone + restrained cyan) ---
val AccentPrimary     = Color(0xFF8B5CF6)  // #8B5CF6 neon indigo (brand base · fills)
val AccentBright      = Color(0xFFA78BFA)  // Primary accent — text/icons on dark only
val AccentSecondary   = Color(0xFF8B7CFF)  // Secondary accent (brief) — 5.4:1 on Elevated
val AccentDeep        = Color(0xFF7C3AED)  // Deep violet — top of the CTA gradient
val AccentCtaBottom   = Color(0xFF6D28D9)  // CTA gradient bottom — white on it = 7.1:1
val AccentGlowStart   = Color(0xFF6366F1)  // Gradient start (decorative glows)
val AccentGradientEnd = Color(0xFF8B5CF6)  // Gradient end (decorative glows)
val AccentCyan        = Color(0xFF22D3EE)  // Informational accent — 9.4:1 on Elevated

// --- Status / semantic feedback ---
val StatusSuccess       = Color(0xFF34D399)
val StatusSuccessDeep   = Color(0xFF0E8A63)  // Filled success with white text
val StatusWarning       = Color(0xFFFBBF24)
val StatusError         = Color(0xFFF87171)
val StatusErrorDeep     = Color(0xFFDC2626)  // Filled destructive with white text (4.83:1)
val StatusInfo          = Color(0xFF60A5FA)
val StatusSuccessContainer = Color(0xFF10241E)
val StatusWarningContainer = Color(0xFF2A2110)
val StatusErrorContainer   = Color(0xFF31151A)
val StatusInfoContainer    = Color(0xFF111E33)

// Backward-compat: all existing code references these
val iOSBlue           = AccentPrimary
val iOSBlueBright     = AccentBright
val iOSBlueDeep       = AccentDeep
val iOSCyan           = AccentCyan
val iOSIndigo         = Color(0xFF6366F1)  // Indigo
val iOSPurple         = Color(0xFFC084FC)  // Purple
val iOSPink           = Color(0xFFFB7185)  // Rose
val iOSRed            = StatusError        // Red
val iOSOrange         = Color(0xFFFB923C)  // Amber-orange
val iOSYellow         = StatusWarning      // Amber
val iOSGreen          = StatusSuccess      // Green
val iOSMint           = AccentBright       // Light indigo

// --- Accent glow / neon for selection states ---
val AccentNeon        = Color(0xFF8B5CF6)
val AccentNeonBright  = Color(0xFFA78BFA)
val AccentGlow        = Color(0x668B5CF6)  // 40% accent outer glow
val AccentSelection   = Color(0xFF8B5CF6)

// --- Track colors for timeline ---
val TrackVideo        = Color(0xFF8B5CF6)  // Indigo for video
val TrackAudio        = Color(0xFFFBBF24)  // Amber for audio/music
val TrackSFX          = Color(0xFFFB7185)  // Rose for sound effects
val TrackOverlay      = Color(0xFF34D399)  // Green for overlays
val TrackSubtitle     = Color(0xFFC084FC)  // Purple for subtitles
val TrackText         = Color(0xFFA78BFA)  // Light indigo for text

// --- Semantic colors ---
val iOSError          = StatusError
val iOSErrorContainer = StatusErrorContainer
val iOSOnError        = Color(0xFFF5F7FB)
val iOSSuccess        = StatusSuccess

// --- Splash screen palette ---
val SplashBackground         = Color(0xFF0B0D12)
val SplashElectricBlue       = Color(0xFF8B5CF6)
val SplashNeonViolet         = Color(0xFFA78BFA)
val SplashAmbientGlowStart   = Color(0x558B5CF6)
val SplashAmbientGlowEnd     = Color(0x55A78BFA)
val SplashTagline            = Color(0xFFB0B8C8)

// --- State tokens (semantic, restrained — no neon glows) ---
val StateSelectedFill       = Color(0x238B5CF6)  // 14% accent wash for selected tiles
val StateSelectedBorder     = Color(0x598B5CF6)  // 35% accent hairline
val StateDisabledAlpha       = 0.45f              // minimum for disabled text/icons
val Scrim                   = Color(0x990B0D12)  // 60% modal/sheet scrim

// --- On-video scrims (2026-10-16, Phase 2) ---
// Chrome that floats DIRECTLY over the video frame (play glyphs, the filter
// / matting status pills, the before/after bar) cannot rely on a surface
// token: its backdrop is whatever the footage happens to be. These are
// measured against the worst case, pure-white footage:
//   OnVideoScrim        — white glyph composites to ~6.2:1
//   OnVideoScrimStrong  — white text  composites to ~10.4:1
// The values they replace (35% black behind glyphs, 55% behind pills, and
// an 18% accent tint alone behind the matting pill) scored ~2.3:1, ~4.4:1
// and ~1.2:1 — unreadable over bright frames.
val OnVideoScrim            = Color(0x9E000000)  // 62% — behind icons/glyphs
val OnVideoScrimStrong      = Color(0xBF000000)  // 75% — behind text pills

// --- Light theme fallback ---
val iOSLightBackground    = Color(0xFFF7F8FA)
val iOSLightSurface       = Color(0xFFFFFFFF)
val iOSLightSurfaceHigh   = Color(0xFFEFF1F5)
val iOSLightLabelPrimary  = Color(0xFF0F172A)
val iOSLightLabelSecondary = Color(0x990F172A)
val iOSLightLabelTertiary = Color(0x4D0F172A)

// --- Backwards-compat aliases ---
val iOSBlack          = CinematicBlack
val iOSBackground     = CinematicBlack
val iOSSurface        = CinematicSurface
val iOSSurfaceHigh    = CinematicElevated
val iOSSurfaceHighest = CinematicHighest
val iOSSurfaceVariant = CinematicElevated
val NeonGreen         = AccentPrimary
val NeonGreenDim      = AccentBright
val NeonLime          = iOSCyan
val CyberBlack        = CinematicBlack
val CyberSurface      = CinematicSurface
val CyberSurfaceHigh  = CinematicElevated
val CyberOutline      = Hairline
val GreenContainer    = Color(0xFF1E1145)
val OnGreenContainer  = AccentBright
