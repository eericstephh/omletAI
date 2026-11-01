package com.aicreatorstudio.app.data

import com.aicreatorstudio.app.model.TutorialProgress
import kotlinx.coroutines.flow.StateFlow

/**
 * Persists how far the user has got through the app's own tutorials.
 *
 * ## Why an interface
 *
 * [com.aicreatorstudio.app.viewmodel.TutorialViewModel] takes this as a
 * constructor dependency, and `TutorialViewModelTest` constructs that
 * ViewModel directly on the JVM. The real implementation needs
 * `SharedPreferences`, which needs a real `Context`, and this project has
 * no Robolectric — so a concrete class here would make the ViewModel
 * untestable. Same reasoning as [AppUpdatePreferences] →
 * `DefaultAppUpdatePreferences`.
 *
 * ## What is stored, and what is derived
 *
 * One [TutorialProgress] value: the highest content version completed and
 * whether the Home tour has been seen. "Should it show?" is NOT stored —
 * it is derived by `shouldShowOnboarding` / `shouldShowHomeTour`, which
 * are pure functions covered by unit tests. Storing the decision as well
 * as its inputs is how the two end up disagreeing.
 *
 * ## Why its own `SharedPreferences` file
 *
 * Same argument as `DefaultAppUpdatePreferences`: this is a *bookmark the
 * app keeps about its own content*, not a preference the user chose. A
 * user who resets their export settings must not be shown the onboarding
 * again, and a user who replays the tutorial must not lose those
 * settings.
 */
interface TutorialPreferences {

    /** The current progress. Collected by the ViewModel and the screens. */
    val progress: StateFlow<TutorialProgress>

    /**
     * Record that the onboarding was completed at the current content
     * version. The Home tour is deliberately left alone: the screen
     * chains them, so finishing the onboarding immediately starts the
     * tour.
     */
    fun markOnboardingSeen()

    /**
     * Record that the Home tour finished (or was dismissed). Safe to call
     * when it was already marked.
     */
    fun markHomeTourSeen()

    /**
     * Re-arm BOTH the onboarding and the tour, so the user gets the full
     * first-run experience again. Called from Settings →
     * «آموزش و راهنما ← نمایش دوباره».
     *
     * Re-arming the tour as well is intentional: replaying the
     * onboarding and then landing on an un-toured Home screen would be a
     * half-replay.
     */
    fun replayAll()

    /** Re-arm only the Home spotlight tour. */
    fun replayHomeTour()
}
