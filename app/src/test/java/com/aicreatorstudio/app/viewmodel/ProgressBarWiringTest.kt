package com.aicreatorstudio.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-shape guards for the **video progress bar** (timeline indicator /
 * story timer) wiring.
 *
 * ## Why this is a source-shape test
 *
 * The three things that can actually break this feature are all
 * untestable in a JVM test source set:
 *
 *  - the **preview** lives in a `@Composable` (needs a composition);
 *  - the **export** lives behind `Media3TransformerExportEngine`, which
 *    needs a `Context` (and the project has no Robolectric);
 *  - the **ViewModel** is a Hilt `@HiltViewModel` that needs Android +
 *    Dagger to construct.
 *
 * The arithmetic that both renderers depend on *is* executed — see
 * `ProgressBarGeometryTest` — and the persistence codec is executed by
 * `ProgressBarSettingsJsonTest`. What is left, and what this file covers,
 * is the **wiring between them**: that the preview and the export both
 * call the shared geometry, that the export adds the effect in the right
 * place in the chain, and that every `saveProjectMeta` call site carries
 * the setting.
 *
 * Those are exactly the regressions this project keeps hitting: a field
 * written but never rendered, a parameter threaded through three of four
 * call sites, and a new effect inserted at the wrong point in the Media3
 * chain.
 *
 * Comments are stripped before every assertion so a KDoc that merely
 * *mentions* a call cannot satisfy a guard — this codebase has been bitten
 * by a comment quoting code that had been removed.
 */
class ProgressBarWiringTest {

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("settings.gradle.kts not found above ${File(".").absolutePath}")
    }

    private fun read(relative: String): String {
        val f = File(repoRoot(), relative)
        assertTrue("$relative missing at $f", f.isFile)
        return f.readText()
    }

    /** Strips `//` and block comments, then returns the remaining code. */
    private fun codeLinesOnly(source: String): String {
        val blockEnd = "*" + "/"
        val out = StringBuilder()
        var inBlock = false
        source.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (inBlock) {
                if (trimmed.contains(blockEnd)) inBlock = false
                return@forEach
            }
            if (trimmed.startsWith("/*")) {
                if (!trimmed.contains(blockEnd) ||
                    trimmed.indexOf(blockEnd) == trimmed.lastIndexOf(blockEnd)
                ) {
                    inBlock = true
                }
                // A ONE-LINE `/** ... */` opens and closes on this very
                // line, so the block must not stay open and swallow every
                // line below it. Omitting this leaves the guard below
                // `RoomProjectRepository`'s single-line KDoc unable to see
                // the encoder it asserts on — the same defect the sibling
                // source-shape tests already guard against.
                if (trimmed.contains(blockEnd)) inBlock = false
                return@forEach
            }
            out.append(line.substringBefore("//")).append('\n')
        }
        return out.toString()
    }

    private val editorViewModel =
        "app/src/main/java/com/aicreatorstudio/app/viewmodel/EditorViewModel.kt"
    private val editorScreen =
        "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/EditorScreen.kt"
    private val exportEngine =
        "app/src/main/java/com/aicreatorstudio/app/export/VideoExportEngine.kt"
    private val overlay = "app/src/main/java/com/aicreatorstudio/app/export/ProgressBarOverlay.kt"
    private val panel =
        "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/ProgressBarPanel.kt"

    private fun indexOfOrFail(haystack: String, needle: String, where: String): Int {
        val index = haystack.indexOf(needle)
        assertTrue("'$needle' not found in $where", index >= 0)
        return index
    }

    // ── Shared geometry: the parity contract ────────────────────

    @Test
    fun `the preview and the export both drive the bar from the shared geometry`() {
        // THE parity guarantee. If either renderer grows its own copy of
        // the arithmetic, the two can drift — and the drift only shows up
        // in the exported file, which is the worst place to find it.
        val preview = codeLinesOnly(read(editorScreen))
        val burnIn = codeLinesOnly(read(overlay))
        assertTrue(
            "the preview must use ProgressBarGeometry.layout",
            preview.contains("ProgressBarGeometry.layout(")
        )
        assertTrue(
            "the preview must use ProgressBarGeometry.progressFraction",
            preview.contains("ProgressBarGeometry.progressFraction(")
        )
        assertTrue(
            "the export overlay must use ProgressBarGeometry.layout",
            burnIn.contains("ProgressBarGeometry.layout(")
        )
        assertTrue(
            "the export overlay must use ProgressBarGeometry.progressFraction",
            burnIn.contains("ProgressBarGeometry.progressFraction(")
        )
    }

    @Test
    fun `the preview converts dp through the shared 720 unit canvas, not screen density`() {
        // The preview block must NOT reach for Compose's own dp→px, or the
        // on-screen bar would scale with the phone's screen density while
        // the exported one scales with the encoder size — a mismatch that
        // only shows up in the exported file.
        val preview = codeLinesOnly(read(editorScreen))
        val start = indexOfOrFail(preview, "ProgressBarGeometry.layout(", editorScreen)
        // Take a window around the call rather than the whole 6000-line
        // file, so an unrelated dp conversion elsewhere cannot fail this.
        val window = preview.substring(
            (start - 1200).coerceAtLeast(0),
            (start + 1200).coerceAtMost(preview.length)
        )
        assertTrue(
            "the preview's bar must be measured in canvas pixels",
            window.contains("canvasHeightPx = canvasSizePx.height")
        )
        assertTrue(
            "the preview's bar must not convert dp with Compose",
            !window.contains("heightDp.dp")
        )
    }

    // ── Preview ─────────────────────────────────────────────────

    @Test
    fun `the preview bar is gated on the enabled flag and on a measured canvas`() {
        val preview = codeLinesOnly(read(editorScreen))
        val start = indexOfOrFail(preview, "ProgressBarGeometry.layout(", editorScreen)
        val window = preview.substring((start - 1500).coerceAtLeast(0), start)
        assertTrue(
            "the preview must check progressBarSettings.isEnabled",
            window.contains("progressBarSettings.isEnabled")
        )
        assertTrue(
            "the preview must not draw before the canvas has been measured",
            window.contains("canvasSizePx.width > 0f")
        )
    }

    @Test
    fun `the preview draws the bar inside the canvas box, before the subtitle layer`() {
        // Inside the aspect-ratio-clipped canvas Box is what makes the
        // preview match the export (the export's plate is canvas-sized).
        // Before the subtitle block matches the export's effect order.
        val preview = codeLinesOnly(read(editorScreen))
        val bar = indexOfOrFail(preview, "ProgressBarGeometry.layout(", editorScreen)
        val canvasBox = indexOfOrFail(preview, ".onSizeChanged { canvasSizePx =", editorScreen)
        val subtitles = indexOfOrFail(preview, "activeSubtitle.subtitleStyle", editorScreen)
        assertTrue("the bar must be drawn after the canvas Box opens", bar > canvasBox)
        assertTrue("the bar must be drawn before the subtitle layer", bar < subtitles)
    }

    @Test
    fun `the preview is fed the same duration the timeline uses`() {
        val screen = codeLinesOnly(read(editorScreen))
        assertTrue(
            "the preview call site must pass the project duration to the bar",
            screen.contains("totalDurationMs = projectDuration")
        )
    }

    // ── Export ──────────────────────────────────────────────────

    @Test
    fun `the export appends the bar's effect between the canvas plate and the subtitles`() {
        // Order in the Media3 chain is the whole design: the bar belongs
        // inside the canvas (so the plate has to exist first) and under the
        // captions (so a caption still reads on top of it).
        val engine = codeLinesOnly(read(exportEngine))
        val plate = indexOfOrFail(engine, "CanvasBackgroundOverlay.build(", exportEngine)
        val bar = indexOfOrFail(engine, "ProgressBarOverlay.build(", exportEngine)
        val subtitles = indexOfOrFail(engine, "OverlayEffect(subtitleOverlays)", exportEngine)
        assertTrue("the bar must run after the canvas plate", bar > plate)
        assertTrue("the bar must run before the subtitle burn-in", bar < subtitles)
    }

    @Test
    fun `the export hands the bar its composition origin so a multi-clip bar is continuous`() {
        // Media3 restarts `presentationTimeUs` at 0 for every item in the
        // sequence, so without this the bar would restart at every cut.
        val engine = codeLinesOnly(read(exportEngine))
        assertTrue(
            "the export must pass a per-segment composition origin",
            engine.contains("compositionOriginMs = compositionOriginsMs.getOrElse(index)")
        )
        assertTrue(
            "the composition origins must come from the shared timeline helper",
            engine.contains("CompositionTimeline.originsMs(originalSegments)")
        )
        assertTrue(
            "the export must pass the composition's total duration",
            engine.contains("totalDurationMs = totalDurationMs")
        )
    }

    @Test
    fun `the export reads the settings off the project`() {
        val engine = codeLinesOnly(read(exportEngine))
        assertTrue(
            "the engine must read project.progressBarSettings",
            engine.contains("val progressBarSettings = project.progressBarSettings")
        )
    }

    @Test
    fun `the export builds nothing at all when the bar is off`() {
        // An untouched project's effect chain has to stay byte-identical to
        // before the feature existed.
        val burnIn = codeLinesOnly(read(overlay))
        assertTrue(
            "build() must return null for a disabled bar",
            burnIn.contains("if (!settings.isEnabled) return null")
        )
    }

    // ── ViewModel ───────────────────────────────────────────────

    @Test
    fun `the view model persists the bar alongside the rest of the project meta`() {
        val vm = codeLinesOnly(read(editorViewModel))
        assertTrue(
            "persistProjectMeta must forward the settings",
            vm.contains("progressBarSettings = state.progressBarSettings")
        )
    }

    @Test
    fun `the view model mirrors the bar back into the editor state on load`() {
        val vm = codeLinesOnly(read(editorViewModel))
        assertTrue(
            "loadProject must mirror project.progressBarSettings into ui state",
            vm.contains("progressBarSettings = project.progressBarSettings")
        )
    }

    @Test
    fun `the view model clamps before storing and gates the undo push`() {
        val vm = codeLinesOnly(read(editorViewModel))
        val start = indexOfOrFail(vm, "fun setProgressBarSettings(", editorViewModel)
        val body = vm.substring(start, (start + 2000).coerceAtMost(vm.length))
        assertTrue("the action must clamp its input", body.contains(".clamped()"))
        assertTrue(
            "the action must gate the undo push on pushHistory",
            body.contains("if (pushHistory) historyDelegate.push()")
        )
        assertTrue(
            "the action must gate the DB write on pushHistory too",
            body.contains("if (pushHistory) persistProjectMeta()")
        )
        assertTrue(
            "the action must mirror the value onto the project for the export",
            body.contains("progressBarSettings = next")
        )
    }

    @Test
    fun `the view model does not push history for a value it already holds`() {
        // A slider re-emitting the current value must not create an undo
        // entry — the stack is 20 deep and does not dedupe.
        val vm = codeLinesOnly(read(editorViewModel))
        val start = indexOfOrFail(vm, "fun setProgressBarSettings(", editorViewModel)
        val body = vm.substring(start, (start + 2000).coerceAtMost(vm.length))
        val guard = body.indexOf("if (_uiState.value.progressBarSettings == next) return")
        val push = body.indexOf("if (pushHistory) historyDelegate.push()")
        assertTrue("the equality guard must exist", guard >= 0)
        assertTrue("the equality guard must run before the push", guard < push)
    }

    // ── Persistence plumbing ────────────────────────────────────

    @Test
    fun `every saveProjectMeta call site carries the settings`() {
        // The fat-parameter write path has four production callers. A new
        // parameter with a default would compile at three of them and
        // silently reset the bar on the fourth — which is why the
        // interface declares it with no default.
        val importVm = codeLinesOnly(
            read("app/src/main/java/com/aicreatorstudio/app/viewmodel/ImportVideoViewModel.kt")
        )
        assertEquals(
            "both ImportVideoViewModel call sites must pass progressBarSettings",
            2,
            Regex("progressBarSettings =").findAll(importVm).count()
        )
        val seeder = codeLinesOnly(
            read("app/src/main/java/com/aicreatorstudio/app/video/SampleProjectSeeder.kt")
        )
        assertEquals(
            "the sample-project seeder must pass progressBarSettings",
            1,
            Regex("progressBarSettings =").findAll(seeder).count()
        )

        val iface = codeLinesOnly(
            read("app/src/main/java/com/aicreatorstudio/app/video/ProjectRepository.kt")
        )
        assertTrue(
            "the interface must declare the parameter without a default",
            iface.contains("progressBarSettings: ProgressBarSettings\n")
        )
        assertTrue(
            "the in-memory implementation must store it",
            iface.contains("progressBarSettings = progressBarSettings")
        )
    }

    @Test
    fun `the room repository encodes and decodes the bar`() {
        val repo = codeLinesOnly(
            read("app/src/main/java/com/aicreatorstudio/app/db/RoomProjectRepository.kt")
        )
        assertTrue(
            "hydrate must decode the blob",
            repo.contains("EditorStateJson.progressBarSettingsFromJson(")
        )
        assertTrue(
            "save must encode the blob",
            repo.contains("EditorStateJson.progressBarSettingsToJson(")
        )
        assertTrue(
            "the column must be written",
            repo.contains("progressBarSettingsJson =")
        )
    }

    @Test
    fun `the schema bump and its migration are both registered`() {
        val db = codeLinesOnly(
            read("app/src/main/java/com/aicreatorstudio/app/db/ProjectDatabase.kt")
        )
        assertTrue("the database version must be 8", db.contains("version = 8"))
        assertTrue(
            "MIGRATION_7_8 must add the column additively with a default",
            db.contains("MIGRATION_7_8") &&
                db.contains("ADD COLUMN progressBarSettingsJson TEXT NOT NULL DEFAULT '{}'")
        )

        val module = codeLinesOnly(
            read("app/src/main/java/com/aicreatorstudio/app/di/AppModule.kt")
        )
        assertTrue(
            "the migration must actually be handed to Room, or the app crashes on open",
            module.contains("ProjectDatabase.MIGRATION_7_8")
        )
        assertTrue(
            "the destructive fallback must stay gone",
            !module.contains("fallbackToDestructiveMigration")
        )
    }

    // ── Panel ───────────────────────────────────────────────────

    @Test
    fun `the panel exists and exposes a live path and a commit path`() {
        val source = codeLinesOnly(read(panel))
        assertTrue("the panel must be a public composable", source.contains("fun ProgressBarPanel("))
        assertTrue(
            "the panel needs a live (no-history) callback",
            source.contains("onSettingsChanged: (ProgressBarSettings) -> Unit")
        )
        assertTrue(
            "the panel needs a commit (history + persist) callback",
            source.contains("onSettingsCommitted: (ProgressBarSettings) -> Unit")
        )
    }

    @Test
    fun `the panel drives every slider through the shared bounds`() {
        // A slider whose range disagreed with the model's clamp would fight
        // the user: the thumb would snap back on release.
        val source = codeLinesOnly(read(panel))
        assertTrue(
            "the height slider must use the shared band",
            source.contains("ProgressBarGeometry.MIN_HEIGHT_DP..ProgressBarGeometry.MAX_HEIGHT_DP")
        )
        assertTrue(
            "the corner-radius slider must use the shared band",
            source.contains("0f..ProgressBarGeometry.MAX_CORNER_RADIUS_DP")
        )
        assertTrue(
            "the margin slider must use the shared band",
            source.contains("0f..ProgressBarGeometry.MAX_MARGIN_DP")
        )
    }

    @Test
    fun `the panel gives the track colour a real consumer`() {
        // `backgroundColorArgb` would otherwise be write-only state — bug
        // class 1, which this project has shipped more than once.
        val source = codeLinesOnly(read(panel))
        assertTrue(
            "the track colour must be editable from the panel",
            source.contains("backgroundColorArgb = preset.argb")
        )
    }

    @Test
    fun `the editor screen dispatches the panel and titles it`() {
        val screen = codeLinesOnly(read(editorScreen))
        assertTrue(
            "the sub-panel dispatcher must handle PROGRESS_BAR",
            screen.contains("ToolPanel.PROGRESS_BAR -> ProgressBarPanel(")
        )
        assertTrue(
            "the sheet title must be set, or the header reads blank",
            screen.contains("ToolPanel.PROGRESS_BAR -> \"نوار پیشرفت\"")
        )
        assertTrue(
            "the panel's write path must reach the view model",
            screen.contains("viewModel.setProgressBarSettings(settings, pushHistory = push)")
        )
    }

    @Test
    fun `the progress bar panel is reachable from the toolbar`() {
        val spec = codeLinesOnly(
            read(
                "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/" +
                    "ContextualToolbarSpec.kt"
            )
        )
        assertTrue(
            "the root row must expose a chip that opens PROGRESS_BAR",
            spec.contains("toolPanel = ToolPanel.PROGRESS_BAR")
        )
    }
}
