package com.aicreatorstudio.app.ai.ocr

import org.json.JSONObject

/**
 * JSON (de)serialization for [OcrClipResult] — the committed OCR
 * output persisted on a clip's `ocrResult` field. Deliberately a
 * hand-rolled two-field-per-line encoder (the project uses the same
 * pattern elsewhere) so no extra serializer dependency is pulled in.
 */
object OcrClipResultJson {

    fun encode(result: OcrClipResult): String {
        val o = JSONObject()
        o.put("text", result.text)
        o.put("timestampMs", result.timestampMs)
        o.put("sourceWidth", result.sourceWidth)
        o.put("sourceHeight", result.sourceHeight)
        o.put("lineCount", result.lineCount)
        o.put("confidence", result.confidence.toDouble())
        o.put("createdAtMs", result.createdAtMs)
        return o.toString()
    }

    fun decode(json: String?): OcrClipResult? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(json)
            OcrClipResult(
                text = o.getString("text"),
                timestampMs = o.getLong("timestampMs"),
                sourceWidth = o.getInt("sourceWidth"),
                sourceHeight = o.getInt("sourceHeight"),
                lineCount = o.getInt("lineCount"),
                confidence = o.getDouble("confidence").toFloat(),
                createdAtMs = o.optLong("createdAtMs", 0L)
            )
        }.getOrNull()
    }
}
