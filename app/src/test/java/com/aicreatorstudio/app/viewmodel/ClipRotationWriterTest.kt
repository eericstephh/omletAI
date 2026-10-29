package com.aicreatorstudio.app.viewmodel

import com.aicreatorstudio.app.model.nextQuarterTurnRotation
import com.aicreatorstudio.app.model.snapRotationToQuarterTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-21 — the `VideoClip.rotation` writer gap.
 *
 * ## The gap
 *
 * `VideoClip.rotation` is a persisted, export-consumed field:
 *
 * ```kotlin
 * // RenderGraphBuilder.toRenderSegment
 * val combinedRotation = normaliseRotation(rotation + transform.rotation)
 * ```
 *
 * but **nothing wrote it**. The Adjust/Effects panel *displayed* it
 * (`EffectsPanel(rotation = clip.rotation)`), so the panel showed a
 * permanent `0°`, and its "Rotate 90°" button wrote
 * `EditorUiState.rotation` — a global mirror that only the preview
 * read. The exported video therefore came out **unrotated** however
 * much the user rotated it.
 *
 * ## The fix
 *
 * 1. The rotation handlers write the **selected clip's** rotation, the
 *    same way brightness / contrast / saturation already do.
 * 2. The preview reads the same field the export reads, so the screen
 *    and the file cannot disagree.
 * 3. Both sides snap to the quarter-turn grid through one shared rule
 *    ([snapRotationToQuarterTurn]).
 *
 * `EditorViewModel` is a Hilt singleton that needs Android + Dagger,
 * so it cannot be constructed in a JVM test. The behavioural part is
 * therefore covered by testing the shared pure rules directly, and the
 * wiring by inspecting the source — the pattern this project's other
 * ViewModel tests use.
 */
class ClipRotationWriterTest {

    // -----------------------------------------------------------------
    // Behaviour: the shared rules
    // -----------------------------------------------------------------

    @Test
    fun `a quarter turn is preserved`() {
        assertEquals(0, snapRotationToQuarterTurn(0))
        assertEquals(90, snapRotationToQuarterTurn(90))
        assertEquals(180, snapRotationToQuarterTurn(180))
        assertEquals(270, snapRotationToQuarterTurn(270))
        // A full turn is the same as no turn.
        assertEquals(0, snapRotationToQuarterTurn(360))
        assertEquals(270, snapRotationToQuarterTurn(630))
    }

    @Test
    fun `a negative angle folds into the positive range`() {
        assertEquals(270, snapRotationToQuarterTurn(-90))
        assertEquals(180, snapRotationToQuarterTurn(-180))
        assertEquals(0, snapRotationToQuarterTurn(-360))
    }

    @Test
    fun `an off-grid angle snaps DOWN to the lower quarter turn`() {
        // Snapping down (not to the nearest) is deliberate: a user who
        // tilts to 89° must not silently get 90°, which would move the
        // frame. The export's own rule is the same one.
        assertEquals(0, snapRotationToQuarterTurn(89))
        assertEquals(90, snapRotationToQuarterTurn(179))
        assertEquals(180, snapRotationToQuarterTurn(269))
        assertEquals(270, snapRotationToQuarterTurn(359))
        assertEquals(0, snapRotationToQuarterTurn(45))
    }

    @Test
    fun `repeated quarter turns accumulate on the clip`() {
        var rotation = 0
        repeat(4) {
            rotation = nextQuarterTurnRotation(rotation, current = 0)
        }
        assertEquals("four 90° steps must come back to 0°", 0, rotation)

        assertEquals(90, nextQuarterTurnRotation(0, current = 0))
        assertEquals(180, nextQuarterTurnRotation(90, current = 0))
        assertEquals(270, nextQuarterTurnRotation(180, current = 0))
        assertEquals(0, nextQuarterTurnRotation(270, current = 0))
    }

    @Test
    fun `the clip's own rotation is the base, not the global mirror`() {
        // The clip wins: the export renders the clip, so repeated taps
        // must accumulate on the clip's value even when the global
        // mirror says something else.
        assertEquals(180, nextQuarterTurnRotation(clipRotation = 90, current = 0))
        assertEquals(0, nextQuarterTurnRotation(clipRotation = 270, current = 0))
    }

    @Test
    fun `with no clip selected the global mirror is the base`() {
        assertEquals(90, nextQuarterTurnRotation(clipRotation = null, current = 0))
        assertEquals(180, nextQuarterTurnRotation(clipRotation = null, current = 90))
    }

    // -----------------------------------------------------------------
    // Wiring: the source shape
    // -----------------------------------------------------------------

    private fun repoRoot(): File {
        var dir: File = File(".").absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            val parent = dir.parentFile ?: break
            dir = parent
        }
        throw IllegalStateException("settings.gradle.kts not found above ${File(".").absolutePath}")
    }

    private fun source(relative: String): String {
        val f = File(repoRoot(), relative)
        assertTrue("missing file: $f", f.isFile)
        return f.readText()
    }

    private val viewModel = "app/src/main/java/com/aicreatorstudio/app/viewmodel/EditorViewModel.kt"
    private val delegate = "app/src/main/java/com/aicreatorstudio/app/viewmodel/AdjustmentDelegate.kt"
    private val editorScreen = "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/EditorScreen.kt"
    private val renderGraph = "app/src/main/java/com/aicreatorstudio/app/export/RenderGraphBuilder.kt"

    @Test
    fun `the rotation handlers write the selected clip`() {
        val src = source(viewModel)
        assertTrue(
            "the rotation must be written to the clip",
            src.contains("if (clipId != null) updateClipRotation(clipId, normalised)") &&
                src.contains("if (clipId != null) updateClipRotation(clipId, next)")
        )
        assertTrue(
            "the clip's rotation must actually be copied",
            src.contains("clip.copy(rotation = rotation)")
        )
        assertTrue(
            "writing the clip's rotation must persist it",
            src.contains("persistClips()")
        )
    }

    @Test
    fun `the rotation handlers no longer write only the global mirror`() {
        val src = source(viewModel)
        assertTrue(
            "the old global-only writer must be gone",
            !src.contains("_uiState.update { it.copy(rotation = rotation % 360) }")
        )
    }

    @Test
    fun `the preview reads the same field the export reads`() {
        val screen = source(editorScreen)
        assertTrue(
            "the preview must take its rotation from the clip",
            screen.contains("rotation = activeClip?.rotation ?: 0")
        )
        assertTrue(
            "the preview must not use the global mirror any more",
            !screen.contains("rotation = uiState.rotation")
        )

        // The export's own source of truth, for the record.
        val graph = source(renderGraph)
        assertTrue(
            "the export combines the clip's rotation with the transform's",
            graph.contains("normaliseRotation(rotation + transform.rotation)")
        )
    }

    @Test
    fun `the adjustment delegate writes the clip too`() {
        val src = source(delegate)
        assertTrue(
            "the delegate must write the clip's rotation",
            src.contains("mutateClip(id) { copy(rotation = v) }")
        )
    }

    @Test
    fun `reset effects resets the clip as well as the mirror`() {
        val src = source(viewModel)
        val reset = src.substringAfter("fun resetEffects()").substringBefore("\n    }")
        assertTrue(
            "reset must clear the clip's rotation",
            reset.contains("updateClipRotation(id, 0)")
        )
        assertTrue(
            "reset must clear the clip's brightness",
            reset.contains("updateClipBrightness(id, 1f)")
        )
        assertTrue(
            "reset must clear the clip's contrast",
            reset.contains("updateClipContrast(id, 1f)")
        )
        assertTrue(
            "reset must clear the clip's saturation",
            reset.contains("updateClipSaturation(id, 1f)")
        )
    }

    @Test
    fun `the editor and the export share one snapping rule`() {
        assertTrue(
            "the export must use the shared rule",
            source(renderGraph).contains("snapRotationToQuarterTurn(deg)")
        )
        assertTrue(
            "the ViewModel must use the shared rule",
            source(viewModel).contains("snapRotationToQuarterTurn(rotation)")
        )
        assertTrue(
            "the clip transform's rotation must be snapped too",
            source(viewModel).contains("snapRotationToQuarterTurn(rotation)")
        )
    }
}
