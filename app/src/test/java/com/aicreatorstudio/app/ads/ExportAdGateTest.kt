package com.aicreatorstudio.app.ads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for [ExportAdGate] — the export-interstitial frequency gate.
 * Pure JVM; no Tapsell SDK on the classpath.
 */
class ExportAdGateTest {

    @Test
    fun `shows on every third completed export`() {
        assertFalse("1st export must NOT show the interstitial", ExportAdGate.shouldShow(1))
        assertFalse("2nd export must NOT show the interstitial", ExportAdGate.shouldShow(2))
        assertTrue("3rd export shows", ExportAdGate.shouldShow(3))
        assertFalse("4th export must NOT show", ExportAdGate.shouldShow(4))
        assertFalse("5th export must NOT show", ExportAdGate.shouldShow(5))
        assertTrue("6th export shows", ExportAdGate.shouldShow(6))
    }

    @Test
    fun `non-positive counters never show`() {
        assertFalse(ExportAdGate.shouldShow(0))
        assertFalse(ExportAdGate.shouldShow(-1))
        assertFalse(ExportAdGate.shouldShow(-3))
    }

    @Test
    fun `cadence constant is exactly three`() {
        assertTrue(
            "the frequency gate must stay at 3 unless monetization is renegotiated",
            ExportAdGate.EXPORT_AD_EVERY == 3
        )
    }
}
