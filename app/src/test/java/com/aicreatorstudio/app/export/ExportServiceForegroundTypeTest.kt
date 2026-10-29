package com.aicreatorstudio.app.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-24 — the export service that could not start.
 *
 * On a Xiaomi 2107113SG (Android 14, API 34) every export was refused
 * before the first frame:
 *
 * ```
 * E/ExportService: could not enter the foreground; the export cannot run
 * android.app.InvalidForegroundServiceTypeException: Starting FGS with
 *   type unknown ... has been prohibited
 * ```
 *
 * Three independent manifest facts are required for a foreground service,
 * and the code had one of them:
 *
 * 1. `FOREGROUND_SERVICE` (API 28+) — the comment claimed it was declared
 *    "above"; it was not, so `startForeground` throws `SecurityException`.
 * 2. A type that exists in the **running** framework. `mediaProcessing`
 *    is API 35, and this app targets 35 while running on API 24+: on
 *    API 34 the unknown attribute is dropped, the service has no type,
 *    and the start is prohibited. `dataSync` (API 29) is the type that
 *    exists there.
 * 3. The per-type permission from API 34 (`FOREGROUND_SERVICE_DATA_SYNC`,
 *    `FOREGROUND_SERVICE_MEDIA_PROCESSING`).
 *
 * The type must also be chosen at **runtime** by SDK level, because the
 * manifest declaring both is not enough — the argument passed to
 * `startForeground` has to be one the device knows.
 *
 * None of this can fail a JVM unit test at runtime (it is a platform
 * check), which is exactly why it is pinned here.
 */
class ExportServiceForegroundTypeTest {

    private fun repoRoot(): File {
        var dir: File = File(".").absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: throw IllegalStateException("settings.gradle.kts not found")
        }
    }

    private fun manifest(): String =
        File(repoRoot(), "app/src/main/AndroidManifest.xml").readText()

    private fun service(): String = File(
        repoRoot(),
        "app/src/main/java/com/aicreatorstudio/app/export/ExportService.kt"
    ).readText()

    @Test
    fun `the manifest declares both foreground service types`() {
        assertTrue(
            "The service must declare `dataSync|mediaProcessing`: `mediaProcessing` " +
                "alone is unknown on API 34, where the package manager drops it and the " +
                "service start is refused (InvalidForegroundServiceTypeException)",
            manifest().contains("""android:foregroundServiceType="dataSync|mediaProcessing"""")
        )
    }

    @Test
    fun `every required foreground service permission is declared`() {
        val m = manifest()
        for (permission in listOf(
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING"
        )) {
            assertTrue(
                "`$permission` is missing from the manifest. Without " +
                    "FOREGROUND_SERVICE, startForeground throws SecurityException on " +
                    "API 28+; without the per-type permission, API 34+ refuses the type.",
                m.contains(permission)
            )
        }
    }

    @Test
    fun `the service picks a type the running device knows`() {
        val src = service()
        assertTrue(
            "API 35+ must use mediaProcessing",
            src.contains("ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING")
        )
        assertTrue(
            "API 29-34 must use dataSync, otherwise startForeground is refused on " +
                "Android 14 (the type is unknown there)",
            src.contains("ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC")
        )
        assertTrue(
            "The choice must be gated on the platform level",
            src.contains("Build.VERSION_CODES.VANILLA_ICE_CREAM")
        )
        assertFalse(
            "`startForeground(..., MEDIA_PROCESSING)` must not be unconditional: that " +
                "is the line that killed every export on Android 14",
            src.contains(
                "startForeground(\n" +
                    "                    NOTIFICATION_ID,\n" +
                    "                    notification,\n" +
                    "                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING\n" +
                    "                )"
            )
        )
    }
}
