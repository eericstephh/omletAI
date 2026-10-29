package com.aicreatorstudio.app.ai.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import com.aicreatorstudio.app.model.AUDIO_MIME_PREFIX
import com.aicreatorstudio.app.model.AudioExtractionFailure
import com.aicreatorstudio.app.model.AudioExtractionPlanner
import com.aicreatorstudio.app.model.ExtractedAudio
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException

/** Result of [AudioExtractionService.extract]. */
sealed interface AudioExtractionOutcome {
    data class Success(val audio: ExtractedAudio) : AudioExtractionOutcome
    data class Failure(val failure: AudioExtractionFailure) : AudioExtractionOutcome
}

/**
 * 2026-10-13 — Extract Audio from Video (استخراج صدا از ویدیو).
 *
 * Thin Android wrapper around the pure
 * [com.aicreatorstudio.app.model.AudioExtractionPlanner] decisions: it reads
 * the container with `MediaExtractor`, writes a standalone `.m4a` into
 * `filesDir/audio/` with `MediaMuxer`, and reports the outcome as data.
 *
 * Two write paths, chosen by [AudioExtractionPlanner.isStreamCopy]:
 *
 *  - **AAC in MP4** (the common case) → pure stream copy. The compressed
 *    samples move extractor → muxer at disk speed, no decode, no quality
 *    loss, so a 5-minute video finishes in ~1 second.
 *  - **Anything else** (Opus / Vorbis / PCM in a video container) →
 *    decode → re-encode to AAC-LC through `MediaCodec`, then mux. The only
 *    format `MediaMuxer` can always write for audio.
 *
 * Every loop is cooperative: [ensureActive] runs each round so a cancelled
 * `viewModelScope` job aborts instantly, and a wall-clock watchdog fails a
 * stalled container instead of hanging the caller forever (the same guards
 * the whisper remux path uses, applied to this file's loop shapes).
 */
@Singleton
class AudioExtractionService @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Extracts the first audio track of [sourceUri] into a new file under
     * `filesDir/audio/`. Returns [AudioExtractionOutcome.Failure] — never
     * throws — for every expected failure so the caller can name it.
     */
    suspend fun extract(sourceUri: Uri): AudioExtractionOutcome {
        val extractor = MediaExtractor()
        var tmpFile: File? = null
        try {
            extractor.setDataSource(context, sourceUri, null)
            val trackMimes = (0 until extractor.trackCount).map { index ->
                runCatching { extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) }
                    .getOrNull()
            }
            val audioIndex = AudioExtractionPlanner.firstAudioTrackIndex(trackMimes)
            if (audioIndex < 0) return AudioExtractionOutcome.Failure(AudioExtractionFailure.NO_AUDIO_TRACK)

            val audioFormat = extractor.getTrackFormat(audioIndex)
            extractor.selectTrack(audioIndex)

            val outDir = File(context.filesDir, AUDIO_OUTPUT_DIR).apply { mkdirs() }
            val finalFile = File(outDir, AudioExtractionPlanner.outputFileName(System.currentTimeMillis()))
            tmpFile = File(outDir, finalFile.name + ".tmp").apply { delete() }

            val durationMs = readDurationMs(audioFormat, sourceUri)
            val wrote = if (AudioExtractionPlanner.isStreamCopy(trackMimes[audioIndex])) {
                copyTrack(extractor, audioFormat, tmpFile)
            } else {
                transcodeTrack(extractor, audioFormat, tmpFile)
            }
            if (!wrote) return AudioExtractionOutcome.Failure(AudioExtractionFailure.EMPTY_AUDIO_TRACK)
            if (!tmpFile.renameTo(finalFile)) {
                tmpFile.copyTo(finalFile, overwrite = true)
                tmpFile.delete()
            }
            // The clip's durationMs must be trustworthy: a zero here would
            // place an infinite-length object on the timeline.
            val finalDurationMs = durationMs.takeIf { it > 0L }
                ?: readDurationMs(null, Uri.fromFile(finalFile))
            if (finalDurationMs <= 0L) {
                finalFile.delete()
                return AudioExtractionOutcome.Failure(AudioExtractionFailure.MISSING_DURATION)
            }
            return AudioExtractionOutcome.Success(
                ExtractedAudio(filePath = finalFile.absolutePath, durationMs = finalDurationMs)
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "audio extraction failed for $sourceUri", e)
            val failure = when (e) {
                is MediaCodec.CodecException -> AudioExtractionFailure.UNSUPPORTED_CODEC
                is java.io.IOException -> AudioExtractionFailure.IO_FAILED
                else -> if (e.message?.contains("codec", ignoreCase = true) == true) {
                    AudioExtractionFailure.UNSUPPORTED_CODEC
                } else {
                    AudioExtractionFailure.UNREADABLE_SOURCE
                }
            }
            return AudioExtractionOutcome.Failure(failure)
        } finally {
            runCatching { extractor.release() }
            // A tmp file that outlived a failure is garbage; the final file
            // is the caller's asset and is never deleted here.
            runCatching { if (tmpFile != null && tmpFile.exists()) tmpFile.delete() }
        }
    }

    /**
     * AAC fast path — compressed samples move straight through the muxer.
     *
     * @return true when at least one sample was written and the muxer
     *   stopped cleanly.
     */
    private suspend fun copyTrack(
        extractor: MediaExtractor,
        audioFormat: MediaFormat,
        outFile: File
    ): Boolean {
        var muxer: MediaMuxer? = null
        return try {
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxTrack = muxer.addTrack(audioFormat)
            muxer.start()
            val info = MediaCodec.BufferInfo()
            val buffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES)
            var samples = 0L
            var lastWriteWallMs = System.currentTimeMillis()
            var lastPts = -1L
            var frozenRounds = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                if (System.currentTimeMillis() - lastWriteWallMs > STALL_TIMEOUT_MS) {
                    throw IllegalStateException("stream copy stalled: no sample in 30 s")
                }
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(muxTrack, buffer, info)
                extractor.advance()
                samples++
                lastWriteWallMs = System.currentTimeMillis()
                if (extractor.sampleTime == lastPts) {
                    if (++frozenRounds > FROZEN_PTS_LIMIT) {
                        throw IllegalStateException("stream copy stalled: PTS frozen")
                    }
                } else {
                    frozenRounds = 0
                    lastPts = extractor.sampleTime
                }
            }
            muxer.stop()
            samples > 0L
        } finally {
            runCatching { muxer?.release() }
        }
    }

    /**
     * Fallback — decode the source audio and re-encode to AAC-LC.
     * Single-threaded pump: each round feeds the decoder from the
     * extractor, drains decoder PCM into a queue, feeds the encoder from
     * that queue, and drains encoder output into the muxer. (Serving both
     * directions every round is what keeps a small encoder input buffer
     * from deadlocking the pump.)
     *
     * @return true when the encoder reached EOS with at least one sample.
     */
    private suspend fun transcodeTrack(
        extractor: MediaExtractor,
        srcFormat: MediaFormat,
        outFile: File
    ): Boolean {
        val srcMime = srcFormat.getString(MediaFormat.KEY_MIME)
            ?: throw IllegalArgumentException("audio track has no MIME")
        val sampleRate = srcFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = srcFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val decoder = MediaCodec.createDecoderByType(srcMime)
        var muxer: MediaMuxer? = null
        var muxTrack = -1
        var samples = 0L
        return try {
            decoder.configure(srcFormat, null, null, 0)
            decoder.start()
            encoder.configure(
                MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels
                ).apply {
                    setInteger(
                        MediaFormat.KEY_BIT_RATE,
                        AudioExtractionPlanner.encodeBitrate(sampleRate, channels)
                    )
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC
                    )
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, ENCODER_MAX_INPUT_BYTES)
                },
                null, null, MediaCodec.CONFIGURE_FLAG_ENCODE
            )
            encoder.start()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // PCM bytes per second at THIS source's rate, so a chunk split
            // across encoder buffers keeps its place on the timeline
            // instead of collapsing onto one instant.
            val bytesPerSecond = sampleRate.toLong() * channels * 2L

            // Pending PCM chunks waiting for an encoder input buffer.
            val pcm = ArrayDeque<Chunk>()
            val info = MediaCodec.BufferInfo()
            val timeoutUs = 10_000L
            var inputEos = false
            var encoderEos = false
            var lastProgressWallMs = System.currentTimeMillis()

            fun noteProgress() { lastProgressWallMs = System.currentTimeMillis() }

            while (!encoderEos) {
                currentCoroutineContext().ensureActive()
                if (System.currentTimeMillis() - lastProgressWallMs > STALL_TIMEOUT_MS) {
                    throw IllegalStateException("transcode stalled: no progress in 30 s")
                }
                if (!inputEos) {
                    val inIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inBuffer = decoder.getInputBuffer(inIndex)
                        val size = if (inBuffer != null) extractor.readSampleData(inBuffer, 0) else -1
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEos = true
                        } else {
                            decoder.queueInputBuffer(
                                inIndex, 0, size, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                        noteProgress()
                    }
                }
                when (val outIndex = decoder.dequeueOutputBuffer(info, timeoutUs)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outIndex >= 0) {
                        val outBuffer = decoder.getOutputBuffer(outIndex)
                        val bytes = ByteArray(info.size)
                        outBuffer?.get(bytes)
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.size > 0) {
                            pcm.addLast(Chunk(bytes, info.presentationTimeUs))
                            noteProgress()
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            pcm.addLast(Chunk(ByteArray(0), info.presentationTimeUs, eos = true))
                            noteProgress()
                        }
                    }
                }
                // Feed the encoder from the pending queue. Every PCM slice
                // needs its OWN dequeued input buffer — a queued buffer
                // belongs to the codec, so re-putting into it would write
                // into memory the encoder is still reading.
                while (pcm.isNotEmpty()) {
                    val chunk = pcm.first()
                    val inIndex = encoder.dequeueInputBuffer(timeoutUs)
                    if (inIndex < 0) break // no encoder input buffer this round
                    if (chunk.eos) {
                        pcm.removeFirst()
                        encoder.queueInputBuffer(
                            inIndex, 0, 0, chunk.ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        noteProgress()
                        continue
                    }
                    val inBuffer = encoder.getInputBuffer(inIndex) ?: break
                    inBuffer.clear()
                    val room = minOf(inBuffer.capacity(), chunk.bytes.size - chunk.sentBytes)
                    inBuffer.put(chunk.bytes, chunk.sentBytes, room)
                    val chunkPts = chunk.ptsUs +
                        (chunk.sentBytes.toLong() * 1_000_000L / bytesPerSecond)
                    encoder.queueInputBuffer(inIndex, 0, room, chunkPts, 0)
                    chunk.sentBytes += room
                    noteProgress()
                    if (chunk.sentBytes >= chunk.bytes.size) pcm.removeFirst()
                }
                when (val outIndex = encoder.dequeueOutputBuffer(info, timeoutUs)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outIndex >= 0) {
                        val outBuffer = encoder.getOutputBuffer(outIndex)
                        if (info.size > 0 && outBuffer != null) {
                            if (muxTrack < 0) {
                                muxTrack = muxer.addTrack(encoder.outputFormat)
                                muxer.start()
                            }
                            muxer.writeSampleData(muxTrack, outBuffer, info)
                            samples++
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        noteProgress()
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderEos = true
                    }
                }
            }
            muxer.stop()
            samples > 0L
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { muxer?.release() }
        }
    }

    /** One queued PCM chunk on its way into the encoder. */
    private class Chunk(
        val bytes: ByteArray,
        val ptsUs: Long,
        val eos: Boolean = false,
        var sentBytes: Int = 0
    )

    /**
     * Track/container duration in ms. Integer/Long-safe (some vendor
     * `MediaFormat`s store `KEY_DURATION` as an Integer, where a bare
     * `getLong` throws `ClassCastException`), with a
     * `MediaMetadataRetriever` fallback for duration-less streams.
     */
    private fun readDurationMs(format: MediaFormat?, uri: Uri): Long {
        val fromFormat = format?.let { readDurationUs(it) } ?: 0L
        if (fromFormat > 0L) return fromFormat / 1_000L
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readDurationUs(format: MediaFormat): Long {
        if (!format.containsKey(MediaFormat.KEY_DURATION)) return 0L
        runCatching { return format.getLong(MediaFormat.KEY_DURATION) }
        return runCatching { format.getInteger(MediaFormat.KEY_DURATION).toLong() }.getOrDefault(0L)
    }

    companion object {
        private const val TAG = "AudioExtractionService"

        /** Output directory under `filesDir`: `filesDir/audio/`. */
        const val AUDIO_OUTPUT_DIR = "audio"

        private const val COPY_BUFFER_BYTES = 1 shl 20
        private const val STALL_TIMEOUT_MS = 30_000L
        private const val FROZEN_PTS_LIMIT = 200
        private const val ENCODER_MAX_INPUT_BYTES = 64 * 1_024
    }
}
