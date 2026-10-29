package com.aicreatorstudio.app.ai

import android.content.Context
import android.net.Uri
import android.util.Log
import com.aicreatorstudio.app.ai.audio.WhisperAudioConditioner
import com.aicreatorstudio.app.ai.audio.WhisperPrompt
import com.aicreatorstudio.app.ai.audio.WindowPlanner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [TranscriptionEngine] backed by whisper.cpp.
 *
 * Two Whisper checkpoints are offered (Tiny / Small were removed
 * in PHASE B1.6 after the Persian quality audit):
 *
 * - **Medium** (`ggml-medium.bin`, ~1.5 GB, 99 languages) —
 *   the default, bundled in the APK and SHA-verified.
 * - **Large-v3** (`ggml-large-v3.bin`, ~3.1 GB, 99 languages) —
 *   opt-in for capable devices, download-only.
 *
 * RAM is INFORMATIONAL ONLY. It never blocks model
 * visibility, selection, download, or installation. The user
 * may download and keep any model; if the device genuinely
 * cannot initialize the selected model, the engine reports a
 * clear load error (naming the model and its documented RAM
 * requirement) instead of crashing or silently switching to
 * another model.
 *
 * The user picks one before generating subtitles. The selection
 * drives [WhisperModelSpec], which in turn picks the catalog
 * entry in [AiModelCatalog] used by the installer.
 *
 * ## Lifecycle (PHASE 2 of the brief)
 *
 *   1. Native self-test (`WhisperNative.selfTest()`) on the first
 *      `initialize*` call. If the .so isn't loadable, the engine
 *      reports [AiFailure.ENGINE_UNAVAILABLE].
 *   2. Model install via [AiModelInstaller.installWithManifest]
 *      (bundle-first, network fallback, SHA-256 verified).
 *      Download eligibility is storage + network only — RAM
 *      plays no role.
 *   3. Native load via `whisper_init_from_file_with_params`.
 *      If the file exists but whisper.cpp rejects it, report
 *      [AiFailure.MODEL_LOAD_FAILED] with the model name and
 *      its documented RAM requirement so the user understands
 *      why. The downloaded model is kept on disk.
 *   4. Once the native context is up, the engine reports READY and
 *      can run transcription. Transcription failures mid-run report
 *      [AiFailure.TRANSCRIPTION_FAILED].
 *
 * ## Word-level timestamps — real, not synthesized
 *
 * whisper.cpp exposes `whisper_full_get_word_t()` returning
 * `{text, t0, t1, p}` where `t0` / `t1` are centiseconds. The JNI
 * wrapper at [WhisperNative.fullWithWordTimestamps] pulls those
 * out for every token in the audio and we convert the centisecond
 * values into milliseconds and copy each entry into a
 * [TranscribedWord].
 *
 * No equal-splitting, no character-count-based estimates, no
 * hand-derived durations. The per-word timing shown on the
 * timeline and in the karaoke highlight comes straight from the
 * acoustic model's token-level alignment.
 *
 * ## Audio path
 *
 * [decodeAudioToPcm] extracts the first audio track via
 * [MediaExtractor] + [MediaCodec], reads `KEY_SAMPLE_RATE` and
 * `KEY_CHANNEL_COUNT` after `INFO_OUTPUT_FORMAT_CHANGED`, and
 * hands the raw PCM to [resampleTo16kMonoFloat] which converts
 * int16 little-endian → float32 in `[-1, 1]` at exactly 16 kHz
 * mono. This is the byte layout whisper.cpp's `whisper_full`
 * expects when fed via `whisper_full_default_params`.
 *
 * ## Long videos
 *
 * whisper.cpp's `whisper_full` accepts arbitrary-length audio in
 * one call; we still process in 30 s windows to bound memory and
 * to allow cooperative cancellation. Each window's word timestamps
 * are offset by the window's start time in the source media so the
 * global timeline position stays correct.
 *
 * ## Cancellation
 *
 * The current coroutine's [Job] is checked between windows and every
 * 100 ms inside a window. As soon as the user cancels, we return
 * a `Result.failure(CancellationException(...))` and free the
 * native context immediately.
 */
@Singleton
class WhisperEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioExtractor: AudioExtractor,
    private val modelInstaller: AiModelInstaller
) : TranscriptionEngine {

    override val id: String = "whisper"

    override val displayName: String = "Whisper"

    /**
     * True only when `System.loadLibrary("whisper")` succeeds AND
     * the JNI self-test ([WhisperNative.selfTest]) returns without
     * throwing. Lazy so we only attempt the native load once per
     * process; the result is then cached.
     */
    override val isAvailable: Boolean by lazy { computeIsAvailable() }

    /**
     * Cached self-test result, populated lazily on the first
     * [initializeModel] / [initializeModelFor] call. Used by the
     * editor UI to show the user which whisper.cpp version is
     * loaded and on which ABI; surfaces problems like
     * "arm64 device, only armeabi-v7a in the APK".
     */
    @Volatile
    internal var lastSelfTest: WhisperNative.SelfTest? = null
        private set

    private val initMutex = Mutex()
    private var nativeContext: Long = 0L
    private var currentModel: WhisperModelSpec? = null

    /**
     * 2026-10-11 — build + memory snapshot, captured lazily once per
     * process. Attached to every failure diagnostic so a bug report
     * carries the device context that a failing run needs. Metadata
     * only — see [WhisperDiagnostics] for what is deliberately excluded.
     */
    private val deviceEnv: WhisperDiagnostics.Environment by lazy {
        WhisperDiagnostics.environment(context)
    }

    /**
     * 2026-09-06 — user-selected transcription language. Null means
     * "use the model catalog's language" (the pre-multilingual "fa"
     * fallback). Set by the ViewModel from the Subs panel's language
     * picker BEFORE [generateTranscription] runs.
     */
    @Volatile
    var selectedLanguage: SubtitleLanguage? = null

    /**
     * 2026-09-06 — RTL detection flag for the selected language,
     * read by the subtitle post-processor / renderer to pick the
     * text direction hint.
     */
    val isCurrentLanguageRtl: Boolean
        get() = selectedLanguage?.rtl ?: true

    /**
     * True if the [WhisperModelSpec]'s file is on disk and
     * SHA-verified via [AiModels.isVerifiedInstalled]. Used by the
     * SubtitleGenerator to decide whether to show the "Download
     * model" pill.
     */
    fun isModelDownloaded(spec: WhisperModelSpec): Boolean =
        AiModels.isVerifiedInstalled(context, spec.aiSpec())

    private fun computeIsAvailable(): Boolean {
        return runCatching {
            // Force a load + JNI self-test. If either throws we
            // report the engine as unavailable; the engine will
            // surface the specific NativeEngineFailure the first
            // time the user actually invokes it.
            System.loadLibrary("whisper")
            val st = WhisperNative.selfTest()
            lastSelfTest = st
            Log.i(TAG, "whisper.cpp v${st.version} on ${st.abi} " +
                "(systemMaxThreads=${st.systemMaxThreads})")
            true
        }.getOrElse {
            Log.w(TAG, "Whisper native self-test failed", it)
            // 2026-10-13 — record the *classified* cause rather than
            // leaving only a logcat line. This is the earliest point
            // the library load is attempted, so it is where an ABI
            // mismatch is first detectable.
            lastNativeFailure = NativeEngineFailure.classify(
                error = it,
                primaryAbi = deviceEnv.primaryAbi,
                lowMemory = deviceEnv.lowMemory
            )
            false
        }
    }

    /**
     * 2026-10-13 — the classified reason the native library last
     * failed to load, or `null` when it loaded cleanly. Read by the
     * ViewModel so the error card can show a *specific* Persian
     * message instead of the old catch-all "your CPU is unsupported".
     */
    @Volatile
    var lastNativeFailure: NativeEngineFailure? = null
        private set

    override suspend fun initializeModel(onProgress: (Float) -> Unit): Result<Unit> =
        initializeModelFor(WhisperModelSpec.Default, onProgress)

    /**
     * Engine-specific entry point used by the ViewModel when the
     * user has chosen a specific model in the AI Subtitle card.
     *
     * Reports progress 0..0.5 for model install (download + verify
     * + atomic rename), 0.5..0.8 for native load, 0.8..1.0 for a
     * final self-test. The phase listener also receives explicit
     * [AiPhase] transitions so the UI can show
     * "Downloading → Verifying → Installing → Loading → Ready".
     */
    suspend fun initializeModelFor(
        spec: WhisperModelSpec,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        initMutex.withLock {
            try {
                // PHASE 2 step 1: native self-test.
                val selfTest = runNativeSelfTest()
                lastSelfTest = selfTest

                // NOTE: there is deliberately NO RAM gate here.
                // RAM is informational only — it never blocks
                // download, installation, or selection. The real
                // native load below is the single source of
                // truth; if the device cannot initialize the
                // model, that failure is caught and reported
                // with a clear message (see the MODEL_LOAD_FAILED
                // mapping around ensureNativeContext).

                // PHASE 2 step 2: install model (download or copy
                // from bundled asset + SHA-256 verify).
                onProgress(0.05f)
                val installOutcome = modelInstaller.installWithManifest(
                    spec = spec.aiSpec(),
                    phaseListener = AiPhaseListener { phase, p ->
                        // Map (0..1) install progress into the
                        // 0.05..0.8 range the UI uses.
                        onProgress(0.05f + 0.75f * p.coerceIn(0f, 1f))
                        Log.d(TAG, "install phase=$phase progress=$p")
                    },
                    onProgress = { /* phase listener handles */ }
                )
                if (installOutcome.isFailed) {
                    onProgress(0f)
                    // 2026-10-11 — the installer now reports a precise
                    // failure kind; carry it into the stage enum so the
                    // UI and a bug report agree on what died.
                    val stage = when (installOutcome.failure) {
                        AiFailure.MODEL_VERIFICATION_FAILED ->
                            WhisperDiagnostics.Stage.MODEL_SHA_MISMATCH
                        AiFailure.MODEL_LOAD_FAILED ->
                            WhisperDiagnostics.Stage.NATIVE_INIT_NULL
                        else -> WhisperDiagnostics.Stage.MODEL_MISSING
                    }
                    val artifact = WhisperDiagnostics.inspectModel(
                        file = AiModels.modelFile(context, spec.aiSpec()),
                        expectedMinBytes = spec.aiSpec().minSizeBytes,
                        expectedBytes = AiModels.expectedSizeFor(context, spec.aiSpec())
                    )
                    WhisperDiagnostics.failure(
                        stage = stage, env = deviceEnv, model = artifact,
                        detail = installOutcome.detail ?: installOutcome.failure?.name ?: "install failed"
                    )
                    return@withContext Result.failure(
                        WhisperFailure(
                            stage = stage,
                            message = installOutcome.detail
                                ?: installOutcome.failure?.persianMessage()
                                ?: "نصب مدل Whisper ناموفق بود",
                            detail = installOutcome.detail
                        )
                    )
                }
                onProgress(0.8f)

                // PHASE 2 step 3: native load. The downloaded
                // model is kept on disk regardless of the
                // outcome. If whisper.cpp rejects the file (e.g.
                // the device genuinely cannot initialize it),
                // report MODEL_LOAD_FAILED with the model name
                // and its documented RAM requirement — never
                // crash, never silently switch models.
                // 2026-09-07 RAM-CHECK FIX — the old pre-check compared
                // `ActivityManager.availMem` against the model's full
                // working set and BLOCKED on a shortfall. On real
                // 8 GB phones availMem hovers around 1.5–2.5 GB
                // (Android deliberately fills RAM with cached
                // processes and reclaims them on demand), so Medium
                // AND Large were permanently blocked with the
                // "رم را خالی کن" message — no amount of closing
                // apps ever satisfied the check. New policy, matching
                // the project rule "RAM never blocks; only a real
                // load failure does":
                //
                //   1. Model ALREADY loaded in memory → skip all
                //      checks (a re-generate must never re-gate on
                //      RAM for a context that is resident).
                //   2. TOTAL device RAM below the requirement → hard
                //      block (physically impossible on this device).
                //   3. System in genuine memory pressure (`mi.lowMemory`)
                //      or availMem below HALF the requirement → block
                //      with the free-memory message.
                //   4. Otherwise → log a warning and attempt the load;
                //      the OS reclaims the cache, and a genuine native
                //      failure still surfaces as a clear Persian error
                //      through the catch below.
                if (nativeContext == 0L || currentModel != spec) {
                    val am = context.getSystemService(
                        android.content.Context.ACTIVITY_SERVICE
                    ) as? android.app.ActivityManager
                    if (am != null) {
                        val mi = android.app.ActivityManager.MemoryInfo()
                        am.getMemoryInfo(mi)
                        val availMb = mi.availMem / (1024L * 1024L)
                        val totalMb = mi.totalMem / (1024L * 1024L)
                        val requiredMb = when (spec) {
                            // 2026-09-08 — Small f16 (488 MB weights,
                            // ~1.6 GB working set with KV cache).
                            WhisperModelSpec.SMALL -> 1_800L
                            WhisperModelSpec.CLOUD_API -> 0L
                        }
                        if (totalMb < requiredMb) {
                            onProgress(0f)
                            return@withContext Result.failure(
                                Exception(
                                    "این مدل روی این دستگاه اجرا نمی‌شود. رم کل دستگاه " +
                                        "${"%.1f".format(totalMb / 1024f)} گیگابایت است ولی " +
                                        "${spec.displayName} به حدود ${spec.ramRequirementAmount()} " +
                                        "رم نیاز دارد. لطفاً مدل کوچک‌تری را انتخاب کنید."
                                )
                            )
                        }
                        // Soft-pressure gate — half of the measured
                        // WORKING SET (requiredMb), NOT minRamMb: the
                        // 2026-09-07 RAM-CHECK FIX specifically stops
                        // blocking 8 GB devices with ~2 GB availMem
                        // (Large 4.0/2 = 2.0 GB is right at their
                        // typical reading). The message quotes the
                        // working-set number so it matches this
                        // threshold instead of the picker's
                        // "Requires approximately X GB" total-RAM copy.
                        if (mi.lowMemory || availMb < requiredMb / 2) {
                            onProgress(0f)
                            return@withContext Result.failure(
                                Exception(
                                    "حافظه آزاد کافی نیست. ${spec.displayName} " +
                                        "حدود ${"%.1f".format(requiredMb / 1024f)} گیگ رم آزاد می‌خواهد؛ " +
                                        "رم کل دستگاه ${"%.1f".format(totalMb / 1024f)} گیگابایت و " +
                                        "الان حدود ${"%.1f".format(availMb / 1024f)} گیگابایت آزاد است. " +
                                        "برنامه‌های پس‌زمینه را ببندید، دستگاه را یک‌بار ری‌استارت کنید " +
                                        "یا دوباره تلاش کنید."
                                )
                            )
                        }
                        if (availMb < requiredMb) {
                            Log.i(
                                TAG,
                                "availMem ${"%.1f".format(availMb / 1024f)} GB below " +
                                    "${spec.name} working set " +
                                    "${"%.1f".format(requiredMb / 1024f)} GB — " +
                                    "attempting load anyway (OS cache reclaim expected)"
                            )
                        }
                        // 2026-10-11 — record the memory state that the
                        // load is about to run under. This is the single
                        // most useful number when a native allocation
                        // fails later: it distinguishes "the device was
                        // genuinely tight" from "memory was fine and
                        // something else broke".
                        WhisperDiagnostics.stage(
                            WhisperDiagnostics.Stage.NATIVE_INIT_NULL, 0L,
                            "pre-load mem totalMb=$totalMb availMb=$availMb " +
                                "requiredMb=$requiredMb lowMemory=${mi.lowMemory}"
                        )
                    }
                }

                try {
                    ensureNativeContext(spec)
                } catch (e: WhisperFailure) {
                    // 2026-10-11 — `ensureNativeContext` now names the
                    // exact stage it died at (missing / truncated /
                    // native-NULL / library). Do NOT flatten that back
                    // into a generic sentence: the stage is the
                    // diagnosis, and the old generic message told a
                    // memory-pressure user to re-download 465 MB, which
                    // cannot have helped.
                    onProgress(0f)
                    return@withContext Result.failure(e)
                } catch (e: Exception) {
                    onProgress(0f)
                    // 2026-09-07 MSG FIX — Persian, matching the rest
                    // of the failure messages surfaced in the card.
                    val stage = WhisperDiagnostics.Stage.NATIVE_INIT_NULL
                    WhisperDiagnostics.failure(
                        stage = stage, env = deviceEnv, model = null, error = e
                    )
                    return@withContext Result.failure(
                        WhisperFailure(
                            stage = stage,
                            message = "بارگذاری ${spec.displayName} روی این دستگاه ممکن نشد. " +
                                "این مدل به حدود ${spec.ramRequirementAmount()} رم نیاز دارد. " +
                                "فایل دانلودشده نگه داشته شد — رم را آزاد کنید و دوباره تلاش کنید " +
                                "یا مدل کوچک‌تری انتخاب کنید. (${WhisperDiagnostics.persianFor(stage)})",
                            cause = e,
                            detail = e.javaClass.simpleName
                        )
                    )
                }
                onProgress(1f)
                Result.success(Unit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "UnsatisfiedLinkError during Whisper init", e)
                onProgress(0f)
                // 2026-10-13 — this used to hard-code
                // "موتور بومی Whisper بارگذاری نشد. کتابخانه libwhisper.so
                //  یافت نشد." for every UnsatisfiedLinkError, including a
                // JNI symbol mismatch and a wrong-architecture ELF. The
                // classified cause names the real one.
                val failure = NativeEngineFailure.classify(
                    error = e,
                    primaryAbi = deviceEnv.primaryAbi,
                    lowMemory = deviceEnv.lowMemory
                )
                lastNativeFailure = failure
                Result.failure(
                    Exception(failure.persianMessage(), e)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Whisper initializeModelFor failed", e)
                onProgress(0f)
                Result.failure(e)
            }
        }
    }

    private fun runNativeSelfTest(): WhisperNative.SelfTest {
        val start = System.currentTimeMillis()
        try {
            System.loadLibrary("whisper")
            val st = WhisperNative.selfTest()
            lastNativeFailure = null
            WhisperDiagnostics.nativeLoad(
                ok = true, abi = st.abi, version = st.version,
                elapsedMs = System.currentTimeMillis() - start
            )
            Log.i(TAG, "whisper.cpp v${st.version} on ${st.abi} " +
                "(systemMaxThreads=${st.systemMaxThreads})")
            return st
        } catch (e: Throwable) {
            // 2026-10-13 — classify BEFORE logging so both the
            // structured record and the user-facing message carry
            // the real stage. Previously every branch here produced
            // the single "CPU unsupported" sentence, which was wrong
            // for a missing .so, a JNI symbol mismatch and an OOM
            // alike.
            val failure = NativeEngineFailure.classify(
                error = e,
                primaryAbi = deviceEnv.primaryAbi,
                lowMemory = deviceEnv.lowMemory
            )
            lastNativeFailure = failure
            WhisperDiagnostics.nativeLoad(
                ok = false, abi = deviceEnv.primaryAbi,
                version = null, elapsedMs = System.currentTimeMillis() - start,
                failure = failure, error = e
            )
            WhisperDiagnostics.failure(
                stage = WhisperDiagnostics.Stage.NATIVE_SELFTEST,
                env = deviceEnv, model = null, error = e,
                detail = failure.name
            )
            throw e
        }
    }

    /**
     * Advisory-only device-capability probe. Returns a
     * [DeviceCapability] snapshot (ABI + RAM + CPU + storage).
     * Informational: no production path gates visibility,
     * selection, download, or installation on this result.
     * Only a real native-load failure blocks inference. The check
     * layers four resources, not just free RAM, because
     * Large-v3 in particular is known to OOM/crash on devices
     * that pass the RAM check but lack the CPU or storage
     * headroom. The layers, in order:
     *
     *   1. **ABI** — `arm64-v8a` or `x86_64` only. The
     *      `armeabi-v7a` build of `libwhisper.so` cannot load
     *      the 3.1 GB Large-v3 model on 32-bit phones (the
     *      ggml memory pool exhausts the 4 GB VA ceiling
     *      during `whisper_full`).
     *   2. **Free RAM** — at least [WhisperModelSpec.minRamMb]
     *      of `ActivityManager.availMem`. The whisper.cpp
     *      working set is dominated by the KV cache; the
     *      minRamMb values were chosen from the actual
     *      measured working set of each model on real
     *      devices, not the file size.
     *   3. **CPU cores** — at least 4 logical cores. whisper.cpp
     *      uses `n_threads` to drive the encoder + decoder in
     *      parallel; 2-core phones hang for 30+ seconds per
     *      audio window.
     *   4. **Storage** — at least 4 GB free on the internal
     *      data partition. The install pipeline stages the
     *      model in `filesDir/models/...` and the partial
     *      file under `cacheDir/ai-model-downloads/`, so we
     *      need a safety margin above the file size itself
     *      for journal + atomic-rename overhead.
     *
     * The function is `suspend` because the storage probe
     * does a `StatFs.getAvailableBytes` call (cheap, but
     * listed as a slow I/O call by lint). The first call
     * caches the result in [lastDeviceCapability] so the UI
     * can re-query without re-probing every recomposition.
     */
    suspend fun checkDeviceCapabilityFor(spec: WhisperModelSpec): DeviceCapability =
        DeviceCapabilityDetector(context).checkFor(spec)

    /**
     * Read-only view of the cached device capability, if a
     * probe was run. Advisory only; the model picker never
     * disables rows based on this. The
     * ViewModel calls this after [checkDeviceCapabilityFor]
     * to decide whether the model picker should let the
     * user pick Large-v3 at all. The picker never disables
     * rows based on capability; rows always remain selectable
     * and downloadable.
     */
    fun lastDeviceCapabilityFor(spec: WhisperModelSpec): DeviceCapability? =
        DeviceCapabilityDetector(context).lastFor(spec)

    private fun ensureNativeContext(spec: WhisperModelSpec) {
        if (nativeContext != 0L && currentModel == spec) return
        releaseNativeContext()
        val specPath = spec.aiSpec()
        val file = AiModels.modelFile(context, specPath)
        val path = file.absolutePath

        // ---- 2026-10-11 DIAGNOSTICS + HONEST FAILURE ----
        // Read the real on-disk state BEFORE attempting the native load.
        // A truncated file and a corrupt-but-full-size file are different
        // faults, and neither is the same as an out-of-native-memory
        // device. The old code reported all three as "may be corrupt",
        // which sent a memory-pressure user to re-download 465 MB for
        // nothing. Snapshot the facts first, then let the outcome decide
        // the message.
        val artifact = WhisperDiagnostics.inspectModel(
            file = file,
            expectedMinBytes = specPath.minSizeBytes,
            expectedBytes = AiModels.expectedSizeFor(context, specPath)
        )
        if (!artifact.exists) {
            WhisperDiagnostics.failure(
                stage = WhisperDiagnostics.Stage.MODEL_MISSING,
                env = deviceEnv, model = artifact
            )
            throw WhisperFailure(
                stage = WhisperDiagnostics.Stage.MODEL_MISSING,
                message = WhisperDiagnostics.persianFor(WhisperDiagnostics.Stage.MODEL_MISSING),
                detail = artifact.summary()
            )
        }
        if (artifact.isTruncated) {
            WhisperDiagnostics.failure(
                stage = WhisperDiagnostics.Stage.MODEL_SIZE_MISMATCH,
                env = deviceEnv, model = artifact
            )
            throw WhisperFailure(
                stage = WhisperDiagnostics.Stage.MODEL_SIZE_MISMATCH,
                message = WhisperDiagnostics.persianFor(WhisperDiagnostics.Stage.MODEL_SIZE_MISMATCH),
                detail = artifact.summary()
            )
        }

        val loadStart = System.currentTimeMillis()
        val ctx = when (val r = WhisperNative.initFromFileDetailed(path)) {
            is WhisperNative.InitResult.Ok -> r.ctx
            is WhisperNative.InitResult.LibraryUnavailable -> {
                // 2026-10-13 — classify the library-load failure instead
                // of falling straight to the generic NATIVE_SELFTEST
                // sentence, which blamed the CPU for a packaging fault.
                val failure = NativeEngineFailure.classify(
                    error = r.cause,
                    primaryAbi = deviceEnv.primaryAbi,
                    lowMemory = deviceEnv.lowMemory
                )
                lastNativeFailure = failure
                WhisperDiagnostics.failure(
                    stage = WhisperDiagnostics.Stage.NATIVE_SELFTEST,
                    env = deviceEnv, model = artifact, error = r.cause,
                    detail = failure.name
                )
                throw WhisperFailure(
                    stage = WhisperDiagnostics.Stage.NATIVE_SELFTEST,
                    message = failure.persianMessage(),
                    cause = r.cause,
                    detail = failure.name
                )
            }
            WhisperNative.InitResult.NativeReturnedNull -> {
                // The library ran and refused the file. Three distinct
                // causes land here: a wrong-bytes file, a native malloc
                // failure, and a genuine runtime rejection. Resolve it
                // honestly using the facts we already have:
                //
                //  1. byte-exact file + reported memory pressure →
                //     INSUFFICIENT_MEMORY (the actionable one)
                //  2. byte-exact file, memory fine → NATIVE_INIT_FAILED
                //  3. size differs from the manifest → MODEL_INVALID
                val exactSizeWrong = artifact.isWrongSize
                val failure = when {
                    exactSizeWrong -> NativeEngineFailure.MODEL_INVALID
                    deviceEnv.lowMemory -> NativeEngineFailure.INSUFFICIENT_MEMORY
                    else -> NativeEngineFailure.NATIVE_INIT_FAILED
                }
                val stage = if (exactSizeWrong) {
                    WhisperDiagnostics.Stage.MODEL_SHA_MISMATCH
                } else {
                    WhisperDiagnostics.Stage.NATIVE_INIT_NULL
                }
                if (!exactSizeWrong) {
                    // Memory-pressure context is the single most useful
                    // number here — record it explicitly.
                    WhisperDiagnostics.memoryPressure(
                        context, "native_init_null"
                    )
                }
                WhisperDiagnostics.failure(
                    stage = stage, env = deviceEnv, model = artifact,
                    detail = if (exactSizeWrong) {
                        "size differs from manifest"
                    } else {
                        "size matches manifest; cause=${failure.name}"
                    }
                )
                throw WhisperFailure(
                    stage = stage,
                    message = failure.persianMessage(),
                    detail = failure.name
                )
            }
            is WhisperNative.InitResult.Threw -> {
                WhisperDiagnostics.failure(
                    stage = WhisperDiagnostics.Stage.NATIVE_INIT_NULL,
                    env = deviceEnv, model = artifact, error = r.cause
                )
                throw WhisperFailure(
                    stage = WhisperDiagnostics.Stage.NATIVE_INIT_NULL,
                    message = WhisperDiagnostics.persianFor(WhisperDiagnostics.Stage.NATIVE_INIT_NULL),
                    cause = r.cause,
                    detail = r.cause.javaClass.simpleName
                )
            }
        }
        nativeContext = ctx
        currentModel = spec
        WhisperDiagnostics.stage(
            WhisperDiagnostics.Stage.NATIVE_INIT_OK,
            System.currentTimeMillis() - loadStart,
            "loaded model=${spec.name} bytes=${artifact.actualBytes}"
        )
    }

    private fun releaseNativeContext() {
        if (nativeContext != 0L) {
            WhisperNative.free(nativeContext)
            WhisperDiagnostics.released("model=${currentModel?.name ?: "?"}")
            nativeContext = 0L
        }
    }

    /**
     * 2026-09-06 — optional audio-source override. When set,
     * [generateTranscription] decodes THIS file instead of the video
     * container. The ViewModel points it at the selected AudioClip's
     * file (the Video Audio object or an imported sound file), so
     * whisper consumes exactly the audio the user means.
     *
     * 2026-10-20 — REMOVED. The field was never written by anything:
     * the ViewModel resolves the source through the pure
     * `com.aicreatorstudio.app.model.resolveSubtitleSourceWindow` and
     * passes the resolved URI as the `videoUri` ARGUMENT of
     * [generateTranscription]. The KDoc claimed "The ViewModel points
     * it at the selected AudioClip's file", which had been untrue
     * since the resolution rules moved into the model layer.
     *
     * A public field that silently overrides an argument is a trap:
     * any future caller that set it would change what got
     * transcribed, with nothing in the call site to explain why.
     */
    override suspend fun generateTranscription(
        videoUri: Uri,
        onProgress: (Float) -> Unit
    ): Result<Transcription> = generateTranscriptionInternal(videoUri, onProgress) { }

    /**
     * 2026-09-07 DECODE-PROGRESS FIX — same pipeline as
     * [generateTranscription] but additionally reports REAL audio
     * decode progress (0..1 = decoded PTS / track duration, straight
     * from `MediaExtractor.sampleTime`) via [onDecodeProgress]. The
     * ViewModel binds this to `SubtitleGenerationState.DecodingAudio`
     * so the AI Subtitle card shows a live "در حال استخراج صدا... X٪"
     * bar during the MediaCodec decode instead of a frozen label that
     * sat at no-percentage for the entire decode.
     *
     * Cancellation is also cooperative now: the decode loop checks
     * the caller's Job every round, so a Cancel tap during decode
     * aborts within milliseconds instead of after the whole file.
     */
    suspend fun generateTranscription(
        videoUri: Uri,
        onProgress: (Float) -> Unit,
        onDecodeProgress: (Float) -> Unit
    ): Result<Transcription> =
        generateTranscriptionInternal(videoUri, onProgress, onDecodeProgress)

    private suspend fun generateTranscriptionInternal(
        videoUri: Uri,
        onProgress: (Float) -> Unit,
        onDecodeProgress: (Float) -> Unit
    ): Result<Transcription> = withContext(Dispatchers.IO) {
        val ctx = nativeContext
        if (ctx == 0L) {
            return@withContext Result.failure(
                Exception("مدل هنوز بارگذاری نشده است. ابتدا یک مدل را انتخاب و دانلود کنید.")
            )
        }
        try {
            // 2026-09-06 AUDIO-SOURCE FIX — prefer an already-extracted
            // audio file when the caller provides one (the Video Audio
            // lane object / an imported music file). The old path
            // always re-decoded the VIDEO container, which on some
            // devices stalls forever in MediaCodec ("در حال استخراج
            // صدا" with no progress). An .m4a/.wav/.mp3 file decodes
            // fast and reliably.
            //
            // 2026-10-20 — the source is the `videoUri` ARGUMENT. It
            // used to be `audioSourceUri ?: videoUri`, where
            // `audioSourceUri` was a field nothing ever set (see the
            // note above), so the expression was always the argument.
            val directSource: Uri = videoUri
            // 2026-09-07 CANCELLATION FIX — capture the caller's Job
            // and hand the decoder a cancellation check so Cancel
            // during "در حال استخراج صدا" aborts the MediaCodec loop
            // immediately (previously cancel was only honoured at
            // whisper 30-s window boundaries, i.e. never during a
            // long decode).
            val currentJob = coroutineContext[Job]
            val checkCancelled: () -> Unit = { currentJob?.ensureActive() }

            // 2026-09-07 REMUX-FIRST FIX — when the source is a VIDEO
            // container, pull the compressed audio track out into a
            // standalone .m4a first (MediaExtractor + MediaMuxer —
            // pure I/O, no decode, real progress, cancellable) and
            // decode THAT. This is the user's "select the audio file
            // on the timeline" idea implemented inside the pipeline:
            // whisper now always consumes a clean, small, audio-only
            // file, which decodes fast and reliably on every device
            // instead of stalling the panel on "در حال استخراج صدا".
            // Audio-only sources (imported m4a/wav/mp3, user-selected
            // audio objects) skip the remux and decode directly.
            //
            // 2026-09-08 PROGRESS CONTRACT — the two callbacks now have
            // a clean, documented semantic the ViewModel relies on for
            // pipeline-global weighting:
            //
            //   onDecodeProgress: 0..1 = progress across the WHOLE
            //     extraction phase (remux + decode, internal 0.7/0.3
            //     split below). ViewModel maps this onto 15%..35%.
            //   onProgress: 0..1 = progress of the TRANSCRIPTION phase
            //     ALONE (pure window ramp). ViewModel maps this onto
            //     35%..100%.
            //
            // Previously onProgress emitted 0.7..1.0 and the ViewModel
            // emitted phase-local 0..1 fractions, so the card's single
            // bar jumped backwards (extraction 100% → transcription 70%)
            // and the percentages had no coherent total meaning — the
            // user read "استخراج 70٪ → رونویسی 80٪" as a broken system.
            var remuxRan = false
            val remuxAdapter: (Float) -> Unit = { f ->
                remuxRan = true
                onDecodeProgress(0.7f * f.coerceIn(0f, 1f))
            }
            val preparedUri = runCatching {
                audioExtractor.prepareAudioSourceForDecode(
                    directSource,
                    onProgress = remuxAdapter,
                    checkCancelled = checkCancelled
                )
            }.getOrNull()
            if (preparedUri != null) {
                Log.i(
                    TAG,
                    "audio remux path active: ${preparedUri.lastPathSegment}"
                )
            }
            val decodeUri: Uri = preparedUri ?: directSource
            val decodePhaseStart = if (remuxRan) 0.7f else 0f
            val decodeAdapter: (Float) -> Unit = { f ->
                onDecodeProgress(
                    decodePhaseStart + (1f - decodePhaseStart) * f.coerceIn(0f, 1f)
                )
            }
            Log.i(
                TAG,
                "audio decode start: uri=${decodeUri.lastPathSegment} phaseStart=$decodePhaseStart"
            )

            val (pcmFloat, sampleRate, _) = run {
                try {
                    audioExtractor.decodeTo16kMonoFloat(
                        decodeUri,
                        onProgress = decodeAdapter,
                        checkCancelled = checkCancelled
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 2026-10-11 — name the stage instead of letting this
                    // surface as an anonymous "transcription failed".
                    WhisperDiagnostics.failure(
                        stage = WhisperDiagnostics.Stage.AUDIO_DECODE,
                        env = deviceEnv, model = null, error = e
                    )
                    return@withContext Result.failure(
                        WhisperFailure(
                            stage = WhisperDiagnostics.Stage.AUDIO_DECODE,
                            message = WhisperDiagnostics.persianFor(
                                WhisperDiagnostics.Stage.AUDIO_DECODE
                            ),
                            cause = e,
                            detail = e.javaClass.simpleName
                        )
                    )
                }
            }
            if (pcmFloat.isEmpty()) {
                WhisperDiagnostics.failure(
                    stage = WhisperDiagnostics.Stage.INFERENCE_EMPTY,
                    env = deviceEnv, model = null, detail = "empty pcm"
                )
                return@withContext Result.failure(
                    WhisperFailure(
                        stage = WhisperDiagnostics.Stage.INFERENCE_EMPTY,
                        message = WhisperDiagnostics.persianFor(
                            WhisperDiagnostics.Stage.INFERENCE_EMPTY
                        )
                    )
                )
            }
            if (sampleRate != SAMPLE_RATE) {
                return@withContext Result.failure(
                    Exception(
                        "نرخ نمونه‌برداری خروجی $sampleRate هرتز است " +
                            "اما $SAMPLE_RATE هرتز انتظار می‌رود"
                    )
                )
            }

            // 2026-10-20 AUDIO CONDITIONING — rumble removal +
            // level normalisation before the model sees the audio.
            // Whisper's log-mel front end has no AGC: quiet phone
            // recordings decode into fluent-but-wrong Persian
            // because the language model outvotes the acoustics.
            // See WhisperAudioConditioner for the full rationale.
            val condStart = System.currentTimeMillis()
            WhisperAudioConditioner.conditionInPlace(pcmFloat, SAMPLE_RATE)
            Log.i(
                TAG,
                "audio conditioned in ${System.currentTimeMillis() - condStart} ms " +
                    "(highpass=${WhisperAudioConditioner.HIGHPASS_HZ}Hz, " +
                    "target=${WhisperAudioConditioner.TARGET_RMS_DBFS}dBFS)"
            )

            // 2) Run whisper.cpp in ~30 s windows. Each window's
            //    word-level timestamps are stitched by adding the
            //    window's start time (samples / SAMPLE_RATE * 1000).
            val allWords = mutableListOf<TranscribedWord>()
            val windowSamples = (CHUNK_MS * SAMPLE_RATE / 1000L).toInt()
            // 2026-10-20 WINDOW BOUNDARIES — cut in the quietest
            // point near each 30 s mark instead of exactly on the
            // grid. A grid cut splits a word in half and both
            // halves decode wrong; see WindowPlanner.
            val windowBounds = WindowPlanner.boundaries(
                samples = pcmFloat,
                sampleRate = SAMPLE_RATE,
                windowSamples = windowSamples
            )
            val totalWindows = (windowBounds.size - 1).coerceAtLeast(1)
            // Tail of the previous window, reused as decoder context.
            var carriedContext = ""
            // 2026-09-06 — the user's language picker wins; the model
            // catalog's language ("fa") is the fallback for callers
            // that never set a selection.
            val lang = selectedLanguage?.code
                ?: currentModel?.language
                ?: "fa"

            // 2026-10-12 — record the inference configuration once per
            // run so a report can distinguish "wrong settings" from
            // "wrong model". Metadata only (counts + enum names).
            currentModel?.let { model ->
                WhisperDiagnostics.inferenceConfig(
                    modelId = model.name,
                    language = lang,
                    beamSize = accuracyMode.beamSize,
                    threads = effectiveThreads(),
                    temperatureInc = accuracyMode.temperatureInc,
                    vad = vadModelPathOrNull(context) != null
                )
            }

            for (windowIdx in 0 until totalWindows) {
                coroutineContext[Job]?.ensureActive()
                val windowStart = windowBounds[windowIdx]
                val windowEnd = windowBounds[windowIdx + 1]
                val wordsBeforeWindow = allWords.size
                // 2026-10-11 MEMORY FIX — `copyOfRange` allocates a
                // fresh FloatArray per window. For a 30-minute
                // recording that is 60 windows x 1.92 MB of transient
                // garbage, and on a device already under memory
                // pressure this churn is exactly what can push a
                // native `malloc` over the edge. The copy is
                // unavoidable in Kotlin (whisper.cpp needs a
                // contiguous buffer), but it is now scoped as tightly
                // as possible: the local reference is not carried
                // across iterations while the decoder still holds the
                // previous native copy.
                val windowSamplesActual: FloatArray =
                    pcmFloat.copyOfRange(windowStart, windowEnd)
                val windowOffsetMs = (windowStart * 1000L) / SAMPLE_RATE

                // 2026-10-11 — time each window. A window that is
                // pathologically slow on one device and fast on another
                // is itself diagnostic, and the counts are what let a
                // report distinguish "slow" from "failed".
                val windowThreads = effectiveThreads()
                val inferStart = System.currentTimeMillis()
                val wordEntries = try {
                    WhisperNative.fullWithWordTimestamps(
                        ctx = ctx,
                        samples = windowSamplesActual,
                        language = lang,
                        initialPrompt = WhisperPrompt.build(customDictionary, carriedContext),
                        beamSize = accuracyMode.beamSize,
                        vadModelPath = vadModelPathOrNull(context) ?: "",
                        translate = false,
                        noContext = true,
                        singleSegment = false,
                        threads = windowThreads,
                        temperatureInc = accuracyMode.temperatureInc
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // A native failure mid-inference must name its stage
                    // and must NOT be swallowed into an empty result
                    // (that is the stop-bug-3 class this project already
                    // fixed once on the JNI side).
                    WhisperDiagnostics.failure(
                        stage = WhisperDiagnostics.Stage.INFERENCE_ERROR,
                        env = deviceEnv, model = null, error = e,
                        detail = "window=${windowIdx + 1}/$totalWindows"
                    )
                    // 2026-10-12 — memory state at the moment of failure.
                    // A native malloc failure inside whisper_full is the
                    // most common real-device inference failure, and this
                    // is the number that proves or rules it out.
                    WhisperDiagnostics.memoryPressure(
                        context, "inference window=${windowIdx + 1}/$totalWindows"
                    )
                    return@withContext Result.failure(
                        WhisperFailure(
                            stage = WhisperDiagnostics.Stage.INFERENCE_ERROR,
                            message = WhisperDiagnostics.persianFor(
                                WhisperDiagnostics.Stage.INFERENCE_ERROR
                            ),
                            cause = e,
                            detail = "window=${windowIdx + 1} ${e.javaClass.simpleName}"
                        )
                    )
                }
                WhisperDiagnostics.inference(
                    windowIndex = windowIdx,
                    totalWindows = totalWindows,
                    samples = windowSamplesActual.size,
                    threads = windowThreads,
                    elapsedMs = System.currentTimeMillis() - inferStart,
                    wordEntries = wordEntries.size
                )

                // 2026-09-08 WORD-MERGE FIX — whisper.cpp's BPE emits
                // Persian words as MULTIPLE sub-word tokens: the token
                // that STARTS a word carries a leading space, the
                // continuation tokens do not ("کلمه" → " ک" + "ل" +
                // "م" + "ه"). The JNI bridge now preserves that leading
                // space, so a token whose text does NOT start with ' '
                // is a continuation and must be CONCATENATED into the
                // previous word — otherwise every letter renders as
                // its own subtitle ("ک ل م ه"). Concatenation merges
                // both the text and the time range.
                for (entry in wordEntries) {
                    if (entry.text.isBlank()) continue
                    val wordStartMs = windowOffsetMs + entry.t0Cs * 10L
                    val wordEndMs = windowOffsetMs + entry.t1Cs * 10L
                    if (wordEndMs <= wordStartMs) continue
                    val startsNewWord = entry.text.startsWith(" ") ||
                        allWords.isEmpty()
                    val stripped = entry.text.trim()
                    if (stripped.isEmpty()) continue
                    if (!startsNewWord && allWords.isNotEmpty()) {
                        // Continuation token — glue onto the previous
                        // word and extend its time range.
                        val prev = allWords.removeAt(allWords.size - 1)
                        allWords.add(
                            prev.copy(
                                text = prev.text + stripped,
                                endTimeMs = maxOf(prev.endTimeMs, wordEndMs)
                            )
                        )
                    } else {
                        allWords.add(
                            TranscribedWord(
                                text = stripped,
                                startTimeMs = wordStartMs,
                                endTimeMs = wordEndMs,
                                confidence = entry.probability.takeIf { it > 0f }
                            )
                        )
                    }
                }

                // 2026-10-20 CROSS-WINDOW CONTEXT — whisper.cpp's own
                // loop feeds the previous window's text back in via
                // `prompt_past`; this engine calls whisper_full once
                // per window with `no_context = true`, so that
                // continuity was lost and every window was decoded as
                // if the recording had just started. Carry the tail of
                // this window into the next prompt instead — gated on
                // confidence so a hallucinated window cannot poison
                // the following ones.
                val windowWords = allWords.subList(wordsBeforeWindow, allWords.size)
                carriedContext = WhisperPrompt.carryOverFrom(
                    windowText = windowWords.joinToString(" ") { it.text },
                    meanConfidence = WhisperPrompt.meanConfidence(
                        windowWords.mapNotNull { it.confidence }
                    )
                )

                // 2026-09-08 PROGRESS CONTRACT — PURE phase-local ramp
                // (0..1 across the whisper windows). The ViewModel owns
                // the pipeline-global weighting (35%..100%); the engine
                // must NOT pre-weight it. See the contract comment at
                // the remux block above.
                onProgress((windowIdx + 1).toFloat() / totalWindows)
            }

            // 2026-10-11 — the decoded recording is no longer needed
            // once every window has been consumed. Nothing below reads
            // `pcmFloat`; the grouping step works purely from
            // `allWords`. Kotlin cannot force an array free and
            // `System.gc()` is deliberately NOT called here (it stalls
            // the frame and ART's concurrent collector already handles
            // this), but the buffer is now provably dead at this point,
            // which is what the scoping below records.

            // 3) Group into readable subtitle segments (Premiere-style
            //    multi-word captions), preserving word timing for the
            //    karaoke highlight.
            val segments = groupWordsIntoSubtitlesForWhisper(allWords)
            // Surface the actual transcription size in logcat so the
            // user / QA can confirm whisper.cpp produced a real
            // result and not a silent zero. PHASE B3 stop-bug-3
            // audit: previously a swallowed JNI exception could
            // mask transcription failures as empty results.
            Log.d(
                TAG,
                "transcription complete: words=${allWords.size} " +
                    "segments=${segments.size}"
            )
            onProgress(1f)

            Result.success(
                Transcription(
                    segments = segments,
                    words = allWords.sortedBy { it.startTimeMs }
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "generateTranscription failed", e)
            Result.failure(e)
        }
    }

    override fun release() {
        // 2026-09-07 MUTEX FIX — the old tryLock/isLocked dance freed
        // the native context WITHOUT holding the lock when an init
        // was in flight, and then unlocked the OTHER holder's lock
        // (kotlinx Mutex has no owner). Now: take the lock properly
        // (waiting if an init is running) and free under it. If the
        // coroutine is cancelled while waiting, the native context
        // is left intact — the next init retries safely.
        runBlocking {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    initMutex.withLock {
                        releaseNativeContext()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Caller cancelled while waiting — leave the
                    // context for the next run.
                }
            }
        }
    }

    /**
     * PHASE B7 — developer-only entry point used by the
     * model-comparison benchmark
     * ([com.aicreatorstudio.app.ai.benchmark.WhisperModelBenchmark]).
     *
     * Like [generateTranscription] but takes a [CatalogEntry]
     * directly, bypassing the production [WhisperModelSpec]
     * enum so the benchmark can also exercise off-catalog
     * models (Tiny, Small) without polluting the production
     * UI surface.
     *
     * The method is **self-contained**: it loads the supplied
     * [CatalogEntry]'s GGML file into a *separate* native
     * context (`benchContext`), transcribes with that context,
     * and frees it. The production `nativeContext` and
     * `currentModel` are NOT touched. This is critical so
     * running the benchmark does not evict a real production
     * model from memory and does not break the production
     * `release()` / `currentModel` invariants.
     *
     * Returns [Result.failure] when the model is not on disk
     * or when the native side reports an error; the benchmark
     * records that as `note = "not installed"` /
     * `note = "load failed"`.
     *
     * Marked `internal` so it is visible to the
     * `connectedAndroidTest` source set (which is the same
     * Gradle module) but not to production callers.
     */
    internal suspend fun benchmarkTranscribe(
        catalogEntry: CatalogEntry,
        videoUri: android.net.Uri
    ): Result<Transcription> = withContext(Dispatchers.IO) {
        var benchContext: Long = 0L
        try {
            runNativeSelfTest()
            val path = AiModels.modelFile(context, catalogEntry).absolutePath
            if (!File(path).exists()) {
                return@withContext Result.failure(
                    Exception("مدل Whisper روی دیسک پیدا نشد: $path")
                )
            }
            val ctxRaw = WhisperNative.initFromFile(path)
            if (ctxRaw == null || ctxRaw == 0L) {
                return@withContext Result.failure(
                    Exception("بارگذاری مدل Whisper ناموفق بود. path=$path")
                )
            }
            benchContext = ctxRaw
            Log.i(
                TAG,
                "Whisper native context loaded (benchmark): entry=${catalogEntry.id} path=$path"
            )

            // 1) Decode audio. Same code path as production
            //    generateTranscription (including cooperative
            //    cancellation during the decode).
            val benchJob = coroutineContext[Job]
            val (pcmFloat, sampleRate, _) = audioExtractor.decodeTo16kMonoFloat(
                videoUri,
                checkCancelled = { benchJob?.ensureActive() }
            )
            if (pcmFloat.isEmpty()) {
                return@withContext Result.failure(
                    Exception("صدای قابل رونویسی در ویدیو پیدا نشد")
                )
            }
            if (sampleRate != SAMPLE_RATE) {
                return@withContext Result.failure(
                    Exception(
                        "نرخ نمونه‌برداری خروجی $sampleRate هرتز است " +
                            "اما $SAMPLE_RATE هرتز انتظار می‌رود"
                    )
                )
            }

            // 2) Windowed whisper_full. Same as production but
            //    with `benchContext` and `catalogEntry.language`
            //    (default "fa") in place of `currentModel`.
            val allWords = mutableListOf<TranscribedWord>()
            val windowSamples = (CHUNK_MS * SAMPLE_RATE / 1000L).toInt()
            val totalSamples = pcmFloat.size
            val totalWindows = ((totalSamples + windowSamples - 1) / windowSamples)
                .coerceAtLeast(1)
            val lang = catalogEntry.language ?: "fa"
            val threads = (catalogEntry.whisperThreads ?: 4)
                .coerceIn(1, 8)
            for (windowIdx in 0 until totalWindows) {
                coroutineContext[Job]?.ensureActive()
                val windowStart = windowIdx * windowSamples
                val windowEnd = minOf(windowStart + windowSamples, totalSamples)
                val windowSamplesActual = pcmFloat.copyOfRange(windowStart, windowEnd)
                val windowOffsetMs = (windowStart * 1000L) / SAMPLE_RATE
                val wordEntries = WhisperNative.fullWithWordTimestamps(
                    ctx = benchContext,
                    samples = windowSamplesActual,
                    language = lang,
                    initialPrompt = "این یک نمونه متن فارسی است. با فاصله " +
                        "بین کلمات نوشته شده است. ما به ایران آمدیم و " +
                        "این چند دقیقه خیلی خوب بود. الان چه می‌گویید؟ " +
                        "من هم می‌توانم این کار را بکنم، اما نه امروز.",
                    beamSize = 5,
                    translate = false,
                    noContext = true,
                    singleSegment = false,
                    threads = threads,
                    temperatureInc = AccuracyMode.BALANCED.temperatureInc
                )
                for (entry in wordEntries) {
                    if (entry.text.isBlank()) continue
                    val wordStartMs = windowOffsetMs + entry.t0Cs * 10L
                    val wordEndMs = windowOffsetMs + entry.t1Cs * 10L
                    if (wordEndMs <= wordStartMs) continue
                    // 2026-09-08 WORD-MERGE — mirrors the production
                    // path: continuation tokens (no leading space) glue
                    // into the previous word.
                    val startsNewWord = entry.text.startsWith(" ") ||
                        allWords.isEmpty()
                    val stripped = entry.text.trim()
                    if (stripped.isEmpty()) continue
                    if (!startsNewWord && allWords.isNotEmpty()) {
                        val prev = allWords.removeAt(allWords.size - 1)
                        allWords.add(
                            prev.copy(
                                text = prev.text + stripped,
                                endTimeMs = maxOf(prev.endTimeMs, wordEndMs)
                            )
                        )
                    } else {
                        allWords.add(
                            TranscribedWord(
                                text = stripped,
                                startTimeMs = wordStartMs,
                                endTimeMs = wordEndMs,
                                confidence = entry.probability.takeIf { it > 0f }
                            )
                        )
                    }
                }
            }
            val segments = groupWordsIntoSubtitlesForWhisper(allWords)
            Log.d(
                TAG,
                "benchmark transcription complete: entry=${catalogEntry.id} " +
                    "words=${allWords.size} segments=${segments.size}"
            )
            Result.success(
                Transcription(
                    segments = segments,
                    words = allWords.sortedBy { it.startTimeMs }
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "benchmarkTranscribe(${catalogEntry.id}) failed", e)
            Result.failure(e)
        } finally {
            if (benchContext != 0L) {
                WhisperNative.free(benchContext)
            }
        }
    }

    /**
     * 2026-09-08 VAD — extract the bundled silero VAD model to a
     * readable file path and return it. whisper.cpp's VAD loads from
     * a file path (not assets), so on first use the 885 KB binary is
     * streamed out of assets into cacheDir. Returns null when the
     * asset is missing — VAD then silently stays off (the pipeline
     * still works, just without the quality boost).
     */
    private fun vadModelPathOrNull(context: Context): String? = runCatching {
        val cacheFile = File(context.cacheDir, "silero-v6.2.0-ggml.bin")
        if (!cacheFile.exists() || cacheFile.length() < 100_000L) {
            context.assets.open("ai-models/silero-vad/silero-v6.2.0-ggml.bin")
                .use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                }
        }
        cacheFile.absolutePath
    }.getOrNull()

    /**
     * 2026-09-09 ACCURACY MODES — REAL decoder parameter sets mapped
     * to whisper.cpp knobs (beam_size / temperature_inc). Not
     * cosmetic labels: every field is passed straight through the
     * JNI into `whisper_full_params`.
     *
     * ## 2026-10-20 — why every mode now uses the same fallback step
     *
     * `temperature_inc` is not a quality knob, it is the spacing of
     * the *failure* ladder: whisper.cpp decodes at 0, then retries
     * at +inc, +2·inc … up to 1.0, and the LAST rung is accepted
     * unconditionally (`if (it != temperatures.size() - 1)` guards
     * the quality test). A small step therefore means "try more and
     * more random decodes", and the accepted subtitle could be the
     * 1.0 one. BALANCED used 0.1, i.e. eleven rungs, which is how a
     * clean window ended up replaced by a fluent invention.
     *
     * 0.2 is whisper.cpp's own default and gives six rungs; the
     * accuracy difference between the modes now comes from beam
     * width alone, which is the parameter that actually helps.
     *
     *  - FAST: greedy (beam 1) — for weak devices, clear audio.
     *  - BALANCED: beam 5 — the default.
     *  - ACCURATE: beam 8 — widest hypothesis net for noisy /
     *    fast / multi-speaker audio.
     */
    enum class AccuracyMode(
        val key: String,
        val displayName: String,
        val beamSize: Int,
        val temperatureInc: Float
    ) {
        FAST("fast", "Fast", 1, 0.2f),
        BALANCED("balanced", "Balanced", 5, 0.2f),
        ACCURATE("accurate", "Accurate", 8, 0.2f);

        companion object {
            fun fromKey(key: String?): AccuracyMode =
                entries.firstOrNull { it.key == key } ?: BALANCED
        }
    }

    /** Current accuracy mode (persisted via SettingsRepository). */
    @Volatile
    var accuracyMode: AccuracyMode = AccuracyMode.BALANCED

    /**
     * 2026-09-09 CUSTOM DICTIONARY — the user's brand names / people /
     * jargon, injected into the initial prompt so the BPE decoder
     * preferentially emits them with correct spelling. whisper.cpp's
     * documented extension mechanism for this IS the initial prompt
     * (`whisper_full_params.initial_prompt` is prepended to the decode
     * context; 224-token budget). Empty when the user hasn't added
     * entries.
     */
    @Volatile
    var customDictionary: List<String> = emptyList()

    /**
     * Effective number of CPU threads to ask whisper.cpp to use
     * for the next `whisper_full(...)` call.
     *
     * ## 2026-10-11 compatibility change
     *
     * The old implementation clamped `whisperThreads` only to
     * `Runtime.availableProcessors()`. On an 8 GB phone that
     * reports 8 logical cores (4 big + 4 little), whisper.cpp
     * would start 8 decoder threads; the little cores finish
     * their share far slower than the big ones, so the decoder
     * waits on them. Worse, every extra thread carries its own
     * scratch buffers, so on a device that is *already* close to
     * its native-memory ceiling, asking for more threads than
     * necessary is a direct cause of a native allocation
     * failure — which is precisely one of the failure modes
     * this investigation is about.
     *
     * Two guards now apply:
     *
     *  1. **[Threads.REQUESTED_CAP]** — never ask for more than
     *     4 no matter what the catalog says. The catalog ships
     *     4, this is the value the project actually validated,
     *     and going above it has no measured accuracy benefit
     *     while costing memory per thread.
     *  2. **Big-core heuristic** — when the CPU count is above
     *     the cap, use the cap rather than the raw count. This
     *     is deliberately the *same* value on a 4-core and an
     *     8-core phone, because the decoder is bandwidth-bound
     *     long before it is core-bound.
     *
     * The result is still clamped down for genuinely small
     * devices (2 cores → 2 threads).
     */
    private fun effectiveThreads(): Int {
        val requested = currentModel?.aiSpec()?.whisperThreads ?: DEFAULT_THREADS
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val maxFromNative = lastSelfTest?.systemMaxThreads ?: cores
        // Cap by the project-validated ceiling first, then by the
        // device, then by what the native self-test reported.
        val capped = minOf(requested, Threads.REQUESTED_CAP)
        val cap = minOf(cores, maxFromNative, Threads.REQUESTED_CAP).coerceAtLeast(1)
        return capped.coerceIn(1, cap)
    }

    // ---- Audio decode (16 kHz mono float32) ----
    // Decoding is delegated to [AudioExtractor] so the Voice Clean
    // engine can reuse the same MediaExtractor + MediaCodec plumbing
    // without duplicating ~140 lines of codec code.

    companion object {
        private const val TAG = "WhisperEngine"

        /** Threads requested when the catalog carries no value. */
        const val DEFAULT_THREADS: Int = 4

        /**
         * Thread policy for the offline decoder.
         *
         * See [effectiveThreads] for why the ceiling exists. Kept as
         * a named object so the value is greppable and so a future
         * settings surface can read [REQUESTED_CAP] rather than
         * duplicating the number.
         */
        object Threads {
            /**
             * Hard ceiling on decoder threads regardless of how many
             * logical cores the device reports. Chosen because the
             * project validated 4, and because extra threads cost
             * native scratch memory without a measured accuracy win.
             */
            const val REQUESTED_CAP: Int = 4
        }

        /** whisper.cpp's required sample rate (Hz). */
        const val SAMPLE_RATE: Int = 16_000

        /** Window length for `whisper_full`. */
        const val CHUNK_MS: Long = 30_000L

        /** Progress allocation: download = 0..0.9, native load = 0.9..1.0. */
        const val DOWNLOAD_WEIGHT: Float = 0.9f

        /** Whisper.cpp's full-default strategy uses ~30s windows. */
        const val MAX_SEGMENT_DURATION_MS: Long = 4_000L
        const val MAX_WORDS_PER_SEGMENT: Int = 10
        const val MAX_WORD_GAP_MS: Long = 1_200L
    }
}

/**
 * Groups recognized words into readable subtitle segments while
 * keeping each segment pinned to the exact start of its first word
 * and the exact end of its last word. The per-word timing is
 * preserved on the returned [Subtitle.words] list so the
 * timeline/preview can drive karaoke-style word highlighting.
 */
internal fun groupWordsIntoSubtitlesForWhisper(
    words: List<TranscribedWord>
): List<Subtitle> {
    if (words.isEmpty()) return emptyList()
    val sorted = words.sortedBy { it.startTimeMs }
    val subtitles = mutableListOf<Subtitle>()
    var current = mutableListOf<TranscribedWord>()

    fun flush() {
        if (current.isEmpty()) return
        val startMs = current.first().startTimeMs.coerceAtLeast(0L)
        var endMs = current.last().endTimeMs
        if (endMs <= startMs) endMs = startMs + 500
        val conf = current.mapNotNull { it.confidence }.takeIf { it.isNotEmpty() }
            ?.average()?.toFloat()
        subtitles.add(
            Subtitle(
                text = current.joinToString(" ") { it.text }.trim(),
                startTimeMs = startMs,
                endTimeMs = endMs,
                words = current.toList(),
                confidence = conf
            )
        )
        current = mutableListOf()
    }

    sorted.forEach { word ->
        if (current.isNotEmpty()) {
            val segmentStart = current.first().startTimeMs
            val segmentEnd = word.endTimeMs
            val duration = segmentEnd - segmentStart
            val gap = word.startTimeMs - current.last().endTimeMs
            if (duration > WhisperEngine.MAX_SEGMENT_DURATION_MS ||
                current.size >= WhisperEngine.MAX_WORDS_PER_SEGMENT ||
                gap > WhisperEngine.MAX_WORD_GAP_MS
            ) {
                flush()
            }
        }
        current.add(word)
    }
    flush()
    return subtitles
}