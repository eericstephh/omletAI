package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SrtParser] - PHASE B4.
 *
 * The parser must never throw. It produces a [ParseResult] for every
 * input, with non-fatal issues surfaced on the result so the import
 * UX can warn the user without aborting the whole import.
 *
 * Coverage:
 *
 *  - Standard HH:MM:SS,mmm format
 *  - Negative values rejected
 *  - Hours / minutes / seconds / millisecond component bounds
 *  - LF and CRLF line endings
 *  - UTF-8 BOM stripped
 *  - Multi-line text preserved verbatim
 *  - Index numbers are metadata, not authoritative
 *  - Duplicate indexes reported but not fatal
 *  - Malformed blocks (bad timestamp, missing arrow, missing text,
 *    invalid range) reported as issues; the rest of the file is
 *    still returned
 *  - Empty / whitespace-only file produces EMPTY issue
 *  - Persian / RTL / Unicode preserved
 *  - Period decimal separator accepted (lenient mode)
 *  - 1/2/3+ digit millisecond component normalized
 *  - Files with no millisecond component accepted as exact seconds
 */
class SrtParserTest {

    @Test
    fun `empty body returns EMPTY issue and no cues`() {
        val r = SrtParser.parse("", "empty.srt")
        assertFalse(r.hasCues)
        assertEquals(0, r.cues.size)
        assertEquals(1, r.issues.size)
        assertEquals(SrtIssueKind.EMPTY, r.issues[0].kind)
    }

    @Test
    fun `whitespace-only body returns EMPTY issue`() {
        val r = SrtParser.parse("   \n\n\t  \n  \r\n  ", "ws.srt")
        assertFalse(r.hasCues)
        assertEquals(SrtIssueKind.EMPTY, r.issues[0].kind)
    }

    @Test
    fun `single standard cue parses to one SrtCue`() {
        val body = "1\n00:00:01,000 --> 00:00:02,500\nHello world\n"
        val r = SrtParser.parse(body)
        assertTrue(r.hasCues)
        assertEquals(1, r.cues.size)
        val c = r.cues[0]
        assertEquals(1L, c.index)
        assertEquals(1_000L, c.startTimeMs)
        assertEquals(2_500L, c.endTimeMs)
        assertEquals("Hello world", c.text)
        assertEquals(0, r.issues.size)
    }

    @Test
    fun `multiple cues preserve source order`() {
        val body = """
            1
            00:00:00,000 --> 00:00:01,000
            First

            2
            00:00:01,500 --> 00:00:03,000
            Second cue

            3
            00:00:03,200 --> 00:00:05,000
            Third
        """.trimIndent()
        val r = SrtParser.parse(body)
        assertEquals(3, r.cues.size)
        assertEquals("First", r.cues[0].text)
        assertEquals("Second cue", r.cues[1].text)
        assertEquals("Third", r.cues[2].text)
        assertEquals(1L, r.cues[0].index)
        assertEquals(2L, r.cues[1].index)
        assertEquals(3L, r.cues[2].index)
    }

    @Test
    fun `multiline text is preserved with newlines`() {
        val body = "1\n00:00:01,000 --> 00:00:03,000\nLine one\nLine two\nLine three\n"
        val r = SrtParser.parse(body)
        assertEquals(1, r.cues.size)
        assertEquals("Line one\nLine two\nLine three", r.cues[0].text)
    }

    @Test
    fun `UTF-8 BOM is stripped from the first cue text`() {
        val body = "﻿1\n00:00:00,000 --> 00:00:01,000\nFirst\n"
        val r = SrtParser.parse(body)
        assertEquals(1, r.cues.size)
        assertFalse(
            "BOM leaked into cue text",
            r.cues[0].text.startsWith("﻿")
        )
        assertEquals("First", r.cues[0].text)
    }

    @Test
    fun `CRLF line endings parse the same as LF`() {
        val lf = "1\n00:00:00,000 --> 00:00:01,000\nA\n"
        val crlf = "1\r\n00:00:00,000 --> 00:00:01,000\r\nA\r\n"
        val lfResult = SrtParser.parse(lf)
        val crlfResult = SrtParser.parse(crlf)
        assertEquals(lfResult.cues.size, crlfResult.cues.size)
        assertEquals(lfResult.cues[0].text, crlfResult.cues[0].text)
        assertEquals(lfResult.cues[0].startTimeMs, crlfResult.cues[0].startTimeMs)
        assertEquals(lfResult.cues[0].endTimeMs, crlfResult.cues[0].endTimeMs)
    }

    @Test
    fun `index numbers are metadata - non-sequential is OK`() {
        val body = """
            5
            00:00:00,000 --> 00:00:01,000
            A

            17
            00:00:01,000 --> 00:00:02,000
            B
        """.trimIndent()
        val r = SrtParser.parse(body)
        assertEquals(2, r.cues.size)
        assertEquals(5L, r.cues[0].index)
        assertEquals(17L, r.cues[1].index)
        assertEquals(0, r.issues.size)
    }

    @Test
    fun `index numbers are metadata - duplicates are non-fatal but reported`() {
        val body = """
            1
            00:00:00,000 --> 00:00:01,000
            A

            1
            00:00:01,000 --> 00:00:02,000
            B
        """.trimIndent()
        val r = SrtParser.parse(body)
        assertEquals(2, r.cues.size)
        assertTrue(
            "expected DUPLICATE_INDEX issue",
            r.issues.any { it.kind == SrtIssueKind.DUPLICATE_INDEX }
        )
    }

    @Test
    fun `bad timestamp line is reported and the rest of the file still parses`() {
        val body = """
            1
            not a timestamp
            Hello

            2
            00:00:02,000 --> 00:00:03,000
            World
        """.trimIndent()
        val r = SrtParser.parse(body)
        assertEquals(1, r.cues.size)
        assertEquals("World", r.cues[0].text)
        assertTrue(
            r.issues.any { it.kind == SrtIssueKind.MALFORMED_TIMESTAMP }
        )
    }

    @Test
    fun `missing arrow is reported as MALFORMED_TIMESTAMP`() {
        val body = "1\n00:00:01,000 00:00:02,000\nHello\n"
        val r = SrtParser.parse(body)
        assertEquals(0, r.cues.size)
        assertTrue(
            r.issues.any { it.kind == SrtIssueKind.MALFORMED_TIMESTAMP }
        )
    }

    @Test
    fun `end before start is reported as INVALID_RANGE`() {
        val body = "1\n00:00:05,000 --> 00:00:02,000\nHello\n"
        val r = SrtParser.parse(body)
        assertEquals(0, r.cues.size)
        assertTrue(
            r.issues.any { it.kind == SrtIssueKind.INVALID_RANGE }
        )
    }

    @Test
    fun `end equal to start is reported as INVALID_RANGE`() {
        val body = "1\n00:00:05,000 --> 00:00:05,000\nHello\n"
        val r = SrtParser.parse(body)
        assertEquals(0, r.cues.size)
        assertTrue(
            r.issues.any { it.kind == SrtIssueKind.INVALID_RANGE }
        )
    }

    @Test
    fun `block with only two lines is reported as MALFORMED_BLOCK`() {
        // The input ends right after the time line, so the
        // block has 2 lines (index + time). The parser
        // reports MALFORMED_BLOCK because the text section
        // is absent entirely.
        val body = "1\n00:00:01,000 --> 00:00:02,000\n"
        val r = SrtParser.parse(body)
        assertEquals(0, r.cues.size)
        assertTrue(
            "expected MALFORMED_BLOCK for 2-line input",
            r.issues.any { it.kind == SrtIssueKind.MALFORMED_BLOCK }
        )
    }

    @Test
    fun `overlapping cues are both kept - overlap is not an error`() {
        val body = """
            1
            00:00:00,000 --> 00:00:05,000
            A

            2
            00:00:02,000 --> 00:00:04,000
            B
        """.trimIndent()
        val r = SrtParser.parse(body)
        // Overlap is a parser ISSUE for the user to see,
        // not a fatal error. Both cues are kept.
        assertEquals(2, r.cues.size)
    }

    @Test
    fun `parseTimestamp handles all 1 2 3 digit millisecond components`() {
        assertEquals(100L, SrtParser.parseTimestamp("00:00:00,1"))
        assertEquals(50L, SrtParser.parseTimestamp("00:00:00,05"))
        assertEquals(123L, SrtParser.parseTimestamp("00:00:00,123"))
        assertEquals(123L, SrtParser.parseTimestamp("00:00:00,1234"))
    }

    @Test
    fun `parseTimestamp accepts period as lenient decimal separator`() {
        assertEquals(1_500L, SrtParser.parseTimestamp("00:00:01.500"))
    }

    @Test
    fun `parseTimestamp accepts bare seconds when no millis`() {
        assertEquals(5_000L, SrtParser.parseTimestamp("00:00:05"))
    }

    @Test
    fun `parseTimestamp rejects negative components`() {
        assertNull(SrtParser.parseTimestamp("-1:00:00,000"))
        assertNull(SrtParser.parseTimestamp("00:-1:00,000"))
    }

    @Test
    fun `parseTimestamp rejects non-numeric components`() {
        assertNull(SrtParser.parseTimestamp("aa:bb:cc,000"))
        assertNull(SrtParser.parseTimestamp("00:00:0a,000"))
    }

    @Test
    fun `parseTimestamp accepts values up to 60 for minutes and seconds (lenient)`() {
        assertEquals(3_900_000L, SrtParser.parseTimestamp("01:05:00,000"))
        assertEquals(60_000L + 0L, SrtParser.parseTimestamp("00:00:60,000"))
    }

    @Test
    fun `Persian text is preserved character-for-character`() {
        val body = "1\n00:00:00,000 --> 00:00:02,000\nسلام دنیا\n"
        val r = SrtParser.parse(body)
        assertEquals(1, r.cues.size)
        assertEquals("سلام دنیا", r.cues[0].text)
    }

    @Test
    fun `mixed Persian and English text is preserved verbatim`() {
        val body = "1\n00:00:00,000 --> 00:00:02,000\nHello سلام World دنیا\n"
        val r = SrtParser.parse(body)
        assertEquals("Hello سلام World دنیا", r.cues[0].text)
    }

    @Test
    fun `numbers and punctuation are preserved`() {
        val body = "1\n00:00:00,000 --> 00:00:01,000\n123,456.78!@#%? \"quoted\"\n"
        val r = SrtParser.parse(body)
        assertEquals("123,456.78!@#%? \"quoted\"", r.cues[0].text)
    }

    @Test
    fun `hours can be larger than 99 for very long media`() {
        val r = SrtParser.parse("1\n100:00:00,000 --> 101:00:00,000\nFar future\n")
        assertEquals(1, r.cues.size)
        assertEquals(360_000_000L, r.cues[0].startTimeMs)
        assertEquals(363_600_000L, r.cues[0].endTimeMs)
    }

    @Test
    fun `parseTimeRange tolerates whitespace around the arrow`() {
        val ms = SrtParser.parseTimeRange("  00:00:01,000   -->   00:00:02,000  ")
        assertNotNull(ms)
        assertEquals(1_000L, ms!!.first)
        assertEquals(2_000L, ms.second)
    }

    @Test
    fun `source label is propagated to issue messages`() {
        val r = SrtParser.parse("", "my-cues.srt")
        assertEquals(1, r.issues.size)
        assertEquals("my-cues.srt", r.issues[0].sourceLabel)
    }
}
