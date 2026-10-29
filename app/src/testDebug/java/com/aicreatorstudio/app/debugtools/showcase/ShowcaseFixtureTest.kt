package com.aicreatorstudio.app.debugtools.showcase

import com.aicreatorstudio.app.model.BuiltInSubtitleTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PHASE 5 — tests for the debug showcase's pure data layer.
 *
 * These live in `src/testDebug` (the debug variant's unit-test source set)
 * because the code under test lives in `src/debug`. `:app:testReleaseUnitTest`
 * therefore neither compiles nor needs this file, which is exactly the
 * isolation the showcase is supposed to have.
 *
 * The tests cover the two things that can silently rot in a showcase:
 * the Persian fixture actually containing the characters it claims to, and
 * the registry being derived from the real sources rather than a copy.
 */
class ShowcaseFixtureTest {

    private val cues = ShowcaseFixture.cues

    // ---------------------------------------------------------------
    // 1. The fixture really is the Persian stress case it claims to be
    // ---------------------------------------------------------------

    @Test
    fun `there is exactly one fixture per caption variant`() {
        assertEquals(ShowcaseCaption.entries.size, cues.size)
        assertEquals(
            ShowcaseCaption.entries.toList(),
            cues.map { it.variant }
        )
        ShowcaseCaption.entries.forEach {
            assertNotNull(ShowcaseFixture.cueFor(it))
        }
    }

    @Test
    fun `the fixtures cover one, two and three line captions`() {
        assertEquals(1, ShowcaseFixture.cueFor(ShowcaseCaption.SHORT).lineCount)
        assertEquals(2, ShowcaseFixture.cueFor(ShowcaseCaption.LONG).lineCount)
        assertEquals(3, ShowcaseFixture.cueFor(ShowcaseCaption.MULTILINE).lineCount)
    }

    @Test
    fun `caption text is derived from the word list and never drifts`() {
        // text and words must be the same tokens in the same order, or
        // KaraokeRanges highlights the wrong instance of a repeated word.
        cues.forEach { cue ->
            assertEquals(
                "${cue.variant} word count",
                cue.words.size,
                cue.tokenCount
            )
            assertEquals(
                "${cue.variant} text must be its tokens joined by whitespace",
                cue.words.map { it.text },
                cue.text.split(Regex("\\s+"))
            )
        }
    }

    @Test
    fun `every fixture carries a real zero width non joiner`() {
        // The half-space is invisible, so this asserts the CODE POINT, not
        // the appearance. It is written as an escape in the fixture for
        // exactly this reason.
        assertTrue(
            "ZWNJ helper is not a single U+200C",
            ShowcaseFixture.ZWNJ == "\u200C" && ShowcaseFixture.ZWNJ.length == 1
        )
        val perCue = mapOf(
            ShowcaseCaption.SHORT to listOf(0),
            ShowcaseCaption.LONG to listOf(0, 2, 9, 12),
            ShowcaseCaption.MULTILINE to listOf(3)
        )
        perCue.forEach { (variant, indices) ->
            val cue = ShowcaseFixture.cueFor(variant)
            indices.forEach { i ->
                val token = cue.words[i].text
                assertTrue(
                    "${variant} token $i («$token») has no ZWNJ (U+200C)",
                    token.contains('\u200C')
                )
            }
            // And they survive into the assembled caption text.
            assertTrue("${variant} caption text lost its half-spaces", cue.text.contains('\u200C'))
        }
    }

    @Test
    fun `the fixtures cover persian digits, latin text and punctuation`() {
        val all = cues.joinToString(" ") { it.text }

        val persianDigits = all.count { it in '\u06F0'..'\u06F9' }
        assertTrue("expected Persian-indic digits in the fixture, found $persianDigits", persianDigits >= 5)

        assertTrue(
            "the mixed-direction Latin token is missing",
            cues.any { cue -> cue.words.any { it.text == "ChatGPT" } }
        )

        listOf("،", "!", ".", "«", "»", "(", ")", "٪").forEach { mark ->
            assertTrue("fixture lost the punctuation mark «$mark»", all.contains(mark))
        }
    }

    @Test
    fun `the fixture uses persian letter forms only, with no arabic look alikes`() {
        val all = cues.joinToString(" ") { it.text }
        // Persian ی/ک (U+06CC/U+06A9) are required; the Arabic ي/ك
        // (U+064A/U+0643) are the classic cross-keyboard corruption and
        // must NOT appear.
        assertTrue("no Persian yeh U+06CC in the fixture", all.contains('\u06CC'))
        assertTrue("no Persian kaf U+06A9 in the fixture", all.contains('\u06A9'))
        assertTrue("Arabic yeh U+064A leaked into the fixture", !all.contains('\u064A'))
        assertTrue("Arabic kaf U+0643 leaked into the fixture", !all.contains('\u0643'))
        assertTrue("the fixture contains U+FFFD (encoding damage)", !all.contains('\uFFFD'))
    }

    // ---------------------------------------------------------------
    // 2. Timing: realistic and explicitly NOT an equal division
    // ---------------------------------------------------------------

    @Test
    fun `word timings are ordered, in window and non overlapping`() {
        cues.forEach { cue ->
            var previousEnd = Long.MIN_VALUE
            cue.words.forEach { w ->
                assertTrue("${cue.variant} «${w.text}» start >= end", w.startTimeMs < w.endTimeMs)
                assertTrue(
                    "${cue.variant} «${w.text}» starts before the previous word ends",
                    w.startTimeMs >= previousEnd
                )
                assertTrue(
                    "${cue.variant} «${w.text}» ends after the fixture window",
                    w.endTimeMs <= cue.durationMs
                )
                previousEnd = w.endTimeMs
            }
            assertTrue("${cue.variant} has no window", cue.durationMs > 0L)
        }
    }

    @Test
    fun `timings are uneven, so no synthetic equal division is being demoed`() {
        cues.forEach { cue ->
            val durations = cue.words.map { it.endTimeMs - it.startTimeMs }
            assertTrue(
                "${cue.variant} durations are all identical ($durations) — that is a " +
                    "fabricated equal division, which this project forbids",
                durations.toSet().size > 1
            )
        }
    }

    @Test
    fun `active word lookup reports the word being spoken and null between words`() {
        val cue = ShowcaseFixture.cueFor(ShowcaseCaption.SHORT)
        cue.words.forEachIndexed { index, w ->
            assertEquals("inside «${w.text}»", index, cue.activeTokenIndex(w.startTimeMs))
            assertEquals("just before the end of «${w.text}»", index, cue.activeTokenIndex(w.endTimeMs - 1))
        }
        // Between word 0 (ends 560) and word 1 (starts 620) there is a gap:
        // that is the INACTIVE state the showcase must be able to display.
        assertNull(cue.activeTokenIndex(590L))
        assertNull("before the first word", cue.activeTokenIndex(0L))
        assertNull("after the last word", cue.activeTokenIndex(cue.durationMs))
    }

    // ---------------------------------------------------------------
    // 3. The registry is derived from the production sources
    // ---------------------------------------------------------------

    @Test
    fun `the registry keeps the json entry and drops the duplicate built in`() {
        // A "json catalog" that deliberately shadows the first six built-ins
        // with recognisable names, mimicking the six fa_pro_* id collisions.
        val json = BuiltInSubtitleTemplates.ALL.take(6).map { it.copy(name = "JSON ${it.id}") }
        val combined = ShowcaseFixture.combinedRegistry(json)

        assertEquals(
            "the six shadowed built-ins must not both survive",
            BuiltInSubtitleTemplates.ALL.size,
            combined.size
        )
        assertEquals(
            "duplicate ids would render two identical cards",
            combined.size,
            combined.map { it.id }.toSet().size
        )
        assertEquals(
            "the JSON entry must win: all six keep their JSON name",
            6,
            combined.count { it.name.startsWith("JSON ") }
        )
        assertEquals(
            "JSON entries keep catalog order and come first",
            json.map { it.id },
            combined.take(6).map { it.id }
        )
        assertEquals(
            "the non-shadowed built-ins are appended after the JSON list",
            BuiltInSubtitleTemplates.ALL.drop(6).map { it.id },
            combined.drop(6).map { it.id }
        )
    }

    @Test
    fun `an empty json catalog still yields the hand built catalogue`() {
        assertEquals(
            BuiltInSubtitleTemplates.ALL.map { it.id },
            ShowcaseFixture.combinedRegistry(emptyList()).map { it.id }
        )
    }

    // ---------------------------------------------------------------
    // 4. The overlay is built by the production apply path
    // ---------------------------------------------------------------

    @Test
    fun `the overlay carries the fixture text and the template styling`() {
        cues.forEach { cue ->
            BuiltInSubtitleTemplates.ALL.take(8).forEach { template ->
                val overlay = ShowcaseFixture.overlayFor(template, cue)
                assertEquals(cue.text, overlay.text)
                assertEquals(cue.words, overlay.words)
                assertEquals(0L, overlay.startTimeMs)
                assertEquals(cue.durationMs, overlay.endTimeMs)
                // withTemplate applied every template axis.
                assertEquals(template.id, overlay.templateId)
                assertEquals(template.style, overlay.subtitleStyle)
                assertEquals(template.motionKind, overlay.motionKind)
                assertEquals(template.wordRevealMode, overlay.wordRevealMode)
                assertEquals(template.activeWordColorArgb, overlay.activeWordColorArgb)
                assertEquals(template.backgroundKind, overlay.backgroundKind)
                // A stable id keeps the preview's remember key from churning.
                assertEquals(
                    ShowcaseFixture.overlayFor(template, cue).id,
                    overlay.id
                )
            }
        }
    }

    // ---------------------------------------------------------------
    // 5. The showcase source set is debug-only
    // ---------------------------------------------------------------

    @Test
    fun `the showcase lives in the debug source set and not in main`() {
        // Guards the structural claim in the report: if someone moves these
        // files into src/main, the showcase starts shipping to users.
        val moduleDir = File(System.getProperty("user.dir") ?: ".")
        val debugFiles = listOf(
            "src/debug/java/com/aicreatorstudio/app/debugtools/SubtitleShowcaseActivity.kt",
            "src/debug/java/com/aicreatorstudio/app/debugtools/showcase/ShowcaseFixture.kt",
            "src/debug/java/com/aicreatorstudio/app/debugtools/showcase/SubtitleShowcaseViewModel.kt",
            "src/debug/java/com/aicreatorstudio/app/debugtools/showcase/SubtitleShowcaseScreen.kt",
            "src/debug/AndroidManifest.xml"
        )
        debugFiles.forEach { relative ->
            assertTrue(
                "$relative is missing — the showcase must stay in src/debug",
                File(moduleDir, relative).isFile
            )
        }
        assertTrue(
            "the debug manifest must declare the showcase activity",
            File(moduleDir, "src/debug/AndroidManifest.xml").readText()
                .contains("SubtitleShowcaseActivity")
        )
        // And nothing showcase-shaped may exist under src/main.
        val mainDir = File(moduleDir, "src/main")
        val leaked = mainDir.walkTopDown()
            .filter { it.isFile }
            .filter { it.name.contains("Showcase") }
            .toList()
        assertEquals("showcase code leaked into src/main: $leaked", emptyList<File>(), leaked)
    }
}
