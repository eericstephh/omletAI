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
     * first. Kept to the last handful of releases — this is a "what's new"
     * surface, not a full product history.
     */
    val releases: List<AppReleaseNote> = listOf(
        AppReleaseNote(
            versionCode = 4,
            versionName = "1.4.0",
            releaseDate = "۸ مهر ۱۴۰۵",
            isCurrent = true,
            features = listOf(
                "اضافه شدن ردیابی حرکت سوژه (Motion Tracking) با متن و استیکر متحرک",
                "منحنی تغییر سرعت پیشرفته (Speed Ramping / Curves) با ۵ پریست آماده هالیوودی",
                "بهبود خودکار ابعاد و کادر برای ویدیوهای افقی و عمودی",
                "بهینه‌سازی نوار ابزار هوشمند ویرایشگر ویدیو",
                "افزودن زنگوله اطلاع‌رسانی تغییرات و آپدیت‌های جدید"
            )
        ),
        AppReleaseNote(
            versionCode = 3,
            versionName = "1.3.0",
            releaseDate = "۲۵ شهریور ۱۴۰۵",
            features = listOf(
                "زیرنویس فارسی هوشمند با استودیوی قالب و کلمات هم‌زمان (Karaoke)",
                "حذف پس‌زمینه ویدیو (Background Remove) با موتور ماتینگ داخلی",
                "بهبود کیفیت ویدیو با آپ‌اسکیلر هوشمند (Smart HD / 4K)",
                "پاک‌سازی صدا و حذف نویز گفتار (Voice Clean)"
            )
        ),
        AppReleaseNote(
            versionCode = 2,
            versionName = "1.2.0",
            releaseDate = "۱۰ شهریور ۱۴۰۵",
            features = listOf(
                "پنل‌های رنگ و نور حرفه‌ای (Lumetri) با محدوده رنگ زنده",
                "افکت‌های سینمایی آماده و پریست‌های فیلتر یک‌کلیکی",
                "مدیریت صدا: ولوم، محو شدن تدریجی و میکس خودکار",
                "کنترل سرعت ثابت کلیپ با حفظ هم‌زمانی صدا"
            )
        ),
        AppReleaseNote(
            versionCode = 1,
            versionName = "1.1.0",
            releaseDate = "۲۸ مرداد ۱۴۰۵",
            features = listOf(
                "ویرایشگر تایم‌لاین چندلایه با درگ، تریم و جابه‌جایی کلیپ",
                "انتقال‌های سینمایی (Transitions) بین کلیپ‌ها",
                "متن و استیکر روی ویدیو با درگ لمسی زنده",
                "خروجی سریع با کیفیت ۷۲۰p، ۱۰۸۰p و ۴K"
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
     * difference, so it is stripped: a debug install of 1.4.0 should read
     * "نسخه جاری 1.4.0", not "1.4.0-debug".
     */
    val currentVersionName: String get() = BuildConfig.VERSION_NAME.substringBefore('-')
}
