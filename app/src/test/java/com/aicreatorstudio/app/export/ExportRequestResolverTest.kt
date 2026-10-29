package com.aicreatorstudio.app.export

import com.aicreatorstudio.app.model.CanvasBackground
import com.aicreatorstudio.app.model.CanvasPreset
import com.aicreatorstudio.app.model.TextOverlay
import com.aicreatorstudio.app.model.UpscalerPreset
import com.aicreatorstudio.app.model.UpscalerSettings
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.model.VideoProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-22 â€” the background export.
 *
 * ## The gap
 *
 * The export used to run in the editor's `viewModelScope`, so leaving
 * the app (or swiping it away) killed a render that might have been
 * minutes old. CapCut keeps exporting in the background, with a
 * notification.
 *
 * ## What this test pins
 *
 * The export now runs in a foreground service, which means the request
 * has to cross a process boundary â€” and the *rules* that turn a project
 * into an export must not exist twice. [ExportRequestResolver] is that
 * single rule, and it is pure, so it is testable here.
 */
class ExportRequestResolverTest {

    // -----------------------------------------------------------------
    // The resolver
    // -----------------------------------------------------------------

    @Test
    fun `the global upscaler default is applied to clips without their own`() {
        val project = project(
            clips = listOf(
                clip(id = "plain"),
                clip(id = "explicit", upscale = UpscalerSettings(UpscalerPreset.FACE_CLARITY_BOOST, 0.9f))
            )
        )
        val request = ExportRequest(
            projectId = project.id,
            quality = ExportQuality.HIGH_1080P,
            baseName = "out",
            globalUpscale = UpscalerSettings(UpscalerPreset.SMART_HD_1080P, 0.5f)
        )
        val resolved = ExportRequestResolver.resolve(project, request)
        val plain = resolved.project.clips.first { it.id == "plain" }
        val explicit = resolved.project.clips.first { it.id == "explicit" }
        assertEquals(
            "a clip with no setting must get the global default",
            UpscalerSettings(UpscalerPreset.SMART_HD_1080P, 0.5f),
            plain.upscale
        )
        assertEquals(
            "a clip's own setting must win over the global default",
            UpscalerSettings(UpscalerPreset.FACE_CLARITY_BOOST, 0.9f),
            explicit.upscale
        )
    }

    @Test
    fun `no global default leaves the clips untouched`() {
        val project = project(clips = listOf(clip(id = "plain")))
        val request = ExportRequest(
            projectId = project.id,
            quality = ExportQuality.HIGH_1080P,
            baseName = "out",
            globalUpscale = UpscalerSettings.Identity
        )
        val resolved = ExportRequestResolver.resolve(project, request)
        assertEquals(UpscalerSettings.Identity, resolved.project.clips.first().upscale)
    }

    @Test
    fun `the editor's live subtitles win over the project's`() {
        val project = project(clips = listOf(clip(id = "c1")))
        val live = listOf(TextOverlay(text = "live", startTimeMs = 0L, endTimeMs = 1_000L))
        val request = ExportRequest(project.id, ExportQuality.HIGH_1080P, "out")
        val resolved = ExportRequestResolver.resolve(project, request, overlays = live)
        assertEquals(1, resolved.project.textOverlays.size)
        assertEquals("live", resolved.project.textOverlays.first().text)
    }

    @Test
    fun `the background service uses the project's own subtitles`() {
        // The service has no editor state, so it passes null and takes
        // the project's (persisted) subtitles.
        val project = project(clips = listOf(clip(id = "c1")))
            .copy(textOverlays = listOf(TextOverlay(text = "saved", startTimeMs = 0L, endTimeMs = 1_000L)))
        val request = ExportRequest(project.id, ExportQuality.HIGH_1080P, "out")
        val resolved = ExportRequestResolver.resolve(project, request, overlays = null)
        assertEquals(1, resolved.project.textOverlays.size)
        assertEquals("saved", resolved.project.textOverlays.first().text)
    }

    @Test
    fun `the output size comes from the canvas preset, not the quality tier`() {
        // Every `ExportQuality` is landscape, so a portrait project must
        // not be framed 16:9 just because the tier says so.
        val project = project(
            clips = listOf(clip(id = "c1", width = 1080, height = 1920)),
            canvasPreset = CanvasPreset.FREE
        )
        val request = ExportRequest(project.id, ExportQuality.HIGH_1080P, "out")
        val resolved = ExportRequestResolver.resolve(project, request)
        assertEquals(1080, resolved.config.outputWidthPx)
        assertEquals(1920, resolved.config.outputHeightPx)
    }

    @Test
    fun `a sized canvas preset wins over the source orientation`() {
        val project = project(
            clips = listOf(clip(id = "c1", width = 1080, height = 1920)),
            canvasPreset = CanvasPreset.VERTICAL_9_16
        )
        val request = ExportRequest(project.id, ExportQuality.HIGH_1080P, "out")
        val resolved = ExportRequestResolver.resolve(project, request)
        assertEquals(1080, resolved.config.outputWidthPx)
        assertEquals(1920, resolved.config.outputHeightPx)
    }

    @Test
    fun `the config carries the request's quality and name`() {
        val project = project(clips = listOf(clip(id = "c1")))
        val request = ExportRequest(project.id, ExportQuality.STANDARD_720P, "my-clip")
        val resolved = ExportRequestResolver.resolve(project, request)
        assertEquals(ExportQuality.STANDARD_720P, resolved.config.quality)
        assertEquals("my-clip", resolved.config.outputBaseName)
    }

    // -----------------------------------------------------------------
    // The wiring
    // -----------------------------------------------------------------

    private fun repoRoot(): File {
        var dir: File = File(".").absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: throw IllegalStateException("settings.gradle.kts not found")
        }
    }

    private fun source(relative: String): String {
        val f = File(repoRoot(), relative)
        assertTrue("missing file: $f", f.isFile)
        return f.readText()
    }

    @Test
    fun `the service is declared as a foreground service of the right type`() {
        val manifest = source("app/src/main/AndroidManifest.xml")
        assertTrue(
            "the export service must be declared",
            manifest.contains("android:name=\".export.ExportService\"")
        )
        assertTrue(
            "the foreground service type must name both types: dataSync for the API 29-34 " +
                "range where mediaProcessing does not exist, and mediaProcessing for the " +
                "API 35+ render",
            manifest.contains("android:foregroundServiceType=\"dataSync|mediaProcessing\"")
        )
        assertTrue(
            "the notification permission must be requested",
            manifest.contains("android.permission.POST_NOTIFICATIONS")
        )
        assertTrue(
            "the media-processing foreground-service permission must be declared",
            manifest.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING")
        )
        assertTrue(
            "the data-sync foreground-service permission must be declared too, " +
                "otherwise the API 29-34 start throws SecurityException",
            manifest.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC")
        )
    }

    @Test
    fun `the editor starts the service instead of the export`() {
        val vm = source(
            "app/src/main/java/com/aicreatorstudio/app/viewmodel/EditorViewModel.kt"
        )
        assertTrue(
            "the editor must hand the export to the service",
            vm.contains("ExportService.start(appContext, request)")
        )
        assertTrue(
            "the editor must observe the engine's SHARED state â€” the " +
                "service is the one that starts the export",
            vm.contains("exportEngine.state.collect")
        )
    }

    @Test
    fun `the service owns the export`() {
        val service = source(
            "app/src/main/java/com/aicreatorstudio/app/export/ExportService.kt"
        )
        assertTrue(
            "the service must start the export",
            service.contains("exportEngine.startExport(resolved.project, resolved.config)")
        )
        assertTrue(
            "the service must resolve the request with the shared rule",
            service.contains("ExportRequestResolver.resolve(project, request, overlays = null)")
        )
        assertTrue(
            "the service must promote itself to the foreground",
            service.contains("startForegroundSafely(buildNotification(0f, null))")
        )
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private fun clip(
        id: String,
        width: Int = 1920,
        height: Int = 1080,
        upscale: UpscalerSettings = UpscalerSettings.Identity
    ): VideoClip = VideoClip(
        id = id,
        uri = "content://media/$id",
        fileName = id,
        durationMs = 1_000,
        width = width,
        height = height,
        fileSizeBytes = 1_000,
        importedAtEpochMillis = 0L,
        timelineStartMs = 0,
        upscale = upscale
    )

    private fun project(
        clips: List<VideoClip>,
        canvasPreset: CanvasPreset = CanvasPreset.FREE
    ): VideoProject = VideoProject(
        id = "p1",
        name = "Test",
        clips = clips,
        createdAtEpochMillis = 0L,
        canvasPreset = canvasPreset,
        canvasBackground = CanvasBackground.Black
    )
}
