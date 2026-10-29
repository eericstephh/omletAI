package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 2026-10-11 Whisper compatibility investigation — §6
 * device-independent tests for [WhisperDiagnostics].
 *
 * ## What these tests do and do NOT prove
 *
 * They prove the **diagnostic layer** behaves correctly: a file that
 * is missing, truncated, or the wrong exact size is classified
 * distinctly, the Persian text for each stage is real Persian, a
 * filesystem path cannot leak into a log line, and the failure object
 * keeps its machine-readable stage through a `Result`.
 *
 * They do **NOT** prove that native inference works. The JVM unit test
 * process has no Android JNI loader and no `libwhisper.so`, so any
 * claim about real native behaviour from this file would be false.
 * Real inference is covered by the instrumentation tests, which are
 * documented as not run in this environment.
 *
 * The distinction matters because the audit's whole point is that
 * "it compiles / it passes" is not evidence that a device can run the
 * model.
 */
class WhisperDiagnosticsTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ---- 1. Model file classification ----

    @Test
    fun `missing model file is reported as not existing and not truncated`() {
        val missing = File(temp.root, "ggml-small.bin")
        val artifact = WhisperDiagnostics.inspectModel(
            file = missing,
            expectedMinBytes = 480_000_000L,
            expectedBytes = 487_601_967L
        )
        assertFalse("a non-existent file must report exists=false", artifact.exists)
        assertEquals(0L, artifact.actualBytes)
        // A missing file is NOT "truncated" — those are different stages
        // and must stay distinguishable. Truncated implies bytes on disk.
        assertFalse(
            "a missing file must not be classified as truncated",
            artifact.isTruncated
        )
        assertFalse(artifact.isWrongSize)
    }

    @Test
    fun `file in the minSizeBytes gap is caught by the exact-size gate`() {
        // This is the real defect the audit found: minSizeBytes is
        // 480,000,000 but the true artefact is 487,601,967. A download
        // truncated inside that 7.2 MB band clears the floor. The
        // exact-size fields must still flag it.
        val truncated = File(temp.root, "ggml-small.bin")
        truncated.writeBytes(ByteArray(483_000_000))

        val artifact = WhisperDiagnostics.inspectModel(
            file = truncated,
            expectedMinBytes = 480_000_000L,
            expectedBytes = 487_601_967L
        )
        assertTrue(artifact.exists)
        assertFalse(
            "483 MB is above the 480 MB floor, so the floor alone misses it",
            artifact.isTruncated
        )
        assertTrue(
            "the exact-size gate must catch a file inside the floor gap",
            artifact.isWrongSize
        )
    }

    @Test
    fun `file below the floor is truncated`() {
        val partial = File(temp.root, "ggml-small.bin")
        partial.writeBytes(ByteArray(1_000))

        val artifact = WhisperDiagnostics.inspectModel(
            file = partial,
            expectedMinBytes = 480_000_000L,
            expectedBytes = 487_601_967L
        )
        assertTrue(artifact.isTruncated)
        assertTrue(artifact.isWrongSize)
    }

    @Test
    fun `correctly sized file is neither truncated nor wrong-sized`() {
        val file = File(temp.root, "ggml-small.bin")
        file.writeBytes(ByteArray(64))

        val artifact = WhisperDiagnostics.inspectModel(
            file = file,
            expectedMinBytes = 64L,
            expectedBytes = 64L
        )
        assertTrue(artifact.exists)
        assertFalse(artifact.isTruncated)
        assertFalse(artifact.isWrongSize)
    }

    @Test
    fun `unknown expected size never reports wrong size`() {
        // `null` means "the manifest has no size for this model", which
        // must be treated as unknown — never as "correct". A null must
        // also never produce a false isWrongSize, which would block a
        // perfectly good install.
        val file = File(temp.root, "ggml-small.bin")
        file.writeBytes(ByteArray(128))

        val artifact = WhisperDiagnostics.inspectModel(
            file = file,
            expectedMinBytes = 64L,
            expectedBytes = null
        )
        assertFalse(
            "an unknown expected size must not be reported as a wrong size",
            artifact.isWrongSize
        )
        assertFalse(artifact.isTruncated)
    }

    // ---- 2. Privacy: no paths, no content ----

    @Test
    fun `summary never contains the absolute path`() {
        val dir = temp.newFolder("models")
        val file = File(dir, "ggml-small.bin")
        file.writeBytes(ByteArray(32))

        val artifact = WhisperDiagnostics.inspectModel(
            file = file,
            expectedMinBytes = 1L,
            expectedBytes = 32L
        )
        val summary = artifact.summary()
        assertTrue(
            "summary must name the file so a report is diagnosable",
            summary.contains("ggml-small.bin")
        )
        assertFalse(
            "summary must NOT leak the absolute path. Got: $summary",
            summary.contains(dir.absolutePath)
        )
        assertFalse(
            "summary must NOT contain a path separator run. Got: $summary",
            summary.contains(temp.root.absolutePath)
        )
    }

    @Test
    fun `model artifact carries a directory NAME not a directory path`() {
        val dir = temp.newFolder("whisper-ggml-small")
        val file = File(dir, "ggml-small.bin")
        file.writeBytes(ByteArray(16))

        val artifact = WhisperDiagnostics.inspectModel(
            file = file,
            expectedMinBytes = 1L
        )
        assertEquals("whisper-ggml-small", artifact.dirName)
        assertFalse(
            "dirName must be a leaf name, not a full path",
            artifact.dirName.contains("/")
        )
    }

    // ---- 3. Stage enum + Persian copy ----

    @Test
    fun `every stage has a non-blank Persian message`() {
        for (stage in WhisperDiagnostics.Stage.entries) {
            val message = WhisperDiagnostics.persianFor(stage)
            assertTrue(
                "stage $stage produced a blank message",
                message.isNotBlank()
            )
            assertTrue(
                "stage $stage message must contain Persian letters. Got: $message",
                message.any { it.code in 0x0600..0x06FF }
            )
        }
    }

    @Test
    fun `no stage message is mojibake`() {
        // The exact corruption signature this codebase has hit before:
        // literal ASCII '?' runs where Persian used to be.
        for (stage in WhisperDiagnostics.Stage.entries) {
            val message = WhisperDiagnostics.persianFor(stage)
            assertFalse(
                "stage $stage message looks corrupted (question-mark run): $message",
                Regex("""\?{3,}""").containsMatchIn(message)
            )
        }
    }

    @Test
    fun `all expected stages exist`() {
        // The enum is the diagnosis vocabulary. If a stage is renamed
        // or dropped, downstream reports silently change meaning, so
        // pin the set explicitly.
        val expected = setOf(
            "NATIVE_SELFTEST",
            "MODEL_MISSING",
            "MODEL_SIZE_MISMATCH",
            "MODEL_SHA_MISMATCH",
            "NATIVE_INIT_NULL",
            "NATIVE_INIT_OK",
            "AUDIO_DECODE",
            "INFERENCE_EMPTY",
            "INFERENCE_ERROR"
        )
        assertEquals(
            expected,
            WhisperDiagnostics.Stage.entries.map { it.name }.toSet()
        )
    }

    // ---- 4. Failure propagation keeps the stage ----

    @Test
    fun `WhisperFailure preserves stage message and detail`() {
        val failure = WhisperFailure(
            stage = WhisperDiagnostics.Stage.MODEL_SIZE_MISMATCH,
            message = "پیام آزمایشی",
            detail = "actual=100 expectedMin=200"
        )
        assertEquals(WhisperDiagnostics.Stage.MODEL_SIZE_MISMATCH, failure.stage)
        assertEquals("پیام آزمایشی", failure.message)
        assertEquals("actual=100 expectedMin=200", failure.detail)
        assertTrue("stage must appear in toString for a bug report", failure.toString().contains("MODEL_SIZE_MISMATCH"))
    }

    @Test
    fun `WhisperFailure survives Result failure with its stage intact`() {
        // The engine returns Result.failure(WhisperFailure(...)). The
        // ViewModel must be able to read the stage back without the
        // type being flattened to a bare Exception.
        val failure = WhisperFailure(
            stage = WhisperDiagnostics.Stage.NATIVE_INIT_NULL,
            message = "native init failed"
        )
        val result: Result<Unit> = Result.failure(failure)
        assertTrue(result.isFailure)
        val recovered = result.exceptionOrNull()
        assertNotNull(recovered)
        assertTrue(
            "the concrete WhisperFailure must survive Result",
            recovered is WhisperFailure
        )
        assertEquals(
            WhisperDiagnostics.Stage.NATIVE_INIT_NULL,
            (recovered as WhisperFailure).stage
        )
    }

    @Test
    fun `WhisperFailure is a plain Exception so existing catch sites still work`() {
        // Adopting the typed failure must not introduce a cascade: every
        // existing `catch (e: Exception)` in the codebase must still
        // catch it.
        val failure = WhisperFailure(
            stage = WhisperDiagnostics.Stage.AUDIO_DECODE,
            message = "decode failed"
        )
        var caught = false
        try {
            throw failure
        } catch (e: Exception) {
            caught = true
            assertTrue(e is WhisperFailure)
        }
        assertTrue("WhisperFailure must be catchable as Exception", caught)
    }

    @Test
    fun `WhisperFailure keeps its cause`() {
        val root = IllegalStateException("native returned NULL")
        val failure = WhisperFailure(
            stage = WhisperDiagnostics.Stage.NATIVE_INIT_NULL,
            message = "init failed",
            cause = root
        )
        assertEquals(root, failure.cause)
    }

    @Test
    fun `WhisperFailure with a null detail still renders`() {
        val failure = WhisperFailure(
            stage = WhisperDiagnostics.Stage.NATIVE_INIT_OK,
            message = "ok"
        )
        assertEquals(null, failure.detail)
        // Must not throw a NPE while building the report string.
        assertTrue(failure.toString().isNotEmpty())
    }

    // ---- 5. Native init result shape ----

    @Test
    fun `InitResult Ok carries the context pointer`() {
        val ok = WhisperNative.InitResult.Ok(ctx = 0xDEAD_BEEFL)
        assertEquals(0xDEAD_BEEFL, ok.ctx)
    }

    @Test
    fun `InitResult distinguishes library-unavailable from native-null`() {
        // These are different diagnoses: a missing .so is an ABI /
        // packaging problem, whereas a NULL from a loaded library is a
        // model or memory problem. Collapsing them was the pre-fix bug.
        val unavailable: WhisperNative.InitResult =
            WhisperNative.InitResult.LibraryUnavailable(
                UnsatisfiedLinkError("libwhisper.so not found")
            )
        val returnedNull: WhisperNative.InitResult =
            WhisperNative.InitResult.NativeReturnedNull

        assertTrue(unavailable is WhisperNative.InitResult.LibraryUnavailable)
        assertTrue(returnedNull is WhisperNative.InitResult.NativeReturnedNull)
        assertFalse(
            "library-unavailable must not be conflated with native-null",
            unavailable is WhisperNative.InitResult.NativeReturnedNull
        )
    }

    @Test
    fun `InitResult Threw carries the original exception`() {
        val boom = IllegalStateException("jni blew up")
        val threw: WhisperNative.InitResult = WhisperNative.InitResult.Threw(boom)
        assertTrue(threw is WhisperNative.InitResult.Threw)
        assertEquals(boom, (threw as WhisperNative.InitResult.Threw).cause)
    }
}
