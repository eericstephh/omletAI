package com.aicreatorstudio.app.model

/**
 * UI state for the Home screen.
 *
 * This lives in `model` (not inside the screen or ViewModel file) so the
 * shape of screen state is easy to find and reuse from tests or previews,
 * matching the MVVM separation the rest of the app follows.
 *
 * ## The update fields are carried, not fetched
 *
 * [releases] / [currentVersionName] are pushed through here from
 * [com.aicreatorstudio.app.data.AppChangelogProvider] by the ViewModel rather
 * than read inside the composable. Two reasons: the sheet stays a pure
 * function of its inputs (so it can be previewed), and the composable never
 * reaches for a singleton behind the ViewModel's back.
 *
 * They are flattened onto this screen's state instead of nesting an
 * [AppUpdateUiState] because `HomeScreenContent` already takes this one object,
 * and a nested field would mean threading a second parameter through the
 * preview and the call site for four values that are only ever read together.
 *
 * @param projects every project the user has, newest first.
 * @param hasUnreadUpdates whether the notification bell should show its red
 *   dot — i.e. the running build is newer than the highest version the user
 *   has acknowledged. See [hasUnreadUpdates].
 * @param currentVersionCode the running build's version code.
 * @param currentVersionName the running build's display version, with the
 *   debug build type's suffix already stripped.
 * @param releases the changelog, newest first.
 */
data class HomeUiState(
    val projects: List<VideoProject> = emptyList(),
    val hasUnreadUpdates: Boolean = false,
    val currentVersionCode: Int = 0,
    val currentVersionName: String = "",
    val releases: List<AppReleaseNote> = emptyList()
)
