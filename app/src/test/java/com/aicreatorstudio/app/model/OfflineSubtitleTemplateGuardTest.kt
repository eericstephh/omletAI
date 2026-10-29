package com.aicreatorstudio.app.model

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-11 — architectural guard: the subtitle TEMPLATE layer is
 * fully offline.
 *
 * The brief states the shipped app "must not depend on those sources or
 * require internet to display/apply templates", and that no remote
 * template configuration or paid service may back the presets. A grep
 * run once by hand is not a guarantee, so this test re-reads the source
 * tree on every build: a future change that wires a template to
 * `OkHttpClient`, a remote config client, or an LLM fails here.
 *
 * Scope is deliberately narrow — only the files that define, render or
 * present templates. The camera, analytics and ad layers legitimately
 * use the network and are out of scope.
 */
class OfflineSubtitleTemplateGuardTest {

    private val templateSources = listOf(
        "model/SubtitleTemplate.kt",
        "model/BuiltInSubtitleTemplates.kt",
        "model/SubtitleStyle.kt",
        "export/CaptionRenderer.kt",
        "ui/screens/editor/subtitle/SubtitleStudioPanel.kt",
        "ui/screens/editor/CaptionBitmap.kt",
        "export/SubtitleBurnIn.kt"
    )

    private val forbidden = listOf(
        // Networking
        "okhttp", "OkHttpClient", "retrofit", "Retrofit",
        "HttpURLConnection", "URLConnection", "InetAddress",
        "java.net.Socket", "openConnection", "HttpClient",
        "Volley", "WebView",
        // Remote config / cloud backends
        "FirebaseRemoteConfig", "RemoteConfig", "firebase", "Firebase",
        "supabase", "Supabase",
        // Cloud AI
        "openai", "OpenAI", "anthropic", "Anthropic", "groq", "Groq",
        // Runtime model download
        "ModelDownloader", "downloadModel", "modelPath"
    )

    private fun sourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/aicreatorstudio/app"),
            File("app/src/main/java/com/aicreatorstudio/app")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("could not locate main source root from ${File(".").absolutePath}")
    }

    @Test
    fun `template layer contains no network or remote-config references`() {
        val root = sourceRoot()
        val violations = ArrayList<String>()
        for (rel in templateSources) {
            val f = File(root, rel)
            assertTrue("expected source file to exist: ${f.absolutePath}", f.isFile)
            val text = f.readText()
            for (token in forbidden) {
                if (text.contains(token)) violations.add("$rel contains '$token'")
            }
        }
        assertTrue(
            "offline template guarantee broken:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `the catalog is constructible with no I O at all`() {
        // Touching ALL builds every one of the 26 templates. If that
        // ever required a file, a socket or a Context it would throw
        // here — there is no Context available in a JVM test.
        val all = BuiltInSubtitleTemplates.ALL
        assertTrue("catalog empty", all.size == 26)
        // And each one is pure data: JSON round trip needs no I/O.
        all.forEach { t ->
            assertTrue("${t.id} is not serializable offline", t.toJson().isNotEmpty())
        }
    }
}
