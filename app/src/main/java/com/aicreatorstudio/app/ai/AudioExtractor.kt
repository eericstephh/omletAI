package com.aicreatorstudio.app.ai

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import com.aicreatorstudio.app.util.BeatDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared helper that decodes the first audio track of a media `Uri`
 * into a float PCM buffer.
 *
 * Extracted from `WhisperEngine.decodeAudioToPcmFloat` /
 * `resampleTo16kMonoFloat` so multiple AI engines can reuse the
 * exact same path without copying ~120 lines of codec plumbing. The
 * algorithm and behaviour are byte-identical to the original — this
 * is a pure refactor.
 *
 * Two public entry points are exposed:
 *
 * - [decodeFirstAudioTrack] — full quality, preserves the source
 *   sample rate, returns stereo if the source has two channels.
 *   Used by the Voice Clean engine when it needs the original
 *   sample rate to re-mux into the export pipeline.
 * - [decodeTo16kMonoFloat] — matches whisper.cpp's expected layout
 *   (16 kHz, mono, float32 in [-1, 1]). Used by anything that wants
 *   the same format the Whisper engine consumes.
 *
 * The decoder runs entirely on the calling thread (which should be
 * `Dispatchers.IO`). The implementation is intentionally stateless —
 * a new `MediaExtractor` and `MediaCodec` are created per call so
 * concurrent callers don't interfere.
 */
@Singleton
class AudioExtractor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * 2026-09-07 REMUX-FIRST FIX — prepare the decode source for
     * whisper transcription:
     *
     *  - **audio-only source** (imported .m4a/.wav/.mp3) → returned
     *    as `null`; the caller decodes it directly (the MediaCodec
     *    path is reliable on audio-only files).
     *  - **video container** → the COMPRESSED audio track is remuxed
     *    into a standalone .m4a in the cache dir with `MediaExtractor`
     *    + `MediaMuxer` (pure I/O — no decode — so it runs at disk
     *    speed with real progress and cancellation) and that file's
     *    URI is returned. Remuxed files carry clean metadata
     *    (guaranteed `KEY_DURATION`), decode fast and reliably on
     *    every device, and sidestep the container/codec quirks that
     *    left the panel stuck on "در حال استخراج صدا" forever.
     *
     * The remux result is cached per source URI (atomic tmp→rename),
     * so a retry after a failure or a second generate skips the I/O.
     * Returns `null` on ANY remux failure — the caller then falls
     * back to the legacy in-place container decode. `CancellationException`
     * is re-thrown so cooperative cancel still aborts instantly.
     */
    fun prepareAudioSourceForDecode(
        sourceUri: Uri,
        onProgress: (Float) -> Unit = {},
        checkCancelled: (() -> Unit)? = null
    ): Uri? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var tmpFile: java.io.File? = null
        try {
            extractor.setDataSource(context, sourceUri, null)
            var audioTrackIndex = -1
            var videoTrackFound = false
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val tf = extractor.getTrackFormat(i)
                val mime = tf.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") -> videoTrackFound = true
                    mime.startsWith("audio/") && audioTrackIndex == -1 -> {
                        audioTrackIndex = i
                        audioFormat = tf
                    }
                }
            }
            // Audio-only source (or no audio at all) → nothing to
            // remux; decode the original directly.
            if (audioTrackIndex == -1 || !videoTrackFound) return null

            val cacheDir = java.io.File(context.cacheDir, "ai-audio-extract").apply {
                if (!exists()) mkdirs()
            }
            val finalFile = java.io.File(cacheDir, "audio-${sourceUri.hashCode()}.m4a")
            if (finalFile.exists() && finalFile.length() > 1_000L) {
                // 2026-09-07 CACHE-SANITY CHECK — a corrupt cached m4a
                // (truncated mid-write, zero-duration container) would
                // poison EVERY retry: the remux is skipped, the decode
                // of the bad file stalls, the user sees the bar lock
                // at the same percentage forever. Reject a cached file
                // whose duration probe reads zero and fall through to
                // a fresh remux that overwrites it.
                val cachedDurUs = metadataDurationUs(Uri.fromFile(finalFile))
                if (cachedDurUs <= 0L) {
                    Log.w(
                        "AudioExtractor",
                        "cached m4a has no duration — discarding corrupt cache: $finalFile"
                    )
                    finalFile.delete()
                } else {
                    onProgress(1f)
                    return Uri.fromFile(finalFile)
                }
            }
            tmpFile = java.io.File(cacheDir, "audio-${sourceUri.hashCode()}.m4a.tmp")
            if (tmpFile.exists()) tmpFile.delete()

            val format = audioFormat!!
            val durationUs =
                readDurationUs(format).takeIf { it > 0 } ?: metadataDurationUs(sourceUri)

            extractor.selectTrack(audioTrackIndex)
            muxer = MediaMuxer(
                tmpFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            val muxTrack = muxer.addTrack(format)
            muxer.start()

            val info = MediaCodec.BufferInfo()
            val buffer = java.nio.ByteBuffer.allocateDirect(1 shl 20)
            var lastEmitMs = 0L
            var lastWriteWallMs = android.os.SystemClock.elapsedRealtime()
            var lastPts = -1L
            var ptsFreezeRounds = 0
            var samplesWritten = 0L
            // Show the bar immediately — the phase has really started.
            if (durationUs > 0) onProgress(0f)
            Log.i(
                "AudioExtractor",
                "remux start: audioTrack=$audioTrackIndex durationUs=$durationUs"
            )
            while (true) {
                checkCancelled?.invoke()
                // 2026-09-07 REMUX WATCHDOG — the previous loop had NO
                // stall detection: a container whose extractor keeps
                // returning the same sample (PTS frozen) spun forever,
                // leaving the progress bar locked at its first value
                // with no error and no cancel. Two detectors:
                //   (a) wall clock — no successful write for 30 s
                //   (b) PTS freeze — 200 consecutive writes at the
                //       same sample time (real audio never repeats
                //       PTS; ~40 writes/sec makes this < 5 s of audio)
                val wallNow = android.os.SystemClock.elapsedRealtime()
                if (wallNow - lastWriteWallMs > 30_000L) {
                    throw Exception("remux stalled: no sample written in 30 s")
                }
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                info.set(0, sampleSize, extractor.sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(muxTrack, buffer, info)
                extractor.advance()
                samplesWritten++
                lastWriteWallMs = android.os.SystemClock.elapsedRealtime()
                if (extractor.sampleTime == lastPts) {
                    ptsFreezeRounds++
                    if (ptsFreezeRounds > 200) {
                        throw Exception(
                            "remux stalled: sample PTS frozen at ${extractor.sampleTime}µs"
                        )
                    }
                } else {
                    ptsFreezeRounds = 0
                    lastPts = extractor.sampleTime
                }
                if (durationUs > 0) {
                    if (lastEmitMs == 0L || wallNow - lastEmitMs >= 100L) {
                        lastEmitMs = wallNow
                        val fraction =
                            extractor.sampleTime.coerceAtLeast(0L).toFloat() / durationUs
                        onProgress(fraction.coerceIn(0f, 1f))
                    }
                }
            }
            Log.i(
                "AudioExtractor",
                "remux done: samples=$samplesWritten tmp=${tmpFile.length()} bytes"
            )
            runCatching { muxer.stop() }
            if (!tmpFile.renameTo(finalFile)) {
                tmpFile.copyTo(finalFile, overwrite = true)
                tmpFile.delete()
            }
            // 2026-09-07 POST-REMUX SANITY — a muxer that accepted
            // zero samples (or a container without readable duration)
            // produces a silent poison file; the subsequent decode
            // would stall with the bar locked. Fail HERE with a
            // clear message and no cache, so the caller's legacy
            // fallback gets a clean shot at the original container.
            val writtenDurUs = metadataDurationUs(Uri.fromFile(finalFile))
            if (samplesWritten == 0L || writtenDurUs <= 0L) {
                finalFile.delete()
                throw Exception(
                    "استخراج صدا ناقص بود ($samplesWritten نمونه نوشته شد). " +
                        "به مسیر مستقیم برمی‌گردیم…"
                )
            }
            onProgress(1f)
            return Uri.fromFile(finalFile)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w("AudioExtractor", "audio remux failed; falling back to direct decode", e)
            tmpFile?.delete()
            return null
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Decodes the first audio track of [videoUri] into 16 kHz mono
     * float32 PCM (whisper.cpp's native sample format). Returns the
     * sample buffer, the actual sample rate of the decoded PCM (for
     * validation), and the channel count the codec emitted before
     * down-mix.
     *
     * 2026-09-07 DECODE-PROGRESS FIX — [onProgress] receives a REAL
     * 0..1 fraction (decoded sample PTS / track duration, throttled
     * to one emission per 100 ms so the UI is not spammed) and
     * [checkCancelled] is invoked every decoder loop round so a
     * cancelled coroutine aborts the decode immediately instead of
     * grinding to completion. Previously this path had NO progress
     * and NO cancellation — the UI froze on "در حال استخراج صدا"
     * for the entire decode and Cancel did nothing.
     */
    fun decodeTo16kMonoFloat(
        videoUri: Uri,
        onProgress: (Float) -> Unit = {},
        checkCancelled: (() -> Unit)? = null
    ): Triple<FloatArray, Int, Int> {
        val (pcm, sampleRate, channels) =
            decodeFirstAudioTrack(videoUri, onProgress, checkCancelled)
        // Downmix to mono int16 FIRST, then drop the raw PCM byte
        // array before allocating the output float buffer. For long
        // videos the raw bytes are ~4-10x the size of the final
        // 16 kHz mono float buffer; holding both simultaneously was
        // the decode path's peak-memory blowup (GC thrash on long
        // clips, which the user experienced as "nothing happening").
        val mono = int16BytesToMonoShort(pcm, channels)
        val (outSamples, outSampleRate) = resampleMonoShortTo16kFloat(mono, sampleRate)
        return Triple(outSamples, outSampleRate, 1)
    }

    /**
     * Resample an int16 PCM byte buffer (as returned by
     * [decodeFirstAudioTrack]) down to 16 kHz mono float. Exposed so
     * the Voice Clean engine can decode once and resample once
     * instead of running the codec twice.
     */
    fun resampleInt16BytesToMonoFloat(
        pcmBytes: ByteArray,
        sampleRate: Int,
        channels: Int
    ): FloatArray = resampleTo16kMonoFloat(pcmBytes, sampleRate, channels).first

    /**
     * Decodes the first audio track to mono float32 PCM at the
     * SOURCE sample rate (no resampling). Used by Voice Clean's
     * RNNoise path, which resamples to 48 kHz itself and applies
     * the resulting gain envelope back onto the original-rate PCM
     * so duration and channel layout are preserved exactly.
     *
     * @return Triple(samples, sampleRate, channels) where `samples`
     *   is mono float in [-1, 1] and `channels` is the source
     *   channel count (for callers that re-apply gains to stereo).
     */
    fun decodeToMonoFloat(videoUri: Uri): Triple<FloatArray, Int, Int> {
        val (pcm, sampleRate, channels) = decodeFirstAudioTrack(videoUri)
        val mono = AudioResampler.int16BytesToMonoFloat(pcm, channels)
        return Triple(mono, sampleRate, channels)
    }

    /**
     * Decode the first audio track to mono float at
     * [analysisSampleRate] for offline signal analysis (beat detection).
     *
     * Distinct from [decodeTo16kMonoFloat] because 16 kHz is wrong for this
     * job: beat detection analyses up to ~2 kHz for snares, and resampling to
     * 16 kHz forces an anti-alias roll-off starting well inside that band,
     * which smears exactly the transients the detector looks for. 22.05 kHz
     * keeps the whole analysis band clean, and halving the rate versus the
     * source roughly quarters the FFT work.
     *
     * Runs the decode and the resample on the calling thread — callers must
     * dispatch to an IO dispatcher.
     *
     * @param analysisSampleRate target rate; defaults to what
     *   [BeatDetector.ANALYSIS_SAMPLE_RATE] expects.
     * @return Triple(samples, actualRate, channels) — `samples` is mono in
     *   `[-1, 1]` at [analysisSampleRate] (or the source rate when that is
     *   already lower, since upsampling cannot recover detail).
     */
    fun decodeForAnalysis(
        videoUri: Uri,
        analysisSampleRate: Int = BeatDetector.ANALYSIS_SAMPLE_RATE,
        onProgress: (Float) -> Unit = {},
        checkCancelled: (() -> Unit)? = null
    ): Triple<FloatArray, Int, Int> {
        val (pcm, sampleRate, channels) =
            decodeFirstAudioTrack(videoUri, onProgress, checkCancelled)
        val mono = AudioResampler.int16BytesToMonoFloat(pcm, channels)
        if (sampleRate <= 0) return Triple(mono, sampleRate, channels)
        if (sampleRate <= analysisSampleRate) {
            // Never upsample: it would invent bandwidth the source lacks and
            // inflate the FFT cost for no gain. The detector adapts its bin
            // mapping to whatever rate it is handed.
            return Triple(mono, sampleRate, channels)
        }
        val resampled = AudioResampler.resampleMonoFloat(mono, sampleRate, analysisSampleRate)
        return Triple(resampled, analysisSampleRate, channels)
    }

    /**
     * Decodes the first audio track and returns the raw int16 PCM
     * bytes alongside the actual output sample rate + channel count.
     * Callers that want to preserve the source sample rate (for
     * re-muxing into the export pipeline) call this directly instead
     * of [decodeTo16kMonoFloat].
     *
     * The optional [onProgress] callback receives the REAL decoded
     * fraction (current sample PTS / `KEY_DURATION` of the audio
     * track) at most every 100 ms, plus a final 1f at EOS. When the
     * track has no duration metadata no progress is reported — the
     * "no fake progress" rule. The optional [checkCancelled] callback
     * runs once per decoder loop round; throwing from it (e.g.
     * `Job.ensureActive()`) aborts the decode immediately.
     */
    fun decodeFirstAudioTrack(
        videoUri: Uri,
        onProgress: (Float) -> Unit = {},
        checkCancelled: (() -> Unit)? = null
    ): Triple<ByteArray, Int, Int> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, videoUri, null)
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val tf = extractor.getTrackFormat(i)
                val mime = tf.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = tf
                    break
                }
            }
            if (audioTrackIndex == -1 || format == null) {
                throw Exception("هیچ ترک صوتی در ویدیو پیدا نشد")
            }
            extractor.selectTrack(audioTrackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw Exception("MIME type not found in audio format")
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            // Real progress source: the track duration in µs.
            // 2026-09-07 PROGRESS FIX — some vendor MediaFormat
            // implementations store KEY_DURATION as an Integer, so a
            // bare `getLong` threw ClassCastException and the code
            // silently fell into "duration = 0 → never emit progress"
            // (the frozen "در حال استخراج صدا" label with a bar that
            // never fills). Read it Integer/Long-safe, then fall back
            // to the container duration from MediaMetadataRetriever.
            var durationUs = readDurationUs(format)
            if (durationUs <= 0L) durationUs = metadataDurationUs(videoUri)

            var lastProgressEmitMs = 0L
            fun emitProgress(force: Boolean) {
                if (durationUs <= 0L) return
                val now = android.os.SystemClock.elapsedRealtime()
                if (!force && now - lastProgressEmitMs < 100L) return
                lastProgressEmitMs = now
                val fraction =
                    (extractor.sampleTime.coerceAtLeast(0L)).toFloat() / durationUs.toFloat()
                onProgress(fraction.coerceIn(0f, 1f))
            }

            val pcmBytes = java.io.ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            val timeoutUs = 10_000L
            var sawInputEos = false
            var sawOutputEos = false
            var outputSampleRate = 16_000
            var outputChannels = 1
            // 2026-09-06 STALL FIX — some codecs/formats stall forever
            // (dequeue never returns a buffer and EOS never arrives),
            // which left the subtitle panel stuck on "در حال استخراج
            // صدا" forever. Every loop round without progress bumps a
            // counter; a round that DOES make progress resets it. After
            // ~30 s of zero progress the decode fails with a clear
            // message instead of hanging the UI state forever.
            var stalledRounds = 0
            val maxStalledRounds = 3_000 // 3000 × 10 ms = 30 s
            // 2026-09-07 REAL-PROGRESS WATCHDOG — `progressed` only
            // proves the codec ACCEPTED a round, not that decoding
            // ADVANCED. Several known MediaCodec/extractor
            // pathologies spin this loop forever with progressed ==
            // true while making zero real progress, which left the
            // bar locked (e.g. at ~70% = decode start after remux)
            // for minutes with no error:
            //   - readSampleData returning 0 forever (no EOS),
            //   - frozen input PTS (extractor.advance() no-ops),
            //   - repeated INFO_OUTPUT_FORMAT_CHANGED spam,
            //   - endless 0-size output buffers.
            // Every real advance (a non-empty input sample, a
            // non-empty output buffer) stamps [lastRealProgressMs];
            // 45 s without ANY stamp is a hard stall. A frozen-PTS
            // counter and a FORMAT_CHANGED-spam counter catch the
            // spin variants even faster.
            var lastRealProgressMs = android.os.SystemClock.elapsedRealtime()
            var lastInputPts = -1L
            var frozenPtsAdvances = 0
            var formatChangedCount = 0
            val decodeStartMs = android.os.SystemClock.elapsedRealtime()
            val maxDecodeMs = 20 * 60 * 1000L // absolute safety cap

            fun noteRealProgress() {
                lastRealProgressMs = android.os.SystemClock.elapsedRealtime()
            }

            while (!sawOutputEos) {
                // 2026-09-07 CANCELLATION FIX — cooperative cancel:
                // throwing here (Job.ensureActive) aborts the decode
                // loop and releases the codec in the finally below,
                // so the user's Cancel tap actually works during
                // "در حال استخراج صدا" instead of being ignored
                // until the whole decode finished.
                checkCancelled?.invoke()
                val wallNow = android.os.SystemClock.elapsedRealtime()
                if (wallNow - decodeStartMs > maxDecodeMs) {
                    throw Exception(
                        "استخراج صدا بیش از حد طول کشید (سقف ۲۰ دقیقه). " +
                            "ویدیو یا فایل صوتی کوتاه‌تری امتحان کنید."
                    )
                }
                var progressed = false
                if (!sawInputEos) {
                    val inIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inIndex)
                            ?: throw Exception("Failed to get input buffer")
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEos = true
                            progressed = true
                            noteRealProgress()
                        } else {
                            decoder.queueInputBuffer(
                                inIndex, 0, sampleSize, extractor.sampleTime, 0
                            )
                            extractor.advance()
                            progressed = true
                            // PTS-freeze detection: consecutive input
                            // advances at the SAME sample time mean the
                            // extractor is spinning, not progressing.
                            if (extractor.sampleTime == lastInputPts) {
                                frozenPtsAdvances++
                                if (frozenPtsAdvances > 500) {
                                    throw Exception(
                                        "استخراج صدا متوقف شد (timestamp صوتی جلو نمی‌رود). " +
                                            "فایل ممکن است خراب باشد."
                                    )
                                }
                            } else {
                                frozenPtsAdvances = 0
                                lastInputPts = extractor.sampleTime
                            }
                            if (sampleSize > 0) noteRealProgress()
                            // Emit on input advance too — the bar then
                            // moves smoothly even on devices whose
                            // codec drains output in big bursts.
                            emitProgress(force = false)
                        }
                    }
                }
                when (val outIndex = decoder.dequeueOutputBuffer(info, timeoutUs)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = decoder.outputFormat
                        outputSampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        // Late duration: some containers only expose
                        // it on the decoder's output format.
                        if (durationUs <= 0L) {
                            readDurationUs(outFormat).takeIf { it > 0 }?.let { durationUs = it }
                        }
                        progressed = true
                        formatChangedCount++
                        if (formatChangedCount > 100) {
                            throw Exception(
                                "استخراج صدا متوقف شد (کدک فرمت را بی‌پایان عوض می‌کند). " +
                                    "فرمت فایل با این دستگاه سازگار نیست."
                            )
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> {
                        if (outIndex >= 0) {
                            val outBuffer = decoder.getOutputBuffer(outIndex)
                                ?: throw Exception("Failed to get output buffer")
                            val chunk = ByteArray(outBuffer.remaining())
                            outBuffer.get(chunk)
                            pcmBytes.write(chunk)
                            decoder.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawOutputEos = true
                            }
                            progressed = true
                            if (info.size > 0) noteRealProgress()
                            emitProgress(force = sawOutputEos)
                        }
                    }
                }
                if (progressed) {
                    stalledRounds = 0
                } else {
                    stalledRounds++
                    if (stalledRounds > maxStalledRounds) {
                        throw Exception(
                            "استخراج صدا متوقف شد (کدک پاسخ نمی‌دهد). " +
                                "فرمت فایل را بررسی کنید یا ویدیوی دیگری امتحان کنید."
                        )
                    }
                }
                // The real-progress net: covers every spin pathology
                // above regardless of which round shape fooled the
                // dequeue-level counter.
                if (android.os.SystemClock.elapsedRealtime() - lastRealProgressMs > 45_000L) {
                    throw Exception(
                        "استخراج صدا متوقف شد (بیش از ۴۵ ثانیه پیشرفت واقعی نبود). " +
                            "فرمت فایل را بررسی کنید یا ویدیوی دیگری امتحان کنید."
                    )
                }
            }
            // 100% — decode fully drained (only when duration is
            // known; otherwise no progress was reported at all).
            emitProgress(force = true)

            return Triple(pcmBytes.toByteArray(), outputSampleRate, outputChannels)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Resamples raw int16 little-endian PCM (any sample rate, any
     * channel count) down to 16 kHz mono float in `[-1, 1]`. Lifted
     * from `WhisperEngine.resampleTo16kMonoFloat` for reuse.
     */
    private fun resampleTo16kMonoFloat(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int
    ): Triple<FloatArray, Int, Int> {
        val monoShort = int16BytesToMonoShort(pcm, channels)
        val (outSamples, outSampleRate) = resampleMonoShortTo16kFloat(monoShort, sampleRate)
        return Triple(outSamples, outSampleRate, 1)
    }

    /**
     * int16 little-endian interleaved PCM bytes → mono int16 array.
     * Split out of [resampleTo16kMonoFloat] so [decodeTo16kMonoFloat]
     * can free the raw byte buffer before allocating the (much
     * larger) float output — cuts peak memory roughly in half.
     */
    private fun int16BytesToMonoShort(pcm: ByteArray, channels: Int): ShortArray {
        val channelCount = channels.coerceAtLeast(1)
        val frameCount = pcm.size / 2 / channelCount
        val monoShort = ShortArray(frameCount)
        for (f in 0 until frameCount) {
            var sum = 0
            for (c in 0 until channelCount) {
                val byteIndex = (f * channelCount + c) * 2
                if (byteIndex + 1 < pcm.size) {
                    val low = pcm[byteIndex].toInt() and 0xFF
                    val high = pcm[byteIndex + 1].toInt()
                    sum += (high shl 8 or low).toShort().toInt()
                }
            }
            monoShort[f] = (sum / channelCount).toShort()
        }
        return monoShort
    }

    /**
     * Resample mono int16 → 16 kHz float32.
     *
     * 2026-10-20 — delegated to [AudioResampler.resampleShortMonoToFloat],
     * which runs a 4th-order anti-alias low-pass while decimating.
     * The old body here interpolated without any filtering, so
     * everything above 8 kHz in the source (mic hiss, sibilance,
     * music) folded back into the speech band and degraded what
     * whisper had to work with.
     */
    private fun resampleMonoShortTo16kFloat(
        monoShort: ShortArray,
        sampleRate: Int
    ): Pair<FloatArray, Int> {
        val out = AudioResampler.resampleShortMonoToFloat(
            monoShort, sampleRate, SAMPLE_RATE
        )
        return Pair(out, SAMPLE_RATE)
    }

    companion object {
        /** Target sample rate (Hz) — matches whisper.cpp's expected input. */
        const val SAMPLE_RATE: Int = 16_000

        /**
         * Integer/Long-safe KEY_DURATION reader. Some vendor
         * MediaFormat implementations store the duration as an
         * Integer; `getLong` on those throws ClassCastException.
         * Returns 0 when absent or unreadable.
         */
        private fun readDurationUs(format: MediaFormat): Long {
            if (!format.containsKey(MediaFormat.KEY_DURATION)) return 0L
            runCatching { return format.getLong(MediaFormat.KEY_DURATION) }
            return runCatching { format.getInteger(MediaFormat.KEY_DURATION).toLong() }
                .getOrDefault(0L)
        }
    }

    /**
     * Container-level duration fallback via
     * MediaMetadataRetriever (ms → µs). Used when the track format
     * carries no duration so the progress bar can still be driven
     * by sample PTS instead of freezing without a bar.
     */
    private fun metadataDurationUs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val ms = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            return ms * 1000L // ms → µs
        } catch (e: Exception) {
            return 0L
        } finally {
            runCatching { retriever.release() }
        }
    }
}