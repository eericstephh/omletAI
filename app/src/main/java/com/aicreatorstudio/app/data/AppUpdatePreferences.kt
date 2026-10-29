package com.aicreatorstudio.app.data

import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks how much of the changelog the user has already seen, and derives
 * whether the Home screen's notification bell should carry an unread badge.
 *
 * ## Why this is an interface
 *
 * [com.aicreatorstudio.app.viewmodel.HomeViewModel] takes it as a constructor
 * dependency, and `HomeViewModelTest` constructs that ViewModel directly on
 * the JVM. The real implementation needs `SharedPreferences`, which needs a
 * real `Context`, and this project has no Robolectric — so a concrete class
 * here would make the ViewModel untestable and force the existing test to be
 * deleted. The interface is what keeps it constructible.
 *
 * This mirrors [com.aicreatorstudio.app.ai.ModelInstaller] →
 * `ModelManager` and [com.aicreatorstudio.app.video.ProjectRepository] →
 * `RoomProjectRepository`: the consumer depends on the interface, the
 * concrete implementation is bound in `di/AppBindings.kt`, and tests use an
 * in-memory fake without going through Hilt.
 *
 * ## What is stored
 *
 * Exactly one integer: the highest `versionCode` the user has acknowledged.
 * Everything else is derived, so there is no second value that can disagree
 * with it. `0` is the "never seen anything" default, which is what a fresh
 * install gets and is why the badge appears on first launch.
 */
interface AppUpdatePreferences {

    /**
     * The highest version code the user has acknowledged. Exposed for
     * diagnostics and tests; consumers normally want [hasUnreadUpdates].
     */
    val lastSeenVersionCode: StateFlow<Int>

    /**
     * `true` while the running build is newer than anything the user has
     * acknowledged — i.e. while the bell should show its red dot.
     */
    val hasUnreadUpdates: StateFlow<Boolean>

    /**
     * Record that the user has seen everything up to [versionCode]
     * (defaults to the running build). Called from the sheet's "متوجه شدم"
     * button, which is what makes the red dot disappear.
     *
     * The marker never moves **backwards** — see
     * [DefaultAppUpdatePreferences.markUpdatesAsRead]. If the user is on a
     * downgraded build (or a restored backup brought a higher marker with it)
     * and acknowledges the older version, writing that lower value would make
     * the badge reappear the moment they upgraded again, for releases they had
     * already read. Clamping upward is the only direction that keeps the
     * marker a true "seen up to here" watermark.
     *
     * The default argument is declared here rather than on the override so
     * that callers holding the interface get it too.
     */
    fun markUpdatesAsRead(versionCode: Int = AppChangelogProvider.currentVersionCode)
}
