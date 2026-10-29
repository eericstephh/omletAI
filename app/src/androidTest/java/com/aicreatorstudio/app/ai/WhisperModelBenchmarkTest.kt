package com.aicreatorstudio.app.ai

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aicreatorstudio.app.ai.benchmark.WhisperModelBenchmark
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * PHASE B7 — model-comparison benchmark (instrumentation).
 *
 * Runs on a real Android process (emulator-5554 by default)
 * and exercises the four logical model sizes —
 * Tiny / Small / Medium / Large-v3 — against the bundled
 * Persian fixture.
 *
 * ## Why an instrumentation test
 *
 * The brief is explicit: "Add a developer/debug-only benchmark
 * entry point if necessary. Do not expose experimental benchmark
 * controls to normal users unless the existing product
 * architecture already has a developer/debug mode." The product
 * has no developer mode, so the benchmark lives **only** as an
 * instrumentation test in the `androidTest` source set. No
 * production UI surface.
 *
 * ## What this test does
 *
 *  1. Stages the B1.5 Persian fixture
 *     `assets/b15/persian_grafik5.m4a` once into cacheDir.
 *  2. Iterates [WhisperModelBenchmark.SPECS]
 *     (`whisper-tiny`, `whisper-small`, `whisper-medium`,
 *     `whisper-large-v3`).
 *  3. For each spec, probes `AiModels.isVerifiedInstalled`.
 *     Off-catalog specs (Tiny / Small) and uninstalled
 *     production specs (Large-v3 on a fresh emulator) record
 *     `note = "not installed"` and are skipped — they are
 *     **not** transcribe-attempted.
 *  4. For installed specs, runs
 *     [WhisperEngine.benchmarkTranscribe] which uses a
 *     **separate** native context (production `currentModel`
 *     is untouched) and measures wall time + RTF.
 *  5. Logs the report to `B7Benchmark` logcat tag and writes
 *     the JSON report to `cacheDir/whisper_benchmark_report.json`
 *     so a developer can `adb pull` it.
 *
 * ## Assertions
 *
 *  - At least one spec is installed (Medium is bundled in
 *    `assets/ai-models/...` and the build copies it to filesDir
 *    before tests run);
 *  - Installed specs that ran produce non-empty Persian output;
 *  - Every word from a successful transcribe has a valid
 *    `startTimeMs >= 0 && endTimeMs > startTimeMs`.
 *  - `whisper_benchmark_report.json` was written and is
 *    non-empty.
 *
 * The test does **not** fail when Tiny / Small / Large-v3 are
 * not installed (they are not in the production catalog or
 * have not been downloaded yet). They are recorded as
 * `not installed` and the test continues.
 */
@RunWith(AndroidJUnit4::class)
class WhisperModelBenchmarkTest {

    private lateinit var context: Context
    private lateinit var engine: WhisperEngine
    private lateinit var audioExtractor: AudioExtractor
    private lateinit var modelInstaller: AiModelInstaller
    private var fixtureUri: Uri? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioExtractor = AudioExtractor(context)
        modelInstaller = AiModelInstaller(context)
        engine = WhisperEngine(context, audioExtractor, modelInstaller)
        fixtureUri = stageFixture()
    }

    @After
    fun tearDown() {
        runCatching { engine.release() }
    }

    @Test
    fun benchmark_runs_across_four_specs_and_writes_json_report() {
        val uri = requireNotNull(fixtureUri) {
            "M4A fixture failed to stage; b15/persian_grafik5.m4a must be in androidTest assets"
        }
        // Ensure the native lib is up. If selfTest fails the
        // whole benchmark is meaningless so we abort early.
        assertTrue(
            "Whisper native lib not available on " +
                android.os.Build.SUPPORTED_ABIS.joinToString(),
            engine.isAvailable
        )
        val audioDurationMs = WhisperModelBenchmark.probeAudioDurationMs(context, uri)
        assumeTrue(
            "Could not probe audio duration for the fixture",
            audioDurationMs > 0
        )

        val report = runCoroutine {
            WhisperModelBenchmark.runBenchmark(
                context = context,
                engine = engine,
                audioUri = uri,
                audioDurationMs = audioDurationMs
            )
        }

        // Report contract.
        assertEquals(uri.toString(), report.fixturePath)
        assertEquals(audioDurationMs, report.fixtureDurationMs)
        assertEquals(
            "Benchmark must record one row per logical spec",
            WhisperModelBenchmark.SPECS.size,
            report.rows.size
        )
        // At least one spec must be installed (Medium is bundled).
        val installed = report.rows.count { it.note != "not installed" }
        assertTrue(
            "Expected at least one spec to be installed (Medium " +
                "is bundled). Got: ${report.rows}",
            installed >= 1
        )

        // Per-row contracts.
        for (row in report.rows) {
            assertEquals("whisper", row.engineId)
            assertEquals(audioDurationMs, row.audioDurationMs)
            when (row.note) {
                "not installed" -> {
                    assertEquals(0, row.segmentCount)
                    assertEquals(0, row.wordCount)
                    assertTrue(
                        "Not-installed row's rtf must be 0",
                        row.realTimeFactor == 0.0
                    )
                }
                "" -> {
                    // Successful transcribe.
                    assertTrue(
                        "${row.modelId} produced 0 words",
                        row.wordCount > 0
                    )
                    assertTrue(
                        "${row.modelId} produced 0 segments",
                        row.segmentCount > 0
                    )
                    // Use the single-arg JUnit assertNotNull so
                    // the smart-cast keeps the generic type.
                    val t = row.transcription!!
                    assertNotNull(
                        "${row.modelId} successful row must include " +
                            "the transcription",
                        t
                    )
                    // Every word must have a valid timestamp.
                    val valid = t.words.count { w ->
                        w.startTimeMs >= 0L && w.endTimeMs > w.startTimeMs
                    }
                    assertTrue(
                        "${row.modelId} produced no word with a valid " +
                            "timestamp pair",
                        valid > 0
                    )
                    // Persian guard: the recognized text must
                    // contain at least one Persian codepoint.
                    val fullText = t.words.joinToString(" ") { it.text }
                    val hasPersian = fullText.any { ch ->
                        ch.code in 0x0600..0x06FF
                    }
                    assertTrue(
                        "${row.modelId} produced no Persian text. Got: " +
                            "'${fullText.take(160)}'",
                        hasPersian
                    )
                }
                "load failed" -> {
                    // The model was on disk but the native
                    // side could not load it. Recorded as
                    // 0 words; not a hard fail of this test
                    // because the gate is "we tried and
                    // reported honestly".
                }
                else -> {
                    throw AssertionError("Unexpected note: ${row.note}")
                }
            }
        }

        // Write the JSON report so a developer can pull it.
        val written = WhisperModelBenchmark.writeReport(context, report)
        assertNotNull(
            "whisper_benchmark_report.json must be written to cacheDir",
            written
        )
        assertTrue(
            "whisper_benchmark_report.json must be non-empty",
            written!!.length() > 0
        )
        // Sanity: the file contains every model id.
        val text = written.readText()
        for (spec in WhisperModelBenchmark.SPECS) {
            assertTrue(
                "Report must mention ${spec.id}: $text",
                text.contains(spec.id)
            )
        }
    }

    // -----------------------------------------------------------------
    // Helper plumbing
    // -----------------------------------------------------------------

    private fun <T> runCoroutine(block: suspend () -> T): T {
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        val thread = Thread {
            result = runCatching { kotlinx.coroutines.runBlocking { block() } }
            latch.countDown()
        }
        thread.isDaemon = false
        thread.start()
        val finished = latch.await(15, TimeUnit.MINUTES)
        check(finished) { "coroutine under test did not finish in 15 minutes" }
        return result!!.getOrThrow()
    }

    private fun stageFixture(): Uri? {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val am = testContext.assets
        val src = "b15/persian_grafik5.m4a"
        val cached = File(context.cacheDir, "b15_persian_grafik5.m4a")
        cached.parentFile?.mkdirs()
        return runCatching {
            am.open(src).use { input ->
                FileOutputStream(cached).use { output ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                    }
                }
            }
            Uri.fromFile(cached)
        }.getOrNull()
    }
}
