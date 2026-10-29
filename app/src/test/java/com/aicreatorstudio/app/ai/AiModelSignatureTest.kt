package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-20 — pins the ONNX / TFLite tensor signatures of the two
 * bundled neural models against the code that drives them.
 *
 * ## Why this file exists
 *
 * Both of these models were being driven with the WRONG signature, and
 * in both cases the failure was total:
 *
 *  - **Silero VAD** — the code passed `input`/`sr`/`h`/`c` (a v4-style
 *    graph) and read three outputs. The bundled `silero_vad.onnx` is a
 *    v5-style graph: inputs `input`/`state`/`sr`, outputs
 *    `output`/`stateN`. Every frame therefore threw, which is why
 *    "حذف سکوت" silently did nothing (the caller swallowed the error)
 *    and Smart-Highlight analysis failed outright.
 *  - **ESRGAN** — the code assumed a 256 px input tile while the
 *    bundled `ESRGAN.tflite` is hard-fixed at `[1,50,50,3]`. The
 *    tiler then read `up.width` AFTER `up.recycle()`, so every upscale
 *    died with `IllegalStateException` on its first tile.
 *
 * A model file can be swapped by the download task without any Kotlin
 * change, so a code-only review cannot catch this class of bug. This
 * test parses the actual binaries and asserts the signatures the
 * engines depend on — the cheapest possible tripwire.
 *
 * The parsers below are deliberately minimal (no protobuf / flatbuffer
 * dependency): they read only what the assertions need.
 */
class AiModelSignatureTest {

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("settings.gradle.kts not found above ${File(".").absolutePath}")
    }

    private fun asset(relative: String): File {
        val f = File(repoRoot(), relative)
        assertTrue("missing asset: $f", f.isFile)
        return f
    }

    // -----------------------------------------------------------------
    // Minimal ONNX (protobuf) reader — graph inputs / outputs only.
    // -----------------------------------------------------------------

    private class Proto(private val b: ByteArray) {
        var pos = 0

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val x = b[pos++].toInt() and 0xFF
                result = result or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) break
                shift += 7
            }
            return result
        }

        /**
         * Yields (fieldNumber, wireType, payload) for the WHOLE message.
         *
         * Each [Proto] instance wraps exactly one protobuf message, so
         * every read starts from offset 0 — otherwise the second call
         * would see an exhausted buffer and silently return nothing.
         */
        fun fields(): List<Triple<Int, Int, ByteArray?>> {
            pos = 0
            val out = ArrayList<Triple<Int, Int, ByteArray?>>()
            while (pos < b.size) {
                val key = readVarint()
                val fieldNo = (key ushr 3).toInt()
                val wire = (key and 0x7).toInt()
                when (wire) {
                    0 -> {
                        val v = readVarint()
                        out += Triple(fieldNo, 0, v.toString().toByteArray())
                    }
                    2 -> {
                        val len = readVarint().toInt()
                        out += Triple(fieldNo, 2, b.copyOfRange(pos, pos + len))
                        pos += len
                    }
                    5 -> { pos += 4 }
                    1 -> { pos += 8 }
                    else -> throw IllegalStateException("unsupported wire type $wire")
                }
            }
            return out
        }

        fun all(fieldNo: Int): List<ByteArray> =
            fields().filter { it.first == fieldNo && it.third != null }.map { it.third!! }

        fun varint(fieldNo: Int): Long? {
            val raw = fields().firstOrNull { it.first == fieldNo && it.second == 0 } ?: return null
            return raw.third!!.toString(Charsets.UTF_8).toLong()
        }
    }

    private data class OnnxTensor(val name: String, val dtype: String, val shape: List<String>)

    private fun onnxTensors(bytes: ByteArray): Pair<List<OnnxTensor>, List<OnnxTensor>> {
        val model = Proto(bytes)
        val graphs = model.all(7) // ModelProto.graph
        assertTrue("ONNX model must contain a graph", graphs.isNotEmpty())
        val g = Proto(graphs[0])
        fun parseType(typeBytes: ByteArray): Pair<String, List<String>> {
            val t = Proto(typeBytes)
            val tensorType = t.all(1).firstOrNull() ?: return "?" to emptyList()
            val tt = Proto(tensorType)
            val elem = tt.varint(1)?.toInt() ?: 0
            val dtype = when (elem) {
                1 -> "FLOAT32"; 2 -> "UINT8"; 3 -> "INT8"; 6 -> "INT32"; 7 -> "INT64"
                9 -> "BOOL"; 10 -> "FLOAT16"; 11 -> "DOUBLE"; else -> "elem$elem"
            }
            val shape = tt.all(2).firstOrNull()?.let { shapeBytes ->
                val sp = Proto(shapeBytes)
                sp.all(1).map { dim ->
                    val d = Proto(dim)
                    val value = d.varint(1)
                    if (value != null) value.toString()
                    else d.all(2).firstOrNull()?.toString(Charsets.UTF_8) ?: "?"
                }
            } ?: emptyList()
            return dtype to shape
        }
        fun valueInfos(fieldNo: Int): List<OnnxTensor> =
            g.all(fieldNo).map { raw ->
                val v = Proto(raw)
                val name = v.all(1).firstOrNull()?.toString(Charsets.UTF_8) ?: "?"
                val typeBytes = v.all(2).firstOrNull()
                val (dtype, shape) = if (typeBytes != null) parseType(typeBytes) else "?" to emptyList()
                OnnxTensor(name, dtype, shape)
            }
        return valueInfos(11) to valueInfos(12) // GraphProto.input / .output
    }

    // -----------------------------------------------------------------
    // Minimal TFLite (flatbuffer) reader — subgraph input / output.
    // -----------------------------------------------------------------

    private class Fb(private val b: ByteArray) {
        fun u16(p: Int) = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
        fun i32(p: Int) = java.nio.ByteBuffer.wrap(b, p, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        fun indirect(p: Int) = p + i32(p)
        fun vtable(tab: Int): Int = tab - i32(tab)
        fun fieldOffset(tab: Int, fid: Int): Int {
            val vt = vtable(tab)
            val vtSize = u16(vt)
            val vo = 4 + fid * 2
            if (vo >= vtSize) return 0
            return u16(vt + vo)
        }
        fun table(p: Int) = indirect(p)
        fun vector(p: Int): Pair<Int, Int> {
            val q = indirect(p)
            return (q + 4) to i32(q)
        }
        fun string(p: Int): String {
            val q = indirect(p)
            val len = i32(q)
            return String(b, q + 4, len, Charsets.UTF_8)
        }
    }

    private data class TfliteTensor(val name: String, val shape: List<Int>)

    private fun tfliteTensors(bytes: ByteArray): Pair<List<TfliteTensor>, List<TfliteTensor>> {
        val fb = Fb(bytes)
        val root = fb.indirect(0)
        val subOff = fb.fieldOffset(root, 2) // Model.subgraphs
        val (subP, subN) = fb.vector(root + subOff)
        assertTrue("TFLite model must contain a subgraph", subN > 0)
        val sg = fb.indirect(subP)

        val tOff = fb.fieldOffset(sg, 0) // SubGraph.tensors
        val (tP, tN) = fb.vector(sg + tOff)
        val iOff = fb.fieldOffset(sg, 1) // SubGraph.inputs
        val (iP, iN) = fb.vector(sg + iOff)
        val oOff = fb.fieldOffset(sg, 2) // SubGraph.outputs
        val (oP, oN) = fb.vector(sg + oOff)

        fun tensorAt(idx: Int): TfliteTensor {
            val tp = fb.indirect(tP + idx * 4)
            val shOff = fb.fieldOffset(tp, 0) // Tensor.shape
            val shape = if (shOff == 0) emptyList() else {
                val (sp, sn) = fb.vector(tp + shOff)
                (0 until sn).map { fb.i32(sp + it * 4) }
            }
            val nOff = fb.fieldOffset(tp, 3) // Tensor.name
            val name = if (nOff == 0) "" else fb.string(tp + nOff)
            return TfliteTensor(name, shape)
        }
        val inputs = (0 until iN).map { tensorAt(fb.i32(iP + it * 4)) }
        val outputs = (0 until oN).map { tensorAt(fb.i32(oP + it * 4)) }
        assertTrue("TFLite model must declare tensors", tN > 0)
        return inputs to outputs
    }

    // -----------------------------------------------------------------
    // Silero VAD
    // -----------------------------------------------------------------

    @Test
    fun `silero vad onnx exposes the v5 style signature the detector drives`() {
        val bytes = asset(
            "app/src/main/assets/ai-models/silero-vad/silero_vad.onnx"
        ).readBytes()
        val (inputs, outputs) = onnxTensors(bytes)

        assertEquals(
            "Silero VAD input names changed — the detector must be updated",
            listOf("input", "state", "sr"),
            inputs.map { it.name }
        )
        assertEquals(
            "Silero VAD output names changed — the detector must be updated",
            listOf("output", "stateN"),
            outputs.map { it.name }
        )

        val state = inputs.first { it.name == "state" }
        assertEquals("the LSTM state tensor must be FLOAT32", "FLOAT32", state.dtype)
        assertEquals(
            "the LSTM state tensor is [2, batch, 128] — row 0 = hidden, row 1 = cell",
            listOf("2", "?", "128"),
            state.shape
        )

        val sr = inputs.first { it.name == "sr" }
        assertEquals(
            "the sample-rate input must be INT64 — an INT32 tensor is rejected by ORT",
            "INT64",
            sr.dtype
        )
    }

    @Test
    fun `the silero detector drives the graph the model actually exposes`() {
        val src = File(
            repoRoot(),
            "app/src/main/java/com/aicreatorstudio/app/ai/vad/SileroVadDetector.kt"
        ).readText()

        assertTrue(
            "the detector must pass a SINGLE 'state' input — the graph has no 'h'/'c'",
            src.contains("\"state\" to stT")
        )
        assertTrue(
            "the v4-style 'h' input does not exist in this graph",
            !src.contains("\"h\" to hTensor")
        )
        assertTrue(
            "the v4-style 'c' input does not exist in this graph",
            !src.contains("\"c\" to cTensor")
        )
        assertTrue(
            "the sample rate must be sent as INT64",
            src.contains("longArrayOf(16000L)")
        )
        assertTrue(
            "the state tensor must be [2, 1, 128]",
            src.contains("longArrayOf(2, 1, 128)")
        )
        assertTrue(
            "the graph has exactly two outputs — a third read would go out of bounds",
            !src.contains("output[2]")
        )
    }

    // -----------------------------------------------------------------
    // ESRGAN
    // -----------------------------------------------------------------

    @Test
    fun `esrgan tflite is fixed at 50x50 in and 200x200 out`() {
        val bytes = asset(
            "app/src/main/assets/ai-models/esrgan_tflite/ESRGAN.tflite"
        ).readBytes()
        val (inputs, outputs) = tfliteTensors(bytes)

        assertEquals(1, inputs.size)
        assertEquals(listOf(1, 50, 50, 3), inputs[0].shape)
        assertEquals(1, outputs.size)
        assertEquals(listOf(1, 200, 200, 3), outputs[0].shape)
    }

    @Test
    fun `the esrgan tiler never reads a recycled bitmap and sizes tiles from the model`() {
        val src = File(
            repoRoot(),
            "app/src/main/java/com/aicreatorstudio/app/ai/upscale/EsrganTiler.kt"
        ).readText()

        assertTrue(
            "the tile dimensions must be captured BEFORE the bitmap is recycled — " +
                "reading them after `recycle()` throws IllegalStateException on Android",
            src.contains("val upW = up.width") && src.contains("val upH = up.height")
        )
        assertTrue(
            "the tile must be sized from the MODEL's native input edge, not a hard-coded 256",
            src.contains("val nativeEdge =")
        )
        assertTrue(
            "the drawn tile width must be the TILE's neural size (tw * netScale), " +
                "not the raw network output width",
            src.contains("val drawW = tw * netScale * s")
        )
        assertTrue(
            "the per-tile output contract must be the ORIGINAL tile's size, " +
                "not the resized working patch's size",
            src.contains("val expectW = tile.width * netScale")
        )
    }
}
