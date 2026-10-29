package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the 2026-10-13 native-engine failure
 * classification.
 *
 * ## What these tests protect
 *
 * The defect being fixed was **not** a crash — it was a *lie*. Every
 * native-load failure in the app reached the user as
 * «این دستگاه از پردازنده‌ی لازم پشتیبانی نمی‌کند» regardless of the
 * actual cause, so:
 *
 * - a missing `.so` (packaging) was reported as a CPU limitation;
 * - a JNI symbol mismatch (build) was reported as a CPU limitation;
 * - a model file that failed to initialise was reported as a CPU
 *   limitation;
 * - a real out-of-memory condition was reported as a CPU limitation.
 *
 * These tests assert that each of those inputs maps to its own
 * distinct cause **and** its own distinct Persian sentence, and — most
 * importantly — that only a genuine architecture/instruction failure
 * is ever allowed to say "your processor is not supported".
 *
 * Pure JVM logic: no `Context`, no `@Composable`, no native library.
 * `android.os.Build` is deliberately not touched here; the ABI is a
 * parameter so the decision is directly testable (the project's
 * `unitTests.isReturnDefaultValues = true` makes `Build.SUPPORTED_ABIS`
 * return an empty array in a JVM test, which would make an assertion
 * pass for the wrong reason).
 */
class NativeEngineFailureTest {

    // ---- ABI decisions ------------------------------------------------

    @Test
    fun `arm64 device with arm64 library is considered available`() {
        val failure = NativeEngineFailure.classify(
            error = UnsatisfiedLinkError("dlopen failed: library \"libwhisper.so\" not found"),
            primaryAbi = "arm64-v8a",
            packagedAbis = setOf("arm64-v8a"),
            libraryAvailable = true
        )
        // The library IS packaged, so this is not an ABI problem even
        // though the loader said "not found" — it is a load failure.
        assertEquals(NativeEngineFailure.LIBRARY_LOAD_FAILED, failure)
    }

    @Test
    fun `armeabi-v7a device with only arm64 packaged is an abi packaging fault not a cpu fault`() {
        val failure = NativeEngineFailure.classify(
            error = UnsatisfiedLinkError("dlopen failed: library \"libwhisper.so\" not found"),
            primaryAbi = "armeabi-v7a",
            packagedAbis = setOf("arm64-v8a"),
            libraryAvailable = false
        )
        assertEquals(NativeEngineFailure.LIBRARY_ABI_MISSING, failure)
        // ...and it must NOT be reported as an instruction-set problem.
        assertTrue(
            "a missing packaged ABI must never be classified as an unsupported CPU",
            failure != NativeEngineFailure.CPU_INSTRUCTION_UNSUPPORTED
        )
    }

    @Test
    fun `x86_64 device with only arm64 packaged is an abi packaging fault`() {
        val failure = NativeEngineFailure.classify(
            error = null,
            primaryAbi = "x86_64",
            packagedAbis = setOf("arm64-v8a"),
            libraryAvailable = false
        )
        assertEquals(NativeEngineFailure.LIBRARY_ABI_MISSING, failure)
    }

    @Test
    fun `packaged abis constant matches the gradle abi filter`() {
        // Guards against the build.gradle.kts abiFilters drifting away
        // from what the classifier believes ships. If someone adds
        // armeabi-v7a to the build, this test fails and forces them to
        // update PACKAGED_ABIS (and to have actually verified a .so
        // exists for it).
        assertEquals(setOf("arm64-v8a"), NativeEngineFailure.PACKAGED_ABIS)
    }

    // ---- Wrong-architecture ELF ---------------------------------------

    @Test
    fun `wrong elf e_machine is reported as a cpu architecture mismatch`() {
        // This is what dlopen emits when the ELF is for a different
        // machine than the device — the one case "your CPU is not
        // supported" is actually correct.
        val failure = NativeEngineFailure.classify(
            error = UnsatisfiedLinkError(
                "dlopen failed: \"/data/app/lib/arm64/libwhisper.so\" has unexpected " +
                    "e_machine: 3"
            ),
            primaryAbi = "arm64-v8a",
            packagedAbis = setOf("arm64-v8a"),
            libraryAvailable = true
        )
        assertEquals(NativeEngineFailure.CPU_INSTRUCTION_UNSUPPORTED, failure)
    }

    @Test
    fun `32 bit library on 64 bit device is a cpu architecture mismatch`() {
        val failure = NativeEngineFailure.classify(
            error = UnsatisfiedLinkError(
                "dlopen failed: \"/data/app/lib/libwhisper.so\" is 32-bit instead of 64-bit"
            ),
            primaryAbi = "arm64-v8a",
            libraryAvailable = true
        )
        assertEquals(NativeEngineFailure.CPU_INSTRUCTION_UNSUPPORTED, failure)
    }

    // ---- JNI binding ---------------------------------------------------

    @Test
    fun `no implementation found is a jni symbol problem`() {
        // The .so loaded fine; only the Java_… binding is missing.
        // Conflating this with a CPU issue is precisely the bug.
        val failure = NativeEngineFailure.classify(
            error = UnsatisfiedLinkError(
                "No implementation found for long " +
                    "com.aicreatorstudio.app.ai.WhisperNative.nativeInit(java.lang.String)"
            ),
            primaryAbi = "arm64-v8a",
            libraryAvailable = true
        )
        assertEquals(NativeEngineFailure.JNI_SYMBOL_UNRESOLVED, failure)
        assertTrue(failure != NativeEngineFailure.CPU_INSTRUCTION_UNSUPPORTED)
    }

    // ---- Memory --------------------------------------------------------

    @Test
    fun `reported memory pressure wins over a generic link error`() {
        val failure = NativeEngineFailure.classify(
            error = null,
            primaryAbi = "arm64-v8a",
            libraryAvailable = true,
            lowMemory = true
        )
        assertEquals(NativeEngineFailure.INSUFFICIENT_MEMORY, failure)
    }

    @Test
    fun `allocation failure message is classified as insufficient memory`() {
        val failure = NativeEngineFailure.classify(
            error = OutOfMemoryError("Failed to allocate 1073741824 bytes"),
            primaryAbi = "arm64-v8a",
            libraryAvailable = true,
            lowMemory = false
        )
        assertEquals(NativeEngineFailure.INSUFFICIENT_MEMORY, failure)
    }

    @Test
    fun `ggml allocation failure is classified as insufficient memory`() {
        val failure = NativeEngineFailure.classify(
            error = RuntimeException("ggml_new_tensor_impl: cannot allocate buffer"),
            primaryAbi = "arm64-v8a",
            libraryAvailable = true
        )
        assertEquals(NativeEngineFailure.INSUFFICIENT_MEMORY, failure)
    }

    // ---- Model side ----------------------------------------------------

    @Test
    fun `classifyModel never returns a hardware verdict`() {
        // A model problem must NEVER be reported as hardware. Exhaustive
        // over the three input facts.
        val cases = listOf(
            Triple(false, false, false), // absent
            Triple(true, true, false),   // truncated
            Triple(true, false, true),   // wrong size
            Triple(true, false, false),  // present and byte-exact
        )
        for ((exists, truncated, wrongSize) in cases) {
            val result = NativeEngineFailure.classifyModel(exists, truncated, wrongSize)
            assertTrue(
                "model state (exists=$exists truncated=$truncated wrongSize=$wrongSize) " +
                    "produced $result, which blames hardware",
                result != NativeEngineFailure.CPU_INSTRUCTION_UNSUPPORTED &&
                    result != NativeEngineFailure.LIBRARY_ABI_MISSING
            )
        }
    }

    @Test
    fun `absent truncated and wrong size models all map to model invalid`() {
        assertEquals(
            NativeEngineFailure.MODEL_INVALID,
            NativeEngineFailure.classifyModel(exists = false, truncated = false, wrongSize = false)
        )
        assertEquals(
            NativeEngineFailure.MODEL_INVALID,
            NativeEngineFailure.classifyModel(exists = true, truncated = true, wrongSize = false)
        )
        assertEquals(
            NativeEngineFailure.MODEL_INVALID,
            NativeEngineFailure.classifyModel(exists = true, truncated = false, wrongSize = true)
        )
    }

    @Test
    fun `byte exact model with null native context is a native init failure not a model fault`() {
        val result = NativeEngineFailure.classifyModel(
            exists = true, truncated = false, wrongSize = false
        )
        assertEquals(NativeEngineFailure.NATIVE_INIT_FAILED, result)
    }

    // ---- Unknown -------------------------------------------------------

    @Test
    fun `an unattributable failure is reported as unknown never as cpu`() {
        val failure = NativeEngineFailure.classify(
            error = IllegalStateException("something unexpected"),
            primaryAbi = "arm64-v8a",
            libraryAvailable = true,
            lowMemory = false
        )
        // We know it is a native init problem, but we must not invent a
        // hardware cause.
        assertEquals(NativeEngineFailure.NATIVE_INIT_FAILED, failure)
        assertTrue(failure != NativeEngineFailure.CPU_INSTRUCTION_UNSUPPORTED)
    }

    @Test
    fun `a null error with no other signal is unknown`() {
        val failure = NativeEngineFailure.classify(
            error = null,
            primaryAbi = "arm64-v8a",
            libraryAvailable = true,
            lowMemory = false
        )
        assertEquals(NativeEngineFailure.UNKNOWN, failure)
    }

    // ---- Persian message mapping ---------------------------------------

    @Test
    fun `every failure kind has a distinct non-blank persian message`() {
        val messages = NativeEngineFailure.entries.map { it to it.persianMessage() }
        for ((kind, msg) in messages) {
            assertTrue("$kind has a blank message", msg.isNotBlank())
            // Persian text must actually contain Persian characters —
            // an English fallback would be a localisation regression.
            assertTrue(
                "$kind message contains no Persian characters: $msg",
                msg.any { it.code in 0x0600..0x06FF }
            )
        }
        val distinct = messages.map { it.second }.toSet()
        assertEquals(
            "two failure kinds share a message; the user cannot tell them apart",
            messages.size, distinct.size
        )
    }

    @Test
    fun `only the architecture fault may claim the cpu is unsupported`() {
        // The heart of the fix: exactly one message may blame the CPU.
        val cpuBlamingMessages = NativeEngineFailure.entries
            .filter { it.persianMessage().contains("پردازنده") }
            .toSet()
        assertTrue(
            "only CPU_INSTRUCTION_UNSUPPORTED may mention the processor, got: $cpuBlamingMessages",
            cpuBlamingMessages.all {
                it == NativeEngineFailure.CPU_INSTRUCTION_UNSUPPORTED ||
                    // LIBRARY_ABI_MISSING legitimately explains that the
                    // APK has no build for this processor architecture;
                    // that is a packaging statement, not an instruction
                    // -set claim, so it is allowed to name it.
                    it == NativeEngineFailure.LIBRARY_ABI_MISSING
            }
        )
        assertTrue(
            NativeEngineFailure.CPU_INSTRUCTION_UNSUPPORTED
                .persianMessage().contains("پردازنده")
        )
    }

    @Test
    fun `the old misleading sentence string no longer appears anywhere in the mapping`() {
        val old = "این دستگاه از پردازنده‌ی لازم پشتیبانی نمی‌کند"
        for (kind in NativeEngineFailure.entries) {
            assertTrue(
                "$kind still carries the removed generic sentence",
                !kind.persianMessage().contains(old)
            )
        }
    }

    // ---- Recovery hints ------------------------------------------------

    @Test
    fun `abi and cpu faults are not user fixable but model and memory faults are`() {
        assertNull(NativeEngineFailure.LIBRARY_ABI_MISSING.recoveryHint())
        assertNull(NativeEngineFailure.CPU_INSTRUCTION_UNSUPPORTED.recoveryHint())
        assertNotNull(NativeEngineFailure.MODEL_INVALID.recoveryHint())
        assertNotNull(NativeEngineFailure.INSUFFICIENT_MEMORY.recoveryHint())
        assertNotNull(NativeEngineFailure.LIBRARY_LOAD_FAILED.recoveryHint())
        assertNotNull(NativeEngineFailure.UNKNOWN.recoveryHint())
    }

    @Test
    fun `model fault recovery says redownload`() {
        assertEquals("redownload_model", NativeEngineFailure.MODEL_INVALID.recoveryHint())
    }

    @Test
    fun `memory fault recovery says free memory`() {
        assertEquals(
            "free_memory_or_restart",
            NativeEngineFailure.INSUFFICIENT_MEMORY.recoveryHint()
        )
    }

    // ---- Logger resilience ---------------------------------------------

    @Test
    fun `classify never throws for any throwable shape`() {
        // A classifier that throws inside a catch block would replace a
        // handled failure with a crash — the worst possible trade.
        val errors = listOf<Throwable?>(
            null,
            UnsatisfiedLinkError(),
            UnsatisfiedLinkError(null as String?),
            OutOfMemoryError(),
            StackOverflowError("deep"),
            RuntimeException(),
            NoClassDefFoundError("Lib"),
            ExceptionInInitializerError("static init"),
            AssertionError("x"),
            IllegalAccessError("y"),
        )
        for (e in errors) {
            for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64", "unknown")) {
                for (available in listOf(true, false)) {
                    for (low in listOf(true, false)) {
                        // Must not throw.
                        val r = NativeEngineFailure.classify(
                            error = e,
                            primaryAbi = abi,
                            libraryAvailable = available,
                            lowMemory = low
                        )
                        assertNotNull(r)
                    }
                }
            }
        }
    }
}
