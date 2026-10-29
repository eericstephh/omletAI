package com.aicreatorstudio.app.model

/**
 * User-facing Persian messages for the offline subtitle pipeline.
 *
 * ## Why these live in `model` as constants
 *
 * `EditorViewModel` previously carried these as inline string literals,
 * and several had been **corrupted to literal `?` characters at write
 * time** — the user saw `"??? ???????? ???? ????? ???? ?????"` instead
 * of an explanation. The same fault had already been diagnosed once for
 * [SUBTITLE_NO_SOURCE_MESSAGE_FA] and fixed by extracting it to a
 * constant; the rest were left inline and stayed broken.
 *
 * Defining them here has three benefits:
 *  1. They are plain `String`s with no `android.*` dependency, so a JVM
 *     test can assert they are real Persian and contain no `?` runs.
 *  2. A single place to audit, instead of string literals scattered
 *     across a 5,000-line ViewModel.
 *  3. The corruption cannot silently recur unnoticed.
 *
 * The 2026-10-11 rule this file encodes: **a user-facing message must
 * never be a run of question marks.** [assertNoMojibake] is the guard.
 */
object SubtitleMessages {

    /** Generation produced no words at all (silence, or a failed decode). */
    const val NO_SPEECH_FOUND_FA: String =
        "صدای قابل رونویسی در این ویدیو پیدا نشد"

    /** Words were produced but no overlay could be built from them. */
    const val NO_OVERLAYS_PRODUCED_FA: String =
        "زیرنویسی از این ویدیو ساخته نشد"

    /** Model install or native load failed. `%s` is the underlying cause. */
    fun modelInitFailed(detail: String): String =
        "نصب یا بارگذاری مدل ناموفق بود: $detail"

    /** Transcription itself failed. `%s` is the underlying cause. */
    fun transcriptionFailed(detail: String): String =
        "رونویسی صدا ناموفق بود: $detail"

    /** SRT export failed. `%s` is the underlying cause. */
    fun srtExportFailed(detail: String): String =
        "ذخیرهٔ فایل SRT ناموفق بود: $detail"

    /** No clip was selected and the project has no media at all. */
    const val NO_MEDIA_SELECTED_FA: String =
        "هیچ ویدیویی برای این کار انتخاب نشده است"

    /** Cloud model selected but the model picker/install could not proceed. */
    fun cloudModelUnavailable(detail: String): String =
        "مدل ابری در دسترس نیست: $detail"

    /** Generic subtitle-generation failure wrapping an inner message. */
    fun subtitleError(detail: String): String = "خطای زیرنویس: $detail"

    /**
     * AI-tool panel status strings (Background Remove, Auto Frame,
     * Voice Clean, Upscale).
     *
     * ## 2026-10-11 — same corruption, second location
     *
     * The subtitle pipeline was not the only place: the AI-tool status
     * messages in `EditorViewModel` had been corrupted to ASCII `?`
     * runs the same way. They are grouped here for the same reasons —
     * one auditable place, JVM-testable, and the
     * [assertNoMojibake] guard can cover them.
     *
     * These describe install phases, so the wording mirrors the phase
     * machine: download → verify → install → ready.
     */
    object AiTool {
        /** Weights are being fetched. */
        const val DOWNLOADING_FA: String = "در حال دانلود مدل…"

        /** Downloaded bytes are being checksum-verified. */
        const val VERIFYING_FA: String = "در حال بررسی صحت فایل…"

        /** Verified bytes are being installed into place. */
        const val INSTALLING_FA: String = "در حال نصب مدل…"

        /** The model is installed and the tool can run offline. */
        const val READY_FA: String = "مدل آماده است"

        /** The install failed with no detail from the underlying layer. */
        const val FAILED_FA: String = "نصب مدل ناموفق بود"

        /** A run was requested but the timeline has no selected clip. */
        const val NO_CLIP_SELECTED_FA: String = "کلیپی برای پردازش انتخاب نشده است"

        /** The user cancelled an in-flight tool run. */
        const val CANCELLED_FA: String = "لغو شد"

        /** A run finished successfully and produced no summary text. */
        const val COMPLETED_FA: String = "انجام شد"

        /** A run failed with no message of its own. */
        const val UNKNOWN_ERROR_FA: String = "خطای ناشناخته"

        /** The matting sidecar (per-frame masks) could not be written to disk. */
        const val MATTING_SIDECAR_FAILED_FA: String =
            "ذخیره‌ی ماسک‌های حذف پس‌زمینه ناموفق بود؛ دوباره تلاش کنید"
    }

    /**
     * 2026-10-13 — playback transport strings.
     *
     * Lives here rather than inline at the EditorScreen call site because
     * the same write-time fault that corrupted the subtitle literals
     * [SubtitleMessagesGuardTest] guards has now hit four more strings
     * outside this file. One constant, one auditable place.
     */
    object Playback {
        /** The transport Play/ Pause was tapped with an empty timeline. */
        const val NOTHING_TO_PLAY_FA: String = "چیزی برای پخش نیست؛ ابتدا یک ویدیو اضافه کنید"
    }

    /**
     * Guard used by the unit tests: a message must contain at least one
     * letter and must not consist of question marks.
     *
     * The `?` run is the exact signature of the corruption this file
     * exists to prevent, so it is treated as a hard failure rather than a
     * style warning.
     */
    fun assertNoMojibake(message: String): Boolean {
        if (message.isBlank()) return false
        // A run of 3+ question marks separated by whitespace is the
        // corruption signature. A single '?' (a legitimate question mark
        // in prose) is allowed.
        val looksCorrupted = Regex("""\?{3,}""").containsMatchIn(message) ||
            Regex("""(^|\s)\?(\s|$)""").findAll(message).count() >= 3
        val hasLetters = message.any { it.isLetter() }
        return hasLetters && !looksCorrupted
    }
}
