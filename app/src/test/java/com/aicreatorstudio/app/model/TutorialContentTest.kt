package com.aicreatorstudio.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the tutorial's content and the pure decisions around it.
 *
 * ## What this covers, and what it cannot
 *
 * The overlay itself is Compose on Android and cannot run here (no
 * Robolectric in this project). What CAN be pinned on the JVM is
 * everything that would be a *silent* defect:
 *
 *  - a page or step whose Persian text was corrupted to ASCII `?` at
 *    write time (the exact defect `SubtitleMessagesGuardTest` guards),
 *  - a step that points at a target no item on the Home screen anchors,
 *  - index arithmetic that can address a page that does not exist — which
 *    would throw inside a `Canvas` and crash the screen the user cannot
 *    escape,
 *  - a version watermark that never re-shows the onboarding.
 */
class TutorialContentTest {

    private val persianLetterRange = '\u0600'..'\u06FF'

    private fun assertRealPersian(label: String, text: String) {
        assertTrue("$label is blank", text.isNotBlank())
        assertFalse(
            "$label contains an ASCII '?', which is the write-time " +
                "corruption signature this project has been bitten by before",
            text.contains('?')
        )
        assertTrue(
            "$label contains no Persian letter — it was probably written in " +
                "the wrong script",
            text.any { it in persianLetterRange }
        )
    }

    @Test
    fun `onboarding pages are well formed`() {
        assertTrue(ONBOARDING_PAGES.isNotEmpty())
        ONBOARDING_PAGES.forEach { page ->
            assertTrue("page id must not be blank", page.id.isNotBlank())
            assertRealPersian("${page.id}.title", page.title)
            assertRealPersian("${page.id}.body", page.body)
        }
    }

    @Test
    fun `onboarding page ids are unique`() {
        val ids = ONBOARDING_PAGES.map { it.id }
        assertEquals(
            "duplicate page ids would make the Compose key ambiguous",
            ids.size,
            ids.toSet().size
        )
    }

    @Test
    fun `every onboarding art variant is used`() {
        // A page whose art is never rendered is dead weight; an art
        // variant with no page means the `when` in the UI has an
        // unreachable branch that nobody will notice is wrong.
        assertEquals(
            TutorialArt.entries.toSet(),
            ONBOARDING_PAGES.map { it.art }.toSet()
        )
    }

    @Test
    fun `tour steps are well formed`() {
        assertTrue(HOME_TOUR_STEPS.isNotEmpty())
        HOME_TOUR_STEPS.forEach { step ->
            assertTrue("step id must not be blank", step.id.isNotBlank())
            assertRealPersian("${step.id}.title", step.title)
            assertRealPersian("${step.id}.body", step.body)
        }
    }

    @Test
    fun `every tutorial target is anchored by exactly one tour step`() {
        val targets = HOME_TOUR_STEPS.map { it.target }
        assertEquals(
            "two steps pointing at the same element is a duplicated step",
            targets.size,
            targets.toSet().size
        )
        assertEquals(
            "a target with no step is dead code; a step with no target " +
                "cannot be rendered",
            TutorialTarget.entries.toSet(),
            targets.toSet()
        )
    }

    @Test
    fun `tour targets map to distinct list indices`() {
        val indices = TutorialTarget.entries.map { tutorialTargetItemIndex(it) }
        assertEquals(
            "two targets on the same LazyColumn item would scroll to the " +
                "same place and highlight the wrong element",
            indices.size,
            indices.toSet().size
        )
        // The Home list is: header, actions, features, projects-title,
        // then the rows. The four anchors must be the FIRST four.
        assertEquals(listOf(0, 1, 2, 3), indices.sorted())
    }

    @Test
    fun `clamp keeps a valid index and rescues an invalid one`() {
        assertEquals(0, clampTutorialIndex(0, 4))
        assertEquals(3, clampTutorialIndex(3, 4))
        assertEquals(0, clampTutorialIndex(-7, 4))
        assertEquals(3, clampTutorialIndex(99, 4))
    }

    @Test
    fun `clamp on an empty list is zero rather than a crash`() {
        // `pages.getOrNull(0)` then returns null and the overlay returns
        // early instead of indexing out of bounds inside a Canvas.
        assertEquals(0, clampTutorialIndex(0, 0))
        assertEquals(0, clampTutorialIndex(5, 0))
    }

    @Test
    fun `last index detection`() {
        assertFalse(isLastTutorialIndex(0, 4))
        assertTrue(isLastTutorialIndex(3, 4))
        assertTrue(isLastTutorialIndex(99, 4))
        assertTrue("an empty list has no next step", isLastTutorialIndex(0, 0))
    }

    @Test
    fun `persian digits replace every ascii digit`() {
        assertEquals("۰", toPersianDigits(0))
        assertEquals("۱", toPersianDigits(1))
        assertEquals("۴", toPersianDigits(4))
        assertEquals("۱۲", toPersianDigits(12))
        assertEquals("۱۴۰۲", toPersianDigits(1402))
    }

    @Test
    fun `persian digits leave a minus sign alone`() {
        // Indexing the digit table with a negative offset would throw
        // inside a draw pass, i.e. a crash with no stack the user can act
        // on. The sign is passed through instead.
        assertEquals("-۵", toPersianDigits(-5))
    }

    @Test
    fun `fresh install shows both stages`() {
        val fresh = TutorialProgress()
        assertTrue(shouldShowOnboarding(fresh))
        assertTrue(shouldShowHomeTour(fresh))
    }

    @Test
    fun `completed onboarding is not shown again at the same version`() {
        val done = TutorialProgress(
            onboardingVersion = TUTORIAL_CONTENT_VERSION,
            homeTourSeen = false
        )
        assertFalse(shouldShowOnboarding(done))
        assertTrue(
            "the tour is chained AFTER the onboarding, so completing the " +
                "onboarding must leave the tour pending",
            shouldShowHomeTour(done)
        )
    }

    @Test
    fun `a stored version below the current one re-shows the onboarding`() {
        // This is the whole point of storing a version instead of a
        // boolean: a major UI change should re-introduce the app.
        val stale = TutorialProgress(
            onboardingVersion = TUTORIAL_CONTENT_VERSION - 1,
            homeTourSeen = true
        )
        assertTrue(shouldShowOnboarding(stale))
    }

    @Test
    fun `the content version is a real watermark`() {
        assertTrue(
            "0 means 'never seen anything', so the shipped version must be " +
                "at least 1 or a fresh install would look completed",
            TUTORIAL_CONTENT_VERSION >= 1
        )
        assertNotEquals(0, TUTORIAL_CONTENT_VERSION)
    }
}
