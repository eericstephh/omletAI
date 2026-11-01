package com.aicreatorstudio.app.data

import com.aicreatorstudio.app.BuildConfig
import com.aicreatorstudio.app.model.AppReleaseNote

/**
 * The app's hand-authored changelog — the content behind the Home screen's
 * notification bell.
 *
 * ## Why this is an object and not a repository
 *
 * The changelog is **authored copy**, not data. It ships inside the APK, it
 * has no remote source, it is never queried and it is never mutated. Wrapping
 * it in an interface with a fake implementation would buy nothing that a
 * plain immutable list does not already give: the list is already
 * deterministic, so a test can assert on it directly.
 *
 * ## Adding a release
 *
 * 1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`.
 * 2. Prepend a new [AppReleaseNote] here and set `isCurrent = true` on it,
 *    clearing `isCurrent` from the previous newest entry.
 *
 * Both steps are required, and step 1 is enforced: `AppChangelogProviderTest`
 * asserts that the newest entry's `versionCode` equals
 * [BuildConfig.VERSION_CODE]. Forget the bump and the test suite fails, rather
 * than the badge silently failing to appear on real devices.
 *
 * ## Ordering contract
 *
 * Newest first. [currentRelease] is `first()`, and the unread comparison
 * relies on the newest entry carrying the highest `versionCode`.
 * `AppChangelogProviderTest` checks both.
 */
object AppChangelogProvider {

    /**
     * Every release we want the user to be able to look back at, newest
     * first.
     *
     * 2026-11-01 — the history was deliberately **wiped** for the store
     * release «1.1»: the 1.1.0–1.4.0 entries described a dev line that was
     * never published under those names, so the bell now opens on exactly
     * one card describing what THIS release ships. Future releases prepend
     * above it, per "Adding a release" in the class KDoc.
     */
    val releases: List<AppReleaseNote> = listOf(
        AppReleaseNote(
            versionCode = 5,
            versionName = "1.1",
            releaseDate = "۱۰ آبان ۱۴۰۵",
            isCurrent = true,
            features = listOf(
                "اصلاح هوشمند املای زیرنویس فارسی با هوش مصنوعی، همراه با پیش‌نمایش و تأیید پیش از اعمال",
                "آموزش اولیه و تور راهنمای صفحهٔ اصلی برای آشنایی سریع با محیط و قابلیت‌ها",
                "امکان نمایش دوبارهٔ آموزش‌ها از بخش «تنظیمات ← آموزش و راهنما»",
                "۱۲ ابزار هوش مصنوعی در ویرایشگر: زیرنویس هوشمند، حذف پس‌زمینه، متن‌یابی، افزایش کیفیت، برش سکوت و سینک ضرب",
                "بهبود پایداری پردازش ویدیو و رفع مشکلات خروجی زیرنویس"
            )
        )
    )

    /** The release that describes the build the user is running. */
    val currentRelease: AppReleaseNote get() = releases.first()

    /**
     * The running build's version code — the app's own metadata, read from
     * `BuildConfig` so it cannot be forgotten the way a hand-copied literal
     * can. `AppChangelogProviderTest` pins it to [currentRelease]'s
     * `versionCode`, which is what makes this a single source of truth
     * instead of two values that merely happen to agree today.
     */
    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE

    /**
     * The running build's display version.
     *
     * The debug build type appends `-debug` (`versionNameSuffix` in
     * `app/build.gradle.kts`), which is build metadata rather than a version
     * difference, so it is stripped: a debug install of 1.1 should read
     * "نسخه جاری 1.1", not "1.1-debug".
     */
    val currentVersionName: String get() = BuildConfig.VERSION_NAME.substringBefore('-')
}
