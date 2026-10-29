package com.aicreatorstudio.app.ai.upscale

/**
 * User-selectable AI Video Upscaler profile.
 *
 * The model is a sealed class (rather than an enum) so we can carry
 * per-profile metadata (icon, headline, "needs" subtitle, and the
 * [UpscaleEngineId] the [UpscaleEngineRegistry] routes to) without a
 * parallel lookup table.
 *
 * Three profiles are exposed:
 *
 *  - [Fast]          — low-end / battery-saver. 2x upscale, TFLite
 *                      CPU/NNAPI or fast GPU shader (Anime4K / FSRCNN).
 *                      Minimal RAM footprint, low thermal throttling risk.
 *  - [HighQuality]   — flagship. 4x upscale, Real-ESRGAN Compact via
 *                      ONNX Runtime Mobile / NCNN / Vulkan. High visual
 *                      fidelity, sharp edge reconstruction.
 *  - [Auto]          — capability-detected default. Resolves to either
 *                      of the above at run time using
 *                      [DeviceCapabilityDetector.recommended].
 *
 * Each profile is bound to a single engine id; the registry's
 * [UpscaleEngineRegistry.byId] is the single source of truth for
 * which id maps to which engine implementation. The engine id is
 * also what the new bottom-sheet card persists into
 * [com.aicreatorstudio.app.model.EditorUiState.selectedUpscaleProfile].
 *
 * The profile only changes the *upscaler pipeline*; it does not
 * affect the regular editor export ([com.aicreatorstudio.app.export.VideoExportEngine])
 * or the v1 [com.aicreatorstudio.app.export.UpscalerExportEngine]
 * (which serves a different "preset" semantic: enhancement vs. true
 * 2x). The two paths coexist.
 */
sealed class UpscaleProfile(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val targetScale: Float,
    val engineId: UpscaleEngineId
) {

    /**
     * Recommended for low-end devices and battery saver mode.
     * 2x upscale, light weight, low memory pressure.
     */
    data object Fast : UpscaleProfile(
        id = "fast",
        displayName = "Fast Mode",
        subtitle = "2x upscale · Recommended for faster export & smooth performance",
        targetScale = 2.0f,
        engineId = UpscaleEngineId.FAST
    )

    /**
     * High-end pipeline. 4x upscale, requires a powerful GPU / NPU.
     */
    data object HighQuality : UpscaleProfile(
        id = "high_quality",
        displayName = "High Quality",
        subtitle = "4x ultra-sharp AI enhancement · Requires powerful GPU",
        targetScale = 4.0f,
        engineId = UpscaleEngineId.HIGH_QUALITY
    )

    /**
     * Auto-detect: ask [DeviceCapabilityDetector.recommended] at run
     * time which underlying profile to use. The user's UI choice is
     * still the *Auto* profile; only the actual engine id swaps.
     */
    data object Auto : UpscaleProfile(
        id = "auto",
        displayName = "Auto",
        subtitle = "Automatically picks Fast or High Quality based on your device",
        targetScale = 0f, // resolved by the detector
        engineId = UpscaleEngineId.AUTO
    )

    companion object {
        /**
         * All three profiles in display order.
         *
         * Initialized lazily via a backing [Array] wrapped in a
         * defensive copy at first access. The previous eager
         * `val all = listOf(Fast, HighQuality, Auto)` worked in
         * single-class-loader scenarios but in the Gradle
         * `transformDebugClassesWithAsm` + multi-jar unit-test
         * classpath the JVM could observe [Fast.INSTANCE] as
         * `null` during the [UpscaleProfile] static initializer
         * (the eager list was built with a literal `null` at index
         * 0). The lazy getter ensures the array is built at the
         * first call site, by which point all data objects are
         * fully loaded.
         */
        private val ALL_VALUES: Array<UpscaleProfile> by lazy {
            arrayOf(Fast, HighQuality, Auto)
        }

        val all: List<UpscaleProfile>
            get() = ALL_VALUES.toList()

        /**
         * Resolve a profile by its stable [id]. Returns null when
         * [id] is not one of the recognised profile ids — the caller
         * (typically a JSON deserializer or the ViewModel's state
         * restore path) treats null as "no profile selected" and
         * leaves the UI state at its default.
         */
        fun byId(id: String?): UpscaleProfile? = ALL_VALUES.firstOrNull { it.id == id }
    }
}

/**
 * Stable engine id keyed by [UpscaleProfile.engineId]. Distinct from
 * the profile id so the [UpscaleEngineRegistry] can route a single
 * id to a single engine implementation, regardless of how many
 * profiles point at it. [UpscaleEngineId.Auto] is the runtime-resolved
 * slot — the registry transparently substitutes the recommended
 * profile's engine when the user picks Auto.
 */
enum class UpscaleEngineId(val key: String) {
    /** Lightweight super-resolution (TFLite CPU/NNAPI / fast GPU shader). */
    FAST("fast"),

    /** Real-ESRGAN Compact via ONNX Runtime Mobile / NCNN / Vulkan. */
    HIGH_QUALITY("high_quality"),

    /**
     * Sentinel: the registry resolves this to FAST or HIGH_QUALITY at
     * lookup time using [DeviceCapabilityDetector]. Engines themselves
     * are never registered under this id.
     */
    AUTO("auto");

    companion object {
        /** Parse a key, returning null for unknown values. */
        fun fromKey(key: String?): UpscaleEngineId? =
            entries.firstOrNull { it.key == key }
    }
}
