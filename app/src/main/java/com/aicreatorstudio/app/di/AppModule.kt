package com.aicreatorstudio.app.di

import android.content.Context
import androidx.room.Room
import com.aicreatorstudio.app.ai.AiToolEngine
import com.aicreatorstudio.app.ai.TranscriptionEngine
import com.aicreatorstudio.app.ai.WhisperEngine
import com.aicreatorstudio.app.ai.engines.MediaPipeMattingEngine
import com.aicreatorstudio.app.ai.engines.MotionTrackingEngine
import com.aicreatorstudio.app.ai.engines.RnnoiseVoiceCleanEngine
import com.aicreatorstudio.app.ai.engines.SaliencyTrackerEngine
import com.aicreatorstudio.app.ai.ocr.TesseractOcrEngine
import com.aicreatorstudio.app.db.ProjectDatabase
import com.aicreatorstudio.app.db.RoomProjectRepository
import com.aicreatorstudio.app.util.DefaultDispatcherProvider
import com.aicreatorstudio.app.util.DispatcherProvider
import com.aicreatorstudio.app.video.ProjectRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideProjectDatabase(@ApplicationContext context: Context): ProjectDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            ProjectDatabase::class.java,
            "projects_db"
        )
            // v4 -> v5 restores project editor state (canvas, captions,
            // overlays, playhead) without wiping existing projects.
            // 2026-10-04 — `fallbackToDestructiveMigration()` removed: a
            // silent wipe of every user project is never an acceptable
            // "safety net". If a future schema change arrives without a
            // migration, Room will now fail loudly (crash on open) instead
            // of destroying data — the migration must then be written.
            .addMigrations(
                ProjectDatabase.MIGRATION_4_5,
                ProjectDatabase.MIGRATION_5_6,
                ProjectDatabase.MIGRATION_6_7,
                // v7 -> v8 stores the project's video progress bar
                // (timeline indicator / story timer) as one additive JSON
                // column defaulting to `{}` (feature off).
                ProjectDatabase.MIGRATION_7_8
            )
            .build()
    }

    @Provides
    @Singleton
    fun provideProjectRepository(
        database: ProjectDatabase,
        mediaStorage: com.aicreatorstudio.app.video.MediaStorageManager
    ): ProjectRepository {
        return RoomProjectRepository(database, mediaStorage)
    }

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider {
        return DefaultDispatcherProvider()
    }

    /**
     * The list of [TranscriptionEngine]s available to the app.
     * 2026-10-10 ACCOUNT-FREE — the legacy Cloud Worker engine
     * (Supabase-auth gated, daily-quota) was REMOVED from the
     * active flow. Cloud transcription uses only
     * [com.aicreatorstudio.app.ai.groq.UserGroqEngine] (the user's
     * own Groq API key — no Omelet account, no daily quota).
     * Small/Medium remain fully offline via [WhisperEngine].
     *
     * ORDER MATTERS: the local Whisper engine must stay FIRST so
     * the offline SMALL path never gets hijacked by a saved API
     * key (activeEngine = first available). CLOUD_API routing does
     * not depend on list order — [SubtitleGenerator] resolves the
     * user-key engine by id and prefers it explicitly.
     */
    @Provides
    @Singleton
    fun provideTranscriptionEngines(
        whisperEngine: WhisperEngine,
        userGroqEngine: com.aicreatorstudio.app.ai.groq.UserGroqEngine
    ): List<TranscriptionEngine> = listOf(whisperEngine, userGroqEngine)

    /**
     * The list of [AiToolEngine]s registered with the app, in
     * priority order. Each id is unique — Background Remove /
     * Auto Frame / Voice Clean (RNNoise neural backend with the
     * spectral-gate fallback built in) / OCR / Motion Tracking.
     *
     * The order is the on-screen order in the AI tools menu but
     * the registry resolves by id so callers don't depend on
     * position.
     *
     * 2026-10-15 — MotionTrackingEngine is in this list. Until now
     * it was missing, so the toolbar's motion-tracking chip fired
     * `runAiTool("motiontrack")` into a registry with no engine for
     * that id and the run failed as `unknown_tool` before doing any
     * work. Adding an engine without adding it here silently
     * produces exactly that failure — `RegistryWiringTest` now
     * asserts every engine class is reachable through the module's
     * provider.
     */
    @Provides
    @Singleton
    fun provideAiToolEngines(
        matting: MediaPipeMattingEngine,
        autoFrame: SaliencyTrackerEngine,
        voiceClean: RnnoiseVoiceCleanEngine,
        ocr: TesseractOcrEngine,
        motionTracking: MotionTrackingEngine
    ): List<AiToolEngine> = listOf(matting, autoFrame, voiceClean, ocr, motionTracking)
}