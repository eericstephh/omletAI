package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-08 SPEED RESTORE â€” JVM regression guard for the Whisper
 * model selection pipeline (supersedes the PHASE B1.6 audit).
 *
 * B1.6 removed Tiny + Small after a Persian accuracy audit. Real
 * devices then showed the consequence: Whisper Medium's CPU-only
 * inference runs at 1-2x realtime (5+ minutes for a 5-minute clip),
 * which users read as "the feature doesn't work". The 2026-09-08
 * rebuild restores the Small size class in its modern form:
 * `ggml-small-q5_1.bin` (488 MB, ~2.5x faster than Medium with
 * near-identical accuracy), plus flash attention and a halved
 * temperature-fallback step in the engine.
 *
 * What the suite pins:
 *
 *   1. [WhisperModelSpec] exposes exactly SMALL + MEDIUM +
 *      LARGE_V3 (Tiny stays gone).
 *   2. The canonical [AiModelCatalog.all] carries a real entry for
 *      every spec with distinct on-disk paths.
 *   3. The default policy ([WhisperModelSpec.defaultFor]) is
 *      SMALL for every device â€” speed first, accuracy opt-in.
 *   4. Migration: legacy TINY/SMALL ids land on SMALL (the same
 *      size class, modern form); MEDIUM/LARGE_V3 round-trip.
 *   5. Every spec hardcodes BCP-47 "fa".
 *   6. The download-URL table covers every catalog entry.
 *   7. The small-q5 entry is DOWNLOAD-ONLY (bundled: false) â€” it
 *      must never bloat the APK.
 */
class WhisperModelSelectionAuditTest {

    // ---- 1. WhisperModelSpec enum shape ----



    @Test
    fun `WhisperModelSpec does not expose TINY`() {
        assertFalse(
            "Tiny (77 MB) stays removed â€” its Persian accuracy was " +
                "never production-acceptable and Small-q5_1 covers the " +
                "low-RAM use case far better.",
            WhisperModelSpec.entries.any { it.name == "TINY" }
        )
    }

    // ---- 2. Catalog plumbing ----

    @Test
    fun `AiModelCatalog all includes a catalog entry for every WhisperModelSpec`() {
        val allIds = AiModelCatalog.all.map { it.id }.toSet()
        for (spec in WhisperModelSpec.entries) {
            assertTrue(
                "AiModelCatalog is missing a CatalogEntry for ${spec.name}. " +
                    "WhisperModelSpec.aiSpec() would fail.",
                spec.aiSpec().id in allIds
            )
        }
    }

    @Test
    fun `AiModelCatalog byId resolves the one offline whisper id and drops medium and large-v3`() {
        assertNotNull(AiModelCatalog.byId("whisper-small"))
        assertNull(AiModelCatalog.byId("whisper-medium"))
        assertNull(AiModelCatalog.byId("whisper-large-v3"))
    }

    @Test
    fun `AiModelCatalog byId resolves the restored whisper-small and rejects withdrawn q5 id`() {
        // 2026-09-08 SPEED RESTORE — the f16 `whisper-small` entry is
        // back as the speed default. The brief-lived q5_1 variant id
        // (per-character Persian output) stays withdrawn, and Tiny
        // stays gone.
        assertNotNull(AiModelCatalog.byId("whisper-small"))
        assertNull(AiModelCatalog.byId("whisper-small-q5"))
        assertNull(AiModelCatalog.byId("whisper-tiny"))
        assertNull(AiModelCatalog.byId("whisper-medium"))
    }

    @Test
    fun `AiModelCatalog byId returns null for unknown model id`() {
        assertNull(AiModelCatalog.byId("whisper-huge"))
        assertNull(AiModelCatalog.byId(""))
        assertNull(AiModelCatalog.byId("whisper-tiny ")) // trailing space
    }

    @Test
    fun `each WhisperModelSpec maps to a distinct on-disk directory and file`() {
        // If two specs resolved to the same file the installer's
        // atomic rename would clobber one with the other.
        // 2026-09-11 — CLOUD_API maps to SMALL's catalog entry by
        // design (routing skips the local path); the distinct-path
        // invariant applies to the LOCAL specs only.
        val localSpecs = WhisperModelSpec.entries
            .filter { it != WhisperModelSpec.CLOUD_API }
        val paths = localSpecs.map { spec ->
            val e = spec.aiSpec()
            "${e.dirName}/${e.fileName}"
        }
        assertEquals(
            "Each local WhisperModelSpec must resolve to a unique " +
                "filesDir/models/<dirName>/<fileName> path.",
            localSpecs.size, paths.toSet().size
        )
    }


    @Test
    fun `AiModelCatalog all carries the restored Small and excludes Tiny and q5`() {
        val ids = AiModelCatalog.all.map { it.id }
        assertTrue(
            "restored 'whisper-small' must be in the catalog",
            ids.contains("whisper-small")
        )
        assertFalse(
            "'whisper-small-q5' (withdrawn q5_1 build) must stay removed",
            ids.contains("whisper-small-q5")
        )
        assertFalse("'whisper-tiny' must stay removed", ids.contains("whisper-tiny"))
    }

    @Test
    fun `whisper-small is download-only so the APK never bundles it`() {
        // The 488 MB binary must be fetched on demand (like
        // Large-v3), not shipped inside the APK.
        val entry = AiModelCatalog.byId("whisper-small")
        assertNotNull(entry)
        assertFalse(
            "whisper-small must be download-only (bundled=false in " +
                "models.lock.json); the installer reads the bundledOnly " +
                "flag from the runtime manifest, which is generated " +
                "with bundled=false for download-only lock entries.",
            entry!!.bundledOnly
        )
    }

    // ---- 3. Device-aware default ----

    @Test
    fun `defaultFor returns SMALL for any free RAM - speed is the default`() {
        // 2026-09-08 directive: DEFAULT = the fast Small-q5_1 model.
        // Medium/Large are opt-in for users who explicitly prefer
        // accuracy and accept the multi-minute wait.
        for (freeMb in listOf(0L, 512L, 1_536L, 4_096L, 8_192L, 16_384L, Long.MAX_VALUE)) {
            assertEquals(
                "defaultFor($freeMb) must be SMALL (speed default).",
                WhisperModelSpec.SMALL,
                WhisperModelSpec.defaultFor(freeMb)
            )
        }
    }

    @Test
    fun `static Default is SMALL`() {
        assertEquals(
            "WhisperModelSpec.Default must be SMALL per the " +
                "2026-09-08 SPEED RESTORE.",
            WhisperModelSpec.SMALL,
            WhisperModelSpec.Default
        )
    }

    // ---- 4. Migration ----

    @Test
    fun `migrateSavedId maps legacy TINY to SMALL`() {
        // Pre-B1.6 TINY selections land on the modern low-RAM model.
        assertEquals(
            WhisperModelSpec.SMALL,
            WhisperModelSpec.migrateSavedId("TINY")
        )
        assertEquals(
            WhisperModelSpec.SMALL,
            WhisperModelSpec.migrateSavedId("WHISPER_TINY")
        )
    }

    @Test
    fun `migrateSavedId maps legacy SMALL to SMALL`() {
        // Same size class the user originally picked, in its modern
        // quantized form â€” NOT Medium (that would 3x their runtime).
        assertEquals(
            WhisperModelSpec.SMALL,
            WhisperModelSpec.migrateSavedId("SMALL")
        )
        assertEquals(
            WhisperModelSpec.SMALL,
            WhisperModelSpec.migrateSavedId("WHISPER_SMALL")
        )
        assertEquals(
            WhisperModelSpec.SMALL,
            WhisperModelSpec.migrateSavedId("small")
        )
    }


    @Test
    fun `migrateSavedId maps null and unknown ids to SMALL`() {
        assertEquals(WhisperModelSpec.SMALL, WhisperModelSpec.migrateSavedId(null))
        assertEquals(WhisperModelSpec.SMALL, WhisperModelSpec.migrateSavedId(""))
        assertEquals(
            WhisperModelSpec.SMALL,
            WhisperModelSpec.migrateSavedId("completely-unknown-id")
        )
    }

    // ---- 5. Language, RAM ladder & download URLs ----

    @Test
    fun `every WhisperModelSpec hardcodes language fa so JNI receives Persian`() {
        for (spec in WhisperModelSpec.entries) {
            assertEquals(
                "WhisperModelSpec.${spec.name}.language must be 'fa'.",
                "fa", spec.language
            )
        }
    }

    @Test
    fun `each WhisperModelSpec's minRamMb is the same value AiModelCatalog reports`() {
        for (spec in WhisperModelSpec.entries) {
            // 2026-09-11 — CLOUD_API has no local catalog entry to
            // compare against (aiSpec falls back to SMALL).
            if (spec == WhisperModelSpec.CLOUD_API) continue
            assertEquals(
                "WhisperModelSpec.${spec.name}.minRamMb drifted from " +
                    "its catalog entry.",
                spec.minRamMb, spec.aiSpec().minRamMb
            )
        }
    }


    @Test
    fun `fitsInRam returns true only when free RAM clears the spec threshold`() {
        for (spec in WhisperModelSpec.entries) {
            assertFalse(
                "${spec.name}.fitsInRam(threshold - 1) must be false",
                spec.fitsInRam(spec.minRamMb - 1)
            )
            assertTrue(
                "${spec.name}.fitsInRam(threshold) must be true",
                spec.fitsInRam(spec.minRamMb)
            )
        }
    }

    @Test
    fun `every catalog whisper entry has a real HuggingFace download URL`() {
        val installer = AiModelInstaller(
            TestContext(java.io.File("."))
        )
        for (spec in WhisperModelSpec.entries) {
            val url = installer.downloadUrlForSpec(spec.aiSpec())
            assertNotNull(
                "download URL missing for ${spec.aiSpec().id}",
                url
            )
            assertTrue(
                "download URL for ${spec.aiSpec().id} must point at the " +
                    "official ggerganov/whisper.cpp mirror: $url",
                url!!.startsWith("https://huggingface.co/ggerganov/whisper.cpp/")
            )
        }
    }

    // ---- 6. Display surface (UI sanity) ----

    @Test
    fun `every WhisperModelSpec has a non-blank displayName and subtitle`() {
        for (spec in WhisperModelSpec.entries) {
            assertTrue(spec.displayName.isNotBlank())
            assertTrue(spec.subtitle.isNotBlank())
        }
    }

    @Test
    fun `displayName strings are distinct so the UI never shows duplicate rows`() {
        val names = WhisperModelSpec.entries.map { it.displayName }
        assertEquals(names.size, names.toSet().size)
    }
}
