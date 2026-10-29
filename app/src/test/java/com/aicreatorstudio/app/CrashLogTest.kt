package com.aicreatorstudio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun boom(): IllegalStateException =
        try {
            throw IllegalStateException("boom-marker")
        } catch (t: IllegalStateException) {
            t
        }

    // ---- buildEntry ----

    @Test
    fun `entry carries header fields and the stack trace`() {
        val entry = CrashLog.buildEntry(
            timestampMillis = 1_700_000_000_000L,
            appVersion = "1.4.0 (4)",
            device = "Pixel 8 / Android 35",
            threadName = "main",
            throwable = boom()
        )
        assertTrue(entry.startsWith(CrashLog.ENTRY_SEPARATOR))
        assertTrue(entry.contains("CRASH @ "))
        assertTrue(entry.contains("app: 1.4.0 (4)"))
        assertTrue(entry.contains("device: Pixel 8 / Android 35"))
        assertTrue(entry.contains("thread: main"))
        assertTrue(entry.contains("boom-marker"))
        assertTrue(entry.contains("IllegalStateException"))
        assertTrue(entry.endsWith("\n"))
    }

    @Test
    fun `entry falls back to question mark for a null thread name`() {
        val entry = CrashLog.buildEntry(0L, "v", "d", null, boom())
        assertTrue(entry.contains("thread: ?"))
    }

    // ---- appendCapped ----

    @Test
    fun `append creates the history file and keeps the newest entry`() {
        val file: File = tmp.newFile("crash_history.txt")
        CrashLog.appendCapped(file, "FIRST\n", maxBytes = 1000)
        CrashLog.appendCapped(file, "SECOND\n", maxBytes = 1000)
        val content = file.readText()
        assertTrue(content.contains("FIRST"))
        assertTrue(content.contains("SECOND"))
        assertTrue(content.indexOf("SECOND") > content.indexOf("FIRST"))
    }

    @Test
    fun `append drops the oldest half when the cap is exceeded`() {
        val file: File = tmp.newFile("crash_history.txt")
        val bigEntry = "X".repeat(600) + "\n"
        CrashLog.appendCapped(file, bigEntry, maxBytes = 1000)
        CrashLog.appendCapped(file, bigEntry, maxBytes = 1000)
        CrashLog.appendCapped(file, "NEWEST\n", maxBytes = 1000)
        val content = file.readText()
        assertTrue(content.length <= 1000 + "NEWEST\n".length)
        assertTrue(content.contains("NEWEST"))
        // The first crash was oldest — it must be gone.
        assertTrue(!content.contains(bigEntry + bigEntry))
    }

    @Test
    fun `append never splits an entry mid-stack when trimming`() {
        val file: File = tmp.newFile("crash_history.txt")
        val sep = CrashLog.ENTRY_SEPARATOR
        CrashLog.appendCapped(file, sep + "OLD-CRASH-1\n" + "y".repeat(700), maxBytes = 1000)
        CrashLog.appendCapped(file, sep + "OLD-CRASH-2\n" + "z".repeat(700), maxBytes = 1000)
        CrashLog.appendCapped(file, sep + "NEW-CRASH\n", maxBytes = 1000)
        val content = file.readText()
        assertTrue(content.contains("NEW-CRASH"))
        // Every kept entry still begins with a full separator — nothing
        // was sliced mid-trace.
        val starts = content.split(sep).drop(1)
        assertTrue(starts.isNotEmpty())
        starts.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun `append works when the history file does not exist yet`() {
        val file = File(tmp.root, "never_created.txt")
        CrashLog.appendCapped(file, "ONLY\n", maxBytes = 100)
        assertEquals("ONLY\n", file.readText())
    }

    @Test
    fun `a single oversized entry is still preserved in full`() {
        val file = File(tmp.root, "oversize.txt")
        val entry = "HUGE".repeat(1000) + "\n" // 4 KB entry, 1 KB cap
        CrashLog.appendCapped(file, entry, maxBytes = 1024)
        assertTrue(file.readText().contains(entry.trim()))
    }
}
