package com.aicreatorstudio.app.model.template

import org.json.JSONObject

/**
 * PHASE 2 — 1:1 data model of `assets/subtitle_templates.json`
 * (schema 3.0, the Source of Truth).
 *
 * Every JSON key has a field here with a SAFE DEFAULT, so older /
 * newer / hand-edited catalogs decode without crashing: unknown or
 * missing values degrade to explicit defaults that later phases map
 * onto the render engine with documented fallbacks. No Android, no
 * Compose, no Media3 — pure data, JVM unit-tested.
 *
 * Deliberately decoupled from [com.aicreatorstudio.app.model.SubtitleTemplate]:
 * the JSON catalog is the authoring format (6 independent axes);
 * engine mapping lands in a later phase.
 */
data class JsonTemplateCatalog(
    val schemaVersion: String = "",
    val templates: List<JsonTemplate> = emptyList()
)

data class JsonTemplate(
    val id: String = "",
    val nameFa: String = "",
    val nameEn: String = "",
    val category: String = "",
    val language: JsonLanguage = JsonLanguage(),
    val font: JsonFont = JsonFont(),
    val color: JsonColor = JsonColor(),
    val shadow: JsonShadow = JsonShadow(),
    val wordHighlight: JsonWordHighlight = JsonWordHighlight(),
    val motion: JsonMotion = JsonMotion(),
    val background: JsonBackground = JsonBackground()
)

data class JsonLanguage(
    val mode: String = "auto",
    val fontMapFa: String = "",
    val fontMapEn: String = "",
    val directionFa: String = "rtl",
    val directionEn: String = "ltr"
)

data class JsonFont(
    val weight: String = "regular",
    val sizeRatio: Float = 1f
)

data class JsonColor(
    val base: String = "#FFFFFF"
)

data class JsonShadow(
    /** none | outline | dropShadow | glow */
    val type: String = "none",
    val color: String? = null,
    /** outline width (sp-like units in JSON). */
    val width: Float? = null,
    /** glow radius. */
    val radius: Float? = null
)

data class JsonWordHighlight(
    /** none | colorChange | box | progressBar */
    val type: String = "none",
    val activeColor: String? = null,
    val inactiveColor: String? = null,
    val sync: String = "wordTimestamp",
    // progressBar extras
    val barColor: String? = null,
    val trackColor: String? = null,
    val barHeightRatio: Float? = null,
    val fillDirectionAware: Boolean = true,
    // colorChange extras
    val keywordScaleBoost: Float? = null,
    // box extras — 2026-10-15: the pill (activeColor) and the word's
    // TEXT may need different colors for an "inverted chip" (e.g.
    // red word on a white chip). Without this the chip and the text
    // share one color and the active word vanishes into the pill.
    val boxTextColor: String? = null,
    val note: String? = null
)

data class JsonMotionPhase(
    /** See the JSON spec (none, fadeIn, glitchIn, punchZoomOnKeyword, …). */
    val type: String = "none",
    /** seconds; null = engine default for the type. */
    val duration: Double? = null,
    val easing: String? = null,
    val charDelay: Double? = null,
    val overshoot: Double? = null
)

data class JsonMotion(
    val entry: JsonMotionPhase = JsonMotionPhase(),
    val exit: JsonMotionPhase = JsonMotionPhase()
)

data class JsonBackground(
    /** none | solidBox | gradientBox | blurVideo | letterboxBars */
    val type: String = "none",
    val color: String? = null,
    val cornerRadius: Float? = null,
    val paddingRatio: Float? = null,
    val colors: List<String> = emptyList(),
    val angle: Float? = null,
    val blurRadius: Float? = null,
    val overlayOpacity: Float? = null
)

/**
 * Tolerant parser for the catalog JSON. Never throws: malformed
 * documents yield an empty catalog, entries with a blank `id` are
 * skipped (counted in [ParseResult.skipped]), everything else
 * decodes with safe defaults.
 */
object JsonTemplateParser {

    data class ParseResult(
        val catalog: JsonTemplateCatalog,
        /** ids that were dropped (blank at the moment). */
        val skipped: List<String> = emptyList()
    )

    fun parse(json: String): ParseResult = runCatching {
        val root = JSONObject(json)
        val schema = root.optString("schemaVersion", "")
        val arr = root.optJSONArray("templates")
        val templates = ArrayList<JsonTemplate>()
        val skipped = ArrayList<String>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "").trim()
                if (id.isEmpty()) {
                    skipped.add("index_$i")
                    continue
                }
                templates.add(parseTemplate(o, id))
            }
        }
        ParseResult(JsonTemplateCatalog(schema, templates), skipped)
    }.getOrElse {
        ParseResult(JsonTemplateCatalog(), listOf("document"))
    }

    private fun parseTemplate(o: JSONObject, id: String): JsonTemplate {
        val name = o.optJSONObject("name")
        val lang = o.optJSONObject("language")
        val fontMap = lang?.optJSONObject("fontMap")
        val dirMap = lang?.optJSONObject("directionMap")
        return JsonTemplate(
            id = id,
            nameFa = name?.optString("fa", "")?.trim().orEmpty(),
            nameEn = name?.optString("en", "")?.trim().orEmpty(),
            category = o.optString("category", "").trim(),
            language = JsonLanguage(
                mode = lang?.optString("mode", "auto")?.trim().orEmpty().ifBlank { "auto" },
                fontMapFa = fontMap?.optString("fa", "")?.trim().orEmpty(),
                fontMapEn = fontMap?.optString("en", "")?.trim().orEmpty(),
                directionFa = dirMap?.optString("fa", "rtl")?.trim().orEmpty().ifBlank { "rtl" },
                directionEn = dirMap?.optString("en", "ltr")?.trim().orEmpty().ifBlank { "ltr" }
            ),
            font = o.optJSONObject("font")?.let { f ->
                JsonFont(
                    weight = f.optString("weight", "regular").trim().ifBlank { "regular" },
                    sizeRatio = f.optDouble("sizeRatio", 1.0).toFloat()
                )
            } ?: JsonFont(),
            color = o.optJSONObject("color")?.let { c ->
                JsonColor(base = c.optString("base", "#FFFFFF").trim().ifBlank { "#FFFFFF" })
            } ?: JsonColor(),
            shadow = o.optJSONObject("shadow")?.let { s ->
                JsonShadow(
                    type = s.optString("type", "none").trim().ifBlank { "none" },
                    color = s.optStringOrNull("color"),
                    width = s.optFloatOrNull("width"),
                    radius = s.optFloatOrNull("radius")
                )
            } ?: JsonShadow(),
            wordHighlight = o.optJSONObject("wordHighlight")?.let { w ->
                JsonWordHighlight(
                    type = w.optString("type", "none").trim().ifBlank { "none" },
                    activeColor = w.optStringOrNull("activeColor"),
                    inactiveColor = w.optStringOrNull("inactiveColor"),
                    sync = w.optString("sync", "wordTimestamp").trim().ifBlank { "wordTimestamp" },
                    barColor = w.optStringOrNull("barColor"),
                    trackColor = w.optStringOrNull("trackColor"),
                    barHeightRatio = w.optFloatOrNull("barHeightRatio"),
                    fillDirectionAware = w.optBoolean("fillDirectionAware", true),
                    keywordScaleBoost = w.optFloatOrNull("keywordScaleBoost"),
                    boxTextColor = w.optStringOrNull("boxTextColor"),
                    note = w.optStringOrNull("note")
                )
            } ?: JsonWordHighlight(),
            motion = o.optJSONObject("motion")?.let { m ->
                JsonMotion(
                    entry = parsePhase(m.optJSONObject("entry")),
                    exit = parsePhase(m.optJSONObject("exit"))
                )
            } ?: JsonMotion(),
            background = o.optJSONObject("background")?.let { b ->
                JsonBackground(
                    type = b.optString("type", "none").trim().ifBlank { "none" },
                    color = b.optStringOrNull("color"),
                    cornerRadius = b.optFloatOrNull("cornerRadius"),
                    paddingRatio = b.optFloatOrNull("paddingRatio"),
                    colors = b.optJSONArray("colors")?.let { arr ->
                        (0 until arr.length()).mapNotNull {
                            arr.optString(it, "").trim().takeIf { s -> s.isNotEmpty() }
                        }
                    } ?: emptyList(),
                    angle = b.optFloatOrNull("angle"),
                    blurRadius = b.optFloatOrNull("blurRadius"),
                    overlayOpacity = b.optFloatOrNull("overlayOpacity")
                )
            } ?: JsonBackground()
        )
    }

    private fun parsePhase(o: JSONObject?): JsonMotionPhase {
        if (o == null) return JsonMotionPhase()
        return JsonMotionPhase(
            type = o.optString("type", "none").trim().ifBlank { "none" },
            duration = if (o.has("duration")) o.optDouble("duration") else null,
            easing = o.optStringOrNull("easing"),
            charDelay = if (o.has("charDelay")) o.optDouble("charDelay") else null,
            overshoot = if (o.has("overshoot")) o.optDouble("overshoot") else null
        )
    }

    private fun JSONObject.optStringOrNull(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).trim().takeIf { it.isNotEmpty() }
    }

    private fun JSONObject.optFloatOrNull(name: String): Float? {
        if (!has(name) || isNull(name)) return null
        return optDouble(name).toFloat()
    }
}
