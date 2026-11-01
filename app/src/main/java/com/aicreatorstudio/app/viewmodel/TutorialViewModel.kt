package com.aicreatorstudio.app.viewmodel

import androidx.lifecycle.ViewModel
import com.aicreatorstudio.app.data.TutorialPreferences
import com.aicreatorstudio.app.model.TutorialProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Owns the tutorial bookkeeping for the two screens that touch it: Home
 * (shows the onboarding + tour) and Settings (replays them).
 *
 * ## Why a ViewModel at all, for two booleans
 *
 * Because the alternative — reaching into `SharedPreferences` from a
 * composable, or hoisting the state into `HomeViewModel` — is worse.
 * `HomeViewModel` already has a JVM test that constructs it directly, and
 * adding a constructor dependency there would break that test for a
 * feature that has nothing to do with the project list. A separate,
 * purpose-built ViewModel keeps the blast radius at zero.
 *
 * ## Why it holds no mutable state of its own
 *
 * `progress` is the preferences' own `StateFlow`, exposed directly. The
 * "is it showing?" decisions are derived by the pure functions in
 * `model/TutorialContent.kt`, and the *step index* is Compose-local
 * (`remember`) in the overlay — it is presentation state that must not
 * survive the overlay closing. Nothing here can drift from what is on
 * disk.
 */
@HiltViewModel
class TutorialViewModel @Inject constructor(
    private val preferences: TutorialPreferences
) : ViewModel() {

    val progress: StateFlow<TutorialProgress> = preferences.progress

    /**
     * The onboarding was read to the end. The Home tour is NOT marked:
     * the screen chains the two, so finishing the onboarding should
     * immediately start the tour.
     */
    fun completeOnboarding() {
        preferences.markOnboardingSeen()
    }

    /**
     * The user pressed «رد کردن». That is a single decision about the
     * whole first-run experience, so both stages are marked — otherwise
     * skipping the onboarding would drop the user straight into a
     * spotlight tour they just declined.
     */
    fun skipAllTutorials() {
        preferences.markOnboardingSeen()
        preferences.markHomeTourSeen()
    }

    /** The tour reached its last step, or the user dismissed it. */
    fun completeHomeTour() {
        preferences.markHomeTourSeen()
    }

    /** Settings → «آموزش و راهنما ← نمایش دوباره». Re-arms both stages. */
    fun replayAll() {
        preferences.replayAll()
    }

    /** Settings → replay only the Home tour. */
    fun replayHomeTour() {
        preferences.replayHomeTour()
    }
}
