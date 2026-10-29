package com.aicreatorstudio.app.db

import com.aicreatorstudio.app.model.AudioClip
import com.aicreatorstudio.app.util.BeatDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for `AudioClip.beatData` through
 * [EditorStateJson.audioClipsToJson] / [EditorStateJson.audioClipsFromJson].
 *
 * These matter more than the average serialisation test because beat markers
 * are *derived* data: re-running detection is expensive (a full decode plus an
 * FFT sweep) and non-deterministic across app versions if the detector's
 * constants ever change. Losing them on restart is not just an inconvenience,
 * it means the user's markers can silently differ after every reopen.
 *
 * `org.json` is a real dependency on the JVM test classpath (unlike the
 * `android.*` stubs that `isReturnDefaultValues` neutralises), so these tests
 * exercise the actual parser.
 */
class AudioBeatPersistenceTest {

    private fun clip(
        id: String = "clip-1",
        beatData: BeatDetector.AudioBeatData? = null
    ) = AudioClip(
        id = id,
        sourceUri = "file:///music/track.m4a",
        fileName = "track.m4a",
        startTimeMs = 0L,
        durationMs = 10_000L,
        beatData = beatData
    )

    private fun roundTrip(clips: List<AudioClip>): List<AudioClip> =
        EditorStateJson.audioClipsFromJson(EditorStateJson.audioClipsToJson(clips))

    // ---------------------------------------------------------------- //

    @Test
    fun `detected markers survive a round trip`() {
        val data = BeatDetector.AudioBeatData(
            markers = listOf(
                BeatDetector.BeatMarker(500L, 1.0f),
                BeatDetector.BeatMarker(1_000L, 0.5f),
                BeatDetector.BeatMarker(1_500L, 0.75f)
            ),
            bpm = 120
        )
        val restored = roundTrip(listOf(clip(beatData = data))).single()
        assertNotNull("beatData must survive save/load", restored.beatData)
        assertEquals(3, restored.beatData!!.markers.size)
        assertEquals(listOf(500L, 1_000L, 1_500L), restored.beatData!!.markers.map { it.timestampMs })
        assertEquals(120, restored.beatData!!.bpm)
    }

    @Test
    fun `marker intensity survives within float tolerance`() {
        val data = BeatDetector.AudioBeatData(
            markers = listOf(BeatDetector.BeatMarker(250L, 0.1234f))
        )
        val restored = roundTrip(listOf(clip(beatData = data))).single()
        assertEquals(0.1234f, restored.beatData!!.markers.single().intensity, 1e-6f)
    }

    @Test
    fun `a null beatData writes no key at all`() {
        val json = EditorStateJson.audioClipsToJson(listOf(clip(beatData = null)))
        val obj = org.json.JSONArray(json).getJSONObject(0)
        assertTrue(
            "an unanalysed clip must not carry a beatData key",
            !obj.has("beatData")
        )
        assertNull(roundTrip(listOf(clip(beatData = null))).single().beatData)
    }

    @Test
    fun `an analysed track with no beats round-trips as analysed-and-empty`() {
        // The distinction that matters: null == "never analysed", empty ==
        // "analysed, genuinely no beats". If this collapsed to null the UI
        // would re-offer Detect forever on a silent track.
        val restored = roundTrip(
            listOf(clip(beatData = BeatDetector.AudioBeatData(emptyList())))
        ).single()
        assertNotNull("analysed-but-empty must not become null", restored.beatData)
        assertTrue(restored.beatData!!.markers.isEmpty())
        assertNull(restored.beatData!!.bpm)
    }

    @Test
    fun `absence of a bpm is preserved`() {
        val data = BeatDetector.AudioBeatData(
            markers = listOf(BeatDetector.BeatMarker(100L)),
            bpm = null
        )
        val restored = roundTrip(listOf(clip(beatData = data))).single()
        assertNull("no tempo must stay absent", restored.beatData!!.bpm)
    }

    @Test
    fun `markers are restored in ascending time order even if stored unordered`() {
        // A partially written or hand-edited blob must not produce a
        // non-monotonic list: the timeline's snapping does a binary search,
        // which silently returns wrong answers on unsorted input.
        val json = """
            [{"id":"c1","sourceUri":"u","fileName":"f","startTimeMs":0,
              "durationMs":1000,
              "beatData":{"markers":[{"t":900,"i":1.0},{"t":100,"i":1.0},{"t":500,"i":1.0}]}}]
        """.trimIndent()
        val restored = EditorStateJson.audioClipsFromJson(json).single()
        assertEquals(
            listOf(100L, 500L, 900L),
            restored.beatData!!.markers.map { it.timestampMs }
        )
    }

    @Test
    fun `duplicate timestamps collapse to the strongest marker`() {
        val json = """
            [{"id":"c1","sourceUri":"u","fileName":"f","startTimeMs":0,
              "durationMs":1000,
              "beatData":{"markers":[{"t":500,"i":0.2},{"t":500,"i":0.9}]}}]
        """.trimIndent()
        val restored = EditorStateJson.audioClipsFromJson(json).single()
        assertEquals(1, restored.beatData!!.markers.size)
        assertEquals(0.9f, restored.beatData!!.markers.single().intensity, 1e-6f)
    }

    @Test
    fun `negative timestamps are dropped on read`() {
        val json = """
            [{"id":"c1","sourceUri":"u","fileName":"f","startTimeMs":0,
              "durationMs":1000,
              "beatData":{"markers":[{"t":-5,"i":1.0},{"t":400,"i":1.0}]}}]
        """.trimIndent()
        val restored = EditorStateJson.audioClipsFromJson(json).single()
        assertEquals(listOf(400L), restored.beatData!!.markers.map { it.timestampMs })
    }

    @Test
    fun `out of range intensity is clamped and non-finite falls back`() {
        val json = """
            [{"id":"c1","sourceUri":"u","fileName":"f","startTimeMs":0,
              "durationMs":1000,
              "beatData":{"markers":[
                {"t":100,"i":5.0},{"t":200,"i":-3.0},{"t":300,"i":"oops"}]}}]
        """.trimIndent()
        val restored = EditorStateJson.audioClipsFromJson(json).single()
        val byTime = restored.beatData!!.markers.associateBy { it.timestampMs }
        assertEquals(1.0f, byTime[100L]!!.intensity, 1e-6f)
        assertEquals(0.0f, byTime[200L]!!.intensity, 1e-6f)
        assertEquals(1.0f, byTime[300L]!!.intensity, 1e-6f)
    }

    @Test
    fun `a malformed beatData payload yields null rather than throwing`() {
        val json = """
            [{"id":"c1","sourceUri":"u","fileName":"f","startTimeMs":0,
              "durationMs":1000,"beatData":"not-an-object"}]
        """.trimIndent()
        val restored = EditorStateJson.audioClipsFromJson(json).single()
        assertNull(restored.beatData)
    }

    @Test
    fun `a beatData object with no markers array reads as analysed-and-empty`() {
        val json = """
            [{"id":"c1","sourceUri":"u","fileName":"f","startTimeMs":0,
              "durationMs":1000,"beatData":{}}]
        """.trimIndent()
        val restored = EditorStateJson.audioClipsFromJson(json).single()
        assertNotNull(restored.beatData)
        assertTrue(restored.beatData!!.markers.isEmpty())
    }

    @Test
    fun `a non-positive stored bpm is treated as absent`() {
        val json = """
            [{"id":"c1","sourceUri":"u","fileName":"f","startTimeMs":0,
              "durationMs":1000,
              "beatData":{"markers":[{"t":100,"i":1.0}],"bpm":0}}]
        """.trimIndent()
        assertNull(EditorStateJson.audioClipsFromJson(json).single().beatData!!.bpm)
    }

    @Test
    fun `legacy blobs with no beatData key still decode`() {
        // A project saved before this feature existed.
        val legacy = """
            [{"id":"c1","sourceUri":"u","fileName":"f","startTimeMs":0,
              "durationMs":1000,"volume":1.0,"muted":false}]
        """.trimIndent()
        val restored = EditorStateJson.audioClipsFromJson(legacy).single()
        assertNull("legacy audio must have no beats", restored.beatData)
        assertEquals("c1", restored.id)
    }

    @Test
    fun `beat data coexists with every other audio field`() {
        val rich = AudioClip(
            id = "c9",
            sourceUri = "file:///x.m4a",
            fileName = "x.m4a",
            startTimeMs = 2_000L,
            durationMs = 8_000L,
            clipOffsetMs = 300L,
            volume = 1.5f,
            muted = true,
            fadeInMs = 250L,
            fadeOutMs = 400L,
            waveformCacheKey = "wf-c9",
            originVideoClipId = "video-9",
            cachedPcmPath = "/cache/x.pcm",
            beatData = BeatDetector.AudioBeatData(
                markers = listOf(BeatDetector.BeatMarker(120L, 0.8f)),
                bpm = 128
            )
        )
        val restored = roundTrip(listOf(rich)).single()
        assertEquals(rich.copy(beatData = restored.beatData), restored)
        assertEquals(1, restored.beatData!!.markers.size)
        assertEquals(128, restored.beatData!!.bpm)
    }

    @Test
    fun `beat data on one clip does not leak onto its neighbours`() {
        val withBeats = clip(id = "a", beatData = BeatDetector.AudioBeatData(
            listOf(BeatDetector.BeatMarker(300L), BeatDetector.BeatMarker(600L)), 100
        ))
        val without = clip(id = "b", beatData = null)
        val restored = roundTrip(listOf(withBeats, without))
        assertEquals(2, restored.first { it.id == "a" }.beatData!!.markers.size)
        assertNull(restored.first { it.id == "b" }.beatData)
    }

    @Test
    fun `a large marker set round-trips without truncation`() {
        // ~5 minutes of 120 BPM beats.
        val markers = (0 until 600).map {
            BeatDetector.BeatMarker(it * 500L, (it % 10) / 10f)
        }
        val data = BeatDetector.AudioBeatData(markers, 120)
        val restored = roundTrip(listOf(clip(beatData = data))).single()
        assertEquals(600, restored.beatData!!.markers.size)
        assertEquals(markers.map { it.timestampMs }, restored.beatData!!.markers.map { it.timestampMs })
    }

    @Test
    fun `the serialised blob stays compact for a long track`() {
        // Markers are the bulk of the payload, so guard against a format that
        // stores redundant keys per marker. 600 markers should be a few kB,
        // not tens of kB.
        val markers = (0 until 600).map { BeatDetector.BeatMarker(it * 500L, 1.0f) }
        val json = EditorStateJson.audioClipsToJson(
            listOf(clip(beatData = BeatDetector.AudioBeatData(markers, 120)))
        )
        assertTrue(
            "600 markers serialised to ${json.length} chars; the format may be storing redundant keys",
            json.length < 20_000
        )
    }

    @Test
    fun `the raw payload shape uses the documented keys`() {
        val data = BeatDetector.AudioBeatData(
            markers = listOf(BeatDetector.BeatMarker(750L, 0.4f)),
            bpm = 90
        )
        val json = EditorStateJson.audioClipsToJson(listOf(clip(beatData = data)))
        val clipObj = org.json.JSONArray(json).getJSONObject(0)
        // beatData is a nested object, not an escaped string.
        val beat = clipObj.getJSONObject("beatData")
        assertEquals(90, beat.getInt("bpm"))
        val marker = beat.getJSONArray("markers").getJSONObject(0)
        assertEquals(750L, marker.getLong("t"))
        assertEquals(0.4, marker.getDouble("i"), 1e-6)
    }
}
