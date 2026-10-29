package com.aicreatorstudio.app.ai.ocr

/**
 * Persian-aware text normalizer for OCR output.
 *
 * Tesseract frequently emits Arabic-script code points for Persian
 * glyphs (the LSTM models share the Arabic Unicode block). This
 * normalizer folds the two known confusable pairs into their
 * Persian forms and repairs Arabic Tatweel / presentation-form
 * artifacts, without touching word order or ZWNJ (نیم‌فاصله):
 *
 *  - Arabic Yeh ي (U+064A) → Persian ی (U+06CC)
 *  - Arabic Kaf ك (U+0643) → Persian ک (U+06A9)
 *  - Arabic presentation forms (U+FB50..U+FDFF, U+FE70..U+FEFF)
 *    → canonical Arabic letters via NFKC folding
 *  - Tatweel ـ (U+0640) stripped
 *  - ZWNJ (U+200C) preserved verbatim
 *  - Arabic-Indic digits ۰-۹ kept (both Persian and Arabic ranges)
 *  - Whitespace collapsed
 */
object OcrTextNormalizer {

    private const val ARABIC_YEH = 'ي'
    private const val PERSIAN_YEH = 'ی'
    private const val ARABIC_KAF = 'ك'
    private const val PERSIAN_KEHEH = 'ک'
    private const val TATWEEL = 'ـ'
    private const val ZWNJ = '‌'

    /** Fold one OCR word into its canonical Persian-friendly form. */
    fun normalizeWord(input: String): String {
        if (input.isBlank()) return ""
        val first = StringBuilder(input.length)
        for (c in input) {
            first.append(
                when (c) {
                    ARABIC_YEH -> PERSIAN_YEH
                    ARABIC_KAF -> PERSIAN_KEHEH
                    TATWEEL -> ""
                    else -> c
                }
            )
        }
        val folded = java.text.Normalizer.normalize(
            first.toString(), java.text.Normalizer.Form.NFKC
        )
        val out = StringBuilder(folded.length)
        for (c in folded) {
            when (c) {
                ARABIC_YEH -> out.append(PERSIAN_YEH)
                ARABIC_KAF -> out.append(PERSIAN_KEHEH)
                TATWEEL -> Unit
                ZWNJ -> out.append(ZWNJ)
                '\t', '\n', '\r' -> out.append(' ')
                else -> out.append(c)
            }
        }
        return out.toString().replace(Regex(" {2,}"), " ").trim()
    }
}
