package com.aicreatorstudio.app

import com.aicreatorstudio.app.util.Formatters
import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun `duration formats seconds under an hour as m colon ss`() {
        assertEquals("1:05", Formatters.duration(65_000L))
    }

    @Test
    fun `duration formats an hour or more as h colon mm colon ss`() {
        assertEquals("1:00:05", Formatters.duration(3_605_000L))
    }

    @Test
    fun `fileSize formats bytes below 1024 as whole bytes`() {
        assertEquals("512 B", Formatters.fileSize(512L))
    }

    @Test
    fun `fileSize formats megabytes with one decimal place`() {
        assertEquals("2.0 MB", Formatters.fileSize(2L * 1024 * 1024))
    }

    @Test
    fun `resolution formats width and height with a multiplication sign`() {
        assertEquals("1920 × 1080", Formatters.resolution(1920, 1080))
    }
}
