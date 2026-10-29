package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-24 — the per-model NETWORK notice that the editor shows above
 * the bottom tool dock.
 *
 * Regression this pins: [WhisperModelSpec.CLOUD_API] uploads the audio
 * to Groq's API, which an Iranian IP cannot reach, so the user has to
 * enable their sanctions-bypass route BEFORE Generate is attempted.
 * Previously the model was silently selectable and the failure only
 * surfaced later, as an opaque network error.
 *
 * The wording is user-visible and was specified verbatim, so it is
 * asserted EXACTLY: a silent reword is a product change, not a
 * refactor. Pure JVM — [WhisperModelSpec] has no Android dependencies.
 */
class WhisperModelNetworkNoticeTest {

    @Test
    fun `cloud model carries the exact sanctions-bypass notice`() {
        assertEquals(
            "برای استفاده از این مدل باید تحریم شکن روشن کنید",
            WhisperModelSpec.CLOUD_API.networkNoticeFa
        )
    }

    @Test
    fun `the notice is single-sourced from the shared constant`() {
        // The banner reads the spec property, never the literal, so the
        // picker, the banner and this test cannot drift apart.
        assertEquals(
            CLOUD_MODEL_NETWORK_NOTICE_FA,
            WhisperModelSpec.CLOUD_API.networkNoticeFa
        )
    }

    @Test
    fun `on-device models carry no notice`() {
        // Only the model that leaves the device can depend on the
        // user's network route. Warning about a VPN on an on-device
        // model would be a lie, and would train the user to ignore the
        // banner on the model that really needs it.
        for (spec in WhisperModelSpec.entries) {
            if (spec.isCloud) continue
            assertNull(
                "${spec.name} runs on-device and must not ask for a VPN",
                spec.networkNoticeFa
            )
        }
    }

    @Test
    fun `every cloud-hosted model carries a notice`() {
        // Forward guard: adding another cloud model must not ship one
        // that silently cannot connect.
        for (spec in WhisperModelSpec.entries) {
            if (!spec.isCloud) continue
            assertTrue(
                "${spec.name} is cloud-hosted and must warn about the network",
                !spec.networkNoticeFa.isNullOrBlank()
            )
        }
    }

    @Test
    fun `the notice still names the tool and the action in Persian`() {
        // Guards against a truncated or mojibake string. Checked as
        // separate words so the assertion does not depend on whether
        // "تحریم شکن" is written with a space or a ZWNJ.
        val notice = requireNotNull(WhisperModelSpec.CLOUD_API.networkNoticeFa)
        assertTrue("must name the sanctions-bypass tool", notice.contains("تحریم"))
        assertTrue("must name the sanctions-bypass tool", notice.contains("شکن"))
        assertTrue("must tell the user to turn it on", notice.contains("روشن"))
        assertTrue(
            "must read as Persian, not mojibake",
            notice.any { it in '\u0600'..'\u06FF' }
        )
    }
}
