package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.ui.theme.AccentGlowStart
import com.aicreatorstudio.app.ui.theme.AccentGradientEnd
import com.aicreatorstudio.app.ui.theme.CinematicBlack
import com.aicreatorstudio.app.ui.theme.CinematicSurface
import com.aicreatorstudio.app.ui.theme.HairlineSubtle

object EditorLayoutSpec {
    // ── Surfaces (aliases of the Apple Glass tokens — single source) ──
    val ScreenBackground = CinematicBlack
    val ScreenSurface = CinematicSurface
    val TopBarHeight: Dp = 52.dp
    // 2026-09-28 — `PlaybackStripHeight` (48dp) and `BottomDockHeight` (80dp)
    // were removed: both had zero consumers. `EditorScreen` owns its own
    // `PlaybackStripHeight` (56dp), and the contextual toolbar sizes to its
    // content, so a fixed dock height no longer exists.
    //
    // 2026-09-30 — `SubPanelHeightFraction` (a flat 0.5f) was removed too.
    // One number could not satisfy both halves of the requirement: a slider
    // panel wants ~170dp, a gallery wants ~340dp, and the video preview must
    // keep a comfortable minimum either way. On a short screen 50% starved
    // the preview; on a tall one it wasted space. Per-panel sizing now lives
    // in `model/SubPanelHeight.kt`, which takes the panel identity *and* the
    // space actually available into account — see
    // `SubPanelHeight.resolveHeightDp`.

    // ── Spacing system ─────────────────────────────────────
    val SpaceXs: Dp = 4.dp
    val SpaceSm: Dp = 8.dp
    val SpaceMd: Dp = 12.dp
    val SpaceLg: Dp = 16.dp
    val SpaceXl: Dp = 20.dp
    val SpaceXxl: Dp = 24.dp
    val SpaceXxxl: Dp = 32.dp

    // ── Video canvas ────────────────────────────────────────
    val VideoCanvasMaxHeight: Dp = 380.dp
    val VideoCanvasCornerRadius: Dp = 18.dp

    // ── Timeline ────────────────────────────────────────────
    val TimelineTrackRowHeight: Dp = 48.dp
    const val TimelineMaxVisibleRows: Int = 4
    val TimelineRulerHeight: Dp = 24.dp
    val TimelineLabelColumnWidth: Dp = 60.dp
    val TimelineCornerRadius: Dp = 16.dp
    val TimelineClipMinWidthDp: Dp = 18.dp
    val TimelineClipCornerRadius: Dp = 8.dp
    val TimelinePlayheadWidth: Dp = 2.dp
    val TimelinePlayheadHandleHeight: Dp = 14.dp
    val TimelinePlayheadHitWidth: Dp = 28.dp

    // ── Squircle radii ──────────────────────────────────────
    val SquircleSmall: Dp = 10.dp
    val SquircleMedium: Dp = 14.dp
    val SquircleLarge: Dp = 18.dp
    val SquircleExtraLarge: Dp = 24.dp

    // ── Snap & zoom ─────────────────────────────────────────
    const val DEFAULT_TIMELINE_PIXELS_PER_MS: Float = 0.06f
    const val MIN_PIXELS_PER_MS: Float = 0.005f
    const val MAX_PIXELS_PER_MS: Float = 1.0f
    const val TIMELINE_SNAP_MS: Long = 80L
    const val TIMELINE_SNAP_PX: Float = 8f

    // ── Scrub throttling ────────────────────────────────────
    const val SCRUB_SEEK_INTERVAL_MS: Long = 66L
    const val SCRUB_VM_PUSH_INTERVAL_MS: Long = 100L
    const val SCRUB_SETTLE_TIMEOUT_MS: Long = 700L

    // ── Animation ───────────────────────────────────────────
    const val SPRING_DAMPING: Float = 0.7f
    const val SPRING_STIFFNESS: Float = 320f
}

val EditorSquircleSmall get() = RoundedCornerShape(EditorLayoutSpec.SquircleSmall)
val EditorSquircleMedium get() = RoundedCornerShape(EditorLayoutSpec.SquircleMedium)
val EditorSquircleLarge get() = RoundedCornerShape(EditorLayoutSpec.SquircleLarge)
val EditorSquircleExtraLarge get() = RoundedCornerShape(EditorLayoutSpec.SquircleExtraLarge)

val EditorPrimaryGradient: Brush
    @Composable get() = Brush.horizontalGradient(listOf(AccentGlowStart, AccentGradientEnd))

val EditorHairline get() = HairlineSubtle
