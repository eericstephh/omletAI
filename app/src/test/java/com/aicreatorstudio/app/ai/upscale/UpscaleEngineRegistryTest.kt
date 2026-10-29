package com.aicreatorstudio.app.ai.upscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [UpscaleEngineRegistry] (via its test-friendly base
 * class [UpscaleEngineRegistryBase]). The registry's job is to
 * route a profile to the right engine and to resolve Auto at
 * lookup time — both of which are easy to exercise with fakes.
 *
 * The base class accepts a plain `(UpscaleProfile) -> UpscaleProfile`
 * lambda for the Auto resolver, so the tests don't need an Android
 * Context.
 */
class UpscaleEngineRegistryTest {

    /**
     * Minimal fake [UpscaleEngine] for unit tests. We deliberately
     * do not extend the production engine classes because those
     * require an Android `Context`.
     */
    private class FakeEngine(
        override val id: String,
        override val displayName: String = "fake-$id",
        override val isAvailable: Boolean = true
    ) : UpscaleEngine {
        // modelStatusText has a default on the interface — tests
        // don't read it, so leaving the default is fine.
        override fun start(
            project: com.aicreatorstudio.app.model.VideoProject,
            profile: UpscaleProfile,
            config: com.aicreatorstudio.app.export.ExportConfig
        ): UpscaleJob = error("not used in registry tests")
    }

    /**
     * Helper: build a registry with the two well-known fake
     * engines. Tests override the auto resolver case-by-case.
     */
    private fun registryWith(
        engines: List<UpscaleEngine>,
        autoResolver: (UpscaleProfile) -> UpscaleProfile = { UpscaleProfile.Fast }
    ): UpscaleEngineRegistryBase = UpscaleEngineRegistryBase(engines, autoResolver)

    @Test
    fun `byEngineId returns the matching engine`() {
        val fast = FakeEngine(UpscaleEngineId.FAST.key)
        val hq = FakeEngine(UpscaleEngineId.HIGH_QUALITY.key)
        val reg = registryWith(listOf(fast, hq))
        assertSame(fast, reg.byEngineId(UpscaleEngineId.FAST))
        assertSame(hq, reg.byEngineId(UpscaleEngineId.HIGH_QUALITY))
    }

    @Test
    fun `byEngineId returns null for the auto sentinel`() {
        val fast = FakeEngine(UpscaleEngineId.FAST.key)
        val reg = registryWith(listOf(fast))
        // Auto is intentionally not registered — callers should go
        // through resolveForProfile instead.
        assertNull(reg.byEngineId(UpscaleEngineId.AUTO))
    }

    @Test
    fun `byEngineId returns null for unknown ids`() {
        val reg = registryWith(emptyList())
        assertNull(reg.byEngineId(UpscaleEngineId.FAST))
    }

    @Test
    fun `resolveForProfile routes Fast and HighQuality to the matching engine`() {
        val fast = FakeEngine(UpscaleEngineId.FAST.key)
        val hq = FakeEngine(UpscaleEngineId.HIGH_QUALITY.key)
        val reg = registryWith(listOf(fast, hq))
        assertSame(fast, reg.resolveForProfile(UpscaleProfile.Fast))
        assertSame(hq, reg.resolveForProfile(UpscaleProfile.HighQuality))
    }

    @Test
    fun `resolveForProfile routes Auto via the autoResolver lambda`() {
        val fast = FakeEngine(UpscaleEngineId.FAST.key)
        val hq = FakeEngine(UpscaleEngineId.HIGH_QUALITY.key)
        val reg = registryWith(
            engines = listOf(fast, hq),
            autoResolver = { UpscaleProfile.HighQuality }
        )
        assertSame(hq, reg.resolveForProfile(UpscaleProfile.Auto))
    }

    @Test
    fun `resolveForProfile falls back to the autoResolver when the resolved profile has no engine`() {
        // Only Fast is registered; Auto resolves to HighQuality;
        // the result must be null (no engine available for the
        // resolved profile) rather than the wrong engine.
        val fast = FakeEngine(UpscaleEngineId.FAST.key)
        val reg = registryWith(
            engines = listOf(fast),
            autoResolver = { UpscaleProfile.HighQuality }
        )
        assertNull(reg.resolveForProfile(UpscaleProfile.Auto))
    }

    @Test
    fun `all returns the registered engines in priority order`() {
        val fast = FakeEngine(UpscaleEngineId.FAST.key)
        val hq = FakeEngine(UpscaleEngineId.HIGH_QUALITY.key)
        val reg = registryWith(listOf(fast, hq))
        assertEquals(listOf<UpscaleEngine>(fast, hq), reg.all)
    }

    @Test
    fun `resolveForProfile returns null when no engine is registered`() {
        val reg = registryWith(emptyList())
        assertNull(reg.resolveForProfile(UpscaleProfile.Fast))
    }

    @Test
    fun `registry does not mutate the input list`() {
        val fast = FakeEngine(UpscaleEngineId.FAST.key)
        val engines = mutableListOf<UpscaleEngine>(fast)
        UpscaleEngineRegistryBase(engines) { UpscaleProfile.Fast }
        assertEquals(1, engines.size)
        assertNotNull(engines.first())
    }

    @Test
    fun `autoResolver is invoked exactly once per Auto resolution`() {
        val fast = FakeEngine(UpscaleEngineId.FAST.key)
        var invocations = 0
        val reg = registryWith(
            engines = listOf(fast),
            autoResolver = {
                invocations += 1
                UpscaleProfile.Fast
            }
        )
        reg.resolveForProfile(UpscaleProfile.Auto)
        reg.resolveForProfile(UpscaleProfile.Auto)
        assertEquals(2, invocations)
    }

    @Test
    fun `the result of resolveForProfile is the engine for the resolved profile`() {
        val fast = FakeEngine(UpscaleEngineId.FAST.key)
        val reg = registryWith(
            engines = listOf(fast),
            autoResolver = { UpscaleProfile.Fast }
        )
        // Pin the assertion so a refactor that swaps the two
        // branches (autoResolver vs lookup) is caught immediately.
        val result = reg.resolveForProfile(UpscaleProfile.Auto)
        assertTrue(result === fast)
    }
}
