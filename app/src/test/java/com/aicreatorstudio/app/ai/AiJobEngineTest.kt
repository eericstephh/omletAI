package com.aicreatorstudio.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coverage for [AiJobEngine] (PART 12 of the brief).
 *
 * Enforces:
 *  - Stage transitions IDLE → PREPARING → ... → COMPLETED.
 *  - Concurrent submit calls for the same toolId are rejected, not
 *    silently queued.
 *  - Cancellation transitions to [AiJobEngine.Stage.CANCELLED] and
 *    does NOT raise an exception to the caller.
 *  - Errors thrown from the work block transition to
 *    [AiJobEngine.Stage.ERROR] and surface a stable message.
 *  - Reset returns the per-tool state to IDLE.
 *  - Heavy work runs off the calling thread.
 */
class AiJobEngineTest {

    @Before
    fun resetState() {
        AiJobEngine.reset("test-tool")
        AiJobEngine.reset("concurrent-tool")
        AiJobEngine.reset("cancel-tool")
        AiJobEngine.reset("error-tool")
        AiJobEngine.reset("stage-tool")
    }

    @After
    fun cleanup() {
        AiJobEngine.cancel("test-tool")
        AiJobEngine.cancel("concurrent-tool")
        AiJobEngine.cancel("cancel-tool")
        AiJobEngine.cancel("error-tool")
        AiJobEngine.cancel("stage-tool")
    }

    @Test
    fun idleObservesImmediately() {
        val job = AiJobEngine.observe("test-tool").value
        assertEquals(AiJobEngine.Stage.IDLE, job.stage)
        assertEquals("test-tool", job.toolId)
    }

    @Test
    fun successRunTransitionsToCompleted() = runBlocking {
        val outcome = AiJobEngine.submit("test-tool") { p, _ ->
            p.updateProgress(0.5f)
            "ok"
        }
        assertTrue(outcome is AiJobEngine.SubmitOutcome.Success)
        val success = outcome as AiJobEngine.SubmitOutcome.Success
        assertEquals("ok", success.output)
        assertEquals(AiJobEngine.Stage.COMPLETED, success.job.stage)
        assertEquals(1f, success.job.progress, 1e-6f)
        assertNotNull("finishedAt must be set on COMPLETED", success.job.finishedAtEpochMs)
        assertTrue("duration must be >= 0", success.job.durationMs >= 0L)
    }

    @Test
    fun duplicateSubmitIsRejected() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val first = async {
            AiJobEngine.submit("concurrent-tool") { _, token ->
                gate.await()
                token.throwIfCancelled()
                "first"
            }
        }
        // Let the first job reach its gate.
        delay(50)
        val second = AiJobEngine.submit("concurrent-tool") { _, _ -> "second" }
        assertTrue(
            "second submit must fail with duplicate-job error",
            second is AiJobEngine.SubmitOutcome.Failure
        )
        val msg = (second as AiJobEngine.SubmitOutcome.Failure).message
        assertTrue(
            "error must mention duplicate / running: $msg",
            msg.contains("running", ignoreCase = true)
        )
        // Release the first job.
        gate.complete(Unit)
        val firstOutcome = first.await()
        assertTrue(firstOutcome is AiJobEngine.SubmitOutcome.Success)
        assertEquals("first", (firstOutcome as AiJobEngine.SubmitOutcome.Success).output)
    }

    @Test
    fun cancelTransitionsToCancelledNotError() = runBlocking {
        val started = CountDownLatch(1)
        // Kick off the submit on a separate coroutine so the test
        // thread isn't blocked waiting for it to return — we need
        // the test thread to be free to call cancel() while the
        // worker is still running.
        val deferredOutcome = kotlinx.coroutines.GlobalScope.async(
            kotlinx.coroutines.Dispatchers.Default
        ) {
            AiJobEngine.submit("cancel-tool") { _, token ->
                started.countDown()
                try {
                    // Long-running work; check cancellation every 5 ms.
                    // Use a 60 s upper bound so the test never finishes
                    // naturally — the cancel() call from the test
                    // thread is the only thing that unblocks it.
                    for (i in 0 until 12000) {
                        token.throwIfCancelled()
                        delay(5)
                    }
                    "should-not-reach"
                } catch (e: CancellationException) {
                    throw e
                }
            }
        }
        // Wait until the worker has actually entered its loop.
        //
        // 5 s, not 1 s: this is a liveness wait on the shared
        // `Dispatchers.Default` pool, and under full-suite load a 1 s budget is
        // a second, unrelated way for this test to flake.
        assertTrue(
            "worker should start within 5 seconds",
            started.await(5, TimeUnit.SECONDS)
        )
        // Cancel from the test thread — the submit coroutine is
        // still suspended on deferred.await().
        //
        // This is the assertion that used to flake, and it was right to: it
        // detects the submit/register race (see
        // [aStartedJobIsAlwaysCancellable]). With the worker now started only
        // after it is registered, a started job is always found.
        val wasRunning = AiJobEngine.cancel("cancel-tool")
        assertTrue("cancel() must return true while running", wasRunning)
        // Now wait for the submit coroutine to surface its outcome. Bounded so
        // that a cancellation regression fails here rather than hanging the
        // whole suite until Gradle's own timeout.
        val outcome = withTimeout(10_000) { deferredOutcome.await() }
        assertTrue(outcome is AiJobEngine.SubmitOutcome.Failure)
        val msg = (outcome as AiJobEngine.SubmitOutcome.Failure).message
        assertEquals("cancelled", msg)
        // The per-tool state must be CANCELLED.
        val final = AiJobEngine.observe("cancel-tool").value
        assertEquals(AiJobEngine.Stage.CANCELLED, final.stage)
    }

    /**
     * Regression for a real race in [AiJobEngine.submit], not just a flaky
     * assertion.
     *
     * `submit` used to `scope.launch { ... }` and only *then* store the job in
     * `activeJobs`. `launch` with the default start mode dispatches
     * immediately, so the block could already be running on a pool thread by
     * the time `launch` returned — i.e. before the registration line executed.
     * A `cancel()` landing in that window found no entry, returned `false`, and
     * was **silently dropped**: the job ran to completion even though the
     * caller had been told there was nothing to cancel.
     *
     * The block below calls `cancel()` as its very first action, which is the
     * earliest moment a cancel can possibly arrive — the worker has started,
     * but the submitting coroutine has barely returned from `launch`. With the
     * worker now started only *after* registration, the window is gone by
     * construction, so every iteration must succeed.
     *
     * Repeated 200 times, because that window was only a few instructions wide.
     *
     * Measured honestly: **this test does not reproduce the old bug.** All 200
     * iterations passed against the original ordering on an idle JVM — the
     * window only widened under load, which is exactly what made the old
     * assertion a flake rather than a hard failure. Treat this as a **guard on
     * the invariant**, not as a reproduction. It pins the property, and it would
     * fire if the window were ever widened into something reproducible — a
     * `yield()`, an `await()`, or a `withContext` inserted between the launch
     * and the registration would do it. The loop is free while the invariant
     * holds: each iteration cancels at its first checkpoint.
     */
    @Test
    fun aStartedJobIsAlwaysCancellable() = runBlocking {
        repeat(200) { i ->
            val toolId = "cancel-race-tool-$i"
            val blockRan = CountDownLatch(1)
            // Written before `countDown()`, read after `await()` — the latch is
            // a happens-before edge, so a plain captured var is safe here.
            var cancelResult: Boolean? = null
            val outcome = kotlinx.coroutines.GlobalScope.async(
                kotlinx.coroutines.Dispatchers.Default
            ) {
                AiJobEngine.submit(toolId) { _, token ->
                    cancelResult = AiJobEngine.cancel(toolId)
                    blockRan.countDown()
                    // Bounded so a regression fails the test rather than
                    // spinning forever. `delay` is the suspension point that
                    // lets the cancelled parent coroutine interrupt the block.
                    for (j in 0 until 500) {
                        token.throwIfCancelled()
                        delay(1)
                    }
                    "not-cancelled"
                }
            }
            assertTrue(
                "iteration $i: worker should start within 5 seconds",
                blockRan.await(5, TimeUnit.SECONDS)
            )
            val result = withTimeout(10_000) { outcome.await() }
            assertEquals(
                "iteration $i: a job whose block has already started must be cancellable",
                true,
                cancelResult
            )
            assertTrue(
                "iteration $i: a cancelled job must surface Failure, got $result",
                result is AiJobEngine.SubmitOutcome.Failure
            )
            assertEquals(
                "iteration $i: wrong failure message",
                "cancelled",
                (result as AiJobEngine.SubmitOutcome.Failure).message
            )
            assertEquals(
                "iteration $i: wrong terminal stage",
                AiJobEngine.Stage.CANCELLED,
                AiJobEngine.observe(toolId).value.stage
            )
        }
    }

    @Test
    fun errorTransitionsToError() = runBlocking {
        val outcome = AiJobEngine.submit("error-tool") { _, _ ->
            throw IllegalStateException("simulated failure")
        }
        assertTrue(outcome is AiJobEngine.SubmitOutcome.Failure)
        val msg = (outcome as AiJobEngine.SubmitOutcome.Failure).message
        assertEquals("simulated failure", msg)
        val final = AiJobEngine.observe("error-tool").value
        assertEquals(AiJobEngine.Stage.ERROR, final.stage)
        assertEquals("simulated failure", final.errorMessage)
    }

    @Test
    fun stageTransitionsAreObserved() = runBlocking {
        val outcomes = mutableListOf<AiJobEngine.Stage>()
        val collectorReady = CompletableDeferred<Unit>()
        val collector = async {
            val flow = AiJobEngine.observe("stage-tool")
            flow.collect { job ->
                if (job.stage.isTerminal) {
                    outcomes.add(job.stage)
                    collectorReady.complete(Unit)
                }
                if (job.stage == AiJobEngine.Stage.PROCESSING) {
                    outcomes.add(job.stage)
                }
            }
        }
        // Give the collector a moment to subscribe.
        delay(20)
        AiJobEngine.submit("stage-tool") { p, _ ->
            p.setStage(AiJobEngine.Stage.LOADING_MODEL)
            p.setStage(AiJobEngine.Stage.PROCESSING)
            42
        }
        // Wait for the collector to record the terminal state.
        withTimeout(2_000) { collectorReady.await() }
        collector.cancel()
        // COMPLETED must be observed exactly once.
        assertEquals(1, outcomes.count { it == AiJobEngine.Stage.COMPLETED })
        assertTrue(
            "PROCESSING should have been observed: $outcomes",
            outcomes.any { it == AiJobEngine.Stage.PROCESSING }
        )
    }

    @Test
    fun heavyWorkRunsOffCallerThread() = runBlocking {
        val callerThread = Thread.currentThread()
        var workerThread: Thread? = null
        AiJobEngine.submit("test-tool") { _, _ ->
            workerThread = Thread.currentThread()
            "ok"
        }
        assertNotNull(workerThread)
        assertNotEquals(
            "worker must NOT run on the caller's thread",
            callerThread, workerThread
        )
    }

    @Test
    fun resetReturnsToIdle() = runBlocking {
        AiJobEngine.submit("test-tool") { _, _ -> "ok" }
        assertEquals(AiJobEngine.Stage.COMPLETED, AiJobEngine.current("test-tool").stage)
        AiJobEngine.reset("test-tool")
        assertEquals(AiJobEngine.Stage.IDLE, AiJobEngine.current("test-tool").stage)
        assertNull(AiJobEngine.current("test-tool").errorMessage)
    }

    @Test
    fun cancelReturnsFalseWhenNothingRunning() {
        // No job in flight for "fresh-tool".
        val wasRunning = AiJobEngine.cancel("fresh-tool")
        assertFalse(wasRunning)
    }

    @Test
    fun observeEmitsAtLeastOneValueImmediately() {
        val flow = AiJobEngine.observe("never-ran-tool")
        assertNotNull(flow.value)
        assertEquals(AiJobEngine.Stage.IDLE, flow.value.stage)
    }

    @Test
    fun manyParallelToolsDoNotInterfere() = runBlocking {
        val tools = (0 until 8).map { "tool-$it" }
        val outcomes = tools.map { toolId ->
            async {
                AiJobEngine.submit(toolId) { p, _ ->
                    p.updateProgress(0.3f)
                    delay(10)
                    p.updateProgress(1f)
                    toolId
                }
            }
        }.awaitAll()
        outcomes.forEach { o ->
            assertTrue("each tool must complete", o is AiJobEngine.SubmitOutcome.Success)
            assertEquals(
                AiJobEngine.Stage.COMPLETED,
                (o as AiJobEngine.SubmitOutcome.Success).job.stage
            )
        }
    }

    @Test
    fun stageEnumTerminalAndRunningFlags() {
        assertTrue(AiJobEngine.Stage.COMPLETED.isTerminal)
        assertTrue(AiJobEngine.Stage.CANCELLED.isTerminal)
        assertTrue(AiJobEngine.Stage.ERROR.isTerminal)
        assertFalse(AiJobEngine.Stage.IDLE.isTerminal)
        assertFalse(AiJobEngine.Stage.PREPARING.isTerminal)
        assertTrue(AiJobEngine.Stage.PREPARING.isRunning)
        assertTrue(AiJobEngine.Stage.PROCESSING.isRunning)
        assertFalse(AiJobEngine.Stage.COMPLETED.isRunning)
        assertFalse(AiJobEngine.Stage.IDLE.isRunning)
    }
}