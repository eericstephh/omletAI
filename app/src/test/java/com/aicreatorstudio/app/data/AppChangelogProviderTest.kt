package com.aicreatorstudio.app.data

import com.aicreatorstudio.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the hand-authored changelog.
 *
 * The changelog is a plain `val` of plain data, so most of what could go wrong
 * with it is a *content* mistake rather than a logic mistake — an entry
 * inserted out of order, a release with no bullets, two entries both claiming
 * to be current, or the whole list drifting away from the version actually
 * being shipped. None of those throw; they just render a subtly wrong sheet.
 * These tests turn each of them into a build failure.
 *
 * The last one is the important one: `AppChangelogProvider.currentVersionCode`
 * reads `BuildConfig`, while `releases` is typed by hand. That is two sources
 * of truth for one fact, and the drift guard below is what binds them.
 */
class AppChangelogProviderTest {

    // ---- Shape ----

    @Test
    fun `the changelog is not empty`() {
        // The bell's sheet renders nothing at all if this is empty, and the
        // badge would still light up from the version comparison — a red dot
        // that opens an empty panel.
        assertTrue(
            "AppChangelogProvider.releases must contain at least one release",
            AppChangelogProvider.releases.isNotEmpty()
        )
    }

    @Test
    fun `every release has at least one feature bullet`() {
        // A release with no bullets is a card that renders as an empty box.
        // If a version genuinely has nothing to say, it should not be listed.
        val empty = AppChangelogProvider.releases.filter { it.features.isEmpty() }
        assertTrue(
            "these releases have no feature bullets: " +
                empty.joinToString { it.versionName },
            empty.isEmpty()
        )
    }

    @Test
    fun `every feature bullet is non-blank`() {
        // Whitespace-only strings survive `isNotEmpty()` but render as an
        // empty bullet row with a dangling dot.
        val blank = AppChangelogProvider.releases.flatMap { release ->
            release.features
                .filter { it.isBlank() }
                .map { "${release.versionName} -> \"$it\"" }
        }
        assertTrue("blank feature bullets: ${blank.joinToString()}", blank.isEmpty())
    }

    @Test
    fun `every release has a version name and a date`() {
        val malformed = AppChangelogProvider.releases.filter {
            it.versionName.isBlank() || it.releaseDate.isBlank()
        }
        assertTrue(
            "releases missing a version name or date: " +
                malformed.joinToString { it.versionCode.toString() },
            malformed.isEmpty()
        )
    }

    // ---- Ordering contract ----

    @Test
    fun `version codes strictly descend from newest to oldest`() {
        // The unread comparison and `currentRelease == first()` both depend on
        // "newest first" being true of the data, not just of the comment. A
        // release inserted in the middle would otherwise be silently treated
        // as the current one.
        val codes = AppChangelogProvider.releases.map { it.versionCode }
        val strictlyDescending = codes.zipWithNext().all { (newer, older) -> newer > older }
        assertTrue(
            "expected strictly descending version codes, got $codes",
            strictlyDescending
        )
    }

    @Test
    fun `the first release carries the highest version code`() {
        val codes = AppChangelogProvider.releases.map { it.versionCode }
        assertEquals(
            "the newest release must be first",
            codes.max(),
            codes.first()
        )
    }

    @Test
    fun `exactly one release is marked current`() {
        // Zero => no "جدید" chip anywhere. Two or more => two cards claim to
        // be the running build.
        val currentCount = AppChangelogProvider.releases.count { it.isCurrent }
        assertEquals(
            "exactly one release must have isCurrent = true",
            1,
            currentCount
        )
    }

    @Test
    fun `the current release is the newest one`() {
        // The chip is meant to mark what the user just received, so it must
        // sit on the top card. This also protects `currentRelease`, which is
        // defined as `first()`.
        assertTrue(
            "the newest release must be the one flagged isCurrent",
            AppChangelogProvider.releases.first().isCurrent
        )
        assertTrue(
            "currentRelease must be the flagged release",
            AppChangelogProvider.currentRelease.isCurrent
        )
    }

    @Test
    fun `only the newest release is current`() {
        // Belt and braces for the chip: every release after the first must be
        // an older one, so none of them may be flagged.
        val olderFlagged = AppChangelogProvider.releases
            .drop(1)
            .filter { it.isCurrent }
        assertTrue(
            "older releases flagged as current: " +
                olderFlagged.joinToString { it.versionName },
            olderFlagged.isEmpty()
        )
    }

    // ---- Drift guard ----

    @Test
    fun `the newest changelog entry matches the real build version code`() {
        // The changelog is hand-written; BuildConfig.VERSION_CODE is not. This
        // is the seam where the two are tied together, and it is the whole
        // reason `currentVersionCode` reads BuildConfig instead of repeating a
        // literal. Bump one without the other and this fails the build, rather
        // than the badge silently failing to appear on real devices.
        assertEquals(
            "app/build.gradle.kts versionCode must match " +
                "AppChangelogProvider.releases.first().versionCode",
            BuildConfig.VERSION_CODE,
            AppChangelogProvider.currentRelease.versionCode
        )
    }

    @Test
    fun `the newest changelog entry matches the real build version name`() {
        // The debug build type appends "-debug" via versionNameSuffix, so the
        // comparison is made against the suffix-stripped form — the same
        // normalisation the UI shows.
        assertEquals(
            "app/build.gradle.kts versionName must match " +
                "AppChangelogProvider.releases.first().versionName",
            BuildConfig.VERSION_NAME.substringBefore('-'),
            AppChangelogProvider.currentRelease.versionName
        )
    }

    @Test
    fun `currentVersionCode and currentVersionName come from the running build`() {
        // These two are what the sheet header and the unread comparison use,
        // so they must track BuildConfig rather than the changelog list.
        assertEquals(BuildConfig.VERSION_CODE, AppChangelogProvider.currentVersionCode)
        assertEquals(
            BuildConfig.VERSION_NAME.substringBefore('-'),
            AppChangelogProvider.currentVersionName
        )
    }

    @Test
    fun `the stripped version name carries no build-type suffix`() {
        // `substringBefore('-')` is only correct while versionNameSuffix is the
        // sole source of a hyphen. If a version name ever legitimately
        // contained one (e.g. a release candidate), this would silently
        // truncate it — so the assumption is asserted rather than assumed.
        assertFalse(
            "the display version must not contain a hyphen: " +
                AppChangelogProvider.currentVersionName,
            AppChangelogProvider.currentVersionName.contains('-')
        )
    }

    // ---- The feature this release is about ----

    @Test
    fun `the current release announces the AI Persian subtitle correction`() {
        // 2026-11-01 — the headline feature of the «1.1» store release. A
        // changelog card that quietly loses it would defeat the reason the
        // announcement exists at all.
        val bullets = AppChangelogProvider.currentRelease.features.joinToString(" ")
        assertTrue(
            "the current release should mention the AI Persian subtitle correction",
            bullets.contains("اصلاح هوشمند املای زیرنویس فارسی")
        )
    }

    @Test
    fun `the current release announces the onboarding tutorial`() {
        // The tutorial is the other user-visible addition of this release.
        val bullets = AppChangelogProvider.currentRelease.features.joinToString(" ")
        assertTrue(
            "the current release should mention the onboarding tutorial",
            bullets.contains("آموزش")
        )
    }

    @Test
    fun `the current release mentions the AI tool count`() {
        // Pinned as a count, not as a list: the number must stay in sync
        // with the real capability catalogue, and the sheet is the one
        // place a user is told how many tools exist.
        val bullets = AppChangelogProvider.currentRelease.features.joinToString(" ")
        assertTrue(
            "the current release should mention the AI tool set",
            bullets.contains("۱۲ ابزار هوش مصنوعی")
        )
    }
}
