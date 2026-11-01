package com.aicreatorstudio.app.data

import com.aicreatorstudio.app.model.TUTORIAL_CONTENT_VERSION
import com.aicreatorstudio.app.model.TutorialProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A non-persisted [TutorialPreferences] for tests (and previews).
 *
 * Lives beside the interface, matching [InMemoryAppUpdatePreferences].
 *
 * Deliberately **not** annotated `@Inject`: nothing in production should
 * be able to reach for this by accident and silently re-show the
 * onboarding to every user on every launch.
 *
 * The behaviour is a faithful copy of [DefaultTutorialPreferences] —
 * including the forward-only watermark in [markOnboardingSeen] — so a
 * test that passes here is testing the real semantics rather than a
 * permissive stand-in. The only difference is where the two values live.
 */
class InMemoryTutorialPreferences(
    initial: TutorialProgress = TutorialProgress()
) : TutorialPreferences {

    private val _progress = MutableStateFlow(initial)

    override val progress: StateFlow<TutorialProgress> = _progress.asStateFlow()

    override fun markOnboardingSeen() {
        val current = _progress.value
        if (current.onboardingVersion >= TUTORIAL_CONTENT_VERSION) return
        _progress.value = current.copy(onboardingVersion = TUTORIAL_CONTENT_VERSION)
    }

    override fun markHomeTourSeen() {
        val current = _progress.value
        if (current.homeTourSeen) return
        _progress.value = current.copy(homeTourSeen = true)
    }

    override fun replayAll() {
        _progress.value = TutorialProgress()
    }

    override fun replayHomeTour() {
        _progress.value = _progress.value.copy(homeTourSeen = false)
    }
}
