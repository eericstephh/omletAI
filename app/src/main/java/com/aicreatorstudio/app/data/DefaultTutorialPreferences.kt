package com.aicreatorstudio.app.data

import android.content.Context
import android.content.SharedPreferences
import com.aicreatorstudio.app.model.TUTORIAL_CONTENT_VERSION
import com.aicreatorstudio.app.model.TutorialProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real, persisted [TutorialPreferences].
 *
 * Named `Default…` and bound to the interface in `di/AppBindings.kt`,
 * matching [DefaultAppUpdatePreferences].
 *
 * ## Storage shape
 *
 * Two primitives in their own file:
 *  - `tutorial_onboarding_version` — an int watermark, never `-1`;
 *  - `tutorial_home_tour_seen` — a boolean.
 *
 * Both are read once at construction and then kept in a
 * [MutableStateFlow], so the Compose screens observe one object instead
 * of polling `SharedPreferences`. Every writer updates the flow and the
 * file together, which is what stops a rotation from resurrecting the
 * onboarding.
 *
 * ## Testability
 *
 * `SharedPreferences` needs a real `Context`, so this class cannot run on
 * the JVM in this project. It is kept deliberately thin — no branching
 * beyond the two replay methods — and the real semantics are exercised
 * against [InMemoryTutorialPreferences], which is a faithful copy.
 */
@Singleton
class DefaultTutorialPreferences @Inject constructor(
    @ApplicationContext context: Context
) : TutorialPreferences {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _progress = MutableStateFlow(
        TutorialProgress(
            onboardingVersion = prefs.getInt(KEY_ONBOARDING_VERSION, NEVER_SEEN),
            homeTourSeen = prefs.getBoolean(KEY_HOME_TOUR_SEEN, false)
        )
    )

    override val progress: StateFlow<TutorialProgress> = _progress.asStateFlow()

    override fun markOnboardingSeen() {
        val current = _progress.value
        // Only ever move forward: re-running an OLD build after a newer
        // one already completed the tour must not lower the watermark and
        // re-show everything on the next upgrade.
        if (current.onboardingVersion >= TUTORIAL_VERSION_WRITTEN) return
        prefs.edit().putInt(KEY_ONBOARDING_VERSION, TUTORIAL_VERSION_WRITTEN).apply()
        _progress.value = current.copy(onboardingVersion = TUTORIAL_VERSION_WRITTEN)
    }

    override fun markHomeTourSeen() {
        val current = _progress.value
        if (current.homeTourSeen) return
        prefs.edit().putBoolean(KEY_HOME_TOUR_SEEN, true).apply()
        _progress.value = current.copy(homeTourSeen = true)
    }

    override fun replayAll() {
        prefs.edit()
            .putInt(KEY_ONBOARDING_VERSION, NEVER_SEEN)
            .putBoolean(KEY_HOME_TOUR_SEEN, false)
            .apply()
        _progress.value = TutorialProgress()
    }

    override fun replayHomeTour() {
        prefs.edit().putBoolean(KEY_HOME_TOUR_SEEN, false).apply()
        _progress.value = _progress.value.copy(homeTourSeen = false)
    }

    private companion object {
        /** Its own file — see the interface KDoc for why. */
        const val PREFS_NAME = "app_tutorial_prefs"

        /** Pinned as `const` so a rename fails loudly instead of silently
         *  re-showing the onboarding to every existing user. */
        const val KEY_ONBOARDING_VERSION = "tutorial_onboarding_version"
        const val KEY_HOME_TOUR_SEEN = "tutorial_home_tour_seen"

        /** "Never seen anything" — the fresh-install state. */
        const val NEVER_SEEN = 0

        /**
         * The version this build writes when the onboarding completes.
         *
         * Taken from the model constant rather than a literal so the
         * stored value and `shouldShowOnboarding`'s comparison can never
         * drift apart.
         */
        const val TUTORIAL_VERSION_WRITTEN: Int = TUTORIAL_CONTENT_VERSION
    }
}
