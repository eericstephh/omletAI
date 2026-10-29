package com.aicreatorstudio.app

import android.app.Application
import com.aicreatorstudio.app.video.SampleProjectSeeder
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Annotated with [HiltAndroidApp] so Hilt can generate the DI container
 * that the rest of the app (ViewModels, repositories, future video/AI
 * services) will hang off of.
 *
 * On the very first launch [SampleProjectSeeder] imports the bundled
 * sample video as a real project so Home shows it right away.
 *
 * 2026-09-09 LANGUAGE FIX — the UI language override lives in
 * [com.aicreatorstudio.app.settings.AppLanguage] (applied per-Activity
 * in `attachBaseContext`, default PERSIAN; changeable in Settings).
 * No Application-level locale juggling is needed.
 */
@HiltAndroidApp
class AICreatorStudioApp : Application() {

    @Inject lateinit var sampleProjectSeeder: SampleProjectSeeder
    @Inject lateinit var tapsellAdManager: com.aicreatorstudio.app.ads.TapsellAdManager

    /**
     * 2026-10-12 — the centralized logger. Injected here (not merely
     * used lazily) so its writer coroutine and log directory are
     * initialized as early as possible in the process, and so the
     * export/Whisper diagnostics objects get a valid sink before any
     * job can run.
     */
    @Inject lateinit var omeletLog: com.aicreatorstudio.app.logging.OmeletLog

    override fun onCreate() {
        super.onCreate()
        // Wire the centralized logger sink into the two diagnostic
        // objects that already existed. Their logcat output is
        // unchanged; the call additionally persists a structured,
        // sanitized record. No export / transcription behaviour moves.
        com.aicreatorstudio.app.export.ExportDiagnostics.attach(omeletLog)
        com.aicreatorstudio.app.ai.WhisperDiagnostics.attach(omeletLog)
        // 2026-10-13 — and into the crash handler, before install(), so a
        // crash raised during install is still recorded. Uses a
        // synchronous store write because the process is about to die.
        CrashReporter.attachLog(omeletLog)
        CrashReporter.install(this)
        // 2026-09-16 — Tapsell Mediation init (no-op while placeholder
        // keys are in place). The app-open interstitial is triggered
        // from the Home screen, once per session.
        tapsellAdManager.initialize()
        sampleProjectSeeder.seedOnFirstLaunch()
    }
}
