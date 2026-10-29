# Build & test

## The canonical command

**`./gradlew` is broken under Git Bash.** The script builds a `:`-separated classpath and the
Windows JVM needs `;`, so it fails with `Could not find or load main class
org.gradle.wrapper.GradleWrapperMain`. The wrapper jar itself is fine — **do not re-download it.**
`gradlew.bat` cannot be piped through the PowerShell tool either (`cmd.exe` is blocked there).

Run Gradle directly on the **pinned JDK 21**. Gradle 8.13 rejects the host JDK 25 that `java` on
PATH resolves to.

```bash
cd "C:\Users\eric steph\Desktop\projects\edit\New folder (2)\AICreatorStudio" && \
"C:/Users/eric steph/.jdks/jbr-21.0.11/bin/java.exe" \
  -classpath "C:/Users/eric steph/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/lib/*" \
  org.gradle.launcher.GradleMain testDebugUnitTest --console=plain
```

Swap `testDebugUnitTest` for `assembleDebug`, `:app:compileDebugKotlin`, or
`testDebugUnitTest --tests "<FQCN>"` to run a single class. `build_check.bat` wraps
`gradlew.bat :app:compileDebugKotlin --no-daemon` but is subject to the same wrapper problem.

## Expected baseline — do not chase these failures

```
1189 tests completed, 5 failed, 2 skipped
```

The 5 failures are **pre-existing and asset-dependent**. They are not caused by your change:

| Test | Why it fails |
|---|---|
| `WhisperNativeSymbolsTest` | Asserts all three ABIs are built; the NDK config produces `arm64-v8a` only. |
| `WhisperModelSelectionAuditTest` (×2) | Stale expectations from removed `MEDIUM`/`LARGE_V3` specs. |
| `WhisperDownloadUrlTest` | References `AiModelCatalog.WHISPER_MEDIUM`, which no longer exists. |
| `RealModelInstallTest` | Looks for a bundled `whisper-medium` model and lockfile entry that are absent. |

If the failure count rises above 5, that is your regression. If a test that used to fail now passes,
that is fine — do not "fix" it back.

## Reading results

JUnit XML lands in `app/build/test-results/testDebugUnitTest/*.xml`. Sum the `tests`, `failures` and
`errors` attributes across the files to get the totals.

Timing: ~55 s cold, ~15 s warm. Run it in the background rather than blocking.

## The test stack — and its hard limits

Available: **JUnit 4**, **kotlinx-coroutines-test**, **org.json**, plus `androidx.test.ext.junit`,
espresso and `compose ui-test-junit4` for instrumentation.

**Not available: Mockito, MockK, Robolectric.** Consequences you must design around:

- `EditorViewModel` has 13 constructor dependencies including `@ApplicationContext Context`, so it
  **cannot be instantiated in a JVM test**. There is no way to mock them.
- Therefore new testable logic belongs in a **pure top-level function**, conventionally in `model/`.
  Precedents to copy: `model/EditorStateHistory.kt`, `model/PlaybackSpeedRange.kt`,
  `model/GrainSignal.kt`, `model/GradeRgbMatrix.kt#toMedia3RgbMatrix4x4`.
- `returnDefaultValues = true` is set, so an effect *builder* can be tested for the **shape** of its
  output — always pair that with a negative case, because a shape test alone will pass on wrong
  maths.
- **Anything inside a `@Composable`, and anything requiring GL, is untestable here.** That covers the
  entire preview effect pipeline. Report such work as verified-by-inspection or `PARTIAL`; never
  claim it is tested.

## Test conventions

- JUnit 4, `org.junit.Assert.*`, backtick-quoted test names.
- KDoc on the test naming the regression it guards.
- If a test only *guards* behaviour rather than *reproducing* a bug, say so in the KDoc.
- Unit tests: `app/src/test/java/com/aicreatorstudio/app/**` (~120 files).
- Instrumentation: `app/src/androidTest/**` (14 files) — e.g. `WhisperRealInferenceTest`,
  `ExportColorParityInstrumentationTest`, `SrtImportExportInstrumentationTest`,
  `SubtitleTimelineAndRtlEndToEndTest`.

## A trap the unit-test loop cannot catch

`mergeDebugAssets` runs **only on `assemble`**, not on a unit-test-only loop. So a missing shader,
model or JSON asset will pass every unit test and fail only on a real build or at runtime. If your
change touches `assets/` or `src/main/cpp/`, run `assembleDebug` too.
