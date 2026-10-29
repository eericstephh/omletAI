package com.aicreatorstudio.app.ui.screens.home

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-20 — guards the Telegram channel button in the home header.
 *
 * The button must:
 *  - sit in the header, next to the other top-of-screen controls;
 *  - use the NON-auto-mirrored paper plane, because a brand mark must
 *    not flip when the app is in Persian;
 *  - be tinted with Telegram's brand blue so it is recognisable;
 *  - open the channel through [com.aicreatorstudio.app.util.TelegramChannel]
 *    rather than a hand-rolled intent.
 */
class HomeHeaderTelegramWiringTest {

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("settings.gradle.kts not found above ${File(".").absolutePath}")
    }

    private fun source(relative: String): String {
        val f = File(repoRoot(), relative)
        assertTrue("missing file: $f", f.isFile)
        return f.readText()
    }

    @Test
    fun `the header carries a telegram button`() {
        val src = source(
            "app/src/main/java/com/aicreatorstudio/app/ui/screens/home/HomeScreen.kt"
        )
        assertTrue(
            "the header must render the Telegram button",
            src.contains("TelegramIconButton(onClick = onTelegramClick)")
        )
        assertTrue(
            "the button must be a real composable of its own",
            src.contains("private fun TelegramIconButton(onClick: () -> Unit)")
        )
    }

    @Test
    fun `the button uses the non mirrored paper plane`() {
        val src = source(
            "app/src/main/java/com/aicreatorstudio/app/ui/screens/home/HomeScreen.kt"
        )
        assertTrue(
            "the paper plane must NOT be the auto-mirrored variant — a brand " +
                "mark must not flip in Persian",
            src.contains("Icons.Filled.Send")
        )
        assertTrue(
            "the auto-mirrored variant must not be used for the Telegram mark",
            !src.contains("Icons.AutoMirrored.Filled.Send")
        )
    }

    @Test
    fun `the button opens the channel through the shared helper`() {
        val src = source(
            "app/src/main/java/com/aicreatorstudio/app/ui/screens/home/HomeScreen.kt"
        )
        assertTrue(
            "the click must go through TelegramChannel.open — the link logic " +
                "(app scheme first, https fallback) lives there",
            src.contains("TelegramChannel.open(context)")
        )
    }

    @Test
    fun `the brand blue is the telegram blue`() {
        val src = source(
            "app/src/main/java/com/aicreatorstudio/app/ui/screens/home/HomeScreen.kt"
        )
        assertTrue(
            "the disc must use Telegram's brand blue #229ED9",
            src.contains("TelegramBrandBlue = Color(0xFF229ED9)")
        )
    }
}
