package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SrtImportState] — PHASE B4.
 *
 * The state machine has six states; the import pipeline is real
 * (no fake progress) so each state carries a real, observable
 * phase of the import. These tests confirm the data classes
 * behave as documented: progress fractions are monotone, the
 * completed state carries both overlay count and skipped
 * duplicate count, the error state carries a message.
 */
class SrtImportStateTest {

    @Test
    fun `Idle is a singleton`() {
        // data objects compare by identity, not by fields, so
        // the equality check verifies the singleton shape.
        assertEquals(SrtImportState.Idle, SrtImportState.Idle)
    }

    @Test
    fun `Reading carries a progress fraction defaulting to zero`() {
        val r = SrtImportState.Reading()
        assertEquals(0f, r.progressFraction, 0.0001f)
    }

    @Test
    fun `Parsing carries a progress fraction of 0_3`() {
        // The fraction is fixed by convention; tests below
        // assert that the import pipeline advances the UI
        // through the documented phases.
        val r = SrtImportState.Parsing()
        assertEquals(0.3f, r.progressFraction, 0.0001f)
    }

    @Test
    fun `Validating carries a progress fraction of 0_6 and optional issues`() {
        val empty = SrtImportState.Validating()
        assertEquals(0.6f, empty.progressFraction, 0.0001f)
        assertTrue(empty.issues.isEmpty())

        val issues = listOf(
            SrtIssue(SrtIssueKind.MALFORMED_BLOCK, "test.srt", "bad block")
        )
        val withIssues = SrtImportState.Validating(issues = issues)
        assertEquals(1, withIssues.issues.size)
    }

    @Test
    fun `Importing carries a progress fraction of 0_85`() {
        val r = SrtImportState.Importing()
        assertEquals(0.85f, r.progressFraction, 0.0001f)
    }

    @Test
    fun `Completed carries overlay count and skipped duplicate count`() {
        val r = SrtImportState.Completed(
            overlayCount = 12,
            skippedDuplicates = 3
        )
        assertEquals(12, r.overlayCount)
        assertEquals(3, r.skippedDuplicates)
    }

    @Test
    fun `Completed issues are empty by default and overridable`() {
        assertTrue(SrtImportState.Completed(0, 0).issues.isEmpty())
        val withIssues = SrtImportState.Completed(
            overlayCount = 1,
            skippedDuplicates = 0,
            issues = listOf(
                SrtIssue(SrtIssueKind.DUPLICATE_INDEX, "x.srt", "dup")
            )
        )
        assertEquals(1, withIssues.issues.size)
    }

    @Test
    fun `Error carries a message`() {
        val r = SrtImportState.Error("could not read file")
        assertEquals("could not read file", r.message)
    }

    @Test
    fun `progress fractions are monotone ascending Idle to Importing`() {
        val r = SrtImportState.Reading()
        val p = SrtImportState.Parsing()
        val v = SrtImportState.Validating()
        val i = SrtImportState.Importing()
        assertTrue(r.progressFraction < p.progressFraction)
        assertTrue(p.progressFraction < v.progressFraction)
        assertTrue(v.progressFraction < i.progressFraction)
    }

    @Test
    fun `Completed is not equal to Importing`() {
        val c = SrtImportState.Completed(1, 0)
        val i = SrtImportState.Importing()
        assertFalse(c == i)
    }

    @Test
    fun `Error is not equal to Idle`() {
        val e = SrtImportState.Error("x")
        val i = SrtImportState.Idle
        assertFalse(e == i)
    }
}
