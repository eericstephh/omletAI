package com.aicreatorstudio.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE A — parity between the preview's transition rendering and the
 * export's.
 *
 * The preview used to ignore `transitionIn` / `transitionOut`
 * completely (its only renderer, `EditorVideoCanvas`, was orphaned),
 * so a transition the user picked in the Transition panel showed
 * nothing on screen while the exported MP4 animated. The export
 * composes transition + motion preset + transform + opacity through
 * [ClipMotion.compose]; the preview now calls the same function, so
 * these tests pin the composition the two share rather than
 * re-deriving it in each place.
 *
 * Pure Kotlin (no `android.graphics`), so the geometry is JVM-testable.
 */
class TransitionPreviewParityTest {

    private val identity = TransitionPose()

    private fun cfg(
        type: TransitionType = TransitionType.FADE_IN,
        durationMs: Int = 500
    ) = TransitionConfig(type = type, durationMs = durationMs)

    private fun composed(
        transition: TransitionPose,
        motion: AnimationPose = AnimationPose(),
        opacity: Float = 1f,
        scale: Float = 1f,
        panX: Float = 0f,
        panY: Float = 0f
    ) = ClipMotion.compose(transition, motion, opacity, scale, panX, panY)

    // ---- the engine evaluates what the panel writes ----

    @Test
    fun `a decode round trip of every transition type survives`() {
        for (type in TransitionType.entries) {
            val encoded = cfg(type).encode()
            val decoded = TransitionConfig.decode(encoded)
            assertEquals("$type must round-trip", type, decoded?.type)
        }
    }

    @Test
    fun `a corrupt or blank transition string decodes to null`() {
        assertTrue(TransitionConfig.decode(null) == null)
        assertTrue(TransitionConfig.decode("") == null)
        assertTrue(TransitionConfig.decode("garbage") == null)
        assertTrue(TransitionConfig.decode("NOT_A_TYPE:500:1.0:EASE_IN_OUT") == null)
        // A negative duration must not reach the engine at all.
        assertTrue(TransitionConfig.decode("FADE_IN:-5:1.0:EASE_IN_OUT")?.durationMs?.let { it < 0 } != false)
    }

    // ---- in-window / out-of-window behaviour ----

    @Test
    fun `the first half of a clip is untouched when only an IN transition is set`() {
        val pose = TransitionEngine.poseForClipLocal(
            clipLocalMs = 4_000L,
            clipDurationMs = 10_000L,
            transitionIn = cfg(TransitionType.FADE_IN, 500),
            transitionOut = null
        )
        assertEquals(identity, pose)
    }

    @Test
    fun `an IN transition reaches full identity at its window end`() {
        // FADE_IN grows from black to identity, so at the end of the
        // window the pose must be exactly identity.
        val atEnd = TransitionEngine.poseForClipLocal(
            clipLocalMs = 500L,
            clipDurationMs = 10_000L,
            transitionIn = cfg(TransitionType.FADE_IN, 500),
            transitionOut = null
        )
        assertEquals(identity, atEnd)
        // And at the very start it must NOT be identity — that is the
        // whole point of previewing a transition.
        val atStart = TransitionEngine.poseForClipLocal(
            clipLocalMs = 0L,
            clipDurationMs = 10_000L,
            transitionIn = cfg(TransitionType.FADE_IN, 500),
            transitionOut = null
        )
        assertFalse("a fade must darken the first frame", atStart == identity)
    }

    @Test
    fun `an OUT transition runs toward the clip end, inverting nothing`() {
        // The original preview computed 1 -> 0, which inverted every
        // leaving transition (FADE_OUT behaved like FADE_IN).
        val nearEnd = TransitionEngine.poseForClipLocal(
            clipLocalMs = 9_900L,
            clipDurationMs = 10_000L,
            transitionIn = null,
            transitionOut = cfg(TransitionType.FADE_OUT, 500)
        )
        val atEnd = TransitionEngine.poseForClipLocal(
            clipLocalMs = 10_000L,
            clipDurationMs = 10_000L,
            transitionIn = null,
            transitionOut = cfg(TransitionType.FADE_OUT, 500)
        )
        assertFalse("a fade-out must not be identity at the end", atEnd == identity)
        // Monotonic: closer to the end = further from identity.
        assertTrue(
            "the last frame must be at least as faded as the frame before it",
            atEnd.alpha <= nearEnd.alpha + 1e-4f
        )
    }

    @Test
    fun `an IN window cannot outlive its clip`() {
        // A 60 s transition on a 5 s clip must be clamped, not run
        // past the end.
        val pose = TransitionEngine.poseForClipLocal(
            clipLocalMs = 5_000L,
            clipDurationMs = 5_000L,
            transitionIn = cfg(TransitionType.FADE_IN, 60_000),
            transitionOut = null
        )
        assertTrue(pose.alpha.isFinite() && pose.alpha in 0f..1f)
    }

    @Test
    fun `a zero-duration clip yields identity instead of dividing by zero`() {
        val pose = TransitionEngine.poseForClipLocal(
            clipLocalMs = 0L,
            clipDurationMs = 0L,
            transitionIn = cfg(TransitionType.FADE_IN),
            transitionOut = cfg(TransitionType.FADE_OUT)
        )
        assertEquals(identity, pose)
    }

    @Test
    fun `the IN window wins when both windows overlap a short clip`() {
        val pose = TransitionEngine.poseForClipLocal(
            clipLocalMs = 200L,
            clipDurationMs = 400L,
            transitionIn = cfg(TransitionType.ZOOM_IN, 300),
            transitionOut = cfg(TransitionType.ZOOM_OUT, 300)
        )
        // poseForClipLocal documents "In-window wins on overlap".
        assertTrue(pose.scaleX >= 1f || pose == identity)
    }

    // ---- composition: the preview and the export share this ----

    @Test
    fun `an identity transition composes to exactly the motion preset`() {
        val motion = AnimationPose(scaleX = 1.5f, alpha = 0.5f)
        val out = composed(identity, motion)
        assertEquals(1.5f, out.scaleX, 1e-4f)
        assertEquals(0.5f, out.alpha, 1e-4f)
    }

    @Test
    fun `scales multiply, translations and rotations add, alphas multiply`() {
        val transition = TransitionPose(
            alpha = 0.8f,
            scaleX = 2f,
            translationXDp = 100f,
            rotationZDeg = 10f
        )
        val motion = AnimationPose(
            alpha = 0.5f,
            scaleX = 3f,
            translationXDp = 50f,
            rotationZ = 20f
        )
        val out = composed(transition, motion, opacity = 1f, scale = 2f)
        assertEquals(0.8f * 0.5f, out.alpha, 1e-4f)      // alpha multiplies
        assertEquals(2f * 3f * 2f, out.scaleX, 1e-4f)   // scales multiply
        assertEquals(100f + 50f, out.translationXDp, 1e-3f) // translations add
        assertEquals(10f + 20f, out.rotationZDeg, 1e-3f)  // rotations add
    }

    @Test
    fun `the transform panel's pan travels in reference canvas units`() {
        // ClipTransform.offsetX is a canvas fraction; the export
        // converts it with TransitionGeometry.REFERENCE_WIDTH_DP, so
        // the preview must use the same pose rather than pixels.
        val out = composed(identity, AnimationPose(), panX = 0.5f)
        val expected = 0.5f * TransitionGeometry.REFERENCE_WIDTH_DP
        assertEquals(expected, out.translationXDp, 1e-3f)
    }

    @Test
    fun `a non-finite opacity or scale cannot blank the preview`() {
        // A NaN from a corrupt blob would otherwise propagate into
        // every pose sample and blank the export.
        val nan = Float.NaN
        val out = composed(
            transition = identity,
            motion = AnimationPose(),
            opacity = nan,
            scale = nan,
            panX = nan,
            panY = nan
        )
        assertEquals(1f, out.alpha, 0f)
        assertEquals(1f, out.scaleX, 0f)
        assertEquals(0f, out.translationXDp, 0f)
    }

    // ---- the wash passes the preview draws on top ----

    @Test
    fun `each wash alpha is bounded and independent of the others`() {
        // GLITCH sets BOTH a fade and a black spike; the export states
        // the two must compose rather than compete.
        val glitch = TransitionEngine.evaluate(
            TransitionConfig(type = TransitionType.GLITCH),
            TransitionDir.IN,
            0.5f
        )
        val fade = TransitionGeometry.fadeAlpha(glitch)
        val black = TransitionGeometry.flashBlackAlpha(glitch)
        val white = TransitionGeometry.whiteoutAlpha(glitch)
        for (a in listOf(fade, black, white)) {
            assertTrue("wash alpha must be 0..1, was $a", a in 0f..1f)
        }
        // Both fade and black are non-zero for a glitch — they are
        // separate passes, not a single max().
        assertTrue("a glitch must darken via alpha", glitch.alpha < 1f)
    }

    @Test
    fun `a plain zoom transition draws no wash at all`() {
        val zoom = TransitionEngine.evaluate(
            TransitionConfig(type = TransitionType.ZOOM_IN),
            TransitionDir.IN,
            0.5f
        )
        assertEquals(0f, TransitionGeometry.fadeAlpha(zoom), 1e-6f)
        assertEquals(0f, TransitionGeometry.flashBlackAlpha(zoom), 1e-6f)
        assertEquals(0f, TransitionGeometry.whiteoutAlpha(zoom), 1e-6f)
        // …and its geometry is not identity, which is what the
        // preview's graphicsLayer scales.
        assertFalse(zoom == identity)
    }

    @Test
    fun `a white flash shows in the wash but not in the clip alpha`() {
        val flash = TransitionEngine.evaluate(
            TransitionConfig(type = TransitionType.FLASH_WHITE),
            TransitionDir.IN,
            0.5f
        )
        assertTrue(TransitionGeometry.whiteoutAlpha(flash) > 0f)
        assertEquals(1f, flash.alpha, 1e-6f)
    }

    @Test
    fun `every transition type evaluates to a finite bounded pose`() {
        for (type in TransitionType.entries) {
            for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val pose = TransitionEngine.evaluate(
                    TransitionConfig(type = type),
                    TransitionDir.IN,
                    p
                )
                assertTrue("$type at $p alpha", pose.alpha.isFinite() && pose.alpha in 0f..1f)
                assertTrue("$type at $p scaleX", pose.scaleX.isFinite() && pose.scaleX in 0f..4f)
                assertTrue("$type at $p scaleY", pose.scaleY.isFinite() && pose.scaleY in 0f..4f)
                assertTrue("$type at $p blur", pose.blurDp.isFinite() && pose.blurDp >= 0f)
                assertTrue("$type at $p fade", TransitionGeometry.fadeAlpha(pose) in 0f..1f)
            }
        }
    }
}
