package com.aicreatorstudio.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-shape guards for the 2026-10-13 **chroma key** wiring.
 *
 * ## Why source shape
 *
 * The same four walls `OverlayMaskWiringTest` describes: the preview is a
 * `@Composable`, the export is a CPU `FrameOp` behind `Media3Transformer`,
 * the ViewModel needs Hilt and Android, and the overlay player wraps
 * `ExoPlayer`. None of it constructs in the JVM source set.
 *
 * The arithmetic itself IS executed — see `ChromaKeyEngineTest` — and the
 * persistence codec is executed by `ChromaKeyPersistenceTest`. What is left
 * is the wiring, and for this feature the wiring is where the bugs actually
 * live:
 *
 *  - a panel that renders the model default instead of the selected
 *    overlay's stored settings;
 *  - a live/commit pair where only the live path was threaded, so a slider
 *    drag persists nothing;
 *  - a panel branch added to the dispatch but not the sheet title, leaving a
 *    blank sheet;
 *  - the export keying the frame **after** the mask clip, which changes the
 *    result because a transparent pixel has no shape left to clip;
 *  - a second implementation of the maths in the preview.
 *
 * Every assertion below strips comments first, so a KDoc that merely
 * *mentions* a call cannot satisfy a guard. See `OverlayMaskWiringTest`'s
 * `codeLinesOnly` for the two harness bugs this avoids.
 */
class ChromaKeyWiringTest {

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "settings.gradle.kts not found above ${File(".").absolutePath}"
        )
    }

    private fun read(relative: String): String {
        val f = File(repoRoot(), relative)
        assertTrue("$relative missing at $f", f.isFile)
        return f.readText()
    }

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
    private val editorScreen = root + "ui/screens/editor/EditorScreen.kt"
    private val editorViewModel = root + "viewmodel/EditorViewModel.kt"
    private val chromaPanel = root + "ui/screens/editor/ChromaKeyPanel.kt"
    private val chromaModel = root + "model/ChromaKeySettings.kt"
    private val pipLayer = root + "ui/screens/editor/OverlayPipLayer.kt"
    private val compositor = root + "export/OverlayCompositeRenderer.kt"
    private val exportHooks = root + "export/AiExportHooks.kt"
    private val renderSegment = root + "export/RenderSegment.kt"
    private val graphBuilder = root + "export/RenderGraphBuilder.kt"
    private val stateJson = root + "db/EditorStateJson.kt"
    private val uiState = root + "model/EditorUiState.kt"
    private val toolbarSpec = root + "ui/screens/editor/ContextualToolbarSpec.kt"
    private val subPanelHeight = root + "model/SubPanelHeight.kt"

    private fun indexOfOrFail(haystack: String, needle: String, where: String): Int {
        val index = haystack.indexOf(needle)
        assertTrue("'$needle' not found in $where", index >= 0)
        return index
    }

    // ---- one maths implementation ------------------------------------

    @Test
    fun `only one file contains the chroma key arithmetic`() {
        // The whole reason `ChromaKeyEngine` is a pure object. A second
        // implementation in a renderer would make "100% visual parity"
        // a statement about review, not about code.
        val engine = codeLinesOnly(read(root + "util/ChromaKeyEngine.kt"))
        assertTrue("the engine owns the BT.601 matrix", engine.contains("0.168736"))
        for (file in listOf(compositor, pipLayer)) {
            val body = codeLinesOnly(read(file))
            assertFalse(
                "$file must not re-derive the matrix",
                body.contains("0.168736")
            )
        }
    }

    @Test
    fun `both renderers call the shared engine`() {
        // Export and preview must each enter the engine through one of its
        // documented entry points, never through a copy of the maths.
        val export = codeLinesOnly(read(compositor))
        assertTrue(
            "export must key through ChromaKeyEngine.applyInto",
            export.contains("ChromaKeyEngine.applyInto(")
        )
        val preview = codeLinesOnly(read(pipLayer))
        assertTrue(
            "preview must key through ChromaKeyEngine.keyedCopy",
            preview.contains("ChromaKeyEngine.keyedCopy(")
        )
    }

    @Test
    fun `the preview keys both its video and its still paths`() {
        // A still overlay that ignores the key is exactly the kind of
        // half-wired feature that reaches a user: the video case is obvious
        // in testing because it moves.
        val preview = codeLinesOnly(read(pipLayer))
        val video = preview.indexOf("OverlayChromaKeyedFrame(")
        assertTrue("the video path must be keyed", video >= 0)
        assertTrue(
            "the video path must take the overlay's settings",
            preview.contains("settings = overlay.chromaKeySettings")
        )
        assertTrue(
            "the still path must be keyed through the engine",
            preview.contains("ChromaKeyEngine.keyedCopy(")
        )
        // The still helper must return the UN-keyed bitmap as its fallback,
        // so a failed key cannot make the overlay disappear.
        assertTrue(
            "the still path must fall back to the un-keyed bitmap",
            preview.contains("keyed?.asImageBitmap() ?: base")
        )
    }

    // ---- export ordering ---------------------------------------------

    @Test
    fun `export applies the chroma key before the mask clip`() {
        // Order is load-bearing, not cosmetic: the key decides which pixels
        // still exist, and the mask clips what is left. Reversing them would
        // show up as a square of keyed-out background at the overlay edge.
        //
        // Asserted as a **data flow**, not as line order across functions:
        // `drawAt` must hand `drawOne` the bitmap that came out of
        // `entry.keyed(decoded)`, and the clip itself happens inside
        // `drawOne` via `OverlayMaskRenderer.buildPath` / `applySoftMask`.
        val body = codeLinesOnly(read(compositor))
        assertTrue(
            "drawAt must key into the src it draws",
            body.contains("val src = entry.keyed(decoded) ?: decoded")
        )
        val drawAtEnd = body.indexOf("private fun drawOne(")
        assertTrue("drawOne is missing", drawAtEnd >= 0)
        val drawAt = body.substring(0, drawAtEnd)
        val keyIndex = drawAt.indexOf("entry.keyed(decoded)")
        val drawIndex = drawAt.indexOf("drawOne(c, frame, src, entry.overlay, box)")
        assertTrue("the key call is missing", keyIndex >= 0)
        assertTrue("drawOne is not called with the keyed src", drawIndex >= 0)
        assertTrue("the key must precede the draw call", keyIndex < drawIndex)
        // The mask clip runs inside drawOne, i.e. strictly after the key.
        val drawOne = body.substring(drawAtEnd)
        assertTrue(
            "drawOne must clip through the shared mask renderer",
            drawOne.contains("OverlayMaskRenderer.buildPath(") ||
                drawOne.contains("OverlayMaskRenderer.applySoftMask(")
        )
    }

    @Test
    fun `export never mutates the decoded frame`() {
        // The frames belong to the decoder and are handed out again for the
        // next timestamp; writing into one would corrupt every later frame.
        val body = codeLinesOnly(read(compositor))
        assertTrue(
            "the key must write into a scratch bitmap, not the source",
            body.contains("fun keyed(src: Bitmap): Bitmap?")
        )
        assertTrue(
            "the scratch must be allocated per size",
            body.contains("Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)")
        )
    }

    @Test
    fun `export falls back to the un-keyed frame when keying is impossible`() {
        // A hardware bitmap throws from `getPixels`. Returning null there is
        // correct; the caller must then draw the original frame rather than
        // drawing nothing.
        val body = codeLinesOnly(read(compositor))
        assertTrue(
            "drawAt must fall back to the decoded frame",
            body.contains("entry.keyed(decoded) ?: decoded")
        )
    }

    @Test
    fun `export recycles its scratch when the renderer closes`() {
        val body = codeLinesOnly(read(compositor))
        val closeIndex = body.indexOf("override fun close()")
        assertTrue("close is missing", closeIndex >= 0)
        val closeBody = body.substring(closeIndex, closeIndex + 1200)
        assertTrue(
            "the chroma scratch must be recycled on close",
            closeBody.contains("keyScratch?.recycle()")
        )
    }

    @Test
    fun `the overlay pass stays last in the export chain`() {
        // Pre-existing invariant from the PiP work, restated because the
        // chroma key runs inside that pass: matting and reframe must have
        // already rewritten the source before overlays composite over it.
        val body = codeLinesOnly(read(exportHooks))
        val media = body.indexOf("burnMediaOverlays(segment, uri)")
        assertTrue("burnMediaOverlays call is missing", media >= 0)
        assertTrue(
            "the overlay pass must come after the matting pass",
            media > body.indexOf("compositeMatting(")
        )
    }

    @Test
    fun `the overlay list carries the settings onto the render graph`() {
        // No new `RenderSegment` field is needed: the segment already
        // carries whole `VideoOverlay` objects, so the chroma settings ride
        // along. Asserted, not assumed — if a future change flattens the
        // list into ids, chroma key would silently stop exporting.
        val segment = codeLinesOnly(read(renderSegment))
        assertTrue(
            "RenderSegment must carry VideoOverlay objects, not ids",
            segment.contains("List<com.aicreatorstudio.app.model.VideoOverlay>")
        )
        val builder = codeLinesOnly(read(graphBuilder))
        assertTrue(
            "the graph builder must pass the overlay list through untouched",
            builder.contains("mediaOverlays)")
        )
        val hooks = codeLinesOnly(read(exportHooks))
        assertTrue(
            "burnMediaOverlays must hand that list to the compositor",
            hooks.contains("overlays = segment.mediaOverlays")
        )
    }

    // ---- panel wiring -------------------------------------------------

    @Test
    fun `the screen resolves the target and hands the panel its settings`() {
        // Guard A: the panel is *controlled*, so it must receive the
        // targeted overlay's stored settings rather than the model
        // default — which is what a copy-paste of the mask branch with the
        // wrong field would produce.
        val body = codeLinesOnly(read(editorScreen))
        val branch = body.indexOf("ToolPanel.CHROMA_KEY -> {")
        assertTrue("the CHROMA_KEY dispatch branch is missing", branch >= 0)
        val window = body.substring(branch, (branch + 2000).coerceAtMost(body.length))
        assertTrue(
            "the panel must be given the target's settings",
            window.contains("keyTarget?.chromaKeySettings")
        )
        assertTrue(
            "the panel must be given a target label",
            window.contains("targetLabel =")
        )
        assertTrue(
            "the panel must be told when there is no target",
            window.contains("hasTarget = keyTarget != null")
        )
        assertTrue("the live path is missing", window.contains("onSettingsChanged = {"))
        assertTrue("the commit path is missing", window.contains("onSettingsCommitted = {"))
        assertTrue(
            "the live path must push no history",
            window.contains("onOverlayChromaChanged(it, settings, false)")
        )
        assertTrue(
            "the commit path must push history",
            window.contains("onOverlayChromaChanged(it, settings, true)")
        )
    }

    @Test
    fun `the panel branch and the sheet title are both present`() {
        // A dispatch without a title renders a blank sheet; a title without
        // a dispatch is an unreachable string.
        val body = codeLinesOnly(read(editorScreen))
        assertTrue(body.contains("ToolPanel.CHROMA_KEY -> {"))
        assertTrue(body.contains("ToolPanel.CHROMA_KEY -> \"پرده سبز\""))
    }

    @Test
    fun `the screen resolves the target the same way the preview does`() {
        // Mask and key compose at render time, so the two panels must
        // resolve the SAME overlay. Pins the shared helper rather than a
        // second, hand-rolled selection rule.
        val body = codeLinesOnly(read(editorScreen))
        assertTrue(
            "the chroma panel must resolve through OverlayPipGeometry.resolveTarget",
            body.contains("OverlayPipGeometry.resolveTarget(")
        )
        assertTrue("editorMs must drive the overlap test", body.contains("editorMs = currentPositionMs"))
        assertTrue(
            "the clip id must be offered as a fallback target",
            body.contains("clipId = selectedClip?.id")
        )
    }

    @Test
    fun `the callback is threaded through every pass-through site`() {
        // The recurring bug in this codebase: a parameter added to three of
        // four call sites compiles (the default swallows the fourth) and the
        // panel silently stops responding.
        val body = codeLinesOnly(read(editorScreen))
        assertTrue(
            "the outer signature must declare onOverlayChromaChanged",
            body.contains("onOverlayChromaChanged:")
        )
        assertTrue(
            "the ViewModel wiring must call setOverlayChromaKey",
            body.contains("viewModel.setOverlayChromaKey(")
        )
        val declarations = Regex("""onOverlayChromaChanged\s*:""").findAll(body).count()
        assertTrue(
            "expected at least two parameter declarations, found $declarations",
            declarations >= 2
        )
        val bindings = Regex("""onOverlayChromaChanged\s*=\s*onOverlayChromaChanged""")
            .findAll(body).count()
        assertTrue("expected the pass-through binding, found $bindings", bindings >= 1)
    }

    @Test
    fun `the viewmodel action sanitises, guards and gates history`() {
        val body = codeLinesOnly(read(editorViewModel))
        val start = body.indexOf("fun setOverlayChromaKey(")
        assertTrue("setOverlayChromaKey is missing", start >= 0)
        val window = body.substring(start, (start + 1400).coerceAtMost(body.length))
        assertTrue(
            "the action must sanitise before storing",
            window.contains("ChromaKeyEngine.sanitize(settings)")
        )
        assertTrue(
            "the action must no-op on an unchanged value",
            window.contains("current.chromaKeySettings == next")
        )
        assertTrue(
            "the action must push history only on request",
            window.contains("if (pushHistory) historyDelegate.push()")
        )
        assertTrue(
            "the action must persist only on request",
            window.contains("if (pushHistory) persistProjectMeta()")
        )
        // The live/commit contract is fully covered by the two positive
        // assertions above. A *negative* assertion here ("the update must
        // not push") was tried and removed: `historyDelegate.push()\n`
        // `_uiState.update` matches the guarded form just as well, so it
        // could not tell a push hang off `if (pushHistory)` from a bare one
        // and therefore proved nothing.
    }

    @Test
    fun `the chip is in the clip toolbar and nowhere else`() {
        // Same rule as the mask chip: a key belongs to one overlay, so a
        // root-level entry point would imply a project-wide effect.
        val body = codeLinesOnly(read(toolbarSpec))
        assertTrue("the chip must exist", body.contains("id = \"chroma_key\""))
        assertTrue(
            "the chip must open the chroma key panel",
            body.contains("toolPanel = ToolPanel.CHROMA_KEY")
        )
        val rootRow = body.indexOf("val RootChips")
        val clipRow = body.indexOf("val ClipChips")
        assertTrue("RootChips missing", rootRow >= 0)
        assertTrue("ClipChips missing", clipRow >= 0)
        assertTrue(
            "the chip must be a member of ClipChips",
            body.substring(clipRow).contains("ChromaKeyChip,")
        )
        assertFalse(
            "the chip must not be in the root row",
            body.substring(rootRow, clipRow).contains("ChromaKeyChip")
        )
    }

    @Test
    fun `the sheet is sized as a gallery`() {
        // Three sliders plus a preset row is a gallery, not a compact
        // sheet. Its own branch rather than one more name in a grouped
        // branch — see the comment in `SubPanelHeight.kt`.
        val body = codeLinesOnly(read(subPanelHeight))
        assertTrue(
            "CHROMA_KEY must be classified GALLERY",
            body.contains("ToolPanel.CHROMA_KEY -> Kind.GALLERY")
        )
        assertTrue(
            "the VOICE_CHANGER pin must still hold",
            body.contains("ToolPanel.VOICE_CHANGER -> Kind.GALLERY")
        )
        assertTrue(
            "the OVERLAY_MASK pin must still hold",
            body.contains("ToolPanel.OVERLAY_MASK -> Kind.GALLERY")
        )
    }

    @Test
    fun `the model defaults are the values the codec treats as unwritten`() {
        // A default that differed from what `EditorStateJson` skips would
        // grow every saved project on first save.
        val model = codeLinesOnly(read(chromaModel))
        assertTrue(
            "the default key colour must be green",
            model.contains("DEFAULT_KEY_COLOR_ARGB: Int = -16711936")
        )
        assertTrue(
            "a blue preset must exist for the panel's chips",
            model.contains("BLUE_KEY_COLOR_ARGB: Int = -16776961")
        )
        assertTrue(
            "isIdentity must not be simply !isEnabled",
            model.contains("isIdentity: Boolean")
        )
    }

    @Test
    fun `the panel declares the enable toggle and the three sliders`() {
        // Step 5 of the request, asserted as shape. A missing slider is
        // reachable only through the UI, which no JVM test renders.
        val body = codeLinesOnly(read(chromaPanel))
        assertTrue("the enable switch is missing", body.contains("Switch("))
        assertTrue(
            "the enable switch must be bound to isEnabled",
            body.contains("checked = settings.isEnabled")
        )
        assertEquals(
            "three sliders expected", 3,
            Regex("""(?m)^\s+LabeledSlider\(""").findAll(body).count()
        )
        for (label in listOf("حساسیت", "نرمی لبه", "حذف هاله سبز")) {
            assertTrue("the '$label' slider is missing", body.contains(label))
        }
        assertTrue(
            "the panel must iterate the preset enum",
            body.contains("ChromaKeyPreset.entries")
        )
        assertTrue("a custom chip must exist", body.contains("دلخواه"))
        assertTrue("the reset button is missing", body.contains("بازنشانی"))
        assertTrue(
            "a slider must commit on release",
            body.contains("onValueChangeFinished = { onCommit(value) }")
        )
    }

    @Test
    fun `the panel edits the selected overlay's settings, not a copy`() {
        // Controlled, not stateful: the panel holds no `remember` of the
        // settings, so there is exactly one value and the preview clip,
        // panel and exported frame cannot disagree.
        val body = codeLinesOnly(read(chromaPanel))
        assertFalse(
            "the panel must not hold its own copy of the settings",
            body.contains("mutableStateOf<ChromaKeySettings>")
        )
        assertTrue(
            "the panel must take the settings as a parameter",
            body.contains("settings: ChromaKeySettings")
        )
        assertTrue("the panel must report the target label", body.contains("targetLabel"))
        assertTrue(
            "the panel must return early when there is no target",
            body.contains("if (!hasTarget)")
        )
    }

    // ---- persistence ------------------------------------------------

    @Test
    fun `the codec writes each key only when it differs from the default`() {
        val body = codeLinesOnly(read(stateJson))
        assertTrue(
            "the chroma block must be gated on isIdentity",
            body.contains("if (!chroma.isIdentity)")
        )
        for (key in listOf("chromaOn", "chromaColor", "chromaSimilarity", "chromaSmoothness", "chromaSpill")) {
            assertTrue("the codec must write $key", body.contains("\"$key\""))
        }
        assertTrue(
            "the codec must sanitise on decode",
            body.contains("ChromaKeyEngine.sanitize(")
        )
    }

    @Test
    fun `the overlay model carries the settings next to the mask`() {
        // Not a proof that masking and keying compose — that is the export
        // ordering test above — but a proof that the two settings live on
        // the same object, so no caller can hold one and not the other.
        val body = codeLinesOnly(read(uiState))
        assertTrue(
            "VideoOverlay must carry chromaKeySettings",
            body.contains("chromaKeySettings: ChromaKeySettings")
        )
        assertTrue(
            "the chroma panel must be a sibling of the mask panel",
            body.contains("CHROMA_KEY")
        )
    }
}
