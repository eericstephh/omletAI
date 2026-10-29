package com.aicreatorstudio.app.model

import com.aicreatorstudio.app.export.CompositionTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ClipMotion] — the shared curve behind the live preview and
 * the exported file.
 *
 * These are pure-Kotlin assertions on purpose: the project has no
 * Robolectric, so anything that needs a `Context` or a GL thread (the
 * `ClipMotionEffects` pass in `export/`) cannot be exercised here. Keeping
 * every coordinate decision in [ClipMotion] is what makes the interesting
 * half of the feature testable at all.
 */
class ClipMotionTest {

    private val delta = 0.0001f

    // ---------------------------------------------------------------------
    // progressAt — the normalisation both renderers share
    // ---------------------------------------------------------------------

    @Test
    fun `progress at the clip start is zero`() {
        assertEquals(0f, ClipMotion.progressAt(clipLocalMs = 0L, clipDurationMs = 4000L), delta)
    }

    @Test
    fun `progress at the clip midpoint is one half`() {
        assertEquals(0.5f, ClipMotion.progressAt(clipLocalMs = 2000L, clipDurationMs = 4000L), delta)
    }

    @Test
    fun `progress at the clip end is one`() {
        assertEquals(1f, ClipMotion.progressAt(clipLocalMs = 4000L, clipDurationMs = 4000L), delta)
    }

    @Test
    fun `progress past the clip end clamps instead of wrapping`() {
        // The old preview computed `position % duration`, so a playhead
        // parked a full clip-length past the end reported 0f again and the
        // preset restarted. Clamping is what makes the ramp one-shot.
        assertEquals(1f, ClipMotion.progressAt(clipLocalMs = 9000L, clipDurationMs = 4000L), delta)
    }

    @Test
    fun `progress before the clip start clamps to zero`() {
        assertEquals(0f, ClipMotion.progressAt(clipLocalMs = -2500L, clipDurationMs = 4000L), delta)
    }

    @Test
    fun `a zero-length clip has no progress`() {
        assertEquals(0f, ClipMotion.progressAt(clipLocalMs = 0L, clipDurationMs = 0L), delta)
    }

    @Test
    fun `a negative clip duration has no progress`() {
        assertEquals(0f, ClipMotion.progressAt(clipLocalMs = 100L, clipDurationMs = -4000L), delta)
    }

    // ---------------------------------------------------------------------
    // poseAt — the per-preset curves
    // ---------------------------------------------------------------------

    @Test
    fun `no preset is the identity pose`() {
        val pose = ClipMotion.poseAt(ClipAnimation.NONE, 0.5f)
        assertEquals(1f, pose.alpha, delta)
        assertEquals(1f, pose.scaleX, delta)
        assertEquals(1f, pose.scaleY, delta)
        assertEquals(0f, pose.translationXDp, delta)
        assertEquals(0f, pose.translationYDp, delta)
        assertEquals(0f, pose.rotationZ, delta)
    }

    @Test
    fun `fade in ramps alpha from transparent to opaque`() {
        assertEquals(0f, ClipMotion.poseAt(ClipAnimation.FADE_IN, 0f).alpha, delta)
        assertEquals(0.5f, ClipMotion.poseAt(ClipAnimation.FADE_IN, 0.5f).alpha, delta)
        assertEquals(1f, ClipMotion.poseAt(ClipAnimation.FADE_IN, 1f).alpha, delta)
    }

    @Test
    fun `fade out ramps alpha from opaque to transparent`() {
        assertEquals(1f, ClipMotion.poseAt(ClipAnimation.FADE_OUT, 0f).alpha, delta)
        assertEquals(0.5f, ClipMotion.poseAt(ClipAnimation.FADE_OUT, 0.5f).alpha, delta)
        assertEquals(0f, ClipMotion.poseAt(ClipAnimation.FADE_OUT, 1f).alpha, delta)
    }

    @Test
    fun `fade in out is a triangle that returns to transparent`() {
        // Distinct from FADE_IN: the clip must be invisible at BOTH ends,
        // which is what makes the ping-pong necessary rather than a ramp.
        assertEquals(0f, ClipMotion.poseAt(ClipAnimation.FADE_IN_OUT, 0f).alpha, delta)
        assertEquals(1f, ClipMotion.poseAt(ClipAnimation.FADE_IN_OUT, 0.5f).alpha, delta)
        assertEquals(0f, ClipMotion.poseAt(ClipAnimation.FADE_IN_OUT, 1f).alpha, delta)
    }

    @Test
    fun `slide left travels one way across the clip`() {
        assertEquals(ClipMotion.SLIDE_X_DP, ClipMotion.poseAt(ClipAnimation.SLIDE_LEFT, 0f).translationXDp, delta)
        assertEquals(0f, ClipMotion.poseAt(ClipAnimation.SLIDE_LEFT, 0.5f).translationXDp, delta)
        assertEquals(-ClipMotion.SLIDE_X_DP, ClipMotion.poseAt(ClipAnimation.SLIDE_LEFT, 1f).translationXDp, delta)
    }

    @Test
    fun `slide right is the mirror of slide left`() {
        assertEquals(-ClipMotion.SLIDE_X_DP, ClipMotion.poseAt(ClipAnimation.SLIDE_RIGHT, 0f).translationXDp, delta)
        assertEquals(ClipMotion.SLIDE_X_DP, ClipMotion.poseAt(ClipAnimation.SLIDE_RIGHT, 1f).translationXDp, delta)
    }

    @Test
    fun `slide up travels from below the frame to above it`() {
        // Positive Y is DOWN in the screen convention, so a slide "up"
        // starts low (+Y) and ends high (-Y).
        assertEquals(ClipMotion.SLIDE_Y_DP, ClipMotion.poseAt(ClipAnimation.SLIDE_UP, 0f).translationYDp, delta)
        assertEquals(-ClipMotion.SLIDE_Y_DP, ClipMotion.poseAt(ClipAnimation.SLIDE_UP, 1f).translationYDp, delta)
    }

    @Test
    fun `slide down is the mirror of slide up`() {
        assertEquals(-ClipMotion.SLIDE_Y_DP, ClipMotion.poseAt(ClipAnimation.SLIDE_DOWN, 0f).translationYDp, delta)
        assertEquals(ClipMotion.SLIDE_Y_DP, ClipMotion.poseAt(ClipAnimation.SLIDE_DOWN, 1f).translationYDp, delta)
    }

    @Test
    fun `slide presets never move the other axis`() {
        val pose = ClipMotion.poseAt(ClipAnimation.SLIDE_LEFT, 0.25f)
        assertEquals(0f, pose.translationYDp, delta)
        assertEquals(1f, pose.scaleX, delta)
        assertEquals(0f, pose.rotationZ, delta)
    }

    @Test
    fun `zoom in grows from small to full frame`() {
        assertEquals(ClipMotion.ZOOM_IN_START_SCALE, ClipMotion.poseAt(ClipAnimation.ZOOM_IN, 0f).scaleX, delta)
        assertEquals(0.725f, ClipMotion.poseAt(ClipAnimation.ZOOM_IN, 0.5f).scaleX, delta)
        assertEquals(1f, ClipMotion.poseAt(ClipAnimation.ZOOM_IN, 1f).scaleX, delta)
    }

    @Test
    fun `zoom in scales both axes equally so the aspect survives`() {
        val pose = ClipMotion.poseAt(ClipAnimation.ZOOM_IN, 0.3f)
        assertEquals(pose.scaleX, pose.scaleY, delta)
    }

    @Test
    fun `zoom out shrinks from large to full frame`() {
        assertEquals(ClipMotion.ZOOM_OUT_START_SCALE, ClipMotion.poseAt(ClipAnimation.ZOOM_OUT, 0f).scaleX, delta)
        assertEquals(1.15f, ClipMotion.poseAt(ClipAnimation.ZOOM_OUT, 0.5f).scaleX, delta)
        assertEquals(1f, ClipMotion.poseAt(ClipAnimation.ZOOM_OUT, 1f).scaleX, delta)
    }

    @Test
    fun `rotate turns exactly one revolution across the clip`() {
        assertEquals(0f, ClipMotion.poseAt(ClipAnimation.ROTATE, 0f).rotationZ, delta)
        assertEquals(180f, ClipMotion.poseAt(ClipAnimation.ROTATE, 0.5f).rotationZ, delta)
        assertEquals(360f, ClipMotion.poseAt(ClipAnimation.ROTATE, 1f).rotationZ, delta)
    }

    @Test
    fun `progress beyond the range is clamped rather than extrapolated`() {
        // A ROTATE at 2f must be 360, not 720 — otherwise a caller that
        // forgets to clamp spins the frame twice in the exported file.
        assertEquals(360f, ClipMotion.poseAt(ClipAnimation.ROTATE, 2f).rotationZ, delta)
        assertEquals(0f, ClipMotion.poseAt(ClipAnimation.ROTATE, -1f).rotationZ, delta)
    }

    @Test
    fun `a non-finite progress falls back to the first frame`() {
        // A NaN would otherwise propagate into the matrix and blank the
        // whole export silently.
        val pose = ClipMotion.poseAt(ClipAnimation.ZOOM_IN, Float.NaN)
        assertEquals(ClipMotion.ZOOM_IN_START_SCALE, pose.scaleX, delta)
    }

    // ---------------------------------------------------------------------
    // fadesClip — which presets need a wash on export
    // ---------------------------------------------------------------------

    @Test
    fun `the fade family reports that it fades the clip`() {
        assertTrue(ClipMotion.fadesClip(ClipAnimation.FADE_IN))
        assertTrue(ClipMotion.fadesClip(ClipAnimation.FADE_OUT))
        assertTrue(ClipMotion.fadesClip(ClipAnimation.FADE_IN_OUT))
    }

    @Test
    fun `the geometry presets cost no wash pass`() {
        assertFalse(ClipMotion.fadesClip(ClipAnimation.NONE))
        assertFalse(ClipMotion.fadesClip(ClipAnimation.SLIDE_LEFT))
        assertFalse(ClipMotion.fadesClip(ClipAnimation.SLIDE_RIGHT))
        assertFalse(ClipMotion.fadesClip(ClipAnimation.SLIDE_UP))
        assertFalse(ClipMotion.fadesClip(ClipAnimation.SLIDE_DOWN))
        assertFalse(ClipMotion.fadesClip(ClipAnimation.ZOOM_IN))
        assertFalse(ClipMotion.fadesClip(ClipAnimation.ZOOM_OUT))
        assertFalse(ClipMotion.fadesClip(ClipAnimation.ROTATE))
    }

    @Test
    fun `every preset that reports a fade actually reaches a lower alpha`() {
        // Ties fadesClip to the curve rather than to a list of names, so a
        // preset added later cannot claim a fade it does not perform.
        for (animation in ClipAnimation.entries) {
            if (!ClipMotion.fadesClip(animation)) continue
            val alphas = (0..10).map { ClipMotion.poseAt(animation, it / 10f).alpha }
            assertTrue(
                "$animation claims to fade but never drops below 1f",
                alphas.any { it < 1f - delta }
            )
        }
    }

    // ---------------------------------------------------------------------
    // compose — the transition + animation fold
    // ---------------------------------------------------------------------

    @Test
    fun `an identity motion leaves the transition untouched`() {
        val transition = TransitionPose(
            alpha = 0.4f,
            scaleX = 1.25f,
            scaleY = 0.8f,
            translationXDp = 120f,
            translationYDp = -60f,
            rotationZDeg = 15f,
            rotationXDeg = 5f,
            blurDp = 3f,
            flashWhite = 0.2f,
            flashBlack = 0.1f
        )
        assertEquals(transition, ClipMotion.compose(transition, AnimationPose()))
    }

    @Test
    fun `scales multiply`() {
        val transition = TransitionPose(scaleX = 2f, scaleY = 3f)
        val motion = AnimationPose(scaleX = 0.5f, scaleY = 0.25f)
        val composed = ClipMotion.compose(transition, motion)
        assertEquals(1f, composed.scaleX, delta)
        assertEquals(0.75f, composed.scaleY, delta)
    }

    @Test
    fun `translations add`() {
        val transition = TransitionPose(translationXDp = 100f, translationYDp = -40f)
        val motion = AnimationPose(translationXDp = 24f, translationYDp = 20f)
        val composed = ClipMotion.compose(transition, motion)
        assertEquals(124f, composed.translationXDp, delta)
        assertEquals(-20f, composed.translationYDp, delta)
    }

    @Test
    fun `a zoom does not shrink the transition's travel`() {
        // This is the whole reason the fold happens before the matrix is
        // built. Two stacked matrices would multiply, scaling the push by
        // the zoom; the preview adds them, and so must the export.
        val transition = TransitionPose(translationXDp = 430f)
        val motion = ClipMotion.poseAt(ClipAnimation.ZOOM_IN, 0f)
        assertEquals(0.45f, motion.scaleX, delta)

        val composed = ClipMotion.compose(transition, motion)
        assertEquals(430f, composed.translationXDp, delta)
        assertEquals(0.45f, composed.scaleX, delta)
    }

    @Test
    fun `rotations add`() {
        val transition = TransitionPose(rotationZDeg = 30f, rotationXDeg = 10f)
        val motion = AnimationPose(rotationZ = 180f)
        val composed = ClipMotion.compose(transition, motion)
        assertEquals(210f, composed.rotationZDeg, delta)
        // The preset has no X rotation, so the transition's must survive.
        assertEquals(10f, composed.rotationXDeg, delta)
    }

    @Test
    fun `alphas multiply`() {
        val transition = TransitionPose(alpha = 0.5f)
        val motion = AnimationPose(alpha = 0.5f)
        assertEquals(0.25f, ClipMotion.compose(transition, motion).alpha, delta)
    }

    @Test
    fun `a fade in on top of an already-darkening transition compounds`() {
        // The clip head of a fading transition plus a FADE_IN preset is
        // genuinely darker than either alone; the preview multiplies, so
        // the export must too.
        val transition = TransitionPose(alpha = 0.8f)
        val motion = ClipMotion.poseAt(ClipAnimation.FADE_IN, 0f)
        assertEquals(0f, ClipMotion.compose(transition, motion).alpha, delta)
    }

    @Test
    fun `the composed alpha stays inside the unit range`() {
        val transition = TransitionPose(alpha = 1f)
        for (animation in ClipAnimation.entries) {
            for (step in 0..10) {
                val composed = ClipMotion.compose(
                    transition,
                    ClipMotion.poseAt(animation, step / 10f)
                )
                assertTrue(
                    "$animation at $step produced alpha ${composed.alpha}",
                    composed.alpha in 0f..1f
                )
            }
        }
    }

    @Test
    fun `blur and flash are transition-only and pass straight through`() {
        val transition = TransitionPose(blurDp = 6f, flashWhite = 0.75f, flashBlack = 0.4f)
        val composed = ClipMotion.compose(transition, ClipMotion.poseAt(ClipAnimation.ROTATE, 0.5f))
        assertEquals(6f, composed.blurDp, delta)
        assertEquals(0.75f, composed.flashWhite, delta)
        assertEquals(0.4f, composed.flashBlack, delta)
    }

    // ---------------------------------------------------------------------
    // Preview / export agreement
    // ---------------------------------------------------------------------

    @Test
    fun `the export's output clock lands on the same progress as the preview's playhead`() {
        // The preview normalises `playhead - clipStart` over the clip's
        // source span; the export normalises the effect chain's item-relative
        // timestamp through `clipLocalMsForOutput`, which undoes the speed
        // bake. Both must reach 0.5 halfway through the clip.
        val sourceDurationMs = 4000L
        val speed = 2f
        val outputDurationMs = (sourceDurationMs / speed).toLong()

        val halfwayOutputUs = (outputDurationMs / 2L) * 1000L
        val exportProgress = ClipMotion.progressAt(
            clipLocalMs = CompositionTimeline.clipLocalMsForOutput(halfwayOutputUs, speed),
            clipDurationMs = sourceDurationMs
        )
        val previewProgress = ClipMotion.progressAt(
            clipLocalMs = (sourceDurationMs / 2L),
            clipDurationMs = sourceDurationMs
        )

        assertEquals(previewProgress, exportProgress, delta)
        assertEquals(0.5f, exportProgress, delta)
    }

    @Test
    fun `a sped-up clip still finishes its preset at the clip's last frame`() {
        val sourceDurationMs = 6000L
        val speed = 3f
        val outputDurationMs = (sourceDurationMs / speed).toLong()

        val progressAtEnd = ClipMotion.progressAt(
            clipLocalMs = CompositionTimeline.clipLocalMsForOutput(
                outputDurationMs * 1000L,
                speed
            ),
            clipDurationMs = sourceDurationMs
        )
        assertEquals(1f, progressAtEnd, delta)
    }

    @Test
    fun `neither renderer's clock depends on where the clip sits on the timeline`() {
        // Both renderers feed `progressAt` a CLIP-LOCAL millisecond value,
        // and neither has to know the clip's `timelineStartMs`:
        //
        //  - the preview's player holds a single media item (the active
        //    clip's URI), so its `currentPositionMs` already runs
        //    0..clipDuration;
        //  - Media3 hands the export item-relative timestamps —
        //    `ExoAssetLoaderVideoRenderer` subtracts the item's
        //    `streamStartPositionUs` — so `presentationTimeUs` likewise
        //    starts at 0 for the clip.
        //
        // Subtracting a timeline offset in either place would start the
        // preset partway through for any clip the user dragged away from
        // zero, which is exactly what this guards.
        val clipDurationMs = 4000L
        val oneSecondInUs = 1_000_000L

        val exportLocalMs = CompositionTimeline.clipLocalMsForOutput(oneSecondInUs, speed = 1f)
        val previewLocalMs = 1000L

        assertEquals(previewLocalMs, exportLocalMs)
        assertEquals(
            ClipMotion.progressAt(previewLocalMs, clipDurationMs),
            ClipMotion.progressAt(exportLocalMs, clipDurationMs),
            delta
        )
        assertEquals(0.25f, ClipMotion.progressAt(exportLocalMs, clipDurationMs), delta)
    }

    // ---------------------------------------------------------------------
    // clipOpacity — the per-clip transparency slider
    // ---------------------------------------------------------------------

    @Test
    fun `clip opacity multiplies into the composed alpha`() {
        val composed = ClipMotion.compose(
            transition = TransitionPose(),
            motion = AnimationPose(),
            clipOpacity = 0.4f
        )
        assertEquals(0.4f, composed.alpha, delta)
    }

    @Test
    fun `clip opacity compounds with a fade rather than replacing it`() {
        // A half-opaque clip halfway through a FADE_IN: 0.5 * 0.5.
        val composed = ClipMotion.compose(
            transition = TransitionPose(),
            motion = ClipMotion.poseAt(ClipAnimation.FADE_IN, 0.5f),
            clipOpacity = 0.5f
        )
        assertEquals(0.25f, composed.alpha, delta)
    }

    @Test
    fun `clip opacity defaults to fully opaque`() {
        assertEquals(
            ClipMotion.compose(TransitionPose(alpha = 0.5f), AnimationPose(alpha = 0.5f)),
            ClipMotion.compose(TransitionPose(alpha = 0.5f), AnimationPose(alpha = 0.5f), 1f)
        )
    }

    @Test
    fun `an out-of-range clip opacity is clamped`() {
        assertEquals(1f, ClipMotion.compose(TransitionPose(), AnimationPose(), 4f).alpha, delta)
        assertEquals(0f, ClipMotion.compose(TransitionPose(), AnimationPose(), -2f).alpha, delta)
    }

    @Test
    fun `a non-finite clip opacity is treated as opaque`() {
        // A NaN would otherwise reach the wash and blank the whole clip.
        assertEquals(
            1f,
            ClipMotion.compose(TransitionPose(), AnimationPose(), Float.NaN).alpha,
            delta
        )
    }

    @Test
    fun `clip opacity does not disturb the geometry`() {
        val composed = ClipMotion.compose(
            transition = TransitionPose(translationXDp = 100f, blurDp = 4f),
            motion = ClipMotion.poseAt(ClipAnimation.ZOOM_IN, 0f),
            clipOpacity = 0.3f
        )
        assertEquals(100f, composed.translationXDp, delta)
        assertEquals(0.45f, composed.scaleX, delta)
        assertEquals(4f, composed.blurDp, delta)
    }

    @Test
    fun `a translucent clip is reported as needing a wash`() {
        assertTrue(ClipMotion.darkensClip(ClipAnimation.NONE, clipOpacity = 0.5f))
        assertTrue(ClipMotion.darkensClip(ClipAnimation.ZOOM_IN, clipOpacity = 0.5f))
    }

    @Test
    fun `a fully opaque clip with no fade needs no wash`() {
        assertFalse(ClipMotion.darkensClip(ClipAnimation.NONE, clipOpacity = 1f))
        assertFalse(ClipMotion.darkensClip(ClipAnimation.SLIDE_LEFT, clipOpacity = 1f))
        assertFalse(ClipMotion.darkensClip(ClipAnimation.ZOOM_IN, clipOpacity = 1f))
        assertFalse(ClipMotion.darkensClip(ClipAnimation.ROTATE, clipOpacity = 1f))
    }

    @Test
    fun `a fading preset needs a wash even at full opacity`() {
        assertTrue(ClipMotion.darkensClip(ClipAnimation.FADE_IN, clipOpacity = 1f))
        assertTrue(ClipMotion.darkensClip(ClipAnimation.FADE_OUT, clipOpacity = 1f))
        assertTrue(ClipMotion.darkensClip(ClipAnimation.FADE_IN_OUT, clipOpacity = 1f))
    }

    @Test
    fun `a negligible opacity dip does not buy a compositing pass`() {
        // 0.995 is below 1f but invisible, so the export should not pay for a
        // GL pass it cannot show. Matches TransitionGeometry's epsilon.
        assertFalse(ClipMotion.darkensClip(ClipAnimation.NONE, clipOpacity = 0.995f))
    }

    @Test
    fun `a non-finite clip opacity never claims to darken the clip`() {
        assertFalse(ClipMotion.darkensClip(ClipAnimation.NONE, clipOpacity = Float.NaN))
    }

    @Test
    fun `opacity zero reports a wash and composes to a fully transparent frame`() {
        // Fully transparent means the frame is entirely the canvas colour,
        // i.e. a fully opaque wash in that colour once exported.
        assertTrue(ClipMotion.darkensClip(ClipAnimation.NONE, clipOpacity = 0f))
        assertEquals(0f, ClipMotion.compose(TransitionPose(), AnimationPose(), 0f).alpha, delta)
    }

    // ---------------------------------------------------------------------
    // transform — the Transform panel's zoom and pan
    // ---------------------------------------------------------------------

    @Test
    fun `the transform's zoom multiplies into the composed scale`() {
        val composed = ClipMotion.compose(
            transition = TransitionPose(),
            motion = AnimationPose(),
            transformScale = 2f
        )
        assertEquals(2f, composed.scaleX, delta)
        assertEquals(2f, composed.scaleY, delta)
    }

    @Test
    fun `the transform's zoom is uniform so the aspect ratio survives`() {
        // The panel has one zoom slider, not two. If the two axes could
        // drift apart a zoom would stretch the picture instead of scaling
        // it, which is not what the preview's `graphicsLayer` does.
        val composed = ClipMotion.compose(
            transition = TransitionPose(scaleX = 1.5f, scaleY = 1.5f),
            motion = AnimationPose(),
            transformScale = 3f
        )
        assertEquals(composed.scaleX, composed.scaleY, delta)
    }

    @Test
    fun `the transform's zoom compounds with a preset's zoom`() {
        // ZOOM_IN starts at 0.45, so a 2x manual zoom on top of it is 0.9.
        // This is also the reason the two are folded rather than stacked:
        // two matrices would multiply the same way, but would also scale
        // the transition's travel, which the preview does not do.
        val composed = ClipMotion.compose(
            transition = TransitionPose(),
            motion = ClipMotion.poseAt(ClipAnimation.ZOOM_IN, 0f),
            transformScale = 2f
        )
        assertEquals(0.9f, composed.scaleX, delta)
        assertEquals(0.9f, composed.scaleY, delta)
    }

    @Test
    fun `the pan is carried in canvas fractions`() {
        val composed = ClipMotion.compose(
            transition = TransitionPose(),
            motion = AnimationPose(),
            panXFraction = 0.5f,
            panYFraction = -0.25f
        )
        assertEquals(
            0.5f * TransitionGeometry.REFERENCE_WIDTH_DP,
            composed.translationXDp,
            delta
        )
        assertEquals(
            -0.25f * TransitionGeometry.REFERENCE_HEIGHT_DP,
            composed.translationYDp,
            delta
        )
    }

    @Test
    fun `the pan round-trips through the frame fraction the matrix reads`() {
        // The load-bearing check on the unit conversion. `TransitionGeometry
        // .matrix` turns a pose into NDC by dividing the dp back out by the
        // reference canvas, so a pan that survives this round trip is a pan
        // that moves the exported frame by exactly the fraction the preview
        // moved it — `0.5f` is half the canvas width, both ways round.
        val composed = ClipMotion.compose(
            transition = TransitionPose(),
            motion = AnimationPose(),
            panXFraction = 0.5f,
            panYFraction = -0.25f
        )
        assertEquals(0.5f, TransitionGeometry.translationFractionX(composed), delta)
        assertEquals(-0.25f, TransitionGeometry.translationFractionY(composed), delta)
    }

    @Test
    fun `the pan adds to a transition's travel instead of replacing it`() {
        val composed = ClipMotion.compose(
            transition = TransitionPose(translationXDp = 100f),
            motion = AnimationPose(),
            panXFraction = 0.5f
        )
        assertEquals(
            100f + 0.5f * TransitionGeometry.REFERENCE_WIDTH_DP,
            composed.translationXDp,
            delta
        )
    }

    @Test
    fun `a non-finite or non-positive transform zoom is treated as no zoom`() {
        // `compose` is the last line of defence before the matrix is built:
        // a zero or negative scale would collapse or mirror the frame, and a
        // NaN would blank the entire export rather than fail loudly.
        listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -2f).forEach { bad ->
            val composed = ClipMotion.compose(
                transition = TransitionPose(),
                motion = AnimationPose(),
                transformScale = bad
            )
            assertEquals("scaleX for scale=$bad", 1f, composed.scaleX, delta)
            assertEquals("scaleY for scale=$bad", 1f, composed.scaleY, delta)
        }
    }

    @Test
    fun `a non-finite pan is treated as centred`() {
        val composed = ClipMotion.compose(
            transition = TransitionPose(),
            motion = AnimationPose(),
            panXFraction = Float.NaN,
            panYFraction = Float.NEGATIVE_INFINITY
        )
        assertEquals(0f, composed.translationXDp, delta)
        assertEquals(0f, composed.translationYDp, delta)
    }

    @Test
    fun `the transform leaves the transition's alpha, blur and flash alone`() {
        // Zoom and pan are pure geometry. If they leaked into alpha or into
        // the flash fields, a pan would silently fade the clip.
        val transition = TransitionPose(
            alpha = 0.6f,
            blurDp = 4f,
            flashWhite = 0.3f,
            flashBlack = 0.2f,
            rotationZDeg = 12f
        )
        val composed = ClipMotion.compose(
            transition = transition,
            motion = AnimationPose(),
            transformScale = 2f,
            panXFraction = 0.25f
        )
        assertEquals(0.6f, composed.alpha, delta)
        assertEquals(4f, composed.blurDp, delta)
        assertEquals(0.3f, composed.flashWhite, delta)
        assertEquals(0.2f, composed.flashBlack, delta)
        assertEquals(12f, composed.rotationZDeg, delta)
    }

    @Test
    fun `an identity transform leaves the transition untouched`() {
        // `PoseTimeline`'s fast path keys off exactly this: identity inputs
        // must be a no-op, or a clip the user never transformed would pay
        // for a GL pass it cannot show.
        val transition = TransitionPose(
            alpha = 0.4f,
            scaleX = 1.25f,
            translationXDp = 120f,
            rotationZDeg = 15f
        )
        assertEquals(
            transition,
            ClipMotion.compose(
                transition = transition,
                motion = AnimationPose(),
                transformScale = 1f,
                panXFraction = 0f,
                panYFraction = 0f
            )
        )
    }
}
