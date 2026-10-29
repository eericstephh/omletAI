package com.aicreatorstudio.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-12 — release vs debug logging policy.
 *
 * The brief requires verbose DEBUG logging to be off in release builds
 * while warnings and errors remain available for user-triggered
 * diagnostics. The decision is a pure function
 * ([LogLevel.defaultMinimum] / [LogLevel.isEnabled]) precisely so it can
 * be tested without a build-type-specific classpath.
 */
class LoggingPolicyTest {

    @Test
    fun `debug builds record everything from DEBUG up`() {
        val min = LogLevel.defaultMinimum(isDebugBuild = true)
        assertEquals(LogLevel.DEBUG, min)
        for (level in LogLevel.entries) {
            assertTrue("debug build must record $level", LogLevel.isEnabled(level, min))
        }
    }

    @Test
    fun `release builds drop DEBUG and INFO but keep WARN and ERROR`() {
        val min = LogLevel.defaultMinimum(isDebugBuild = false)
        assertEquals(LogLevel.WARN, min)
        assertFalse("release must drop DEBUG", LogLevel.isEnabled(LogLevel.DEBUG, min))
        assertFalse("release must drop INFO", LogLevel.isEnabled(LogLevel.INFO, min))
        assertTrue("release must keep WARN", LogLevel.isEnabled(LogLevel.WARN, min))
        assertTrue("release must keep ERROR", LogLevel.isEnabled(LogLevel.ERROR, min))
    }

    @Test
    fun `raising the threshold never re-enables a lower level`() {
        val thresholds = LogLevel.entries
        for (min in thresholds) {
            for (level in thresholds) {
                val expected = level.rank >= min.rank
                assertEquals(
                    "isEnabled($level, $min)",
                    expected,
                    LogLevel.isEnabled(level, min)
                )
            }
        }
    }
}
