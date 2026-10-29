package com.aicreatorstudio.app.data.font

import com.aicreatorstudio.app.model.CustomFont
import com.aicreatorstudio.app.model.CustomFontFiles
import org.json.JSONArray
import org.json.JSONObject

/**
 * (De)serialisation of the imported-font metadata list.
 *
 * A hand-rolled JSON codec rather than a serializer dependency, matching
 * [com.aicreatorstudio.app.ai.ocr.OcrClipResultJson] and the rest of
 * this project. It is a **pure** object (no `File`, no `Context`), which
 * is the point: the encoder/decoder is the part most likely to acquire a
 * subtle bug (a dropped field, a bad default, a traversal hole), and
 * keeping it free of Android types means it can be unit-tested directly
 * on the JVM.
 *
 * ## Encoding
 *
 * `{"version":1,"fonts":[{"id":…,"name":…,"filePath":…}, …]}`
 *
 * `filePath` is stored **relative** to the font directory (just the
 * filename), not absolute. An absolute path embeds the app's data
 * directory, which is stable in practice but is not part of the API
 * contract; storing the leaf name and re-deriving the absolute path at
 * load time is what lets the repository relocate the directory without
 * a metadata migration, and makes a tampered entry that names
 * `/data/data/other.app/…` structurally impossible to honour — the
 * [decode] step re-validates every filename against
 * [CustomFontFiles.isStorageFileName] and **drops anything that does
 * not look like one of ours** rather than trusting it.
 *
 * `version` is written so a future format change can migrate instead of
 * silently misreading. An unknown/newer version decodes to an empty list
 * (fonts are re-importable; refusing to start is not an option).
 */
object CustomFontMetadataJson {

    /** Current on-disk format version. */
    const val VERSION: Int = 1

    private const val KEY_VERSION = "version"
    private const val KEY_FONTS = "fonts"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_FILE = "file"

    /**
     * Encode [fonts] to a single JSON string.
     *
     * [fontDirPath] is not written — see the class KDoc. It is accepted
     * only so the call site reads naturally and to keep the signature
     * stable if a future version does embed a base path.
     */
    fun encode(fonts: List<CustomFont>): String {
        val array = JSONArray()
        fonts.forEach { font ->
            array.put(
                JSONObject().apply {
                    put(KEY_ID, font.id)
                    put(KEY_NAME, font.name)
                    // Leaf name only — never the absolute path.
                    put(KEY_FILE, font.filePath.substringAfterLast('/'))
                }
            )
        }
        return JSONObject().apply {
            put(KEY_VERSION, VERSION)
            put(KEY_FONTS, array)
        }.toString()
    }

    /**
     * Decode [json] into a list of [CustomFont], resolving each entry's
     * `filePath` against [fontDirPath].
     *
     * Never throws: malformed JSON, a missing field, or a hostile
     * filename all reduce to "skip that entry" (or "return empty"),
     * because a broken metadata file must not brick the font picker.
     * Every entry is re-validated rather than trusted — the metadata
     * file is written by us, but it is also user-reachable on a rooted
     * device, and a traversal entry would otherwise become an arbitrary
     * file read.
     */
    fun decode(json: String?, fontDirPath: String): List<CustomFont> {
        if (json.isNullOrBlank()) return emptyList()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val version = root.optInt(KEY_VERSION, 0)
        // Unknown (including a future) version: refuse rather than guess.
        if (version != VERSION) return emptyList()
        val array = root.optJSONArray(KEY_FONTS) ?: return emptyList()

        val out = ArrayList<CustomFont>(array.length())
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val id = entry.optString(KEY_ID).takeIf { it.isNotBlank() } ?: continue
            val fileName = entry.optString(KEY_FILE).takeIf { it.isNotBlank() } ?: continue
            // Re-validate: only names this module could have produced.
            if (!CustomFontFiles.isStorageFileName(fileName)) continue
            val name = entry.optString(KEY_NAME).takeIf { it.isNotBlank() }
                ?: CustomFontFiles.UNTITLED_NAME
            out.add(
                CustomFont(
                    id = id,
                    name = name,
                    filePath = joinPath(fontDirPath, fileName)
                )
            )
        }
        return out
    }

    /**
     * Join with a single separator, tolerating a trailing one. Kept
     * local (rather than `java.io.File`) so this object stays pure and
     * a test can assert the exact string.
     */
    private fun joinPath(dir: String, name: String): String =
        dir.trimEnd('/') + "/" + name
}
