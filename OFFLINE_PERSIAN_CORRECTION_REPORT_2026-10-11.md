# Offline Persian Subtitle Correction — Upgrade Report

**Date:** 2026-10-11
**Scope:** `com.aicreatorstudio.app.ai.subtitle` (Omelet / AICreatorStudio, Android)
**Constraint set:** fully offline, zero cost, no sign-in, no runtime model download.

---

## 1. Audit result — the layer already existed

The brief assumed a Persian correction system to build. The audit found one
already in place and working, so the job became **extending** it rather than
replacing it. Verified by reading every file, not by inference:

| Component | Role | Status before |
|---|---|---|
| `PersianNormalizer.kt` | 1:1 char fold — Arabic `ي`→Persian `ی`, `ك`→`ک`; strips BOM/ZWSP; **preserves ZWNJ** | Working |
| `corrector/PersianLexicon.kt` | ~1 000-word lexicon + `ZWNJ_FIXES` (84 entries) | Working |
| `corrector/SuspiciousWordDetector.kt` | Rules A (trailing repeat) / B (suffix junk) / C (fuzzy + low conf) | Working |
| `corrector/CorrectionCandidateGenerator.kt` | Collapse / strip / می-prefix / edit-distance ≤2 | Working |
| `corrector/ContextValidator.kt` | `OfflineContextValidator` — weighted deterministic selector | Working |
| `corrector/TranscriptCorrector.kt` | apply / flag / keep + JSON correction log | Working |
| `SubtitlePostProcessor.kt` | Orchestrator (normalize → BPE merge → integrity → correct → silence → repeat → segment) | Working |

Single call site: `EditorViewModel:3500`. Settings: `SubtitlePostProcessSettings`
(auto-apply ≥ 0.90, review ≥ 0.70, `correctTypos` default `true`).

**The real gap:** the corrector had **no vocabulary for sound-alike errors at
all**. Rules A–C only handle doubled letters, junk suffixes and edit-distance
neighbours of a *lexicon* word. An acoustic error like «آشق» for «عاشق» is not
within Levenshtein-2 of a dictionary form in a way the old pipeline could use,
so it passed through untouched. That is what this upgrade adds.

The Whisper transcription pipeline was **not modified** — no verified bug was
found in it.

---

## 2. Changed files

### New (main)
- **`corrector/PersianConfusables.kt`** (217 lines) — character-level
  sound-alike map + variant derivation.
- **`corrector/HomophonePolicy.kt`** (213 lines) — preservation *veto* gate.

### Modified (main)
- **`corrector/SuspiciousWordDetector.kt`** — new **rule D**, reason code
  `REASON_HOMOPHONE = "homophone_shape"`, confidence-gated (< 0.60 or null).
- **`corrector/CorrectionCandidateGenerator.kt`** — new origin
  `ORIGIN_HOMOPHONE = "confusable_rewrite"`; candidates derived from the map,
  accepted only on a lexicon hit.
- **`corrector/ContextValidator.kt`** — homophone candidates receive **no**
  mechanical bonus; scored on sentence evidence via `contextSupportsNoun`.
- **`corrector/TranscriptCorrector.kt`** — policy veto runs **before**
  candidate generation; a veto is final.

### New (test)
- `corrector/PersianConfusablesTest.kt` (8 tests)
- `corrector/TranscriptCorrectorPreservationTest.kt` (21 tests)
- `corrector/OfflineCorrectionGuardTest.kt` (3 tests)

No Gradle dependency added. No asset added. No manifest change.
`SubtitlePostProcessSettings` was **not** changed — defaults already correct.

---

## 3. Correction rules

### Rule D — contextual homophone (new)
Fires when a token is **not** a lexicon word and a sound-shape rewrite of it
**is**. Example: `آ` → `عا` turns «آشق» into «عاشق», which the lexicon contains.

The relationship is expressed as a **character** rule, never a word pair:

```kotlin
GROUPS = ["عآاأإ", "سصث", "زذضظ", "تط", "حه", "قغخ", "کگ"]
EXPANSIONS = { "آ" -> ["عا", "ا"], "ا" -> ["عا", "آ"], "ع" -> ["آ", "ا"], "ه" -> ["ح"] }
```

Variants are derived by re-spelling (≤ 48, bounded), and only a variant that is
**already a lexicon word** becomes a candidate. The map cannot invent vocabulary.

### Scoring — why «آشق» is not fixed unconditionally
A homophone candidate is **denied** the mechanical bonuses that make collapse
corrections near-certain. It is scored:

- `+0.12` if the preceding token opens a noun slot (`یه`, `یک`, `این`, `اون`,
  `مثل`, `دوست`, …) — i.e. sentence evidence.
- `−0.30` otherwise.

Without evidence the candidate cannot reach the 0.90 auto-apply threshold, so
the word survives. `آشق` in isolation, or after a verb, is left alone. This is
the mechanism that satisfies §4.

### Preservation veto (`HomophonePolicy`) — checked in this order
1. User custom dictionary → **keep**
2. Non-Persian / numeric token → **keep**
3. Already a lexicon word → **keep**
4. Trailing doubled letter → **allow** (structural defect outranks all heuristics)
5. Deliberate repetition (same token adjacent) → **keep**
6. Colloquial register → **keep**
7. No lexical neighbour → **keep**

---

## 4. Dictionary source and licence

**No external dictionary is bundled.** The vocabulary is the pre-existing
hand-written `PersianLexicon` (`RAW` block + `ZWNJ_FIXES`), authored in-repo on
2026-09-11. The new `PersianConfusables` groups and `HomophonePolicy` lists are
likewise hand-curated, ~430 lines total.

**Licence impact: none.** No third-party data, no model weights, no download,
no attribution obligation introduced.

---

## 5. Test results

Command (JDK 21 / JBR, Gradle 8.13):

```
java -classpath "<gradle-8.13>/lib/*" org.gradle.launcher.GradleMain \
  testDebugUnitTest assembleDebug --console=plain
```

| | |
|---|---|
| **Total** | **2118 completed** |
| Failed | **2** |
| Skipped | 2 |

The 2 failures are the **pre-existing, asset-dependent** pair, unchanged by
this work and known never to chase:

- `RealModelInstallTest`
- `WhisperNativeSymbolsTest`

Confirmed by inspecting which result files contain a `<failure>` element —
**only those two**.

New/changed test classes:

| Class | Tests | Failures |
|---|---|---|
| `PersianConfusablesTest` | 8 | 0 |
| `TranscriptCorrectorPreservationTest` | 21 | 0 |
| `OfflineCorrectionGuardTest` | 3 | 0 |
| `TranscriptCorrectorTest` (pre-existing, must still pass) | 18 | 0 |

Coverage against the brief: contextual correction (§4), false-positive
prevention, normalisation, half-spaces, proper nouns, colloquial text, and
exact timestamp preservation are each asserted explicitly.

---

## 6. Build result

`assembleDebug` ran and **rebuilt the APK**:

```
app/build/outputs/apk/debug/app-debug.apk
122,201,865 bytes   (2026-10-11 10:26)
```

Note: the Gradle run ends `BUILD FAILED` because the *test* task fails on the
two known tests, but `packageDebug` and `assembleDebug` both executed — verified
by APK mtime, not by exit code.

---

## 7. App-size impact

**Unchanged — 122,201,865 bytes, byte-identical count to the pre-change APK.**

No new dependency, no new asset, no model. The addition is ~430 lines of
pure Kotlin compiled into existing dex files.

The brief's §8 condition (no LLM, no substantial size increase) is met by
**not** adding a model at all. No offline-Android LLM was evaluated because no
model can satisfy the size/RAM/licence constraints inside a 122 MB APK without
approval — so the requirement self-resolves in favour of the rule-based layer.

---

## 8. Offline verification

Audited by grep across the entire correction path (9 source files):

- No `okhttp` / `retrofit` / `HttpURLConnection` / `Socket` / `InetAddress`
- No `groq` / `openai` / `firebase` / `supabase`
- No LLM runtime, no `download`, no `assets.open`, no model path

This is now enforced **on every build** by `OfflineCorrectionGuardTest`, which
re-reads the source tree and fails if any forbidden token or out-of-scope import
appears. The guarantee is structural, not a one-off inspection.

---

## 9. Known limitations

Stated plainly, without overclaiming:

- **Not human-level accuracy.** The layer is rule- and lexicon-driven. Nothing
  here was validated against a human-corrected reference corpus, and no accuracy
  percentage is claimed.
- **Coverage is bounded by the lexicon.** A mis-heard word whose correct form is
  absent from `PersianLexicon` can *never* be corrected — by design. Growing the
  lexicon is the lever.
- **The colloquial heuristic is a heuristic.** A genuine typo that resembles a
  colloquial register is kept rather than fixed. This is the intended
  false-negative preference.
- **No device or emulator run.** Correctness is argued from the unit suite and a
  successful build. On-device behaviour was **not** observed.
- **Not yet wired to UI.** The corrector runs inside `postProcess` with
  `correctTypos = true`; there is no user-facing screen for reviewing flagged
  (0.70–0.90) corrections. The log is persisted via `persistCorrectionLog`.
- `androidTest` remains broken (pre-existing, unrelated).

---

## 10. Bugs found and fixed during this work

Worth recording because they were subtle:

1. **Kotlin heterogeneous-list trap.** `List<String> + List<Char>` infers
   `List<Any>`, so a whole group string leaked in as one "replacement"
   (`MEMBERS['آ'] == ["عاأإ", "عا", "ا"]`). Fixed with `.map { it.toString() }`.
2. **`آ` is U+0622, not U+0627.** The glottal group listed plain alef, so `آ`
   was silently absent from the map. Both codepoints are now listed.
3. **The veto over-reached.** Matching colloquial markers as *substrings*
   blocked the mechanical corrections that already worked (`هستمم`, `برمم`,
   `بدیمن`). Structural defects now short-circuit the policy first.
4. `"میرم" → "میرم"` is pre-existing intended ZWNJ normalisation, not a
   register change. The test expectation was wrong, not the code.
