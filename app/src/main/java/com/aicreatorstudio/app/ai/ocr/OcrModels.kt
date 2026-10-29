package com.aicreatorstudio.app.ai.ocr

/**
 * AI OCR result model — everything the UI + timeline need from one
 * OCR pass over a frame or video.
 *
 * ## Coordinates
 *
 * [OcrBox.rect] is in **normalized 0..1 frame coordinates** (x, y
 * from the top-left), so the result is resolution-independent and
 * can be drawn over any preview scale directly.
 */
data class OcrAnalysisResult(
    val timestampMs: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val lines: List<OcrLine>
) {
    val isEmpty: Boolean get() = lines.isEmpty()
}

/**
 * One text line detected by OCR: the recognized text plus its
 * bounding box and the model confidence (0..1).
 */
data class OcrLine(
    val text: String,
    /** Normalized 0..1 rect: left/top/right/bottom in frame space. */
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** Tesseract word-mean confidence 0..100, normalized to 0..1. */
    val confidence: Float
) {
    val confidencePercent: Int get() = (confidence * 100).toInt().coerceIn(0, 100)
}

/**
 * A committed OCR result on a clip: the text the user approved
 * (after optional editing), the frame timestamp it came from, and
 * the frame size it was computed against. Persisted on the clip as
 * JSON so Apply/Reset work across sessions.
 */
data class OcrClipResult(
    val text: String,
    val timestampMs: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val lineCount: Int,
    /** Mean confidence of the committed lines, 0..1. */
    val confidence: Float,
    val createdAtMs: Long = System.currentTimeMillis()
)
