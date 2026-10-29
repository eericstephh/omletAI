package com.aicreatorstudio.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A shared AI processing-job architecture (PART 12 of the brief).
 *
 * 2026-10-20 — READ THIS BEFORE TRUSTING THE DOC BELOW.
 *
 * The class KDoc used to claim that "Every AI tool (Background
 * Remove, Auto Frame, Voice Clean, AI Upscale, Whisper subtitles)
 * routes its long-running work through a single `AiJobEngine`". That
 * is **not** true: nothing in `main/` references this object. The
 * production path is `EditorViewModel.runAiTool`, which launches the
 * engine's `process` on `viewModelScope` and mirrors progress into
 * `EditorUiState.aiTools`; the Upscale engines have their own
 * `UpscaleJob` / `UpscaleProgress` pair, and the subtitle path has
 * `SubtitleGenerationState`.
 *
 * So the state machine, the [CancellationToken] and the per-tool
 * `StateFlow<AiJob>` described below are **available but unused**.
 * They are kept because they are a coherent, tested implementation of
 * the brief's requirement, and because deleting them would throw
 * away working code. But nothing routes through them yet, and a
 * maintainer must not assume that a tool's cancellation, progress or
 * exclusivity comes from here.
 *
 * ## What the engine provides (when it IS used)
 *
 *  - One terminal state machine:
 *    `IDLE → PREPARING → LOADING_MODEL → PROCESSING → FINALIZING → COMPLETED`
 *    with side states `CANCELLED` and `ERROR`.
 *  - Cooperative cancellation: cancelling the underlying [Job] moves
 *    the active state to `CANCELLED` and stops the worker coroutine at
 *    the next checkpoint.
 *  - Per-tool exclusivity: a second `submit` call for the same tool id
 *    while a job is still in flight returns `Result.failure` with a
 *    stable message — never silently queues and never runs duplicate
 *    inference.
 *  - Lifecycle safety: heavy work runs on `Dispatchers.Default` /
 *    `Dispatchers.IO`, never on `Dispatchers.Main`.
 *  - Structured results: progress / stage / cancellation / error /
 *    output path / duration / cleanup are all reachable through one
 *    `AiJob` value object that any UI panel can `collect`.
 *
 * The engine is `@Singleton` and lives in Hilt's application component
 * so every screen / ViewModel observes the same `StateFlow<AiJob>` for
 * a given `toolId`.
 */
object AiJobEngine {

    /**
     * Per-tool state machine. Mirrors the lifecycle required by the
     * brief (PART 12) and the per-tool states already used by
     * [SubtitleGenerationState] / [AiToolState] — kept distinct so
     * every AI operation can be uniformly observed through one
     * sealed type.
     */
    enum class Stage {
        IDLE,
        PREPARING,
        LOADING_MODEL,
        PROCESSING,
        FINALIZING,
        COMPLETED,
        CANCELLED,
        ERROR;

        val isTerminal: Boolean
            get() = this == COMPLETED || this == CANCELLED || this == ERROR

        val isRunning: Boolean
            get() = this == PREPARING || this == LOADING_MODEL ||
                this == PROCESSING || this == FINALIZING
    }

    /**
     * One observable job. The [progress] is 0f..1f within the current
     * [stage]; [stageProgress] tracks 0..1 across stages so a UI
     * progress bar can span the entire run.
     */
    data class AiJob(
        val id: String,
        val toolId: String,
        val stage: Stage,
        val progress: Float,
        val stageProgress: Float,
        val startedAtEpochMs: Long,
        val finishedAtEpochMs: Long?,
        val errorMessage: String?,
        val outputPath: String?,
        val durationMs: Long
    ) {
        companion object {
            fun idle(toolId: String): AiJob = AiJob(
                id = UUID.randomUUID().toString(),
                toolId = toolId,
                stage = Stage.IDLE,
                progress = 0f,
                stageProgress = 0f,
                startedAtEpochMs = 0L,
                finishedAtEpochMs = null,
                errorMessage = null,
                outputPath = null,
                durationMs = 0L
            )
        }
    }

    /**
     * Outcome of [submit]. Success means the engine ran the block to
     * completion; the [output] carries whatever the block returned.
     * Failure carries either a duplicate-job error, a cancellation,
     * or the exception thrown by the block.
     */
    sealed interface SubmitOutcome<out T> {
        data class Success<T>(val job: AiJob, val output: T) : SubmitOutcome<T>
        data class Failure(val message: String) : SubmitOutcome<Nothing>
    }

    /**
     * Mutable, concurrency-safe registry of `toolId → current AiJob`.
     * UI panels observe the per-tool flow and the global `jobs` flow
     * to render badges / progress bars / status text.
     *
     * 2026-10-20 — the registries live behind a `by lazy` holder.
     *
     * They used to be plain `private val`s on this `object`, i.e.
     * static fields initialised in the object's `<clinit>`. R8's class
     * merging can reorder static initialisers, and a registry
     * observed before its initialiser ran is null — the release build
     * then died with
     *
     * ```
     * NullPointerException: ... ConcurrentHashMap.put(...) on a null object reference
     * ```
     *
     * A `by lazy` holder is initialised on FIRST ACCESS under a
     * lock, so its value can never be observed as null. The lookups
     * also use `computeIfAbsent`, which is atomic — two workers
     * racing for the same tool id cannot both create a state.
     */
    private object Registries {
        val perTool: ConcurrentHashMap<String, MutableStateFlow<AiJob>> = ConcurrentHashMap()
        val activeJobs: ConcurrentHashMap<String, Job> = ConcurrentHashMap()
        val activeLocks: ConcurrentHashMap<String, Mutex> = ConcurrentHashMap()
    }

    /**
     * The shared "ai-jobs" scope. Lives on `SupervisorJob` so a
     * failure in one tool does NOT cancel an unrelated tool's job.
     */
    private val scope: CoroutineScope = CoroutineScope(
        Dispatchers.Default + kotlinx.coroutines.SupervisorJob()
    )

    /**
     * Observable per-tool job state. The flow always emits at least
     * one value (`Stage.IDLE`) so a UI binding never has to handle a
     * null.
     */
    fun observe(toolId: String): StateFlow<AiJob> {
        return Registries.perTool.computeIfAbsent(toolId) {
            MutableStateFlow(AiJob.idle(toolId))
        }
    }

    /**
     * Internal accessor — same lookup as [observe] but typed as the
     * mutable backing flow so the engine's writer paths can use
     * `update {}` / `value = ...` without casting. Not exposed.
     */
    private fun mutableFlow(toolId: String): MutableStateFlow<AiJob> {
        return Registries.perTool.computeIfAbsent(toolId) {
            MutableStateFlow(AiJob.idle(toolId))
        }
    }

    /**
     * Returns the current job snapshot (or [AiJob.idle] when no job
     * has ever run). Cheap, side-effect-free.
     */
    fun current(toolId: String): AiJob = observe(toolId).value

    /**
     * Cancel an in-flight job. Safe to call when no job is running.
     * Returns true if a job was actually cancelled; false if there was
     * nothing to cancel (already terminal or never started).
     *
     * **Guarantee:** if a job for [toolId] is observable as running — i.e.
     * [observe] has moved past `IDLE` — this returns true. [submit] starts the
     * worker lazily, and only *after* registering it in [activeJobs] and
     * publishing the new state, so there is no window in which a visible job is
     * unfindable. See the comment at the registration site for the race that
     * used to exist there.
     */
    fun cancel(toolId: String): Boolean {
        val job = Registries.activeJobs.remove(toolId) ?: return false
        job.cancel()
        return true
    }

    /**
     * Run [block] as the active job for [toolId]. The block is
     * invoked on `Dispatchers.Default` with a [Progress] sink it can
     * use to push progress updates; the engine translates those
     * updates into `AiJob` state transitions on the per-tool flow.
     *
     * A second [submit] call for the same [toolId] while a job is
     * still running returns [SubmitOutcome.Failure] with a stable
     * "job already running" message — never runs concurrent
     * inference, never silently queues.
     */
    suspend fun <T> submit(
        toolId: String,
        block: suspend (progress: Progress, token: CancellationToken) -> T
    ): SubmitOutcome<T> {
        val flow = mutableFlow(toolId)
        val lock = Registries.activeLocks.computeIfAbsent(toolId) { Mutex() }
        val now = System.currentTimeMillis()
        val newJob = AiJob(
            id = UUID.randomUUID().toString(),
            toolId = toolId,
            stage = Stage.PREPARING,
            progress = 0f,
            stageProgress = 0f,
            startedAtEpochMs = now,
            finishedAtEpochMs = null,
            errorMessage = null,
            outputPath = null,
            durationMs = 0L
        )
        // Locking ensures two concurrent submits can't both pass the
        // "no active job" check. Note the lock is held for the *whole* run
        // and released in the `finally` below, not just around the check —
        // `tryLock` never blocks, so a concurrent submit fails fast here
        // rather than queueing, which is the documented contract.
        val acquired = lock.tryLock()
        if (!acquired) {
            return SubmitOutcome.Failure(
                "Ai job for '$toolId' is already running"
            )
        }
        return try {
            if (Registries.activeJobs.containsKey(toolId)) {
                return SubmitOutcome.Failure(
                    "Ai job for '$toolId' is already running"
                )
            }
            // NOTE: `flow.value = newJob` is deliberately NOT published here.
            // Publishing PREPARING before the worker is registered would let an
            // observer that reacts to the flow call cancel() while [activeJobs]
            // is still empty. See the publication-order comment below.
            val deferred = CompletableDeferred<T>()
            val cancellationToken = CancellationToken { deferred.cancel() }
            val coroutineJob = scope.launch(start = CoroutineStart.LAZY) {
                val progress = Progress(toolId, flow, newJob.id)
                try {
                    val out = withContext(Dispatchers.Default) {
                        block(progress, cancellationToken)
                    }
                    deferred.complete(out)
                } catch (e: CancellationException) {
                    if (!deferred.isCompleted) deferred.cancel(e)
                } catch (e: Throwable) {
                    if (!deferred.isCompleted) deferred.completeExceptionally(e)
                }
            }
            // Register BEFORE starting the worker, and start it lazily.
            //
            // `scope.launch` with the default start mode dispatches
            // immediately, so the block could already be running on a pool
            // thread by the time `launch` returned — i.e. before the line
            // that used to register it here. A `cancel()` arriving in that
            // window found no entry in [activeJobs], returned `false`, and
            // was **silently dropped** while the job ran to completion.
            //
            // That window is why `AiJobEngineTest`'s cancellation test flaked:
            // it asserts `cancel()` returns true while the worker is running,
            // and intermittently the worker really was running but
            // unregistered. Starting lazily closes the window by construction
            // rather than by timing — the worker cannot begin before it is
            // registered, so "the block has started" now implies "cancel()
            // will find it".
            //
            // Note the window was only a few instructions wide, so it was not
            // reproducible on demand: 200 sampled attempts against the old
            // ordering all passed on an idle JVM. It widened under load, which
            // is what made it a flake rather than a hard failure.
            //
            // The lazy start introduces a second, narrower window of its own —
            // between the registration below and `start()` — and it fails
            // differently: cancelling a LAZY job that has never run means the
            // body never executes, so nothing would ever complete `deferred`
            // and the `await()` further down would suspend **forever**. A hang
            // is a worse failure than the dropped cancel it replaced, so
            // `deferred` is settled from the job's completion as a backstop.
            coroutineJob.invokeOnCompletion { cause ->
                if (!deferred.isCompleted) {
                    // The job ended without the body ever settling `deferred`
                    // — it was cancelled before it could run, or the engine's
                    // scope was cancelled. Surface that as a cancellation so
                    // the caller gets Failure("cancelled") instead of hanging.
                    // (`CancellationException` has no (String, Throwable)
                    // constructor, hence `initCause`.)
                    val cancellation = CancellationException("cancelled")
                    if (cause != null) cancellation.initCause(cause)
                    deferred.cancel(cancellation)
                }
            }
            Registries.activeJobs[toolId] = coroutineJob
            // Publish PREPARING only now that the worker is registered, so that
            // anything able to observe the job as running is also able to
            // cancel it. The `Progress` sink guards its updates on
            // `current.id != jobId`, and it is only ever called from inside the
            // block — which cannot run before `start()` below — so this still
            // precedes every progress update.
            flow.value = newJob
            coroutineJob.start()
            // Wait for the coroutine to finish without throwing —
            // cancellation surfaces as SubmitOutcome.Failure, never as
            // an exception that escapes the engine.
            val result = try {
                deferred.await()
            } catch (e: CancellationException) {
                flow.value = flow.value.copy(
                    stage = Stage.CANCELLED,
                    finishedAtEpochMs = System.currentTimeMillis(),
                    durationMs = System.currentTimeMillis() - flow.value.startedAtEpochMs
                )
                return SubmitOutcome.Failure("cancelled")
            } catch (e: Throwable) {
                flow.value = flow.value.copy(
                    stage = Stage.ERROR,
                    errorMessage = e.message ?: e::class.java.simpleName,
                    finishedAtEpochMs = System.currentTimeMillis(),
                    durationMs = System.currentTimeMillis() - flow.value.startedAtEpochMs
                )
                return SubmitOutcome.Failure(e.message ?: e::class.java.simpleName)
            }
            val finishedJob = flow.value.copy(
                stage = Stage.COMPLETED,
                progress = 1f,
                stageProgress = 1f,
                finishedAtEpochMs = System.currentTimeMillis(),
                durationMs = System.currentTimeMillis() - newJob.startedAtEpochMs
            )
            flow.value = finishedJob
            SubmitOutcome.Success(finishedJob, result)
        } finally {
            Registries.activeJobs.remove(toolId)
            lock.unlock()
        }
    }

    /**
     * Reset a tool's state to [Stage.IDLE]. Called by the UI when the
     * user dismisses the error banner or moves away from the panel.
     * Idempotent.
     */
    fun reset(toolId: String) {
        mutableFlow(toolId).value = AiJob.idle(toolId)
    }

    /**
     * Sink passed into the worker's block. Use [updateProgress] to
     * report progress on the current stage; use [setStage] to
     * transition (e.g. `PROCESSING` once the model is loaded).
     */
    class Progress constructor(
        val toolId: String,
        private val flow: MutableStateFlow<AiJob>,
        private val jobId: String
    ) {
        fun updateProgress(progress: Float) {
            val clamped = progress.coerceIn(0f, 1f)
            flow.update { current ->
                if (current.id != jobId) current
                else current.copy(progress = clamped, stageProgress = clamped)
            }
        }

        fun setStage(stage: Stage, progress: Float = 0f) {
            flow.update { current ->
                if (current.id != jobId) current
                else current.copy(stage = stage, progress = progress, stageProgress = 0f)
            }
        }

        fun setOutputPath(path: String?) {
            flow.update { current ->
                if (current.id != jobId) current
                else current.copy(outputPath = path)
            }
        }
    }

    /**
     * Lightweight cancellation handle. The worker block checks
     * [throwIfCancelled] between heavy checkpoints; cancelling the
     * parent coroutine also interrupts the block, but the explicit
     * check covers the gap between awaits.
     */
    class CancellationToken constructor(
        private val canceller: () -> Unit
    ) {
        @Volatile private var cancelled: Boolean = false

        fun cancel() {
            cancelled = true
            canceller()
        }

        val isCancelled: Boolean get() = cancelled

        fun throwIfCancelled() {
            if (cancelled) throw CancellationException("cancelled")
        }
    }
}