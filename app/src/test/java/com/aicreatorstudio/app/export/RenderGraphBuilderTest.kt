package com.aicreatorstudio.app.export

import androidx.media3.effect.OverlayEffect
import com.aicreatorstudio.app.model.CanvasBackground
import com.aicreatorstudio.app.model.CanvasPreset
import com.aicreatorstudio.app.model.ClipAnimation
import com.aicreatorstudio.app.model.ColorGrading
import com.aicreatorstudio.app.model.CropPreset
import com.aicreatorstudio.app.model.TransitionConfig
import com.aicreatorstudio.app.model.TransitionDir
import com.aicreatorstudio.app.model.TransitionEasing
import com.aicreatorstudio.app.model.TransitionEngine
import com.aicreatorstudio.app.model.TransitionGeometry
import com.aicreatorstudio.app.model.TransitionType
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.model.VideoEffect
import com.aicreatorstudio.app.model.VideoProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderGraphBuilderTest {

    // --- Empty / null projects ---

    @Test
    fun `null project returns EmptyProject`() {
        val result = RenderGraphBuilder.build(null)
        assertSame(RenderGraphResult.EmptyProject, result)
    }

    @Test
    fun `project with no clips returns EmptyProject`() {
        val project = sampleProject(clips = emptyList())
        val result = RenderGraphBuilder.build(project)
        assertSame(RenderGraphResult.EmptyProject, result)
    }

    @Test
    fun `project with only audio-only clips returns NoRenderableClips`() {
        val audio = sampleClip(id = "a1", isAudioOnly = true)
        val project = sampleProject(clips = listOf(audio))
        val result = RenderGraphBuilder.build(project)
        assertSame(RenderGraphResult.NoRenderableClips, result)
    }

    // --- Basic success path ---

    @Test
    fun `single video clip produces one render segment`() {
        val clip = sampleClip(id = "c1", durationMs = 5_000, timelineStartMs = 0)
        val result = RenderGraphBuilder.build(sampleProject(clips = listOf(clip)))
        assertTrue(result is RenderGraphResult.Success)
        val segments = (result as RenderGraphResult.Success).segments
        assertEquals(1, segments.size)
        val segment = segments.first()
        assertEquals(clip.uri, segment.sourceUri)
        assertEquals(0L, segment.sourceStartMs)
        assertEquals(5_000L, segment.sourceEndMs)
    }

    @Test
    fun `segments are ordered by timelineStartMs ascending`() {
        val c1 = sampleClip(id = "c1", durationMs = 1_000, timelineStartMs = 200)
        val c2 = sampleClip(id = "c2", durationMs = 1_000, timelineStartMs = 0)
        val c3 = sampleClip(id = "c3", durationMs = 1_000, timelineStartMs = 100)
        val result = RenderGraphBuilder.build(sampleProject(clips = listOf(c1, c2, c3)))
        val segments = (result as RenderGraphResult.Success).segments
        assertEquals(listOf("c2", "c3", "c1"), segments.map { it.displayName })
    }

    @Test
    fun `totalOutputDurationMs sums per-segment output durations`() {
        val c1 = sampleClip(id = "c1", durationMs = 4_000, timelineStartMs = 0, speed = 2f)
        val c2 = sampleClip(id = "c2", durationMs = 6_000, timelineStartMs = 4_000)
        val result = RenderGraphBuilder.build(sampleProject(clips = listOf(c1, c2)))
        val segments = (result as RenderGraphResult.Success).segments
        // c1: 4000ms source / 2x speed = 2000ms output
        // c2: 6000ms source / 1x speed = 6000ms output
        assertEquals(8_000L, result.totalOutputDurationMs)
        assertEquals(2_000L, segments[0].outputDurationMs)
        assertEquals(6_000L, segments[1].outputDurationMs)
    }

    // --- Trim handling ---

    @Test
    fun `trim range is honoured when trimEndMs is positive`() {
        val clip = sampleClip(
            id = "c1",
            durationMs = 10_000,
            trimStartMs = 1_000,
            trimEndMs = 4_000
        )
        val result = RenderGraphBuilder.build(sampleProject(clips = listOf(clip)))
        val segment = (result as RenderGraphResult.Success).segments.first()
        assertEquals(1_000L, segment.sourceStartMs)
        assertEquals(4_000L, segment.sourceEndMs)
    }

    @Test
    fun `trimEndMs of -1 is treated as end of source`() {
        val clip = sampleClip(
            id = "c1",
            durationMs = 10_000,
            trimStartMs = 0,
            trimEndMs = -1L
        )
        val result = RenderGraphBuilder.build(sampleProject(clips = listOf(clip)))
        val segment = (result as RenderGraphResult.Success).segments.first()
        assertEquals(10_000L, segment.sourceEndMs)
    }

    @Test
    fun `trimEndMs of 0 is treated as end of source`() {
        val clip = sampleClip(
            id = "c1",
            durationMs = 10_000,
            trimStartMs = 2_000,
            trimEndMs = 0L
        )
        val result = RenderGraphBuilder.build(sampleProject(clips = listOf(clip)))
        val segment = (result as RenderGraphResult.Success).segments.first()
        assertEquals(2_000L, segment.sourceStartMs)
        assertEquals(10_000L, segment.sourceEndMs)
    }

    @Test
    fun `trim range is clamped to the source duration`() {
        val clip = sampleClip(
            id = "c1",
            durationMs = 5_000,
            trimStartMs = 1_000,
            trimEndMs = 99_999
        )
        val result = RenderGraphBuilder.build(sampleProject(clips = listOf(clip)))
        val segment = (result as RenderGraphResult.Success).segments.first()
        assertEquals(5_000L, segment.sourceEndMs)
    }

    @Test
    fun `zero-length trim range filters the segment out`() {
        val clip = sampleClip(
            id = "c1",
            durationMs = 5_000,
            trimStartMs = 3_000,
            trimEndMs = 3_000
        )
        val result = RenderGraphBuilder.build(sampleProject(clips = listOf(clip)))
        assertSame(RenderGraphResult.NoRenderableClips, result)
    }

    // --- Effects pass-through ---

    @Test
    fun `per-clip color and rotation settings pass through to the segment`() {
        val clip = sampleClip(
            id = "c1",
            durationMs = 1_000,
            brightness = 1.4f,
            contrast = 0.8f,
            saturation = 0.5f,
            rotation = 270
        )
        val result = RenderGraphBuilder.build(sampleProject(clips = listOf(clip)))
        val segment = (result as RenderGraphResult.Success).segments.first()
        assertEquals(1.4f, segment.brightness, 0.0001f)
        assertEquals(0.8f, segment.contrast, 0.0001f)
        assertEquals(0.5f, segment.saturation, 0.0001f)
        assertEquals(270, segment.rotationDegrees)
    }

    @Test
    fun `rotation is truncated to a multiple of 90 degrees`() {
        // The builder truncates (not rounds) — see RenderGraphBuilder
        // KDoc for the rationale. The user-visible dial in the editor
        // does its own rounding; this function trusts the input.
        val cases = listOf(
            0 to 0,
            45 to 0,
            89 to 0,
            90 to 90,
            135 to 90,
            180 to 180,
            270 to 270,
            360 to 0,
            361 to 0,
            // Negatives: -90 % 360 == -90, +360 == 270, /90*90 == 270
            -90 to 270,
            // -135 % 360 == -135, +360 == 225, /90*90 == 180
            -135 to 180,
            -179 to 180,
            -180 to 180
        )
        for ((input, expected) in cases) {
            val clip = sampleClip(id = "c-$input", durationMs = 1_000, rotation = input)
            val result = RenderGraphBuilder.build(sampleProject(clips = listOf(clip)))
            val segment = (result as RenderGraphResult.Success).segments.first()
            assertEquals("rotation $input should normalise to $expected", expected, segment.rotationDegrees)
        }
    }

    @Test
    fun `non-positive speed falls back to 1x`() {
        val clip = sampleClip(id = "c1", durationMs = 1_000, speed = 0f)
        val result = RenderGraphBuilder.build(sampleProject(clips = listOf(clip)))
        val segment = (result as RenderGraphResult.Success).segments.first()
        assertEquals(1f, segment.speed, 0.0001f)
    }

    @Test
    fun `audio-only clips are dropped but a video-only sibling still renders`() {
        val audio = sampleClip(id = "a1", isAudioOnly = true)
        val video = sampleClip(id = "v1", durationMs = 2_000, timelineStartMs = 0)
        val result = RenderGraphBuilder.build(sampleProject(clips = listOf(audio, video)))
        val segments = (result as RenderGraphResult.Success).segments
        assertEquals(1, segments.size)
        assertEquals("v1", segments.first().displayName)
    }

    // --- 2026-09-16 multi-clip export fields ---

    @Test
    fun `every video clip becomes its own segment in timeline order`() {
        val clips = listOf(
            sampleClip(id = "c3", durationMs = 1_000, timelineStartMs = 8_000),
            sampleClip(id = "c1", durationMs = 2_000, timelineStartMs = 0),
            sampleClip(id = "c2", durationMs = 3_000, timelineStartMs = 4_000)
        )
        val segments = (RenderGraphBuilder.build(sampleProject(clips = clips))
            as RenderGraphResult.Success).segments

        assertEquals(listOf("c1", "c2", "c3"), segments.map { it.clipId })
        // Concatenated end-to-end, matching totalOutputDurationMs.
        assertEquals(6_000L, CompositionTimeline.totalDurationMs(segments))
    }

    @Test
    fun `timelineStartMs is carried through and the auto-layout sentinel normalises to zero`() {
        val placed = sampleClip(id = "c1", durationMs = 1_000, timelineStartMs = 4_500)
        val placedSegment = (RenderGraphBuilder.build(sampleProject(clips = listOf(placed)))
            as RenderGraphResult.Success).segments.first()
        assertEquals(4_500L, placedSegment.timelineStartMs)

        // -1 is the model's "auto layout after previous clip" sentinel.
        val auto = sampleClip(id = "c2", durationMs = 1_000, timelineStartMs = -1)
        val autoSegment = (RenderGraphBuilder.build(sampleProject(clips = listOf(auto)))
            as RenderGraphResult.Success).segments.first()
        assertEquals(0L, autoSegment.timelineStartMs)
    }

    @Test
    fun `source dimensions are carried through for the FREE multi-clip canvas`() {
        val clip = sampleClip(id = "c1", durationMs = 1_000)
        val segment = (RenderGraphBuilder.build(sampleProject(clips = listOf(clip)))
            as RenderGraphResult.Success).segments.first()
        assertEquals(1920, segment.sourceWidthPx)
        assertEquals(1080, segment.sourceHeightPx)
    }

    // --- New per-clip field pass-through (2026-08-30 toolbar audit) ---

    @Test
    fun `videoEffect passes through to the render segment`() {
        val clip = sampleClip(id = "c1", durationMs = 1_000, videoEffect = VideoEffect.SEPIA)
        val result = RenderGraphBuilder.build(sampleProject(clips = listOf(clip)))
        val segment = (result as RenderGraphResult.Success).segments.first()
        assertEquals(VideoEffect.SEPIA, segment.videoEffect)
    }

    @Test
    fun `non-identity colorGrading passes through to the render segment`() {
        val grading = ColorGrading.Identity.copy(
            exposure = 0.5f,
            temperature = 0.25f
        )
        val clip = sampleClip(id = "c1", durationMs = 1_000, colorGrading = grading)
        val result = RenderGraphBuilder.build(sampleProject(clips = listOf(clip)))
        val segment = (result as RenderGraphResult.Success).segments.first()
        assertEquals(0.5f, segment.colorGrading.exposure, 0.0001f)
        assertEquals(0.25f, segment.colorGrading.temperature, 0.0001f)
    }

    @Test
    fun `crop preset and aspect ratio pass through to the render segment`() {
        val clip = sampleClip(
            id = "c1",
            durationMs = 1_000,
            cropPreset = CropPreset.INSTAGRAM_POST,
            cropAspectRatio = 1f
        )
        val result = RenderGraphBuilder.build(sampleProject(clips = listOf(clip)))
        val segment = (result as RenderGraphResult.Success).segments.first()
        assertEquals(CropPreset.INSTAGRAM_POST, segment.cropPreset)
        assertEquals(1f, segment.cropAspectRatio, 0.0001f)
    }

    @Test
    fun `opacity passes through to the render segment`() {
        val clip = sampleClip(id = "c1", durationMs = 1_000, opacity = 0.5f)
        val result = RenderGraphBuilder.build(sampleProject(clips = listOf(clip)))
        val segment = (result as RenderGraphResult.Success).segments.first()
        assertEquals(0.5f, segment.opacity, 0.0001f)
    }

    @Test
    fun `per-clip volume passes through to the render segment including 0 (mute)`() {
        val clip = sampleClip(id = "c1", durationMs = 1_000, volume = 0f)
        val result = RenderGraphBuilder.build(sampleProject(clips = listOf(clip)))
        val segment = (result as RenderGraphResult.Success).segments.first()
        assertEquals(0f, segment.volume, 0.0001f)
    }

    @Test
    fun `animation passes through to the render segment`() {
        val clip = sampleClip(
            id = "c1",
            durationMs = 1_000,
            animation = ClipAnimation.FADE_IN
        )
        val result = RenderGraphBuilder.build(sampleProject(clips = listOf(clip)))
        val segment = (result as RenderGraphResult.Success).segments.first()
        assertEquals(ClipAnimation.FADE_IN, segment.animation)
    }

    @Test
    fun `project canvas preset is exposed on Success result`() {
        val clip = sampleClip(id = "c1", durationMs = 1_000)
        val project = sampleProject(
            clips = listOf(clip),
            canvasPreset = CanvasPreset.VERTICAL_9_16
        )
        val result = RenderGraphBuilder.build(project)
        assertTrue(result is RenderGraphResult.Success)
        val success = result as RenderGraphResult.Success
        assertEquals(CanvasPreset.VERTICAL_9_16, success.canvasPreset)
    }

    @Test
    fun `project canvas background is exposed on Success result`() {
        val clip = sampleClip(id = "c1", durationMs = 1_000)
        val project = sampleProject(
            clips = listOf(clip),
            canvasBackground = CanvasBackground.SolidColor(0xFF1E90FF) // dodger blue
        )
        val result = RenderGraphBuilder.build(project)
        assertTrue(result is RenderGraphResult.Success)
        val success = result as RenderGraphResult.Success
        assertTrue(success.canvasBackground is CanvasBackground.SolidColor)
        assertEquals(0xFF1E90FF, (success.canvasBackground as CanvasBackground.SolidColor).colorValue)
    }

    @Test
    fun `Blur background flows through to Success result`() {
        val clip = sampleClip(id = "c1", durationMs = 1_000)
        val project = sampleProject(
            clips = listOf(clip),
            canvasBackground = CanvasBackground.Blur
        )
        val result = RenderGraphBuilder.build(project)
        assertTrue(result is RenderGraphResult.Success)
        val success = result as RenderGraphResult.Success
        assertSame(CanvasBackground.Blur, success.canvasBackground)
    }

    @Test
    fun `multiple clips with isolated per-clip effects only affect the targeted clip`() {
        // Three clips with deliberately different per-clip states.
        // The render graph should preserve each clip's settings
        // independently — the Adjust / Filter / Opacity / Volume /
        // Mute panel on one clip must not leak to its neighbours.
        val a = sampleClip(
            id = "a", durationMs = 1_000, timelineStartMs = 0,
            videoEffect = VideoEffect.SEPIA, opacity = 0.8f, volume = 0.3f
        )
        val b = sampleClip(
            id = "b", durationMs = 1_000, timelineStartMs = 1_000,
            videoEffect = VideoEffect.NOIR, opacity = 0.4f, volume = 0f // muted
        )
        val c = sampleClip(
            id = "c", durationMs = 1_000, timelineStartMs = 2_000
            // identity / defaults
        )
        val result = RenderGraphBuilder.build(sampleProject(clips = listOf(a, b, c)))
        val segments = (result as RenderGraphResult.Success).segments
        assertEquals(3, segments.size)

        val segA = segments[0]
        assertEquals(VideoEffect.SEPIA, segA.videoEffect)
        assertEquals(0.8f, segA.opacity, 0.0001f)
        assertEquals(0.3f, segA.volume, 0.0001f)

        val segB = segments[1]
        assertEquals(VideoEffect.NOIR, segB.videoEffect)
        assertEquals(0.4f, segB.opacity, 0.0001f)
        assertEquals(0f, segB.volume, 0.0001f)

        val segC = segments[2]
        assertEquals(VideoEffect.NONE, segC.videoEffect)
        assertEquals(1f, segC.opacity, 0.0001f)
        assertEquals(1f, segC.volume, 0.0001f)
    }

    // --- Transitions: the persisted string reaches the render segment ---
    //
    // `transitionIn` / `transitionOut` were write-only for the entire life of
    // the Transition panel: the editor persisted them and nothing in either
    // renderer ever read them back. These tests pin the bridge that now
    // carries them from the clip model into the render graph, which is what
    // the export's ClipMotionEffects.build() consumes.

    @Test
    fun `a clip with no transitions yields null configs`() {
        val clip = sampleClip(id = "c1", durationMs = 2_000)
        val segment = (RenderGraphBuilder.build(sampleProject(clips = listOf(clip)))
            as RenderGraphResult.Success).segments.first()
        assertNull(segment.transitionIn)
        assertNull(segment.transitionOut)
    }

    @Test
    fun `encoded transitions are decoded onto the segment`() {
        val cfg = TransitionConfig(
            type = TransitionType.ZOOM_IN,
            durationMs = 400,
            intensity = 1.5f,
            easing = TransitionEasing.EASE_OUT
        )
        val clip = sampleClip(id = "c1", durationMs = 2_000, transitionIn = cfg.encode())
        val segment = (RenderGraphBuilder.build(sampleProject(clips = listOf(clip)))
            as RenderGraphResult.Success).segments.first()

        val decoded = segment.transitionIn
        assertNotNull(decoded)
        assertEquals(TransitionType.ZOOM_IN, decoded!!.type)
        assertEquals(400, decoded.durationMs)
        assertEquals(1.5f, decoded.intensity, 0.0001f)
        assertEquals(TransitionEasing.EASE_OUT, decoded.easing)
    }

    @Test
    fun `the two ends are carried independently`() {
        val inCfg = TransitionConfig(TransitionType.PUSH_LEFT, 300, 1.2f, TransitionEasing.LINEAR)
        val outCfg = TransitionConfig(TransitionType.FLASH_WHITE, 150, 2f, TransitionEasing.EASE_IN)
        val clip = sampleClip(
            id = "c1",
            durationMs = 2_000,
            transitionIn = inCfg.encode(),
            transitionOut = outCfg.encode()
        )
        val segment = (RenderGraphBuilder.build(sampleProject(clips = listOf(clip)))
            as RenderGraphResult.Success).segments.first()

        val decodedIn = segment.transitionIn
        val decodedOut = segment.transitionOut
        assertNotNull(decodedIn)
        assertNotNull(decodedOut)
        assertEquals(TransitionType.PUSH_LEFT, decodedIn!!.type)
        assertEquals(300, decodedIn.durationMs)
        assertEquals(TransitionType.FLASH_WHITE, decodedOut!!.type)
        assertEquals(150, decodedOut.durationMs)
    }

    @Test
    fun `a malformed transition string decodes to null rather than throwing`() {
        // The DB column is a plain String?, so a row written by an older
        // build (or hand-edited) can hold anything at all. A decode failure
        // must degrade to "no transition" and never crash the render graph.
        val clip = sampleClip(
            id = "c1",
            durationMs = 2_000,
            transitionIn = "NOT_A_TRANSITION:abc:xyz:NOPE",
            transitionOut = ""
        )
        val segment = (RenderGraphBuilder.build(sampleProject(clips = listOf(clip)))
            as RenderGraphResult.Success).segments.first()
        assertNull(segment.transitionIn)
        assertNull(segment.transitionOut)
    }

    @Test
    fun `a decoded transition is usable by the export probe`() {
        // End-to-end shape check for the export path: once the string stored
        // on the clip has travelled through RenderGraphBuilder, it is enough
        // for ClipMotionEffects to decide what to build and for
        // TransitionGeometry to produce a usable matrix. No Android or GL
        // types are involved, so this stays a plain unit test.
        val cfg = TransitionConfig(
            type = TransitionType.FADE_IN,
            durationMs = 500,
            intensity = 1f,
            easing = TransitionEasing.LINEAR
        )
        val clip = sampleClip(id = "c1", durationMs = 2_000, transitionIn = cfg.encode())
        val segment = (RenderGraphBuilder.build(sampleProject(clips = listOf(clip)))
            as RenderGraphResult.Success).segments.first()
        val decoded = segment.transitionIn
        assertNotNull(decoded)

        // The fade family expresses itself as reduced alpha, and the export
        // has to convert that into a full-frame wash — MP4 has no alpha
        // channel. It is a *fade* wash, which takes the canvas colour, not a
        // black one: a fade never flashes black.
        assertTrue(TransitionGeometry.needsFadeOverlay(decoded!!, TransitionDir.IN))
        assertFalse(TransitionGeometry.needsBlackFlashOverlay(decoded, TransitionDir.IN))

        // At the start of an IN window the frame is fully faded out...
        val atStart = TransitionEngine.evaluate(decoded, TransitionDir.IN, 0f)
        assertEquals(0f, TransitionGeometry.frameAlpha(atStart), 0.0001f)
        assertEquals(1f, TransitionGeometry.fadeAlpha(atStart), 0.0001f)

        // ...and fully restored at the end of it.
        val atEnd = TransitionEngine.evaluate(decoded, TransitionDir.IN, 1f)
        assertEquals(1f, TransitionGeometry.frameAlpha(atEnd), 0.0001f)
        assertEquals(0f, TransitionGeometry.fadeAlpha(atEnd), 0.0001f)

        // The matrix must be finite and well-formed at both extremes, so the
        // GL layer never receives a NaN.
        for (pose in listOf(atStart, atEnd)) {
            val m = TransitionGeometry.matrix(pose, aspectRatio = 16f / 9f)
            assertEquals(16, m.size)
            for (v in m) assertTrue("matrix entry must be finite, got $v", v.isFinite())
        }
    }

    @Test
    fun `a fading clip keeps its wash even when the frame size is unknown`() {
        // Regression (2026-09-17). The full-frame washes used to be sized from a
        // caller-supplied frame size and were skipped outright when it was
        // unknown. There is one case where the engine genuinely cannot know it:
        // a FREE canvas with a single clip whose frame a user rotation grew to
        // the rotated bounding box, because step 1's
        // `ScaleAndRotateTransformation` runs after the size was resolved. A
        // rotated clip's fade therefore silently became a cut.
        //
        // Each wash now observes the size from Media3's
        // `TextureOverlay.configure`, so the size has gone from the signature
        // entirely — and an unknown height must no longer suppress the pass.
        val cfg = TransitionConfig(
            type = TransitionType.FADE_IN,
            durationMs = 500,
            intensity = 1f,
            easing = TransitionEasing.LINEAR
        )
        val clip = sampleClip(
            id = "c1",
            durationMs = 2_000,
            rotation = 90,
            transitionIn = cfg.encode()
        )
        val segment = (RenderGraphBuilder.build(sampleProject(clips = listOf(clip)))
            as RenderGraphResult.Success).segments.first()
        assertEquals(90, segment.rotationDegrees)

        val effects = ClipMotionEffects.build(segment = segment, outputHeightPx = 0)

        assertTrue(
            "a rotated clip on a FREE canvas must still emit its fade wash",
            effects.any { it is OverlayEffect }
        )
    }

    @Test
    fun `a purely geometric clip emits no wash at all`() {
        // The counterpart, so the assertion above is not trivially true: a
        // preset that never leaves full opacity must still cost no wash.
        val clip = sampleClip(id = "c1", durationMs = 2_000, animation = ClipAnimation.ZOOM_IN)
        val segment = (RenderGraphBuilder.build(sampleProject(clips = listOf(clip)))
            as RenderGraphResult.Success).segments.first()

        val effects = ClipMotionEffects.build(segment = segment, outputHeightPx = 0)

        assertFalse(
            "a purely geometric preset must not add an overlay pass",
            effects.any { it is OverlayEffect }
        )
    }

    // --- Audio-only clips are mixed in, not dropped (2026-09-16) ---
    //
    // Until this change the builder filtered `isAudioOnly` clips out of the
    // render graph entirely: the editor played them through
    // `AudioMixerPlayer`, and the exported MP4 simply had no music in it.
    // They now ride along in `audioSegments` so the engine can mix them.

    @Test
    fun `audio-only clips are returned separately instead of being dropped`() {
        val video = sampleClip(id = "v1", durationMs = 4_000, timelineStartMs = 0)
        val music = sampleClip(id = "a1", durationMs = 8_000, timelineStartMs = 1_000, isAudioOnly = true)
        val result = RenderGraphBuilder.build(sampleProject(clips = listOf(video, music)))
        val success = result as RenderGraphResult.Success

        assertEquals(1, success.segments.size)
        assertEquals("v1", success.segments.first().clipId)
        assertEquals(1, success.audioSegments.size)
        assertEquals("a1", success.audioSegments.first().clipId)
    }

    @Test
    fun `audioSegments is empty when the project has no audio-only clips`() {
        val video = sampleClip(id = "v1", durationMs = 1_000)
        val success = RenderGraphBuilder.build(sampleProject(clips = listOf(video)))
            as RenderGraphResult.Success
        assertTrue(success.audioSegments.isEmpty())
    }

    @Test
    fun `audio segments keep their editor-timeline position`() {
        // The engine maps this onto the composition timeline; the builder's
        // job is just to not lose it.
        val video = sampleClip(id = "v1", durationMs = 4_000, timelineStartMs = 0)
        val music = sampleClip(
            id = "a1", durationMs = 8_000, timelineStartMs = 2_500, isAudioOnly = true
        )
        val success = RenderGraphBuilder.build(sampleProject(clips = listOf(video, music)))
            as RenderGraphResult.Success
        assertEquals(2_500L, success.audioSegments.first().timelineStartMs)
    }

    @Test
    fun `audio segments carry volume and fades for the export chain`() {
        val video = sampleClip(id = "v1", durationMs = 4_000)
        val music = sampleClip(
            id = "a1", durationMs = 8_000, isAudioOnly = true,
            volume = 0.4f, fadeInMs = 750L, fadeOutMs = 1_250L
        )
        val success = RenderGraphBuilder.build(sampleProject(clips = listOf(video, music)))
            as RenderGraphResult.Success
        val audio = success.audioSegments.first()
        assertEquals(0.4f, audio.volume, 0.0001f)
        assertEquals(750L, audio.fadeInMs)
        assertEquals(1_250L, audio.fadeOutMs)
    }

    @Test
    fun `a zero-length audio clip is dropped`() {
        val video = sampleClip(id = "v1", durationMs = 4_000)
        val empty = sampleClip(
            id = "a1", durationMs = 8_000, isAudioOnly = true,
            trimStartMs = 3_000, trimEndMs = 3_000
        )
        val success = RenderGraphBuilder.build(sampleProject(clips = listOf(video, empty)))
            as RenderGraphResult.Success
        assertTrue(success.audioSegments.isEmpty())
    }

    @Test
    fun `a project with only audio clips still has no video to render`() {
        // An MP4 needs a video track, so this stays a hard failure rather
        // than silently producing an audio-only file with a black frame.
        val music = sampleClip(id = "a1", durationMs = 8_000, isAudioOnly = true)
        val result = RenderGraphBuilder.build(sampleProject(clips = listOf(music)))
        assertSame(RenderGraphResult.NoRenderableClips, result)
    }

    @Test
    fun `totalOutputDurationMs stays video-only so music cannot extend the file`() {
        val video = sampleClip(id = "v1", durationMs = 4_000, timelineStartMs = 0)
        val longMusic = sampleClip(
            id = "a1", durationMs = 60_000, timelineStartMs = 0, isAudioOnly = true
        )
        val success = RenderGraphBuilder.build(sampleProject(clips = listOf(video, longMusic)))
            as RenderGraphResult.Success
        assertEquals(4_000L, success.totalOutputDurationMs)
    }

    // --- blurReferenceHeightPx: the height the dp-authored blur scales against ---

    @Test
    fun `a pinned frame scales the blur against the resolved height`() {
        // A Presentation pins the frame, so the resolved height IS the frame
        // height and a rotation cannot change it.
        assertEquals(
            1080,
            blurReferenceHeightPx(
                resolvedWidthPx = 1920,
                resolvedHeightPx = 1080,
                rotationDegrees = 90,
                frameIsPinned = true
            )
        )
    }

    @Test
    fun `an unrotated unpinned frame scales against the resolved height`() {
        assertEquals(
            1080,
            blurReferenceHeightPx(
                resolvedWidthPx = 1920,
                resolvedHeightPx = 1080,
                rotationDegrees = 0,
                frameIsPinned = false
            )
        )
    }

    @Test
    fun `a rotated unpinned frame scales against the rotated bounds`() {
        // The case this function exists for: a FREE canvas holding a single
        // rotated clip. Step 1 has already grown 1920x1080 to a 1080x1920
        // bounding box, so the blur has to be scaled against 1920. Scaling it
        // against the resolved 1080 made the blur weaker by exactly the
        // rotation factor.
        assertEquals(
            1920,
            blurReferenceHeightPx(
                resolvedWidthPx = 1920,
                resolvedHeightPx = 1080,
                rotationDegrees = 90,
                frameIsPinned = false
            )
        )
    }

    @Test
    fun `a half turn leaves the frame dimensions alone`() {
        // 180 is also a quarter-turn, but it maps the rectangle onto itself, so
        // the height is unchanged and the blur scale is unaffected.
        assertEquals(
            1080,
            blurReferenceHeightPx(
                resolvedWidthPx = 1920,
                resolvedHeightPx = 1080,
                rotationDegrees = 180,
                frameIsPinned = false
            )
        )
    }

    @Test
    fun `a three-quarter turn swaps the frame dimensions`() {
        assertEquals(
            1920,
            blurReferenceHeightPx(
                resolvedWidthPx = 1920,
                resolvedHeightPx = 1080,
                rotationDegrees = 270,
                frameIsPinned = false
            )
        )
    }

    @Test
    fun `a degenerate frame stays degenerate under rotation`() {
        // `TransitionGeometry.blurSigmaPx` returns 0 for a non-positive height,
        // which is how the blur gets skipped entirely. A rotation must not turn
        // that 0 into a real size.
        assertEquals(
            0,
            blurReferenceHeightPx(0, 0, 90, frameIsPinned = false)
        )
    }

    // --- Helpers ---

    private fun sampleProject(
        clips: List<VideoClip>,
        name: String = "Test",
        canvasPreset: CanvasPreset = CanvasPreset.FREE,
        canvasBackground: CanvasBackground = CanvasBackground.Black
    ): VideoProject = VideoProject(
        id = "p1",
        name = name,
        clips = clips,
        createdAtEpochMillis = 0L,
        canvasPreset = canvasPreset,
        canvasBackground = canvasBackground
    )

    private fun sampleClip(
        id: String,
        durationMs: Long = 1_000,
        timelineStartMs: Long = 0,
        trimStartMs: Long = 0L,
        trimEndMs: Long = -1L,
        isAudioOnly: Boolean = false,
        speed: Float = 1f,
        brightness: Float = 1f,
        contrast: Float = 1f,
        saturation: Float = 1f,
        rotation: Int = 0,
        videoEffect: VideoEffect = VideoEffect.NONE,
        colorGrading: ColorGrading = ColorGrading.Identity,
        cropPreset: CropPreset = CropPreset.FREE,
        cropAspectRatio: Float = 0f,
        opacity: Float = 1f,
        volume: Float = 1f,
        fadeInMs: Long = 0L,
        fadeOutMs: Long = 0L,
        animation: ClipAnimation = ClipAnimation.NONE,
        transitionIn: String? = null,
        transitionOut: String? = null
    ): VideoClip = VideoClip(
        id = id,
        uri = "content://media/$id",
        fileName = id,
        durationMs = durationMs,
        width = 1920,
        height = 1080,
        fileSizeBytes = 1_000_000,
        importedAtEpochMillis = 0L,
        speed = speed,
        trimStartMs = trimStartMs,
        trimEndMs = trimEndMs,
        timelineStartMs = timelineStartMs,
        isAudioOnly = isAudioOnly,
        brightness = brightness,
        contrast = contrast,
        saturation = saturation,
        rotation = rotation,
        videoEffect = videoEffect,
        colorGrading = colorGrading,
        cropPreset = cropPreset,
        cropAspectRatio = cropAspectRatio,
        opacity = opacity,
        volume = volume,
        fadeInMs = fadeInMs,
        fadeOutMs = fadeOutMs,
        animation = animation,
        transitionIn = transitionIn,
        transitionOut = transitionOut
    )
}
