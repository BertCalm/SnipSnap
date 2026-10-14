package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportOutcome
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.Exporters
import com.snipsnap.kit.Finding
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Names
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.OspreyPatch
import com.snipsnap.synth.PadRecipe
import com.snipsnap.xpm.PadNoteMap
import com.snipsnap.xpm.WavInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A handoff of the existing OSPREY starter, not a new voicing. The production
 * starter, kit store and export drivers do all rendering/writing. This harness
 * verifies the files they made, then places editable kit metadata alongside the
 * one WAV set shared by SnipSnap, the MPC 3 track and its MPC 2 twin.
 */
object OspreyHandoffGenerator {
    const val KIT_NAME = "SnipSnap Osprey R3"
    const val ARCHIVE_NAME = "osprey-r3-kit.zip"
    const val MANIFEST_NAME = "handoff-manifest.json"
    private const val MAX_ARCHIVE_BYTES = 25L * 1024 * 1024
    private const val RATE = 44_100
    private const val FRAMES = RATE * 8
    private const val R3_SOURCE = "bad8d8a52277bd92d27407f6aa5370cff11422e2"

    internal data class Provenance(val commit: String, val sources: Map<String, String>)
    internal data class Result(val archive: File, val manifest: File, val bundle: File)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 2) { "Supply an output directory and optional preserved R3 audition directory." }
        val repository = repositoryRoot()
        val output = File(args.getOrNull(0) ?: "../testkit/osprey-handoff").canonicalFile
        val baseline = File(args.getOrNull(1) ?: "../testkit/osprey-gentle-audition").canonicalFile
        require(!output.toPath().startsWith(baseline.toPath()) && !baseline.toPath().startsWith(output.toPath())) {
            "Keep the immutable audition and handoff output in separate directories."
        }
        require(git(repository, "status", "--porcelain").isEmpty()) {
            "Commit the tested source before generating a handoff; source provenance requires a clean HEAD."
        }
        val sourcePaths = git(repository, "ls-files").lines().filter { path ->
            path.endsWith(".kt") && (
                path.startsWith("synth/src/main/") || path.startsWith("audio/src/main/") ||
                    path.startsWith("kit/src/main/") || path.startsWith("mpc3/src/main/") ||
                    path.startsWith("xpm/src/main/") || path.startsWith("json/src/main/") ||
                    path == "shell/src/main/kotlin/com/snipsnap/shell/StarterKits.kt" ||
                    path == "shell/src/test/kotlin/com/snipsnap/shell/OspreyHandoffGenerator.kt"
                )
        } + "shell/build.gradle.kts"
        val source = Provenance(git(repository, "rev-parse", "HEAD"), sourcePaths.sorted().associateWith { sha(File(repository, it)) })
        require(File(baseline, "manifest.json").isFile) {
            "The preserved published R3 audition is required. Supply its directory as the second argument; do not regenerate it."
        }
        val result = generate(output, baseline, source)
        println("${result.archive.name}: ${result.archive.length()} bytes, SHA-256 ${sha(result.archive)}")
        println("16 unchanged model-3 recipes and 16 shared PCM24 WAVs; native and compatibility read-back complete.")
        println("Hardware loading and phone playback remain unverified. ${result.manifest}")
    }

    /** [baseline] may be absent only in the integration test's temporary fixture. */
    internal fun generate(output: File, baseline: File?, source: Provenance): Result {
        require(Names.isMpcSafe(KIT_NAME))
        val historicalManifest = baseline?.let { File(it, "manifest.json") }
        val historicalHash = historicalManifest?.let(::sha)
        val historical = historicalManifest?.let { Json.parse(it.readText()).obj() }
        historical?.let {
            require(it.getValue("engine").str() == "OSPREY" && it.getValue("renderModel").int() == 3)
            require(it.getValue("sourceCommit").str() == R3_SOURCE) { "Use the preserved published gentle-R3 audition." }
            require(!it.getValue("workingTreeDirty").bool()) { "The R3 reference must have clean source provenance." }
        }
        val historicalRows = historical?.getValue("clips")?.arr().orEmpty().map { it.obj() }
            .filter { it.getValue("renderModel").int() == 3 && !it.getValue("excerpt").bool() }
        output.mkdirs()
        val bundle = File(output, KIT_NAME)
        bundle.deleteRecursively()
        bundle.mkdirs()
        val snipsnap = File(bundle, "snipsnap")
        val kit = requireNotNull(StarterKits.byId("osprey")).render(KIT_NAME, snipsnap)
        val loaded = KitStore.load(snipsnap)
        require(kit == loaded) { "KitStore load changed the starter metadata or recipes." }
        require(loaded.pads.map { it.slot } == (1..16).toList()) { "The existing sixteen-pad starter must retain its order." }
        val preflight = Preflight.check(loaded, snipsnap)
        require(!preflight.blocked()) { "Starter preflight failed: $preflight" }

        val rows = loaded.pads.map { pad ->
            val label = PadNoteMap.labelForPad(pad.slot)
            val recipeValue = requireNotNull(pad.recipe) { "$label has no recipe." }
            val recipe = PadRecipe.fromJsonValue(recipeValue)
            val patch = recipe.patch as? OspreyPatch ?: error("$label is not OSPREY.")
            require(patch.modelVersion == 3 && patch.midi == 60 && patch.seed == 731 &&
                patch.velocity == 1f && patch.durationSeconds == 8f) { "$label changed the settled R3 source controls." }
            require(recipe.fx == null && !recipe.alias && recipe.treatment == null && recipe.amount == null)
            require(patch.macros.getValue("SPACE") == 0f) { "$label must retain its dry source." }
            require(pad.drumClass == DrumClass.TONAL && pad.oneShot && pad.tuneCoarse == 0 && pad.tuneFine == 0 &&
                pad.velocityLayers.isEmpty() && pad.chain == null && pad.pan == .5f && pad.level == .707946f &&
                pad.attack == null && pad.decay == null && pad.cutoff == null && pad.resonance == null && pad.humanize == null) {
                "$label acquired a playback, FX, pitch or shape treatment."
            }
            val raw = recipe.render()
            require(raw.sampleRate == RATE && raw.channels == 2 && raw.frameCount == FRAMES)
            require(raw.samples.all { it.isFinite() } && raw.samples.maxOf { abs(it) } < 1f) { "$label clips or contains a non-finite value before PCM encoding." }
            val file = File(snipsnap, pad.sampleFile)
            val encoded = encode(raw, WavWriter.BitDepth.PCM_24)
            require(file.readBytes().contentEquals(encoded)) { "$label no longer regenerates the exact starter WAV bytes." }
            val info = WavInfo.read(file)
            require(info.bitsPerSample == 24 && info.sampleRate == RATE && info.channels == 2 && info.frameCount == FRAMES.toLong())
            require(WavReader.readSmpl(file) == null) { "$label gained a sampler loop or pitch sheet." }
            val decoded = WavReader.read(file)
            val maxError = raw.samples.indices.maxOf { abs(raw.samples[it] - decoded.samples[it]) }
            require(maxError <= 2e-7f) { "$label PCM24 error exceeds encoding precision: $maxError" }
            val recipeFile = File(bundle, "recipes/$label.json").apply { parentFile.mkdirs(); writeText(Json.write(recipeValue) + "\n") }
            require(PadRecipe.fromJsonText(recipeFile.readText()).toJsonValue() == recipeValue)
            val matches = historicalRows.filter { old ->
                val patchFile = resolve(requireNotNull(baseline), old.getValue("patchFile").str())
                val oldPatch = OspreyPatch.fromJsonText(patchFile.readText())
                oldPatch.copy(name = patch.name) == patch
            }.map { old ->
                val rawPath = old.getValue("rawFile").str()
                val oldRaw = resolve(requireNotNull(baseline), rawPath)
                val patchFile = resolve(baseline, old.getValue("patchFile").str())
                require(sha(oldRaw) == old.getValue("rawSha256").str() && sha(patchFile) == old.getValue("patchSha256").str()) {
                    "The preserved R3 reference was modified."
                }
                val pcm16 = encode(raw, WavWriter.BitDepth.PCM_16)
                require(pcm16.contentEquals(oldRaw.readBytes())) { "$label differs from the published R3 raw audition." }
                linkedMapOf<String, Any?>("id" to old.getValue("id").str(), "sourceCommit" to old.getValue("sourceCommit").str(),
                    "rawFile" to rawPath, "rawSha256" to sha(oldRaw), "patchSha256" to sha(patchFile),
                    "comparisonEncoding" to "unmodified engine samples encoded as PCM16 by WavWriter", "byteExact" to true)
            }
            linkedMapOf<String, Any?>(
                "slot" to pad.slot, "label" to label, "name" to patch.name, "kitDisplayName" to pad.displayName,
                "mpcTriggerMidi" to PadNoteMap.noteForPad(pad.slot), "sourceMidi" to patch.midi,
                "sourceNote" to "C4", "modelVersion" to 3, "seed" to patch.seed, "velocity" to patch.velocity,
                "durationSeconds" to 8, "loop" to false, "oneShot" to true, "fx" to null, "normalizationGain" to 1,
                "macros" to patch.macros, "rawFile" to relative(bundle, file), "recipeFile" to relative(bundle, recipeFile),
                "rawSha256" to sha(file), "recipeSha256" to sha(recipeFile), "recipeJsonSha256" to sha(Json.write(recipeValue).toByteArray()),
                "recipeRegenerationByteExact" to true, "maxPcm24EncodingError" to maxError,
                "pcm" to pcm(info, file), "rawEngineDiagnostics" to diagnostics(raw), "decodedPcmDiagnostics" to diagnostics(decoded),
                "publishedR3RawMatches" to matches,
            )
        }
        require(baseline == null || rows.sumOf { (it.getValue("publishedR3RawMatches") as List<*>).size } == 11) {
            "Expected eleven exact patch matches in the published R3 audition."
        }
        val native = Exporters.export(ExportFormat.MPC3_TRACK, loaded, snipsnap, File(bundle, "mpc"), dualGeneration = true)
        val nativeReport = ExportReadBack.verify(loaded, native)
        val shared = requireNotNull(native.companion)
        val twin = File(shared, "$KIT_NAME.xpm")
        require(twin.isFile) { "The compatibility twin is missing." }
        val twinReport = ExportReadBack.verify(loaded, ExportOutcome(ExportFormat.PROGRAM_FOLDER, twin, null, native.findings))
        require(!nativeReport.blocked() && !twinReport.blocked()) { "Export read-back failed: $nativeReport $twinReport" }
        for ((pad, row) in loaded.pads.zip(rows)) {
            val copy = File(shared, "${Names.sanitizeStem(pad.sampleStem)}.wav")
            require(copy.readBytes().contentEquals(File(snipsnap, pad.sampleFile).readBytes())) { "MPC export altered ${pad.sampleFile}." }
            row["starterSampleFile"] = pad.sampleFile
            row["rawFile"] = relative(bundle, copy)
            row["mpcFile"] = relative(bundle, copy)
            row["mpcSha256"] = sha(copy)
        }
        // The exported WAV bytes are already identical. Remap only their bare
        // filenames in kit.json so SnipSnap can use that same sample folder.
        val sharedKit = loaded.copy(pads = loaded.pads.map { pad ->
            pad.copy(sampleFile = "${Names.sanitizeStem(pad.sampleStem)}.wav")
        })
        require(sharedKit.copy(pads = sharedKit.pads.zip(loaded.pads).map { (mapped, original) ->
            mapped.copy(sampleFile = original.sampleFile)
        }) == loaded) { "Shared-kit remapping changed more than the sample filenames." }
        val sharedKitFile = KitStore.save(sharedKit, shared)
        val sharedLoaded = KitStore.load(shared)
        require(sharedLoaded == sharedKit) { "Shared-folder KitStore load changed metadata or recipes." }
        val sharedPreflight = Preflight.check(sharedLoaded, shared)
        require(!sharedPreflight.blocked()) { "Shared-folder kit preflight failed: $sharedPreflight" }
        for ((pad, row) in sharedLoaded.pads.zip(rows)) {
            val regenerated = PadRecipe.fromJsonValue(requireNotNull(pad.recipe)).render()
            val bytes = File(shared, pad.sampleFile).readBytes()
            require(bytes.contentEquals(encode(regenerated, WavWriter.BitDepth.PCM_24))) {
                "Shared-folder recipe no longer regenerates ${pad.sampleFile}."
            }
            require(sha(bytes) == row.getValue("rawSha256")) { "Shared-folder metadata changed the raw audio." }
            row["sharedRecipeRegenerationByteExact"] = true
        }
        require(snipsnap.deleteRecursively()) { "Could not remove the redundant starter WAV staging folder." }
        require(bundle.walkTopDown().count { it.isFile && it.extension.equals("wav", true) } == 16) {
            "The bundle must have one shared set of sixteen WAVs for SnipSnap and both MPC generations."
        }
        val validation = linkedMapOf<String, Any?>(
            "preflight" to findings(preflight), "sharedKitPreflight" to findings(sharedPreflight),
            "nativeXtd" to findings(nativeReport), "compatibilityXpm" to findings(twinReport),
            "starterKitStoreLoadExact" to true, "sharedKitStoreLoadExact" to true,
            "sharedKitDiffersOnlyBySampleFilenames" to true,
            "recipeRegenerationByteExactPads" to 16, "sharedRecipeRegenerationByteExactPads" to 16, "exportAudioByteExactPads" to 16,
            "publishedR3RawByteExactPads" to rows.count { (it.getValue("publishedR3RawMatches") as List<*>).isNotEmpty() },
            "hardwareVerified" to false, "phoneVerified" to false,
            "readBackLimit" to "Read-back proves agreement with SnipSnap's reader. It does not establish hardware loading. SKIP rows remain unchecked.",
        )
        writeJson(File(bundle, "validation.json"), validation)
        File(bundle, "PAD-MAP.txt").writeText(padMap(sharedLoaded))
        File(bundle, "README.txt").writeText(readme())
        require(historicalManifest == null || sha(historicalManifest) == historicalHash) { "The preserved audition manifest changed during generation." }
        val files = bundle.walkTopDown().filter { it.isFile }.sortedBy { relative(bundle, it) }.map {
            linkedMapOf("path" to relative(bundle, it), "bytes" to it.length(), "sha256" to sha(it))
        }.toList()
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "engine" to "OSPREY", "title" to "Osprey R3 working-baseline kit",
            "starterId" to "osprey", "kitName" to KIT_NAME, "renderModel" to 3,
            "sourceCommit" to source.commit, "workingTreeDirty" to false, "sourceFilesSha256" to source.sources,
            "audioPolicy" to "Existing starter rendered without changing DSP, presets, order, FX, pitch, gain or normalization. Production PCM24 WAV bytes are preserved in one shared folder; kit.json sample filenames alone are mapped to the export filenames.",
            "sampleRate" to RATE, "channels" to 2, "bitDepth" to 24, "padCount" to 16, "waveFileCount" to 16,
            "bundleRoot" to KIT_NAME, "snipsnapKit" to relative(bundle, sharedKitFile), "nativeProgram" to relative(bundle, native.primary),
            "compatibilityProgram" to relative(bundle, twin), "sharedMpcAudioFolder" to relative(bundle, shared),
            "padMap" to "PAD-MAP.txt", "instructions" to "README.txt", "validationReport" to "validation.json",
            "baseline" to historical?.let { linkedMapOf("sourceCommit" to R3_SOURCE, "manifestSha256" to historicalHash, "audioModified" to false) },
            "reviewStatus" to "R3 is the owner's working audio baseline. Phone playback and MPC loading remain unverified.",
            "validation" to validation, "pads" to rows,
            "fileInventoryPolicy" to "Every bundled file except this self-referential manifest is listed by path, byte count and SHA-256.",
            "files" to files,
        )
        writeJson(File(bundle, "manifest.json"), manifest)
        val archive = File(output, ARCHIVE_NAME)
        archive(bundle, archive)
        require(archive.length() <= MAX_ARCHIVE_BYTES) { "Archive is ${archive.length()} bytes; hosting budget is $MAX_ARCHIVE_BYTES." }
        val archiveRows = ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size == bundle.walkTopDown().count { it.isFile })
            entries.map { entry ->
                require(!entry.isDirectory && entry.name.startsWith("$KIT_NAME/") && ".." !in entry.name.split('/'))
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val sourceFile = resolve(bundle, entry.name.removePrefix("$KIT_NAME/"))
                require(bytes.contentEquals(sourceFile.readBytes())) { "ZIP round-trip changed ${entry.name}." }
                linkedMapOf("path" to entry.name, "bytes" to bytes.size, "sha256" to sha(bytes))
            }
        }
        val receipt = LinkedHashMap(manifest).apply {
            put("archive", linkedMapOf("file" to ARCHIVE_NAME, "bytes" to archive.length(), "sha256" to sha(archive),
                "entryCount" to archiveRows.size, "zipRoundTripByteExact" to true, "entries" to archiveRows))
        }
        val manifestFile = File(output, MANIFEST_NAME)
        writeJson(manifestFile, receipt)
        return Result(archive, manifestFile, bundle)
    }

    private fun pcm(info: WavInfo, file: File): Map<String, Any> = linkedMapOf(
        "encoding" to "signed-integer PCM", "sampleRate" to info.sampleRate, "channels" to info.channels,
        "bitDepth" to info.bitsPerSample, "frames" to info.frameCount, "bytes" to file.length(), "samplerMetadata" to "absent",
    )

    private fun diagnostics(snip: Snip): Map<String, Any> {
        val samples = snip.samples
        val left = samples.indices.step(2).sumOf { samples[it].toDouble() } / snip.frameCount
        val right = (1 until samples.size step 2).sumOf { samples[it].toDouble() } / snip.frameCount
        val tailStart = (snip.frameCount.toInt() - RATE / 50).coerceAtLeast(0) * 2
        return linkedMapOf(
            "finite" to samples.all { it.isFinite() }, "peak" to samples.maxOf { abs(it) },
            "rms" to sqrt(samples.sumOf { it.toDouble() * it } / samples.size),
            "leftDc" to left, "rightDc" to right, "railSampleCount" to samples.count { abs(it) >= 1f },
            "tail20msRms" to sqrt((tailStart until samples.size).sumOf { samples[it].toDouble() * samples[it] } / (samples.size - tailStart)),
            "maxAdjacentFrameStep" to (2 until samples.size).maxOf { abs(samples[it] - samples[it - 2]) },
        )
    }

    private fun padMap(kit: Kit): String = buildString {
        append("OSPREY R3 — EXISTING STARTER PAD MAP\n\n")
        append("All samples: C4/source MIDI 60, seed 731, velocity 1, dry, 8-second finite one-shots.\n")
        append("MPC trigger MIDI selects a pad; it does not transpose the recorded C4 source.\n")
        append("Pad mixer levels retain the starter's 0.707946 setting; WAVs have no normalization.\n\n")
        append("PAD   TRIGGER  SOURCE  RECIPE NAME                  WAV\n")
        for (pad in kit.pads) {
            val patch = PadRecipe.fromJsonValue(requireNotNull(pad.recipe)).patch as OspreyPatch
            append(String.format(Locale.ROOT, "%s   %3d      C4      %-28s %s\n", PadNoteMap.labelForPad(pad.slot),
                PadNoteMap.noteForPad(pad.slot), patch.name, pad.sampleFile))
        }
        append("\nPhysical MPC grid, top to bottom: A13 A14 A15 A16 / A09 A10 A11 A12 / A05 A06 A07 A08 / A01 A02 A03 A04.\n")
        append("A16 BETWEEN SHORES retains its extended excitation (HOLD 1); it still ends at 8 seconds and is not a loop.\n")
    }

    private fun readme(): String = """
        OSPREY R3 — WORKING AUDIO BASELINE

        This is the existing SnipSnap OSPREY starter: fifteen coast/sea presets,
        then BETWEEN SHORES. R3 preserves the sound direction the owner preferred.
        No DSP, preset, pad-order, FX, normalization or pitch changes were made.
        All files are raw 24-bit PCM, 44.1 kHz stereo, eight-second finite one-shots
        at source MIDI 60 (C4), seed 731 and source velocity 1. Pad mixer levels
        retain the production starter's 0.707946 setting. The WAV bytes remain raw.

        SNIPSNAP
        The mpc/${KIT_NAME}_[TrackData]/ folder contains kit.json and sixteen
        WAVs. Keep them together. Open that folder as a kit using the existing
        SnipSnap kit-folder workflow. Every pad retains its model-3 recipe;
        only sample filenames in kit.json are mapped to the export filenames.
        All other starter metadata and every audio byte are preserved.
        recipes/A01.json through A16.json are separate copies for inspection.
        A binary/phone install is not included or verified by this download.

        MPC 3
        Copy the entire mpc/ folder to your drive. Keep $KIT_NAME.xtd beside
        ${KIT_NAME}_[TrackData]/.
        Load the .xtd as a native drum track on MPC 3 firmware.

        MPC 2 COMPATIBILITY
        Browse inside mpc/${KIT_NAME}_[TrackData]/ and load $KIT_NAME.xpm.
        This compatibility program, the native track and SnipSnap kit.json all
        share one set of WAVs. Do not move these files away from their samples.

        CHECKS AND LISTENING
        PAD-MAP.txt names each pad and its trigger note. manifest.json records
        source, recipe and file hashes, PCM bounds and raw R3 audition matches.
        validation.json holds preflight and native/XPM export read-back reports.
        Original and shared-folder KitStore reloads, recipe regeneration,
        export audio and ZIP extraction are checked for exact agreement. The
        sample-filename metadata remap is checked separately. The adjacent
        handoff-manifest.json also
        identifies the ZIP by byte count and SHA-256.
        Read-back agrees with SnipSnap's own reader; it is not a hardware pass.
        SKIP findings remain unchecked. Phone playback and MPC loading are still
        unverified. Start at a comfortable monitor level; compare voices before
        adding FX. HOLD changes finite excitation, never loop playback.
    """.trimIndent() + "\n"

    private fun findings(rows: List<Finding>) = rows.map { linkedMapOf("severity" to it.severity.name, "message" to it.message, "slot" to it.slot) }
    private fun encode(snip: Snip, depth: WavWriter.BitDepth): ByteArray = ByteArrayOutputStream().also { WavWriter.write(it, snip, depth) }.toByteArray()
    private fun archive(bundle: File, target: File) {
        ZipOutputStream(target.outputStream().buffered()).use { out ->
            out.setLevel(Deflater.BEST_COMPRESSION)
            bundle.walkTopDown().filter { it.isFile }.sortedBy { relative(bundle, it) }.forEach { file ->
                out.putNextEntry(ZipEntry("${bundle.name}/${relative(bundle, file)}").apply { time = 0L })
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }

    private fun relative(root: File, file: File) = file.relativeTo(root).invariantSeparatorsPath
    private fun resolve(root: File, path: String): File = File(root, path).canonicalFile.also {
        require(it.toPath().startsWith(root.canonicalFile.toPath())) { "Reference escapes its pack: $path" }
    }
    private fun sha(file: File) = sha(file.readBytes())
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
    private fun writeJson(file: File, value: Any?) = file.writeText(Json.write(json(value)) + "\n")
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble())
        is Map<*, *> -> JsonValue.Obj(value.entries.associateTo(LinkedHashMap()) { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported JSON value ${value.javaClass.name}")
    }
    private fun repositoryRoot(): File = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, ".git").exists() }
    private fun git(repository: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repository.path) + arguments).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        require(process.waitFor() == 0) { "Cannot read source provenance: $output" }
        return output.trim()
    }
}
