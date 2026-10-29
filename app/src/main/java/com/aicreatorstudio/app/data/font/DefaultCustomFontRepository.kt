package com.aicreatorstudio.app.data.font

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import com.aicreatorstudio.app.model.CustomFont
import com.aicreatorstudio.app.model.CustomFontFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real, persisted [CustomFontRepository].
 *
 * Font files live in `filesDir/fonts/`, metadata in
 * `filesDir/fonts/index.json`. Both are app-internal: no permission is
 * required, no other app can read them, and they are removed with the
 * app — which is the honest lifetime for a font the user imported for
 * this app.
 *
 * Named `Default…` to match
 * [com.aicreatorstudio.app.data.DefaultAppUpdatePreferences]. Bound to
 * the interface in `di/AppBindings.kt`.
 *
 * ## Why the metadata cache is a `StateFlow` loaded eagerly
 *
 * [getCustomFonts] must emit immediately on collection — a font picker
 * bound to it would otherwise flash empty and then fill in, and a
 * `MutableStateFlow` that starts empty and is filled by a coroutine has
 * exactly that race. The list is small (a user's imported fonts) and
 * reading one JSON file is cheap, so the constructor loads it
 * synchronously and the flow is correct from the first composition.
 *
 * ## What this class deliberately does NOT decide
 *
 * Sanitisation, filename derivation, magic-byte sniffing, and JSON
 * shape all live in pure objects ([CustomFontFiles],
 * [CustomFontMetadataJson]) that are unit-tested directly. This class
 * is the thin Android edge: it moves bytes and calls the platform
 * `Typeface` loader. Keeping it thin is what makes the *decisions*
 * testable in a project with no Robolectric.
 *
 * ## Failure policy
 *
 * Every public method is total. An import that produces a file the
 * platform cannot load deletes that file again and returns `null`, so a
 * rejected import leaves **no trace** — no orphan, no metadata entry,
 * and no half-registered font that would render as boxes.
 */
@Singleton
class DefaultCustomFontRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : CustomFontRepository {

    private val fontDir: File
        get() = File(context.filesDir, CustomFontFiles.FONT_DIR_NAME)

    private val indexFile: File
        get() = File(fontDir, INDEX_FILE_NAME)

    /**
     * Eagerly loaded in the constructor so the first emission is
     * correct. See the class KDoc.
     */
    private val _fonts: MutableStateFlow<List<CustomFont>> =
        MutableStateFlow(readIndex().also { CustomFontLocator.publish(it) })

    override fun getCustomFonts(): StateFlow<List<CustomFont>> = _fonts.asStateFlow()

    override suspend fun importFont(uri: String, displayNameHint: String?): CustomFont? =
        withContext(Dispatchers.IO) {
            val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return@withContext null
            val dir = ensureFontDir() ?: return@withContext null

            val id = UUID.randomUUID().toString()
            val displayName = displayNameHint
                ?.let { CustomFontFiles.displayNameFrom(it) }
                ?: queryDisplayName(parsed)
                    ?.let { CustomFontFiles.displayNameFrom(it) }
                ?: CustomFontFiles.UNTITLED_NAME

            // The extension is taken from the provider's name when it
            // is one we support, else defaulted to ttf. It is never
            // taken from the content URI's path, and it is validated
            // again inside storageFileNameFor.
            val extension = queryDisplayName(parsed)
                ?.let { CustomFontFiles.extensionOf(it) }
                ?.takeIf { it in CustomFontFiles.SUPPORTED_EXTENSIONS }
                ?: "ttf"

            val target = File(dir, CustomFontFiles.storageFileNameFor(id, extension))

            // 1. Copy.
            val copied = runCatching {
                context.contentResolver.openInputStream(parsed)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                    true
                } ?: false
            }.getOrDefault(false)

            if (!copied || !target.isFile || target.length() == 0L) {
                target.delete()
                return@withContext null
            }

            // 2. Sniff the header — cheap, and it catches a file that
            //    merely *pretends* to be a font by extension.
            val looksLikeFont = runCatching {
                target.inputStream().use { stream ->
                    val head = ByteArray(4)
                    val read = stream.read(head)
                    read == 4 && CustomFontFiles.isLikelyFontBytes(head)
                }
            }.getOrDefault(false)

            // 3. Ask the platform. This is the authoritative check:
            //    `createFromFile` returns null (or throws) for a
            //    truncated or unparseable font, and *this* is the
            //    condition the renderer will hit later — so verifying
            //    it now means an accepted font is one that will render.
            val loadable = looksLikeFont && canLoadTypeface(target)

            if (!loadable) {
                target.delete()
                return@withContext null
            }

            val font = CustomFont(
                id = id,
                name = displayName.ifBlank { CustomFontFiles.UNTITLED_NAME },
                filePath = target.absolutePath
            )
            _fonts.value = listOf(font) + _fonts.value
            writeIndex(_fonts.value)
            CustomFontLocator.publish(_fonts.value)
            font
        }

    override suspend fun deleteCustomFont(id: String): CustomFont? = withContext(Dispatchers.IO) {
        val removed = _fonts.value.firstOrNull { it.id == id } ?: return@withContext null
        // Metadata first: the user's visible outcome ("it is gone from
        // my list") must not depend on an unlink succeeding.
        _fonts.value = _fonts.value.filterNot { it.id == id }
        writeIndex(_fonts.value)
        CustomFontLocator.publish(_fonts.value)
        runCatching { File(removed.filePath).delete() }
        // Any straggler is reaped by pruneOrphans on the next start.
        removed
    }

    override fun fileFor(id: String): String? {
        val font = _fonts.value.firstOrNull { it.id == id } ?: return null
        val file = File(font.filePath)
        // Missing or zero-length means "fall back to the default
        // family", not "crash". This is the only id→path conversion in
        // the app, so the check cannot be forgotten at a call site.
        if (!file.isFile || file.length() == 0L) return null
        return file.absolutePath
    }

    override suspend fun pruneOrphans() = withContext(Dispatchers.IO) {
        val dir = fontDir
        if (!dir.isDirectory) return@withContext
        val claimed = _fonts.value.map { it.filePath }.toSet()
        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            if (file.name == INDEX_FILE_NAME) return@forEach
            if (file.absolutePath in claimed) return@forEach
            runCatching { file.delete() }
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Create the font directory, or null when it cannot be made. */
    private fun ensureFontDir(): File? {
        val dir = fontDir
        if (dir.isDirectory) return dir
        return if (dir.mkdirs() || dir.isDirectory) dir else null
    }

    /**
     * Read the provider's display name for [uri].
     *
     * The `OpenableColumns.DISPLAY_NAME` query is the only reliable way
     * to learn what the user actually picked — the document URI itself
     * usually looks like `content://…/document/1234`, with no name in
     * it. Returns null when the provider does not expose one, which is
     * normal for some file managers and is why every caller treats the
     * name as optional.
     */
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
    }.getOrNull()

    /** `true` when the platform can build a `Typeface` from [file]. */
    private fun canLoadTypeface(file: File): Boolean = runCatching {
        // `createFromFile` returns a Typeface for a well-formed font and
        // throws for a truncated/garbage one. We only need "did it
        // parse", so the result is discarded — the renderer does its own
        // lazy load and keeps the Typeface it builds.
        Typeface.createFromFile(file)
        true
    }.getOrDefault(false)

    /** Read + decode the metadata index. Empty on any problem. */
    private fun readIndex(): List<CustomFont> {
        val file = indexFile
        if (!file.isFile) return emptyList()
        val json = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return runCatching {
            CustomFontMetadataJson.decode(json, fontDir.absolutePath)
        }.getOrDefault(emptyList())
    }

    /** Write the metadata index. Best-effort: a failure loses the list, not the files. */
    private fun writeIndex(fonts: List<CustomFont>) {
        runCatching {
            val dir = ensureFontDir() ?: return
            File(dir, INDEX_FILE_NAME).writeText(CustomFontMetadataJson.encode(fonts))
        }
    }

    private companion object {
        /**
         * Metadata file inside the font directory. Shares the directory
         * with the fonts so the two can never be separated by a partial
         * cleanup — `pruneOrphans` explicitly skips it.
         */
        const val INDEX_FILE_NAME = "index.json"
    }
}
