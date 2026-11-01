package com.aicreatorstudio.app.ui.tutorial

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.aicreatorstudio.app.model.TutorialTarget

/**
 * Where the tour's semantic anchors currently are, in root coordinates.
 *
 * ## Why a registry instead of coordinates passed down
 *
 * The tour points at four composables that live at four different depths
 * of `HomeScreen`'s `LazyColumn`. Threading a callback through every
 * intermediate composable to report "here I am" would touch the signature
 * of each one — and a `LazyColumn` item that scrolls away has to be able
 * to *retract* its bounds too, which a one-shot callback cannot express.
 *
 * A registry keyed by [TutorialTarget] solves both: any composable can
 * write its current bounds, and the reader simply asks for the one it
 * needs. Bounds are overwritten on every layout pass, so a scrolled item
 * reports its new (possibly off-screen) position rather than a stale one.
 *
 * ## Why the coordinates are trustworthy
 *
 * [boundsInRoot] is relative to the root of the composition. The overlay
 * is drawn by a `Canvas` that fills the destination, which is the same
 * origin — so a rect recorded here can be punched out of the scrim
 * directly, with no manual inset arithmetic and no assumption about
 * status-bar height.
 */
class TutorialAnchors {

    /**
     * A **snapshot** map, not a plain `HashMap`.
     *
     * Bounds are written from `onGloballyPositioned`, i.e. after layout.
     * A plain map would take the write and never notify anyone — the
     * overlay would read `null` on its first composition and keep
     * drawing a uniform scrim forever, because nothing it read had
     * changed. A `mutableStateMapOf` makes the read observable, so the
     * first measured frame recomposes the overlay exactly once. It
     * converges: writing the same `Rect` again is not a change.
     */
    private val bounds = mutableStateMapOf<TutorialTarget, Rect>()

    /** Record (or overwrite) the current bounds of [target]. */
    fun record(target: TutorialTarget, rect: Rect) {
        bounds[target] = rect
    }

    /** The last measured bounds of [target], or `null` if it is not composed. */
    fun rectOf(target: TutorialTarget): Rect? = bounds[target]

    /** Forget everything — used when the tour ends so a later run re-measures. */
    fun clear() {
        bounds.clear()
    }
}

/**
 * The registry the current screen publishes, or `null` when no tour is
 * running.
 *
 * `staticCompositionLocalOf` (not `compositionLocalOf`) because the value
 * is a stable object whose *identity* never changes — only its contents
 * do — so there is no read-tracking to do and no recomposition storm when
 * an anchor moves.
 */
val LocalTutorialAnchors = staticCompositionLocalOf<TutorialAnchors?> { null }

/**
 * Marks the composable this is applied to as the anchor for [target].
 *
 * A `@Composable` **extension** on `Modifier` (not a plain function
 * returning one) so the call site reads as a modifier in a chain:
 * `Modifier.tutorialAnchor(TutorialTarget.X)`. It has to be `@Composable`
 * because that is the only way to read [LocalTutorialAnchors] — and it
 * returns `this` unchanged when no registry is published, so the same
 * call site costs nothing in a build where the tour is off and in every
 * Compose preview.
 *
 * Usage — wrap, never re-signature:
 * ```
 * item(key = "actions") {
 *     Box(Modifier.tutorialAnchor(TutorialTarget.CREATE_PROJECT)) {
 *         ActionButtons(...)
 *     }
 * }
 * ```
 * Wrapping is deliberate: `ActionButtons` keeps its own signature, and
 * the anchor is a property of *where the item is*, not of what it draws.
 */
@Composable
fun Modifier.tutorialAnchor(target: TutorialTarget): Modifier {
    val anchors = LocalTutorialAnchors.current ?: return this
    return this.then(
        remember(anchors, target) {
            Modifier.onGloballyPositioned { coordinates ->
                anchors.record(target, coordinates.boundsInRoot())
            }
        }
    )
}
