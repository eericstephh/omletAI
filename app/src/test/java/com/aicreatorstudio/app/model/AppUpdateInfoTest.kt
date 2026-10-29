package com.aicreatorstudio.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the unread-update decision.
 *
 * [hasUnreadUpdates] is the one piece of logic in the "App Update &
 * Changelog" feature that can be wrong in a way the user actually notices —
 * a red dot that never appears, or one that never goes away — and everything
 * around it is untestable on the JVM here: `AppUpdatePreferences` needs
 * `SharedPreferences` (no Robolectric in this project) and `HomeViewModel` is
 * a Hilt ViewModel with constructor injection. So the decision lives in a
 * pure top-level function and is covered directly.
 *
 * Plain `Int` / `Boolean` only; nothing here touches `android.*`, which
 * `unitTests.isReturnDefaultValues = true` would silently stub.
 */
class AppUpdateInfoTest {

    // ---- The comparison itself ----

    @Test
    fun `a fresh install has unread updates`() {
        // lastSeen == 0 is the "never acknowledged anything" default, so the
        // badge must appear on the very first launch.
        assertTrue(hasUnreadUpdates(currentVersionCode = 4, lastSeenVersionCode = 0))
    }

    @Test
    fun `a strictly newer build is unread`() {
        assertTrue(hasUnreadUpdates(currentVersionCode = 4, lastSeenVersionCode = 3))
    }

    @Test
    fun `the acknowledged build is not unread`() {
        assertFalse(hasUnreadUpdates(currentVersionCode = 4, lastSeenVersionCode = 4))
    }

    @Test
    fun `a marker from the future is not unread`() {
        // A downgraded build, or a restored backup carrying a higher marker.
        // `>` rather than `!=` is what keeps the badge dark here; with `!=`
        // this case would light the badge up again for releases the user has
        // already read.
        assertFalse(hasUnreadUpdates(currentVersionCode = 4, lastSeenVersionCode = 9))
    }

    @Test
    fun `zero against zero is not unread`() {
        // Degenerate but reachable: a build whose versionCode is 0 on a
        // device that has acknowledged nothing. Must not divide the badge
        // from the rest of the app's behaviour.
        assertFalse(hasUnreadUpdates(currentVersionCode = 0, lastSeenVersionCode = 0))
    }

    @Test
    fun `the gap size does not matter`() {
        // One release behind and ten releases behind are both simply "unread".
        // There is no severity tier, so no accidental second threshold.
        assertTrue(hasUnreadUpdates(currentVersionCode = 5, lastSeenVersionCode = 4))
        assertTrue(hasUnreadUpdates(currentVersionCode = 14, lastSeenVersionCode = 4))
    }

    // ---- Data shape ----

    @Test
    fun `a release note is not current unless it says so`() {
        // `isCurrent` must never be implied. If it defaulted to true, every
        // card in the sheet would render its "جدید" chip.
        val note = AppReleaseNote(
            versionCode = 1,
            versionName = "1.1.0",
            releaseDate = "۲۸ مرداد ۱۴۰۵",
            features = listOf("چیزی")
        )
        assertFalse(note.isCurrent)
    }

    @Test
    fun `the update ui state defaults to empty and read`() {
        // The pre-load default must not claim there is something to read:
        // a spurious badge on every cold start is worse than a late one.
        val state = AppUpdateUiState()
        assertTrue(state.releases.isEmpty())
        assertFalse(state.hasUnreadUpdates)
        assertEquals(0, state.currentVersionCode)
        assertEquals("", state.currentVersionName)
    }

    @Test
    fun `the home ui state carries the update fields`() {
        val state = HomeUiState(
            hasUnreadUpdates = true,
            currentVersionCode = 4,
            currentVersionName = "1.4.0",
            releases = listOf(
                AppReleaseNote(
                    versionCode = 4,
                    versionName = "1.4.0",
                    releaseDate = "۸ مهر ۱۴۰۵",
                    features = listOf("قابلیت تازه"),
                    isCurrent = true
                )
            )
        )
        assertTrue(state.hasUnreadUpdates)
        assertEquals(4, state.currentVersionCode)
        assertEquals("1.4.0", state.currentVersionName)
        assertEquals(1, state.releases.size)
        assertTrue(state.releases.first().isCurrent)
    }
}
