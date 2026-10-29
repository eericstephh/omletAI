package com.aicreatorstudio.app.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.aicreatorstudio.app.audio.AudioPairingService
import com.aicreatorstudio.app.model.VideoClip
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seeds the app with the bundled sample video
 * (`assets/sample/Animation_storyboard_for_cooking_202609010012.mp4`)
 * on the very first launch so the user opens the app and immediately
 * sees the sample project on Home — ready to tap open in the editor.
 *
 * The flow mirrors a real import exactly:
 *
 *  1. Copy the asset into app-owned storage (`filesDir/media/`).
 *  2. Extract real metadata (duration / resolution / rotation) with
 *     [MediaMetadataRetriever].
 *  3. Create a persistent project through [ProjectRepository].
 *  4. Probe the file for an audio track and pair it, exactly like
 *     [com.aicreatorstudio.app.viewmodel.ImportVideoViewModel] does.
 *
 * One-time guard: a SharedPreferences flag marks the seed as done so
 * deleting the project does not resurrect it on the next launch. If
 * the flag is somehow set while the project is gone (e.g. a
 * destructive migration wiped the DB), the user can re-import the
 * video manually — the seeder only ever runs once per install.
 *
 * Threading: [seedOnFirstLaunch] is fire-and-forget (called from
 * `Application.onCreate`); all disk + DB work happens on
 * [Dispatchers.IO]. Every step is wrapped so a failure never crashes
 * the app — the user just doesn't see the sample project and can
 * import videos normally.
 */
@Singleton
class SampleProjectSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectRepository: ProjectRepository,
    private val audioPairing: AudioPairingService
) {

    /**
     * Kick off the seeding on a background thread. Safe to call from
     * `Application.onCreate` — returns immediately, never throws.
     */
    fun seedOnFirstLaunch() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { seedIfNeeded() }
                .onFailure { Log.w(TAG, "sample seeding failed", it) }
        }
    }

    private suspend fun seedIfNeeded() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SEEDED, false)) return

        // 1. Copy the bundled asset into app-owned storage so the
        //    project's URI stays valid for the lifetime of the install
        //    (same guarantee the import flow gives picked media).
        val dest = File(context.filesDir, MEDIA_DIR).apply { mkdirs() }
            .let { File(it, "${UUID.randomUUID()}.mp4") }
        context.assets.open(SAMPLE_ASSET_PATH).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        if (!dest.exists() || dest.length() == 0L) {
            dest.delete()
            Log.w(TAG, "sample asset copy produced an empty file; aborting seed")
            return
        }

        // 2. Real metadata from the copied file.
        val retriever = MediaMetadataRetriever()
        val (durationMs, rawWidth, rawHeight, rotation) = try {
            retriever.setDataSource(dest.absolutePath)
            val d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val r = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (d <= 0L || w <= 0 || h <= 0) {
                Log.w(TAG, "sample asset has no readable video metadata; aborting seed")
                return
            }
            listOf(d, w, h, r)
        } finally {
            runCatching { retriever.release() }
        }.map { it.toString().toLongOrNull() ?: 0L }
        val (displayW, displayH) = if (rotation == 90L || rotation == 270L) {
            rawHeight to rawWidth
        } else {
            rawWidth to rawHeight
        }

        val clip = VideoClip(
            id = UUID.randomUUID().toString(),
            uri = Uri.fromFile(dest).toString(),
            fileName = SAMPLE_DISPLAY_NAME,
            durationMs = durationMs,
            width = displayW.toInt(),
            height = displayH.toInt(),
            fileSizeBytes = dest.length(),
            importedAtEpochMillis = System.currentTimeMillis()
        )

        // 3. Persist the project (same path the import screen uses,
        //    including the source-size-derived canvas preset).
        val project = projectRepository.createProjectFromClip(
            clip,
            name = SAMPLE_DISPLAY_NAME.substringBeforeLast('.')
        )

        // 4. Pair the audio track if the file has one — identical to
        //    ImportVideoViewModel.saveToProject so the timeline shows
        //    the same waveform the user gets from a manual import.
        val paired = audioPairing.pairForVideo(
            videoUri = Uri.fromFile(dest),
            videoClipId = clip.id,
            startTimeMs = clip.timelineStartMs.coerceAtLeast(0L),
            durationMs = clip.effectiveDurationMs.coerceAtLeast(0L),
            fileName = clip.fileName
        )
        if (paired != null) {
            val current = projectRepository.getProject(project.id)
            if (current != null) {
                projectRepository.saveProjectMeta(
                    projectId = project.id,
                    canvasPreset = current.canvasPreset,
                    canvasBackground = current.canvasBackground,
                    textOverlays = current.textOverlays,
                    mediaOverlays = current.mediaOverlays,
                    audioClips = listOf(paired),
                    lastPositionMs = current.lastPositionMs,
                    progressBarSettings = current.progressBarSettings
                )
            }
        }

        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
        Log.i(TAG, "sample project seeded: ${project.id} (${dest.name})")
    }

    companion object {
        private const val TAG = "SampleProjectSeeder"
        private const val PREFS_NAME = "sample_project_seed"
        private const val KEY_SEEDED = "seeded"
        private const val MEDIA_DIR = "media"

        /** Bundled asset (ASCII-safe name) inside the APK. */
        const val SAMPLE_ASSET_PATH =
            "sample/Animation_storyboard_for_cooking_202609010012.mp4"

        /** Human-readable name shown on the Home project card. */
        const val SAMPLE_DISPLAY_NAME =
            "Animation_storyboard_for_cooking._202609010012.mp4"
    }
}
