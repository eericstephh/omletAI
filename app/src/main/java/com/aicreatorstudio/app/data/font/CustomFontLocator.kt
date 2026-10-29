package com.aicreatorstudio.app.data.font

import com.aicreatorstudio.app.model.CustomFont
import java.io.File

/**
 * The single lookup that turns a persisted `custom:<id>` font reference
 * into an absolute file path.
 *
 * ## Why a process-wide holder instead of injection everywhere
 *
 * The two surfaces that must agree on a font — the Compose preview
 * (`CaptionBitmap.rememberCaptionImageBitmap`) and the Media3 export
 * (`SubtitleBurnIn` / `TrackedOverlayRenderer`) — both funnel into
 * `CaptionRenderer.render`, which is deliberately **DI-free** (it is a
 * stateless `object` called from the render thread). Threading a
 * `CustomFontRepository` through that call chain would mean changing
 * the signature of the export graph for a lookup that is, semantically,
 * "ask the app where this font file is".
 *
 * So the repository publishes its own current list here when it emits,
 * and the renderer reads it through the injected lambda. This keeps the
 * renderer's parameter list honest (it takes a resolver function, not a
 * dependency) while giving both surfaces the *same* answer by
 * construction.
 *
 * ## Lifetime
 *
 * Populated by [DefaultCustomFontRepository] on every list emission —
 * including the constructor's initial read — so it is correct before
 * the first frame is rendered. Cleared on delete. There is exactly one
 * writer, which is what keeps it from drifting.
 *
 * ## Not a cache of `Typeface`s
 *
 * Only paths. Parsing a font is the platform's job and
 * `CaptionRenderer` keeps the resulting `Typeface`s in its own cache,
 * keyed per surface. Holding `Typeface`s globally would leak across
 * configurations for no benefit.
 */
object CustomFontLocator {

    @Volatile
    private var entries: Map<String, String> = emptyMap()

    /**
     * Replace the published list. Called by the repository whenever its
     * font list changes.
     */
    fun publish(fonts: List<CustomFont>) {
        entries = fonts.associate { it.id to it.filePath }
    }

    /** Remove one entry, e.g. after a delete. */
    fun forget(id: String) {
        val current = entries
        if (id in current) entries = current - id
    }

    /**
     * The absolute path for a `custom:<id>` font reference, or `null`
     * when the reference is unknown or its file is gone.
     *
     * Accepts either the prefixed form (`custom:abc-123`, what a style
     * persists) or a bare id, because the two callers arrive from
     * different places: a persisted style has the prefix, a
     * freshly-picked font may hand over the id directly.
     *
     * Returns `null` rather than throwing for every failure mode, and
     * verifies the file actually exists — a published path whose file
     * was deleted out from under us must fall back, not crash the
     * export.
     */
    fun pathFor(fontFamilyOrId: String?): String? {
        if (fontFamilyOrId.isNullOrBlank()) return null
        val id = CustomFont.customIdOf(fontFamilyOrId) ?: fontFamilyOrId
        val path = entries[id] ?: return null
        val file = File(path)
        if (!file.isFile || file.length() == 0L) return null
        return file.absolutePath
    }
}
