package com.aicreatorstudio.app.data.font

import com.aicreatorstudio.app.model.CustomFont
import kotlinx.coroutines.flow.Flow

/**
 * The user's imported fonts: where they are stored, and how the UI
 * learns about them.
 *
 * ## Why this is an interface
 *
 * `EditorViewModel` takes it as a constructor dependency, and
 * `EditorViewModel` is exercised by JVM tests. The real implementation
 * needs a `Context` (for `filesDir`) and the Android `Typeface` loader
 * (for reading a family name out of the font tables), and this project
 * has **no Robolectric** — so a concrete class here would make those
 * tests unconstructible. The interface is what keeps them possible.
 *
 * This mirrors the established pattern in this codebase:
 * [com.aicreatorstudio.app.data.AppUpdatePreferences] →
 * `DefaultAppUpdatePreferences`, and
 * [com.aicreatorstudio.app.video.ProjectRepository] →
 * `RoomProjectRepository` — consumer depends on the interface, the
 * concrete class is bound in `di/AppBindings.kt`, and tests use an
 * in-memory fake without going through Hilt.
 *
 * ## Contract
 *
 * All methods are **total**: they never throw. An import that cannot
 * succeed returns `null`; a delete of an unknown id is a no-op. This is
 * deliberate — the callers are a file picker and a trash icon, both of
 * which a user can trigger with a corrupt file or a stale list, and
 * neither of which should be able to crash the editor.
 *
 * [getCustomFonts] emits the current list immediately on collection and
 * again after every successful import or delete, so a picker bound to
 * it updates without polling.
 */
interface CustomFontRepository {

    /**
     * Every successfully imported font, newest first.
     *
     * Newest-first is a deliberate ordering: a user who has just
     * imported a font expects it at the top of their list, not buried
     * among older imports. Bundled [com.aicreatorstudio.app.model.PersianFont]
     * families are **not** included — this is only what the user added.
     */
    fun getCustomFonts(): Flow<List<CustomFont>>

    /**
     * Copy the font at [uri] into app-internal storage and register it.
     *
     * Steps, in order, each of which can fail cleanly:
     *  1. resolve a display name (the font's own family name if the
     *     tables are parseable, else the sanitised filename);
     *  2. copy the bytes into `filesDir/fonts/<uuid>.<ext>`;
     *  3. verify the copy actually looks like a font (sfnt sniff) and
     *     that the platform can build a `Typeface` from it — a file
     *     that fails either check is **deleted again**, so a rejected
     *     import leaves nothing behind;
   *  4. append the metadata entry and emit.
     *
     * @return the new [CustomFont], or `null` when the file is not a
     *   usable font (wrong type, unreadable, corrupt) — the caller
     *   shows «فایل فونت معتبر نیست» and nothing is persisted.
     */
    suspend fun importFont(uri: String, displayNameHint: String? = null): CustomFont?

    /**
     * Remove the font with [id] and delete its file.
     *
     * A no-op when [id] is unknown. Deleting the file is best-effort:
     * if the metadata removal succeeds but unlinking fails, the font is
     * still gone from the user's list (the visible outcome), and the
     * orphaned file is reaped by [pruneOrphans] on the next start.
     *
     * @return the [CustomFont] that was removed, or `null` if there was
     *   nothing to remove. Returning the removed record (rather than
     *   `Unit`) is what lets the caller decide whether an active style
     *   needs to fall back to the default family.
     */
    suspend fun deleteCustomFont(id: String): CustomFont?

    /**
     * The absolute path for [id], or `null` when the font is unknown
     * **or its file is missing**.
     *
     * Callers must treat `null` as "use the default family", never as
     * "crash". This is the single place that turns an id into a path,
     * so the missing-file check cannot be forgotten at one call site.
     */
    fun fileFor(id: String): String?

    /**
     * Delete any file in the font directory that no metadata entry
     * claims. Cheap, idempotent, and safe to call on every start —
     * it is the counterpart to "delete the record, then try to unlink"
     * in [deleteCustomFont], which guarantees an orphan cannot survive
     * indefinitely.
     */
    suspend fun pruneOrphans()
}
