package com.aicreatorstudio.app.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the AI tool registry wiring.
 *
 * ## The bug this pins (2026-10-15)
 *
 * A device report ended with
 * `ai_tool/unknown_tool: no engine registered for toolId=motiontrack`
 * followed by `run_started` for a *different* tool. The Motion
 * Tracking feature shipped its engine
 * (`MotionTrackingEngine`, `TOOL_ID = "motiontrack"`) and the toolbar
 * wired its chip to `onRunAiTool("motiontrack", …)`, but the engine
 * was never added to the Hilt `List<AiToolEngine>` in
 * [AppModule.provideAiToolEngines]. The registry resolves engines
 * **by id from that list**, so the lookup returned null and the run
 * failed as "unknown tool" before doing any work — a wiring gap no
 * compiler or existing test could see, because the class existed and
 * the call site was correct.
 *
 * ## What is testable here
 *
 * The provider is a Hilt `@Provides` method, so a JVM test cannot
 * execute it. The invariant it must hold is textual and precise:
 * every tool id the editor toolbar runs must have its engine class
 * named in the provider, with the new engine in the list expression.
 * Comments are stripped before every assertion, so a KDoc that
 * merely *mentions* an engine cannot satisfy a guard.
 */
class RegistryWiringTest {

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "settings.gradle.kts not found above ${File(".").absolutePath}"
        )
    }

    private fun read(relative: String): String {
        val f = File(repoRoot(), relative)
        assertTrue("$relative missing at $f", f.isFile)
        return f.readText()
    }

    /** Strips `//` and block comments, then returns the remaining code. */
    private fun codeLinesOnly(source: String): String {
        val blockEnd = "*" + "/"
        val out = StringBuilder()
        var inBlock = false
        for (raw in source.lineSequence()) {
            val line = raw
            val result = StringBuilder()
            var i = 0
            while (i < line.length) {
                if (inBlock) {
                    val end = line.indexOf(blockEnd, i)
                    if (end < 0) {
                        i = line.length
                    } else {
                        inBlock = false
                        i = end + blockEnd.length
                    }
                } else {
                    val lineComment = line.indexOf("//", i)
                    val blockStart = line.indexOf("/*", i)
                    val next = when {
                        lineComment >= 0 && (blockStart < 0 || lineComment < blockStart) -> lineComment
                        blockStart >= 0 -> blockStart
                        else -> -1
                    }
                    if (next < 0) {
                        result.append(line.substring(i))
                        i = line.length
                    } else if (next == lineComment) {
                        i = line.length
                    } else {
                        result.append(line.substring(i, next))
                        inBlock = true
                        i = next + 2
                    }
                }
            }
            out.append(result).append('\n')
        }
        return out.toString()
    }

    private val appModulePath =
        "app/src/main/java/com/aicreatorstudio/app/di/AppModule.kt"
    private val editorScreenPath =
        "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/EditorScreen.kt"

    private fun appModuleCode(): String = codeLinesOnly(read(appModulePath))

    /**
     * The exact regression: `MotionTrackingEngine` must be a
     * constructor parameter of the provider AND appear in the list
     * it returns. Being injected elsewhere is not enough — the
     * registry only sees what the provider lists.
     */
    @Test
    fun `MotionTrackingEngine is registered in the AiToolRegistry provider`() {
        val code = appModuleCode()
        assertTrue(
            "provideAiToolEngines must take the motion-tracking engine " +
                "as a parameter (added 2026-10-15; without it the toolbar " +
                "chip fails as unknown_tool)",
            code.contains("motionTracking: MotionTrackingEngine")
        )
        assertTrue(
            "the provider's returned list must include the motion-tracking engine",
            code.contains("listOf(matting, autoFrame, voiceClean, ocr, motionTracking)")
        )
        assertTrue(
            "AppModule must import MotionTrackingEngine",
            code.contains("import com.aicreatorstudio.app.ai.engines.MotionTrackingEngine")
        )
    }

    /**
     * The general form of the same invariant: every tool id the
     * editor toolbar runs must be served by an engine that the
     * provider registers. A new chip without a registration fails
     * the same silent way, so this test must fail first.
     */
    @Test
    fun `every toolbar tool id has a registered engine`() {
        val editorCode = codeLinesOnly(read(editorScreenPath))
        val toolIds = Regex("""onRunAiTool\("([a-z]+)"""").findAll(editorCode)
            .map { it.groupValues[1] }
            .toSet()
        assertTrue(
            "no onRunAiTool call sites found — the toolbar shape changed",
            toolIds.isNotEmpty()
        )

        val moduleCode = appModuleCode()
        val engineClassById = mapOf(
            "matting" to "MediaPipeMattingEngine",
            "autoframe" to "SaliencyTrackerEngine",
            "voiceclean" to "RnnoiseVoiceCleanEngine",
            "ocr" to "TesseractOcrEngine",
            "motiontrack" to "MotionTrackingEngine"
        )
        toolIds.forEach { id ->
            val engineClass = engineClassById[id]
            assertTrue(
                "the toolbar runs toolId=\"$id\" but no engine is known to " +
                    "serve it — map it in $appModulePath and provide it",
                engineClass != null
            )
            assertTrue(
                "toolId=\"$id\" needs $engineClass in the AiToolRegistry " +
                    "provider, otherwise runAiTool logs unknown_tool",
                moduleCode.contains(engineClass!!)
            )
        }
    }

    /**
     * The provider's list shape must stay a list of the five
     * registered engines — dropping one back out (or reordering
     * silently) would resurrect the same class of failure.
     */
    @Test
    fun `the provider registers exactly the five tool engines`() {
        val code = appModuleCode()
        assertEquals(
            "the AiToolEngine list must stay exactly these five engines",
            "listOf(matting, autoFrame, voiceClean, ocr, motionTracking)",
            Regex("""listOf\(\s*matting[^)]*\)""").find(code)?.value
        )
    }
}
