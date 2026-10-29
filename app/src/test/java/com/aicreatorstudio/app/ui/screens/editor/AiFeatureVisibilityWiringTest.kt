package com.aicreatorstudio.app.ui.screens.editor

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-19 — guards that the two "silently dead" AI surfaces now have a
 * visible result.
 *
 * ## Smart Highlights had no reader
 *
 * `applyHighlights` writes `VideoClip.highlightRanges` and the panel reports
 * how many ranges were applied, but nothing in the UI or the export ever
 * read that list. From the user's side the tool ran, said "done", and
 * changed nothing. The ranges are now painted on the clip tile.
 *
 * ## The Upscaler was locked behind a stale badge
 *
 * `ComingSoonChipIds` contained `"upscale"`, and `ToolbarTile` sets
 * `enabled = !comingSoon` — the tile was not just badged, it was
 * unreachable. The engine underneath is complete (`UpscaleEngineRegistry`
 * → `EsrganUpscalePipeline`, bundled 4x model, real progress/cancel), so the
 * lock is removed.
 *
 * Structural assertions only: Compose cannot be hosted in this project's JVM
 * test stack, and the constants involved are private to their files.
 */
class AiFeatureVisibilityWiringTest {

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("settings.gradle.kts not found above ${File(".").absolutePath}")
    }

    private fun source(relative: String): String {
        val f = File(repoRoot(), relative)
        assertTrue("missing file: $f", f.isFile)
        return f.readText()
    }

    @Test
    fun `highlight ranges are painted on the clip tile`() {
        val src = source(
            "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/EditorTimeline.kt"
        )
        assertTrue(
            "the timeline must hand the clip's ranges to the tile",
            src.contains("highlightRanges = clip.highlightRanges")
        )
        assertTrue(
            "the strip composable must be invoked from ClipBlock",
            src.contains("HighlightRangeStrip(")
        )
        assertTrue(
            "ranges are clip-local and must be clamped to the tile span",
            src.contains("range.startMs.coerceIn(0L, clipDurationMs)")
        )
    }

    @Test
    fun `the upscaler tile is no longer locked`() {
        val src = source(
            "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/ContextualToolbar.kt"
        )
        val marker = "private val ComingSoonChipIds"
        val start = src.indexOf(marker)
        assertTrue("ComingSoonChipIds declaration not found", start >= 0)
        val lineEnd = src.indexOf('\n', start).let { if (it < 0) src.length else it }
        val declaration = src.substring(start, lineEnd)
        assertTrue(
            "upscale must not be locked: the ESRGAN pipeline is implemented",
            !declaration.contains("\"upscale\"")
        )
        assertTrue(
            "the lock set must be explicitly empty (a finished app ships no locked chip)",
            declaration.contains("emptySet()")
        )
    }
}
