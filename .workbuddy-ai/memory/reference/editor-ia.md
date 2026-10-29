# Editor dimensions, IA and persistence — the narrow doc

Read before touching canvas sizing, the contextual toolbar, sub-panel heights, the audio lane, or
per-clip persistence. Split out of `MEMORY.md` on 2026-10-29 to keep that file under its size limit.

## Canvas / display dimensions (2026-09-30)

One owner per step:

`model/VideoDimensions.resolve(...)` (coded-frame → display swap; the probe is ground truth for
orientation **only**) → `CanvasPreset.forSourceDimensions` (import) →
`ExportFrame.resolveOutputSize(...)` (encoder size; never upscaled, always even).

- Every canvas default is `FREE`.
- Every `ExportQuality` is landscape — **never** use `quality.widthPx` as an aspect source.
- Preview has exactly **one** `.aspectRatio(...)`; `EditorVideoCanvas.kt` is dead code.

## Editor toolbar / IA (shipped 2026-09-28)

`ContextualToolbar` ← `EditorBottomDockSection`, driven by `ContextualToolbarSpec` +
`model/EditorContext.kt`. Chrome is forced **LTR** (Persian labels, LTR chrome).

`ContextualToolbarSpecTest` pins the root row to **exactly 8** flat chips, ≤ 8 Persian characters per
label, and no panel twice per context. Audio chips = **6**; clip row = **17**; with transitions = **18**.

«هوشمند» is the last remaining sheet, holding 11 panels: autoframe, matting, motion_track, ocr,
voice_clean, voice_enhance, silence_cut, reframe, beat_sync, highlights, upscale.

## Sub-panel sizing (2026-10-01)

`model/SubPanelHeight.kt` (`resolveHeightDp`, `kindFor`, `Kind`) — pure floats, JVM-testable.

`availableDp` is the **post-chrome** height from `BoxWithConstraints` inside `SubPanelRow`.
`(available × fraction).coerceIn(min, max).coerceAtMost(available − 180)` — the last clamp **wins**.
COMPACT is an allow-list of exactly 11 kinds (`SubPanelHeightTest`); everything else → GALLERY.
`SubPanelContent` needs `uiState.copy(toolPanel = panel)` because it dispatches on `uiState.toolPanel`.

## Paired-audio invariant

Every mutator of `project.clips` must also touch the `AudioClip` whose `originVideoClipId` is that
clip, or the "Video Audio" lane drifts one step behind. Three bugs in one day came from this
(split / delete / duplicate).

Covered: `splitClip`, `splitAudioClip`, `deleteClip`, `duplicateClip`, `updateClipPosition`.
**Not covered: `setClipTrim`, `setClipSpeed`** — those need a source-time vs timeline-time decision
first. Persist **both** tables (`persistClips()` + `persistProjectMeta()`).

Guarded by `AudioLaneLifecycleWiringTest`.

Still open: `audioClips` never reaches `RenderGraphBuilder`.

## Per-clip persistence — `ClipAiTools` is the carrier

Per-clip settings ride the **`aiToolsJson`** blob, so no Room migration is needed. A new field touches,
in order:

1. the clip model;
2. `db/EditorStateJson.kt` — the field **and** `aiToolsToJson`/`FromJson` **and** the audio variants;
3. `db/RoomProjectRepository.kt`'s `toVideoProject` (⚠️ omitting it is bug class 1 — it is how
   `motionTrack` was lost);
4. `EditorViewModel`'s `toClipAiTools()`;
5. `export/RenderSegment.kt` + `RenderGraphBuilder`.

**Append params last**; write only when non-default.
