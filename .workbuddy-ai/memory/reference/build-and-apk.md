# Build, test, verify, archive

Everything about producing and validating an APK for `com.aicreatorstudio.app`.

## Gradle

`./gradlew <task> --offline` works from Bash. **Keep `--offline`.**

Fallback when the wrapper's JDK is wrong — pin **JDK 21**, because Gradle 8.13 rejects JDK 25:

```
"C:/Users/eric steph/.jdks/jbr-21.0.11/bin/java.exe" -classpath \
  "C:/Users/eric steph/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/lib/*" \
  org.gradle.launcher.GradleMain <task>
```

`cmd.exe` is blocked from the PowerShell tool.

## Test suite

~3000 tests. test+assemble takes 3–6 min → **run it in the background** and grep the log for `^e:` / `BUILD`.

**Never-chase baseline** (fails for reasons unrelated to your change):

- `RealModelInstallTest` — asset-dependent.
- `WhisperNativeSymbolsTest` — stale.
- `WhisperLargeV3LockfileTest` — stale; asserts a `whisper-large-v3` catalog entry that no longer exists
  (the offline catalog is Small-only) and that tiny/small were removed.

### ⚠️ `compileDebugUnitTestKotlin` is RED since 2026-09-21

`WhisperDownloadUrlTest` + `WhisperLargeV3Download{,Pipeline,Resilience}Test` reference removed constants, so
the **whole test source set** fails to compile. Workaround:

```bash
mkdir -p build/tmp/stale-tests
for f in WhisperDownloadUrlTest WhisperLargeV3DownloadTest \
         WhisperLargeV3DownloadPipelineTest WhisperLargeV3DownloadResilienceTest; do
  mv "app/src/test/java/com/aicreatorstudio/app/ai/$f.kt" build/tmp/stale-tests/
done
./gradlew testDebugUnitTest --offline
mv build/tmp/stale-tests/*.kt app/src/test/java/com/aicreatorstudio/app/ai/   # restore
```

`assembleDebug` is unaffected by this.

### ⚠️ Assemble in a separate invocation

A chained `test && assemble` after a failing test leaves a **stale APK**. Always run `assembleDebug` as its
own invocation, then verify by **mtime + DEX grep**:

```bash
find app/build/tmp/kotlin-classes/debug -name 'Foo.class'   # class mtime must be newer than Foo.kt
stat -c %s app/build/outputs/apk/debug/app-debug.apk        # APK must be newer still
```

### ⚠️ Orphaned assets inflate the APK and the inflation SURVIVES removal

Incremental `packageDebug` keeps dead bytes after an asset is deleted — a 1.5 GB APK was produced while
`unzip -l` still listed 183 MB. **`rm` the APK, then repackage**, and compare `stat -c %s` with
`unzip -l | tail -2` (the on-disk size should be *smaller* than the uncompressed total, not larger).

## Verifying an APK

SDK is at `C:/android`, build-tools **35.0.0**. PowerShell returns empty output (ConPTY) → use Bash with
`cmd //c "...apksigner.bat"`.

⚠️ A **second** SDK exists at `~/AppData/Local/Android/Sdk` (34.0.0 / 36.0.0 only — **no 35.0.0**). Probing
it first makes a correct documented path look stale. Confirm with `ls /c/android/build-tools` before
concluding anything is missing.

```bash
APK=app/build/outputs/apk/debug/app-debug.apk
/c/android/build-tools/35.0.0/zipalign.exe -c 4 $APK
cmd //c "C:\android\build-tools\35.0.0\apksigner.bat verify --print-certs $APK"
/c/android/build-tools/35.0.0/aapt2 dump badging $APK
unzip -l $APK | tail -2
```

- No `strings` on this machine → unzip `classes*.dex` and `grep -a` **all** of them (~19 shards).
- ⚠️ A third-party shard (`classes17.dex`) legitimately contains `glGetString` / `OpenGL` — it has **zero**
  app classes. Run `grep -c aicreatorstudio classes17.dex` before blaming our code; our classes live in
  `classes3/11.dex`.
- A native change has only really shipped when the packaged `.so` mtime (and the `stripped_native_libs` size)
  is newer than the `.cpp` that changed.
- Bundled fonts live in `resources.arsc`, not `res/font` — probe for family names.

## Release & archive

R8 + `shrinkResources` gives ~74 MB vs ~116 MB debug. Signing is wired through `local.properties`
(`OMELET_STORE_FILE=release-key.jks`, path relative to `app/`). Archive the debug build to
`dist/Omelet-1.4.0-debug-<feature>.apk`.

## Device checks

```bash
ADB=/c/android/platform-tools/adb.exe          # adb is NOT on PATH
$ADB devices
$ADB logcat -b crash -d                        # tombstones (Abort message + backtrace)
$ADB logcat -b crash -c                        # clear before a test run
```

⚠️ Git Bash rewrites `/sdcard/...` into a Windows path. Prefix device-path commands with
`MSYS_NO_PATHCONV=1` (and pull to a **Windows** destination, since `/tmp` does not exist).

⚠️ `uiautomator dump` returns the **keyguard** hierarchy when the phone is locked, and a Compose screen can
expose zero text nodes — do not treat an empty dump as "the UI is broken".
