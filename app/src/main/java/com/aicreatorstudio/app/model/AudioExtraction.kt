package com.aicreatorstudio.app.model

/**
 * 2026-10-13 — Extract Audio from Video (استخراج صدا از ویدیو).
 *
 * Pure, JVM-testable core of the feature. Holds no `android.*` type and no
 * I/O of its own: every decision the extractor must make — which track to
 * read, whether the stream can be copied, where the output file lands, what
 * the failure means — is a function here, and
 * [com.aicreatorstudio.app.ai.media.AudioExtractionService] is the thin
 * Android wrapper that feeds those functions real
 * `MediaExtractor` / `MediaMuxer` data.
 *
 * The split follows the project rule that testable logic lives in pure
 * top-level objects (like [SubtitleEditOps] and [ProgressBarGeometry]) so a
 * JVM test can cover the decisions without a device, and the codec plumbing
 * is verified structurally.
 */

/** MIME prefixes a media container reports through `MediaFormat.KEY_MIME`. */
const val VIDEO_MIME_PREFIX: String = "video/"
const val AUDIO_MIME_PREFIX: String = "audio/"

/** Output container/extension for the extracted file. */
const val EXTRACTED_AUDIO_EXTENSION: String = ".m4a"

/** Prefix for the output file name: `extracted_<epochMillis>.m4a`. */
const val EXTRACTED_AUDIO_FILE_PREFIX: String = "extracted_"

/** Timeline label shown for a clip produced by this feature. */
const val EXTRACTED_AUDIO_CLIP_NAME: String = "صدای استخراج‌شده"

/** Green confirmation banner shown after a successful extraction. */
const val EXTRACT_AUDIO_SUCCESS_MESSAGE: String =
    "صدای ویدیو با موفقیت استخراج و اضافه شد"

/**
 * Why an extraction failed. Each entry maps to exactly one user-facing
 * Persian sentence ([messageFa]) so the failure is always named instead of
 * a silent no-op — the same rule the native-engine diagnostics follow.
 */
enum class AudioExtractionFailure {
    /** The chosen container carries no audio track at all. */
    NO_AUDIO_TRACK,

    /** The source could not be opened / read (corrupt file, dead grant). */
    UNREADABLE_SOURCE,

    /** The muxer accepted zero samples — nothing playable was written. */
    EMPTY_AUDIO_TRACK,

    /** The extracted file reports no duration — an unusable container. */
    MISSING_DURATION,

    /** A codec is present but this device cannot decode/encode it. */
    UNSUPPORTED_CODEC,

    /** Writing the output file failed (storage full / unexpected I/O). */
    IO_FAILED,

    /** The user backed out of a non-cancellable phase; no error is shown. */
    CANCELLED;

    /** The single Persian sentence the UI shows for this failure. */
    val messageFa: String
        get() = when (this) {
            NO_AUDIO_TRACK -> "این ویدیو ترک صوتی ندارد"
            UNREADABLE_SOURCE -> "فایل ویدیو قابل خواندن نبود"
            EMPTY_AUDIO_TRACK -> "استخراج صدا ناقص بود؛ فایل قابل پخش تولید نشد"
            MISSING_DURATION -> "مدت فایل استخراج‌شده شناسایی نشد"
            UNSUPPORTED_CODEC -> "کدک صدای این ویدیو روی این دستگاه پشتیبانی نمی‌شود"
            IO_FAILED -> "ذخیره فایل صوتی ناموفق بود"
            CANCELLED -> ""
        }
}

/**
 * A successful extraction: the on-disk file and its duration (the clip's
 * `durationMs` comes from here).
 */
data class ExtractedAudio(
    val filePath: String,
    val durationMs: Long
)

/**
 * The decisions of the extraction feature, all pure.
 *
 * Every function is deterministic and side-effect free so
 * `AudioExtractionTest` can exercise the branches a device-only test would
 * have to synthesize media files for.
 */
object AudioExtractionPlanner {

    /** MIME of AAC audio in an MP4 container — the stream-copy fast path. */
    const val STREAM_COPY_MIME: String = "audio/mp4a-latm"

    /** Some containers report raw ADTS AAC with this MIME instead. */
    const val ALT_AAC_MIME: String = "audio/aac"

    /**
     * Index of the first track whose MIME starts with `audio` (the
     * `AUDIO_MIME_PREFIX`) in [trackMimes] (as returned by
     * `MediaExtractor.getTrackFormat(i).getString(KEY_MIME)`), or -1 when the
     * container carries no audio at all.
     *
     * A null MIME can never match an audio prefix, so it is skipped rather
     * than treated as audio — that mistake would feed a video track's
     * format to the audio muxer and produce a silent, unplayable file.
     */
    fun firstAudioTrackIndex(trackMimes: List<String?>): Int =
        trackMimes.indexOfFirst { mime -> mime != null && mime.startsWith(AUDIO_MIME_PREFIX) }

    /**
     * True when the track's audio can be remuxed by pure stream copy.
     *
     * Fast path condition from the brief: AAC in an MP4 container
     * (`MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4`) is bit-identical to
     * the source, so the file is written at disk speed with no decode and
     * no quality loss. Anything else (Opus, Vorbis, raw PCM, …) needs the
     * decode → encode pipeline.
     */
    fun isStreamCopy(mime: String?): Boolean =
        mime == STREAM_COPY_MIME || mime == ALT_AAC_MIME

    /**
     * Output file name under `filesDir/audio/`: `extracted_<epochMillis>.m4a`.
     *
     * The epoch millis come from the caller so the name is a pure function
     * of its argument (testable) and two extractions of the same video can
     * never overwrite each other.
     */
    fun outputFileName(epochMillis: Long): String =
        "$EXTRACTED_AUDIO_FILE_PREFIX$epochMillis$EXTRACTED_AUDIO_EXTENSION"

    /**
     * Where the new audio clip lands on the timeline, in ms.
     *
     * At the current playhead when the project already has audio (the user
     * is layering a sound where they are looking), and at 0 when it does
     * not — an extracted soundtrack that starts mid-video with silence
     * before it is never what a first audio track wants.
     *
     * A negative playhead (the preview reports -1 before the first frame on
     * some devices) coerces to 0 rather than producing a clip the timeline
     * cannot place.
     */
    fun resolveStartMs(playheadMs: Long, hasExistingAudio: Boolean): Long =
        if (hasExistingAudio) playheadMs.coerceAtLeast(0L) else 0L

    /**
     * Bitrate for the decode → encode fallback, in bits per second.
     *
     * 128 kbps stereo-transparent default scaled by channel count: enough
     * to be inaudibly close to the source for speech/music on a phone
     * speaker, small enough that the fallback stays cheap on a long video.
     * The source bitrate is not consulted because it is routinely absent
     * from the decoded format.
     */
    fun encodeBitrate(sampleRate: Int, channels: Int): Int {
        val perChannel = if (sampleRate >= 44_100) 64_000 else 48_000
        return (perChannel * channels.coerceIn(1, 2)).coerceIn(32_000, 256_000)
    }
}
