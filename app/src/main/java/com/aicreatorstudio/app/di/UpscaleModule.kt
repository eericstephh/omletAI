package com.aicreatorstudio.app.di

import com.aicreatorstudio.app.ai.upscale.FastUpscaleEngine
import com.aicreatorstudio.app.ai.upscale.HighQualityUpscaleEngine
import com.aicreatorstudio.app.ai.upscale.UpscaleEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the AI Video Upscaler module.
 *
 * Two engines ship in the v1 app:
 *
 *  - [FastUpscaleEngine]  — Lanczos 2x, lightweight, no model download
 *  - [HighQualityUpscaleEngine] — Real-ESRGAN Compact 4x (TFLite
 *    model not yet bundled; the engine gracefully falls back to a
 *    Lanczos 4x pass until the model is in `assets/models/`).
 *
 * The list is the priority order: when a profile resolves to a
 * specific engine id, the first matching registration wins. Today
 * each id has exactly one impl, so the order is mostly cosmetic.
 *
 * A future TFLite ESRGAN implementation can be prepended to the
 * `high_quality` slot (or added under a new id) without touching
 * any consumer — the registry only routes by id, not by impl.
 */
@Module
@InstallIn(SingletonComponent::class)
object UpscaleModule {

    @Provides
    @Singleton
    fun provideUpscaleEngines(
        fast: FastUpscaleEngine,
        highQuality: HighQualityUpscaleEngine
    ): List<UpscaleEngine> = listOf(fast, highQuality)
}
