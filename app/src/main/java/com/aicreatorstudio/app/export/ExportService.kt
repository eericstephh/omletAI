package com.aicreatorstudio.app.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.aicreatorstudio.app.MainActivity
import com.aicreatorstudio.app.R
import com.aicreatorstudio.app.video.ProjectRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 2026-10-22 — the export runs HERE, in a foreground service, so it
 * survives the user leaving the app.
 *
 * ## The gap this closes
 *
 * The export used to run in the editor's `viewModelScope`. That scope
 * is cancelled when the Activity goes away, so **leaving the app
 * mid-render killed the export** — and swiping the app away killed it
 * too, along with everything else in the process. CapCut keeps
 * exporting in the background and shows a notification.
 *
 * ## Why a foreground service and not WorkManager
 *
 * A foreground service is the smallest thing that provides exactly the
 * behaviour CapCut has, with no new dependency: the process is not
 * killed while the service is in the foreground, so the render
 * survives. WorkManager would additionally survive a *process* death,
 * but the export's engine is an in-memory Media3 pipeline, so a
 * restarted process would have to re-render from zero anyway — and
 * `androidx.hilt:hilt-work` is not available to this project offline.
 *
 * ## What is honest about the limits
 *
 * The render still lives in memory. If the process is killed outright
 * (the user force-stops the app from Settings, or the system kills a
 * foreground service under extreme memory pressure), the export is lost
 * and is NOT resumed. That is a real limitation, not a claim of
 * "never lost" — see `open-gaps.md`.
 *
 * ## The notification
 *
 * A progress notification with a cancel action, on a dedicated low
 * importance channel. `POST_NOTIFICATIONS` is requested at runtime on
 * API 33+; without it the export still runs, the notification is just
 * not shown.
 */
@AndroidEntryPoint
class ExportService : Service() {

    @Inject lateinit var exportEngine: VideoExportEngine

    @Inject lateinit var projectRepository: ProjectRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var exportJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            scope.launch {
                exportEngine.cancel()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        val request = ExportRequest.fromIntent(intent)
        if (request == null) {
            // A malformed intent must not start a half-configured
            // export. Report it and stop.
            Log.w(TAG, "export service started without a usable request")
            stopSelf()
            return START_NOT_STICKY
        }
        // Android requires the foreground promotion to happen promptly
        // after the service is started, so it goes first — before the
        // project is even loaded.
        startForegroundSafely(buildNotification(0f, null))
        if (exportJob?.isActive == true) {
            // One export at a time; a second request is ignored rather
            // than racing the first (the engine would cancel it anyway).
            Log.w(TAG, "export already running; ignoring the new request")
            return START_NOT_STICKY
        }
        exportJob = scope.launch { runExport(request) }
        return START_NOT_STICKY
    }

    private suspend fun runExport(request: ExportRequest) {
        val project = projectRepository.getProject(request.projectId)
        if (project == null) {
            Log.w(TAG, "export service: project ${request.projectId} not found")
            stopSelf()
            return
        }
        // The service has no editor state, so it uses the project's own
        // (persisted) subtitles — the same set the editor would have
        // passed. The resolver is shared, so the file is identical.
        val resolved = ExportRequestResolver.resolve(project, request, overlays = null)
        exportEngine.startExport(resolved.project, resolved.config)
            .collectLatest { state ->
                when (state) {
                    is ExportState.Exporting -> {
                        val progress = state.progress.progress
                        updateNotification(buildNotification(progress, state.progress.currentStage.displayName))
                    }

                    is ExportState.Preparing ->
                        updateNotification(buildNotification(0f, null))

                    is ExportState.Succeeded -> {
                        updateNotification(buildNotification(1f, DONE))
                        stopSelf()
                    }

                    is ExportState.Failed -> {
                        Log.w(TAG, "export service: export failed — ${state.message}")
                        stopSelf()
                    }

                    ExportState.Cancelled -> stopSelf()

                    ExportState.Idle -> Unit
                }
            }
    }

    override fun onDestroy() {
        exportJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ---- notification ----

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = CHANNEL_DESCRIPTION
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(progress: Float, stage: String?): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(TITLE)
            .setContentText(stage ?: PREPARING)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    CANCEL,
                    cancelIntent
                ).build()
            )
        if (progress > 0f) {
            builder.setProgress(100, (progress * 100).toInt().coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun startForegroundSafely(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    foregroundServiceType()
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // Android 12+ throws ForegroundServiceStartNotAllowedException
            // when a foreground service is started from the background.
            // The user taps Export from the foreground, so this should
            // not happen — but if it does, the export must not silently
            // vanish: the failure is logged and the service stops.
            Log.e(TAG, "could not enter the foreground; the export cannot run", t)
            stopSelf()
        }
    }

    /**
     * 2026-10-24 — the foreground-service type has to exist in the
     * **running** framework, not just in the compile SDK.
     *
     * `mediaProcessing` was added in API 35. This app targets 35 but runs
     * on Android 8+ devices, and on Android 14 the package manager treats
     * the unknown attribute value as no type at all, so
     * `startForeground(id, notification, mediaProcessing)` failed with
     *
     * ```
     * InvalidForegroundServiceTypeException: Starting FGS with type
     * unknown ... has been prohibited
     * ```
     *
     * and **every export on that phone was refused** before a single
     * frame was decoded. `dataSync` exists from API 29 and is an accurate
     * description of "write the rendered video to disk"; API 35+ keeps the
     * purpose-built `mediaProcessing` type. The manifest declares both
     * (`dataSync|mediaProcessing`), which is what makes either legal.
     */
    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }

    private fun updateNotification(notification: Notification) {
        try {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.notify(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "could not update the export notification", t)
        }
    }

    companion object {
        private const val TAG = "ExportService"
        private const val CHANNEL_ID = "export_progress"
        private const val NOTIFICATION_ID = 4711
        const val ACTION_CANCEL = "com.aicreatorstudio.app.action.CANCEL_EXPORT"

        // The project's UI strings are hardcoded Persian (see the
        // editor panels), so the notification follows the same rule
        // rather than introducing a resource file for four strings.
        private const val CHANNEL_NAME = "پیشرفت خروجی"
        private const val CHANNEL_DESCRIPTION = "نمایش پیشرفت خروجی گرفتن ویدیو"
        private const val TITLE = "در حال خروجی گرفتن"
        private const val PREPARING = "آماده‌سازی…"
        private const val CANCEL = "لغو"
        private const val DONE = "خروجی آماده شد"

        /**
         * Start an export in the background.
         *
         * The caller is the editor, and it must be in the foreground
         * (Android 12+ forbids starting a foreground service from the
         * background) — which it is, because the user taps Export.
         */
        fun start(context: Context, request: ExportRequest) {
            val intent = request.toIntent(context)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "could not start the export service", t)
            }
        }
    }
}
