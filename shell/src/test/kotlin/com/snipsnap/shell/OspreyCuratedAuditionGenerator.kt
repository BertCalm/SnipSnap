package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
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
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.synth.OspreyCuratedPresets
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
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.w3c.dom.Element

/** An audition and editable handoff of the new presets on the unchanged R3 engine. */
object OspreyCuratedAuditionGenerator {
    const val KIT_NAME = "SnipSnap Osprey Curated"
    const val ARCHIVE_NAME = "osprey-curated-kit.zip"
    private const val MAX_ARCHIVE_BYTES = 25L * 1024 * 1024
    private const val RATE = 44_100
    private const val PEAK_CEILING = .8f
    private const val REQUESTED_RMS = .12f

    internal data class Provenance(val commit: String, val sources: Map<String, String>)
    internal data class Result(val archive: File, val manifest: File, val bundle: File)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply at most one output directory." }
        val repository = repositoryRoot()
        require(git(repository, "status", "--porcelain").isEmpty()) {
            "Commit the tested source before rendering the curated audition; provenance requires a clean HEAD."
        }
        val sourcePaths = git(repository, "ls-files").lines().filter { path ->
            path.endsWith(".kt") || path.endsWith(".gradle.kts") ||
                path == "gradle.properties" || path == "gradle/libs.versions.toml" ||
                path == "gradle/wrapper/gradle-wrapper.properties"
        }
        val source = Provenance(git(repository, "rev-parse", "HEAD"),
            sourcePaths.sorted().associateWith { sha(File(repository, it)) })
        val output = File(args.getOrNull(0) ?: "../testkit/osprey-curated-audition").canonicalFile
        val result = generate(output, source)
        println("${result.archive.name}: ${result.archive.length()} bytes; SHA-256 ${sha(result.archive)}")
        println("Sixteen new model-3 presets, raw PCM24 kit, matched PCM16 previews and saved recipes: ${result.manifest}")
        println("New presets awaiting listening. Phone and MPC playback of this bank remain unverified.")
    }

    /** Production integration fixture; the caller supplies provenance without requiring Git. */
    internal fun generate(output: File, source: Provenance): Result {
        require(Names.isMpcSafe(KIT_NAME))
        val entries = OspreyCuratedPresets.entries
        require(entries.size == 16 && entries.map { it.id }.toSet().size == 16)
        require(entries.all { Regex("[a-z0-9_]+").matches(it.id) })
        output.mkdirs()
        val bundle = File(output, KIT_NAME)
        require(!bundle.exists() || bundle.deleteRecursively())
        bundle.mkdirs()
        val previews = File(output, "previews")
        require(!previews.exists() || previews.deleteRecursively())
        previews.mkdirs()
        val staging = File(bundle, "snipsnap")
        val original = requireNotNull(StarterKits.byId("osprey-curated")).render(KIT_NAME, staging)
        val loaded = KitStore.load(staging)
        require(loaded == original) { "KitStore changed the original curated kit or recipes." }
        require(loaded.pads.map { it.slot } == (1..16).toList())
        val preflight = Preflight.check(loaded, staging)
        require(!preflight.blocked()) { "Curated starter preflight failed: $preflight" }

        // Keep the raw engine output. Listening gains affect only the separate previews.
        val rawSnips = loaded.pads.zip(entries).map { (pad, entry) ->
            val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
            val patch = recipe.patch as? OspreyPatch ?: error("${entry.id} is not OSPREY.")
            require(patch == entry.patch && patch.modelVersion == 3)
            require(recipe.fx == null && recipe.board == null && !recipe.alias && recipe.treatment == null &&
                recipe.amount == null && recipe.terminalFadeMs == null) { "${entry.id} acquired a post-render treatment." }
            require(patch.macros.getValue("SPACE") in 0f.. .45f)
            require(pad.drumClass == DrumClass.TONAL && pad.oneShot && pad.chain == null && pad.velocityLayers.isEmpty() &&
                pad.tuneCoarse == 0 && pad.tuneFine == 0 && pad.pan == .5f && pad.level == .707946f &&
                pad.attack == null && pad.decay == null && pad.cutoff == null && pad.resonance == null && pad.humanize == null)
            val raw = recipe.render()
            val expectedFrames = (patch.durationSeconds * RATE).roundToInt()
            require(raw.sampleRate == RATE && raw.channels == 2 && raw.frameCount == expectedFrames)
            require(raw.samples.all { it.isFinite() } && raw.peak() < 1f && raw.peak() > 0f)
            val file = File(staging, pad.sampleFile)
            require(file.readBytes().contentEquals(encode(raw, WavWriter.BitDepth.PCM_24))) {
                "${entry.id} does not regenerate the exact production PCM24 bytes."
            }
            verifyPcm(file, raw, 24)
            raw
        }
        val target = commonTarget(rawSnips)
        val rows = loaded.pads.zip(entries).mapIndexed { index, (pad, entry) ->
            val raw = rawSnips[index]
            val patch = entry.patch
            val recipe = requireNotNull(pad.recipe)
            val patchFile = File(bundle, "patches/${entry.id}.json").apply {
                parentFile.mkdirs(); writeText(Json.write(patch.toJsonValue()) + "\n")
            }
            val recipeFile = File(bundle, "recipes/${entry.id}.json").apply {
                parentFile.mkdirs(); writeText(Json.write(recipe) + "\n")
            }
            require(OspreyPatch.fromJsonText(patchFile.readText()) == patch)
            require(PadRecipe.fromJsonText(recipeFile.readText()).toJsonValue() == recipe)
            val gain = target / Loudness.of(raw)
            val matched = raw.copy(samples = FloatArray(raw.samples.size) { raw.samples[it] * gain })
            require(matched.peak() <= PEAK_CEILING + 1e-6f && matched.samples.all { it.isFinite() })
            require(abs(Loudness.of(matched) - target) < 2e-6f)
            val preview = File(previews, "${entry.id}.wav")
            WavWriter.write(preview, matched, WavWriter.BitDepth.PCM_16)
            verifyPcm(preview, matched, 16)
            val rawFile = File(staging, pad.sampleFile)
            linkedMapOf<String, Any?>(
                "id" to entry.id, "slot" to pad.slot, "label" to PadNoteMap.labelForPad(pad.slot),
                "role" to entry.role, "title" to patch.name, "description" to entry.description,
                "modelVersion" to patch.modelVersion, "midi" to patch.midi, "velocity" to patch.velocity,
                "seed" to patch.seed, "duration" to patch.durationSeconds, "durationSeconds" to patch.durationSeconds,
                "macros" to patch.macros, "mpcTriggerMidi" to PadNoteMap.noteForPad(pad.slot),
                "drumClass" to pad.drumClass.name, "oneShot" to true, "loop" to false,
                "externalFx" to false, "rawGain" to 1, "rawFile" to relative(output, rawFile), "rawSha256" to sha(rawFile),
                "patchFile" to relative(output, patchFile), "patchSha256" to sha(patchFile),
                "recipeFile" to relative(output, recipeFile), "recipeSha256" to sha(recipeFile),
                "recipeRegenerationByteExact" to true,
                "sampleRate" to raw.sampleRate, "channels" to raw.channels, "frames" to raw.frameCount,
                "rawBitDepth" to 24, "rawPeak" to raw.peak(), "rawRms" to rms(raw), "rawLoudest200msRms" to Loudness.of(raw),
                "previewFile" to relative(output, preview), "previewSha256" to sha(preview), "previewBitDepth" to 16,
                "previewGain" to gain, "previewPeak" to matched.peak(), "previewRms" to rms(matched),
                "previewLoudest200msRms" to Loudness.of(matched), "previewTargetRms" to target,
                "waveform" to waveform(matched),
            )
        }

        val native = Exporters.export(ExportFormat.MPC3_TRACK, loaded, staging, File(bundle, "mpc"), dualGeneration = true)
        val nativeReport = ExportReadBack.verify(loaded, native)
        val shared = requireNotNull(native.companion)
        val twin = File(shared, "$KIT_NAME.xpm")
        require(twin.isFile())
        val twinReport = ExportReadBack.verify(loaded, ExportOutcome(ExportFormat.PROGRAM_FOLDER, twin, null, native.findings))
        require(!nativeReport.blocked() && !twinReport.blocked()) { "Export read-back failed: $nativeReport $twinReport" }
        verifyPlaybackWindows(loaded, staging, native.primary, twin)
        for ((pad, row) in loaded.pads.zip(rows)) {
            val copy = File(shared, "${Names.sanitizeStem(pad.sampleStem)}.wav")
            require(copy.readBytes().contentEquals(File(staging, pad.sampleFile).readBytes())) {
                "MPC export altered ${pad.sampleFile}."
            }
            row["rawFile"] = relative(output, copy)
            row["mpcFile"] = relative(output, copy)
            row["mpcSha256"] = sha(copy)
        }
        val sharedKit = loaded.copy(pads = loaded.pads.map {
            it.copy(sampleFile = "${Names.sanitizeStem(it.sampleStem)}.wav")
        })
        require(sharedKit.copy(pads = sharedKit.pads.zip(loaded.pads).map { (mapped, originalPad) ->
            mapped.copy(sampleFile = originalPad.sampleFile)
        }) == loaded) { "Shared folder remap changed more than sample filenames." }
        val kitFile = KitStore.save(sharedKit, shared)
        val sharedLoaded = KitStore.load(shared)
        require(sharedLoaded == sharedKit)
        val sharedPreflight = Preflight.check(sharedLoaded, shared)
        require(!sharedPreflight.blocked())
        for ((pad, row) in sharedLoaded.pads.zip(rows)) {
            val regenerated = PadRecipe.fromJsonValue(requireNotNull(pad.recipe)).render()
            val file = File(shared, pad.sampleFile)
            require(file.readBytes().contentEquals(encode(regenerated, WavWriter.BitDepth.PCM_24)))
            require(sha(file) == row.getValue("rawSha256"))
        }
        require(staging.deleteRecursively()) { "Cannot remove redundant raw staging files." }
        require(bundle.walkTopDown().count { it.isFile && it.extension.equals("wav", true) } == 16)

        val validation = linkedMapOf<String, Any?>(
            "preflight" to findings(preflight), "sharedKitPreflight" to findings(sharedPreflight),
            "nativeXtd" to findings(nativeReport), "compatibilityXpm" to findings(twinReport),
            "starterKitStoreLoadExact" to true, "sharedKitStoreLoadExact" to true,
            "sharedKitDiffersOnlyBySampleFilenames" to true, "recipeRegenerationByteExactPads" to 16,
            "sharedRecipeRegenerationByteExactPads" to 16, "exportAudioByteExactPads" to 16,
            "nativeAndXpmFullLengthOneShotPads" to 16, "matchedPreviewHeadroomPads" to 16,
            "hardwareVerified" to false, "phoneVerified" to false,
            "readBackLimit" to "Software read-back proves agreement with SnipSnap's readers and emitted playback fields; hardware listening remains open. SKIP findings remain unchecked.",
        )
        writeJson(File(bundle, "validation.json"), validation)
        File(bundle, "PAD-MAP.txt").writeText(padMap(sharedLoaded))
        File(bundle, "README.txt").writeText(readme())
        val files = inventory(bundle)
        val base = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "engine" to "OSPREY", "title" to "Osprey curated musical presets",
            "starterId" to "osprey-curated", "kitName" to KIT_NAME, "renderModel" to 3,
            "sourceCommit" to source.commit, "workingTreeDirty" to false, "sourceFilesSha256" to source.sources,
            "reviewStatus" to "new presets awaiting listening", "acceptedModels" to emptyList<Int>(),
            "audioPolicy" to "Unchanged R3 engine with new authored patches. SPACE belongs to the saved instrument; no external FX, cleanup, normalization, looping or pitch treatment is applied to raw kit WAVs. Listening previews alone share one gain target.",
            "sampleRate" to RATE, "channels" to 2, "bitDepth" to 24, "padCount" to 16, "waveFileCount" to 16,
            "bundleRoot" to KIT_NAME, "snipsnapKit" to relative(output, kitFile),
            "nativeProgram" to relative(output, native.primary), "compatibilityProgram" to relative(output, twin),
            "sharedMpcAudioFolder" to relative(output, shared),
            "padMap" to "$KIT_NAME/PAD-MAP.txt", "instructions" to "$KIT_NAME/README.txt",
            "validationReport" to "$KIT_NAME/validation.json", "validation" to validation,
            "listeningLevel" to linkedMapOf("measure" to "Loudness.of: loudest 200 ms stereo-fold RMS with gentle low cut, 50 ms hop",
                "requestedTargetRms" to REQUESTED_RMS, "commonTargetRms" to target, "peakCeiling" to PEAK_CEILING,
                "selectionRule" to "min(0.12, min over all clips of 0.8 * raw loudest-200-ms RMS / raw channel peak)",
                "scope" to "Full-duration previews only; raw PCM24 kit bytes remain unchanged."),
            "clips" to rows, "pads" to rows,
            "pathPolicy" to "Catalog paths are relative to the audition output directory; bundle inventory paths are relative to bundleRoot.",
            "fileInventoryPolicy" to "Every bundled file except the self-referential manifest is listed by byte count and SHA-256. Previews are outside the kit ZIP.",
            "files" to files,
        )
        writeJson(File(bundle, "manifest.json"), base)
        val archive = File(output, ARCHIVE_NAME)
        archive(bundle, archive)
        require(archive.length() <= MAX_ARCHIVE_BYTES) { "Archive is ${archive.length()} bytes; budget is $MAX_ARCHIVE_BYTES." }
        val zipped = ZipFile(archive).use { zip ->
            val zipEntries = zip.entries().asSequence().toList()
            require(zipEntries.size == bundle.walkTopDown().count { it.isFile })
            require(zipEntries.count { it.name.endsWith(".wav") } == 16)
            zipEntries.map { entry ->
                require(!entry.isDirectory && entry.name.startsWith("$KIT_NAME/") && ".." !in entry.name.split('/'))
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                require(bytes.contentEquals(resolve(output, entry.name).readBytes())) { "ZIP round-trip changed ${entry.name}." }
                linkedMapOf("path" to entry.name, "bytes" to bytes.size, "sha256" to sha(bytes))
            }
        }
        val receipt = LinkedHashMap(base).apply {
            put("archive", linkedMapOf("file" to ARCHIVE_NAME, "bytes" to archive.length(), "sha256" to sha(archive),
                "entryCount" to zipped.size, "entries" to zipped, "zipRoundTripByteExact" to true))
        }
        val manifest = File(output, "manifest.json")
        writeJson(manifest, receipt)
        return Result(archive, manifest, bundle)
    }

    /** One target that every channel can reach without a per-clip peak guard changing the comparison. */
    internal fun commonTarget(snips: List<Snip>): Float {
        require(snips.isNotEmpty())
        return minOf(REQUESTED_RMS, snips.minOf {
            val level = Loudness.of(it)
            require(level > 0f && it.peak() > 0f && it.samples.all { sample -> sample.isFinite() })
            PEAK_CEILING * level / it.peak()
        })
    }

    private fun verifyPcm(file: File, snip: Snip, bits: Int) {
        val info = WavInfo.read(file)
        require(info.bitsPerSample == bits && info.channels == snip.channels && info.sampleRate == snip.sampleRate &&
            info.frameCount == snip.frameCount.toLong())
        require(WavReader.readSmpl(file) == null) { "${file.name} gained a sampler pitch sheet or loop." }
        val decoded = WavReader.read(file)
        val tolerance = if (bits == 24) 2e-7f else 4e-5f
        require(snip.samples.indices.maxOf { abs(snip.samples[it] - decoded.samples[it]) } <= tolerance)
    }

    /** X-Ray does not surface one-shot/windows. Inspect those exact emitted fields as an additional check. */
    private fun verifyPlaybackWindows(kit: Kit, samples: File, xtd: File, xpm: File) {
        val native = Mpc3Project.read(xtd)
        require(native.isTrack && native.programs().single().getValue("type").int() == 0)
        val nativePads = native.drumPrograms().single().getValue("instruments").arr()
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(xpm)
        val instruments = document.getElementsByTagName("Instrument")
        for (pad in kit.pads) {
            val frames = WavInfo.read(File(samples, pad.sampleFile)).frameCount
            val instrument = nativePads[pad.slot - 1].obj()
            require(instrument.getValue("triggerMode").int() == 0)
            val layer = instrument.getValue("layersv").arr().map { it.obj() }.single { it.getValue("sampleName").str().isNotEmpty() }
            val slice = layer.getValue("sliceInfo").obj()
            require(!layer.getValue("loop").bool() && layer.getValue("coarseTune").int() == 0 && layer.getValue("fineTune").int() == 0)
            require(slice.getValue("Start").long() == 0L && slice.getValue("End").long() == frames && slice.getValue("LoopMode").int() == 0)
            val xmlPad = (0 until instruments.length).map { instruments.item(it) as Element }
                .single { it.getAttribute("number").toInt() == pad.slot - 1 }
            require(xmlPad.text("OneShot") == "True")
            val layers = xmlPad.getElementsByTagName("Layer")
            val xmlLayer = (0 until layers.length).map { layers.item(it) as Element }.single { it.text("SampleName").isNotEmpty() }
            require(xmlLayer.text("Loop") == "False" && xmlLayer.text("SliceLoop") == "0")
            require(xmlLayer.text("SliceStart") == "0" && xmlLayer.text("SliceEnd").toLong() == frames)
            require(xmlLayer.text("TuneCoarse") == "0" && xmlLayer.text("TuneFine") == "0")
        }
    }

    private fun Element.text(name: String): String = getElementsByTagName(name).item(0).textContent.trim()
    private fun rms(snip: Snip) = sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size)
    private fun waveform(snip: Snip): List<Float> = (0 until 96).map { bucket ->
        val start = bucket * snip.frameCount / 96
        val end = (bucket + 1) * snip.frameCount / 96
        (start * snip.channels until end * snip.channels).maxOfOrNull { abs(snip.samples[it]) } ?: 0f
    }

    private fun padMap(kit: Kit) = buildString {
        append("OSPREY CURATED — SIXTEEN MUSICAL STARTING POINTS\n\n")
        append("New presets awaiting listening. All are finite one-shots; HOLD is excitation, not loop playback.\n")
        append("Raw 24-bit PCM, 44.1 kHz stereo. SPACE is authored inside the source instrument.\n")
        append("Source MIDI is baked into each sample; trigger MIDI selects the pad without transposing it.\n\n")
        append("PAD   TRIGGER  SOURCE  SECONDS  ROLE          RECIPE NAME\n")
        for ((pad, entry) in kit.pads.zip(OspreyCuratedPresets.entries)) {
            val patch = entry.patch
            append(String.format(Locale.ROOT, "%s   %3d      %3d     %5.2f    %-13s %s\n", PadNoteMap.labelForPad(pad.slot),
                PadNoteMap.noteForPad(pad.slot), patch.midi, patch.durationSeconds, entry.role, patch.name))
        }
        append("\nPhysical MPC grid, top to bottom: A13 A14 A15 A16 / A09 A10 A11 A12 / A05 A06 A07 A08 / A01 A02 A03 A04.\n")
        append("Rows from bottom: foundations / short gestures / moving textures / long swells.\n")
    }

    private fun readme() = """
        OSPREY CURATED — NEW PRESETS AWAITING LISTENING

        Sixteen authored musical starting points on the unchanged model-3 engine:
        four warm foundations, four short gestures, four moving textures and four
        long swells. Approval of the earlier R3 kit does not approve this new bank.
        The new presets' phone listening, recipe replay and MPC loading remain open.

        AUDIO AND RECIPES
        Every kit sample is the production starter's raw 24-bit PCM, 44.1 kHz stereo
        output. Pitch, velocity, seed, duration and all macros travel in each saved
        patch and recipe. Authored SPACE is part of OSPREY itself. No external FX,
        cleanup, normalization, loop metadata or pad transposition is added.
        All samples are finite one-shots. HOLD extends excitation, never looping.
        Pad mixer levels retain 0.707946. Listening previews live outside this ZIP
        and use one achievable loudness target; their gains do not alter these WAVs.

        SNIPSNAP
        Keep mpc/${KIT_NAME}_[TrackData]/kit.json with its sixteen WAVs as an
        editable KitStore kit. The original saved recipes remain on every pad;
        only sample filenames are mapped to the shared MPC export filenames.
        In a build containing the curated presets choose NEW KIT > STARTERS >
        OSPREY CURATED. Android XPM ZIP import loads samples without saved recipes.
        patches/ and recipes/ contain separate JSON copies for inspection/replay.
        A phone binary is not part of this download.

        MPC 3
        Copy the whole mpc/ folder. Keep $KIT_NAME.xtd beside
        ${KIT_NAME}_[TrackData]/ and load the .xtd as a native drum track.

        MPC 2
        Open mpc/${KIT_NAME}_[TrackData]/$KIT_NAME.xpm. The native track,
        compatibility program and editable SnipSnap kit all share one WAV set.

        VALIDATION AND LISTENING
        PAD-MAP.txt gives trigger/source notes, roles and durations. manifest.json
        records source provenance and every recipe/audio hash. validation.json
        records preflight, recipe replay and both format read-backs. One-shot,
        non-looped full-length windows are also inspected in both emitted files.
        The ZIP is extracted and compared byte for byte. These software checks
        establish agreement with our own readers, not hardware acceptance.
        SKIP findings remain unchecked. Audition the new bank before adding FX.
    """.trimIndent() + "\n"

    private fun inventory(root: File) = root.walkTopDown().filter { it.isFile }.sortedBy { relative(root, it) }.map {
        linkedMapOf("path" to relative(root, it), "bytes" to it.length(), "sha256" to sha(it))
    }.toList()
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
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
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
