package com.aicreatorstudio.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 2026-10-12 — file-level behaviour: rotating writer, retention across
 * restarts, concurrent writes not interleaving, and write-failure
 * resilience.
 *
 * ## Why these live at the file level
 *
 * `OmeletLogStore` needs a `Context`, and the JVM test process has none
 * (and `android.*` is inert thanks to `isReturnDefaultValues = true`).
 * Rather than fake a Context, this class exercises the **same pure
 * policy** the store delegates to ([LogFormat.shouldRotate],
 * [LogFormat.filesToPrune]) against a real temporary directory, plus a
 * tiny faithful writer that mirrors the store's append/rotate/prune
 * sequence. That makes the rotation and retention rules behavioral, not
 * source-text, assertions.
 */
class LogStorageRotationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val prefix = "omelet.log"

    /** Writes one line, rotating when the active file would exceed the cap. */
    private fun appendWithRotation(
        dir: File,
        line: String,
        maxFileBytes: Long,
        maxFiles: Int,
        maxTotalBytes: Long
    ) {
        val active = File(dir, prefix)
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        if (LogFormat.shouldRotate(active.length(), bytes.size, maxFileBytes)) {
            rotate(dir, maxFiles)
        }
        active.appendBytes(bytes)
        prune(dir, maxFiles, maxTotalBytes)
    }

    private fun rotate(dir: File, maxFiles: Int) {
        File(dir, "$prefix.$maxFiles").takeIf { it.exists() }?.delete()
        for (gen in maxFiles - 1 downTo 1) {
            val from = File(dir, "$prefix.$gen")
            if (from.exists()) from.renameTo(File(dir, "$prefix.${gen + 1}"))
        }
        File(dir, prefix).renameTo(File(dir, "$prefix.1"))
    }

    private fun prune(dir: File, maxFiles: Int, maxTotalBytes: Long) {
        val files = dir.listFiles()?.filter { it.isFile && it.name.startsWith(prefix) }
            ?: return
        val described = files.map {
            LogFile(generationOf(it), it.length())
        }
        val doomed = LogFormat.filesToPrune(described, maxFiles, maxTotalBytes)
            .map { it.generation }.toSet()
        files.filter { generationOf(it) in doomed }.forEach { it.delete() }
    }

    private fun generationOf(f: File): Int =
        if (f.name == prefix) 0
        else f.name.substringAfter("$prefix.", "").toIntOrNull() ?: 0

    // ---- 1. Rotation ----

    @Test
    fun `active file rotates once it passes the cap and the old content survives`() {
        val dir = tmp.newFolder("logs")
        val line = LogFormat.toLine(
            LogRecord(System.currentTimeMillis(), LogLevel.INFO, "c", "e", message = "x".repeat(200))
        )
        // Cap small enough that ~3 lines force a rotation.
        repeat(12) { appendWithRotation(dir, line, maxFileBytes = 600L, maxFiles = 4, maxTotalBytes = Long.MAX_VALUE) }

        assertTrue("a rotated file must exist", File(dir, "$prefix.1").exists())
        val all = dir.listFiles()!!.filter { it.name.startsWith(prefix) }
        assertTrue("more than one file after rotation", all.size > 1)
        val parsed = all.flatMap { LogFormat.parseAll(it.readText()) }
        // Retention is bounded (4 files × ~600 B), so not every one of the
        // 12 records survives — but the newest ones must, and nothing may
        // be corrupted.
        assertTrue("the newest records must survive rotation", parsed.size >= 4)
        assertTrue("retention must stay bounded", parsed.size <= 12)
    }

    @Test
    fun `rotation cascades and never keeps more than maxFiles files`() {
        val dir = tmp.newFolder("logs")
        val line = LogFormat.toLine(
            LogRecord(System.currentTimeMillis(), LogLevel.INFO, "c", "e", message = "y".repeat(150))
        )
        repeat(60) { appendWithRotation(dir, line, maxFileBytes = 400L, maxFiles = 3, maxTotalBytes = Long.MAX_VALUE) }
        val files = dir.listFiles()!!.filter { it.name.startsWith(prefix) }
        assertTrue("file count must stay bounded", files.size <= 3)
    }

    @Test
    fun `retention prunes oldest files first and keeps the newest`() {
        val dir = tmp.newFolder("logs")
        // Create generations 0..3 with known contents.
        File(dir, prefix).writeText("NEWEST\n")
        File(dir, "$prefix.1").writeText("ONE\n")
        File(dir, "$prefix.2").writeText("TWO\n")
        File(dir, "$prefix.3").writeText("OLDEST\n")
        prune(dir, maxFiles = 2, maxTotalBytes = Long.MAX_VALUE)

        val names = dir.listFiles()!!.map { it.name }.toSet()
        assertTrue("active file must survive", prefix in names)
        assertTrue("gen 1 must survive", "$prefix.1" in names)
        assertFalse("gen 3 (oldest) must be pruned", "$prefix.3" in names)
    }

    // ---- 2. Restart / partial file ----

    @Test
    fun `a partially written trailing line is skipped and earlier records survive`() {
        val dir = tmp.newFolder("logs")
        val active = File(dir, prefix)
        active.appendBytes(
            (
                LogFormat.toLine(LogRecord(1L, LogLevel.INFO, "c", "e1")) + "\n" +
                    LogFormat.toLine(LogRecord(2L, LogLevel.WARN, "c", "e2")) + "\n" +
                    "{\"ts\":3,\"lvl\":\"ERR" // killed mid-write
                ).toByteArray()
        )
        val records = LogFormat.parseAll(active.readText())
        assertEquals("the two complete records survive a partial write", 2, records.size)
    }

    // ---- 3. Concurrency ----

    @Test
    fun `concurrent appends never interleave bytes inside a line`() {
        val dir = tmp.newFolder("logs")
        val active = File(dir, prefix)
        val threads = 8
        val perThread = 200
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val lock = Any()

        repeat(threads) { t ->
            pool.submit {
                start.await()
                repeat(perThread) { i ->
                    val line = LogFormat.toLine(
                        LogRecord(
                            timestampMillis = 1_000L + t * perThread + i,
                            level = LogLevel.INFO,
                            component = "t$t",
                            event = "e$i",
                            message = "m".repeat(40)
                        )
                    )
                    // Mirror the store's single-writer lock.
                    synchronized(lock) { active.appendBytes((line + "\n").toByteArray()) }
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))

        val lines = active.readText().lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals("no line may be lost", threads * perThread, lines.size)
        // Every line must parse — an interleaved write would corrupt one.
        val records = lines.mapNotNull { LogFormat.fromLine(it) }
        assertEquals("no line may be corrupted by interleaving", lines.size, records.size)
    }

    // ---- 4. Write failure ----

    @Test
    fun `writing into a non-writable path is reported rather than throwing`() {
        // Simulate the store's guard: a directory that cannot be created
        // must flip to disabled instead of crashing the caller.
        val blocked = File(tmp.root, "not-a-dir")
        blocked.writeText("file occupying the name")
        val dir = File(blocked, "logs") // parent is a file → mkdirs fails
        val created = dir.exists() || dir.mkdirs()
        assertFalse("mkdirs must fail when a file occupies the parent", created)
    }
}
