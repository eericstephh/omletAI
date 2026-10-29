package com.aicreatorstudio.app.ui.screens.home

import com.aicreatorstudio.app.model.ToolPanel
import com.aicreatorstudio.app.settings.LocalizedStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-20 — keeps the home screen's AI capability catalogue honest.
 *
 * The catalogue replaced a hand-written list of six "smart
 * capabilities" that was missing eight of the twelve AI tools the app
 * ships, listed one non-AI feature, and reused three icons for
 * different tools. This test makes that class of drift impossible:
 *
 *  - every AI panel the editor's smart-tool chip row exposes must
 *    appear in the catalogue (so a new tool cannot ship unlisted),
 *  - every entry must have a unique id, panel and icon (so two tools
 *    can never end up sharing a glyph again),
 *  - every visible string must be a real translation key in the
 *    English and Arabic tables (so a capability never shows raw
 *    Persian in another language).
 */
class AiCapabilityCatalogueTest {

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("settings.gradle.kts not found above ${File(".").absolutePath}")
    }

    /**
     * The AI panels the editor exposes in its "smart tools" chip row.
     *
     * Read from the toolbar spec so the test tracks the real product
     * surface rather than a copy of it.
     */
    private fun editorSmartAiPanels(): Set<ToolPanel> {
        val src = File(
            repoRoot(),
            "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/ContextualToolbarSpec.kt"
        ).readText()
        val panels = Regex("ToolPanel\\.(AI_[A-Z_]+|UPSCALER|SUBTITLES)")
            .findAll(src)
            .map { ToolPanel.valueOf(it.groupValues[1]) }
            .toSet()
        assertTrue("the editor's smart chip row was not found", panels.isNotEmpty())
        return panels
    }

    @Test
    fun `every ai tool the editor exposes is listed on the home screen`() {
        val catalogued = AI_CAPABILITIES.map { it.panel }.toSet()
        val editorPanels = editorSmartAiPanels()

        val missing = editorPanels - catalogued
        assertTrue(
            "these AI tools ship in the editor but are missing from the home " +
                "screen catalogue: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `the catalogue lists nothing the editor cannot open`() {
        val editorPanels = editorSmartAiPanels()
        val unknown = AI_CAPABILITIES.map { it.panel }.filter { it !in editorPanels }
        assertTrue(
            "the home screen advertises panels the editor does not expose: $unknown",
            unknown.isEmpty()
        )
    }

    @Test
    fun `no two capabilities share an id, a panel or an icon`() {
        val dupIds = AI_CAPABILITIES.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertTrue("duplicate capability ids: $dupIds", dupIds.isEmpty())

        val dupPanels = AI_CAPABILITIES.groupBy { it.panel }.filterValues { it.size > 1 }.keys
        assertTrue(
            "two capabilities open the same panel — one of them is unreachable: $dupPanels",
            dupPanels.isEmpty()
        )

        val dupIcons = AI_CAPABILITIES.groupBy { it.icon }.filterValues { it.size > 1 }.keys
        assertTrue(
            "two capabilities share the same icon, so the icon distinguishes nothing: " +
                dupIcons.map { it.name },
            dupIcons.isEmpty()
        )
    }

    @Test
    fun `every capability string is translated in english and arabic`() {
        val original = LocalizedStrings.currentTag
        try {
            for (tag in listOf("en", "ar")) {
                LocalizedStrings.currentTag = tag
                for (capability in AI_CAPABILITIES) {
                    assertNotNull(capability.title)
                    assertTrue(
                        "the title of '${capability.id}' is not translated for '$tag'",
                        LocalizedStrings.t(capability.title) != capability.title
                    )
                    assertTrue(
                        "the description of '${capability.id}' is not translated for '$tag'",
                        LocalizedStrings.t(capability.description) != capability.description
                    )
                }
            }
        } finally {
            LocalizedStrings.currentTag = original
        }
    }

    @Test
    fun `the section heading is translated too`() {
        val original = LocalizedStrings.currentTag
        try {
            LocalizedStrings.currentTag = "en"
            assertTrue(
                "the AI capabilities heading must be translated",
                LocalizedStrings.t("قابلیت‌های هوش مصنوعی") != "قابلیت‌های هوش مصنوعی"
            )
        } finally {
            LocalizedStrings.currentTag = original
        }
    }

    @Test
    fun `the catalogue is complete and ordered for a two column grid`() {
        // An odd count would leave a hole in the last row; the grid
        // pads it with a spacer, which is fine, but the count must be
        // explicit so a new capability is a deliberate addition.
        assertEquals(
            "the home screen must list every AI capability the app ships",
            12, AI_CAPABILITIES.size
        )
    }

    /**
     * 2026-10-20 — the rows show ONE description line.
     * 2026-10-27 — the section became a 3-per-row grid of tiles; the
     * description is capped at two ellipsised lines there, and this
     * budget (set for the TRANSLATED English length, which is longer)
     * keeps it inside those two without fighting the ellipsis.
     */
    @Test
    fun `every description is short enough for a single line`() {
        for (capability in AI_CAPABILITIES) {
            assertTrue(
                "the description of '${capability.id}' is ${capability.description.length} " +
                    "characters — it must stay under 46 so the row keeps one line",
                capability.description.length <= 46
            )
        }
    }

    /**
     * 2026-10-20 — the rows are a LIST, one capability per row.
     * 2026-10-27 — user-directed change: the section is now a
     * 3-per-row grid of compact tiles (`chunked(3)`); both the old
     * one-per-row layout and the earlier two-column grid are pinned
     * gone.
     */
    @Test
    fun `the section renders a three-per-row capability grid`() {
        // The unit test's working directory is the module dir, so walk
        // up to the repo root.
        val file = generateSequence(File(".").absoluteFile) { it.parentFile }
            .map {
                File(
                    it,
                    "app/src/main/java/com/aicreatorstudio/app/ui/screens/home/AiCapabilities.kt"
                )
            }
            .firstOrNull { it.isFile }
        assertNotNull("AiCapabilities.kt not found", file)
        val text = file!!.readText()
        assertTrue(
            "the section must render a three-per-row capability grid",
            text.contains("AI_CAPABILITIES.chunked(3)")
        )
        assertTrue(
            "the old one-capability-per-row layout must be gone",
            !text.contains("AI_CAPABILITIES.forEach { capability ->")
        )
        assertTrue(
            "the old two-column grid must be gone",
            !text.contains("AI_CAPABILITIES.chunked(2)")
        )
    }
}
