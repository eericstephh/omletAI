package com.aicreatorstudio.app.ads

import java.util.concurrent.atomic.AtomicInteger

/**
 * Frequency gate for the export interstitial (2026-10-05).
 *
 * The export ad used to fire after EVERY completed export — the single
 * most annoying moment in the flow (the user just finished a long render
 * and wants to see the result). Monetization stays intact but the friction
 * is cut to a third: the interstitial shows on every Nth completed export
 * only ([EXPORT_AD_EVERY]).
 *
 * Pure JVM logic (no Android imports) so the unit suite can pin the
 * contract; [TapsellAdManager] owns the counter and consults this gate.
 */
object ExportAdGate {

    /** Show the export interstitial on every Nth completed export. */
    const val EXPORT_AD_EVERY: Int = 3

    /**
     * `true` when completed-export number [exportNumber] (1-based, one
     * increment per completed export) should show the interstitial:
     * the 3rd, 6th, 9th, … export.
     */
    fun shouldShow(exportNumber: Int): Boolean =
        exportNumber > 0 && exportNumber % EXPORT_AD_EVERY == 0

    /** Process-lifetime counter handed to [shouldShow] by the ad manager. */
    fun newCounter(): AtomicInteger = AtomicInteger(0)
}
