package com.aicreatorstudio.app.ai

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE B1.6 — locks the network-fallback download URL for
 * every Whisper model in the catalog.
 *
 * The previous production code carried a hardcoded `when` over
 * `spec.id` with three branches (Tiny/Small/Medium).
 * PHASE B1.5 added Small/Medium, and PHASE B1.6 replaced
 * them with Medium + Large-v3. The audit exposes
 * [AiModelInstaller.downloadUrlForSpec] so every size can
 * be checked against a real upstream URL.
 *
 * What this test pins:
 *
 *   - The two Whisper sizes (MEDIUM, LARGE_V3) resolve to
 *     a HuggingFace URL on the official
 *     `ggerganov/whisper.cpp` mirror. The mirror is the same
 *     publisher we bundle the assets from, so the SHA-256 in
 *     `models.lock.json` is authoritative for the bytes the
 *     user will receive.
 *   - The removed Tiny + Small ids return null (no fake
 *     placeholder URL), so the install path correctly
 *     short-circuits to `AiFailure.MODEL_DOWNLOAD_FAILED` if
 *     a stale code path ever references the old ids.
 *   - The file name in the URL matches the spec's
 *     [CatalogEntry.fileName]. A URL/file mismatch means the
 *     SHA the user is verifying against corresponds to a
 *     different file than the one we will actually download.
 *   - Unknown / non-Whisper specs return null (no fake
 *     placeholder URL), so the install path correctly
 *     short-circuits to `AiFailure.MODEL_DOWNLOAD_FAILED`.
 *
 * The test deliberately uses a substring check
 * (`huggingface.co/ggerganov/whisper.cpp`) instead of a full
 * URL match, so a future refactor that switches the resolver to
 * a different mirror with a longer path (e.g. with a revision
 * SHA) does not require updating this test.
 */
class WhisperDownloadUrlTest {

    private val installer: AiModelInstaller by lazy {
        AiModelInstaller(TestContext(java.io.File(".")))
    }

    @Test
    fun `whisper-small has a real upstream URL on the official mirror`() {
        val url = installer.downloadUrlForSpec(AiModelCatalog.WHISPER_SMALL)
        assertNotNull(
            "AiModelCatalog.WHISPER_SMALL must have a non-null download URL",
            url
        )
        assertTrue(
            "Small URL must point at ggerganov/whisper.cpp mirror, got: $url",
            url!!.contains("huggingface.co/ggerganov/whisper.cpp")
        )
        assertTrue(
            "Small URL must reference ggml-small.bin, got: $url",
            url.endsWith("ggml-small.bin")
        )
    }


    @Test
    fun `removed Tiny catalog entry has no download URL - Small is restored`() {
        // 2026-09-08 SPEED RESTORE — Tiny stays removed (no download
        // URL must resolve for it), but the f16 Small entry is back
        // as the speed default and MUST resolve to its official URL.
        val tiny = CatalogEntry(
            id = "whisper-tiny",
            displayName = "Whisper Tiny",
            dirName = "whisper-ggml-tiny",
            fileName = "ggml-tiny.bin",
            minSizeBytes = 70_000_000L
        )
        assertNull(
            "Removed Tiny must not have a download URL",
            installer.downloadUrlForSpec(tiny)
        )
        val smallUrl = installer.downloadUrlForSpec(AiModelCatalog.WHISPER_SMALL)
        assertNotNull(
            "Restored Small (2026-09-08) must have a download URL",
            smallUrl
        )
        assertTrue(
            "Small URL must reference ggml-small.bin, got: $smallUrl",
            smallUrl!!.endsWith("ggml-small.bin")
        )
    }

    @Test
    fun `URL file name matches CatalogEntry file name for every Whisper spec`() {
        // The SHAs in models.lock.json are pinned to a
        // specific upstream file. A URL whose last path
        // segment does not match the CatalogEntry.fileName
        // would mean the SHA check would be verifying the
        // wrong bytes.
        // 2026-09-24 — WHISPER_MEDIUM was removed from AiModelCatalog
        // on 2026-09-21 (users migrated to Small). SMALL is the only
        // local Whisper entry left, so it is the only one to pin.
        for (spec in listOf(
            AiModelCatalog.WHISPER_SMALL
        )) {
            val url = installer.downloadUrlForSpec(spec)
            assertNotNull("URL for ${spec.id} must be non-null", url)
            val last = url!!.substringAfterLast('/')
            assertTrue(
                "URL for ${spec.id} must end with ${spec.fileName}, " +
                    "got URL=$url last=$last",
                last == spec.fileName
            )
        }
    }

    @Test
    fun `unknown spec id returns null instead of a fake URL`() {
        // Synthesise a spec the catalog doesn't know about;
        // the resolver must return null (the install path
        // will then report MODEL_DOWNLOAD_FAILED with a clear
        // message). Returning a placeholder URL would silently
        // download a wrong file and trip the SHA check
        // much later.
        val unknown = CatalogEntry(
            id = "whisper-fake",
            displayName = "Fake",
            dirName = "whisper-ggml-fake",
            fileName = "ggml-fake.bin",
            minSizeBytes = 1L
        )
        assertNull(installer.downloadUrlForSpec(unknown))
    }
}
