package com.aicreatorstudio.app.db

import com.aicreatorstudio.app.model.AudioClip
import com.aicreatorstudio.app.model.VoiceEffect
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence coverage for the 2026-10-13 Voice Changer.
 *
 * ## Where the value actually lives
 *
 * There is no `voiceEffect` column. It rides the clip's `aiToolsJson` blob
 * ([EditorStateJson.ClipAiTools]) — the same carrier `motionTrack`,
 * `speedCurve` and the per-clip `look` settings use. That is a deliberate
 * choice: the blob is a single `TEXT` column, so adding a field costs no
 * Room migration and no schema version bump, and a project written by an
 * older build decodes with the field defaulting to `VoiceEffect.NONE`.
 *
 * So the two things that can break are exactly the two this file tests:
 * the JSON round-trip, and the decoder's tolerance of an absent or
 * unreadable value. (The third — `RoomProjectRepository.toVideoProject`
 * actually *reading* the decoded field — is a one-line wiring risk with no
 * observable JVM surface, and is guarded by `VoiceEffectWiringTest`'s
 * source-shape assertion instead. Room itself needs Robolectric, which this
 * project does not have.)
 */
class VoiceEffectPersistenceTest {

    // ---- name decoding ----

    @Test
    fun `every effect name decodes back to itself`() {
        VoiceEffect.entries.forEach { effect ->
            assertEquals(
                "round-trip failed for ${effect.name}",
                effect,
                EditorStateJson.voiceEffectFromName(effect.name)
            )
        }
    }

    @Test
    fun `an absent or unreadable name decodes to NONE`() {
        // The safe answer. Guessing at a different effect would silently
        // change how an existing project sounds — far worse than quietly
        // dropping to the pass-through.
        assertEquals(VoiceEffect.NONE, EditorStateJson.voiceEffectFromName(null))
        assertEquals(VoiceEffect.NONE, EditorStateJson.voiceEffectFromName(""))
        assertEquals(VoiceEffect.NONE, EditorStateJson.voiceEffectFromName("   "))
        assertEquals(VoiceEffect.NONE, EditorStateJson.voiceEffectFromName("chipmunk"))
        assertEquals(VoiceEffect.NONE, EditorStateJson.voiceEffectFromName("NOT_AN_EFFECT"))
        assertEquals(VoiceEffect.NONE, EditorStateJson.voiceEffectFromName("CHIPMUNK "))
    }

    // ---- aiTools blob ----

    @Test
    fun `NONE is omitted from the blob so untouched projects stay identical`() {
        // Writing the default would grow every project's blob and would
        // make a stale value indistinguishable from a real edit — the same
        // rule `speedCurve` and `look` already follow.
        val json = EditorStateJson.aiToolsToJson(
            mapOf("clip-1" to EditorStateJson.ClipAiTools())
        )
        assertFalse(
            "the default voice effect must not be written: $json",
            json.contains("voiceEffect")
        )
    }

    @Test
    fun `a non-default effect survives the blob round-trip`() {
        val tools = mapOf(
            "clip-1" to EditorStateJson.ClipAiTools(voiceEffect = VoiceEffect.CHIPMUNK),
            "clip-2" to EditorStateJson.ClipAiTools(voiceEffect = VoiceEffect.ECHO),
            "clip-3" to EditorStateJson.ClipAiTools()
        )
        val json = EditorStateJson.aiToolsToJson(tools)

        assertTrue(json.contains("\"voiceEffect\":\"CHIPMUNK\""))
        assertTrue(json.contains("\"voiceEffect\":\"ECHO\""))

        val decoded = EditorStateJson.aiToolsFromJson(json)
        assertEquals(VoiceEffect.CHIPMUNK, decoded.getValue("clip-1").voiceEffect)
        assertEquals(VoiceEffect.ECHO, decoded.getValue("clip-2").voiceEffect)
        assertEquals(
            "a clip with no effect must decode to NONE",
            VoiceEffect.NONE,
            decoded.getValue("clip-3").voiceEffect
        )
    }

    @Test
    fun `every effect survives the blob round-trip`() {
        val tools = VoiceEffect.entries.associate { effect ->
            effect.name to EditorStateJson.ClipAiTools(voiceEffect = effect)
        }
        val decoded = EditorStateJson.aiToolsFromJson(EditorStateJson.aiToolsToJson(tools))
        VoiceEffect.entries.forEach { effect ->
            assertEquals(
                "$effect did not survive the blob round-trip",
                effect,
                decoded.getValue(effect.name).voiceEffect
            )
        }
    }

    @Test
    fun `a blob from an older build decodes to NONE`() {
        // Hand-crafted: a row written before this feature existed. It has
        // no `voiceEffect` key at all, and must load with the default
        // rather than throwing or producing a half-built ClipAiTools.
        val legacy = JSONObject().apply {
            put("legacy-clip", JSONObject().apply {
                put("mattingEnabled", false)
            })
        }.toString()

        val decoded = EditorStateJson.aiToolsFromJson(legacy)
        assertEquals(1, decoded.size)
        assertEquals(VoiceEffect.NONE, decoded.getValue("legacy-clip").voiceEffect)
    }

    @Test
    fun `a hand-edited nonsense effect name decodes to NONE rather than crashing`() {
        val corrupt = JSONObject().apply {
            put("clip-1", JSONObject().apply {
                put("voiceEffect", "SOMETHING_ELSE")
            })
        }.toString()
        val decoded = EditorStateJson.aiToolsFromJson(corrupt)
        assertEquals(VoiceEffect.NONE, decoded.getValue("clip-1").voiceEffect)
    }

    @Test
    fun `a non-string effect value decodes to NONE`() {
        // org.json's optString on a number returns its text; the decoder
        // must not blow up, and must not invent an effect.
        val corrupt = JSONObject().apply {
            put("clip-1", JSONObject().apply {
                put("voiceEffect", 42)
            })
        }.toString()
        val decoded = EditorStateJson.aiToolsFromJson(corrupt)
        assertEquals(VoiceEffect.NONE, decoded.getValue("clip-1").voiceEffect)
    }

    // ---- audio clip list ----

    @Test
    fun `an audio clip's voice effect round-trips`() {
        val original = AudioClip(
            id = "audio-1",
            sourceUri = "content://media/audio/1",
            fileName = "voice.m4a",
            startTimeMs = 0L,
            durationMs = 10_000L,
            voiceEffect = VoiceEffect.ROBOT
        )
        val decoded = EditorStateJson.audioClipsFromJson(
            EditorStateJson.audioClipsToJson(listOf(original))
        )
        assertEquals(1, decoded.size)
        assertEquals(VoiceEffect.ROBOT, decoded.first().voiceEffect)
        assertEquals(original, decoded.first())
    }

    @Test
    fun `an audio clip with no effect omits the key and decodes to NONE`() {
        val original = AudioClip(
            id = "audio-2",
            sourceUri = "content://media/audio/2",
            fileName = "plain.m4a",
            startTimeMs = 0L,
            durationMs = 5_000L
        )
        val json = EditorStateJson.audioClipsToJson(listOf(original))
        assertFalse("the default must not be written: $json", json.contains("voiceEffect"))
        assertEquals(
            VoiceEffect.NONE,
            EditorStateJson.audioClipsFromJson(json).first().voiceEffect
        )
    }

    @Test
    fun `every effect survives the audio clip round-trip`() {
        val clips = VoiceEffect.entries.mapIndexed { index, effect ->
            AudioClip(
                id = "audio-$index",
                sourceUri = "content://media/audio/$index",
                fileName = "clip$index.m4a",
                startTimeMs = index * 1_000L,
                durationMs = 5_000L,
                voiceEffect = effect
            )
        }
        val decoded = EditorStateJson.audioClipsFromJson(
            EditorStateJson.audioClipsToJson(clips)
        )
        assertEquals(clips, decoded)
    }

    @Test
    fun `an audio row from an older build decodes to NONE`() {
        val legacy = org.json.JSONArray().put(
            JSONObject().apply {
                put("id", "legacy")
                put("sourceUri", "content://media/audio/legacy")
                put("fileName", "legacy.mp3")
                put("startTimeMs", 0L)
                put("durationMs", 2_000L)
            }
        ).toString()
        val decoded = EditorStateJson.audioClipsFromJson(legacy)
        assertEquals(1, decoded.size)
        assertEquals(VoiceEffect.NONE, decoded.first().voiceEffect)
    }
}
