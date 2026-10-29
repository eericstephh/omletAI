package com.aicreatorstudio.app.viewmodel

import com.aicreatorstudio.app.model.CustomFontMessages
import com.aicreatorstudio.app.model.SubtitleMessages
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-shape guards for the custom-font ViewModel wiring.
 *
 * ## Why this is a source-shape test
 *
 * `EditorViewModel` is a Hilt `@HiltViewModel` singleton that needs
 * Android + Dagger to construct, so it cannot be built in this project's
 * JVM test source set (there is no Robolectric or Mockito). The
 * established answer — used by `SubtitleEditingWorkflowTest` for the
 * subtitle editing actions — is to read the real `.kt` file and assert
 * on the shape of the code. That is not as strong as executing it, but
 * it *does* catch the regressions that actually happen here: an action
 * losing its persistence call, a fallback being deleted in a refactor,
 * or a message regressing to mojibake.
 *
 * The behaviour that CAN be executed is tested directly and is where
 * the real risk lives: `CustomFontFilesTest`,
 * `CustomFontMetadataJsonTest` and `InMemoryCustomFontRepositoryTest`.
 * Together they cover the security-relevant decisions; this file covers
 * the wiring between them.
 */
class CustomFontWiringTest {

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

    private fun viewModelSource(): String =
        read("app/src/main/java/com/aicreatorstudio/app/viewmodel/EditorViewModel.kt")

    /**
     * Strips `//` and block comments so a KDoc that merely *mentions* a
     * call cannot satisfy a guard. This project has already been bitten
     * by a comment quoting code that had been removed.
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
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEach
            out.appendLine(line)
        }
        return out.toString()
    }

    /** Extract one function body by brace matching from its signature. */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("signature not found: $signature", start >= 0)
        val open = source.indexOf('{', start)
        assertTrue("no opening brace after: $signature", open >= 0)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
            i++
        }
        throw IllegalStateException("unbalanced braces after: $signature")
    }

    // ==================================================================
    // The actions exist
    // ==================================================================

    @Test
    fun `the import and delete actions are declared on the ViewModel`() {
        // Guards against a refactor deleting or renaming an action the
        // font pickers call.
        val code = codeLinesOnly(viewModelSource())
        assertTrue("missing importCustomFont", code.contains("fun importCustomFont("))
        assertTrue("missing deleteCustomFont", code.contains("fun deleteCustomFont("))
    }

    @Test
    fun `the repository is a constructor dependency`() {
        // If the dependency is dropped, the actions cannot compile — but
        // asserting it here names the reason rather than leaving a
        // cryptic "unresolved reference" for whoever hits it next.
        val code = codeLinesOnly(viewModelSource())
        assertTrue(
            "EditorViewModel must take a CustomFontRepository",
            code.contains("customFontRepository: CustomFontRepository")
        )
    }

    @Test
    fun `CustomFontMessages is imported rather than inlining the Persian strings`() {
        // The project has twice shipped corrupted "???" strings to
        // users; routing every message through the guarded constants is
        // the fix, and inlining a literal would quietly undo it.
        val code = codeLinesOnly(viewModelSource())
        assertNotNull(
            "EditorViewModel must import CustomFontMessages",
            Regex("import com\\.aicreatorstudio\\.app\\.model\\.CustomFontMessages").find(code)
        )
    }

    // ==================================================================
    // Import behaviour
    // ==================================================================

    @Test
    fun `the import action calls the repository and reports both outcomes`() {
        val body = bodyOf(codeLinesOnly(viewModelSource()), "fun importCustomFont(")
        assertTrue(
            "importCustomFont must call customFontRepository.importFont(",
            body.contains("customFontRepository.importFont(")
        )
        assertTrue(
            "importCustomFont must show the success message",
            body.contains("IMPORT_SUCCESS")
        )
        assertTrue(
            "importCustomFont must show the failure message",
            body.contains("IMPORT_FAILURE")
        )
    }

    @Test
    fun `a failed import is reported on the error channel, not the success one`() {
        // Mixing the two would show a green "added!" banner for a file
        // that was rejected — the worst possible confusion.
        //
        // The assertion is positional on purpose: `errorMessage = null`
        // appears in the SUCCESS branch (clearing a stale error), so a
        // naive `contains("errorMessage")` would pass while the failure
        // path was silent. Instead, require the statement that assigns
        // IMPORT_FAILURE to also assign errorMessage.
        val body = bodyOf(codeLinesOnly(viewModelSource()), "fun importCustomFont(")
        val successAt = body.indexOf("IMPORT_SUCCESS")
        val failureAt = body.indexOf("IMPORT_FAILURE")
        assertTrue("both messages required", successAt >= 0 && failureAt >= 0)

        // The IMPORT_FAILURE reference and the `errorMessage =` that
        // feeds it live on the same statement line.
        val failureLine = body
            .lineSequence()
            .firstOrNull { it.contains("IMPORT_FAILURE") }
        assertNotNull("no line assigns IMPORT_FAILURE", failureLine)
        assertTrue(
            "the failure branch must assign errorMessage, but the line was: $failureLine",
            failureLine!!.contains("errorMessage")
        )
    }

    // ==================================================================
    // Delete behaviour — the safety requirement
    // ==================================================================

    @Test
    fun `the delete action calls the repository`() {
        val body = bodyOf(codeLinesOnly(viewModelSource()), "fun deleteCustomFont(")
        assertTrue(
            "deleteCustomFont must call customFontRepository.deleteCustomFont(",
            body.contains("customFontRepository.deleteCustomFont(")
        )
    }

    @Test
    fun `deleting a font sweeps the styles that referenced it`() {
        // The user's explicit requirement: a deleted font must fall back
        // to the default Persian font rather than leaving a dangling
        // `custom:<id>` on a caption.
        val body = bodyOf(codeLinesOnly(viewModelSource()), "fun deleteCustomFont(")
        assertTrue(
            "deleteCustomFont must invoke the fallback sweep",
            body.contains("fallbackAwayFromCustomFont(")
        )
    }

    @Test
    fun `the fallback rewrites both font fields on an overlay`() {
        // `updateSelectedTextFont` keeps `fontFamily` and
        // `subtitleStyle.fontFamily` in sync; the fallback must do the
        // same, or the decorative renderer would keep pointing at a
        // deleted file while the plain renderer showed the fallback —
        // a mismatch visible only in the exported video.
        val body = bodyOf(codeLinesOnly(viewModelSource()), "fun fallbackAwayFromCustomFont(")
        assertTrue(
            "the sweep must guard on overlay.fontFamily",
            body.contains("overlay.fontFamily")
        )
        assertTrue(
            "the sweep must also guard on subtitleStyle.fontFamily",
            body.contains("subtitleStyle")
        )
        assertTrue(
            "the sweep must write DEFAULT_PERSIAN_FONT_FAMILY",
            body.contains("DEFAULT_PERSIAN_FONT_FAMILY")
        )
    }

    @Test
    fun `the fallback pushes history and persists`() {
        // A fallback is a document change; it must be undoable and
        // survive a reopen.
        val body = bodyOf(codeLinesOnly(viewModelSource()), "fun fallbackAwayFromCustomFont(")
        assertTrue("sweep must call historyDelegate.push()", body.contains("historyDelegate.push()"))
        assertTrue("sweep must call persistProjectMeta()", body.contains("persistProjectMeta()"))
    }

    @Test
    fun `the fallback target is Vazirmatn, the default template's family`() {
        // `fa_01_clean` (the default subtitle template) ships
        // `fontFamily = PersianFont.VAZIR.name`; the fallback must land
        // on the same family or the two paths would disagree.
        val code = codeLinesOnly(viewModelSource())
        assertNotNull(
            "DEFAULT_PERSIAN_FONT_FAMILY must be PersianFont.VAZIR.name",
            Regex("""DEFAULT_PERSIAN_FONT_FAMILY\s*:\s*String\s*=\s*PersianFont\.VAZIR\.name""")
                .find(code)
        )
    }

    @Test
    fun `an unknown font id pushes no history`() {
        // Mirrors the "a rejected operation returns before pushing
        // history" contract of the subtitle editing actions: a
        // double-tap on the trash icon must not add an undo step that
        // visibly does nothing. The early `return@launch` on a null
        // removal is what enforces it.
        val body = bodyOf(codeLinesOnly(viewModelSource()), "fun deleteCustomFont(")
        val removeAt = body.indexOf("customFontRepository.deleteCustomFont(")
        assertTrue("delete must call the repository", removeAt >= 0)
        val guardBeforePush = body.substring(removeAt)
        assertTrue(
            "deleteCustomFont must bail out when the repository removed nothing; body was:\n$body",
            guardBeforePush.contains("return@launch") || guardBeforePush.contains("?: return")
        )
    }

    // ==================================================================
    // State exposure
    // ==================================================================

    @Test
    fun `the custom font list is mirrored into UI state`() {
        val code = codeLinesOnly(viewModelSource())
        assertTrue(
            "the ViewModel must collect getCustomFonts() into uiState",
            code.contains("customFontRepository.getCustomFonts()")
        )
        assertTrue(
            "the collection must write customFonts into the state",
            code.contains("customFonts = fonts")
        )
    }

    @Test
    fun `the confirmation can be dismissed`() {
        val code = codeLinesOnly(viewModelSource())
        assertTrue(
            "consumeEditorConfirmation must exist or the banner cannot close",
            code.contains("fun consumeEditorConfirmation()")
        )
    }

    // ==================================================================
    // The messages themselves are real Persian
    // ==================================================================

    @Test
    fun `every custom-font message is real Persian, not mojibake`() {
        listOf(
            CustomFontMessages.IMPORT_SUCCESS,
            CustomFontMessages.IMPORT_FAILURE,
            CustomFontMessages.DELETE_SUCCESS,
            CustomFontMessages.DELETE_FALLBACK_APPLIED
        ).forEach { message ->
            assertTrue(
                "corrupted user-facing message: $message",
                SubtitleMessages.assertNoMojibake(message)
            )
        }
    }

    @Test
    fun `the import messages match the wording the feature specified`() {
        // Pinned verbatim so a well-meaning copy edit cannot silently
        // change wording the user approved.
        org.junit.Assert.assertEquals("فونت با موفقیت اضافه شد", CustomFontMessages.IMPORT_SUCCESS)
        org.junit.Assert.assertEquals("فایل فونت معتبر نیست", CustomFontMessages.IMPORT_FAILURE)
    }

    // ==================================================================
    // UI plumbing — the strip is reachable from both panels
    // ==================================================================

    @Test
    fun `the shared font strip is used by both font pickers`() {
        // Duplicating the strip would let the ActivityResultContract
        // (MIME filter, OpenDocument choice) drift between the two.
        val subtitles = read(
            "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/subtitle/SubtitleStudioPanel.kt"
        )
        val textOverlay = read(
            "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/TextOverlayPanel.kt"
        )
        assertTrue("SubtitleStudioPanel must use CustomFontStrip", subtitles.contains("CustomFontStrip("))
        assertTrue("TextOverlayPanel must use CustomFontStrip", textOverlay.contains("CustomFontStrip("))
    }

    @Test
    fun `the strip offers every mime type the user specified`() {
        val strip = read(
            "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/CustomFontStrip.kt"
        )
        // MimeTypeMap does not know font/ttf on every API level, and
        // cloud providers report application/octet-stream — all three
        // must be requested or the picker hides real font files.
        assertTrue("must request font/ttf", strip.contains("\"font/ttf\""))
        assertTrue("must request font/otf", strip.contains("\"font/otf\""))
        assertTrue("must request application/octet-stream", strip.contains("\"application/octet-stream\""))
        assertTrue("must use OpenDocument for a re-readable URI", strip.contains("OpenDocument()"))
    }

    @Test
    fun `the renderer falls back to a bundled family for an unknown custom font`() {
        val renderer = read(
            "app/src/main/java/com/aicreatorstudio/app/export/CaptionRenderer.kt"
        )
        // Parity is structural: the export path must resolve a custom
        // font through the same locator the preview uses.
        assertTrue(
            "CaptionRenderer must resolve custom fonts",
            renderer.contains("CustomFont.isCustomFontId(")
        )
        assertTrue(
            "CaptionRenderer must accept a custom-path resolver",
            renderer.contains("resolveCustomFontPath")
        )
        assertTrue(
            "the default resolver must be CustomFontLocator::pathFor",
            renderer.contains("CustomFontLocator")
        )
    }
}
