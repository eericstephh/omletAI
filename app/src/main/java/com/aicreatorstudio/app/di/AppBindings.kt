package com.aicreatorstudio.app.di

import com.aicreatorstudio.app.ai.DefaultNetworkClient
import com.aicreatorstudio.app.ai.ModelInstaller
import com.aicreatorstudio.app.ai.ModelManager
import com.aicreatorstudio.app.ai.NetworkClient
import com.aicreatorstudio.app.ai.UnifiedModelInstaller
import com.aicreatorstudio.app.data.AppUpdatePreferences
import com.aicreatorstudio.app.data.DefaultAppUpdatePreferences
import com.aicreatorstudio.app.data.DefaultTutorialPreferences
import com.aicreatorstudio.app.data.TutorialPreferences
import com.aicreatorstudio.app.data.font.CustomFontRepository
import com.aicreatorstudio.app.data.font.DefaultCustomFontRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * `@Binds` declarations for the application graph.
 *
 * Lives in its own module (separate from [AppModule]) because Dagger
 * forbids mixing static `@Provides` methods and abstract `@Binds`
 * methods in the same `@Module`. [AppModule] is an `object`; this
 * module is an `abstract class` — both are installed in
 * [SingletonComponent] so Hilt sees them as one graph.
 *
 * Today's bindings:
 *
 *  - [NetworkClient] → [DefaultNetworkClient] and
 *    [ModelInstaller] → [ModelManager] — the LEGACY installer stack.
 *    2026-10-20: the KDoc here used to claim that "AI tool engines
 *    depend on the interface, not the concrete class". That was
 *    false: the engines take the CONCRETE
 *    [com.aicreatorstudio.app.ai.AiModelInstaller] (see
 *    `MediaPipeMattingEngine`, `SaliencyTrackerEngine`,
 *    `TesseractOcrEngine`), and the AI Upscale engines take
 *    [UnifiedModelInstaller]. Nothing in `main/` injects the
 *    [ModelInstaller] interface, so this binding is currently
 *    unreachable from production code — the real installs go through
 *    [AiModelInstaller] / [UnifiedModelInstaller].
 *
 *    The binding is KEPT because [com.aicreatorstudio.app.ai.ModelManager]
 *    and its [NetworkClient] seam are still covered by
 *    `ModelManagerTest`, which constructs them directly. The comment
 *    was wrong about WHY it exists, so it is stated correctly here
 *    rather than pretending the interface is the production seam.
 *  - [AppUpdatePreferences] → [DefaultAppUpdatePreferences] —
 *    [com.aicreatorstudio.app.viewmodel.HomeViewModel] takes the
 *    interface, so the ViewModel stays constructible in a JVM test.
 *  - [CustomFontRepository] → [DefaultCustomFontRepository] —
 *    [com.aicreatorstudio.app.viewmodel.EditorViewModel] takes the
 *    interface, same reasoning: the real class needs a `Context` and
 *    the `Typeface` loader, neither of which exists on the JVM.
 *
 * Tests construct [ModelManager] / a hand-rolled `ModelInstaller`
 * fake directly, so they don't go through Hilt and don't need
 * test-only bindings.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindings {

    @Binds
    @Singleton
    abstract fun bindNetworkClient(impl: DefaultNetworkClient): NetworkClient

    @Binds
    @Singleton
    abstract fun bindModelInstaller(impl: ModelManager): ModelInstaller

    @Binds
    @Singleton
    abstract fun bindUnifiedHttpFetcher(
        impl: UnifiedModelInstaller.DefaultHttpFetcher
    ): UnifiedModelInstaller.HttpFetcher

    /**
     * The changelog bookmark behind the Home screen's notification bell.
     *
     * Bound as an interface so `HomeViewModel` — which takes it as a
     * constructor dependency — stays constructible in a JVM test, where
     * `SharedPreferences` is unavailable. `HomeViewModelTest` supplies
     * `InMemoryAppUpdatePreferences` instead of going through Hilt.
     */
    @Binds
    @Singleton
    abstract fun bindAppUpdatePreferences(
        impl: DefaultAppUpdatePreferences
    ): AppUpdatePreferences

    /**
     * 2026-11-01 — the first-run tutorial's bookmark (onboarding content
     * version + whether the Home tour has been seen).
     *
     * Bound as an interface for the same reason as
     * [AppUpdatePreferences]: `TutorialViewModel` takes it as a
     * constructor dependency and is constructed directly in a JVM test,
     * where `SharedPreferences` is unavailable. Tests supply
     * `InMemoryTutorialPreferences` instead of going through Hilt.
     */
    @Binds
    @Singleton
    abstract fun bindTutorialPreferences(
        impl: DefaultTutorialPreferences
    ): TutorialPreferences

    /**
     * The user's imported fonts.
     *
     * Bound as an interface for the same reason as
     * [AppUpdatePreferences]: `EditorViewModel` takes it as a
     * constructor dependency, the real implementation needs a `Context`
     * (for `filesDir`) and the Android `Typeface` loader, and this
     * project has no Robolectric — so a concrete class here would make
     * the ViewModel unconstructible in a JVM test. Tests supply
     * `InMemoryCustomFontRepository` instead of going through Hilt.
     */
    @Binds
    @Singleton
    abstract fun bindCustomFontRepository(
        impl: DefaultCustomFontRepository
    ): CustomFontRepository

    /**
     * 2026-11-01 — the AI subtitle-correction stage's two seams.
     *
     * Both are bound as interfaces for the reason above: the real
     * implementations need a `Context` (Keystore, SharedPreferences)
     * and a live socket, so a concrete dependency would make
     * `PersianSubtitleCorrector` unconstructible in a JVM test. The
     * tests supply a fake client and fake credentials instead of going
     * through Hilt, which is what lets rate limits, timeouts,
     * cancellation and malformed replies be asserted **without a paid
     * API call**.
     */
    @Binds
    @Singleton
    abstract fun bindChatCompletionClient(
        impl: com.aicreatorstudio.app.ai.groq.GroqChatClient
    ): com.aicreatorstudio.app.ai.subtitle.correction.ChatCompletionClient

    @Binds
    @Singleton
    abstract fun bindCorrectionCredentials(
        impl: com.aicreatorstudio.app.ai.subtitle.correction.DefaultCorrectionCredentials
    ): com.aicreatorstudio.app.ai.subtitle.correction.CorrectionCredentials
}
