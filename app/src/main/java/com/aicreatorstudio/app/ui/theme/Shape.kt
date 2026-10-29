package com.aicreatorstudio.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// ============================================================================
// iOS Squircle-style shape system
// Apple uses "continuous" corner curves (squircles) that feel softer than
// standard rounded rectangles. On Android we approximate this with
// RoundedCornerShape at iOS-typical radii: 10 / 13 / 16 / 22 / 28dp.
// ============================================================================

val AppShapes = Shapes(
    // Small chips, badges, compact buttons
    extraSmall = RoundedCornerShape(10.dp),
    // List rows, small cards, secondary buttons
    small      = RoundedCornerShape(13.dp),
    // Cards, sheets, primary buttons
    medium     = RoundedCornerShape(16.dp),
    // Bottom sheets, large cards
    large      = RoundedCornerShape(22.dp),
    // Modals, full-screen sheets, top-level containers
    extraLarge = RoundedCornerShape(28.dp)
)

// iOS system component corner sizes (used by the custom glass components
// and timeline clip thumbnails).
object iOSShapes {
    val capsule   = CircleShape                // Pill shape — true capsule
    val iconBox   = RoundedCornerShape(14.dp)      // iOS app icon / control
    val chip      = RoundedCornerShape(12.dp)      // Filter / speed chips
    val card      = RoundedCornerShape(18.dp)      // Card surface
    val sheet     = RoundedCornerShape(22.dp)      // Bottom sheet top
    val modal     = RoundedCornerShape(24.dp)      // Modal dialog
    val thumb     = RoundedCornerShape(8.dp)       // Timeline clip thumbnail
    val knob      = CircleShape                    // Slider knob
}

/**
 * Raw radius scale, one source of truth for corner sizes. Reach for these
 * (or [AppShapes] / [iOSShapes]) instead of an inline `RoundedCornerShape`
 * so a card on Home and a card in a sheet can never disagree.
 */
object AppRadius {
    val xs   = 8.dp
    val sm   = 12.dp
    val md   = 16.dp
    val lg   = 20.dp
    val xl   = 24.dp
    val xxl  = 28.dp
    val pill = 999.dp

    fun shape(radius: androidx.compose.ui.unit.Dp) = RoundedCornerShape(radius)
}

/**
 * Motion tokens. Restrained by default: state changes use [standard], entries
 * use [emphasized], and anything decorative must respect the system
 * reduced-motion setting (see `LocalMotionEnabled`).
 */
object Motion {
    const val fastMs = 120
    const val standardMs = 200
    const val emphasizedMs = 320
    const val slowMs = 480

    /** Symmetric ease — for state swaps (selection, colour, borders). */
    val standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Slight overshoot-free deceleration — for entrances and reveals. */
    val emphasized: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Exit / dismissal. */
    val exit: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)
}

/**
 * Premium spacing scale — 4pt grid. Prefer these over ad-hoc dp values
 * so toolbars, sheets and cards share one rhythm.
 */
object PremiumSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}

/**
 * Restrained elevation — Apple depth comes from hairlines + translucency,
 * not heavy shadows. One soft shadow for floating layers only.
 */
object PremiumElevation {
    val none = 0.dp
    val floating = 8.dp   // docked toolbars, sheets
    val modal = 16.dp     // dialogs, export sheet
}
