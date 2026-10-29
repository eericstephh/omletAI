package com.aicreatorstudio.app.data

import com.aicreatorstudio.app.model.hasUnreadUpdates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A non-persisted [AppUpdatePreferences] for tests (and previews).
 *
 * Lives beside the interface, matching
 * [com.aicreatorstudio.app.video.InMemoryProjectRepository], which sits beside
 * `ProjectRepository` for the same reason.
 *
 * Deliberately **not** annotated `@Inject`: nothing in production should be
 * able to reach for this by accident and silently lose the user's bookmark.
 * It is constructed explicitly, by tests.
 *
 * The behaviour is a faithful copy of [DefaultAppUpdatePreferences] —
 * including the upward clamp in [markUpdatesAsRead] — so a test that passes
 * here is testing the real semantics rather than a permissive stand-in. The
 * only difference is where the integer lives.
 */
class InMemoryAppUpdatePreferences(
    initialLastSeenVersionCode: Int = 0
) : AppUpdatePreferences {

    private val _lastSeenVersionCode = MutableStateFlow(initialLastSeenVersionCode)

    override val lastSeenVersionCode: StateFlow<Int> = _lastSeenVersionCode.asStateFlow()

    private val _hasUnreadUpdates = MutableStateFlow(
        hasUnreadUpdates(AppChangelogProvider.currentVersionCode, initialLastSeenVersionCode)
    )

    override val hasUnreadUpdates: StateFlow<Boolean> = _hasUnreadUpdates.asStateFlow()

    override fun markUpdatesAsRead(versionCode: Int) {
        val newMarker = versionCode.coerceAtLeast(_lastSeenVersionCode.value)
        _lastSeenVersionCode.value = newMarker
        _hasUnreadUpdates.value =
            hasUnreadUpdates(AppChangelogProvider.currentVersionCode, newMarker)
    }
}
