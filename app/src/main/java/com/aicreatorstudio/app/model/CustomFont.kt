package com.aicreatorstudio.app.model

/**
 * A font the **user** imported from device storage — as opposed to the
 * bundled OFL families in [PersianFont], which ship inside the APK and
 * can never be removed.
 *
 * ## Why this is separate from [PersianFont]
 *
 * [PersianFont] is a compile-time enum: its entries map to
 * `res/font` resources, its ids are the enum names, and the whole set
 * is immutable. A user-imported font has none of those properties — it
 * is discovered at runtime, it lives as a file in app storage, and it
 * can be deleted. Modelling it as a second enum entry (the tempting
 * shortcut) would mean either a mutable enum or a fake `res/font`
 * resource per import, both of which break the export renderer's
 * resource lookup and make saved projects unreproducible.
 *
 * So the two coexist:
 *
 *  * **[PersianFont]** answers "which bundled family?" — resolved to a
 *    `res/font` resource id, and the value persisted on a
 *    [com.aicreatorstudio.app.model.SubtitleStyle] is the enum's
 *    `name`.
 *  * **[CustomFont]** answers "which imported file?" — resolved to an
 *    absolute path, and the value persisted on a style is the id
 *    prefixed with [ID_PREFIX].
 *
 * A style therefore carries a single opaque `fontFamily` string that is
 * unambiguous: `"VAZIR"` is a bundled family, `"custom:<uuid>"` is an
 * import. [isCustomFontId] is the discriminator.
 *
 * ## Lifetime and honesty about failure
 *
 * [filePath] is an absolute path into app-internal storage. Files there
 * survive app restarts but **not** a reinstall or "clear data", and a
 * user can revoke nothing (internal storage needs no permission) — but
 * the file *can* be missing if storage was full mid-copy, or if a
 * future cleanup removed it. Consumers must therefore treat a missing
 * or unreadable file as a **fallback**, never as a crash: see
 * `CustomFontRepository.fileFor` and the resolution in
 * `CaptionRenderer.resolveTypeface`. A saved project that references a
 * deleted font renders in the default Persian family instead of
 * failing to export.
 */
data class CustomFont(
    val id: String,
    val name: String,
    val filePath: String,
    val isCustom: Boolean = true
) {
    /**
     * The value written into `SubtitleStyle.fontFamily` /
     * `TextOverlay.fontFamily` to select this font. Persisting the bare
     * [id] would be ambiguous with a [PersianFont] enum name, so the
     * prefix is what makes the namespace collision impossible.
     */
    val selectionId: String get() = ID_PREFIX + id

    companion object {
        /** Namespace marker for a custom font reference. */
        const val ID_PREFIX: String = "custom:"

        /**
         * `true` when a persisted `fontFamily` string refers to a
         * custom font rather than a bundled [PersianFont].
         *
         * Prefix-based so an old project (which can only contain enum
         * names) is unaffected, and a future import scheme could add a
         * second prefix without disturbing this one.
         */
        fun isCustomFontId(fontFamily: String?): Boolean =
            fontFamily != null && fontFamily.startsWith(ID_PREFIX)

        /** The bare id inside a `custom:` reference, or null. */
        fun customIdOf(fontFamily: String?): String? =
            if (isCustomFontId(fontFamily)) fontFamily!!.removePrefix(ID_PREFIX) else null
    }
}

/**
 * Pure, JVM-testable font-file helpers: name sanitisation and the
 * import-path contract.
 *
 * Deliberately free of `android.*` so it can be unit-tested directly —
 * the project has no Robolectric, and `unitTests.isReturnDefaultValues
 * = true` makes `android.*` calls silently return `0`/`null`, so a test
 * touching `File`-adjacent Android APIs could pass for the wrong
 * reason. Everything here is plain `String` in, plain `String` out.
 */
object CustomFontFiles {

    /** Extensions a user may import. Lower-case, no dot. */
    val SUPPORTED_EXTENSIONS: Set<String> = setOf("ttf", "otf")

    /** Directory under `filesDir` that holds every imported font. */
    const val FONT_DIR_NAME: String = "fonts"

    /** Fallback display name when a filename yields nothing usable. */
    const val UNTITLED_NAME: String = "فونت سفارشی"

    /**
     * The file extension of [fileName] in lower case, or `""` when
     * there is none. Never returns a dotted form.
     */
    fun extensionOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot < 0 || dot == fileName.length - 1) return ""
        return fileName.substring(dot + 1).lowercase()
    }

    /**
     * `true` when the name looks like a font we can hand to
     * `Typeface.createFromFile`.
     *
     * **Extension-only, and knowingly so.** A real check would parse
     * the sfnt header (byte 0..3 must be `0x00010000`, `OTTO`, `true`,
     * or `ttcf`). That is exactly what [isLikelyFontBytes] does, and the
     * repository *does* run it after copying — but it needs the bytes,
     * which a purely name-based pre-check does not have. This function
     * exists to reject the obviously wrong file (a `.jpg`, a `.txt`) and
     * to give the picker its MIME filter. The authoritative check is the
     * byte sniff.
     */
    fun hasSupportedExtension(fileName: String): Boolean =
        extensionOf(fileName) in SUPPORTED_EXTENSIONS

    /**
     * Sniff the first bytes of [header] for an sfnt signature.
     *
     * Every TTF/OTF/TTC begins with one of:
     *  * `00 01 00 00` — TrueType outlines
     *  * `4F 54 54 4F` (`OTTO`) — CFF/PostScript outlines
     *  * `74 72 75 65` (`true`) — legacy Apple TrueType
     *  * `74 74 63 66` (`ttcf`) — a TrueType Collection
     *
     * A collection is accepted because `Typeface.createFromFile`
     * accepts one (it takes face 0), and rejecting it would be a
     * capability regression for no benefit.
     *
     * Pure: takes a byte array, returns a boolean. The repository reads
     * the file to get it; tests pass the four magic numbers directly.
     */
    fun isLikelyFontBytes(header: ByteArray): Boolean {
        if (header.size < 4) return false
        val b = header
        fun at(i: Int) = b[i].toInt() and 0xFF
        return when {
            at(0) == 0x00 && at(1) == 0x01 && at(2) == 0x00 && at(3) == 0x00 -> true
            at(0) == 0x4F && at(1) == 0x54 && at(2) == 0x54 && at(3) == 0x4F -> true // OTTO
            at(0) == 0x74 && at(1) == 0x72 && at(2) == 0x75 && at(3) == 0x65 -> true // true
            at(0) == 0x74 && at(1) == 0x74 && at(2) == 0x63 && at(3) == 0x66 -> true // ttcf
            else -> false
        }
    }

    /**
     * Turn a user-supplied filename into a safe **display name**.
     *
     * Strips the extension and any directory component, replaces
     * characters that are unsafe or visually confusing in a UI label,
     * and collapses whitespace. Persian text passes through untouched —
     * the vast majority of imported Persian fonts have Persian names
     * (`وزیرمتن.ttf`), and mangling them would defeat the purpose.
     *
     * Notably this is a *display* sanitiser, not a *path* sanitiser:
     * the on-disk name is never derived from user input at all (see
     * [storageFileNameFor]), which is what makes path traversal
     * structurally impossible rather than merely filtered.
     */
    fun displayNameFrom(fileName: String): String {
        val bare = fileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
        val withoutExt = if (extensionOf(bare).isEmpty()) bare else bare.substringBeforeLast('.')
        val cleaned = withoutExt
            // Path-ish and control characters: never meaningful in a
            // font's family name, always suspicious in one.
            .map { ch ->
                when {
                    ch.code < 0x20 -> ' '
                    ch in "/\\:*?\"<>|" -> ' '
                    ch == '\u0000' -> ' '
                    else -> ch
                }
            }
            .joinToString("")
            // Collapse runs of whitespace and trim the ends.
            .trim()
            .replace(Regex("\\s+"), " ")
        return cleaned.ifBlank { UNTITLED_NAME }
    }

    /**
     * The deterministic on-disk filename for font [id].
     *
     * **`id` is a UUID we generate, never user input.** Deriving the
     * filename from the UUID is what makes directory traversal
     * impossible by construction: an imported `../../databases/x.ttf`
     * or `..;rm -rf.ttf` cannot influence the path, because none of it
     * reaches here. The extension is supplied by the caller from the
     * sniffed/declared type and is also validated against
     * [SUPPORTED_EXTENSIONS].
     *
     * Falls back to `ttf` for an unrecognised extension so a caller
     * cannot be tricked into writing a `.png` into the font directory.
     */
    fun storageFileNameFor(id: String, extension: String): String {
        val safeExt = extension.lowercase()
            .takeIf { it in SUPPORTED_EXTENSIONS }
            ?: "ttf"
        return "$id.$safeExt"
    }

    /**
     * `true` when [candidateName] is one this module could have
     * produced — `<uuid>.<ttf|otf>`.
     *
     * Used to decide whether a stray file in the font directory is ours
     * to clean up, and to reject a tampered metadata file that names
     * something outside the directory. This is the *inverse* of
     * [storageFileNameFor], and the pair is tested as a round trip.
     */
    fun isStorageFileName(candidateName: String): Boolean {
        if (candidateName.contains('/') || candidateName.contains('\\')) return false
        val dot = candidateName.lastIndexOf('.')
        if (dot <= 0) return false
        val stem = candidateName.substring(0, dot)
        val ext = candidateName.substring(dot + 1).lowercase()
        if (ext !in SUPPORTED_EXTENSIONS) return false
        // The stem must look like the UUID we mint — 8-4-4-4-12 hex.
        return UUID_REGEX.matches(stem)
    }

    /** 8-4-4-4-12 hex, case-insensitive. */
    private val UUID_REGEX =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
}
