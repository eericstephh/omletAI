# Speed Ramping / Curves — implementation notes (2026-09-29)

## What was built

A per-clip variable-speed ramp ("speed curve") on top of the existing
constant-speed control, applied in both the exported file and (as an
average) the live preview.

| Layer | File | Role |
|---|---|---|
| Model | `model/SpeedCurve.kt` | `SpeedPoint`, `SpeedCurvePreset` (HERO/BULLET/MONTAGE/FLASH_IN/FLASH_OUT), `SpeedCurve`, sanitising, linear `speedAt` |
| Math | `model/util/SpeedCurveInterpolator.kt` | forward/inverse time map, exact output duration, split helper, staircase grid |
| Persistence | `db/EditorStateJson.kt` | `ClipAiTools.speedCurve` on the existing AI-tools blob |
| Export adapter | `export/SpeedCurveSpeedProvider.kt` | `SpeedCurveInterpolator` → Media3 `SpeedProvider` |
| Export wiring | `export/VideoExportEngine.kt` | `SpeedChangeEffect` + `SpeedChangingAudioProcessor` take the curve provider |
| Render graph | `export/RenderSegment.kt`, `export/RenderGraphBuilder.kt` | carry the curve; `outputDurationMs` becomes ramp-aware |
| UI | `ui/screens/editor/SpeedCurvePanel.kt` | curve canvas, gestures, presets, duration readout |
| UI wiring | `ui/screens/editor/EditorScreen.kt` | SPEED panel; preview uses the ramp's average |
| ViewModel | `viewmodel/EditorViewModel.kt` | `setClipSpeedCurve` / `clearClipSpeedCurve`, split-path curve slicing |

## The three decisions that matter

### 1. The time axis is the SOURCE clip, not the output

`SpeedPoint.normalizedTime` is a fraction of the source span. This is
forced: Media3's `SpeedProvider` is sampled on the **input** timeline.
`SpeedChangeShaderProgram.queueInputFrame` calls
`getSpeed(presentationTimeUs)` where that PTS is the *decoded frame's
own* timestamp, and `SpeedProviderUtil.getDurationAfterSpeedProviderApplied`
integrates `dt / speed(t)` over the input duration. A curve keyed on
output time would have to be inverted before Media3 could use it, and
that inversion is only approximate.

### 2. The engine reproduces Media3's STAIRCASE, not the smooth curve

`SpeedProvider` is **piecewise constant** by contract:

```java
// SpeedChangeShaderProgram
output += (inputTime - lastSpeedChangeInputTime) / speedProvider.getSpeed(inputTime);
```

`getNextSpeedChangeTimeUs` is walked in a `while (next <= t)` loop. So
`SpeedCurveInterpolator` pre-samples the ramp onto a uniform grid
(`SLICES_PER_INTERVAL = 32` per knot interval) and reports each slice's
rate. `SpeedCurveSpeedProvider` returns **that slice's** rate — never a
freshly sampled curve value — so the shader's accumulated PTS and
`SpeedProviderUtil`'s duration agree exactly. If they disagreed, audio
and video would drift.

The *drawn* curve is smooth (`SpeedCurvePanel` samples the linear
function per pixel); the *baked* curve is the staircase. Drawing the
staircase would look like a bug.

### 3. The constant `speed` MULTIPLIES the ramp, it is not replaced

`buildSpeedProvider` (in `VideoExportEngine.kt`) folds `segment.speed`
into the curve's knots before constructing the provider. So
`clip.speed = 2` still means "the whole ramp, twice as fast". Three
consumers use the same multiplication and must stay in step:
`VideoExportEngine.buildEditedMediaItem` (video),
`VideoExportEngine.buildAudioProcessors` (audio), and
`RenderSegment.outputDurationMs` (the composition clock).

## Why `∫dt/v` is not an average speed

The output duration is `∫₀ᵀ dt/v(t)` — a **harmonic** average. A ramp
that spends equal time at 0.5× and 2× comes out **25% longer** than the
source, not the same length. `SpeedCurveInterpolatorTest` pins this as a
regression guard, and the panel shows the resulting duration next to the
source duration so the user sees the consequence immediately.

## Backward compatibility

The constant-speed path is untouched when the curve is absent **or
inactive**:

- `SpeedCurve.isActive` is false for `null`, for fewer than two knots,
  and for an all-`1f` ramp. An inactive curve is never persisted
  (`aiToolsToJson` skips it) and never activates the export branch.
- `RenderSegment.usesSpeedCurve` is the single predicate the engine
  branches on.
- A ramp with an active curve is *added to* the clip, so clearing it
  restores the previous constant playback rather than destroying it.

## Preview vs export — an explicit, disclosed divergence

A ramp cannot be previewed: `ExoPlayer.setPlaybackParameters` is one
constant rate, and driving it from the position poller would violate the
"one writer per player property" invariant (`volume` already owns that
poller — see MEMORY.md). So the preview plays the ramp's **effective
average** (`source / output`, from the same interpolator), and the panel
says so in a visible line rather than pretending to match.

## Known gaps

1. **Timeline width is source-based.** `EditorTimeline` draws
   `effectiveDurationMs` and has never been speed-aware — a 2× clip
   already draws at its source width today. Making the timeline
   ramp-aware is a separate change (it also moves auto-layout and
   hit-testing) and was deliberately not bundled here.
2. **Audio-only clips** on a ramped video: `buildAudioOnlyMediaItem`
   inverts the time map correctly, but an audio-only clip that itself
   carries a ramp is unusual and only lightly exercised.
3. **`preservePitch` is carried but not branched on.** Media3 always
   stretches through `SonicAudioProcessor`, so there is no non-pitch-
   preserving mode to select today.
4. **Aliasing.** A steep ramp steps frame PTS; Media3's
   `SpeedChangeEffect` documents that it "doesn't drop any frames", so a
   6× section keeps its frames and relies on the encoder/muxer. No extra
   frame dropping or interpolation was added.

## Bugs found and fixed in passing

- **`motionTrack` was dropped on save from 3 of the 4 persistence call
  sites** (`RoomProjectRepository.toVideoClip` and three inline
  `ClipAiTools(...)` literals in `EditorViewModel`). A tracked overlay's
  trajectory survived but its settings did not, so it reverted to a
  plain Auto Frame crop on reopen. All four now go through one
  `List<VideoClip>.toClipAiTools()` helper so a field cannot be listed
  in three places and forgotten in the fourth.
- **Splitting a ramped clip replayed the whole ramp in both halves.**
  `splitClip` used `copy(trimEndMs = …)` / `copy(trimStartMs = …)`,
  which leave `speedCurve` untouched; both halves kept the full curve
  and compressed it into their own span. Now sliced with
  `SpeedCurveInterpolator.split`, which renormalises each window and
  samples the original at the cut so the ramp stays continuous.
