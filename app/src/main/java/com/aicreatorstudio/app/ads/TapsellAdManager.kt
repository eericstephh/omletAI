package com.aicreatorstudio.app.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ir.tapsell.mediation.MediationInitializationListener
import ir.tapsell.mediation.Tapsell
import ir.tapsell.mediation.ad.AdStateListener
import ir.tapsell.mediation.ad.request.RequestResultListener
import ir.tapsell.mediation.ad.show.AdShowCompletionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hilt entry point so Compose screens (which cannot constructor-inject)
 * can resolve the app-scoped [TapsellAdManager].
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TapsellAdsEntryPoint {
    fun tapsellAdManager(): TapsellAdManager
}

/**
 * 2026-09-16 — app-scoped Tapsell Mediation interstitial manager.
 * 2026-09-29 — extended from one placement to two.
 *
 * ## Placements
 *
 * Two interstitials, each with its own zone id and its own slot:
 *
 *  - [showAppOpenOnce] — once per process, when Home opens.
 *  - [showExportInterstitial] — every THIRD completed export
 *    ([ExportAdGate] frequency gate, 2026-10-05).
 *
 * They are kept in separate [Placement] slots rather than sharing one ad id
 * field, because the two have genuinely different lifecycles and would
 * otherwise clobber each other's in-flight request.
 *
 * ## The lifecycle difference, which is the whole reason for [Placement]
 *
 * *Once per process* and *once per export* are not the same rule, and the
 * difference is a single flag:
 *
 *  - App-open claims its slot and **never releases it after a successful
 *    show** — one ad per process, by design.
 *  - Export **releases its slot once the ad has been closed**, so the next
 *    export can show again.
 *
 * Both release the slot when *nothing was shown* (request failed, show
 * failed, host Activity dying), so a transient network failure does not
 * permanently burn the placement.
 *
 * The session slot also makes "once per process" rotation-proof: Activity
 * recreation re-triggers the caller, but only the first trigger reaches the
 * SDK.
 *
 * ## Concurrency
 *
 * [showing] is a cross-placement guard. Two interstitials must never be on
 * screen at once, and the SDK does not arbitrate that for us. In practice the
 * two placements rarely overlap (an export takes minutes), but the guard is
 * cheap and the failure mode without it is a crash or an ANR.
 *
 * Only the application context is retained — the Activity is ever
 * method-local, so nothing leaks across the ad Activity.
 */
@Singleton
class TapsellAdManager @Inject constructor(
    @ApplicationContext private val appContext: Context
) {

    enum class AdState { IDLE, LOADING, READY, SHOWING }

    /**
     * One placement's in-flight request.
     *
     * @param reuseAfterShow `true` when the placement may run again after an
     *   ad has actually been shown. App-open is once per process (`false`);
     *   export runs on every completed export (`true`).
     */
    private class Placement(val reuseAfterShow: Boolean) {
        /** Single-use interstitial ad id from the last successful request. */
        @Volatile
        var adId: String? = null

        /** `true` from the moment a request is claimed until it is released. */
        val consumed = AtomicBoolean(false)
    }

    private val appOpenPlacement = Placement(reuseAfterShow = false)
    private val exportPlacement = Placement(reuseAfterShow = true)

    /**
     * 2026-10-05 — process-lifetime completed-export counter feeding
     * [ExportAdGate]: the export interstitial shows on every THIRD
     * completed export instead of every one (friction cut to a third,
     * monetization preserved). Resets with the process, which is
     * acceptable for a frequency cap.
     */
    private val exportCounter = AtomicInteger(0)

    /**
     * Coarse, **diagnostic-only** view of the manager: the state of whichever
     * placement was touched most recently.
     *
     * It is deliberately not per-placement, because nothing consumes it — a
     * grep for `adState` outside this file returns nothing. With two
     * placements a single shared flow cannot describe both, so treat this as
     * a log aid rather than a source of truth. If a UI ever needs to react to
     * ad state, replace this with a per-placement flow rather than reading
     * this one.
     */
    private val _adState = MutableStateFlow(AdState.IDLE)
    val adState: StateFlow<AdState> = _adState.asStateFlow()

    private val initialized = AtomicBoolean(false)

    /** True while an interstitial from ANY placement is on screen. */
    private val showing = AtomicBoolean(false)

    fun initialize() {
        if (!TapsellAds.isConfigured) {
            Log.i(TAG, "initialize: placeholder app key — Tapsell init skipped")
            return
        }
        if (!initialized.compareAndSet(false, true)) return
        runCatching {
            Tapsell.setInitializationListener(
                object : MediationInitializationListener {
                    override fun onInitializationComplete() {
                        Log.i(TAG, "Tapsell Mediation initialized")
                    }
                }
            )
            // Mirrors the official mediation sample (consent granted
            // once at startup).
            Tapsell.setUserConsent(true)
            Tapsell.initialize(appContext)
        }.onFailure { e ->
            Log.w(TAG, "Tapsell.initialize threw", e)
            initialized.set(false)
        }
    }

    /**
     * Request the app-open interstitial and show it as soon as it is ready —
     * at most once per process. Safe to call on every Activity recreation
     * (rotations); late calls are no-ops. Silent no-op while the app-open
     * placement has no zone id, while the SDK is uninitialized, or when the
     * host Activity is dying.
     */
    fun showAppOpenOnce(activity: Activity) {
        if (!TapsellAds.hasAppOpenZone) {
            Log.i(TAG, "showAppOpenOnce: app-open zone id not set — skipped")
            return
        }
        requestAndShow(TapsellAds.INTERSTITIAL_ZONE_ID_APP_OPEN, appOpenPlacement, activity)
    }

    /**
     * Request the export interstitial and show it as soon as it is ready.
     *
     * Called when an export reaches its success state, i.e. after the video
     * has been rendered and saved.
     *
     * 2026-10-05 — FREQUENCY GATE. Previously every completed export showed
     * one — the most annoying possible moment (the user just finished a
     * long render). Monetization is preserved but the friction is cut to a
     * third: the interstitial shows on every THIRD completed export
     * ([ExportAdGate]). The counter is process-lifetime; a fresh process
     * restarts the cadence, which is acceptable for a frequency cap.
     *
     * Safe to call on every recomposition — the slot claim makes overlapping
     * calls no-ops, so a second export while an ad is still loading will not
     * queue a second ad, and gate-skipped exports do not touch the slot at
     * all.
     */
    fun showExportInterstitial(activity: Activity) {
        val exportNumber = exportCounter.incrementAndGet()
        if (!ExportAdGate.shouldShow(exportNumber)) {
            Log.i(
                TAG,
                "showExportInterstitial: export #$exportNumber — gate skipped " +
                    "(shows on every ${ExportAdGate.EXPORT_AD_EVERY}rd export)"
            )
            return
        }
        if (!TapsellAds.hasExportZone) {
            Log.i(TAG, "showExportInterstitial: export zone id not set — skipped")
            return
        }
        requestAndShow(TapsellAds.INTERSTITIAL_ZONE_ID_EXPORT, exportPlacement, activity)
    }

    /**
     * Shared request path for both placements.
     *
     * Claims [slot] before touching the SDK, so concurrent calls for the same
     * placement collapse into one request.
     */
    private fun requestAndShow(zoneId: String, slot: Placement, activity: Activity) {
        if (!TapsellAds.isConfigured || !initialized.get()) return
        if (!slot.consumed.compareAndSet(false, true)) return
        if (activity.isFinishing || activity.isDestroyed) {
            slot.consumed.set(false)
            return
        }
        _adState.value = AdState.LOADING
        runCatching {
            Tapsell.requestInterstitialAd(
                zoneId,
                object : RequestResultListener {
                    override fun onSuccess(adId: String) {
                        slot.adId = adId
                        _adState.value = AdState.READY
                        showIfAlive(slot, activity)
                    }

                    override fun onFailure(message: String) {
                        Log.w(TAG, "interstitial request failed ($zoneId): $message")
                        // Nothing was shown — let a later attempt retry.
                        release(slot, wasShown = false)
                    }
                }
            )
        }.onFailure { e ->
            Log.w(TAG, "requestInterstitialAd threw ($zoneId)", e)
            release(slot, wasShown = false)
        }
    }

    /** Shows the READY ad unless the host died while it was loading. */
    private fun showIfAlive(slot: Placement, activity: Activity) {
        val adId = slot.adId
        if (adId == null || activity.isFinishing || activity.isDestroyed) {
            release(slot, wasShown = false)
            return
        }
        // Cross-placement guard: never two interstitials at once.
        if (!showing.compareAndSet(false, true)) {
            release(slot, wasShown = false)
            return
        }
        _adState.value = AdState.SHOWING
        runCatching {
            Tapsell.showInterstitialAd(
                adId,
                activity,
                object : AdStateListener.Interstitial {
                    override fun onAdImpression() = Unit

                    override fun onAdClicked() = Unit

                    override fun onAdClosed(completionState: AdShowCompletionState) {
                        Log.i(TAG, "interstitial closed: ${completionState.name}")
                        showing.set(false)
                        // wasShown = true: app-open keeps its slot consumed
                        // (once per process), export releases it (every export).
                        release(slot, wasShown = true)
                    }

                    override fun onAdFailed(message: String) {
                        Log.w(TAG, "interstitial show failed: $message")
                        showing.set(false)
                        // Never made it to the screen — let a later attempt retry.
                        release(slot, wasShown = false)
                    }
                }
            )
        }.onFailure { e ->
            Log.w(TAG, "showInterstitialAd threw", e)
            showing.set(false)
            release(slot, wasShown = false)
        }
    }

    /**
     * Clears the in-flight ad id and, unless the placement is meant to stay
     * consumed, releases the slot for a future attempt.
     *
     * @param wasShown `true` only when an ad actually reached the screen
     *   (i.e. it was closed by the user). Failures pass `false` so they always
     *   release.
     */
    private fun release(slot: Placement, wasShown: Boolean) {
        slot.adId = null
        _adState.value = AdState.IDLE
        if (!wasShown || slot.reuseAfterShow) {
            slot.consumed.set(false)
        }
    }

    companion object {
        private const val TAG = "TapsellInterstitial"
    }
}
