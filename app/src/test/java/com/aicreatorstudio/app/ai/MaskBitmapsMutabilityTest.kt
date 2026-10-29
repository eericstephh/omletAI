package com.aicreatorstudio.app.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-29 — guards the mask-post-process mutability fix.
 *
 * ## The defect this pins
 *
 * `MaskBitmaps.applyPostProcess` wrote the processed alpha straight back
 * into the bitmap it was handed (`mask.setPixels(...)`). Its only caller
 * passes the result of `BitmapFactory.decodeFile(sidecarPng)`, and
 * **decoded bitmaps are immutable**, so any export with a non-default
 * expansion / feather / reverse died with
 *
 * ```
 * java.lang.IllegalStateException
 *   at android.graphics.Bitmap.setPixels
 *   at ...AiExportHooks$compositeMatting$2
 * ```
 *
 * surfaced to the user as the useless "Export failed at PREPARING: Video
 * processing failed". The no-op path (`isNoOp`) never touches the bitmap,
 * which is exactly why the defect survived: it only fires once a slider
 * is off zero.
 *
 * `Bitmap` cannot be exercised under this project's JVM stack
 * (`isReturnDefaultValues = true` makes `android.*` inert), so the
 * contract is asserted structurally against the real sources — the same
 * shape as `AiFrameAccuracyWiringTest`.
 *
 * ## Why the comments are stripped first
 *
 * The fixed file's KDoc quotes the *broken* call while explaining it. A
 * negative assertion against the raw text would fail on its own
 * documentation. The stripper below is a small state machine rather than
 * a line filter on purpose: a line-oriented version mis-handles a KDoc
 * that spans lines and silently eats the code after it.
 */
class MaskBitmapsMutabilityTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (!File(dir, "app/src/main/java").isDirectory) {
            dir = dir.parentFile ?: error("cannot locate the repository root")
        }
        return dir
    }

    private fun code(relativePath: String): String =
        stripComments(File(repoRoot(), relativePath).readText())

    /** Removes `//` line comments and `/* … */` block comments. */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else '\u0000'
            when {
                c == '/' && next == '/' -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i += 2
                }
                c == '"' -> {
                    // Skip string literals so a `//` inside one cannot
                    // start a bogus comment.
                    out.append(c)
                    i++
                    while (i < src.length && src[i] != '"') {
                        if (src[i] == '\\' && i + 1 < src.length) {
                            out.append(src[i]); i++
                        }
                        out.append(src[i]); i++
                    }
                    if (i < src.length) { out.append(src[i]); i++ }
                }
                else -> {
                    out.append(c); i++
                }
            }
        }
        return out.toString()
    }

    @Test
    fun `applyPostProcess never writes into the caller's bitmap`() {
        val src = code("app/src/main/java/com/aicreatorstudio/app/ai/MaskBitmaps.kt")
        assertFalse(
            "applyPostProcess must not call setPixels on its input: the caller passes " +
                "a BitmapFactory-decoded (immutable) sidecar PNG",
            src.contains("mask.setPixels(")
        )
        assertTrue(
            "the post-process must write into a mutable copy",
            src.contains("mask.copy(Bitmap.Config.ARGB_8888, true)")
        )
        assertTrue(
            "the copy must actually receive the pixels",
            src.contains("out.setPixels(")
        )
    }

    @Test
    fun `the export hook frees the decoded original once it is replaced`() {
        val src = code("app/src/main/java/com/aicreatorstudio/app/export/AiExportHooks.kt")
        assertTrue(
            "maskFor must recycle the decoded PNG after applyPostProcess returns a copy, " +
                "otherwise every distinct mask leaks a full-resolution bitmap",
            src.contains("if (processed !== decoded) decoded.recycle()")
        )
    }

    @Test
    fun `the mask short side override is actually wired through`() {
        // MaskBitmaps had a shortSideOverride it computed and never passed
        // on, so its KDoc promised resolution-independent scaling that the
        // code did not perform.
        val bitmaps = code("app/src/main/java/com/aicreatorstudio/app/ai/MaskBitmaps.kt")
        assertTrue(
            "MaskPostProcess.apply must receive the short side",
            bitmaps.contains("MaskPostProcess.apply(alpha, w, h, expansion, feather, reverse, shortSide)")
        )
        val post = code("app/src/main/java/com/aicreatorstudio/app/ai/MaskPostProcess.kt")
        assertTrue(
            "MaskPostProcess.apply must accept and honour shortSideOverride",
            post.contains("shortSideOverride: Int? = null") &&
                post.contains("val shortSide = shortSideOverride ?: minOf(w, h)")
        )
    }
}
