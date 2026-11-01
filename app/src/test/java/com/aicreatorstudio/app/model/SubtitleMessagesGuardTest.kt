package com.aicreatorstudio.app.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-11 Whisper compatibility investigation — §6 guard for the
 * user-facing Persian subtitle messages.
 *
 * ## The defect this guards against
 *
 * Several user-facing Persian string literals in the subtitle pipeline
 * had been corrupted to literal ASCII `?` characters at write time, so
 * a user with a failed Whisper run saw `"??? ????? ???"` instead of an
 * explanation. One of them
 * ([SUBTITLE_NO_SOURCE_MESSAGE_FA]) had already been diagnosed and
 * extracted to a constant; the rest stayed inline in `EditorViewModel`
 * and stayed broken.
 *
 * The fix was to move them into [SubtitleMessages]. These tests are the
 * reason the fix can't silently rot: they assert the constants are real
 * Persian prose and that the corruption detector itself works.
 *
 * ## Honest scope
 *
 * These tests prove the **constants** are well-formed. They do NOT
 * prove every call site in `EditorViewModel` uses them — that would
 * need an Android-context test of a 5,000-line ViewModel, which this
 * project's test stack cannot provide. The remaining inline literals
 * elsewhere in the app are inventoried separately in the audit report
 * rather than claimed as fixed.
 */
class SubtitleMessagesGuardTest {

    private val allMessages: List<Pair<String, String>> by lazy {
        listOf(
            "NO_SPEECH_FOUND_FA" to SubtitleMessages.NO_SPEECH_FOUND_FA,
            "NO_OVERLAYS_PRODUCED_FA" to SubtitleMessages.NO_OVERLAYS_PRODUCED_FA,
            "NO_MEDIA_SELECTED_FA" to SubtitleMessages.NO_MEDIA_SELECTED_FA,
            "modelInitFailed" to SubtitleMessages.modelInitFailed("دلیل"),
            "transcriptionFailed" to SubtitleMessages.transcriptionFailed("دلیل"),
            "srtExportFailed" to SubtitleMessages.srtExportFailed("دلیل"),
            "cloudModelUnavailable" to SubtitleMessages.cloudModelUnavailable("دلیل"),
            "subtitleError" to SubtitleMessages.subtitleError("دلیل"),
            // 2026-10-11 — the AI-tool panel strings were corrupted by
            // the same write-time fault and are covered here so they
            // cannot silently rot again.
            "AiTool.DOWNLOADING_FA" to SubtitleMessages.AiTool.DOWNLOADING_FA,
            "AiTool.VERIFYING_FA" to SubtitleMessages.AiTool.VERIFYING_FA,
            "AiTool.INSTALLING_FA" to SubtitleMessages.AiTool.INSTALLING_FA,
            "AiTool.READY_FA" to SubtitleMessages.AiTool.READY_FA,
            "AiTool.FAILED_FA" to SubtitleMessages.AiTool.FAILED_FA,
            "AiTool.NO_CLIP_SELECTED_FA" to SubtitleMessages.AiTool.NO_CLIP_SELECTED_FA,
            "AiTool.CANCELLED_FA" to SubtitleMessages.AiTool.CANCELLED_FA,
            "AiTool.COMPLETED_FA" to SubtitleMessages.AiTool.COMPLETED_FA,
            "AiTool.UNKNOWN_ERROR_FA" to SubtitleMessages.AiTool.UNKNOWN_ERROR_FA,
            // 2026-10-13 — the transport banner for an empty timeline.
            "Playback.NOTHING_TO_PLAY_FA" to SubtitleMessages.Playback.NOTHING_TO_PLAY_FA,
            // 2026-10-31 — SRT export now reports its own causes instead
            // of borrowing the generation-failure text.
            "NO_SUBTITLES_TO_EXPORT_FA" to SubtitleMessages.NO_SUBTITLES_TO_EXPORT_FA,
            "SRT_DESTINATION_UNAVAILABLE_FA" to SubtitleMessages.SRT_DESTINATION_UNAVAILABLE_FA,
            // 2026-11-01 — Persian Subtitle AI Correction. The privacy
            // notice is the highest-stakes string in this file: if it
            // ever degrades to `???`, the user is asked to approve a
            // text upload they cannot read.
            "NO_SUBTITLES_TO_CORRECT_FA" to SubtitleMessages.NO_SUBTITLES_TO_CORRECT_FA,
            "CORRECTION_PRIVACY_NOTICE_FA" to SubtitleMessages.CORRECTION_PRIVACY_NOTICE_FA,
            "CORRECTION_TITLE_FA" to SubtitleMessages.CORRECTION_TITLE_FA,
            "CORRECTION_SUBTITLE_FA" to SubtitleMessages.CORRECTION_SUBTITLE_FA
        )
    }

    @Test
    fun `every message is non-blank`() {
        for ((name, message) in allMessages) {
            assertTrue("$name is blank", message.isNotBlank())
        }
    }

    @Test
    fun `every message contains Persian letters`() {
        for ((name, message) in allMessages) {
            assertTrue(
                "$name contains no Persian characters — it may have been " +
                    "corrupted again. Got: $message",
                message.any { it.code in 0x0600..0x06FF }
            )
        }
    }

    @Test
    fun `no message is a run of question marks`() {
        for ((name, message) in allMessages) {
            assertFalse(
                "$name looks like the mojibake defect (question-mark run): $message",
                Regex("""\?{3,}""").containsMatchIn(message)
            )
        }
    }

    @Test
    fun `every message passes the shared corruption guard`() {
        // The guard itself is the contract other code can reuse; if a
        // message fails it, that is a hard failure, not a warning.
        for ((name, message) in allMessages) {
            assertTrue(
                "$name failed SubtitleMessages.assertNoMojibake — the guard " +
                    "rejects it, which means a user would see it as broken. " +
                    "Got: $message",
                SubtitleMessages.assertNoMojibake(message)
            )
        }
    }

    @Test
    fun `detail is interpolated into the templated messages`() {
        // A templated message that silently drops its detail leaves the
        // user with a generic sentence and no cause — the exact problem
        // this work set out to fix.
        val detail = "کد-۱۲۳"
        assertTrue(SubtitleMessages.modelInitFailed(detail).contains(detail))
        assertTrue(SubtitleMessages.transcriptionFailed(detail).contains(detail))
        assertTrue(SubtitleMessages.srtExportFailed(detail).contains(detail))
        assertTrue(SubtitleMessages.cloudModelUnavailable(detail).contains(detail))
        assertTrue(SubtitleMessages.subtitleError(detail).contains(detail))
    }

    // ---- The guard's own behaviour ----

    @Test
    fun `guard rejects the actual corruption signature`() {
        // This is verbatim what the user saw before the fix.
        assertFalse(
            SubtitleMessages.assertNoMojibake(
                "????? ??????? ?????? ???: timeout"
            )
        )
    }

    @Test
    fun `guard rejects a blank message`() {
        assertFalse(SubtitleMessages.assertNoMojibake(""))
        assertFalse(SubtitleMessages.assertNoMojibake("   "))
    }

    @Test
    fun `guard accepts real Persian prose`() {
        assertTrue(
            SubtitleMessages.assertNoMojibake(
                "صدای قابل رونویسی در این ویدیو پیدا نشد"
            )
        )
    }

    @Test
    fun `guard tolerates a single legitimate question mark`() {
        assertTrue(
            SubtitleMessages.assertNoMojibake(
                "فایل مدل را دانلود کرده‌اید؟"
            )
        )
    }

    @Test
    fun `guard tolerates ASCII text with letters`() {
        // The guard is about corruption, not about requiring Persian.
        assertTrue(SubtitleMessages.assertNoMojibake("Model file is corrupt"))
    }

    // ---- Repo-wide regression scan ----

    @Test
    fun `no live Kotlin string literal in main sources is a question-mark run`() {
        // ## Why this scans the source tree
        //
        // The corruption is a WRITE-TIME fault, not a runtime one: a
        // Persian literal gets persisted as ASCII '?'. Nothing at
        // runtime can detect it, and no type checker can either. The
        // only reliable guard is a static scan, so this test walks the
        // main sources and fails if a *live* string literal (not a
        // comment) consists of question marks.
        //
        // Comments are skipped on purpose: several comments deliberately
        // quote the corrupted text to document the defect.
        val mainSrc = locateMainSources()
            ?: run {
                // Fail loudly rather than pass vacuously — a scan that
                // silently skips is worse than no scan.
                org.junit.Assert.fail(
                    "Could not locate app/src/main/java from cwd " +
                        File(".").absolutePath
                )
                return
            }

        val offenders = mutableListOf<String>()
        val stringLiteral = Regex("""\"([^\"\\]*)\"""")
        val mojibake = Regex("""\?{3,}""")

        mainSrc.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { idx, line ->
                    val trimmed = line.trimStart()
                    // Skip comments — they intentionally quote the defect.
                    if (trimmed.startsWith("//") ||
                        trimmed.startsWith("*") ||
                        trimmed.startsWith("/*")
                    ) return@forEachIndexed

                    for (m in stringLiteral.findAll(line)) {
                        val literal = m.groupValues[1]
                        if (!mojibake.containsMatchIn(literal)) continue
                        // Require the literal to be "all punctuation" —
                        // a run of '?' with no letters or digits. A
                        // legitimate message never looks like this.
                        val stripped = literal.replace("?", "")
                        if (stripped.any { it.isLetterOrDigit() }) continue
                        offenders += "${file.name}:${idx + 1}: $literal"
                    }
                }
            }

        assertTrue(
            "Found ${offenders.size} live Kotlin string literal(s) that are " +
                "question-mark runs — the write-time Persian corruption has " +
                "reappeared. Fix them (move to SubtitleMessages) or, if the " +
                "line is a comment, the scanner is wrong:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    private fun locateMainSources(): File? {
        var dir: File? = File(".").absoluteFile
        repeat(8) {
            if (dir?.resolve("settings.gradle.kts")?.isFile == true) {
                val f = dir.resolve("app/src/main/java")
                if (f.isDirectory) return f
            }
            dir = dir?.parentFile
        }
        val direct = File("app/src/main/java")
        return if (direct.isDirectory) direct else null
    }
}
