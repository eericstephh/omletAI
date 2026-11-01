package com.aicreatorstudio.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The theme choice's pure resolution logic — the part that decides what a
 * user's stored preference MEANS, and therefore the part that must never
 * drift.
 *
 * The failure modes pinned here are the ones a user actually sees: a typo'd
 * or future key silently reverting to the wrong theme, a persisted key that
 * cannot round-trip, and the legacy migration mapping to something other
 * than the documented brand default.
 */
class AppThemeTest {

    @Test
    fun `every persisted key round-trips`() {
        AppTheme.entries.forEach { theme ->
            assertEquals(theme, AppTheme.fromKey(theme.key))
        }
    }

    @Test
    fun `keys are unique and non-blank`() {
        val keys = AppTheme.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        keys.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun `null or unknown keys fall back to the brand default`() {
        assertEquals(AppTheme.VIOLET_NIGHT, AppTheme.fromKey(null))
        assertEquals(AppTheme.VIOLET_NIGHT, AppTheme.fromKey(""))
        assertEquals(AppTheme.VIOLET_NIGHT, AppTheme.fromKey("violet "))
        assertEquals(AppTheme.VIOLET_NIGHT, AppTheme.fromKey("VIOLET"))
        // A key from a FUTURE build (a theme added after this release and
        // then removed by a downgrade) must resolve, not crash.
        assertEquals(AppTheme.VIOLET_NIGHT, AppTheme.fromKey("future_theme"))
    }

    @Test
    fun `the legacy theme-mode ordinal maps to the brand dark theme`() {
        // 0 = DARK, 1 = LIGHT, 2 = SYSTEM in the old enum. LIGHT and SYSTEM
        // were never rendered as light (screens hardcode the dark base), so
        // every legacy value lands on the same dark identity — the mapping
        // is pinned so nobody "improves" one arm of it without noticing.
        (0..2).forEach { ordinal ->
            assertEquals(AppTheme.VIOLET_NIGHT, AppTheme.fromLegacyThemeModeOrdinal(ordinal))
        }
    }

    @Test
    fun `there are exactly three themes`() {
        // The user asked for three. Pinned so an accidental fourth entry
        // (which the settings picker would render but the KDoc denies) is
        // a deliberate act, not a slip.
        assertEquals(3, AppTheme.entries.size)
    }
}
