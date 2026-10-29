package com.aicreatorstudio.app.ai.upscale

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hilt-provided lookup for the [UpscaleEngine]s that ship with the
 * AI Video Upscaler module. Mirrors the shape of
 * [com.aicreatorstudio.app.ai.AiToolRegistry]: a priority-ordered
 * list plus by-id and by-profile accessors.
 *
 * Engines are registered by Hilt in
 * [com.aicreatorstudio.app.di.UpscaleModule]. The list is immutable
 * after construction. Resolution rules:
 *
 *  - [byEngineId] returns the engine whose [UpscaleEngine.id] matches
 *    the [UpscaleEngineId.key]. Used by the bottom sheet's "Force
 *    engine" debug row.
 *  - [resolveForProfile] is the primary entry point — given a user
 *    profile, it returns the underlying engine the profile routes
 *    to. [UpscaleProfile.Auto] is resolved via the [autoResolver]
 *    callback at lookup time, so a single registry instance can
 *    serve an "Auto" selection that adapts as the device state
 *    changes between sessions.
 *
 * The registry never blocks and never throws. Unknown engine ids
 * return null; the ViewModel converts null into a
 * [UpscaleProgress.Failed] tick so the user gets a clear "engine
 * not found" error instead of a silent no-op.
 *
 * The Hilt-bound constructor takes the live [DeviceCapabilityDetector]
 * for production use; the [UpscaleEngineRegistryBase] superclass
 * accepts a pure `(UpscaleProfile) -> UpscaleProfile` lambda so a
 * JVM unit test can construct the registry with a fixed resolver
 * (no [android.content.Context] required).
 */
@Singleton
class UpscaleEngineRegistry @Inject constructor(
    engines: @JvmSuppressWildcards List<UpscaleEngine>,
    private val deviceCapabilityDetector: DeviceCapabilityDetector
) : UpscaleEngineRegistryBase(
    engines = engines,
    autoResolver = { deviceCapabilityDetector.recommended() }
) {

    /**
     * Convenience: which profile will Auto resolve to on this
     * device? The bottom sheet uses this to set the recommended
     * badge on either the Fast or the High Quality card.
     */
    fun autoResolvedProfile(): UpscaleProfile =
        deviceCapabilityDetector.recommended()
}

/**
 * Test-friendly base class for [UpscaleEngineRegistry]. Splits the
 * Auto resolution policy out of the Android-bound detector so a
 * JVM unit test can construct the registry with a fixed lambda
 * (no [android.content.Context] required).
 */
open class UpscaleEngineRegistryBase(
    engines: List<UpscaleEngine>,
    private val autoResolver: (UpscaleProfile) -> UpscaleProfile
) {

    private val engines: List<UpscaleEngine> = engines

    /** All registered engines in priority order. */
    val all: List<UpscaleEngine> get() = engines

    /**
     * Look up an engine by its [UpscaleEngineId]. The
     * [UpscaleEngineId.AUTO] sentinel is **not** registered directly
     * — callers should use [resolveForProfile] which substitutes the
     * recommended profile's engine id.
     */
    fun byEngineId(id: UpscaleEngineId): UpscaleEngine? =
        engines.firstOrNull { it.id == id.key }

    /**
     * Resolve a user-facing [UpscaleProfile] to the actual engine
     * that should run it. For [UpscaleProfile.Auto] the underlying
     * profile is recomputed via the constructor's [autoResolver]
     * lambda so the result tracks device state.
     */
    open fun resolveForProfile(profile: UpscaleProfile): UpscaleEngine? {
        val effective = if (profile is UpscaleProfile.Auto) {
            autoResolver(profile)
        } else profile
        return byEngineId(effective.engineId)
    }
}
