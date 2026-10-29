package com.aicreatorstudio.app.model

/**
 * One released version of the app, as shown in the "تغییرات و آپدیت‌های جدید"
 * sheet behind the Home-screen notification bell.
 *
 * This is deliberately plain data with no Android and no Compose dependency:
 * the changelog is authored by hand in
 * [com.aicreatorstudio.app.data.AppChangelogProvider], carried through
 * [com.aicreatorstudio.app.viewmodel.HomeViewModel] as part of [HomeUiState],
 * and rendered by `AppUpdateSheet`. Nothing in between needs to know how to
 * fetch it, so nothing in between has to be faked in a test.
 *
 * @param versionCode monotonic build number. This is the value the unread
 *   badge compares against — see [hasUnreadUpdates]. Must be strictly
 *   greater than the [versionCode] of every later entry in the list, because
 *   the list is ordered newest-first.
 * @param versionName the human-readable version, e.g. `"1.4.0"`. Shown to the
 *   user, never compared.
 * @param releaseDate a **pre-formatted, localised** date string (Persian
 *   calendar and Persian digits, e.g. `"۸ مهر ۱۴۰۵"`). Deliberately a
 *   `String` rather than an epoch: the changelog is authored copy, not data
 *   to be reformatted, and formatting it here would need a Persian calendar
 *   formatter that the JVM unit tests cannot exercise.
 * @param features bullet points, newest feature first. Each entry is a
 *   complete sentence fragment in Persian; the sheet renders one styled
 *   bullet per element. An empty list is a bug — a release with nothing to
 *   say should not be in the changelog at all — and
 *   `AppChangelogProviderTest` fails the build if one appears.
 * @param isCurrent `true` for exactly one entry: the release that describes
 *   the running build. The sheet uses it to highlight the newest card with a
 *   "جدید" chip. Enforced by test, not by convention.
 */
data class AppReleaseNote(
    val versionCode: Int,
    val versionName: String,
    val releaseDate: String,
    val features: List<String>,
    val isCurrent: Boolean = false
)

/**
 * What the Home screen needs in order to draw the bell, its badge and the
 * changelog sheet.
 *
 * [releases] is carried in the UI state rather than read from
 * [com.aicreatorstudio.app.data.AppChangelogProvider] inside the composable so
 * the sheet stays a pure function of its inputs — which is what lets it have a
 * `@Preview` and what keeps the MVVM boundary intact (the composable never
 * reaches for a singleton).
 */
data class AppUpdateUiState(
    val releases: List<AppReleaseNote> = emptyList(),
    val currentVersionCode: Int = 0,
    val currentVersionName: String = "",
    val hasUnreadUpdates: Boolean = false
)

/**
 * Whether the user has something they have not seen yet.
 *
 * Pure, top-level and trivially testable on purpose. The unread decision is
 * the one piece of logic in this feature that can be wrong in a way the user
 * notices (a badge that never appears, or one that never clears), and every
 * class around it — `AppUpdatePreferences` needs `SharedPreferences`,
 * `HomeViewModel` is a Hilt ViewModel with constructor injection — is
 * untestable on the JVM in this project. So the decision lives here, where it
 * is covered directly.
 *
 * `>` rather than `!=` is load-bearing: the seen marker is written from the
 * app's *current* version, so a downgrade (or a restored backup carrying a
 * higher marker) must not light the badge up again.
 *
 * A fresh install has `lastSeenVersionCode == 0`, so the very first launch
 * reports unread — which is the intended behaviour: a new user has, in fact,
 * never seen any of the changelog.
 */
fun hasUnreadUpdates(currentVersionCode: Int, lastSeenVersionCode: Int): Boolean =
    currentVersionCode > lastSeenVersionCode
