package com.aicreatorstudio.app.ai.subtitle.corrector

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-11 — architectural guard: the offline correction path.
 *
 * The brief's requirement is not "we did not add a network call
 * today" but "this component must not be able to make one". A grep
 * executed once by hand is not a guarantee; this test re-checks the
 * source on every build, so a future contribution that adds
 * `OkHttpClient` or `URL(...)` to the correction layer fails CI.
 *
 * It also pins the *dependency* claim: no LLM, no model download, no
 * cloud client may be referenced from these files.
 */
class OfflineCorrectionGuardTest {

    private val correctionSources = listOf(
        "ai/subtitle/PersianNormalizer.kt",
        "ai/subtitle/SubtitlePostProcessor.kt",
        "ai/subtitle/corrector/TranscriptCorrector.kt",
        "ai/subtitle/corrector/HomophonePolicy.kt",
        "ai/subtitle/corrector/PersianConfusables.kt",
        "ai/subtitle/corrector/ContextValidator.kt",
        "ai/subtitle/corrector/CorrectionCandidateGenerator.kt",
        "ai/subtitle/corrector/SuspiciousWordDetector.kt",
        "ai/subtitle/corrector/PersianLexicon.kt"
    )

    private val forbidden = listOf(
        // Networking
        "okhttp", "OkHttpClient", "retrofit", "Retrofit",
        "HttpURLConnection", "URLConnection", "InetAddress",
        "java.net.Socket", "openConnection", "HttpClient",
        "Volley", "WebView",
        // Cloud / remote model clients
        "groq", "Groq", "openai", "OpenAI", "anthropic", "Anthropic",
        "firebase", "Firebase", "supabase", "Supabase",
        // On-device LLM / runtime model download
        "llama", "Llama", "tflite", "TensorFlowLite", "onnxruntime",
        "ModelDownloader", "downloadModel", "MediaPipe",
        "translator", "Translator"
    )

    private fun sourceRoot(): File {
        // Tests run with CWD = module (app/) or project root; probe both.
        val candidates = listOf(
            File("src/main/java/com/aicreatorstudio/app"),
            File("app/src/main/java/com/aicreatorstudio/app")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("could not locate main source root from ${File(".").absolutePath}")
    }

    @Test
    fun `correction sources contain no network or cloud references`() {
        val root = sourceRoot()
        val violations = ArrayList<String>()
        for (rel in correctionSources) {
            val f = File(root, rel)
            assertTrue("expected source file to exist: ${f.absolutePath}", f.isFile)
            val text = f.readText()
            for (token in forbidden) {
                if (text.contains(token)) {
                    violations.add("$rel contains '$token'")
                }
            }
        }
        assertTrue(
            "offline guarantee broken:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `correction sources import nothing outside the local and stdlib scope`() {
        val root = sourceRoot()
        val allowedPrefixes = listOf(
            "com.aicreatorstudio.app",
            "kotlin.",
            "kotlinx.coroutines",
            "java.util",
            "java.io.File",
            "org.json",
            "java.lang",
            "java.util.UUID"
        )
        val violations = ArrayList<String>()
        for (rel in correctionSources) {
            val f = File(root, rel)
            for (line in f.readLines()) {
                val t = line.trim()
                if (!t.startsWith("import ")) continue
                val imp = t.removePrefix("import ").removeSuffix(";").trim()
                if (imp.startsWith("kotlin.") || imp.startsWith("java.util") ||
                    imp.startsWith("com.aicreatorstudio")
                ) {
                    continue
                }
                if (allowedPrefixes.none { imp.startsWith(it) }) {
                    violations.add("$rel imports '$imp'")
                }
            }
        }
        assertTrue(
            "unexpected dependency in the offline layer:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `no runtime model download is possible from the corrector`() {
        val root = sourceRoot()
        val downloadTokens = listOf(
            "download", "Download", "fetch(", "Fetch(",
            "assets.open", "context.assets", "model.bin", "modelPath"
        )
        val violations = ArrayList<String>()
        for (rel in correctionSources) {
            val f = File(root, rel)
            val text = f.readText()
            for (t in downloadTokens) {
                if (text.contains(t)) violations.add("$rel contains '$t'")
            }
        }
        assertTrue(
            "correction layer must not reference download machinery:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}
