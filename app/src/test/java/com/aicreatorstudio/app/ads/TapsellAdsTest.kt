package com.aicreatorstudio.app.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Configuration guards for the Tapsell Mediation integration.
 *
 * These exist because the integration is **silently self-disabling by
 * design**: a placeholder credential makes every entry point a no-op rather
 * than an error, so a mis-filled key or zone produces a working app that
 * simply never shows an ad. There is no crash to notice and no test to fail —
 * which is exactly why one is pinned here.
 *
 * The ad path itself (request, show, Activity lifecycle) cannot be unit
 * tested: it needs a real `Activity` and the SDK. These tests cover the half
 * that is pure data, and they are the only automated verification available
 * without a device.
 */
class TapsellAdsTest {

    @Test
    fun `the app key is filled in`() {
        assertTrue(
            "TapsellAds.APP_KEY is still the placeholder — the SDK would " +
                "never initialise and no ad would ever show",
            TapsellAds.isConfigured
        )
    }

    @Test
    fun `isConfigured does not require a zone id`() {
        // Regression guard for the bug this change fixed: `isConfigured` used
        // to AND in the app-open zone id, so a missing app-open zone disabled
        // the WHOLE SDK — including the export placement that had a perfectly
        // good zone id. App-open is still a placeholder today, so if the two
        // concerns are ever recombined this goes red immediately.
        assertTrue(
            "isConfigured gates SDK initialisation, which is about the app " +
                "key only — it must not be ANDed with any zone id",
            TapsellAds.isConfigured
        )
    }

    @Test
    fun `the export placement has a zone id`() {
        assertTrue(
            "no export zone id — the post-export ad is disabled",
            TapsellAds.hasExportZone
        )
    }

    @Test
    fun `the export zone id is a Tapsell object id`() {
        // Tapsell zone ids are 24-character lowercase hex (Mongo ObjectIds).
        // Catches a truncated paste or a stray character.
        val zone = TapsellAds.INTERSTITIAL_ZONE_ID_EXPORT
        assertEquals("zone id must be 24 characters: \"$zone\"", 24, zone.length)
        assertTrue(
            "zone id must be lowercase hex: \"$zone\"",
            zone.all { it in '0'..'9' || it in 'a'..'f' }
        )
    }

    @Test
    fun `the app-open placement is intentionally not enabled yet`() {
        // Only one zone id was supplied, and it belongs to the export
        // placement — reusing it for app-open would merge the two
        // placements' reporting. So app-open stays dark for now.
        // Update this assertion when a second zone id is added.
        assertFalse(
            "app-open unexpectedly has a zone id — update this test",
            TapsellAds.hasAppOpenZone
        )
    }

    @Test
    fun `the app key is lowercase alphanumeric`() {
        // Catches a paste that swallowed a newline or a stray dash. A key in
        // the wrong shape fails at runtime as "no fill", not at build time.
        val key = TapsellAds.APP_KEY
        assertTrue(
            "app key must be lowercase alphanumeric, got ${key.length} chars",
            key.all { it in 'a'..'z' || it in '0'..'9' }
        )
    }
}
