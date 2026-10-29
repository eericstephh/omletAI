package com.aicreatorstudio.app.data.font

import com.aicreatorstudio.app.model.CustomFont
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * A non-persisted [CustomFontRepository] for tests (and previews).
 *
 * Lives beside the interface, matching
 * [com.aicreatorstudio.app.data.InMemoryAppUpdatePreferences] — which
 * sits beside `AppUpdatePreferences` for the same reason, and is
 * deliberately **not** annotated `@Inject` so nothing in production can
 * reach for it by accident and silently drop the user's fonts.
 *
 * ## Fidelity to the real implementation
 *
 * The observable semantics match [DefaultCustomFontRepository] exactly:
 * newest-first ordering, `null` for an unusable import, a no-op delete
 * of an unknown id, `fileFor` returning `null` for a missing file, and
 * a `pruneOrphans` that is idempotent. A test that passes here is
 * testing the real contract rather than a permissive stand-in.
 *
 * What it does **not** do is touch the filesystem. Files are tracked as
 * a set of paths that "exist"; [addFont] seeds both the metadata entry
 * and that set, and [deleteCustomFont] clears both — so a test can
 * still exercise the "record present but file gone" path by calling
 * [simulateMissingFile], which is the one state the real repository can
 * reach (a copy interrupted by a full disk, or an external cleanup) and
 * the one most likely to be mishandled.
 *
 * [importFont] cannot work without a real `Uri` and `ContentResolver`,
 * so the fake does not pretend to. Tests that need an import call the
 * real byte-level helpers directly (`CustomFontFiles.isLikelyFontBytes`)
 * and use [addFont] to express the post-import state.
 */
class InMemoryCustomFontRepository(
    initial: List<CustomFont> = emptyList(),
    /** Directory the fake pretends fonts live in. */
    private val dirPath: String = "/fake/files/fonts"
) : CustomFontRepository {

    private val _fonts = MutableStateFlow(initial.toList())

    /** Paths the fake reports as existing. */
    private val existingFiles: MutableSet<String> = initial.map { it.filePath }.toMutableSet()

    override fun getCustomFonts(): Flow<List<CustomFont>> = _fonts.asStateFlow()

    override suspend fun importFont(uri: String, displayNameHint: String?): CustomFont? {
        // No ContentResolver here: report failure rather than inventing
        // a success the caller would then render as an empty caption.
        return null
    }

    override suspend fun deleteCustomFont(id: String): CustomFont? {
        val removed = _fonts.value.firstOrNull { it.id == id } ?: return null
        existingFiles.remove(removed.filePath)
        _fonts.value = _fonts.value.filterNot { it.id == id }
        return removed
    }

    override fun fileFor(id: String): String? {
        val font = _fonts.value.firstOrNull { it.id == id } ?: return null
        return if (font.filePath in existingFiles) font.filePath else null
    }

    override suspend fun pruneOrphans() {
        val claimed = _fonts.value.map { it.filePath }.toSet()
        // The fake has no directory listing, so "orphan" here is only
        // the reverse direction: a file with no entry. Dropping such a
        // path keeps `fileFor` honest.
        existingFiles.retainAll(claimed)
    }

    // ------------------------------------------------------------------
    // Test helpers — not part of the interface
    // ------------------------------------------------------------------

    /**
     * Register a font as though an import had succeeded. Entries are
     * prepended so the fake reproduces the real newest-first order.
     */
    fun addFont(
        id: String = UUID.randomUUID().toString(),
        name: String,
        extension: String = "ttf"
    ): CustomFont {
        // Keep the on-disk name shape real (uuid.ext) so a test can
        // assert `isStorageFileName` compatibility on the fake too.
        val fileName = "$id.$extension"
        val font = CustomFont(id = id, name = name, filePath = "$dirPath/$fileName")
        existingFiles.add(font.filePath)
        _fonts.value = listOf(font) + _fonts.value
        return font
    }

    /** The current list, for assertions that prefer reading over collecting. */
    fun snapshot(): List<CustomFont> = _fonts.value

    /**
     * Make a registered font's file disappear without touching its
     * metadata — the "record present, file gone" state that
     * [fileFor] must report as `null` and that every consumer must
     * survive.
     */
    fun simulateMissingFile(id: String) {
        _fonts.value.firstOrNull { it.id == id }?.let { existingFiles.remove(it.filePath) }
    }
}
