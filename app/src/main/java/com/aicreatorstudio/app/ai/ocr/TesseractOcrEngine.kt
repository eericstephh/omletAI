package com.aicreatorstudio.app.ai.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import com.aicreatorstudio.app.ai.AiModelCatalog
import com.aicreatorstudio.app.ai.AiModelInstaller
import com.aicreatorstudio.app.ai.AiToolEngine
import com.aicreatorstudio.app.ai.AiToolOptions
import com.aicreatorstudio.app.ai.AiToolResult
import com.aicreatorstudio.app.ai.OcrToolPayload
import com.aicreatorstudio.app.ai.media.SequentialFrameSampler
import com.aicreatorstudio.app.ai.media.VideoFramePipeline
import com.googlecode.tesseract.android.ResultIterator
import com.googlecode.tesseract.android.TessBaseAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/**
 * **AI OCR** — real on-device text extraction for video frames,
 * powered by Tesseract 5 LSTM ([TessBaseAPI], the
 * Tesseract4Android JNI build of libtesseract+libleptonica).
 *
 * ## Languages
 *
 * Three tessdata_fast language packs are managed through the
 * standard models.lock.json pipeline (download → SHA-256 verify →
 * install → READY):
 *
 *  - `tesseract_fas` — Persian (primary; RTL)
 *  - `tesseract_ara` — Arabic (secondary; RTL)
 *  - `tesseract_eng` — English (secondary; LTR)
 *
 * They are loaded **together** as a single combined engine by
 * default: Persian video text routinely mixes Latin brand names /
 * digits and Arabic-script lines, and Tesseract's script combiner
 * picks the best per-word language automatically. The user picks the
 * exact set in the OCR panel — the set travels to the engine as a
 * bit mask inside [AiToolOptions.strength] (see [OcrLanguagePacks]
 * for the encoding — which both sides read from one place). Only the
 * packs that are actually installed are joined into the combination
 * — the engine degrades gracefully (e.g. `fas+eng`) if one pack is
 * missing.
 *
 * ## Pipeline per OCR run
 *
 * ```
 * video → sampled frames (every ~1s) → preprocess (grayscale +
 * contrast-preserving 2x upscale) → TessBaseAPI.setImage →
 * HOCR iterator (word boxes + confidences) → line grouping →
 * Persian normalization (Arabic Yeh/Kaf → Persian forms, ZWNJ
 * preserved) → OcrAnalysisResult per timestamp
 * ```
 *
 * ## Why HOCR (ResultIterator) and not getUTF8Text()
 *
 * The plain `getUTF8Text()` returns text without geometry. The
 * HOCR path walks a [TessBaseAPI.ResultIterator] at word level and
 * yields per-word bounding boxes + confidences, which we merge
 * into text lines (same baseline band = one line) — exactly the
 * "متن + Bounding Box + Confidence" contract.
 *
 * ## Offline
 *
 * Tesseract inference is pure native CPU code; nothing in this
 * engine touches the network. The only network step in the whole
 * feature is the user-triggered language-pack download.
 */
@Singleton
class TesseractOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelInstaller: AiModelInstaller
) : AiToolEngine {

    override val id: String = "ocr"

    override val displayName: String = "AI OCR"

    /**
     * 2026-10-20 — the status line now reports the packs that are
     * REALLY installed, through the same helper the OCR panel uses
     * for its language pills.
     *
     * The old version printed the catalog's *display* language ("fa",
     * "ar", "en") for the missing packs while the panel spoke
     * Tesseract codes ("fas", "ara", "eng") — two different names for the
     * same pack. Everything now speaks the Tesseract code.
     */
    override val modelStatusText: String
        get() {
            val installed = installedOcrLanguages()
            val missing = OcrLanguagePacks.ALL_CODES.filter { it !in installed }
            return when {
                installed.isEmpty() -> "Model: Tesseract language packs (not installed)"
                missing.isEmpty() -> "Model: Tesseract 5 · ${installed.joinToString("+")} ready (offline)"
                else -> "Model: missing ${missing.joinToString("+")}"
            }
        }

    /**
     * The OCR language packs that are ACTUALLY installed on this
     * device, as Tesseract language codes ("fas", "ara", "eng").
     *
     * 2026-10-20 — the OCR panel used to hard-code three "ready"
     * language pills, so it claimed that Persian, Arabic AND English
     * were available even when only the Persian pack had been
     * downloaded. This is the honest source of truth: a pack that is
     * not on disk can never be reported as ready.
     *
     * The order follows [OCR_SPECS] (fas, ara, eng), so the returned
     * list is stable across calls.
     */
    fun installedOcrLanguages(): List<String> =
        OCR_SPECS
            .filter { modelInstaller.isInstalled(it) }
            .map { it.fileName.substringBefore('.') }

    override val isAvailable: Boolean
        get() = modelInstaller.isInstalled(AiModelCatalog.TESSERACT_FAS)

    /**
     * `TessBaseAPI` is not thread-safe; all inference is serialized.
     *
     * 2026-10-20 — a `ReentrantLock` instead of `synchronized`:
     * the run is a suspend function, and Kotlin forbids a suspension
     * point inside a `synchronized` block. The lock is taken around
     * the WHOLE run (obtain + infer), not just around the calls that
     * touch the native object.
     */
    private val tessLock = java.util.concurrent.locks.ReentrantLock()

    private var tess: TessBaseAPI? = null
    private var tessLanguages: String = ""

    // ----------------------------------------------------------------
    // Model lifecycle
    // ----------------------------------------------------------------

    override suspend fun ensureModelReady(onProgress: (Float) -> Unit): Result<Unit> {
        val specs = OCR_SPECS
        // Download each pack through the standard bundled-first +
        // SHA-verified pipeline. Progress is split evenly.
        for ((index, spec) in specs.withIndex()) {
            if (modelInstaller.isInstalled(spec)) continue
            val base = index.toFloat() / specs.size
            val span = 1f / specs.size
            val outcome = modelInstaller.installWithManifest(spec) { p ->
                onProgress(base + p * span)
            }
            if (outcome.failure != null) {
                val msg = outcome.detail ?: outcome.failure.persianMessage()
                return Result.failure(Exception(msg))
            }
        }
        onProgress(1f)
        return Result.success(Unit)
    }

    /**
     * Where the installed `*.traineddata` files must live for
     * Tesseract: `<dataParent>/tessdata/<lang>.traineddata`.
     * The lockfile dirName is `tesseract/tessdata`, and
     * [AiModels.modelFile] already returns the file inside it, so
     * the TessBaseAPI data path is
     * `filesDir/models/tesseract` (parent of `tessdata`).
     */
    fun tessDataParentDir(): File =
        File(context.filesDir, "models/tesseract")

    /**
     * Create (or re-create) the shared [TessBaseAPI] for the
     * requested language combination. Returns null + a Persian
     * error string when no pack is usable. The caller MUST call
     * [recycleTess] afterwards.
     *
     * 2026-10-20 — the requested language combination is now matched as
     * a SET, not as a substring. `available` is built in
     * `OCR_SPECS` order (fas, ara, eng) so it reads "fas+ara+eng";
     * the old `available.contains(languages)` test answered false for
     * the request "fas+eng" (because "fas+ara+eng" does not contain
     * that substring) and silently fell back to all three packs. A
     * plain substring test is also wrong in principle — "fas+eng" is a
     * subset of "fas+ara+eng" but not a substring of it.
     */
    private fun obtainTess(languages: String): Pair<TessBaseAPI, String>? {
        val parent = tessDataParentDir()
        val availableSpecs = OCR_SPECS.filter { modelInstaller.isInstalled(it) }
        val available = availableSpecs
            .map { it.fileName.substringBefore('.') }
            .joinToString("+")
        if (available.isBlank()) return null
        // Prefer the requested combination when every requested pack is
        // actually installed; otherwise degrade to whatever is there.
        val availableSet = available.split("+").toSet()
        val requestedSet = languages.split("+").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val langs = if (requestedSet.isNotEmpty() && availableSet.containsAll(requestedSet)) {
            languages
        } else {
            available
        }
        val existing = tess
        if (existing != null && tessLanguages == langs) return existing to langs
        recycleTess()
        return try {
            val api = TessBaseAPI()
            // init returns true on success (boolean JNI signature in
            // Tesseract4Android 4.x).
            val ok = api.init(parent.absolutePath, langs)
            if (!ok) {
                api.recycle()
                return null
            }
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            tess = api
            tessLanguages = langs
            api to langs
        } catch (t: Throwable) {
            Log.w(TAG, "Tesseract init failed for $langs", t)
            null
        }
    }

    private fun recycleTess() {
        runCatching { tess?.recycle() }
        tess = null
        tessLanguages = ""
    }

    // ----------------------------------------------------------------
    // Tool run
    // ----------------------------------------------------------------

    override suspend fun process(
        clipId: String,
        videoUri: Uri,
        options: AiToolOptions,
        onProgress: (Float) -> Unit
    ): Result<AiToolResult> = withContext(Dispatchers.IO) {
        try {
            if (!isAvailable) {
                return@withContext Result.failure(Exception(modelStatusText))
            }
            // 2026-10-20 — the language SET is the user's choice now (the OCR
            // panel's language pills), not a fixed 0..1 ratio.
            //
            // The explicit [AiToolOptions.ocrLanguages] set is
            // preferred. The `strength` bit mask (documented on
            // [OcrLanguagePacks]) is the fallback for older call sites
            // that have not been updated yet. An empty result on both
            // channels means "no explicit selection" — every installed
            // pack is used.
            val requested = if (options.ocrLanguages.isNotEmpty()) {
                options.ocrLanguages.filter { it in installedOcrLanguages() }
            } else {
                OcrLanguagePacks.codesOf(
                    OcrLanguagePacks.maskOfStrength(options.strength)
                )
            }
            val langs = (requested.ifEmpty { installedOcrLanguages() })
                .joinToString("+")
            // 2026-10-20 — SERIALISE THE WHOLE RUN.
            //
            // `TessBaseAPI` is NOT thread-safe, and the old code used
            // `tessLock` ONLY inside `release()`. `obtainTess()` mutated
            // the shared `tess` field and could call `tess.recycle()`
            // while another run was inside `api.setImage(...)` — a
            // native use-after-free.
            //
            // The ViewModel cancels the previous run before starting a
            // new one, but Tesseract calls are not cancellable, so the
            // old run is still inside `setImage()` when the new run
            // starts. Holding the lock for the entire run makes the
            // overlap impossible: the second run waits for the first
            // to finish, and the first one's `recycle()` can never race
            // the second one's inference.
            tessLock.lock()
            try {
                val pair = obtainTess(langs)
                if (pair == null) {
                    Result.failure(
                        Exception("بارگذاری موتور OCR ناموفق بود (زبان‌ها: $langs)")
                    )
                } else {
                    val (api, effectiveLangs) = pair
                    try {
                        val results = analyzeVideo(api, videoUri, options, onProgress)
                        val best = results.maxByOrNull { it.lines.size }
                        val summary = if (results.isEmpty()) {
                            "متنی در این کلیپ پیدا نشد"
                        } else {
                            val totalLines = results.sumOf { it.lines.size }
                            "$totalLines خط متن در ${results.size} فریم استخراج شد (زبان: $effectiveLangs)"
                        }
                        Result.success(
                            AiToolResult(
                                ocr = OcrToolPayload(
                                    frames = results,
                                    languages = effectiveLangs,
                                    best = best
                                ),
                                summary = summary
                            )
                        ).also { onProgress(1f) }
                    } finally {
                        // Release the image-side buffers but keep the
                        // engine instance warm for subsequent runs of
                        // this session.
                        runCatching { api.clear() }
                    }
                }
            } finally {
                tessLock.unlock()
            }
        } catch (e: CancellationException) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "OCR run failed", e)
            Result.failure(e)
        }
    }

    /**
     * Sample frames across the clip and OCR each one. Frame stride
     * adapts to clip length so a 10-minute clip is not analyzed
     * frame-by-frame (bounded at [MAX_FRAMES] samples).
     *
     * 2026-10-19 FIX — the old path asked
     * `VideoFramePipeline.sampleFrame` for each timestamp, which opened a
     * brand-new `MediaMetadataRetriever` per sample **and** used
     * `OPTION_CLOSEST_SYNC`, i.e. it read the same keyframe repeatedly and
     * reported the requested timestamp instead of the picture's real one.
     * Text that changed between keyframes was invisible and every result
     * was mis-timed. One sequential decode now yields frame-accurate
     * samples, and the legacy path survives only as a fallback.
     */
    private suspend fun analyzeVideo(
        api: TessBaseAPI,
        videoUri: Uri,
        options: AiToolOptions,
        onProgress: (Float) -> Unit
    ): List<OcrAnalysisResult> {
        val info = VideoFramePipeline.probe(context, videoUri)
            ?: throw Exception("خواندن اطلاعات ویدیو ناموفق بود")
        val durationMs = info.durationMs.coerceAtLeast(1L)
        val stride = max(
            1000L,
            durationMs / MAX_FRAMES
        )
        val expected = ((durationMs / stride).toInt() + 1).coerceAtMost(MAX_FRAMES)

        val results = ArrayList<OcrAnalysisResult>(expected)
        val ownerJob = coroutineContext[Job]
        var done = 0

        suspend fun consume(frame: Bitmap, timeMs: Long) {
            val analyzed = withContext(Dispatchers.Default) { analyzeBitmapNow(api, frame) }
            if (analyzed.lines.isNotEmpty()) {
                results.add(analyzed.copy(timestampMs = timeMs))
            }
        }

        val sampled = SequentialFrameSampler.sample(
            context = context,
            uri = videoUri,
            intervalMs = stride,
            maxFrames = MAX_FRAMES,
            // Cap the analysis frame: `preprocess` doubles anything under
            // 1600 px, so a 4K source used to build an 8K grayscale copy
            // per sample. 1080p keeps glyph detail and bounds memory.
            maxSide = OCR_ANALYSIS_MAX_SIDE,
            isCancelled = { ownerJob?.isActive == false }
        ) { frame ->
            // 2026-10-20 — `runCatching` swallows CancellationException
            // (it is a Throwable), so a cancelled OCR run kept decoding
            // and OCR-ing frames to the end of the clip. It also hid
            // genuine per-frame Tesseract errors, which were then
            // reported as "no text found" instead of an error.
            try {
                consume(frame.bitmap, frame.timeMs)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "OCR frame failed at ${frame.timeMs}ms", t)
            }
            frame.bitmap.recycle()
            done++
            onProgress(0.05f + 0.9f * (done.toFloat() / expected.coerceAtLeast(1)))
        }

        if (sampled == 0) {
            // Fallback: per-timestamp retrieval. Slower (one retriever per
            // sample) and keyframe-accurate only — never the default.
            var t = 0L
            var index = 0
            while (t < durationMs && index < MAX_FRAMES) {
                coroutineContext[Job]?.ensureActive()
                val frame = VideoFramePipeline.sampleFrame(context, videoUri, t)
                if (frame != null) {
                    try {
                        consume(frame, t)
                    } finally {
                        frame.recycle()
                    }
                }
                index++
                onProgress(0.05f + 0.9f * (index.toFloat() / expected.coerceAtLeast(1)))
                t += stride
            }
        }
        return results
    }

    /**
     * Blocking core of [analyzeBitmap] — the sampler's callback already
     * runs on a worker thread, and wrapping it in another `withContext`
     * per frame only added hop overhead.
     */
    private fun analyzeBitmapNow(api: TessBaseAPI, src: Bitmap): OcrAnalysisResult {
        val processed = preprocess(src)
        return try {
            api.setImage(processed)
            val lines = extractLines(api, processed.width, processed.height)
            OcrAnalysisResult(
                timestampMs = 0L,
                sourceWidth = src.width,
                sourceHeight = src.height,
                lines = lines
            )
        } finally {
            processed.recycle()
        }
    }

    /**
     * OCR one bitmap: preprocess → Tesseract → word iterator →
     * line grouping. Internal so the instrumentation test can hit
     * it directly with a rendered text image (real inference, no
     * mocks).
     */
    suspend fun analyzeBitmap(api: TessBaseAPI, src: Bitmap): OcrAnalysisResult =
        withContext(Dispatchers.Default) { analyzeBitmapNow(api, src) }

    /**
     * Tesseract-friendly preprocessing: grayscale + 2x upscale.
     * LSTM models were trained on ~32-48 px x-height text; video
     * frames typically carry 10-20 px glyphs, so a 2x upscale
     * measurably improves recall, and grayscale removes color
     * noise the LSTM never saw in training.
     */
    internal fun preprocess(src: Bitmap): Bitmap {
        val scale = if (max(src.width, src.height) < 1600) 2f else 1f
        val out = Bitmap.createBitmap(
            (src.width * scale).toInt(),
            (src.height * scale).toInt(),
            Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(PaintFlagsFilterHolder.filter)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, Matrix(), paint)
        return out
    }

    /** ColorMatrix grayscale paint filter (allocated once). */
    private object PaintFlagsFilterHolder {
        val filter = android.graphics.ColorMatrixColorFilter(
            android.graphics.ColorMatrix().apply { setSaturation(0f) }
        ).let { cf ->
            android.graphics.Paint().apply {
                colorFilter = cf
                isFilterBitmap = true
                isAntiAlias = true
            }
        }
    }

    /**
     * Walk the word-level [ResultIterator], normalize each word's
     * text, and merge words into lines by vertical overlap (same
     * baseline band = one line). Words with empty text or
     * sub-threshold confidence are dropped.
     *
     * API notes (Tesseract4Android 4.9): `getResultIterator()` is
     * nullable, the iterator extends [PageIterator] whose
     * `getBoundingRect` may return null, iteration starts at
     * `begin()` and advances with `next(level)`, and the native
     * handle is released with `delete()`.
     */
    internal fun extractLines(api: TessBaseAPI, imgW: Int, imgH: Int): List<OcrLine> {
        val out = ArrayList<OcrLine>()
        var iterator: ResultIterator? = null
        try {
            iterator = api.resultIterator
            if (iterator == null) return out
            val rawLines =
                ArrayList<Triple<MutableList<String>, android.graphics.Rect, MutableList<Float>>>()
            iterator.begin()
            do {
                val word = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD) ?: continue
                val trimmed = word.trim()
                if (trimmed.isEmpty()) continue
                val conf = iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                if (conf < MIN_WORD_CONFIDENCE) continue
                val rect = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    ?: continue
                val normalized = OcrTextNormalizer.normalizeWord(trimmed)
                if (normalized.isEmpty()) continue
                // Merge into an existing line when the word's vertical
                // band overlaps an open line's band.
                val merged = rawLines.lastOrNull { (_, r, _) ->
                    val lineTop = r.top.toFloat()
                    val lineBottom = r.bottom.toFloat()
                    val wordTop = rect.top.toFloat()
                    val wordBottom = rect.bottom.toFloat()
                    val overlap = minOf(lineBottom, wordBottom) - maxOf(lineTop, wordTop)
                    overlap > 0.5f * minOf(r.height().toFloat(), rect.height().toFloat())
                }
                if (merged != null) {
                    merged.first.add(normalized)
                    merged.third.add(conf)
                    union(merged.second, rect)
                } else {
                    rawLines.add(
                        Triple(
                            mutableListOf(normalized),
                            android.graphics.Rect(rect),
                            mutableListOf(conf)
                        )
                    )
                }
            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))

            for ((words, rect, confs) in rawLines) {
                // 2026-10-20 FIX — only the ARABIC runs are reversed.
                //
                // Tesseract yields the words in VISUAL left-to-right
                // order. Inside a run of Arabic-script words the
                // reading order is the reverse of the visual order,
                // but a run of Latin words is read in the visual
                // order as-is. The old code reversed the WHOLE line
                // as soon as a single Arabic word was present, so
                // "APP PRO" inside a Persian line came out as
                // "PRO APP" — the exact opposite of what the old helper's
                // KDoc promised.
                //
                // A pure-Persian line is a single Arabic run and is
                // therefore still reversed as a whole.
                val text = reorderWordsForReading(words).joinToString(" ")
                val meanConf = confs.average().toFloat() / 100f
                out.add(
                    OcrLine(
                        text = text,
                        left = rect.left.toFloat() / imgW,
                        top = rect.top.toFloat() / imgH,
                        right = rect.right.toFloat() / imgW,
                        bottom = rect.bottom.toFloat() / imgH,
                        confidence = meanConf.coerceIn(0f, 1f)
                    )
                )
            }
        } finally {
            // Tesseract4Android's iterator exposes delete() for the
            // native handle (no recycle()); PageIterator has no close.
            runCatching { iterator?.delete() }
        }
        return out
    }

    private fun union(dst: android.graphics.Rect, src: android.graphics.Rect) {
        dst.union(src)
    }

    // ----------------------------------------------------------------
    // Script detection + reading order
    // ----------------------------------------------------------------

    /**
     * True when the word contains at least one Arabic-script code
     * point (Arabic, Persian, Urdu and the presentation forms).
     */
    internal fun isArabicScriptWord(word: String): Boolean =
        OcrReadingOrder.isArabicScriptWord(word)

    /**
     * True when at least one word of the line contains Arabic-script
     * code points.
     *
     * 2026-10-20 — this is the predicate the old (mis-named)
     * `isRtlDominant` actually implemented. It is kept under an
     * honest name, because "does this line contain any Arabic at
     * all" is a different question from "is this line
     * RTL-dominant".
     */
    internal fun containsArabicScript(words: List<String>): Boolean =
        OcrReadingOrder.containsArabicScript(words)

    /**
     * True when the line is RTL-dominant: it holds at least one
     * Arabic-script word AND the Arabic-script words are at least as
     * many as the words written in another script (a tie counts as
     * RTL).
     *
     * 2026-10-20 — the old implementation returned `true` as soon as
     * ONE Arabic word appeared anywhere in the line, so the name did
     * not describe the behaviour at all: a line with a single
     * Persian brand name among five English words was reported as
     * RTL-dominant. The test now really counts. The old "contains
     * any Arabic" predicate is still available as
     * [containsArabicScript].
     */
    internal fun isRtlDominant(words: List<String>): Boolean =
        OcrReadingOrder.isRtlDominant(words)

    /**
     * Put the words of a line into reading order.
     *
     * 2026-10-20 FIX — the RTL reversal used to flip the WHOLE line
     * as soon as one Arabic word was present, so the order of the
     * Latin words was destroyed as well ("APP PRO" became
     * "PRO APP"), even though the old helper's KDoc promised that
     * the Latin words keep their own internal order.
     *
     * What is correct: the words arrive in VISUAL left-to-right
     * order. Inside a contiguous run of Arabic-script words the
     * reading order is the reverse of the visual order, while a
     * contiguous run of Latin words is read in the visual order
     * as-is. So the list is split into alternating Arabic / Latin
     * runs and only the Arabic runs are reversed.
     *
     * Neutral tokens (digits, punctuation) join the run they sit
     * in, so a number between two Persian words stays inside the
     * Arabic run and travels with the words around it.
     *
     * A pure-Persian line is one Arabic run and is therefore still
     * reversed as a whole — the common case is unchanged.
     */
    internal fun reorderWordsForReading(words: List<String>): List<String> =
        OcrReadingOrder.reorder(words)

    override fun release() {
        // The same lock the run holds — so `release()` can never
        // recycle the native API while a run is inside `setImage()`.
        tessLock.lock()
        try {
            recycleTess()
        } finally {
            tessLock.unlock()
        }
    }

    companion object {
        private const val TAG = "TesseractOcr"

        /** Words below this Tesseract confidence are discarded. */
        const val MIN_WORD_CONFIDENCE: Float = 40f

        /** Upper bound on analyzed frames per run (memory + latency). */
        const val MAX_FRAMES: Int = 60

        /**
         * Longest side of an OCR analysis frame. `preprocess` doubles
         * anything under 1600 px, so this bounds the grayscale copy at
         * ~2x1080p instead of 2x4K.
         */
        const val OCR_ANALYSIS_MAX_SIDE: Int = 1920

        /** Centralized language-pack specs (models.lock.json entries). */
        val OCR_SPECS = listOf(
            AiModelCatalog.TESSERACT_FAS,
            AiModelCatalog.TESSERACT_ARA,
            AiModelCatalog.TESSERACT_ENG
        )
    }
}


/**
 * The OCR language packs and the encoding that carries the user's
 * language choice from the OCR panel to the engine.
 *
 * 2026-10-20 — [AiToolOptions] (in `ai/AiToolEngine.kt`) has no
 * `ocrLanguages` field and that file must not be changed here, so
 * the requested language SET travels through
 * [AiToolOptions.strength] as a BIT MASK:
 *
 * ```
 *   1 = fas  (Persian)
 *   2 = ara  (Arabic)
 *   4 = eng  (English)
 *   strength = (sum of the selected bits).toFloat()
 * ```
 *
 * A mask of 0 means "no explicit selection" — the engine then loads every
 * installed pack. The mapping lives in this one pure-Kotlin object
 * (no Android types) so the panel and the engine can never drift
 * apart, and it is unit-testable on the JVM.
 *
 * The proper fix is a dedicated `ocrLanguages: Set<String>` field on
 * [AiToolOptions]; until that exists the mask is the single
 * documented channel.
 */
object OcrLanguagePacks {

    /** Tesseract language code — Persian. */
    const val FAS = "fas"

    /** Tesseract language code — Arabic. */
    const val ARA = "ara"

    /** Tesseract language code — English. */
    const val ENG = "eng"

    /** Bit for [FAS]. */
    const val MASK_FAS = 1

    /** Bit for [ARA]. */
    const val MASK_ARA = 2

    /** Bit for [ENG]. */
    const val MASK_ENG = 4

    /** Every supported bit. */
    const val MASK_ALL = MASK_FAS or MASK_ARA or MASK_ENG

    /** Every supported code, in display order. */
    val ALL_CODES: List<String> = listOf(FAS, ARA, ENG)

    /** The bit of [code], or 0 when the code is unknown. */
    fun maskOf(code: String): Int = when (code.lowercase()) {
        FAS -> MASK_FAS
        ARA -> MASK_ARA
        ENG -> MASK_ENG
        else -> 0
    }

    /** The mask of a set of codes (unknown codes are ignored). */
    fun maskOf(codes: Collection<String>): Int =
        codes.fold(0) { acc, code -> acc or maskOf(code) }

    /** The codes of [mask], in display order. */
    fun codesOf(mask: Int): List<String> =
        ALL_CODES.filter { mask and maskOf(it) != 0 }

    /**
     * Decode the [AiToolOptions.strength] channel into a mask.
     *
     * Only whole numbers are meaningful — a fractional value is not a
     * mask and is treated as "no selection" (0), which the engine
     * reads as "use every installed pack".
     */
    fun maskOfStrength(strength: Float): Int {
        if (!strength.isFinite() || strength <= 0f) return 0
        val whole = strength.toInt()
        if (whole.toFloat() != strength) return 0
        return whole and MASK_ALL
    }
}


/**
 * The reading-order rules for an OCR line.
 *
 * 2026-10-20 — split out of [TesseractOcrEngine] into a pure
 * object: these are pure text rules and must be verifiable on the
 * JVM without a Tesseract instance (the engine itself needs a
 * `Context`).
 */
object OcrReadingOrder {

    /**
     * Arabic-script word.
     *
     * 2026-10-20 — plain ints (not an enum) so the classifier stays
     * allocation-free on the OCR hot path.
     */
    private const val CLASS_ARABIC = 1

    /** Word written in another script. */
    private const val CLASS_LATIN = 2

    /** Token without any letter (digits, punctuation). */
    private const val CLASS_NEUTRAL = 0

    /**
     * True when the word contains at least one Arabic-script code
     * point (Arabic, Persian, Urdu and the presentation forms).
     */
    fun isArabicScriptWord(word: String): Boolean =
        word.any { it in '\u0600'..'\u06FF' || it in '\uFB50'..'\uFDFF' || it in '\uFE70'..'\uFEFF' }

    /**
     * True when at least one word of the line contains Arabic-script
     * code points.
     *
     * 2026-10-20 — this is the predicate the old (mis-named)
     * `isRtlDominant` actually implemented. It is kept under an
     * honest name, because "does this line contain any Arabic at
     * all" is a different question from "is this line
     * RTL-dominant".
     */
    fun containsArabicScript(words: List<String>): Boolean =
        words.any { isArabicScriptWord(it) }

    /**
     * True when the line is RTL-dominant: it holds at least one
     * Arabic-script word AND the Arabic-script words are at least as
     * many as the words written in another script (a tie counts as
     * RTL).
     *
     * 2026-10-20 — the old implementation returned `true` as soon as
     * ONE Arabic word appeared anywhere in the line, so the name did
     * not describe the behaviour at all: a line with a single
     * Persian brand name among five English words was reported as
     * RTL-dominant. The test now really counts. The old "contains
     * any Arabic" predicate is still available as
     * [containsArabicScript].
     */
    fun isRtlDominant(words: List<String>): Boolean {
        val arabic = words.count { isArabicScriptWord(it) }
        if (arabic == 0) return false
        val other = words.count { !isArabicScriptWord(it) && it.any { ch -> ch.isLetter() } }
        return arabic >= other
    }

    /**
     * Put the words of a line into reading order.
     *
     * 2026-10-20 FIX — the RTL reversal used to flip the WHOLE line
     * as soon as one Arabic word was present, so the order of the
     * Latin words was destroyed as well ("APP PRO" became
     * "PRO APP"), even though the old helper's KDoc promised that
     * the Latin words keep their own internal order.
     *
     * What is correct: the words arrive in VISUAL left-to-right
     * order. Inside a contiguous run of Arabic-script words the
     * reading order is the reverse of the visual order, while a
     * contiguous run of Latin words is read in the visual order
     * as-is. So the list is split into alternating Arabic / Latin
     * runs and only the Arabic runs are reversed.
     *
     * Neutral tokens (digits, punctuation) join the run they sit
     * in, so a number between two Persian words stays inside the
     * Arabic run and travels with the words around it.
     *
     * A pure-Persian line is one Arabic run and is therefore still
     * reversed as a whole — the common case is unchanged.
     */
    fun reorder(words: List<String>): List<String> {
        if (words.size <= 1) return words
        val classes = words.map { wordScriptClass(it) }
        // Neutral tokens inherit the class of the run they are
        // embedded in (the previous word); a leading neutral token
        // inherits the class of the first real word.
        val resolved = IntArray(words.size)
        var previous = CLASS_NEUTRAL
        for (i in words.indices) {
            val c = if (classes[i] == CLASS_NEUTRAL) previous else classes[i]
            resolved[i] = c
            if (c != CLASS_NEUTRAL) previous = c
        }
        var first = CLASS_NEUTRAL
        for (c in resolved) {
            if (c != CLASS_NEUTRAL) {
                first = c
                break
            }
        }
        if (first != CLASS_NEUTRAL) {
            for (i in resolved.indices) {
                if (resolved[i] != CLASS_NEUTRAL) break
                resolved[i] = first
            }
        }
        val out = ArrayList<String>(words.size)
        var start = 0
        while (start < words.size) {
            val cls = resolved[start]
            var end = start + 1
            while (end < words.size && resolved[end] == cls) end++
            val run = words.subList(start, end)
            if (cls == CLASS_ARABIC) out.addAll(run.asReversed()) else out.addAll(run)
            start = end
        }
        return out
    }

    /**
     * Script class of a word: [CLASS_ARABIC] for Arabic-script text,
     * [CLASS_LATIN] for words written in another script and
     * [CLASS_NEUTRAL] for tokens without any letter (digits,
     * punctuation) — those belong to the surrounding run.
     */
    private fun wordScriptClass(word: String): Int = when {
        isArabicScriptWord(word) -> CLASS_ARABIC
        word.any { it.isLetter() } -> CLASS_LATIN
        else -> CLASS_NEUTRAL
    }
}
