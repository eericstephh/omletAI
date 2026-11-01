package com.aicreatorstudio.app.ui.tutorial

import com.aicreatorstudio.app.model.TutorialTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-01 — the tutorial must actually be WIRED, not merely built.
 *
 * Compose UI cannot be rendered under this project's JVM test stack (no
 * Robolectric), so the connection between the model, the Home screen and
 * Settings is asserted structurally against the real source. This is the
 * guard for the failure mode this codebase has hit before: a feature that
 * ships with a green test suite and zero call sites.
 *
 * ## What is pinned, and why each one matters
 *
 *  - **Every [TutorialTarget] is anchored in `HomeScreen`.** A step whose
 *    target is never recorded draws a uniform scrim with no hole and
 *    points at nothing — a silent, plausible-looking failure.
 *  - **The anchors are published through the CompositionLocal.** Without
 *    the provider, `tutorialAnchor(...)` returns a no-op `Modifier` and
 *    every recorded rect is `null`; the tour still "runs", it just never
 *    highlights anything.
 *  - **Both overlays are composed by `HomeScreen`.** The onboarding and
 *    the tour are the feature; a `when` that lost a branch would leave a
 *    user permanently on a scrim.
 *  - **Settings can re-arm both stages.** The tutorial is one-time by
 *    design, so this entry is the only way back.
 *
 * ## Honest scope
 *
 * `contains(...)` on source text cannot prove the call is *reached*, only
 * that it exists and is not commented out. It also does not prove the
 * rendering is correct — that needs the device. What it does prove is
 * that nobody can delete the wiring while the tests stay green.
 */
class TutorialWiringTest {

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "settings.gradle.kts not found above ${File(".").absolutePath}"
        )
    }

    private fun read(relativePath: String): String {
        val f = File(repoRoot(), relativePath)
        assertTrue("missing file: $f", f.isFile)
        return f.readText()
    }

    private fun homeScreen() = read(
        "app/src/main/java/com/aicreatorstudio/app/ui/screens/home/HomeScreen.kt"
    )

    private fun settingsScreen() = read(
        "app/src/main/java/com/aicreatorstudio/app/ui/screens/settings/SettingsScreen.kt"
    )

    private fun bindings() = read(
        "app/src/main/java/com/aicreatorstudio/app/di/AppBindings.kt"
    )

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    @Test
    fun `every tutorial target is anchored on the home screen`() {
        val source = homeScreen()
        val anchored = occurrences(source, "tutorialAnchor(TutorialTarget.")
        assertEquals(
            "every TutorialTarget needs exactly one anchor, otherwise that " +
                "tour step highlights nothing",
            TutorialTarget.entries.size,
            anchored
        )
        // And each specific target by name — a count alone would pass if
        // the same target were anchored four times.
        TutorialTarget.entries.forEach { target ->
            assertTrue(
                "HomeScreen does not anchor $target",
                source.contains("tutorialAnchor(TutorialTarget.$target)")
            )
        }
    }

    @Test
    fun `the anchor registry is published to the composition`() {
        assertTrue(
            "without the provider, tutorialAnchor() returns Modifier and " +
                "every rect stays null",
            homeScreen().contains("LocalTutorialAnchors provides")
        )
    }

    @Test
    fun `the home screen composes both tutorial stages`() {
        val source = homeScreen()
        assertTrue("the onboarding is not composed", source.contains("OnboardingOverlay("))
        assertTrue("the spotlight tour is not composed", source.contains("TutorialSpotlight("))
        assertTrue(
            "the tour must pass the measured rect, not null",
            source.contains("tutorialAnchors.rectOf(")
        )
    }

    @Test
    fun `the tour scrolls to the step's item before highlighting it`() {
        assertTrue(
            "a target below the fold would draw a ring around nothing",
            homeScreen().contains("animateScrollToItem(")
        )
    }

    @Test
    fun `settings can re-arm both tutorial stages`() {
        val source = settingsScreen()
        assertTrue(
            "«نمایش دوبارهٔ آموزش اولیه» must call replayAll()",
            source.contains("tutorialVm.replayAll()")
        )
        assertTrue(
            "«نمایش دوبارهٔ تور صفحهٔ اصلی» must call replayHomeTour()",
            source.contains("tutorialVm.replayHomeTour()")
        )
    }

    @Test
    fun `the tutorial preferences are bound in the graph`() {
        val source = bindings()
        assertTrue(
            "TutorialViewModel would fail to inject without this binding",
            source.contains("bindTutorialPreferences")
        )
        assertTrue(source.contains("DefaultTutorialPreferences"))
    }
}
