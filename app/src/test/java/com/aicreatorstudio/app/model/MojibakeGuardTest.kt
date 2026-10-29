package com.aicreatorstudio.app.model

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-13 — source-shape guard against the recurring Persian-string
 * corruption.
 *
 * ## History
 *
 * This project has now been bitten by the same write-time fault three
 * times: first the subtitle literals in `EditorViewModel`, then the AI
 * tool panel strings (both now grouped in [SubtitleMessages] and covered
 * by `SubtitleMessagesGuardTest`), and on 2026-10-13 four more
 * user-visible strings outside that file — the cloud-Whisper
 * "file too large" line, the media-relink failure, the model-download
 * failure and the unknown-AI-tool message. Each time the symptom was a
 * user reading `"??? ???? ???"`.
 *
 * `SubtitleMessagesGuardTest` only covers the constants in that one
 * file. This test closes the hole: it walks the whole main source set
 * and fails on any *code* line whose string literal carries the `???`
 * signature, so a future corruption anywhere is a red build rather than
 * a Persian user reading question marks.
 *
 * ## Why it is source-shape rather than behavioral
 *
 * The test stack cannot construct the ViewModel or the service that own
 * those literals, so the message text cannot be asserted at runtime.
 * The corruption leaves a textual signature that is unambiguous, and
 * that is exactly what this scans for.
 *
 * ## Deliberate limits
 *
 * Comment bodies are skipped, not verified. The historical comments
 * *document* the corruption by reproducing its signature; rewriting them
 * would erase the audit trail.
 */
class MojibakeGuardTest {

    /** A run of three or more '?' — the corruption signature. */
    private val signature = Regex("""\?{3,}""")

    private fun mainSources(): List<File> {
        val root = File("src/main/java")
        assertTrue("main source root not found from ${File("").absolutePath}", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /**
     * True when [line] is a comment or the inside of one. Three shapes:
     * a KDoc/block continuation line (` * ...`), a line that starts a
     * block comment, and a full-line `//` comment.
     */
    private fun isCommentLine(line: String): Boolean {
        val t = line.trim()
        if (t.startsWith("*")) return true
        if (t.startsWith("/*")) return true
        if (t.startsWith("//")) return true
        return false
    }

    /**
     * Removes the trailing `//` comment from [line] when the `//` is not
     * inside a string literal. A `//` before any quote on the line is
     * unambiguous; one after an odd number of quotes is inside a string
     * (e.g. `"content://media/1"`) and must be kept so the string check
     * below still sees the whole literal.
     */
    private fun stripLineComment(line: String): String {
        val idx = line.indexOf("//")
        if (idx < 0) return line
        val quotesBefore = line.take(idx).count { it == '"' }
        return if (quotesBefore % 2 == 0) line.take(idx) else line
    }

    /** Removes inline block comments so their contents are not scanned. */
    private fun stripBlockComments(line: String): String =
        Regex("""/\*.*?\*/""").replace(line, " ")

    @Test
    fun `no main-source code line carries the question-mark signature`() {
        val offenders = mutableListOf<String>()
        for (file in mainSources()) {
            file.readLines().forEachIndexed { index, raw ->
                if (isCommentLine(raw)) return@forEachIndexed
                val code = stripBlockComments(stripLineComment(raw))
                if (signature.containsMatchIn(code)) {
                    offenders += "${file.path}:${index + 1}: ${raw.trim().take(90)}"
                }
            }
        }
        assertTrue(
            "Persian string corruption found (a user would read these). " +
                "Fix each literal, do not weaken this test:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun `the scanner actually detects the signature`() {
        // A guard that cannot fail proves nothing. Both directions:
        // corrupted Persian, and a legitimate single question mark.
        assertTrue(signature.containsMatchIn("\"??? ???? ???\""))
        assertTrue(!signature.containsMatchIn("\"آیا مطمئنید؟\""))
        assertTrue(!isCommentLine("    val x = \"???\""))
        // A KDoc continuation line that documents the corruption passes.
        assertTrue(isCommentLine(" * \"??? ????????\" instead"))
    }
}
