package com.aicreatorstudio.app.model

/**
 * Onboarding / guided-tour content and the pure decisions around it.
 *
 * ## Why the copy lives in `model/` and not in the Compose files
 *
 * Two reasons, both practical:
 *
 *  1. **It is testable here.** The UI in `ui/tutorial/` cannot run on the
 *     JVM (it needs Compose's Android runtime), but the *content* and the
 *     *sequencing* can. `TutorialContentTest` asserts that every page has
 *     real Persian prose, that no string was corrupted to ASCII `?` at
 *     write time (the same defect class `SubtitleMessagesGuardTest`
 *     guards), and that the step/index arithmetic is correct.
 *  2. **There is exactly one copy.** The tour, the settings entry that
 *     replays it, and any future in-editor guide all read the same lists,
 *     so a re-worded step cannot disagree with itself.
 *
 * The strings are Persian literals used as `t()` keys — the established
 * convention in this app: `t("…")` looks the key up in
 * `LocalizedStrings` and falls back to the key itself, so an untranslated
 * entry still renders correct Persian rather than a blank.
 *
 * ## Versioning
 *
 * [TUTORIAL_CONTENT_VERSION] is a watermark, not a boolean. Bumping it
 * re-shows the onboarding to users who already saw an older version —
 * which is the point of a *tutorial*: when the app gains a major surface,
 * the "how this works" copy has to be re-read. A plain `seen = true` flag
 * could never do that. This mirrors `AppUpdatePreferences`, which stores
 * the highest version code the user has acknowledged for the same reason.
 */

/**
 * Bump when the onboarding copy changes enough that a returning user
 * should see it again. Stored in `TutorialPreferences` as the highest
 * version acknowledged.
 */
const val TUTORIAL_CONTENT_VERSION = 1

/**
 * A spot on the Home screen the tour can point at.
 *
 * These are *semantic* anchors, not coordinates: the Home screen attaches
 * them to the real composables with `Modifier.tutorialAnchor(...)`, and
 * the overlay reads back the measured bounds. Nothing here knows about
 * pixels, and moving a section does not break the tour.
 */
enum class TutorialTarget {
    /** The header row: settings, notifications, Telegram. */
    HEADER_SETTINGS,

    /** The "new project" / "import video" pair. */
    CREATE_PROJECT,

    /** The 3-per-row AI capability grid. */
    AI_CAPABILITIES,

    /** The user's own project list. */
    PROJECTS
}

/**
 * Which illustration an onboarding page shows.
 *
 * Deliberately an enum rather than an `ImageVector` field: `model/` must
 * not depend on Compose, and the icon choice is a presentation decision.
 * The AI page additionally receives the *real* capability icons from the
 * caller, so the tutorial cannot advertise a tool the app does not ship.
 */
enum class TutorialArt {
    WELCOME,
    TRANSCRIBE,
    AI_TOOLS,
    READY
}

/** One full-screen page of the first-run onboarding. */
data class TutorialPage(
    /** Stable id — also the Compose `key` so a page change is a real change. */
    val id: String,
    val title: String,
    val body: String,
    val art: TutorialArt
)

/** One step of the Home spotlight tour, pointing at a [TutorialTarget]. */
data class TutorialStep(
    val id: String,
    val title: String,
    val body: String,
    val target: TutorialTarget
)

/**
 * Everything persisted about the user's tutorial history.
 *
 * A single immutable value with two fields, rather than two loose
 * booleans in `SharedPreferences`, so there is no way for the two to
 * disagree and no partial write to reason about.
 */
data class TutorialProgress(
    /**
     * Highest [TUTORIAL_CONTENT_VERSION] the user has completed.
     * `0` = never seen anything (fresh install).
     */
    val onboardingVersion: Int = 0,
    /** Whether the Home spotlight tour has been completed or skipped. */
    val homeTourSeen: Boolean = false
)

/**
 * `true` once the user has completed the onboarding at the CURRENT
 * content version. A stored value from an older version is *not*
 * "seen" — that is what makes a version bump re-show it.
 */
fun hasSeenOnboarding(progress: TutorialProgress): Boolean =
    progress.onboardingVersion >= TUTORIAL_CONTENT_VERSION

/**
 * Whether the first-run onboarding should cover the Home screen.
 *
 * The Home screen calls this on every composition; it is a pure read of
 * a `StateFlow`, so completing the tutorial dismisses it without any
 * navigation event.
 */
fun shouldShowOnboarding(progress: TutorialProgress): Boolean =
    !hasSeenOnboarding(progress)

/**
 * Whether the spotlight tour should run.
 *
 * Intentionally independent of [shouldShowOnboarding]: the Home screen
 * chains them (onboarding first, tour immediately after), but the tour
 * can also be replayed on its own from Settings without the onboarding.
 */
fun shouldShowHomeTour(progress: TutorialProgress): Boolean =
    !progress.homeTourSeen

/**
 * Clamp [index] into `0 until size`.
 *
 * Returns `0` for an empty list. Used by both the onboarding pager and
 * the tour so that a step list that shrinks (or a restored index from a
 * process death) can never index out of bounds — the failure mode would
 * be an `IndexOutOfBoundsException` inside a `Canvas`, i.e. a crash on a
 * screen the user cannot escape.
 */
fun clampTutorialIndex(index: Int, size: Int): Int {
    if (size <= 0) return 0
    return index.coerceIn(0, size - 1)
}

/** `true` when [index] addresses the final item of a [size]-item list. */
fun isLastTutorialIndex(index: Int, size: Int): Boolean {
    if (size <= 0) return true
    return clampTutorialIndex(index, size) == size - 1
}

/**
 * Which `LazyColumn` item of the Home screen holds [target].
 *
 * The tour scrolls the list to this index before highlighting, so a step
 * whose element is below the fold still gets its hole. This mapping is a
 * fact about `HomeScreen`'s item order, kept here — and covered by a
 * test — because the failure mode is silent: the tour would simply
 * scroll to the wrong place and draw a ring around something unrelated.
 *
 * The order is `header`, `actions`, `features`, `projects-title`, then
 * either the empty state or the project rows. The first four indices are
 * therefore stable regardless of whether the user has projects.
 */
fun tutorialTargetItemIndex(target: TutorialTarget): Int = when (target) {
    TutorialTarget.HEADER_SETTINGS -> 0
    TutorialTarget.CREATE_PROJECT -> 1
    TutorialTarget.AI_CAPABILITIES -> 2
    TutorialTarget.PROJECTS -> 3
}

/**
 * Renders [value] with Persian-Indic digits (`۰۱۲۳۴۵۶۷۸۹`).
 *
 * The step counter («۱ از ۴») is the one place the tutorial shows a
 * number, and an ASCII `1` inside Persian prose reads as a foreign
 * character — the rest of the app already writes `۳۰ ثانیه`. Kept here
 * as a pure function so the mapping is tested rather than eyeballed.
 *
 * Negative values keep their sign as ASCII `-`, which is what a Persian
 * reader expects to see for a minus anyway.
 */
fun toPersianDigits(value: Int): String {
    val text = value.toString()
    return buildString(text.length) {
        text.forEach { ch ->
            // Anything that is not an ASCII digit (a leading '-') is
            // passed through untouched — indexing PERSIAN_DIGITS with a
            // negative offset would throw inside a draw pass.
            if (ch in '0'..'9') append(PERSIAN_DIGITS[ch - '0']) else append(ch)
        }
    }
}

private val PERSIAN_DIGITS = charArrayOf(
    '۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹'
)

/**
 * The first-run onboarding, in order.
 *
 * Four pages, each making ONE claim the app can keep:
 *
 *  1. what it is (and that it needs no account),
 *  2. the core flow (local Whisper — the video is never uploaded),
 *  3. the AI toolbox (a grid of the real tools, see [TutorialArt.AI_TOOLS]),
 *  4. how to start, plus where to find this again.
 *
 * Deliberately no numbers in the copy: the AI page renders the actual
 * `AI_CAPABILITIES` list, so writing "12 tools" here would be a second
 * source of truth that goes stale the moment a tool is added.
 */
val ONBOARDING_PAGES: List<TutorialPage> = listOf(
    TutorialPage(
        id = "welcome",
        title = "به املت AI خوش آمدید",
        body = "استودیوی ویرایش ویدیو با هوش مصنوعی — کاملاً فارسی، بدون نیاز به حساب کاربری، " +
            "و بیشتر پردازش‌ها روی خود گوشی انجام می‌شود.",
        art = TutorialArt.WELCOME
    ),
    TutorialPage(
        id = "transcribe",
        title = "از ویدیو تا زیرنویس فارسی",
        body = "ویدیو را وارد کنید و «زیرنویس هوشمند» را بزنید. Whisper روی همین گوشی زیرنویس " +
            "می‌سازد؛ ویدیوی شما هیچ‌وقت آپلود نمی‌شود.",
        art = TutorialArt.TRANSCRIBE
    ),
    TutorialPage(
        id = "ai_tools",
        title = "ابزارهای هوش مصنوعی",
        body = "از حذف پس‌زمینه و متن‌یابی تا افزایش کیفیت و برش سکوت — هر ابزار از داخل " +
            "ویرایشگر و با یک ضربه باز می‌شود.",
        art = TutorialArt.AI_TOOLS
    ),
    TutorialPage(
        id = "ready",
        title = "آماده‌ی شروع",
        body = "یک پروژه بسازید، ویدیو را وارد کنید و از نوار پایین صفحه ابزار دلخواه‌تان را " +
            "انتخاب کنید. هر وقت خواستید، این آموزش را از «تنظیمات ← آموزش و راهنما» دوباره ببینید.",
        art = TutorialArt.READY
    )
)

/**
 * The Home spotlight tour, in the order the eye travels down the screen:
 * header, primary actions, AI grid, then the user's own projects.
 *
 * Four steps, not twelve. A tour that highlights everything teaches
 * nothing; these four are the four *decisions* on this screen — where the
 * settings are, how to start, what the app can do, and where my work is.
 */
val HOME_TOUR_STEPS: List<TutorialStep> = listOf(
    TutorialStep(
        id = "header",
        title = "تنظیمات و اتصال هوش مصنوعی",
        body = "کلید Groq، زبان برنامه، تم و دانلود مدل‌ها از این‌جا در دسترس‌اند. " +
            "زنگ بالا هم تغییرات هر نسخه را نشان می‌دهد.",
        target = TutorialTarget.HEADER_SETTINGS
    ),
    TutorialStep(
        id = "create",
        title = "شروع یک پروژه",
        body = "با «پروژه جدید» یا «افزودن ویدیو»، ویدیو را از گالری انتخاب می‌کنید و " +
            "پروژه ساخته می‌شود.",
        target = TutorialTarget.CREATE_PROJECT
    ),
    TutorialStep(
        id = "ai",
        title = "قابلیت‌های هوش مصنوعی",
        body = "هر کاشی یک ابزار واقعی است، نه تبلیغ. با یک ضربه همان ابزار داخل ویرایشگر باز می‌شود.",
        target = TutorialTarget.AI_CAPABILITIES
    ),
    TutorialStep(
        id = "projects",
        title = "پروژه‌های من",
        body = "کارهای قبلی این‌جا می‌مانند. برای ادامه، یکی را انتخاب کنید.",
        target = TutorialTarget.PROJECTS
    )
)
