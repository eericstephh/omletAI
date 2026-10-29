package com.aicreatorstudio.app.export

import android.content.Context
import android.content.Intent
import com.aicreatorstudio.app.model.TextOverlay
import com.aicreatorstudio.app.model.UpscalerPreset
import com.aicreatorstudio.app.model.UpscalerSettings
import com.aicreatorstudio.app.model.VideoProject

/**
 * 2026-10-22 — the request that starts an export, and the ONE rule that
 * turns it into a project + a config.
 *
 * ## Why a request object
 *
 * The export used to run inside the editor's `viewModelScope`, which
 * means it died with the Activity: leaving the app, or swiping it away,
 * killed a render that may have been minutes old. CapCut keeps
 * exporting in the background, with a notification.
 *
 * The fix moves the export into a foreground **service** — but a service
 * cannot receive a `VideoProject` through an `Intent`, so it needs the
 * project's id plus the few values that are not on the project. Those
 * travel as [ExportRequest].
 *
 * ## Why the resolver is a separate, pure function
 *
 * The editor used to assemble the project and the config inline, and the
 * service would have had to do it again — two copies of the same rules,
 * free to drift. [resolve] is the single copy: the editor and the
 * service both call it, so the file the service renders is the file the
 * editor would have rendered.
 *
 * It is pure (no Android, no IO) so it is unit-testable on the JVM.
 */
data class ExportRequest(
    /** The project to export. */
    val projectId: String,
    /** The target quality tier. */
    val quality: ExportQuality,
    /** The output file's base name. */
    val baseName: String,
    /**
     * The AI upscaler's **global** default.
     *
     * It lives on `EditorUiState`, not on the project, so it has to
     * travel with the request — the service has no editor state. The
     * per-clip settings are on the project already and are read from
     * there.
     */
    val globalUpscale: UpscalerSettings = UpscalerSettings.Identity
) {

    /** Encode the request into an [Intent] for [ExportService]. */
    fun toIntent(context: Context): Intent =
        Intent(context, ExportService::class.java).apply {
            putExtra(EXTRA_PROJECT_ID, projectId)
            putExtra(EXTRA_QUALITY, quality.name)
            putExtra(EXTRA_BASE_NAME, baseName)
            putExtra(EXTRA_UPSCALE_PRESET, globalUpscale.preset.name)
            putExtra(EXTRA_UPSCALE_STRENGTH, globalUpscale.strength)
        }

    companion object {
        const val EXTRA_PROJECT_ID = "projectId"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_BASE_NAME = "baseName"
        const val EXTRA_UPSCALE_PRESET = "upscalePreset"
        const val EXTRA_UPSCALE_STRENGTH = "upscaleStrength"

        /**
         * Decode a request from an [Intent], or null when the intent is
         * not one of ours.
         *
         * A malformed intent must not start an export with half a
         * request, so anything missing or unrecognised decodes to null
         * and the caller reports a failure instead of exporting the
         * wrong project.
         */
        fun fromIntent(intent: Intent?): ExportRequest? {
            val projectId = intent?.getStringExtra(EXTRA_PROJECT_ID)?.takeIf { it.isNotBlank() }
                ?: return null
            val quality = intent.getStringExtra(EXTRA_QUALITY)
                ?.let { name -> ExportQuality.entries.firstOrNull { it.name == name } }
                ?: return null
            val baseName = intent.getStringExtra(EXTRA_BASE_NAME) ?: "export"
            val preset = intent.getStringExtra(EXTRA_UPSCALE_PRESET)
                ?.let { name -> UpscalerPreset.entries.firstOrNull { it.name == name } }
                ?: UpscalerPreset.OFF
            val strength = intent.getFloatExtra(EXTRA_UPSCALE_STRENGTH, 0.5f)
            return ExportRequest(
                projectId = projectId,
                quality = quality,
                baseName = baseName,
                globalUpscale = UpscalerSettings(
                    preset = preset,
                    strength = strength.coerceIn(0f, 1f)
                )
            )
        }
    }
}

/** The project and the config an export runs with. */
data class ResolvedExport(
    val project: VideoProject,
    val config: ExportConfig
)

/**
 * The single rule that turns a project into an export.
 *
 * Three things happen here, and every one of them used to live inline
 * in the editor's `startExportLocked`:
 *
 * 1. **The current subtitles are used.** The in-memory project can lag
 *    behind the editor's state (a generated subtitle set is committed
 *    to the state and to disk, but not always copied back into the
 *    project object), so the editor passes its live overlay list. The
 *    service has no editor state and passes nothing — it uses the
 *    project's own (persisted) overlays, which are the same set.
 *
 * 2. **The global upscaler default is applied** to the clips that have
 *    no per-clip setting, so the file matches what the preview showed.
 *
 * 3. **The output size** comes from the canvas preset, falling back to
 *    the source's own orientation — never from the quality tier, which
 *    is always landscape and would frame a portrait project 16:9.
 */
object ExportRequestResolver {

    /**
     * @param overlays the editor's CURRENT subtitle set, or null to use
     *   the project's own (the background service's case).
     */
    fun resolve(
        project: VideoProject,
        request: ExportRequest,
        overlays: List<TextOverlay>? = null
    ): ResolvedExport {
        val withOverlays = if (overlays != null && overlays != project.textOverlays) {
            project.copy(textOverlays = overlays)
        } else {
            project
        }

        val global = request.globalUpscale
        val withUpscale = if (global != UpscalerSettings.Identity) {
            withOverlays.copy(
                clips = withOverlays.clips.map { clip ->
                    if (clip.upscale == UpscalerSettings.Identity) {
                        clip.copy(upscale = global)
                    } else {
                        clip
                    }
                }
            )
        } else {
            withOverlays
        }

        val preset = withUpscale.canvasPreset
        val referenceClip = withUpscale.clips.firstOrNull { !it.isAudioOnly }
        val (outW, outH) = ExportFrame.resolveOutputSize(
            canvasPreset = preset,
            sourceWidthPx = referenceClip?.width ?: 0,
            sourceHeightPx = referenceClip?.height ?: 0,
            qualityWidthPx = request.quality.widthPx,
            qualityHeightPx = request.quality.heightPx
        )
        val config = ExportConfig(
            quality = request.quality,
            outputBaseName = request.baseName,
            outputWidthPx = outW,
            outputHeightPx = outH,
            canvasPreset = preset,
            canvasBackground = withUpscale.canvasBackground
        )
        return ResolvedExport(project = withUpscale, config = config)
    }
}
