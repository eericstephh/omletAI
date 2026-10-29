package com.aicreatorstudio.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-20 — guards the Telegram channel link.
 *
 * The home screen's header carries a Telegram button. It has to open
 * the RIGHT channel, and it has to work on a device where the Telegram
 * app is installed as well as on one where it is not.
 */
class TelegramChannelTest {

    @Test
    fun `the channel url points at the right handle`() {
        assertEquals("https://t.me/AIerix", TelegramChannel.WEB_URL)
        assertEquals("AIerix", TelegramChannel.HANDLE)
    }

    @Test
    fun `the app scheme uses the same handle as the web url`() {
        assertEquals(
            "the two urls must address the same channel — a mismatch sends the " +
                "user to one channel in the app and another in the browser",
            "tg://resolve?domain=${TelegramChannel.HANDLE}",
            TelegramChannel.APP_URI
        )
        assertTrue(
            "the app scheme must carry the handle",
            TelegramChannel.APP_URI.contains(TelegramChannel.HANDLE)
        )
    }

    @Test
    fun `the web url is https so a browser can always open it`() {
        assertTrue(
            "the fallback url must be https",
            TelegramChannel.WEB_URL.startsWith("https://")
        )
        assertTrue(
            "the fallback url must be a t.me link",
            TelegramChannel.WEB_URL.startsWith("https://t.me/")
        )
    }
}
