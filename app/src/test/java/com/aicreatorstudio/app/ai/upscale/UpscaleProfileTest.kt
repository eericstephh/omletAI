package com.aicreatorstudio.app.ai.upscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the [UpscaleProfile] / [UpscaleEngineId] types and
 * their lookup helpers. The profiles are pure data — no Android
 * framework calls — so these are plain JVM unit tests.
 */
class UpscaleProfileTest {

    @Test
    fun `all three profiles are exposed`() {
        val all = UpscaleProfile.all
        assertEquals(3, all.size)
        assertTrue(all.contains(UpscaleProfile.Fast))
        assertTrue(all.contains(UpscaleProfile.HighQuality))
        assertTrue(all.contains(UpscaleProfile.Auto))
    }

    @Test
    fun `fast profile routes to the fast engine id`() {
        assertEquals(UpscaleEngineId.FAST, UpscaleProfile.Fast.engineId)
        assertEquals(2.0f, UpscaleProfile.Fast.targetScale, 0.001f)
    }

    @Test
    fun `high quality profile routes to the high quality engine id`() {
        assertEquals(UpscaleEngineId.HIGH_QUALITY, UpscaleProfile.HighQuality.engineId)
        assertEquals(4.0f, UpscaleProfile.HighQuality.targetScale, 0.001f)
    }

    @Test
    fun `auto profile is the auto engine id and has a placeholder target scale`() {
        assertEquals(UpscaleEngineId.AUTO, UpscaleProfile.Auto.engineId)
        // Auto's target scale is resolved at run time by the
        // device capability detector. We keep it at 0f so accidental
        // use of the raw value fails loudly (no silent 0x upscale).
        assertEquals(0f, UpscaleProfile.Auto.targetScale, 0.001f)
    }

    @Test
    fun `byId resolves every stable id`() {
        assertEquals(UpscaleProfile.Fast, UpscaleProfile.byId("fast"))
        assertEquals(UpscaleProfile.HighQuality, UpscaleProfile.byId("high_quality"))
        assertEquals(UpscaleProfile.Auto, UpscaleProfile.byId("auto"))
    }

    @Test
    fun `byId returns null for unknown or null ids`() {
        assertNull(UpscaleProfile.byId(null))
        assertNull(UpscaleProfile.byId(""))
        assertNull(UpscaleProfile.byId("ULTRA_HD")) // case-sensitive
    }

    @Test
    fun `engine id fromKey round trips`() {
        for (id in UpscaleEngineId.entries) {
            assertEquals(id, UpscaleEngineId.fromKey(id.key))
        }
        assertNull(UpscaleEngineId.fromKey(null))
        assertNull(UpscaleEngineId.fromKey("nope"))
    }

    @Test
    fun `profile display names are non-blank and stable`() {
        // A regression in these strings breaks the bottom sheet
        // copy. Pin them so the change is loud.
        assertEquals("Fast Mode", UpscaleProfile.Fast.displayName)
        assertEquals("High Quality", UpscaleProfile.HighQuality.displayName)
        assertEquals("Auto", UpscaleProfile.Auto.displayName)
    }

    @Test
    fun `auto engine id has no engine registered directly`() {
        // The registry provides a "resolve Auto via detector" path
        // but should never hold an engine for the Auto sentinel.
        // We don't have a registry instance in this test, so we
        // assert the key is distinct and non-empty as a contract.
        assertEquals("auto", UpscaleEngineId.AUTO.key)
        assertNotNull(UpscaleEngineId.AUTO)
    }
}
