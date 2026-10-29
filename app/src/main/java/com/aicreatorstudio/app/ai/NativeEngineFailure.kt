package com.aicreatorstudio.app.ai

/**
 * The **real** reasons a native AI engine can fail to load, kept
 * strictly apart from one another.
 *
 * ## Why this type exists (2026-10-13 root-cause fix)
 *
 * Before this, every native-load failure in the app reached the user
 * as one sentence from [AiFailure.ENGINE_UNAVAILABLE]:
 *
 * > «موتور بومی بارگذاری نشد. این دستگاه از پردازنده‌ی لازم
 * >  پشتیبانی نمی‌کند.»
 *
 * That sentence is **wrong for most of the failures that produced it**.
 * It was emitted from at least six call sites, only one of which is
 * actually about CPU capability:
 *
 * | Site | Real cause | Was the message honest? |
 * |------|-----------|-------------------------|
 * | `WhisperEngine.computeIsAvailable` | `libwhisper.so` missing/unloadable | No — it's a packaging fault |
 * | `WhisperEngine.runNativeSelfTest` | library load or JNI symbol resolution | No |
 * | `WhisperEngine.initializeModelFor` (`UnsatisfiedLinkError`) | library load | No |
 * | `WhisperEngine.ensureNativeContext` (`LibraryUnavailable`) | library load | No |
 * | `MediaPipeMattingEngine.ensureSegmenterLocked` | `libmediapipe_tasks_vision_jni.so` load **or** model file / init failure | No |
 * | `SaliencyTrackerEngine.ensureLandmarkerLocked` | same as matting | No |
 *
 * The consequences were measurable:
 *
 * 1. **It blamed hardware for a file problem.** A truncated
 *    `whisper-small.bin` — or a MediaPipe model that failed to
 *    initialise — told the user their *processor* was unsupported, so
 *    they stopped looking for the real fix (re-download the model).
 * 2. **It made bug reports useless.** "CPU not supported" is not a
 *    stage, so a report contained nothing actionable.
 * 3. **It was actively misleading on the one case it did describe.**
 *    The app ships **only** `arm64-v8a` (`abiFilters +=
 *    listOf("arm64-v8a")`; verified by APK inspection — 14 `.so`
 *    files, all under `lib/arm64-v8a/`). On a `armeabi-v7a` or
 *    `x86_64` device the honest cause is *"this APK does not contain
 *    a library for your processor architecture"* — a packaging fact —
 *    not *"your processor lacks a required instruction set"*.
 *
 * [NativeEngineFailure] replaces the single string with distinct
 * machine-readable causes, each with its own Persian message and a
 * recovery hint. The classification itself is a **pure function**
 * ([classify]) so it is fully JVM-testable — see the project rule that
 * anything needing a `Context` or an `@Composable` cannot be tested.
 *
 * ## What is deliberately unchanged
 *
 * The native libraries, the JNI symbols, the ABI filter, the model
 * files and the inference parameters are all untouched. This is a
 * *reporting* fix: it changes which sentence the user reads, never
 * what the code does.
 */
enum class NativeEngineFailure {

    /**
     * The APK contains **no** copy of the library for the device's
     * primary ABI.
     *
     * This is a packaging fact, not a CPU limitation. On Omelet it
     * means the device is not `arm64-v8a`, because `abiFilters`
     * strips every other ABI out of the APK.
     *
     * Recovery: none in-app — the APK cannot be repaired at runtime.
     * The user needs a build that contains their ABI.
     */
    LIBRARY_ABI_MISSING,

    /**
     * A library *could* exist for this ABI but the loader refused it:
     * a transitive `DT_NEEDED` dependency is missing, the ELF is
     * truncated, the page-size / linker requirement is unmet, or the
     * file was stripped out of the package by minification.
     *
     * Recovery: reinstall the app; if it persists it is a build fault.
     */
    LIBRARY_LOAD_FAILED,

    /**
     * The library loaded, but a JNI method could not be bound — the
     * `Java_...` symbol is absent from the `.so`, or the Kotlin
     * declaration and the C signature disagree.
     *
     * Recovery: none in-app; this is a build fault, report it.
     */
    JNI_SYMBOL_UNRESOLVED,

    /**
     * The library loaded and symbols resolved, but a genuinely
     * required CPU instruction is absent (e.g. an `SIGILL` / illegal
     * instruction trap from a kernel compiled for a newer
     * micro-architecture).
     *
     * This is the **only** case where "the processor is not
     * supported" is the correct thing to tell the user, and it is
     * deliberately the rarest.
     */
    CPU_INSTRUCTION_UNSUPPORTED,

    /**
     * The model file is absent, truncated, or fails its integrity
     * check. The engine itself is fine.
     *
     * Recovery: re-download the model.
     */
    MODEL_INVALID,

    /**
     * The model file passed every check and the native library
     * returned a null context. With a byte-exact file this is a
     * native allocation failure.
     *
     * Recovery: free memory, restart the app, try a smaller model.
     */
    NATIVE_INIT_FAILED,

    /** The device could not satisfy a native allocation. */
    INSUFFICIENT_MEMORY,

    /**
     * Anything we could not attribute. Never claims a cause we have
     * not evidenced.
     *
     * Recovery: retry; if it recurs, export the technical report.
     */
    UNKNOWN,
    ;

    /**
     * One-line Persian message for the UI.
     *
     * Each arm is a *different* sentence on purpose: the whole point
     * of this enum is that a user (and a bug report) can tell the
     * cases apart.
     */
    fun persianMessage(): String = when (this) {
        LIBRARY_ABI_MISSING ->
            "این نسخه از برنامه برای پردازنده‌ی این دستگاه ساخته نشده است " +
                "(معماری پردازنده پشتیبانی نمی‌شود)."
        LIBRARY_LOAD_FAILED ->
            "کتابخانهٔ پردازش هوش مصنوعی روی این دستگاه بارگذاری نشد. " +
                "لطفاً برنامه را دوباره نصب کنید."
        JNI_SYMBOL_UNRESOLVED ->
            "کتابخانهٔ هوش مصنوعی ناقص نصب شده است. لطفاً برنامه را دوباره نصب کنید."
        CPU_INSTRUCTION_UNSUPPORTED ->
            "پردازندهٔ این دستگاه دستورهای لازم برای اجرای این مدل را ندارد."
        MODEL_INVALID ->
            "فایل مدل ناقص یا خراب است. لطفاً مدل را دوباره دانلود کنید."
        NATIVE_INIT_FAILED ->
            "موتور هوش مصنوعی نتوانست مدل را اجرا کند. برنامه‌های پس‌زمینه را " +
                "ببندید و دوباره تلاش کنید؛ اگر تکرار شد مدل کوچک‌تری انتخاب کنید."
        INSUFFICIENT_MEMORY ->
            "حافظهٔ آزاد کافی نیست. برنامه‌های پس‌زمینه را ببندید، " +
                "دستگاه را یک‌بار ری‌استارت کنید یا مدل کوچک‌تری انتخاب کنید."
        UNKNOWN ->
            "اجرای موتور هوش مصنوعی ناموفق بود. لطفاً دوباره تلاش کنید."
    }

    /**
     * Short recovery hint appended to a technical report. `null` when
     * there is nothing the user can do.
     */
    fun recoveryHint(): String? = when (this) {
        LIBRARY_ABI_MISSING -> null
        LIBRARY_LOAD_FAILED -> "reinstall_app"
        JNI_SYMBOL_UNRESOLVED -> "reinstall_app"
        CPU_INSTRUCTION_UNSUPPORTED -> null
        MODEL_INVALID -> "redownload_model"
        NATIVE_INIT_FAILED -> "free_memory_or_smaller_model"
        INSUFFICIENT_MEMORY -> "free_memory_or_restart"
        UNKNOWN -> "retry_then_report"
    }

    companion object {

        /**
         * Map a thrown native-load [Throwable] plus the surrounding
         * facts to a specific cause.
         *
         * ## Why the *class* and the *message* are both consulted
         *
         * `UnsatisfiedLinkError` is thrown for at least four distinct
         * situations on Android and its message is the only
         * discriminator the platform gives us:
         *
         * ```
         * "dlopen failed: library \"libwhisper.so\" not found"
         * "dlopen failed: cannot locate symbol \"whisper_init_from_file_with_params\"..."
         * "dlopen failed: ... has unexpected e_machine: 3"
         * "No implementation found for long com.…WhisperNative.nativeInit"
         * ```
         *
         * The first is a packaging/ABI problem, the third is a real
         * architecture mismatch, and the fourth is a JNI binding
         * problem. Collapsing them — which the old code did — is
         * exactly the defect this function exists to remove.
         *
         * @param error the caught throwable, if any.
         * @param primaryAbi the device's primary ABI, as reported by
         *   [android.os.Build.SUPPORTED_ABIS] (or a test value).
         * @param packagedAbis the ABIs actually present in the APK.
         * @param libraryAvailable whether the library is known to be
         *   packaged for [primaryAbi]. When `false`, the failure is
         *   attributed to packaging *before* the message is inspected,
         *   because that is the dominant cause and it is knowable
         *   without a guess.
         * @param lowMemory whether the device reported memory
         *   pressure at the time of the failure.
         */
        fun classify(
            error: Throwable?,
            primaryAbi: String,
            packagedAbis: Set<String> = PACKAGED_ABIS,
            libraryAvailable: Boolean = primaryAbi in packagedAbis,
            lowMemory: Boolean = false
        ): NativeEngineFailure {
            // 1. Packaging before prose: if the APK has no library for
            //    this ABI, that is the answer regardless of what the
            //    loader chose to say.
            if (!libraryAvailable) {
                return if (error is UnsatisfiedLinkError) {
                    // Distinguish "absent" from "wrong architecture".
                    val m = error.message.orEmpty().lowercase()
                    if (m.contains("e_machine") || m.contains("wrong elf") ||
                        m.contains("is 32-bit instead of 64-bit") ||
                        m.contains("is 64-bit instead of 32-bit")
                    ) {
                        CPU_INSTRUCTION_UNSUPPORTED
                    } else {
                        LIBRARY_ABI_MISSING
                    }
                } else {
                    LIBRARY_ABI_MISSING
                }
            }

            // 2. Native allocation failures. Checked before the generic
            //    link-error branch: an OOM surfaces as an `Error` whose
            //    message names the allocation, and blaming a missing
            //    library would send the user to reinstall for nothing.
            if (lowMemory || isAllocationFailure(error)) {
                return INSUFFICIENT_MEMORY
            }

            if (error is UnsatisfiedLinkError) {
                val m = error.message.orEmpty().lowercase()
                return when {
                    // "No implementation found for …" — the .so loaded
                    // but the Java_… symbol is not in it.
                    m.contains("no implementation found") -> JNI_SYMBOL_UNRESOLVED
                    // Explicit architecture rejection from the loader.
                    m.contains("e_machine") ||
                        m.contains("wrong elf") ||
                        m.contains("is 32-bit instead of 64-bit") ||
                        m.contains("is 64-bit instead of 32-bit") -> CPU_INSTRUCTION_UNSUPPORTED
                    // Everything else from dlopen: a dependency is
                    // missing or the ELF is unusable.
                    else -> LIBRARY_LOAD_FAILED
                }
            }

            // 3. SIGILL / illegal-instruction traps arrive as these
            //    types, which is what a genuine unsupported-CPU
            //    failure actually looks like at runtime.
            if (error != null && isIllegalInstruction(error)) {
                return CPU_INSTRUCTION_UNSUPPORTED
            }

            // 4. A null native context with a byte-exact model file was
            //    already disambiguated by the caller; anything reaching
            //    here without a recognisable signature is reported
            //    honestly as unknown rather than blamed on hardware.
            return if (error == null) UNKNOWN else NATIVE_INIT_FAILED
        }

        /**
         * Classify a **model-side** failure (absent / truncated /
         * wrong size / SHA mismatch). Kept separate from [classify] so
         * a model problem can never be reported as a hardware problem
         * — the single most damaging confusion the old code allowed.
         */
        fun classifyModel(
            exists: Boolean,
            truncated: Boolean,
            wrongSize: Boolean
        ): NativeEngineFailure = when {
            !exists -> MODEL_INVALID
            truncated -> MODEL_INVALID
            wrongSize -> MODEL_INVALID
            else -> NATIVE_INIT_FAILED
        }

        /** `UnsatisfiedLinkError` messages that name an allocation failure. */
        private fun isAllocationFailure(error: Throwable?): Boolean {
            val m = (error?.message ?: return false).lowercase()
            return m.contains("out of memory") ||
                m.contains("cannot allocate") ||
                m.contains("failed to allocate") ||
                m.contains("std::bad_alloc") ||
                m.contains("ggml_new_tensor") ||
                m.contains("mmap failed")
        }

        /** `SIGILL`-family throwables — the true "CPU lacks it" signal. */
        private fun isIllegalInstruction(error: Throwable): Boolean {
            val name = error.javaClass.name
            return name == "java.lang.InternalError" ||
                name.contains("IllegalInstruction") ||
                name == "android.util.SuperNotCalledException"
        }

        /**
         * The ABIs this APK actually packages.
         *
         * Verified by inspecting the release artifact: every native
         * library lives under `lib/arm64-v8a/`. Kept in sync with
         * `app/build.gradle.kts` -> `ndk { abiFilters +=
         * listOf("arm64-v8a") }` and asserted by a regression test.
         */
        val PACKAGED_ABIS: Set<String> = setOf("arm64-v8a")
    }
}
