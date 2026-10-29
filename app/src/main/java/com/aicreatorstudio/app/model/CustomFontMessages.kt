package com.aicreatorstudio.app.model

/**
 * User-facing Persian messages for the custom-font importer.
 *
 * ## Why these live in `model` as constants
 *
 * Same reason as [SubtitleMessages]: `EditorViewModel` is where these
 * strings are *used*, but Persian literals written inline into a
 * 5,000-line file have already been silently corrupted to ASCII `?` runs
 * twice in this project. Holding them here means
 *
 *  1. they are plain `String`s with no `android.*` dependency, so a JVM
 *     test can assert they are real Persian, and
 *  2. [SubtitleMessages.assertNoMojibake] — the project's existing guard —
 *     can cover them without a second implementation.
 *
 * The 2026-10-11 rule stands: **a user-facing message must never be a run
 * of question marks.**
 */
object CustomFontMessages {

    /**
     * An import succeeded. Shown in the confirmation banner after the
     * repository has copied the file, sniffed its sfnt header and proven
     * the platform can turn it into a `Typeface`.
     */
    const val IMPORT_SUCCESS: String = "فونت با موفقیت اضافه شد"

    /**
     * An import failed. Deliberately non-specific about *which* check
     * rejected the file: from the user's point of view a truncated
     * download, a renamed `.zip`, and an unreadable content URI are the
     * same problem — "this is not a font I can use" — and naming an
     * internal stage would only invite a support question we cannot
     * answer from the UI.
     */
    const val IMPORT_FAILURE: String = "فایل فونت معتبر نیست"

    /**
     * The font was removed and one or more captions referenced it, so
     * they were reverted to the default Persian family.
     *
     * This is a *confirmation*, not a warning: the fallback is the safe
     * behaviour the user expects, and saying so prevents "did I just lose
     * my text styling?" doubt.
     */
    const val DELETE_FALLBACK_APPLIED: String =
        "فونت حذف شد و متنهای مرتبط به فونت پیشفرض بازگشتند"

    /** The font was removed and nothing referenced it. */
    const val DELETE_SUCCESS: String = "فونت حذف شد"
}
