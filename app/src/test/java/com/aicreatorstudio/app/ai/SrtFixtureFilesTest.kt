package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE B4 — fixture-driven tests that read real SubRip files
 * from `app/src/test/resources/srt-fixtures/` and run them
 * through the import pipeline. This catches encoding issues
 * (BOM, CRLF) and Persian / mixed-language content that the
 * unit tests on synthetic strings might miss.
 *
 * The fixtures (10 total, A–J):
 *
 *  A — English basic
 *  B — Persian basic
 *  C — Persian + English mixed
 *  D — Persian multiline
 *  E — Punctuation and numbers
 *  F — UTF-8 BOM
 *  G — CRLF line endings
 *  H — Overlapping cues
 *  I — Irregular index numbers
 *  J — Mixed stress (multiline + Persian/English + numbers)
 *  K — Persian RTL stress (ZWNJ, Persian digits, mixed Latin,
 *      short/long words, 1/2/3-line cues) — added by the subtitle QA
 *      pass; see `docs/subtitles/qa-report.md` §5.
 */
class SrtFixtureFilesTest {

    private fun readFixture(name: String): String {
        val stream = javaClass.classLoader!!.getResourceAsStream(
            "srt-fixtures/$name"
        ) ?: error("Test fixture not found on classpath: $name")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Test
    fun `A - English basic parses to three cues`() {
        val body = readFixture("A-english-basic.srt")
        val r = SrtParser.parse(body, "A-english-basic.srt")
        assertEquals(3, r.cues.size)
        assertEquals("Hello world", r.cues[0].text)
        assertEquals("This is a test", r.cues[1].text)
        assertEquals("Goodbye", r.cues[2].text)
        assertTrue(
            "English basic should produce no issues",
            r.issues.isEmpty()
        )
    }

    @Test
    fun `B - Persian basic parses to three cues`() {
        val body = readFixture("B-persian-basic.srt")
        val r = SrtParser.parse(body, "B-persian-basic.srt")
        assertEquals(3, r.cues.size)
        assertEquals("سلام دنیا", r.cues[0].text)
        assertEquals("این یک آزمایش است", r.cues[1].text)
        assertEquals("خداحافظ", r.cues[2].text)
    }

    @Test
    fun `C - Persian and English mixed parses to four cues`() {
        val body = readFixture("C-persian-english-mixed.srt")
        val r = SrtParser.parse(body)
        assertEquals(4, r.cues.size)
        // No Unicode mixing: each cue's text is exactly what
        // the fixture file carries.
        assertEquals("Hello سلام", r.cues[0].text)
        assertEquals("World دنیا", r.cues[1].text)
        assertEquals("AI: هوش مصنوعی", r.cues[2].text)
        assertEquals("NLE: ویرایشگر", r.cues[3].text)
    }

    @Test
    fun `D - Persian multiline preserves line breaks`() {
        val body = readFixture("D-persian-multiline.srt")
        val r = SrtParser.parse(body)
        assertEquals(2, r.cues.size)
        assertEquals(
            "خط اول\nخط دوم\nخط سوم",
            r.cues[0].text
        )
        assertEquals(
            "یک پاراگراف\nچند خطی\nکه باید حفظ شود",
            r.cues[1].text
        )
    }

    @Test
    fun `E - punctuation and numbers are preserved`() {
        val body = readFixture("E-punctuation-numbers.srt")
        val r = SrtParser.parse(body)
        assertEquals(4, r.cues.size)
        assertEquals(
            "Question? Exclamation!",
            r.cues[0].text
        )
        assertEquals(
            "\"Quoted text\" and 'apostrophe'",
            r.cues[1].text
        )
        assertEquals(
            "Numbers: 123,456.78 / 1,000,000",
            r.cues[2].text
        )
        assertEquals(
            "Symbols: @#\$%^&*()_+-={}[]|\\:;<>?,./",
            r.cues[3].text
        )
    }

    @Test
    fun `F - UTF-8 BOM is stripped from the first cue text`() {
        val body = readFixture("F-utf8-bom.srt")
        // Sanity check: the first three bytes of the file are
        // the UTF-8 BOM (EF BB BF). If a future refactor
        // changes the way the file is loaded (e.g. through
        // a binary channel) this test would fail loudly.
        assertEquals(
            "Expected file to start with UTF-8 BOM EF BB BF",
            0xEF.toByte(), body.toByteArray(Charsets.UTF_8)[0]
        )
        val r = SrtParser.parse(body)
        assertEquals(1, r.cues.size)
        // The first character of the cue text must NOT be
        // the BOM. The brief is explicit: "Imported text
        // must remain exactly intact. Do NOT reverse
        // strings/characters/Unicode ordering."
        val firstChar = r.cues[0].text.firstOrNull()
        assertTrue(
            "BOM leaked into cue text: first char codepoint = " +
                (firstChar?.code?.toString() ?: "<null>"),
            firstChar == null || firstChar.code != 0xFEFF
        )
        assertEquals("Hello BOM", r.cues[0].text)
    }

    @Test
    fun `G - CRLF line endings parse the same as LF`() {
        val body = readFixture("G-crlf.srt")
        val r = SrtParser.parse(body)
        assertEquals(2, r.cues.size)
        assertEquals("Line one", r.cues[0].text)
        assertEquals("Line two", r.cues[1].text)
    }

    @Test
    fun `H - overlapping cues are all kept and reported`() {
        val body = readFixture("H-overlapping.srt")
        val r = SrtParser.parse(body)
        // Three overlapping cues, none rejected.
        assertEquals(3, r.cues.size)
        assertEquals("Long first cue", r.cues[0].text)
        assertEquals("Shorter cue inside", r.cues[1].text)
        assertEquals("Another overlapping cue", r.cues[2].text)
    }

    @Test
    fun `I - irregular indexes are honored without complaint`() {
        val body = readFixture("I-irregular-indexes.srt")
        val r = SrtParser.parse(body)
        assertEquals(3, r.cues.size)
        assertEquals(7L, r.cues[0].index)
        assertEquals(42L, r.cues[1].index)
        assertEquals(100L, r.cues[2].index)
        // Brief: indexes are metadata; do not assume they
        // are sequential. The parser must NOT emit
        // issues for a non-sequential but otherwise valid
        // file.
        assertTrue(
            "expected no issues for irregular-but-valid indexes",
            r.issues.none { it.kind == SrtIssueKind.MALFORMED_BLOCK }
        )
    }

    @Test
    fun `J - mixed stress fixture round trips`() {
        val body = readFixture("J-mixed-stress.srt")
        val first = SrtParser.parse(body).cues
        assertEquals(3, first.size)
        val overlays = SrtImportMapper.toTextOverlays(first)
        val srt = SrtFormatter.format(overlays.map { o ->
            Subtitle(
                text = o.text,
                startTimeMs = o.startTimeMs,
                endTimeMs = o.endTimeMs
            )
        })
        val second = SrtParser.parse(srt).cues
        assertEquals(first.size, second.size)
        for (i in first.indices) {
            assertEquals(first[i].text, second[i].text)
            assertEquals(first[i].startTimeMs, second[i].startTimeMs)
            assertEquals(first[i].endTimeMs, second[i].endTimeMs)
        }
    }

    @Test
    fun `all eleven fixtures have the expected number of cues`() {
        // Belt-and-braces smoke test: every fixture should
        // produce a non-empty cue list. Catches a refactor
        // that accidentally breaks an early-return path.
        val expected = mapOf(
            "A-english-basic.srt" to 3,
            "B-persian-basic.srt" to 3,
            "C-persian-english-mixed.srt" to 4,
            "D-persian-multiline.srt" to 2,
            "E-punctuation-numbers.srt" to 4,
            "F-utf8-bom.srt" to 1,
            "G-crlf.srt" to 2,
            "H-overlapping.srt" to 3,
            "I-irregular-indexes.srt" to 3,
            "J-mixed-stress.srt" to 3,
            "K-persian-rtl-stress.srt" to 5
        )
        for ((name, count) in expected) {
            val body = readFixture(name)
            val r = SrtParser.parse(body, name)
            assertEquals(
                "fixture $name produced ${r.cues.size} cues, expected $count",
                count,
                r.cues.size
            )
        }
    }
}
