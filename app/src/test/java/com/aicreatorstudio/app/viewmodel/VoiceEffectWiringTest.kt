package com.aicreatorstudio.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-shape guards for the **Voice Changer** (تغییر صدا) wiring.
 *
 * ## Why this is a source-shape test
 *
 * The three places this feature can silently break are all out of reach of
 * a JVM test:
 *
 *  - the **preview** is a `@Composable` and an `ExoPlayer` (needs a
 *    composition and a `Context`);
 *  - the **export** is behind `Media3TransformerExportEngine` (needs a
 *    `Context`, and this project has no Robolectric);
 *  - the **ViewModel** is a Hilt `@HiltViewModel` (needs Android + Dagger
 *    to construct).
 *
 * The parts that *are* executed are covered elsewhere: `VoiceEffectTest`
 * for the parameter resolution and the clamps, `VoiceEffectDspTest` for the
 * actual filter / modulator / delay maths, and `VoiceEffectPersistenceTest`
 * for the JSON codec. What is left — and what only a source-shape guard can
 * catch — is the **wiring**: that both renderers build the same two
 * processors in the same order, that the export inserts them before the
 * speed stage, and that the value reaches every layer.
 *
 * That is not a hypothetical risk. This project's own history is a list of
 * fields that were persisted but never rendered, parameters forwarded to
 * three of four call sites, and a stem player that read a field nobody had
 * passed it.
 *
 * Comments are stripped before every assertion, so a KDoc that merely
 * *mentions* a call cannot satisfy a guard.
 */
class VoiceEffectWiringTest {

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("settings.gradle.kts not found above ${File(".").absolutePath}")
    }

    private fun read(relative: String): String {
        val f = File(repoRoot(), relative)
        assertTrue("$relative missing at $f", f.isFile)
        return f.readText()
    }

    /** Strips `//` and block comments, then returns the remaining code. */
    private fun codeLinesOnly(source: String): String {
        val blockEnd = "*" + "/"
        val out = StringBuilder()
        var inBlock = false
        source.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (inBlock) {
                if (trimmed.contains(blockEnd)) inBlock = false
                return@forEach
            }
            if (trimmed.startsWith("/*")) {
                if (!trimmed.contains(blockEnd) ||
                    trimmed.indexOf(blockEnd) == trimmed.lastIndexOf(blockEnd)
                ) {
                    inBlock = true
                }
                // A ONE-LINE `/** ... */` opens and closes on this very
                // line; without this the block would stay open and swallow
                // every line below it, and the guards below would fail for
                // the wrong reason.
                if (trimmed.contains(blockEnd)) inBlock = false
                return@forEach
            }
            out.append(line.substringBefore("//")).append('\n')
        }
        return out.toString()
    }

    private fun indexOfOrFail(haystack: String, needle: String, where: String): Int {
        val index = haystack.indexOf(needle)
        assertTrue("'$needle' not found in $where", index >= 0)
        return index
    }

    // ---- paths ----

    private val model = "app/src/main/java/com/aicreatorstudio/app/model/VoiceEffect.kt"
    private val dsp = "app/src/main/java/com/aicreatorstudio/app/model/VoiceEffectDsp.kt"
    private val processor =
        "app/src/main/java/com/aicreatorstudio/app/export/VoiceEffectAudioProcessor.kt"
    private val previewAudio =
        "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/VoiceEffectPreviewAudio.kt"
    private val panel =
        "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/VoiceChangerPanel.kt"
    private val editorScreen =
        "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/EditorScreen.kt"
    private val mixerPlayer =
        "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/AudioMixerPlayer.kt"
    private val toolbarSpec =
        "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/ContextualToolbarSpec.kt"
    private val editorViewModel =
        "app/src/main/java/com/aicreatorstudio/app/viewmodel/EditorViewModel.kt"
    private val exportEngine =
        "app/src/main/java/com/aicreatorstudio/app/export/VideoExportEngine.kt"
    private val renderSegment =
        "app/src/main/java/com/aicreatorstudio/app/export/RenderSegment.kt"
    private val roomRepo =
        "app/src/main/java/com/aicreatorstudio/app/db/RoomProjectRepository.kt"
    private val stateJson = "app/src/main/java/com/aicreatorstudio/app/db/EditorStateJson.kt"
    private val subPanelHeight =
        "app/src/main/java/com/aicreatorstudio/app/model/SubPanelHeight.kt"

    // ── The DSP's per-frame state advance ───────────────────────

    @Test
    fun `the delay pointer advances once per frame, not once per buffer`() {
        // THE bug this DSP was written with, and the one that is invisible
        // in review: advancing the delay pointer once per *buffer* makes the
        // echo's length depend on how much audio Media3 happens to hand
        // over, so the same preset would sound different at a different
        // buffer size. The indentation is the assertion — 16 spaces is
        // inside `if (echoEnabled)` inside `for (frame ...)`, while a
        // per-buffer advance would sit at 8.
        val source = codeLinesOnly(read(dsp))
        assertTrue(
            "the per-frame loop must exist",
            source.contains("\n        for (frame in 0 until frames) {")
        )
        assertTrue(
            "the ring modulator's LFO must advance per frame",
            source.contains("\n            val lfo = if (ringModEnabled) nextLfo() else 0f")
        )
        assertEquals(
            "the delay pointer must be advanced exactly once, inside the frame loop",
            1,
            Regex("writeIndex = \\(writeIndex \\+ 1\\)").findAll(source).count()
        )
        assertTrue(
            "the delay pointer must be advanced inside the frame loop",
            source.contains("\n                writeIndex = (writeIndex + 1) % delayFrames")
        )
    }

    @Test
    fun `the band edges are clamped below nyquist before they reach a biquad`() {
        // A cut-off at or above Nyquist makes `sin(w0)` collapse and the
        // normalised coefficients become NaN, which silences the track.
        val source = codeLinesOnly(read(dsp))
        assertTrue(
            "the biquad must clamp its cut-off away from Nyquist",
            source.contains("val ceiling = rate * 0.45f")
        )
        assertTrue(
            "the cut-off must be clamped, not trusted",
            source.contains("cutoffHz.coerceIn(1f, ceiling.coerceAtLeast(1f))")
        )
    }

    // ── The processor ───────────────────────────────────────────

    @Test
    fun `the processor reuses one DSP and rebuilds it only on a profile change`() {
        // Rebuilding per buffer would restart the filters and the delay
        // line 20+ times a second; never rebuilding would make a preset
        // switch inaudible.
        val source = codeLinesOnly(read(processor))
        assertTrue(
            "the DSP must be cached",
            source.contains("private var dsp: VoiceEffectDsp? = null")
        )
        assertTrue(
            "the built profile must be remembered so a change can be detected",
            source.contains("if (current != null && builtProfile == profile) return current")
        )
        assertTrue(
            "a flush must reset the DSP so a tail cannot cross a cut",
            source.contains("override fun onFlush()") && source.contains("dsp?.reset()")
        )
    }

    @Test
    fun `the processor never reports itself inactive`() {
        // Media3 asks `isActive()` once, right after `configure()`, and
        // DROPS a processor that answers no. A processor that declined
        // while the effect was NONE would therefore be gone for the rest of
        // the session, and turning an effect on afterwards would silently
        // do nothing — the feature would only work if the user picked an
        // effect before pressing play.
        val source = codeLinesOnly(read(processor))
        assertTrue(
            "VoiceEffectAudioProcessor must not override isActive()",
            !source.contains("override fun isActive") &&
                !source.contains("override val isActive")
        )
        assertTrue(
            "the class must extend BaseAudioProcessor, whose isActive() is true once configured",
            source.contains(": BaseAudioProcessor()")
        )
    }

    @Test
    fun `the processor outputs sixteen bit pcm and consumes its whole input`() {
        // 2026-10-14 — this used to assert ENCODING_PCM_FLOAT, which was
        // the bug: SonicAudioProcessor.configure throws
        // UnhandledAudioFormatException on any input that is not
        // ENCODING_PCM_16BIT, so a float output here killed every play
        // attempt at DefaultAudioSink.configure. The output lane is now
        // pinned to what the following stage accepts; see
        // VoiceEffectAudioProcessorTest for the behavioural half.
        val source = codeLinesOnly(read(processor))
        assertTrue(
            "the output format must be 16-bit PCM — SonicAudioProcessor accepts nothing else",
            source.contains("C.ENCODING_PCM_16BIT")
        )
        assertTrue(
            "the declared input must be recognised, not assumed to be float",
            source.contains("inputIsFloat = inputAudioFormat.encoding ==")
        )
        assertTrue(
            "the input buffer must be fully consumed or Media3 will re-queue it",
            source.contains("inputBuffer.position(inputBuffer.limit())")
        )
    }

    @Test
    fun `building a processor for NONE yields nothing at all`() {
        // This is what keeps an untouched project's exported audio
        // byte-identical to the file this feature shipped against.
        val source = codeLinesOnly(read(processor))
        val start = indexOfOrFail(source, "fun build(effect:", processor)
        val body = source.substring(start, (start + 600).coerceAtMost(source.length))
        assertTrue(
            "build() must return null for the identity effect",
            body.contains("if (effect.isIdentity) return null")
        )
        assertTrue(
            "build() must also refuse a profile that resolves to identity",
            body.contains("if (profile.isIdentity) return null")
        )
    }

    // ── Preview / export parity: the chain order ────────────────

    @Test
    fun `the preview runs the voice stages before the speed stage`() {
        // Media3 builds `DefaultAudioProcessorChain` as
        // `[custom..., silenceSkip, sonic]`, so a processor handed to
        // `setAudioProcessors` runs BEFORE the rate change. The export has
        // to match that, or an echo tail is a different length in the
        // exported file than in the preview.
        val preview = codeLinesOnly(read(previewAudio))
        val voice = indexOfOrFail(preview, "VoiceEffectAudioProcessor { profile }", previewAudio)
        val pitch = indexOfOrFail(preview, "SonicAudioProcessor().apply", previewAudio)
        val chain = indexOfOrFail(preview, "arrayOf(voiceProcessor, pitchProcessor)", previewAudio)
        assertTrue("the voice stage must precede the pitch stage", voice < pitch)
        assertTrue("the chain must be the two processors in that order", chain > pitch)
        assertTrue(
            "the processors must be handed to the audio sink",
            preview.contains(".setAudioProcessors(bridge.processors)")
        )
    }

    @Test
    fun `the preview's renderers factory mirrors the platform default`() {
        // The only seam for attaching an AudioProcessor to an ExoPlayer is
        // the protected `buildAudioSink`. It has to keep the two flags the
        // platform default passes through, or the rest of the preview's
        // audio behaviour (including its speed handling) changes.
        val preview = codeLinesOnly(read(previewAudio))
        assertTrue(
            "buildAudioSink must be overridden",
            preview.contains("override fun buildAudioSink(")
        )
        assertTrue(
            "the float-output flag must be passed through unchanged",
            preview.contains(".setEnableFloatOutput(enableFloatOutput)")
        )
        assertTrue(
            "the playback-params flag must be passed through unchanged",
            preview.contains(
                ".setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)"
            )
        )
        assertTrue(
            "the factory must extend DefaultRenderersFactory",
            preview.contains(") : DefaultRenderersFactory(context)")
        )
    }

    @Test
    fun `the preview retargets pitch through the processor, not through PlaybackParameters`() {
        // Routing pitch through `PlaybackParameters.pitch` would put the
        // pitch stage AFTER the rate change in the preview while the export
        // applies it BEFORE — and the two would diverge on exactly the
        // clips where it is hardest to notice.
        val preview = codeLinesOnly(read(previewAudio))
        assertTrue(
            "the bridge must retarget the Sonic processor",
            preview.contains("pitchProcessor.setPitch(VoiceEffects.clampPitch(effect.pitchFactor))")
        )
        assertTrue(
            "the bridge must never touch PlaybackParameters",
            !preview.contains("PlaybackParameters")
        )
    }

    @Test
    fun `the bridge's profile is volatile because the audio thread reads it`() {
        val preview = codeLinesOnly(read(previewAudio))
        assertTrue(
            "the profile must be @Volatile",
            preview.contains("@Volatile") && preview.contains("private var profile:")
        )
    }

    @Test
    fun `the export inserts the voice stages after volume and before speed`() {
        // Order in the Media3 chain IS the parity contract. Before the
        // speed stage means the voice transformation always sees
        // source-rate audio, exactly as it does in the preview.
        val engine = codeLinesOnly(read(exportEngine))
        val start = indexOfOrFail(engine, "fun buildAudioProcessors(", exportEngine)
        val body = engine.substring(start, (start + 3_000).coerceAtMost(engine.length))

        val volume = indexOfOrFail(body, "VolumeScalingAudioProcessor(", "buildAudioProcessors")
        val voice = indexOfOrFail(body, "VoiceEffectAudioProcessor.build(", "buildAudioProcessors")
        val speed = indexOfOrFail(body, "SpeedChangingAudioProcessor(", "buildAudioProcessors")
        val fade = indexOfOrFail(body, "FadeAudioProcessor(", "buildAudioProcessors")

        assertTrue("the voice stage must run after the volume stage", voice > volume)
        assertTrue("the voice stage must run before the speed stage", voice < speed)
        assertTrue("the speed stage must still run before the fade", speed < fade)
    }

    @Test
    fun `the export adds a pitch stage only for the two pitch presets`() {
        val engine = codeLinesOnly(read(exportEngine))
        val start = indexOfOrFail(engine, "fun buildAudioProcessors(", exportEngine)
        val body = engine.substring(start, (start + 3_000).coerceAtMost(engine.length))
        assertTrue(
            "the pitch stage must be gated on the shared predicate",
            body.contains("if (VoiceEffects.needsPitchStage(segment.voiceEffect))")
        )
        assertTrue(
            "the pitch must come from the clamped shared value",
            body.contains("setPitch(VoiceEffects.clampPitch(segment.voiceEffect.pitchFactor))")
        )
    }

    @Test
    fun `the export does nothing at all for the identity effect`() {
        val engine = codeLinesOnly(read(exportEngine))
        val start = indexOfOrFail(engine, "fun buildAudioProcessors(", exportEngine)
        val body = engine.substring(start, (start + 3_000).coerceAtMost(engine.length))
        assertTrue(
            "the whole voice block must be gated on the identity check",
            body.contains("if (!segment.voiceEffect.isIdentity) {")
        )
    }

    @Test
    fun `the speed curve's own pitch preservation is left untouched`() {
        // The brief's hard constraint: adding a voice effect must not
        // regress the existing SpeedCurve behaviour. The voice stages are
        // ADDITIVE — the curve path still goes through the same
        // SpeedChangingAudioProcessor it always did.
        val engine = codeLinesOnly(read(exportEngine))
        assertTrue(
            "the curve path must still reuse the shared speed processor",
            engine.contains("processors += SpeedChangingAudioProcessor(curveProvider)")
        )
        assertTrue(
            "the constant-speed path must still exist",
            engine.contains(
                "processors += SpeedChangingAudioProcessor(ConstantSpeedProvider(segment.speed))"
            )
        )
    }

    // ── The segment carries the effect ──────────────────────────

    @Test
    fun `the render segment carries the effect and the builder fills it`() {
        val segment = codeLinesOnly(read(renderSegment))
        assertTrue(
            "RenderSegment must declare a voiceEffect field",
            segment.contains("val voiceEffect:")
        )
        val builder = codeLinesOnly(
            read("app/src/main/java/com/aicreatorstudio/app/export/RenderGraphBuilder.kt")
        )
        assertTrue(
            "the builder must copy the clip's effect onto the segment",
            builder.contains("voiceEffect = voiceEffect")
        )
    }

    // ── Persistence plumbing ────────────────────────────────────

    @Test
    fun `the room repository reads the effect back off the decoded blob`() {
        // THE bug class this project has shipped more than once: the codec
        // encodes and decodes the field, but the mapper that turns the
        // entity into a domain object never reads it — so the setting is
        // written, reloaded, and silently dropped. `motionTrack` was lost
        // exactly this way.
        val repo = codeLinesOnly(read(roomRepo))
        assertTrue(
            "toVideoProject must read the decoded voice effect",
            repo.contains("voiceEffect = aiTools?.voiceEffect ?: VoiceEffect.NONE")
        )
    }

    @Test
    fun `the view model writes the clip's effect into the ai tools blob`() {
        val vm = codeLinesOnly(read(editorViewModel))
        assertTrue(
            "toClipAiTools must carry the clip's voice effect",
            vm.contains("voiceEffect = clip.voiceEffect")
        )
    }

    @Test
    fun `the blob writes the effect only when it is not the default`() {
        val json = codeLinesOnly(read(stateJson))
        assertTrue(
            "a non-default effect must be written",
            json.contains("put(\"voiceEffect\", clip.voiceEffect.name)")
        )
        assertTrue(
            "the default must be omitted so old projects stay byte-identical",
            json.contains("if (!clip.voiceEffect.isIdentity) {")
        )
        assertTrue(
            "the decoder must route through the tolerant name lookup",
            json.contains("voiceEffect = voiceEffectFromName(")
        )
    }

    // ── ViewModel ───────────────────────────────────────────────

    @Test
    fun `the view model action pushes history and persists, once per real change`() {
        val vm = codeLinesOnly(read(editorViewModel))
        val start = indexOfOrFail(vm, "fun setClipVoiceEffect(", editorViewModel)
        val body = vm.substring(start, (start + 1_600).coerceAtMost(vm.length))

        assertTrue(
            "the action must push an undo snapshot",
            body.contains("historyDelegate.push()")
        )
        assertTrue(
            "the action must persist the clips",
            body.contains("persistClips()")
        )
        assertTrue(
            "the action must write the effect onto the matching clip only",
            body.contains("if (clip.id == clipId) clip.copy(voiceEffect = effect) else clip")
        )
        // Re-tapping the card that is already selected must not create an
        // undo entry — the stack is 20 deep and does not dedupe.
        val guard = body.indexOf("if (current == effect) return")
        val push = body.indexOf("historyDelegate.push()")
        assertTrue("an equality guard must exist", guard >= 0)
        assertTrue("the equality guard must run before the push", guard < push)
    }

    // ── Preview wiring on the screen ────────────────────────────

    @Test
    fun `the main preview player is built with the voice renderers factory`() {
        val screen = codeLinesOnly(read(editorScreen))
        assertTrue(
            "the bridge must be remembered for the player's lifetime",
            screen.contains("remember { VoiceEffectPreviewBridge() }")
        )
        assertTrue(
            "the player must be built with the voice renderers factory",
            screen.contains("VoiceEffectRenderersFactory(")
        )
        assertTrue(
            "a preset tap must reach the live bridge",
            screen.contains("voicePreviewBridge.set(")
        )
    }

    @Test
    fun `the audio mixer gives every stem its own bridge and retargets it`() {
        // One bridge per stem, not one shared: two songs on the timeline
        // can carry different effects, and a shared bridge would let the
        // second one overwrite the first on every sync.
        val mixer = codeLinesOnly(read(mixerPlayer))
        assertTrue(
            "each stem must hold its own bridge",
            mixer.contains("val voice: VoiceEffectPreviewBridge")
        )
        assertTrue(
            "each stem's player must be built with its own factory",
            mixer.contains("VoiceEffectRenderersFactory(context, voice)")
        )
        assertTrue(
            "a re-sync must retarget the stem's effect",
            mixer.contains("old.voice.set(clip.voiceEffect)")
        )
        assertTrue(
            "a new stem must seed its bridge from the clip",
            mixer.contains("voice.set(clip.voiceEffect)")
        )
    }

    @Test
    fun `the audio clip's effect is passed to the stem and is part of the sync key`() {
        // The stem is the OTHER half of the preview's audio path. Omitting
        // the argument leaves the field at its default and the stem keeps
        // playing the old effect — the exact "declared but never forwarded"
        // failure the fade fields already fell into. Omitting it from the
        // LaunchedEffect KEY means the change never even triggers a sync.
        val screen = codeLinesOnly(read(editorScreen))
        assertTrue(
            "the stem must receive the clip's voice effect",
            screen.contains("voiceEffect = clip.voiceEffect")
        )
        assertTrue(
            "the sync key must include the voice effect",
            screen.contains("it.fadeOutMs, it.voiceEffect)")
        )
    }

    // ── UI ──────────────────────────────────────────────────────

    @Test
    fun `the editor screen dispatches the panel and titles the sheet`() {
        val screen = codeLinesOnly(read(editorScreen))
        assertTrue(
            "the sub-panel dispatcher must handle VOICE_CHANGER",
            screen.contains("ToolPanel.VOICE_CHANGER -> {") &&
                screen.contains("VoiceChangerPanel(")
        )
        assertTrue(
            "the sheet title must be set, or the header reads blank",
            screen.contains("ToolPanel.VOICE_CHANGER -> \"تغییر صدا\"")
        )
        assertTrue(
            "the panel's write path must reach the view model",
            screen.contains("viewModel.setClipVoiceEffect(clipId, effect)")
        )
    }

    @Test
    fun `the panel offers every preset in the shared order`() {
        // The panel must render the shared list, not its own hardcoded
        // copy — otherwise a new effect would appear in the DSP and never
        // in the UI.
        val source = codeLinesOnly(read(panel))
        assertTrue("the panel must be a public composable", source.contains("fun VoiceChangerPanel("))
        assertTrue(
            "the panel must iterate the shared preset order",
            source.contains("val presets = VoiceEffects.panelOrder")
        )
        assertTrue(
            "the panel must offer the reset affordance",
            source.contains("\"بازنشانی به حالت عادی\"")
        )
        assertTrue(
            "reset must go through the same selection path",
            source.contains("onSelect(VoiceEffect.NONE)")
        )
    }

    @Test
    fun `the panel is controlled and cannot hold a stale copy of the effect`() {
        // The panel renders the value it is given; a local `remember` copy
        // would let the highlighted card drift from the effect the preview
        // and the export are actually using.
        val source = codeLinesOnly(read(panel))
        assertTrue(
            "the panel must take the current effect as a parameter",
            source.contains("selected: VoiceEffect,")
        )
        assertTrue(
            "the panel must not cache the selection",
            !source.contains("remember { mutableStateOf(selected") &&
                !source.contains("remember(selected)")
        )
    }

    @Test
    fun `the toolbar exposes the voice changer from both audio contexts`() {
        val spec = codeLinesOnly(read(toolbarSpec))
        val clipRow = spec.substring(
            indexOfOrFail(spec, "val ClipChips", toolbarSpec),
            indexOfOrFail(spec, "val AudioChips", toolbarSpec)
        )
        val audioRow = spec.substring(
            indexOfOrFail(spec, "val AudioChips", toolbarSpec),
            indexOfOrFail(spec, "val TextChips", toolbarSpec)
        )
        assertTrue(
            "the clip toolbar must expose the voice changer",
            clipRow.contains("VoiceChangerChip")
        )
        assertTrue(
            "the audio toolbar must expose the voice changer",
            audioRow.contains("VoiceChangerChip")
        )
        assertTrue(
            "both rows must share one chip definition",
            spec.contains("val VoiceChangerChip: ContextChip.Panel = ContextChip.Panel(")
        )
        assertTrue(
            "the chip must route to the voice changer panel",
            spec.contains("toolPanel = ToolPanel.VOICE_CHANGER")
        )
    }

    @Test
    fun `the panel is classified for sizing`() {
        // `kindFor` is an exhaustive `when`, so a new ToolPanel cannot be
        // forgotten — but the *classification* still has to be deliberate.
        val source = codeLinesOnly(read(subPanelHeight))
        assertTrue(
            "VOICE_CHANGER must be classified as a gallery",
            source.contains("ToolPanel.VOICE_CHANGER -> Kind.GALLERY")
        )
    }
}
