package com.aicreatorstudio.app.ads

/**
 * Tapsell Mediation credentials and placement ("zone") ids.
 *
 * 2026-09-16 — migrated from the deprecated **Tapsell Plus** to the official
 * **Tapsell Mediation** SDK (`ir.tapsell:tapsell`, per the mediation sample).
 * Tapsell Plus is the older product name; Mediation is its successor, so the
 * "Tapsell Plus" dependency no longer exists and must not be added back.
 *
 * ## Credentials
 *
 * [APP_KEY] identifies this app. The SDK does **not** read it from here — it
 * reads the `ir.tapsell.mediation.APPLICATION_KEY` manifest meta-data, which
 * is populated from `manifestPlaceholders["TapsellMediationAppKey"]` in
 * `app/build.gradle.kts`. **The two values must be kept in sync**: this
 * constant is only what [isConfigured] checks, so a key set here but not in
 * Gradle (or vice versa) fails in a confusing way.
 *
 * ## Placements
 *
 * Tapsell calls an ad placement a *zone*. Each one has its own zone id and its
 * own fill, so they are enabled independently:
 *
 *  - [INTERSTITIAL_ZONE_ID_APP_OPEN] — shown once per process when Home opens.
 *  - [INTERSTITIAL_ZONE_ID_EXPORT] — shown when a video export finishes.
 *
 * A zone left at [PLACEHOLDER_ZONE_ID] simply disables that placement; the
 * other one keeps working. This is deliberate: it lets the export placement
 * ship without inventing a second zone id for app-open, which previously was
 * impossible because [isConfigured] demanded both.
 *
 * ## Why the checks are split
 *
 * [isConfigured] answers "may the SDK be initialised at all?" — that is purely
 * about the app key. Whether a *specific* placement may run is
 * [hasAppOpenZone] / [hasExportZone]. Folding the two concerns into one flag
 * (the previous behaviour) meant a missing app-open zone silently disabled the
 * export ad as well.
 */
object TapsellAds {

    /** Sentinel for "this credential has not been filled in yet". */
    private const val PLACEHOLDER_APP_KEY = "YOUR_TAPSELL_KEY"

    /** Sentinel for "this placement has no zone id yet". */
    private const val PLACEHOLDER_ZONE_ID = "YOUR_ZONE_ID"

    /**
     * App key from the Tapsell console (app.tapsell.ir).
     *
     * Mirrored into `manifestPlaceholders["TapsellMediationAppKey"]` in
     * `app/build.gradle.kts` — change both together.
     */
    const val APP_KEY =
        "gpperaotjlhstpjinpeqtsjmpqcdgkrothifljdgthfplnmmhkjbmprsqehstdcnlositc"

    /**
     * Interstitial shown once per process when the Home screen opens.
     *
     * Still a placeholder: the console zone id supplied for this change was
     * for the export placement, and reusing one zone id for two placements
     * would merge their reporting. Drop a second zone id in here to turn
     * app-open ads back on.
     */
    const val INTERSTITIAL_ZONE_ID_APP_OPEN = PLACEHOLDER_ZONE_ID

    /**
     * Interstitial shown when a video export finishes and the file has been
     * saved.
     */
    const val INTERSTITIAL_ZONE_ID_EXPORT = "6abb735d3c315037c8c55b38"

    /**
     * True once a real app key is present, i.e. the SDK may be initialised.
     * Says nothing about whether any particular placement has a zone id.
     */
    val isConfigured: Boolean
        get() = APP_KEY != PLACEHOLDER_APP_KEY

    /** True when the app-open placement has a real zone id. */
    val hasAppOpenZone: Boolean
        get() = INTERSTITIAL_ZONE_ID_APP_OPEN != PLACEHOLDER_ZONE_ID

    /** True when the export placement has a real zone id. */
    val hasExportZone: Boolean
        get() = INTERSTITIAL_ZONE_ID_EXPORT != PLACEHOLDER_ZONE_ID
}
