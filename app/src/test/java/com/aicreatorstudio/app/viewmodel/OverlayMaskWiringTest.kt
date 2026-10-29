package com.aicreatorstudio.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-shape guards for the **2026-10-13 PiP overlay + shape masks**
 * wiring.
 *
 * ## Why this is a source-shape test
 *
 * The pieces that can actually break this feature are all unreachable from
 * a JVM test source set:
 *
 *  - the **preview** is a `@Composable` (needs a composition);
 *  - the **export** is a CPU `FrameOp` behind `Media3TransformerExportEngine`,
 *    which needs a `Context` and a `MediaCodec` (and this project has no
 *    Robolectric and no device in CI);
 *  - the **ViewModel** is a Hilt `@HiltViewModel` that needs Android +
 *    Dagger to construct;
 *  - the **overlay player** wraps `ExoPlayer`.
 *
 * The arithmetic that both renderers depend on *is* executed — see
 * `OverlayMaskTest` — and the persistence codec is executed by
 * `OverlayMaskPersistenceTest`. What is left, and what this file covers, is
 * the **wiring between them**, which is precisely the class of bug this
 * project has shipped repeatedly:
 *
 *  - a field written but never rendered (`positionX`/`positionY`/`scale`
 *    had **zero consumers** before this change);
 *  - two renderers each keeping their own copy of a shape, so preview and
 *    export agree only "by review";
 *  - an effect inserted at the wrong point in the export chain;
 *  - a parameter threaded through three of four call sites.
 *
 * The parity assertions below are the important ones: they pin that the
 * Compose clip and the export clip are built from the **same** outline and
 * the **same** placement function. That is what makes "100% visual parity"
 * structural rather than aspirational.
 *
 * Comments are stripped before every assertion so a KDoc that merely
 * *mentions* a call cannot satisfy a guard — this codebase has been bitten
 * by a comment quoting code that had been removed.
 */
class OverlayMaskWiringTest {

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

    /**
     * Strips `//` and block comments, then returns the remaining code.
     *
     * Kept byte-identical to the copies in `ProgressBarWiringTest`,
     * `CustomFontWiringTest`, `SubtitleEditingWorkflowTest` and
     * `NativeEngineFailureWiringTest`. In particular the one-line
     * `/** ... */` case must re-close the block on the same line, or it
     * swallows every line below it and the assertion below it fails for
     * the wrong reason.
     */
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
                if (trimmed.contains(blockEnd)) inBlock = false
                return@forEach
            }
            out.append(line.substringBefore("//")).append('\n')
        }
        return out.toString()
    }

    private val root = "app/src/main/java/com/aicreatorstudio/app/"
    private val editorScreen = "${root}ui/screens/editor/EditorScreen.kt"
    private val editorViewModel = "${root}viewmodel/EditorViewModel.kt"
    private val maskShape = "${root}ui/screens/editor/OverlayMaskShape.kt"
    private val pipLayer = "${root}ui/screens/editor/OverlayPipLayer.kt"
    private val pipPlayer = "${root}ui/screens/editor/OverlayPipPlayer.kt"
    private val maskPanel = "${root}ui/screens/editor/OverlayMaskPanel.kt"
    private val maskRenderer = "${root}export/OverlayMaskRenderer.kt"
    private val compositor = "${root}export/OverlayCompositeRenderer.kt"
    private val exportHooks = "${root}export/AiExportHooks.kt"
    private val graphBuilder = "${root}export/RenderGraphBuilder.kt"
    private val renderSegment = "${root}export/RenderSegment.kt"
    private val toolbarSpec = "${root}ui/screens/editor/ContextualToolbarSpec.kt"
    private val subPanelHeight = "${root}model/SubPanelHeight.kt"
    private val roomRepo = "${root}db/RoomProjectRepository.kt"

    private fun indexOfOrFail(haystack: String, needle: String, where: String): Int {
        val index = haystack.indexOf(needle)
        assertTrue("'$needle' not found in $where", index >= 0)
        return index
    }

    /**
     * The body of one function: from its signature up to the next
     * 4-space-indented declaration.
     *
     * Preferred over a fixed character window because several assertions
     * here are **negative** ("this action must NOT push history"). A window
     * that stopped short of the closing brace would let the forbidden call
     * sit just outside it, and the guard would pass for the wrong reason —
     * the exact class of mistake this project's source-shape harnesses have
     * already made twice.
     */
    private fun functionBody(source: String, signature: String, where: String): String {
        val start = indexOfOrFail(source, signature, where)
        var lineStart = source.indexOf('\n', start + signature.length)
        while (lineStart >= 0) {
            val nextStart = lineStart + 1
            val rawEnd = source.indexOf('\n', nextStart)
            val lineEnd = if (rawEnd < 0) source.length else rawEnd
            val line = source.substring(nextStart, lineEnd)
            if (line.startsWith("    ") && !line.startsWith("     ") && line.contains("fun ")) {
                return source.substring(start, nextStart)
            }
            lineStart = rawEnd
        }
        return source.substring(start)
    }

    // ── THE parity contract ─────────────────────────────────────────

    @Test
    fun `the preview and the export build the mask from one shared outline`() {
        // If either renderer grows its own copy of the shape maths, the two
        // can drift — and the drift only shows up in the exported file,
        // which is the worst place to find it. Both must call
        // `OverlayMaskGeometry.outline`, which returns a plain normalised
        // polygon consumed by Compose `Path` and `android.graphics.Path`
        // alike.
        val preview = codeLinesOnly(read(maskShape))
        val export = codeLinesOnly(read(maskRenderer))
        assertTrue(
            "the Compose clip must use OverlayMaskGeometry.outline",
            preview.contains("OverlayMaskGeometry.outline(")
        )
        assertTrue(
            "the export clip must use OverlayMaskGeometry.outline",
            export.contains("OverlayMaskGeometry.outline(")
        )
    }

    @Test
    fun `the preview and the export place the PiP with one shared function`() {
        // Same argument as above, for the box: `positionX`, `positionY` and
        // `scale` describe a rectangle, and a second implementation of that
        // rectangle would be a second definition of where the overlay is.
        val preview = codeLinesOnly(read(pipLayer))
        val export = codeLinesOnly(read(compositor))
        assertTrue(
            "the preview must use OverlayPipGeometry.resolveBox",
            preview.contains("OverlayPipGeometry.resolveBox(")
        )
        assertTrue(
            "the export must use OverlayPipGeometry.resolveBox",
            export.contains("OverlayPipGeometry.resolveBox(")
        )
    }

    @Test
    fun `both renderers convert dp through the shared 720 unit canvas`() {
        // The corner radius is the one mask value with a unit. If the
        // preview converted it with Compose's `Dp.toPx()` it would scale
        // with screen density while the export scales with encoder size, so
        // the same project would export different rounding than it
        // previewed.
        val preview = codeLinesOnly(read(maskShape))
        val export = codeLinesOnly(read(maskRenderer))
        assertTrue(
            "the Compose clip must use the shared corner-radius conversion",
            preview.contains("OverlayMaskGeometry.cornerRadiusFraction(")
        )
        assertTrue(
            "the export clip must use the shared corner-radius conversion",
            export.contains("OverlayMaskGeometry.cornerRadiusFraction(")
        )
        assertTrue(
            "the export clip must convert dp through the shared helper",
            export.contains("OverlayMaskGeometry.dpToCanvasPx(")
        )
    }

    @Test
    fun `the feather is driven from one alpha mask in both renderers`() {
        // The soft edge is the one place the two renderers legitimately use
        // different idioms (a Compose `BlendMode` vs a `PorterDuff` mode),
        // so the shared artefact is the rasterised alpha mask rather than
        // the draw call. Both must read `rasterizeAlphaMask`.
        val export = codeLinesOnly(read(maskRenderer))
        val preview = codeLinesOnly(read(pipLayer))
        assertTrue(
            "the export must build the soft edge from rasterizeAlphaMask",
            export.contains("rasterizeAlphaMask(")
        )
        assertTrue(
            "the preview must reuse the same rasterised mask",
            preview.contains("rasterizeAlphaMask(")
        )
        assertTrue(
            "the preview must composite it with a DstIn blend",
            preview.contains("BlendMode.DstIn")
        )
    }

    @Test
    fun `inversion is each renderer's own idiom over the same outline`() {
        // Compose's `PathFillType` has no inverse variant (only NonZero and
        // EvenOdd — verified against ui-graphics 1.7.2), so the preview
        // cannot use the export's trick. It knocks the outside out with a
        // `DstOut` draw on an offscreen layer instead. Same outline, same
        // result, each renderer's native mechanism.
        val export = codeLinesOnly(read(maskRenderer))
        val preview = codeLinesOnly(read(pipLayer))
        assertTrue(
            "the export must invert with a fill type",
            export.contains("INVERSE_EVEN_ODD")
        )
        assertTrue(
            "the preview must invert with a DstOut knockout",
            preview.contains("BlendMode.DstOut")
        )
        assertTrue(
            "the knockout needs an offscreen compositing layer to work",
            preview.contains("CompositingStrategy.Offscreen")
        )
        assertFalse(
            "Compose has no inverse fill type — reaching for one will not compile",
            codeLinesOnly(read(maskShape)).contains("PathFillType.Inverse")
        )
    }

    @Test
    fun `the preview hit test is mask aware`() {
        // A circular facecam must not swallow taps in its own transparent
        // corners, or the overlay becomes an invisible dead zone.
        val preview = codeLinesOnly(read(pipLayer))
        assertTrue(
            "the gesture must consult the shared containment test",
            preview.contains("OverlayMaskGeometry.contains(")
        )
    }

    // ── Preview wiring ──────────────────────────────────────────────

    @Test
    fun `the preview layer is fed the project's overlay list, not a copy`() {
        // The same `uiState.overlays` drives the preview here and the export
        // through `RenderSegment.mediaOverlays`. Feeding the preview
        // anything derived-and-filtered is how the two drift.
        val screen = codeLinesOnly(read(editorScreen))
        assertTrue(
            "the preview must be handed uiState.overlays",
            screen.contains("pipOverlays = uiState.overlays")
        )
        assertTrue(
            "the PiP layer must be rendered in the preview",
            screen.contains("OverlayPipLayer(")
        )
        assertTrue(
            "the PiP layer must be drawn from that list",
            screen.contains("overlays = pipOverlays")
        )
    }

    @Test
    fun `the preview player is created once and released`() {
        // One `ExoPlayer` per video overlay is a real resource; leaking it
        // across a recomposition would exhaust the codec pool.
        val screen = codeLinesOnly(read(editorScreen))
        assertTrue(
            "the player holder must be remembered",
            screen.contains("remember { OverlayPipPlayer(")
        )
        assertTrue(
            "the player holder must be released on dispose",
            screen.contains("pipPlayer.release()")
        )
        val player = codeLinesOnly(read(pipPlayer))
        assertTrue(
            "release must actually release the players",
            player.contains("fun release()")
        )
    }

    @Test
    fun `the overlay player is slaved to the editor clock`() {
        // The PiP preview has to stay in sync with the timeline, not free
        // run. It follows the same poller the stem players use.
        val screen = codeLinesOnly(read(editorScreen))
        assertTrue(
            "the poller must re-sync the overlay players",
            screen.contains("pipPlayer.syncTo(")
        )
        assertTrue(
            "the poller must start and stop them with playback",
            screen.contains("pipPlayer.setPlaying(")
        )
    }

    // ── Export wiring ───────────────────────────────────────────────

    @Test
    fun `the export composites overlays last, after every geometry pass`() {
        // ORDER IS LOAD-BEARING. `positionX`/`positionY`/`scale` describe a
        // position in the frame the viewer finally sees, so a PiP burned in
        // before the reframe would be cropped by it. The tracked-overlay
        // burn-in can also draw over the frame, so the PiP must follow it
        // too.
        val hooks = codeLinesOnly(read(exportHooks))
        val tracked = indexOfOrFail(hooks, "burnTrackedOverlay(segment, uri)", exportHooks)
        val overlay = indexOfOrFail(hooks, "burnMediaOverlays(segment, uri)", exportHooks)
        assertTrue(
            "the PiP pass must run after the tracking burn-in",
            overlay > tracked
        )
    }

    @Test
    fun `the export skips the whole pass when no overlay is live`() {
        // The pass costs a full re-transcode, so a project with no overlays
        // must export through exactly the path it did before the feature
        // existed.
        val hooks = codeLinesOnly(read(exportHooks))
        assertTrue(
            "the pass must be gated on a live overlay",
            hooks.contains("OverlayCompositeRenderer.hasRenderableOverlays(")
        )
        val compositorSrc = codeLinesOnly(read(compositor))
        assertTrue(
            "the gate must be time-aware",
            compositorSrc.contains("fun hasRenderableOverlays(")
        )
        assertTrue(
            "the renderer must be able to report that it has nothing to draw",
            compositorSrc.contains("val hasContent")
        )
    }

    @Test
    fun `the renderer is closed even when the transcode fails`() {
        // An open `MediaCodec` on the failure path leaks a hardware codec
        // and makes the NEXT export fail too.
        val hooks = codeLinesOnly(read(exportHooks))
        val body = functionBody(hooks, "suspend fun burnMediaOverlays(", exportHooks)
        assertTrue("the renderer must be closed", body.contains("close()"))
        assertTrue("the close must be in a finally block", body.contains("finally"))
    }

    @Test
    fun `the overlays reach the export segment through the render graph`() {
        // The chain `VideoProject` → `RenderGraphBuilder` → `RenderSegment`
        // → `AiExportHooks`. A break anywhere in it means the export renders
        // nothing while the preview looks perfect.
        assertTrue(
            "RenderSegment must carry the overlays",
            codeLinesOnly(read(renderSegment)).contains("val mediaOverlays")
        )
        val builder = codeLinesOnly(read(graphBuilder))
        assertTrue(
            "the builder must read them off the project",
            builder.contains("val mediaOverlays = project.mediaOverlays")
        )
        assertTrue(
            "the builder must set them on the segment",
            builder.contains("mediaOverlays = mediaOverlays")
        )
        assertTrue(
            "the export hook must read them off the segment",
            codeLinesOnly(read(exportHooks)).contains("segment.mediaOverlays")
        )
    }

    @Test
    fun `the export maps a frame time to the overlay's own timeline`() {
        // The overlay has its own media clock; the frame has the export's.
        // Conflating them would make a PiP start at the wrong moment on any
        // project with more than one clip.
        val export = codeLinesOnly(read(compositor))
        assertTrue(
            "the export must map the frame time through the shared helper",
            export.contains("OverlayPipGeometry.editorMsForSourcePts(")
        )
        assertTrue(
            "the export must ask whether the overlay is live",
            export.contains("OverlayPipGeometry.isVisibleAt(")
        )
    }

    // ── Persistence plumbing ────────────────────────────────────────

    @Test
    fun `the room repository encodes and decodes the overlays`() {
        val repo = codeLinesOnly(read(roomRepo))
        assertTrue(
            "hydrate must decode the blob",
            repo.contains("EditorStateJson.mediaOverlaysFromJson(")
        )
        assertTrue(
            "save must encode the blob",
            repo.contains("EditorStateJson.mediaOverlaysToJson(")
        )
        assertTrue(
            "the column must be written",
            repo.contains("mediaOverlaysJson =")
        )
    }

    // ── ViewModel ───────────────────────────────────────────────────

    @Test
    fun `the mask action sanitises, guards and gates its undo push`() {
        val vm = codeLinesOnly(read(editorViewModel))
        val body = functionBody(vm, "fun setOverlayMask(", editorViewModel)
        assertTrue(
            "the action must sanitise before storing",
            body.contains("OverlayMaskGeometry.sanitize(settings)")
        )
        val guard = body.indexOf("if (current.maskSettings == next) return")
        val push = body.indexOf("if (pushHistory) historyDelegate.push()")
        val persist = body.indexOf("if (pushHistory) persistProjectMeta()")
        assertTrue("the equality guard must exist", guard >= 0)
        assertTrue("the equality guard must run before the push", guard < push)
        assertTrue("the push must be gated on pushHistory", push >= 0)
        assertTrue("the DB write must be gated on pushHistory", persist >= 0)
        assertTrue(
            "the action must be a no-op for an unknown overlay",
            body.contains("?: return")
        )
    }

    @Test
    fun `the transform action clamps through the shared geometry`() {
        val vm = codeLinesOnly(read(editorViewModel))
        val body = functionBody(vm, "fun updateOverlayTransform(", editorViewModel)
        assertTrue(
            "the position must be clamped",
            body.contains("OverlayPipGeometry.clampNorm(positionX)")
        )
        assertTrue(
            "the scale must be clamped",
            body.contains("OverlayPipGeometry.clampScale(scale)")
        )
        assertFalse(
            "a per-frame drag must not push history",
            body.contains("historyDelegate.push()")
        )
        assertFalse(
            "a per-frame drag must not write to the database",
            body.contains("persistProjectMeta()")
        )
    }

    @Test
    fun `one gesture costs one undo entry and one write`() {
        // The position changes ~60×/s while the finger is down, so the
        // snapshot is taken on the first moved frame and the persist
        // happens once on release — the same split the text gesture uses.
        val vm = codeLinesOnly(read(editorViewModel))
        assertTrue(
            "begin must push exactly one snapshot",
            functionBody(vm, "fun beginOverlayTransform()", editorViewModel)
                .contains("historyDelegate.push()")
        )
        assertTrue(
            "end must persist exactly once",
            functionBody(vm, "fun endOverlayTransform()", editorViewModel)
                .contains("persistProjectMeta()")
        )
    }

    @Test
    fun `selecting an overlay does not clear the selected clip`() {
        // The mask chip lives in the CLIP toolbar, and the clip toolbar only
        // shows while a clip is selected. Clearing the clip here would make
        // the mask panel vanish the instant the user picked the overlay it
        // edits.
        val vm = codeLinesOnly(read(editorViewModel))
        val body = functionBody(vm, "fun selectOverlay(", editorViewModel)
        assertFalse(
            "selectOverlay must not touch selectedClipId",
            body.contains("selectedClipId")
        )
        assertTrue(
            "selectOverlay must store the id",
            body.contains("selectedOverlayId = overlayId")
        )
    }

    // ── Panel + toolbar reachability ────────────────────────────────

    @Test
    fun `the panel exposes a live path and a commit path`() {
        val source = codeLinesOnly(read(maskPanel))
        assertTrue("the panel must be a composable", source.contains("fun OverlayMaskPanel("))
        assertTrue(
            "the panel needs a live (no-history) callback",
            source.contains("onSettingsChanged: (OverlayMaskSettings) -> Unit")
        )
        assertTrue(
            "the panel needs a commit (history + persist) callback",
            source.contains("onSettingsCommitted: (OverlayMaskSettings) -> Unit")
        )
    }

    @Test
    fun `the panel lays out the five shapes in the shared order`() {
        val source = codeLinesOnly(read(maskPanel))
        assertTrue(
            "the chips must follow OverlayMaskGeometry.panelOrder",
            source.contains("OverlayMaskGeometry.panelOrder")
        )
    }

    @Test
    fun `the corner radius slider exists only for the rounded rect`() {
        // The brief asks for a slider "active when Rounded Rect is picked".
        // Hiding it (rather than disabling it) is the honest reading: a
        // radius is meaningless for a circle, heart or star.
        val source = codeLinesOnly(read(maskPanel))
        val gate = indexOfOrFail(
            source,
            "if (settings.type == OverlayMaskType.ROUNDED_RECT)",
            maskPanel
        )
        val slider = source.indexOf("OverlayMaskGeometry.MIN_CORNER_RADIUS_DP", gate)
        assertTrue("the radius slider must live inside that gate", slider > gate)
    }

    @Test
    fun `the panel drives its sliders through the shared bounds`() {
        // A slider whose range disagreed with the model's clamp would fight
        // the user: the thumb would snap back on release. Matched on the two
        // constants separately rather than on the whole `range =` expression,
        // so a line wrap or a reformat cannot fail the guard for the wrong
        // reason.
        val source = codeLinesOnly(read(maskPanel))
        assertTrue(
            "the radius slider must use the shared lower bound",
            source.contains("OverlayMaskGeometry.MIN_CORNER_RADIUS_DP")
        )
        assertTrue(
            "the radius slider must use the shared upper bound",
            source.contains("OverlayMaskGeometry.MAX_CORNER_RADIUS_DP")
        )
        assertTrue(
            "the feather slider must use the shared lower bound",
            source.contains("OverlayMaskGeometry.MIN_FEATHER_DP")
        )
        assertTrue(
            "the feather slider must use the shared upper bound",
            source.contains("OverlayMaskGeometry.MAX_FEATHER_DP")
        )
    }

    @Test
    fun `the reset button restores the model default`() {
        // Hard-coding `OverlayMaskSettings()` here instead of
        // `OverlayMaskSettings.Default` would compile and silently diverge
        // the moment the default changes.
        val source = codeLinesOnly(read(maskPanel))
        assertTrue(
            "the reset must commit the model default",
            source.contains("onSettingsCommitted(OverlayMaskSettings.Default)")
        )
    }

    @Test
    fun `the panel writes back through the view model with the right history flag`() {
        val screen = codeLinesOnly(read(editorScreen))
        assertTrue(
            "the live path must not push history",
            screen.contains("onOverlayMaskChanged(it, settings, false)")
        )
        assertTrue(
            "the commit path must push history",
            screen.contains("onOverlayMaskChanged(it, settings, true)")
        )
        assertTrue(
            "the panel's write path must reach the view model",
            screen.contains("viewModel.setOverlayMask(overlayId, settings, pushHistory = push)")
        )
    }

    @Test
    fun `the editor screen dispatches the panel, titles it, and resolves its target`() {
        val screen = codeLinesOnly(read(editorScreen))
        assertTrue(
            "the sub-panel dispatcher must handle OVERLAY_MASK",
            screen.contains("ToolPanel.OVERLAY_MASK -> {")
        )
        assertTrue(
            "the dispatcher must render the panel",
            screen.contains("OverlayMaskPanel(")
        )
        assertTrue(
            "the sheet title must be set, or the header reads blank",
            screen.contains("ToolPanel.OVERLAY_MASK -> \"ماسک لایه\"")
        )
        assertTrue(
            "the target must be resolved through the shared resolver",
            screen.contains("OverlayPipGeometry.resolveTarget(")
        )
    }

    @Test
    fun `the preview and the panel pick the same overlay to edit`() {
        // The PiP wearing the selection chrome and the PiP the panel
        // reshapes must never be two different objects. Asserted on the
        // bare member name because the preview wraps the receiver onto its
        // own line (`OverlayPipGeometry\n    .resolveTarget(`), which a
        // fully-qualified match would miss.
        val layer = codeLinesOnly(read(pipLayer))
        assertTrue(
            "the preview must resolve the same target",
            layer.contains(".resolveTarget(")
        )
    }

    @Test
    fun `the mask panel is reachable from the toolbar`() {
        val spec = codeLinesOnly(read(toolbarSpec))
        assertTrue(
            "a chip must open ToolPanel.OVERLAY_MASK",
            spec.contains("toolPanel = ToolPanel.OVERLAY_MASK")
        )
    }

    @Test
    fun `the mask sheet is sized as a gallery, not compact`() {
        // It carries five chips plus two sliders plus a switch — far more
        // than a COMPACT row. It must NOT join the COMPACT allow-list,
        // which `SubPanelHeightTest` pins at exactly 11 entries.
        val source = codeLinesOnly(read(subPanelHeight))
        assertTrue(
            "OVERLAY_MASK must resolve to the gallery height",
            source.contains("ToolPanel.OVERLAY_MASK -> Kind.GALLERY")
        )
    }
}
