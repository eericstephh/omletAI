package com.aicreatorstudio.app.di

import com.aicreatorstudio.app.export.Media3TransformerExportEngine
import com.aicreatorstudio.app.export.Media3UpscalerExportEngine
import com.aicreatorstudio.app.export.UpscalerExportEngine
import com.aicreatorstudio.app.export.VideoExportEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the [VideoExportEngine] and [UpscalerExportEngine] interfaces
 * to their Media3 `Transformer` implementations.
 *
 * Kept as a separate `@Binds` module so [AppModule] (which is
 * `object`-based for `@Provides` methods) doesn't have to switch to an
 * `abstract class`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ExportModule {

    @Binds
    @Singleton
    abstract fun bindVideoExportEngine(
        impl: Media3TransformerExportEngine
    ): VideoExportEngine

    @Binds
    @Singleton
    abstract fun bindUpscalerExportEngine(
        impl: Media3UpscalerExportEngine
    ): UpscalerExportEngine
}
