package com.aicreatorstudio.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the transition pose resolver.
 *
 * [TransitionEngine.poseForClipLocal] is the single source of truth both
 * renderers call — the Compose preview and the Media3 export — so a bug
 * here shows up twice: wrong on screen *and* wrong in the exported file.
 *
 * These also pin down a pre-existing defect. `evaluate()`'s OUT branch is
 * `a = eased`, documented as "starts at identity and grows to the extreme",
 * so the caller must supply progress running 0 -> 1 across the OUT window.
 * The original code supplied `(clipDuration - localMs) / outWin`, which
 * runs 1 -> 0 and therefore inverted every leaving transition: FADE_OUT
 * behaved identically to FADE_IN. Nothing rendered transitions at the time,
 * so it went unnoticed.
 */
class TransitionEnginePoseTest {

    private fun assertClose(expected: Float, actual: Float, delta: Float = 1e-3f) {
        assertEquals(expected.toDouble(), actual.toDouble(), delta.toDouble())
    }

    private val zoomIn = TransitionConfig(
        type = TransitionType.ZOOM_IN,
        durationMs = 500,
        intensity = 1f,
        easing = TransitionEasing.LINEAR
    )

    private val fadeOut = TransitionConfig(
        type = TransitionType.FADE_OUT,
        durationMs = 500,
        intensity = 1f,
        easing = TransitionEasing.LINEAR
    )

    private fun clip(
        timelineStartMs: Long,
        durationMs: Long = 10_000L,
        transitionIn: String? = null,
        transitionOut: String? = null
    ) = VideoClip(
        id = "clip-1",
        uri = "content://test/clip",
        fileName = "clip.mp4",
        durationMs = durationMs,
        width = 1920,
        height = 1080,
        fileSizeBytes = 1L,
        importedAtEpochMillis = 0L,
        timelineStartMs = timelineStartMs,
        transitionIn = transitionIn,
        transitionOut = transitionOut
    )

    // ---- no transition ----

    @Test
    fun `a clip with no transitions stays at identity everywhere`() {
        for (localMs in listOf(0L, 1L, 250L, 9_999L, 10_000L)) {
            val pose = TransitionEngine.poseForClipLocal(localMs, 10_000L, null, null)
            assertClose(1f, pose.scaleX)
            assertClose(1f, pose.alpha)
            assertClose(0f, pose.translationXDp)
        }
    }

    @Test
    fun `a zero-length clip yields identity rather than dividing by zero`() {
        for (duration in listOf(0L, -1L, -10_000L)) {
            val pose = TransitionEngine.poseForClipLocal(0L, duration, zoomIn, fadeOut)
            assertClose(1f, pose.scaleX)
            assertClose(1f, pose.alpha)
        }
    }

    // ---- entering transition ----

    @Test
    fun `an entering transition starts at its extreme`() {
        val pose = TransitionEngine.poseForClipLocal(0L, 10_000L, zoomIn, null)

        // ZOOM_IN at full intensity is `1 + 0.5 * a`, and a == 1 at
        // progress 0.
        assertClose(1.5f, pose.scaleX)
    }

    @Test
    fun `an entering transition settles by the end of its window`() {
        val pose = TransitionEngine.poseForClipLocal(500L, 10_000L, zoomIn, null)

        assertClose(1f, pose.scaleX)
    }

    @Test
    fun `an entering transition is inert after its window`() {
        val pose = TransitionEngine.poseForClipLocal(4_000L, 10_000L, zoomIn, null)

        assertClose(1f, pose.scaleX)
    }

    @Test
    fun `an entering transition interpolates across its window`() {
        // LINEAR easing, so halfway through the window is halfway through
        // the effect: a = 0.5 -> 1 + 0.5 * 0.5 = 1.25.
        val pose = TransitionEngine.poseForClipLocal(250L, 10_000L, zoomIn, null)

        assertClose(1.25f, pose.scaleX)
    }

    // ---- leaving transition (regression guard for the inverted window) ----

    @Test
    fun `a leaving transition peaks at the last frame`() {
        val pose = TransitionEngine.poseForClipLocal(10_000L, 10_000L, null, zoomIn)

        assertClose(1.5f, pose.scaleX)
    }

    @Test
    fun `a leaving transition is at identity at the start of its window`() {
        val pose = TransitionEngine.poseForClipLocal(9_500L, 10_000L, null, zoomIn)

        assertClose(1f, pose.scaleX)
    }

    @Test
    fun `a leaving transition is inert before its window`() {
        val pose = TransitionEngine.poseForClipLocal(1_000L, 10_000L, null, zoomIn)

        assertClose(1f, pose.scaleX)
    }

    @Test
    fun `fade out is transparent at the last frame and opaque before its window`() {
        // The clearest expression of the old bug: with the inverted
        // progress this came out exactly backwards, making FADE_OUT a
        // fade-IN.
        val atEnd = TransitionEngine.poseForClipLocal(10_000L, 10_000L, null, fadeOut)
        val beforeWindow = TransitionEngine.poseForClipLocal(1_000L, 10_000L, null, fadeOut)

        assertClose(0f, atEnd.alpha)
        assertClose(1f, beforeWindow.alpha)
    }

    @Test
    fun `fade out darkens progressively toward the clip end`() {
        val quarter = TransitionEngine.poseForClipLocal(9_625L, 10_000L, null, fadeOut).alpha
        val threeQuarters = TransitionEngine.poseForClipLocal(9_875L, 10_000L, null, fadeOut).alpha

        assertTrue("expected $quarter > $threeQuarters", quarter > threeQuarters)
    }

    // ---- window clamping and overlap ----

    @Test
    fun `a window longer than the clip is clamped to the clip`() {
        val huge = TransitionConfig(
            type = TransitionType.ZOOM_IN,
            durationMs = 60_000,
            intensity = 1f,
            easing = TransitionEasing.LINEAR
        )

        // With the window clamped to the whole clip, progress at the very
        // start is 0 and at the very end is 1 — no out-of-range values.
        val atStart = TransitionEngine.poseForClipLocal(0L, 1_000L, huge, null)
        val atEnd = TransitionEngine.poseForClipLocal(1_000L, 1_000L, huge, null)

        assertClose(1.5f, atStart.scaleX)
        assertClose(1f, atEnd.scaleX)
    }

    @Test
    fun `the entering transition wins when both windows overlap`() {
        val pose = TransitionEngine.poseForClipLocal(0L, 1_000L, zoomIn, zoomIn)

        // Both windows cover the whole clip; IN is checked first.
        assertClose(1.5f, pose.scaleX)
    }

    @Test
    fun `a playhead outside the clip is clamped to its bounds`() {
        val before = TransitionEngine.poseForClipLocal(-5_000L, 10_000L, zoomIn, null)
        val after = TransitionEngine.poseForClipLocal(99_000L, 10_000L, null, zoomIn)

        assertClose(1.5f, before.scaleX)
        assertClose(1.5f, after.scaleX)
    }

    // ---- the clip-list entry point ----

    @Test
    fun `the clip-list entry point finds the clip under the playhead`() {
        val pose = TransitionEngine.poseForCurrentPosition(
            listOf(clip(timelineStartMs = 2_000L, transitionIn = zoomIn.encode())),
            2_000L
        )

        assertClose(1.5f, pose.scaleX)
    }

    @Test
    fun `the clip-list entry point returns identity outside every clip`() {
        val pose = TransitionEngine.poseForCurrentPosition(
            listOf(clip(timelineStartMs = 2_000L, transitionIn = zoomIn.encode())),
            100L
        )

        assertClose(1f, pose.scaleX)
    }

    @Test
    fun `an auto-layout sentinel start reads as zero`() {
        // `timelineStartMs` is -1 for "auto layout after the previous clip".
        val pose = TransitionEngine.poseForCurrentPosition(
            listOf(clip(timelineStartMs = -1L, transitionIn = zoomIn.encode())),
            0L
        )

        assertClose(1.5f, pose.scaleX)
    }

    @Test
    fun `the clip-list entry point agrees with the clip-local resolver`() {
        val encoded = zoomIn.encode()
        val single = clip(timelineStartMs = 3_000L, transitionIn = encoded)

        for (offset in listOf(0L, 125L, 250L, 500L, 2_000L)) {
            val viaList = TransitionEngine.poseForCurrentPosition(listOf(single), 3_000L + offset)
            val direct = TransitionEngine.poseForClipLocal(
                clipLocalMs = offset,
                clipDurationMs = single.effectiveDurationMs,
                transitionIn = zoomIn,
                transitionOut = null
            )
            assertClose(direct.scaleX, viaList.scaleX)
            assertClose(direct.alpha, viaList.alpha)
        }
    }

    @Test
    fun `an empty clip list yields identity`() {
        assertClose(1f, TransitionEngine.poseForCurrentPosition(emptyList(), 0L).scaleX)
    }

    @Test
    fun `a malformed encoded transition is treated as absent`() {
        val pose = TransitionEngine.poseForCurrentPosition(
            listOf(clip(timelineStartMs = 0L, transitionIn = "not-a-transition")),
            0L
        )

        assertClose(1f, pose.scaleX)
    }
}
