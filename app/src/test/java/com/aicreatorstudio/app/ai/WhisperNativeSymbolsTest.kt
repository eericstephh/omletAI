package com.aicreatorstudio.app.ai

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard for the v1.7.6 whisper.cpp JNI bridge.
 *
 * The Kotlin side of the JNI contract is [WhisperNative] — it
 * declares four `external` functions whose C++ implementations
 * live in `app/src/main/cpp/whisper-jni.cpp`. If a refactor
 * accidentally renames or removes one of those Kotlin declarations
 * without updating the JNI, the app crashes at first launch with
 * an `UnsatisfiedLinkError`. The crash is loud in production but
 * the JVM unit test path can't reproduce it because the test
 * JVM doesn't have Android's JNI loader.
 *
 * This test does the next best thing: it scans the compiled
 * `libwhisper.so` (per ABI) for the four expected JNI symbol
 * names and asserts each one is exported. That catches the most
 * common refactor mistake (renaming a Kotlin native method
 * without updating the C++ side) before it ships.
 *
 * The scan is done via raw `File.readBytes()` + a substring
 * search because the project doesn't depend on a binary-parser
 * library and the .so files are small enough (≤ 1.2 MB stripped)
 * to hold in memory.
 */
class WhisperNativeSymbolsTest {

    /**
     * ABI directories under `app/build/intermediates/stripped_native_libs/`
     * that the AGP merges into the APK. The directory is only
     * populated after `:app:assembleDebug` (or release) has run,
     * so this test is skipped (not failed) when the libs are
     * missing — the test classpath is built before the native
     * libs.
     */
    private val expectedSymbols = listOf(
        "Java_com_aicreatorstudio_app_ai_WhisperNative_nativeInit",
        "Java_com_aicreatorstudio_app_ai_WhisperNative_nativeFree",
        "Java_com_aicreatorstudio_app_ai_WhisperNative_nativeFullWithWordTimestamps",
        "Java_com_aicreatorstudio_app_ai_WhisperNative_nativeSelfTest"
    )

    /**
     * The ABIs `libwhisper.so` is packaged for.
     *
     * ## 2026-10-11 ABI AUDIT CORRECTION
     *
     * This list used to be `arm64-v8a, armeabi-v7a, x86_64`, which
     * made the test fail on every run: `app/build.gradle.kts` sets
     * `abiFilters += listOf("arm64-v8a")` and the built APK contains
     * native libraries only under `lib/arm64-v8a/`. Asserting three
     * ABIs against a one-ABI build was a permanently red test that
     * hid real regressions behind expected noise.
     *
     * The single entry below matches what actually ships. If an ABI
     * is ever added to `abiFilters`, add it here too — that is the
     * point of keeping the two greppable.
     */
    private val abis = listOf("arm64-v8a")

    @Test
    fun `libwhisper so exports all four JNI symbols per ABI`() {
        val projectRoot = locateProjectRoot()
        val libDir = File(
            projectRoot,
            "app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib"
        )
        assumeTrue(
            "libwhisper.so not built yet — run :app:assembleDebug first. Looked at: $libDir",
            libDir.isDirectory
        )
        for (abi in abis) {
            val so = File(libDir, "$abi/libwhisper.so")
            assumeTrue("Missing native lib for $abi at $so", so.isFile)
            val bytes = so.readBytes()
            val asAscii = String(bytes, Charsets.ISO_8859_1)
            for (symbol in expectedSymbols) {
                assertTrue(
                    "Expected JNI symbol '$symbol' not found in $so. " +
                        "Did the Kotlin declaration in WhisperNative drift from " +
                        "the C++ side in whisper-jni.cpp?",
                    asAscii.contains(symbol)
                )
            }
        }
    }

    @Test
    fun `libwhisper so is built for every supported ABI`() {
        val projectRoot = locateProjectRoot()
        val libDir = File(
            projectRoot,
            "app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib"
        )
        assumeTrue(
            "Native libs not built yet — run :app:assembleDebug first. Looked at: $libDir",
            libDir.isDirectory
        )
        for (abi in abis) {
            val so = File(libDir, "$abi/libwhisper.so")
            assertTrue("Missing $abi build of libwhisper.so at $so", so.isFile)
            assertTrue(
                "$abi libwhisper.so is suspiciously small (${so.length()} B) — " +
                    "the JNI bridge did not link correctly",
                so.length() > 100_000L
            )
        }
    }

    @Test
    fun `bundled whisper model has the GGML magic header when present`() {
        // ## 2026-10-11 CORRECTION — the bundled-model assertion
        //
        // This test used to hard-code the path
        // `ai-models/whisper-ggml-medium/ggml-medium.bin` and assert
        // its presence. That is doubly stale:
        //
        //   1. `whisper-medium` no longer exists in the catalog. The
        //      vetting in `WhisperModelSpec` retains only `SMALL` and
        //      `CLOUD_API`; the 2026-10-11 audit confirmed
        //      `whisper-ggml-medium` was removed.
        //   2. `whisper-small` is declared `bundled: false` — it is
        //      downloaded on first use (487,601,967 B), so it is not
        //      in the APK assets at all.
        //
        // There is therefore **no bundled Whisper model to assert**
        // on, and `assumeTrue` correctly skips. The test is kept
        // because the GGML magic-byte check is still valuable the day
        // a model *is* bundled again; it now discovers whatever
        // `*.bin` is actually present instead of naming a dead file.
        val projectRoot = locateProjectRoot()
        val processedAssets = File(
            projectRoot,
            "app/build/intermediates/assets/debug/mergeDebugAssets"
        )
        val aiModelsDir = File(processedAssets, "ai-models")
        val model = aiModelsDir.walkTopDown()
            .maxDepth(2)
            .firstOrNull { it.isFile && it.name.endsWith(".bin") }
        assumeTrue(
            "No bundled Whisper model in the APK assets — expected for " +
                "the current catalog (whisper-small is downloaded, not " +
                "bundled). Looked under: $aiModelsDir",
            model != null
        )
        // The GGML file format starts with the literal bytes
        // "lmgg" (little-endian "ggml") followed by a uint32
        // version. A real ggml file starts with that magic; a
        // zero-byte stub or an unrelated file would not.
        val header = model!!.inputStream().use { it.readNBytes(4) }
        assertTrue(
            "${model.name} does not start with the GGML magic. " +
                "Got bytes: ${header.joinToString("") { "%02x".format(it) }}",
            header.size == 4 &&
                header[0] == 'l'.code.toByte() &&
                header[1] == 'm'.code.toByte() &&
                header[2] == 'g'.code.toByte() &&
                header[3] == 'g'.code.toByte()
        )
    }

    /**
     * Walks up from the current working directory until a
     * `settings.gradle.kts` is found. The JVM test runs from
     * the project root but defensive in case a CI runner cd's
     * into a subdirectory.
     */
    private fun locateProjectRoot(): File {
        var dir: File? = File(".").absoluteFile
        repeat(8) {
            if (dir?.resolve("settings.gradle.kts")?.isFile == true) return dir!!
            dir = dir?.parentFile
        }
        // Fall back to cwd if the search didn't find settings.
        return File(".").absoluteFile
    }
}
