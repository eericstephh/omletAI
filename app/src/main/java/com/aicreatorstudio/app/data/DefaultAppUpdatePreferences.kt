package com.aicreatorstudio.app.data

import android.content.Context
import android.content.SharedPreferences
import com.aicreatorstudio.app.model.hasUnreadUpdates
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real, persisted [AppUpdatePreferences]: one integer in its own
 * `SharedPreferences` file.
 *
 * Named `Default…` to match [com.aicreatorstudio.app.ai.DefaultNetworkClient].
 * Bound to the interface in `di/AppBindings.kt`.
 *
 * ## Why its own `SharedPreferences` file
 *
 * [com.aicreatorstudio.app.settings.SettingsRepository] already owns an
 * `app_settings` file, and the obvious shortcut would be to add one more key
 * to it. It is deliberately not done: `SettingsRepository` is the user's
 * *preferences* — things they chose — while this is a *bookmark* the app
 * maintains about its own content. They have different lifetimes (clearing
 * preferences should not resurrect an unread badge; wiping the app's update
 * bookmark should not reset the user's export quality), and keeping them in
 * separate files makes that separation real rather than notional.
 *
 * ## Testability
 *
 * This class is only the storage; the decision it feeds lives in
 * [com.aicreatorstudio.app.model.hasUnreadUpdates] as a pure function and is
 * covered directly by unit tests. `SharedPreferences` needs a real `Context`,
 * so this class cannot run on the JVM in this project (there is no
 * Robolectric) and is kept as thin as possible for exactly that reason.
 * Tests use `InMemoryAppUpdatePreferences` instead.
 */
@Singleton
class DefaultAppUpdatePreferences @Inject constructor(
    @ApplicationContext context: Context
) : AppUpdatePreferences {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _lastSeenVersionCode =
        MutableStateFlow(prefs.getInt(KEY_LAST_SEEN_VERSION_CODE, NEVER_SEEN))

    override val lastSeenVersionCode: StateFlow<Int> = _lastSeenVersionCode.asStateFlow()

    /**
     * Held as its own `MutableStateFlow` rather than derived with `map` +
     * `stateIn` because that would need a `CoroutineScope` in a `@Singleton`
     * with no lifecycle to attach it to, and would leave the badge cold until
     * the first collector arrived. Two plain fields updated together cannot
     * go stale and cannot be missed.
     */
    private val _hasUnreadUpdates = MutableStateFlow(
        hasUnreadUpdates(AppChangelogProvider.currentVersionCode, _lastSeenVersionCode.value)
    )

    override val hasUnreadUpdates: StateFlow<Boolean> = _hasUnreadUpdates.asStateFlow()

    override fun markUpdatesAsRead(versionCode: Int) {
        val newMarker = versionCode.coerceAtLeast(_lastSeenVersionCode.value)
        if (newMarker != _lastSeenVersionCode.value) {
            prefs.edit().putInt(KEY_LAST_SEEN_VERSION_CODE, newMarker).apply()
            _lastSeenVersionCode.value = newMarker
        }
        // Recomputed unconditionally: cheap, and it keeps the two flows
        // consistent even on the no-op path above.
        _hasUnreadUpdates.value =
            hasUnreadUpdates(AppChangelogProvider.currentVersionCode, newMarker)
    }

    private companion object {
        /** Its own file — see the class KDoc for why it is not in `app_settings`. */
        const val PREFS_NAME = "app_update_prefs"

        /**
         * Pinned as a `const` so a rename fails loudly instead of silently
         * orphaning the user's bookmark and re-showing every past release.
         */
        const val KEY_LAST_SEEN_VERSION_CODE = "last_seen_update_version_code"

        /** "Never acknowledged anything" — the fresh-install state. */
        const val NEVER_SEEN = 0
    }
}
