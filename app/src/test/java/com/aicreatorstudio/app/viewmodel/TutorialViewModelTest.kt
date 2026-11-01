package com.aicreatorstudio.app.viewmodel

import com.aicreatorstudio.app.data.InMemoryTutorialPreferences
import com.aicreatorstudio.app.model.TutorialProgress
import com.aicreatorstudio.app.model.shouldShowHomeTour
import com.aicreatorstudio.app.model.shouldShowOnboarding
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tutorial's state machine, exercised through the real ViewModel with
 * an in-memory preferences fake — no Hilt, no Android, no Robolectric.
 *
 * The point of these tests is the CHAINING contract, which is the part
 * that is easy to get subtly wrong: finishing the onboarding must NOT
 * also mark the tour, otherwise the tour never runs; skipping must mark
 * both, otherwise the user is dropped into a tour they just declined.
 */
class TutorialViewModelTest {

    private fun viewModel(
        initial: TutorialProgress = TutorialProgress()
    ): Pair<TutorialViewModel, InMemoryTutorialPreferences> {
        val prefs = InMemoryTutorialPreferences(initial)
        return TutorialViewModel(prefs) to prefs
    }

    @Test
    fun `a fresh install shows the onboarding`() {
        val (vm, _) = viewModel()
        assertTrue(shouldShowOnboarding(vm.progress.value))
    }

    @Test
    fun `completing the onboarding hands over to the tour`() {
        val (vm, _) = viewModel()

        vm.completeOnboarding()

        assertFalse(
            "the onboarding must dismiss itself",
            shouldShowOnboarding(vm.progress.value)
        )
        assertTrue(
            "the tour is the SECOND stage and must still be pending — " +
                "marking it here would silently skip the guided tour for " +
                "every user",
            shouldShowHomeTour(vm.progress.value)
        )
    }

    @Test
    fun `skipping marks both stages`() {
        val (vm, _) = viewModel()

        vm.skipAllTutorials()

        assertFalse(shouldShowOnboarding(vm.progress.value))
        assertFalse(
            "«رد کردن» is one decision about the whole first-run " +
                "experience; dropping the user into a tour they just " +
                "declined is the bug this asserts against",
            shouldShowHomeTour(vm.progress.value)
        )
    }

    @Test
    fun `completing the tour only affects the tour`() {
        val (vm, _) = viewModel(
            TutorialProgress(
                onboardingVersion = com.aicreatorstudio.app.model.TUTORIAL_CONTENT_VERSION,
                homeTourSeen = false
            )
        )

        vm.completeHomeTour()

        assertFalse(shouldShowHomeTour(vm.progress.value))
    }

    @Test
    fun `replayAll re-arms both stages`() {
        val (vm, _) = viewModel(
            TutorialProgress(
                onboardingVersion = com.aicreatorstudio.app.model.TUTORIAL_CONTENT_VERSION,
                homeTourSeen = true
            )
        )

        vm.replayAll()

        assertTrue(shouldShowOnboarding(vm.progress.value))
        assertTrue(shouldShowHomeTour(vm.progress.value))
    }

    @Test
    fun `replayHomeTour does not re-arm the onboarding`() {
        val (vm, _) = viewModel(
            TutorialProgress(
                onboardingVersion = com.aicreatorstudio.app.model.TUTORIAL_CONTENT_VERSION,
                homeTourSeen = true
            )
        )

        vm.replayHomeTour()

        assertTrue(shouldShowHomeTour(vm.progress.value))
        assertFalse(
            "replaying the tour must not push the four-page onboarding " +
                "in front of a returning user",
            shouldShowOnboarding(vm.progress.value)
        )
    }

    @Test
    fun `marking the onboarding twice is idempotent`() {
        val (vm, prefs) = viewModel()

        vm.completeOnboarding()
        val afterFirst = vm.progress.value
        vm.completeOnboarding()

        assertTrue(
            "a second completion must be a no-op, not a state churn that " +
                "recomposes the Home screen for nothing",
            afterFirst == vm.progress.value
        )
        assertTrue(prefs.progress.value == afterFirst)
    }
}
