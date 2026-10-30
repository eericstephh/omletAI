package com.aicreatorstudio.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-30 — the paired-audio mirror.
 *
 * `VideoProject` keeps a video clip's soundtrack in two places: the
 * clip's own `volume`, and an `AudioClip` on the "Video Audio" lane. The
 * preview mixer and the export read the **clip**; the volume sheet and
 * the lane tile read the **AudioClip**. Anything that writes one without
 * the other is invisible in one of the two places, which is how this
 * project lost the fade fields, the voice effect, and — until this test
 * — the paired volume entirely.
 *
 * [PairedAudio] is now the single rule, so these are the assertions that
 * stop the next mutator from re-opening the hole.
 */
class PairedAudioMirrorTest {

    private fun video(
        id: String = "v1",
        start: Long = 0L,
        trimStart: Long = 0L,
        trimEnd: Long = 2_000L,
        volume: Float = 1f,
        durationMs: Long = 5_000L
    ) = VideoClip(
        id = id,
        uri = "file:///$id.mp4",
        fileName = "$id.mp4",
        durationMs = durationMs,
        width = 1920,
        height = 1080,
        fileSizeBytes = 1L,
        importedAtEpochMillis = 0L,
        volume = volume,
        trimStartMs = trimStart,
        trimEndMs = trimEnd,
        timelineStartMs = start
    )

    private fun paired(
        originId: String? = "v1",
        start: Long = 0L,
        duration: Long = 2_000L,
        offset: Long = 0L,
        volume: Float = 1f,
        muted: Boolean = false
    ) = AudioClip(
        id = "a1",
        sourceUri = "file:///v1.mp4",
        fileName = "v1.mp4",
        startTimeMs = start,
        durationMs = duration,
        clipOffsetMs = offset,
        volume = volume,
        muted = muted,
        originVideoClipId = originId
    )

    @Test
    fun `isPaired follows the origin id, not the uri`() {
        assertTrue(PairedAudio.isPaired(paired()))
        assertFalse("standalone music has no origin", PairedAudio.isPaired(paired(originId = null)))
    }

    @Test
    fun `mirrorGeometry copies position, source offset and length`() {
        val v = video(start = 3_000L, trimStart = 500L, trimEnd = 1_500L)
        val a = paired(start = 0L, duration = 999L, offset = 0L)

        val mirrored = PairedAudio.mirrorGeometry(a, v)

        assertEquals("tile must sit where the clip sits", 3_000L, mirrored.startTimeMs)
        assertEquals("tile must read from the clip's trim start", 500L, mirrored.clipOffsetMs)
        // effectiveDurationMs = trimEnd - trimStart = 1000
        assertEquals("tile length must be the clip's trimmed span", 1_000L, mirrored.durationMs)
        // Identity and the volume/mute flags are not geometry.
        assertEquals("a1", mirrored.id)
        assertEquals(1f, mirrored.volume, 0f)
    }

    @Test
    fun `mirrorGeometry leaves a standalone audio object alone`() {
        val music = paired(originId = null, start = 7_000L, duration = 4_000L, offset = 250L)
        val mirrored = PairedAudio.mirrorGeometry(music, video())
        assertSame("user-placed audio must not be moved by a video edit", music, mirrored)
    }

    @Test
    fun `mirrorGeometry never produces a zero-length tile`() {
        // A clip trimmed to nothing (or a source shorter than its trim
        // start) yields effectiveDurationMs == 0; a 0-length audio object
        // is not renderable and the lane would draw a zero-width tile.
        val degenerate = video(trimStart = 5_000L, trimEnd = 5_000L)
        val mirrored = PairedAudio.mirrorGeometry(paired(), degenerate)
        assertEquals(1L, mirrored.durationMs)
    }

    @Test
    fun `mirrorGeometry clamps a negative timeline start`() {
        val mirrored = PairedAudio.mirrorGeometry(paired(), video(start = -1L))
        assertEquals(0L, mirrored.startTimeMs)
    }

    @Test
    fun `volumeForVideo forwards a paired level and refuses standalone audio`() {
        assertEquals(0.4f, PairedAudio.volumeForVideo(paired(volume = 0.4f))!!, 1e-6f)
        assertNull(
            "a music track has no video clip to write through to",
            PairedAudio.volumeForVideo(paired(originId = null, volume = 0.4f))
        )
    }

    @Test
    fun `mute wins over the remembered level`() {
        assertEquals(0f, PairedAudio.volumeForVideo(paired(volume = 0.8f, muted = true))!!, 0f)
    }

    @Test
    fun `unmuting restores the remembered level instead of unity`() {
        // The tile keeps its own level while muted, so unmute must return
        // to it. Snapping to 1f would silently change the user's mix.
        assertEquals(0.35f, PairedAudio.volumeForVideo(paired(volume = 0.35f, muted = false))!!, 1e-6f)
    }

    @Test
    fun `the gain range is enforced by the model, so the helpers only guard the edges`() {
        // `AudioClip.init` rejects a volume outside 0..2 outright
        // ("AudioClip.volume must be in 0..2"), so an invalid level can
        // never reach these helpers from a real project. The clamps in
        // `PairedAudio` exist for the OTHER direction — a `VideoClip`
        // volume handed to `withVideoVolume` — and for `copy()`, which
        // runs `init` again.
        val atMax = paired(volume = 2f)
        assertEquals(2f, PairedAudio.volumeForVideo(atMax)!!, 0f)

        val zero = paired(volume = 0f)
        assertEquals(0f, PairedAudio.volumeForVideo(zero)!!, 0f)

        val thrown = runCatching { paired(volume = 9f) }.exceptionOrNull()
        assertTrue(
            "the model must keep rejecting out-of-range gain; if this stops throwing, " +
                "PairedAudio's clamp becomes the only guard and a bad blob can reach the mixer",
            thrown is IllegalArgumentException
        )
    }

    @Test
    fun `withVideoVolume mirrors the clip level and clears a stale mute flag`() {
        val muted = paired(volume = 1f, muted = true)
        val out = PairedAudio.withVideoVolume(muted, 0.5f)
        assertEquals(0.5f, out.volume, 1e-6f)
        assertFalse("the tile cannot stay muted while the clip has gain", out.muted)
    }

    @Test
    fun `withVideoVolume mutes the tile when the clip is silenced`() {
        val out = PairedAudio.withVideoVolume(paired(volume = 0.5f), 0f)
        assertEquals(0f, out.volume, 0f)
        assertTrue("zero gain on the clip means the tile reads as muted", out.muted)
    }

    @Test
    fun `withVideoVolume leaves standalone audio alone`() {
        val music = paired(originId = null, volume = 0.9f)
        assertSame(music, PairedAudio.withVideoVolume(music, 0f))
    }

    @Test
    fun `the two directions round-trip to the same level`() {
        // audio -> video -> audio must be stable, otherwise dragging the
        // tile's slider and then the clip's would drift the value.
        val a = paired(volume = 0.62f)
        val asVideo = PairedAudio.volumeForVideo(a)!!
        val back = PairedAudio.withVideoVolume(a, asVideo)
        assertEquals(a.volume, back.volume, 1e-6f)
        assertEquals(a.muted, back.muted)
    }
}

/**
 * The ViewModel cannot be constructed in this JVM (Hilt + Android
 * dependencies), so the *wiring* is asserted against the source text.
 *
 * Every assertion here is **positive** — the file must contain the call —
 * which is why this needs no comment stripper: a KDoc that mentions the
 * helper cannot make a `contains` assertion pass or fail wrongly, it can
 * only help it pass. (The negative-assertion stripper problem documented
 * for the other wiring tests does not apply.)
 *
 * This is the assertion that actually matters: `PairedAudio` can be
 * perfect and still be dead code if a mutator forgets to call it, which
 * is precisely the failure this whole change exists to end.
 */
class PairedAudioWiringTest {

    private fun source(relativePath: String): String {
        var dir = java.io.File(System.getProperty("user.dir") ?: ".")
        while (!java.io.File(dir, "app/src/main/java").isDirectory) {
            dir = dir.parentFile ?: error("cannot locate the repository root")
        }
        return java.io.File(dir, relativePath).readText()
    }

    @Test
    fun `every geometry mutator mirrors the paired tile`() {
        val vm = source("app/src/main/java/com/aicreatorstudio/app/viewmodel/EditorViewModel.kt")
        assertEquals(
            "setClipTrim and setClipSpeed must both call PairedAudio.mirrorGeometry",
            2,
            Regex(Regex.escape("PairedAudio.mirrorGeometry(audio, next)")).findAll(vm).count()
        )
    }

    @Test
    fun `both audio-object setters write through to the video clip`() {
        val vm = source("app/src/main/java/com/aicreatorstudio/app/viewmodel/EditorViewModel.kt")
        assertEquals(
            "updateAudioClipVolume and updateAudioClipMute must both forward the level",
            2,
            Regex(Regex.escape("PairedAudio.volumeForVideo(target)")).findAll(vm).count()
        )
    }

    @Test
    fun `the video-clip volume setter mirrors into the tile`() {
        val vm = source("app/src/main/java/com/aicreatorstudio/app/viewmodel/EditorViewModel.kt")
        assertTrue(
            "updateClipVolume must call PairedAudio.withVideoVolume, or the tile keeps " +
                "showing a level the preview and export no longer use",
            vm.contains("PairedAudio.withVideoVolume(audio, v)")
        )
    }

    @Test
    fun `touching the tile persists both tables`() {
        // The two tables live in different blobs (saveClips vs
        // saveProjectMeta), so a write that crosses them must save both.
        val vm = source("app/src/main/java/com/aicreatorstudio/app/viewmodel/EditorViewModel.kt")
        val body = vm.substringAfter("fun updateAudioClipVolume(")
            .substringBefore("fun updateAudioClipMute(")
        assertTrue(
            "updateAudioClipVolume must persist the clips table it now writes to",
            body.contains("persistClips()")
        )
        assertTrue(
            "updateAudioClipVolume must still persist the audio table",
            body.contains("persistProjectMeta()")
        )
    }
}

