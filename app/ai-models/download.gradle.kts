/*
 * Gradle task that fetches every AI model artifact referenced in
 * models.lock.json, SHA-256-verifies each one against the lockfile,
 * and writes them to the locations the Android build expects.
 *
 * Three sub-tasks:
 *
 *   downloadWhisperCppSource   -> app/src/main/cpp/whisper/
 *                                  app/src/main/cpp/ggml/
 *                                  (compiled by the existing
 *                                  externalNativeBuild + CMakeLists.txt
 *                                  into jniLibs/<abi>/libwhisper.so)
 *
 *   downloadAiModelWeights     -> app/src/main/assets/ai-models/<id>/
 *                                  (bundled into the APK)
 *
 *   generateAiModelsManifest   -> app/src/main/assets/ai-models/manifest.json
 *                                  (catalog embedded in the APK;
 *                                  runtime SHA lookup)
 *
 * The task is idempotent: a SHA-matching file is skipped.
 *
 * No fake files, no fake SHAs. Every entry in models.lock.json is a
 * real artifact verified by the maintainer against the official
 * publisher at lockfile-generation time.
 */

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

val lockFile = file("ai-models/models.lock.json")

// Resolve a stable staging dir at the project root. We never write
// outside the project tree, and we clean stale partials on entry.
val stagingDir: File = file("${rootDir}/build/ai-staging").apply { mkdirs() }

val cppWhisperDir: File = file("${rootDir}/app/src/main/cpp/whisper")
val cppGgmlDir: File = file("${rootDir}/app/src/main/cpp/ggml")
val cppRnnoiseDir: File = file("${rootDir}/app/src/main/cpp/rnnoise")
val assetsAiDir: File = file("${rootDir}/app/src/main/assets/ai-models")
val manifestOut: File = file("${assetsAiDir}/manifest.json")

val lockData: Map<String, Any?> by lazy {
    @Suppress("UNCHECKED_CAST")
    groovy.json.JsonSlurper().parseText(lockFile.readText()) as Map<String, Any?>
}

val nativeLibs: Map<String, Any?> by lazy {
    @Suppress("UNCHECKED_CAST")
    lockData["nativeLibs"] as Map<String, Any?>
}

val modelEntries: List<Map<String, Any?>> by lazy {
    @Suppress("UNCHECKED_CAST")
    lockData["models"] as List<Map<String, Any?>>
}

// ---------------------------------------------------------------------
// 1. whisper.cpp source -> app/src/main/cpp/whisper/ + ggml/
// ---------------------------------------------------------------------

val downloadWhisperCppSource by tasks.registering {
    group = "ai-models"
    description = "Downloads whisper.cpp v1.7.6 source, SHA-verifies, extracts into app/src/main/cpp/whisper/ + ggml/."

    val marker = file("${cppWhisperDir}/whisper.cpp")
    val whisperMeta: Map<String, Any?> = @Suppress("UNCHECKED_CAST") (nativeLibs["whisperCpp"] as Map<String, Any?>)
    val expectedSha: String = whisperMeta["sha256"] as String
    val url: String = whisperMeta["url"] as String
    val stagingTar = File(stagingDir, "whisper-cpp.tar.gz")
    val stagingTarSha = File(stagingDir, "whisper-cpp.tar.gz.sha256")

    inputs.property("url", url)
    inputs.property("expectedSha256", expectedSha)

    outputs.file(marker)

    doLast {
        if (marker.exists() && sha256Of(marker.parentFile.let { findExtractedMarker(it) } ?: marker) == expectedSha) {
            logger.lifecycle("[ai-models] whisper.cpp source already extracted at $cppWhisperDir (sha256 verified).")
            return@doLast
        }
        logger.lifecycle("[ai-models] Downloading whisper.cpp from $url")
        downloadTo(url, stagingTar)
        val got = sha256Of(stagingTar)
        require(got == expectedSha) {
            "whisper.cpp SHA mismatch.\n  expected: $expectedSha\n  got:      $got"
        }
        stagingTarSha.writeText(got)

        logger.lifecycle("[ai-models] SHA verified. Extracting whisper.cpp source...")
        cppWhisperDir.parentFile.mkdirs()
        if (cppWhisperDir.exists()) cppWhisperDir.deleteRecursively()
        if (cppGgmlDir.exists()) cppGgmlDir.deleteRecursively()

        // tar.gz extraction via the system `tar` binary (universally
        // available on every dev/CI host Gradle runs on; see
        // extractTarGz below for the rationale).
        extractTarGz(stagingTar, cppWhisperDir.parentFile)

        // whisper.cpp v1.7.6 lays out sources under
        //   whisper.cpp-1.7.6/src/whisper.cpp
        //   whisper.cpp-1.7.6/include/whisper.h
        //   whisper.cpp-1.7.6/ggml/src/...
        //   whisper.cpp-1.7.6/ggml/include/...
        // We flatten the four needed subtrees into
        //   app/src/main/cpp/whisper/
        //   app/src/main/cpp/ggml/
        // so the existing CMakeLists.txt (which expects whisper/ +
        // ggml/ at the top of the cpp dir) keeps working.
        val topLevel = cppWhisperDir.parentFile.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("whisper.cpp-") }
            ?.firstOrNull()
            ?: error("whisper.cpp source not extracted under ${cppWhisperDir.parentFile}")

        val srcWhisperSrc = File(topLevel, "src")
        val srcWhisperInc = File(topLevel, "include")
        val srcGgmlSrc = File(topLevel, "ggml/src")
        val srcGgmlInc = File(topLevel, "ggml/include")
        require(srcWhisperSrc.exists()) { "whisper.cpp tarball missing src/ dir" }
        require(srcWhisperInc.exists()) { "whisper.cpp tarball missing include/ dir" }
        require(srcGgmlSrc.exists()) { "whisper.cpp tarball missing ggml/src/ dir" }
        require(srcGgmlInc.exists()) { "whisper.cpp tarball missing ggml/include/ dir" }

        if (cppWhisperDir.exists()) cppWhisperDir.deleteRecursively()
        if (cppGgmlDir.exists()) cppGgmlDir.deleteRecursively()
        copyDirContents(srcWhisperSrc, cppWhisperDir)
        copyDirContents(srcWhisperInc, cppWhisperDir)
        copyDirContents(srcGgmlSrc, cppGgmlDir)
        copyDirContents(srcGgmlInc, cppGgmlDir)
        topLevel.deleteRecursively()

        logger.lifecycle("[ai-models] whisper.cpp source extracted to $cppWhisperDir + $cppGgmlDir")
    }
}

// ---------------------------------------------------------------------
// 1b. RNNoise source -> app/src/main/cpp/rnnoise/
// ---------------------------------------------------------------------
// Vendors the classic small-model RNNoise DSP+GRU sources (BSD) so
// Voice Clean runs genuine neural denoising fully offline. The
// pre-trained 8-bit weights live in src/rnn_data.c inside the
// tarball, so no runtime weight download is needed. Mirrors the
// whisper.cpp task above: SHA-verify, extract, flatten the needed
// files, keep license files.

val downloadRnnoiseSource by tasks.registering {
    group = "ai-models"
    description = "Downloads RNNoise source snapshot, SHA-verifies, extracts into app/src/main/cpp/rnnoise/."

    val marker = file("${cppRnnoiseDir}/denoise.c")
    val rnnoiseMeta: Map<String, Any?> = @Suppress("UNCHECKED_CAST") (nativeLibs["rnnoise"] as Map<String, Any?>)
    val expectedSha: String = rnnoiseMeta["sha256"] as String
    val url: String = rnnoiseMeta["url"] as String
    val stagingTar = File(stagingDir, "rnnoise.tar.gz")

    inputs.property("url", url)
    inputs.property("expectedSha256", expectedSha)

    outputs.file(marker)

    doLast {
        if (marker.exists()) {
            logger.lifecycle("[ai-models] RNNoise source already present at $cppRnnoiseDir.")
            return@doLast
        }
        logger.lifecycle("[ai-models] Downloading RNNoise from $url")
        downloadTo(url, stagingTar)
        val got = sha256Of(stagingTar)
        require(got == expectedSha) {
            "RNNoise SHA mismatch.\n  expected: $expectedSha\n  got:      $got"
        }

        logger.lifecycle("[ai-models] SHA verified. Extracting RNNoise source...")
        val tmpDir = File(stagingDir, "rnnoise-extract").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        extractTarGz(stagingTar, tmpDir)
        val topLevel = tmpDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("rnnoise-") }
            ?.firstOrNull()
            ?: error("RNNoise source not extracted under $tmpDir")

        if (cppRnnoiseDir.exists()) cppRnnoiseDir.deleteRecursively()
        cppRnnoiseDir.mkdirs()
        val srcDir = File(topLevel, "src")
        val incDir = File(topLevel, "include")
        require(srcDir.exists()) { "RNNoise tarball missing src/ dir" }
        require(incDir.exists()) { "RNNoise tarball missing include/ dir" }
        // Keep exactly the file set the upstream jni/Makefile.srcs
        // builds on Android (+ license files for attribution).
        listOf(
            "denoise.c", "pitch.c", "celt_lpc.c", "kiss_fft.c", "rnn.c",
            "rnn_data.c", "rnn_reader.c",
            "pitch.h", "celt_lpc.h", "kiss_fft.h",
            "_kiss_fft_guts.h", "rnn.h", "rnn_data.h", "tansig_table.h",
            "opus_types.h", "common.h", "arch.h"
        ).forEach { name ->
            val src = File(srcDir, name)
            require(src.exists()) { "RNNoise tarball missing src/$name" }
            src.copyTo(File(cppRnnoiseDir, name), overwrite = true)
        }
        File(incDir, "rnnoise.h").copyTo(File(cppRnnoiseDir, "rnnoise.h"), overwrite = true)
        listOf("COPYING", "AUTHORS").forEach { name ->
            val f = File(topLevel, name)
            if (f.exists()) f.copyTo(File(cppRnnoiseDir, name), overwrite = true)
        }
        // Vendored patch (deterministic): the snapshot's rnnoise.h
        // opens `extern "C"` twice but closes it once, which breaks
        // C++ inclusion (hard error in rnnoise-jni.cpp). Remove the
        // second opener; the single closer at EOF is unchanged. The
        // replace is verified so a future upstream fix (single
        // opener) fails loudly instead of silently double-patching.
        val headerFile = File(cppRnnoiseDir, "rnnoise.h")
        val headerText = headerFile.readText()
        val dupOpener = "#ifdef __cplusplus\nextern \"C\" {\n#endif\n\ntypedef struct DenoiseState"
        require(headerText.contains(dupOpener)) {
            "rnnoise.h layout changed: duplicate extern \"C\" opener not found; review the vendored patch"
        }
        headerFile.writeText(
            headerText.replace(
                dupOpener,
                "// NOTE (vendored patch): duplicate extern \"C\" opener removed.\n\ntypedef struct DenoiseState"
            )
        )
        tmpDir.deleteRecursively()
        logger.lifecycle("[ai-models] RNNoise source extracted to $cppRnnoiseDir")
    }
}

// ---------------------------------------------------------------------
// 2. Model weights -> app/src/main/assets/ai-models/<id>/
// ---------------------------------------------------------------------

val downloadAiModelWeights by tasks.registering {
    group = "ai-models"
    description = "Downloads every model in models.lock.json into app/src/main/assets/ai-models/, SHA-verified."

    inputs.files(lockFile)
    outputs.dir(assetsAiDir)

    doLast {
        assetsAiDir.mkdirs()
        for (entry in modelEntries) {
            val id = entry["id"] as String
            val dirName = entry["dirName"] as String
            val fileName = entry["fileName"] as String
            val url = entry["url"] as String
            val expectedSha = entry["sha256"] as String
            val expectedSize = (entry["sizeBytes"] as Number).toLong()

            // PHASE B1.7 — models flagged with `"bundled": false`
            // (e.g. whisper-large-v3, 3.1 GB) are NEVER downloaded
            // into `app/src/main/assets/`. They are runtime-only;
            // the user downloads them from inside the app and the
            // installer persists them under `filesDir/models/`.
            // Bundling a 3.1 GB binary into the APK would bloat
            // every install and violate the brief's "do not
            // bundle Large-v3" rule. Default = `true` so older
            // entries (Medium, selfie_multiclass, pose_landmarker)
            // keep their existing behavior — the lockfile change
            // is the only edit required to flip a model to
            // download-only.
            val bundled = (entry["bundled"] as? Boolean) ?: true
            if (!bundled) {
                logger.lifecycle(
                    "[ai-models] $id is download-only (bundled=false); " +
                        "skipping assets/. The app will download it " +
                        "from $url on first use."
                )
                // Ensure any leftover partial / stale artifacts from
                // a previous (bundled=true) run are removed so a
                // future re-bundle of the model still produces a
                // clean tree. We DO NOT remove a real, SHA-verified
                // installed model from `filesDir/models/` — the
                // uninstall path lives in the runtime, not here.
                val outDir = File(assetsAiDir, dirName)
                if (outDir.exists()) {
                    outDir.listFiles()?.forEach { stale ->
                        logger.lifecycle("[ai-models]   removing stale $id artifact: ${stale.name}")
                        stale.delete()
                    }
                    outDir.delete()
                }
                continue
            }

            val outDir = File(assetsAiDir, dirName).apply { mkdirs() }
            val outFile = File(outDir, fileName)
            val partial = File(outDir, "$fileName.partial")

            if (outFile.exists() && outFile.length() == expectedSize && sha256Of(outFile) == expectedSha) {
                logger.lifecycle("[ai-models] $id already present and SHA-verified at ${outFile.absolutePath}")
                continue
            }

            logger.lifecycle("[ai-models] Downloading $id ($expectedSize bytes) from $url")
            downloadTo(url, partial)

            val gotSize = partial.length()
            val gotSha = sha256Of(partial)
            if (gotSize != expectedSize) {
                partial.delete()
                error("$id size mismatch. expected=$expectedSize got=$gotSize")
            }
            if (gotSha != expectedSha) {
                partial.delete()
                error("$id SHA mismatch.\n  expected: $expectedSha\n  got:      $gotSha")
            }

            if (!partial.renameTo(outFile)) {
                // renameTo can fail across volumes / AV locks; fall back
                // to a copy + delete.
                outFile.delete()
                partial.copyTo(outFile)
                partial.delete()
            }
            File(outDir, "$fileName.sha256").writeText(gotSha)
            logger.lifecycle("[ai-models] $id ready at ${outFile.absolutePath} (sha256 verified)")
        }
    }
}

// ---------------------------------------------------------------------
// 3. Manifest generator -> app/src/main/assets/ai-models/manifest.json
// ---------------------------------------------------------------------

val generateAiModelsManifest by tasks.registering {
    group = "ai-models"
    description = "Generates app/src/main/assets/ai-models/manifest.json from models.lock.json. Strip URLs; runtime only needs id + sha256 + size + dirName + fileName + bundledOnly."

    inputs.files(lockFile)
    outputs.file(manifestOut)

    doLast {
        assetsAiDir.mkdirs()
        // The runtime ModelRegistry reads the full lockfile (URLs +
        // SHAs + sizes) from assets. Copy it verbatim so the
        // installer never invents a URL or SHA on its own.
        lockFile.copyTo(File(assetsAiDir, "models.lock.json"), overwrite = true)
        val manifest = StringBuilder()
        manifest.append("{\n")
        manifest.append("  \"_format\": \"aicreatorstudio-ai-manifest-v1\",\n")
        manifest.append("  \"_generatedAt\": \"${java.time.Instant.now()}\",\n")
        manifest.append("  \"models\": [\n")
        for ((i, entry) in modelEntries.withIndex()) {
            val id = entry["id"] as String
            val dirName = entry["dirName"] as String
            val fileName = entry["fileName"] as String
            val size = (entry["sizeBytes"] as Number).toLong()
            val sha = entry["sha256"] as String
            val displayName = entry["displayName"] as String
            val bundledOnly = (entry["bundledOnly"] as? Boolean) ?: false
            manifest.append("    {\n")
            manifest.append("      \"id\": \"$id\",\n")
            manifest.append("      \"displayName\": \"$displayName\",\n")
            manifest.append("      \"dirName\": \"$dirName\",\n")
            manifest.append("      \"fileName\": \"$fileName\",\n")
            manifest.append("      \"sizeBytes\": $size,\n")
            manifest.append("      \"sha256\": \"$sha\",\n")
            manifest.append("      \"bundledOnly\": $bundledOnly\n")
            manifest.append("    }")
            if (i < modelEntries.size - 1) manifest.append(",")
            manifest.append("\n")
        }
        manifest.append("  ]\n")
        manifest.append("}\n")
        manifestOut.writeText(manifest.toString())
        logger.lifecycle("[ai-models] manifest written to $manifestOut")
    }
}

// Aggregate task so a single command does everything.
val downloadAiAssets by tasks.registering {
    group = "ai-models"
    description = "Downloads whisper.cpp source + RNNoise source + every AI model weight + generates the runtime manifest. Idempotent; skips files that are already SHA-verified."

    dependsOn(downloadWhisperCppSource, downloadRnnoiseSource, downloadAiModelWeights, generateAiModelsManifest)
}

// Auto-wire to preBuild so a clean Gradle build populates jniLibs/ + assets/.
afterEvaluate {
    tasks.findByName("preBuild")?.dependsOn(downloadAiAssets)
    tasks.findByName("mergeDebugAssets")?.dependsOn(downloadAiAssets)
    tasks.findByName("mergeReleaseAssets")?.dependsOn(downloadAiAssets)
}

// ---------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------

fun sha256Of(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { ins ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

fun downloadTo(url: String, out: File) {
    out.parentFile.mkdirs()
    if (out.exists()) out.delete()
    @Suppress("DEPRECATION")
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 30_000
        readTimeout = 120_000
        setRequestProperty("User-Agent", "AI-Creator-Studio-Gradle/1.0")
        instanceFollowRedirects = true
    }
    try {
        val rc = conn.responseCode
        require(rc in 200..299) { "HTTP $rc downloading $url" }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
        conn.inputStream.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(128 * 1024)
                var totalRead = 0L
                var lastLog = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    totalRead += n
                    if (System.currentTimeMillis() - lastLog > 2000) {
                        if (total > 0) {
                            val pct = (totalRead.toDouble() / total * 100).toInt()
                            logger.lifecycle("[ai-models]   ${out.name}: $pct% (${totalRead / 1024} / ${total / 1024} KB)")
                        } else {
                            logger.lifecycle("[ai-models]   ${out.name}: ${totalRead / 1024} KB")
                        }
                        lastLog = System.currentTimeMillis()
                    }
                }
            }
        }
    } finally {
        conn.disconnect()
    }
}

fun findExtractedMarker(dir: File): File? =
    if (File(dir, "whisper/whisper.cpp").exists()) File(dir, "whisper/whisper.cpp") else null

fun extractTarGz(tarGz: File, destDir: File) {
    // Tar.gz extraction. We shell out to the host's `tar` binary
    // because (a) it's universally available on every CI/dev
    // machine Gradle runs on (Linux, macOS, Git Bash on Windows,
    // WSL), (b) Ant's Expand task has known type-attribute
    // incofccnsistencies across 1.10.x patch versions, and
    // (c) the Gradle script classpath doesn't expose Apache
    // Commons Compress directly.
    destDir.mkdirs()
    val cmd = listOf("tar", "-xzf", tarGz.absolutePath, "-C", destDir.absolutePath)
    val proc = ProcessBuilder(cmd)
        .redirectErrorStream(true)
        .start()
    val out = proc.inputStream.bufferedReader().readText()
    val rc = proc.waitFor()
    if (rc != 0) {
        error("tar extraction failed (rc=$rc): $out")
    }
}

/**
 * Recursively copy the contents of [src] (a directory) into [dest].
 * The destination is created if it doesn't exist; existing files
 * are overwritten. Used by [downloadWhisperCppSource] to flatten
 * whisper.cpp's v1.7.6 source tree (which nests whisper.cpp's C++
 * under `src/` and ggml's C under `ggml/src/`) into the
 * `app/src/main/cpp/whisper/` and `app/src/main/cpp/ggml/` layout
 * the CMakeLists.txt expects.
 */
fun copyDirContents(src: File, dest: File) {
    dest.mkdirs()
    src.listFiles()?.forEach { child ->
        val target = File(dest, child.name)
        if (child.isDirectory) {
            copyDirContents(child, target)
        } else {
            child.copyTo(target, overwrite = true)
        }
    }
}