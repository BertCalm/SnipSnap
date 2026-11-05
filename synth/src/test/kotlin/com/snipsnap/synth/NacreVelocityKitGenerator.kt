package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportOutcome
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.xpm.PadNoteMap
import com.snipsnap.xpm.WavInfo
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.sqrt

/** Three complete source performances per pad; the accepted flat trial remains separate. */
object NacreVelocityKitGenerator {
    const val KIT_NAME = "SnipSnap Nacre Velocity Kit"
    const val ARCHIVE_NAME = "SnipSnap-Nacre-Velocity-Kit.zip"
    private const val FRAMES = 264_600
    private const val SOURCE_KEY = "nacreVelocityLayers"
    private val STARTS = listOf(1, 42, 85)
    private val ENDS = listOf(41, 84, 127)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "supply only the destination root directory" }
        val archive = generate(File(args.firstOrNull() ?: "../testkit"))
        println("Wrote ${archive.absolutePath}: sixteen NACRE pads, forty-eight velocity samples, both MPC formats and complete layer recipes")
    }

    fun generate(outputRoot: File): File {
        val root = outputRoot.canonicalFile
        require(root.isDirectory || root.mkdirs()) { "could not create $root" }
        val repo = repositoryRoot()
        val revision = git(repo, "rev-parse", "HEAD")
        val dirty = git(repo, "status", "--porcelain").isNotEmpty()
        val work = Files.createTempDirectory(root.toPath(), ".nacre-velocity-kit-").toFile()
        val destination = File(work, KIT_NAME)
        try {
            val arranged = SynthKits.nacreVelocity().map { requireNotNull(it) }
            require(arranged.size == 16)
            val bundles = arranged.mapIndexed { index, pad ->
                require(pad.drumClass == DrumClass.TONAL && pad.oneShot && pad.takes.isEmpty())
                require(pad.softVariants.size == 2 && pad.layerNames.size == 3)
                val bundle = NacreVelocityRecipe.fromJsonText(pad.source.getValue(SOURCE_KEY))
                require(NacreVelocityRecipe.fromJsonText(bundle.toJsonText()) == bundle)
                val replayed = bundle.renderAllLayers()
                val samples = pad.softVariants + pad.snip
                require(replayed.size == 3 && samples.size == 3)
                for (zone in 0..2) {
                    val sample = samples[zone]
                    require(sample.sampleRate == 44_100 && sample.channels == 1 && sample.frameCount == FRAMES)
                    require(sample.samples.all { it.isFinite() } && sample.peak() <= .950001f)
                    require(sample.samples.contentEquals(replayed[zone].samples)) { "pad ${index + 1} layer $zone lost source replay" }
                }
                val main = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                require(main == bundle.layers.last().recipe && bundle.layers.last().samplingGain == 1f)
                bundle
            }
            val rendered = File(work, "rendered")
            val kit = KitAssembler.assembleArranged(KIT_NAME, arranged, rendered, key = KeySpec.parse("Cminpent"))
            require(kit.pads.map { it.slot } == (1..16).toList())
            for (pad in kit.pads) {
                require(pad.oneShot && pad.drumClass == DrumClass.TONAL && pad.muteGroup == 0 && pad.chain == null)
                require(pad.tuneCoarse == 0 && pad.tuneFine == 0 && pad.velocityLayers.size == 3)
                require(pad.velocityLayers.map { it.velStart } == STARTS && pad.velocityLayers.map { it.velEnd } == ENDS)
                require((1..127).all { v -> pad.velocityLayers.count { v in it.velStart..it.velEnd } == 1 })
                require(pad.velocityLayers.none { 0 in it.velStart..it.velEnd })
            }
            require(!Preflight.check(kit, rendered).blocked())
            val result = Mpc3Exporter.exportTrack(kit, rendered, destination, mpc2Twin = true)
            require(result.samples.size == 48 && result.samples.map { it.name }.distinct().size == 48)
            val data = result.samples.first().parentFile
            require(result.samples.all { it.parentFile == data })
            val restored = KitStore.load(data)
            require(restored.copy(pads = restored.pads.map { it.copy(source = it.source - "rawSha256" - "rawVelocitySha256") }) == kit) {
                "exported history differs from the arranged velocity bank"
            }
            require(!Preflight.check(restored, data).blocked())
            val mpc2 = File(data, "$KIT_NAME.xpm")
            require(mpc2.isFile)
            val findings = listOf(
                ExportOutcome(ExportFormat.MPC3_TRACK, result.program, data, result.findings),
                ExportOutcome(ExportFormat.PROGRAM_FOLDER, mpc2, null, result.findings),
            ).flatMap { ExportReadBack.verify(kit, it) }
            require(!findings.blocked()) { "velocity kit read-back failed: $findings" }
            verifyPlayback(kit, result.program, mpc2)

            val pads = kit.pads.mapIndexed { index, pad ->
                val bundle = bundles[index]
                val bundleFile = File(destination, "recipes/${PadNoteMap.labelForPad(pad.slot)}.layers.json")
                bundleFile.parentFile.mkdirs()
                bundleFile.writeText(bundle.toJsonText() + "\n")
                val hard = bundle.layers.last().recipe.patch as NacrePatch
                val expected = arranged[index].softVariants + arranged[index].snip
                val hashes = Json.parse(restored.pads[index].source.getValue("rawVelocitySha256")).obj()
                require(hashes.keys == pad.velocityLayers.map { it.sampleFile }.toSet())
                val layers = pad.velocityLayers.mapIndexed { zone, mapped ->
                    val layer = bundle.layers[zone]
                    val recipe = layer.recipe
                    val patch = recipe.patch as NacrePatch
                    val sample = File(data, mapped.sampleFile)
                    val info = WavInfo.read(sample)
                    require(info.sampleRate == 44_100 && info.channels == 1 && info.bitsPerSample == 24 && info.frameCount == FRAMES.toLong())
                    require(sample.readBytes().contentEquals(wavBytes(expected[zone]))) { "pad ${pad.slot} zone $zone changed its delivered samples" }
                    require(sample.readBytes().contentEquals(File(rendered, mapped.sampleFile).readBytes()))
                    require(hashes.getValue(mapped.sampleFile).str() == sha256(sample))
                    val base = "recipes/${PadNoteMap.labelForPad(pad.slot)}-${zone + 1}"
                    val sourceRecipe = File(destination, "$base.patch.json").apply { writeText(recipe.toJsonText() + "\n") }
                    val wrapper = File(destination, "$base.json").apply {
                        writeText(Json.write(json(linkedMapOf(
                            "format" to "snipsnap-nacre-velocity-layer", "version" to 1,
                            "name" to layer.name, "velocityStart" to mapped.velStart, "velocityEnd" to mapped.velEnd,
                            "samplingGain" to layer.samplingGain, "recipe" to recipe.toJsonValue(),
                        ))) + "\n")
                    }
                    require(PadRecipe.fromJsonText(sourceRecipe.readText()) == recipe)
                    val audio = WavReader.read(sample)
                    require(audio.samples.all { it.isFinite() } && audio.samples.last() == 0f)
                    linkedMapOf<String, Any?>(
                        "name" to layer.name, "velocityStart" to mapped.velStart, "velocityEnd" to mapped.velEnd,
                        "sourceVelocity" to patch.velocity, "sourceTouch" to patch.macros.getValue("TOUCH"),
                        "samplingGain" to layer.samplingGain, "frames" to FRAMES, "durationSeconds" to 6,
                        "sample" to sample.relativeTo(destination).invariantSeparatorsPath, "sampleSha256" to sha256(sample),
                        "recipe" to wrapper.relativeTo(destination).invariantSeparatorsPath, "recipeSha256" to sha256(wrapper),
                        "sourceRecipe" to sourceRecipe.relativeTo(destination).invariantSeparatorsPath,
                        "sourceRecipeSha256" to sha256(sourceRecipe), "savedRecipe" to recipe.toJsonValue(),
                        "metrics" to metrics(audio),
                    )
                }
                linkedMapOf<String, Any?>(
                    "slot" to pad.slot, "pad" to PadNoteMap.labelForPad(pad.slot), "name" to pad.displayName,
                    "preset" to pad.source.getValue("preset"), "pitchMidi" to hard.midi, "note" to Scales.nameOf(hard.midi),
                    "triggerMidi" to linkedMapOf("mpc2" to PadNoteMap.noteForPad(pad.slot), "mpc3" to Mpc3Note.noteFor(pad.slot)),
                    "seed" to hard.seed.toString(), "model" to hard.model, "padMixerLevel" to pad.level,
                    "bundle" to Json.parse(bundle.toJsonText()), "bundleFile" to bundleFile.relativeTo(destination).invariantSeparatorsPath,
                    "bundleSha256" to sha256(bundleFile), "layers" to layers,
                )
            }
            val manifest = linkedMapOf<String, Any?>(
                "schemaVersion" to 1, "engine" to NacrePatch.ENGINE, "name" to KIT_NAME, "model" to 3,
                "padCount" to 16, "sampleCount" to 48, "playback" to "ONE_SHOT", "effects" to false,
                "audio" to linkedMapOf("sampleRate" to 44_100, "channels" to 1, "format" to "PCM_24", "frames" to FRAMES),
                "layout" to "A01–A08 Pearl Close C3–F4 in C minor pentatonic; A09–A12 Coral Reply and A13–A16 Kelp Passage on C3, Eb3, G3 and C4.",
                "source" to copySources(repo, destination, revision, dirty),
                "programs" to linkedMapOf(
                    "mpc3" to result.program.relativeTo(destination).invariantSeparatorsPath, "mpc3Sha256" to sha256(result.program),
                    "mpc2" to mpc2.relativeTo(destination).invariantSeparatorsPath, "mpc2Sha256" to sha256(mpc2),
                    "kit" to File(data, KitStore.FILE_NAME).relativeTo(destination).invariantSeparatorsPath,
                    "kitSha256" to sha256(File(data, KitStore.FILE_NAME)),
                ),
                "verification" to linkedMapOf("stagingPreflightPassed" to true, "exportPreflightPassed" to true,
                    "bothFormatsReadBackPassed" to true, "allProgramVelocityAndPlaybackFieldsVerified" to true,
                    "allLayerRecipesReplayExactly" to true, "allExportsMatchLayerPcm24" to true,
                    "allLayerFingerprintsMatch" to true),
                "gainPolicy" to "Each layer records its ordinary source recipe and fixed samplingGain. The hard layer is the unchanged source at gain 1. No measured peak/RMS matching or device velocity curve emulation is used.",
                "browserPreview" to "Preview selects the actual shipped WAV for MIDI velocity. All players use one common monitor volume; the browser does not emulate MPC velocity-to-volume behavior.",
                "samePadRetrigger" to "MPC 2 retains Mono=True and Polyphony=1, so a repeat may restart its preceding tail. MPC 3 retains its existing non-monophonic configuration; its numeric polyphony enum is not claimed as a voice count.",
                "acceptance" to "Hardware loading, dynamics, repeated strikes, overlap and musical acceptance remain pending a listen on the target MPC.",
                "pads" to pads,
            )
            File(destination, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
            File(destination, "metrics.csv").writeText(buildString {
                append("pad,preset,pitchMidi,layer,velocityStart,velocityEnd,sourceVelocity,sourceTouch,samplingGain,frames,peak,rms,dc,lastSecondPeak\n")
                for (pad in pads) for (raw in pad.getValue("layers") as List<*>) {
                    val layer = raw as Map<*, *>; val m = layer["metrics"] as Map<*, *>
                    append(listOf(pad["pad"], pad["preset"], pad["pitchMidi"], layer["name"], layer["velocityStart"], layer["velocityEnd"],
                        layer["sourceVelocity"], layer["sourceTouch"], layer["samplingGain"], FRAMES, m["peak"], m["rms"], m["dc"], m["lastSecondPeak"]).joinToString(","))
                    append('\n')
                }
            })
            File(destination, "read-back.txt").writeText(findings.joinToString("\n") { "${it.severity}: ${it.message}" } +
                "\nDirect checks passed for all forty-eight velocity layers, one-shot triggers, complete slice ends, no loops, no pitch shift and no choke relationships.\n")
            File(destination, "README.txt").writeText(readme())
            File(destination, "index.html").writeText(listeningPage(pads))
            val files = destination.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(destination).invariantSeparatorsPath }.toList()
            File(destination, "SHA256SUMS.txt").writeText(files.joinToString("") { "${sha256(it)}  ${it.relativeTo(destination).invariantSeparatorsPath}\n" })
            val archive = File(work, ARCHIVE_NAME)
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                for (file in destination.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(destination).invariantSeparatorsPath }) {
                    zip.putNextEntry(ZipEntry("$KIT_NAME/${file.relativeTo(destination).invariantSeparatorsPath}").also { it.time = 0L })
                    file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            val checksum = File(work, "$ARCHIVE_NAME.sha256").apply { writeText("${sha256(archive)}  $ARCHIVE_NAME\n") }
            publish(listOf(destination, archive, checksum), root, work)
            return File(root, ARCHIVE_NAME)
        } finally { work.deleteRecursively() }
    }

    /** Readers deliberately omit some playback fields, so inspect both actual files. */
    private fun verifyPlayback(kit: Kit, native: File, mpc2: File) {
        val project = Mpc3Project.read(native)
        val instruments = project.drumPrograms().single().getValue("instruments").arr()
        val nativeMap = project.programs().single().getValue("padNoteMap").obj().getValue("noteForPad").obj()
        val factory = DocumentBuilderFactory.newInstance().apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        val document = factory.newDocumentBuilder().parse(mpc2)
        val xml = document.getElementsByTagName("Instrument")
        val xmlMap = document.getElementsByTagName("PadNote")
        for (pad in kit.pads) {
            val instrument = instruments[pad.slot - 1].obj()
            require(nativeMap.getValue("value${pad.slot - 1}").int() == Mpc3Note.noteFor(pad.slot))
            val note = xmlMap.item(pad.slot - 1) as Element
            require(note.getElementsByTagName("Note").item(0).textContent.toInt() == PadNoteMap.noteForPad(pad.slot))
            require(instrument.getValue("triggerMode").int() == 0 && instrument.getValue("whichMuteGroup").int() == 0)
            require(instrument.getValue("synthSection").obj().getValue("velocitySensitivity").num() == 1.0)
            require(!instrument.getValue("monophonic").bool() && instrument.getValue("polyphony").int() == 3)
            require(instrument.getValue("coarseTune").int() == 0 && instrument.getValue("fineTune").int() == 0)
            require(instrument.getValue("muteTargets").obj().values.all { it.int() == 0 })
            val nativeFilled = instrument.getValue("layersv").arr().map { it.obj() }.filter { it.getValue("sampleFile").str().isNotBlank() }
            require(nativeFilled.size == 3 && nativeFilled.map { it.getValue("velocityStart").int() } == STARTS.reversed())
            val element = xml.item(pad.slot - 1) as Element
            fun text(tag: String) = element.getElementsByTagName(tag).item(0).textContent
            require(text("OneShot") == "True" && text("MuteGroup") == "0" && text("Mono") == "True" && text("Polyphony") == "1")
            require(text("VelocitySensitivity").toFloat() == 1f)
            require(text("TuneCoarse") == "0" && text("TuneFine") == "0" && (1..4).all { text("MuteTarget$it") == "0" })
            val xmlLayers = element.getElementsByTagName("Layer")
            val xmlFilled = (0 until xmlLayers.length).map { xmlLayers.item(it) as Element }.filter {
                it.getElementsByTagName("SampleName").item(0).textContent.isNotBlank()
            }
            require(xmlFilled.size == 3)
            for ((index, zone) in pad.velocityLayers.withIndex()) {
                val n = nativeFilled.single { it.getValue("velocityStart").int() == zone.velStart }
                require(n.getValue("sampleFile").str() == zone.sampleFile && n.getValue("velocityEnd").int() == zone.velEnd)
                require(!n.getValue("loop").bool() && n.getValue("coarseTune").int() == 0 && n.getValue("fineTune").int() == 0)
                require(n.getValue("volume").obj().getValue("gainCoefficient").num() == 1.0)
                val slice = n.getValue("sliceInfo").obj()
                require(slice.getValue("Start").int() == 0 && slice.getValue("End").int() == FRAMES && slice.getValue("LoopMode").int() == 0)
                val x = xmlFilled[index]
                fun z(tag: String) = x.getElementsByTagName(tag).item(0).textContent
                require(z("SampleName") == zone.sampleStem && z("VelStart").toInt() == zone.velStart && z("VelEnd").toInt() == zone.velEnd)
                require(z("SliceStart") == "0" && z("SliceEnd").toInt() == FRAMES && z("SliceLoop") == "0" && z("Loop") == "False")
                require(z("TuneCoarse") == "0" && z("TuneFine") == "0" && z("Volume").toFloat() == 1f)
            }
            for (velocity in 0..127) {
                val expected = if (velocity == 0) 0 else 1
                require(nativeFilled.count { velocity in it.getValue("velocityStart").int()..it.getValue("velocityEnd").int() } == expected)
                require(xmlFilled.count { velocity in it.getElementsByTagName("VelStart").item(0).textContent.toInt()..it.getElementsByTagName("VelEnd").item(0).textContent.toInt() } == expected)
            }
        }
    }
    private fun metrics(audio: Snip): Map<String, Any?> {
        val x = audio.samples
        val energy = x.sumOf { it.toDouble() * it }
        val tail = x.takeLast(44_100)
        return linkedMapOf("peak" to audio.peak(), "rms" to sqrt(energy / x.size), "dc" to x.sumOf { it.toDouble() } / x.size,
            "lastSecondPeak" to tail.maxOf { abs(it) },
            "lastSecondEnergyFraction" to if (energy == 0.0) 0.0 else tail.sumOf { it.toDouble() * it } / energy,
            "lastSample" to x.last())
    }

    private fun copySources(repo: File, destination: File, revision: String, dirty: Boolean): Map<String, Any?> {
        val paths = File(repo, "synth/src/main/kotlin/com/snipsnap/synth").listFiles()!!.filter { it.name.startsWith("Nacre") && it.extension == "kt" }
            .map { it.relativeTo(repo).invariantSeparatorsPath }.toMutableList()
        paths += listOf("SynthKits.kt", "PadRecipe.kt", "Patches.kt", "Strings.kt", "Dsp.kt", "Tide.kt", "Keys.kt", "Thump.kt")
            .map { "synth/src/main/kotlin/com/snipsnap/synth/$it" }
        paths += listOf("Cleanup.kt", "WavWriter.kt", "WavReader.kt", "Loudness.kt", "Resampler.kt")
            .map { "audio/src/main/kotlin/com/snipsnap/audio/$it" }
        paths += listOf("KitAssembler.kt", "KitExporter.kt", "KitStore.kt", "Mpc3Exporter.kt", "ExportReadBack.kt", "Preflight.kt", "ImportSidecar.kt")
            .map { "kit/src/main/kotlin/com/snipsnap/kit/$it" }
        paths += listOf("json/src/main/kotlin/com/snipsnap/json/Json.kt", "mpc3/src/main/kotlin/com/snipsnap/mpc3/Mpc3TrackWriter.kt",
            "xpm/src/main/kotlin/com/snipsnap/xpm/XpmWriter.kt", "xpm/src/main/kotlin/com/snipsnap/xpm/PadNoteMap.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/NacreVelocityKitGenerator.kt", "synth/src/test/kotlin/com/snipsnap/synth/NacreVelocityPackTest.kt", "synth/src/main/kotlin/com/snipsnap/synth/Presets.kt", "shell/src/main/kotlin/com/snipsnap/shell/StarterKits.kt", "synth/build.gradle.kts", "docs/NACRE.md")
        val files = paths.distinct().sorted().map { path ->
            val original = File(repo, path)
            require(original.isFile) { "missing source input $path" }
            val target = "source/$path"
            File(destination, target).also { it.parentFile.mkdirs(); original.copyTo(it) }
            linkedMapOf("repositoryPath" to path, "path" to target, "sha256" to sha256(original))
        }
        return linkedMapOf("repository" to "https://github.com/BertCalm/SnipSnap", "revision" to revision,
            "dirty" to dirty, "snapshotRoot" to "source/", "files" to files)
    }

    private fun publish(staged: List<File>, root: File, work: File) {
        val backups = File(work, "backup").also { require(it.mkdirs()) }
        val movedOld = mutableListOf<Pair<File, File>>()
        val movedNew = mutableListOf<File>()
        try {
            for (source in staged) {
                val target = File(root, source.name)
                if (target.exists()) {
                    val backup = File(backups, source.name)
                    move(target, backup)
                    movedOld += target to backup
                }
                move(source, target)
                movedNew += target
            }
        } catch (failure: Exception) {
            movedNew.asReversed().forEach { it.deleteRecursively() }
            movedOld.asReversed().forEach { (target, backup) -> move(backup, target) }
            throw failure
        }
    }

    private fun move(from: File, to: File) {
        try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(from.toPath(), to.toPath()) }
    }

    private fun repositoryRoot() = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .firstOrNull { File(it, ".git").exists() } ?: error("run within the SnipSnap repository")

    private fun git(repo: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repo.absolutePath) + arguments).redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().readText().trim()
        require(process.waitFor() == 0) { "git failed" }
        return result
    }

    private fun wavBytes(audio: Snip) = ByteArrayOutputStream().also { WavWriter.write(it, audio) }.toByteArray()
    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        // The patch itself also encodes seed as a decimal string. Refuse a new
        // accidental Long field here rather than silently losing precision.
        is Long -> error("encode 64-bit integers as decimal strings")
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("unsupported JSON value ${value.javaClass.name}")
    }

    private fun readme() = """
        SNIPSNAP NACRE VELOCITY KIT — sixteen pads, three complete performances each

        Copy this folder intact to the MPC card.
        MPC 3: load SnipSnap Nacre Velocity Kit.xtd beside its _[TrackData] folder.
        MPC 2: enter that data folder and load SnipSnap Nacre Velocity Kit.xpm.
        The programs share forty-eight mono 44.1 kHz PCM24 WAVs, six seconds each.
        MIDI 1–41 selects Soft, 42–84 Medium, and 85–127 Hard. Velocity 0 is note-off.
        MPC 3 pad triggers use 36–51; MPC 2 retains its drum-pad lookup. Both trigger
        maps are recorded separately from each sample's native harp pitch.

        A01–A08: Pearl Close — C3, Eb3, F3, G3, Bb3, C4, Eb4, F4.
        A09–A12: Coral Reply — C3, Eb3, G3, C4.
        A13–A16: Kelp Passage — C3, Eb3, G3, C4.

        Each layer stores an ordinary source PadRecipe and an explicit fixed
        samplingGain in its replay wrapper. The bank's source excitation and TOUCH
        follow the recorded effort policy; the hard layer keeps its preferred
        source at gain 1. No per-sample peak/RMS matching is used. The source engine,
        older models and the previous single-strike kit retain their own recipes.
        The complete bank recipe travels inline in kit.json's source metadata and
        separately in recipes/*.layers.json. Per-layer *.patch.json files describe
        native source renders; *.json wrappers add the exact delivery gain.
        The inline full-bank source travels through XPN backups with all layer
        WAVs. Generic XPN backups do not recreate every ordinary kit label/key or
        main PadRecipe automatically; the explicit bank source remains replayable.
        A normal main PadRecipe rebuilds the hard sample. Rebuilding all three
        layers uses the explicit NacreVelocityRecipe, not a generic FX-bank edit.

        Each sample is a finite one-shot, with no added rack effects, pitch shift,
        sample loop, extra tail fade or choke relationship. Samples keep the full
        six-second ending. The program retains the standard pad mixer headroom.
        Hardware velocity can also change playback level. Browser preview selects
        the exact exported layer but uses one common monitor volume; it does not
        emulate a device velocity-to-volume curve or prove hardware behavior.
        Different pads have no programmed choke relationship. MPC 2 keeps Mono=True
        and Polyphony=1, so repeating one pad can restart/cut its preceding tail.
        The native MPC 3 non-monophonic setting is retained; its numeric polyphony
        enum is not claimed as a voice count. No held/keygroup operation is claimed.

        HARDWARE LISTEN PENDING
        Check loading; soft/medium/hard changes; the 41/42 and 84/85 boundaries;
        repeated strikes; overlapping notes; full endings; and level against drums.
        Automated checks establish the files, settings, saved recipes and hashes.
        They do not establish musical acceptance on the target MPC.

        REPLAY
        Rebuild: ./gradlew --no-daemon :synth:generateNacreVelocityKit
        The optional -PnacreVelocityKitOutput=/absolute/output/root chooses a root.
        Keep README.txt, manifest.json, metrics.csv, recipes/, source/ and
        SHA256SUMS.txt with the kit when archiving its provenance.
    """.trimIndent() + "\n"

    private fun listeningPage(pads: List<Map<String, Any?>>): String {
        fun esc(value: Any?) = value.toString().replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        val cards = pads.joinToString("\n") { pad ->
            @Suppress("UNCHECKED_CAST")
            val layers = pad.getValue("layers") as List<Map<String, Any?>>
            val initial = layers[1]
            """
                <article data-slot="${esc(pad["slot"])}"><p class="eyebrow">${esc(pad["pad"])} · ${esc(pad["note"])}</p>
                <h2>${esc(pad["name"])}</h2><p class="layer-status" aria-live="polite">Medium · MIDI 42–84</p>
                <audio controls preload="none" src="${esc(initial["sample"])}" aria-label="${esc(pad["pad"])} ${esc(pad["name"])}"></audio>
                <div class="choices" role="group" aria-label="Choose ${esc(pad["pad"])} strike">${layers.mapIndexed { i, l -> "<button type=\"button\" data-zone=\"$i\" aria-pressed=\"${i == 1}\">${esc(l["name"])}</button>" }.joinToString("")}</div>
                <p class="downloads"><a data-wave href="${esc(initial["sample"])}" download>Selected WAV</a> · <a data-replay href="${esc(initial["recipe"])}" download>Replay recipe</a> · <a href="${esc(pad["bundleFile"])}" download>All three recipes</a></p></article>
            """.trimIndent()
        }
        val data = Json.write(json(pads)).replace("<", "\\u003c")
        return """
            <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#102b2c">
            <title>Nacre · Native velocity layers</title><meta name="description" content="Sixteen finite harp pads with soft, medium and hard source performances. Preview the actual MPC export and download both program formats.">
            <style>:root{color-scheme:light;font:16px/1.55 system-ui,sans-serif;background:#f6f3ea;color:#173b3b}*{box-sizing:border-box}body{max-width:1120px;margin:auto;padding:32px 22px}h1{font:italic clamp(2.5rem,7vw,4.8rem)/1.05 Georgia,serif;letter-spacing:-.04em;margin:22px 0}h2{font:1.2rem/1.3 Georgia,serif;margin:8px 0}.eyebrow{font-size:.72rem;text-transform:uppercase;letter-spacing:.12em;color:#536a64}a{color:#176b65;text-underline-offset:4px}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(255px,1fr));gap:16px}article{padding:20px;background:#fffdf6;border:1px solid #cdd4c8;border-radius:5px}audio{width:100%;margin:10px 0}.note,.transport{padding:18px;background:#e4ebe1;margin:22px 0;border:1px solid #cdd4c8}.note{border-left:4px solid #c4a575}.transport{display:flex;gap:18px;flex-wrap:wrap;align-items:center}.transport label{display:flex;gap:9px;align-items:center;flex-wrap:wrap}button{font:inherit;font-size:.85rem;padding:8px 12px;border:1px solid #a6b9ad;color:#173b3b;background:transparent;border-radius:3px;cursor:pointer}button[aria-pressed=true]{background:#173b3b;color:#f6f3ea}.choices{display:flex;gap:7px;flex-wrap:wrap}.downloads,footer,.layer-status{font-size:.8rem;color:#536a64}a:focus-visible,button:focus-visible,input:focus-visible,audio:focus-visible{outline:3px solid #b68134;outline-offset:3px}footer{border-top:1px solid #cdd4c8;margin-top:30px;padding-top:18px}input[type=range]{max-width:150px}</style>
            <header><p class="eyebrow">SNIPSNAP / NACRE</p><h1>Let the strike<br>change the harp.</h1><p>Sixteen pads. Three complete source performances on each. Compare the actual exported soft, medium and hard samples.</p>
            <p><a href="../$ARCHIVE_NAME" download>Download the complete MPC kit</a> · <a href="README.txt">Loading & replay</a> · <a href="manifest.json">Manifest</a></p></header>
            <section class="note" aria-label="Listening context"><strong>MPC listen pending</strong><p>The browser chooses the shipped layer for a MIDI velocity, at one common monitor volume. It does not emulate hardware gain. Try each layer at the same volume to judge the change in pluck character.</p><p>Full six-second tails, no added rack effects or sample loops. Different pads have no programmed choke relationship; repeating one pad on MPC 2 may restart its tail.</p></section>
            <section class="transport" aria-label="Preview controls"><label for="midi">MIDI velocity <input id="midi" type="range" min="1" max="127" value="64"><output id="midi-value" for="midi">64 · Medium</output></label><label for="volume">Monitor volume <input id="volume" type="range" min="0" max="1" step="0.05" value="0.7"><output id="volume-value" for="volume">70%</output></label><button id="stop" type="button">Stop all</button></section>
            <main class="grid" aria-label="Sixteen Nacre pads">$cards</main>
            <footer><p>Three replayable source performances per pad · mono 44.1 kHz PCM24 · finite one-shots.</p><p><a href="metrics.csv">Measurements</a> · <a href="SHA256SUMS.txt">Checksums</a></p></footer>
            <script>(()=>{'use strict';const pads=$data;const midi=document.getElementById('midi'),value=document.getElementById('midi-value'),volume=document.getElementById('volume'),volumeValue=document.getElementById('volume-value');const cards=[...document.querySelectorAll('article[data-slot]')];function select(card,zone){const pad=pads.find(p=>p.slot===Number(card.dataset.slot)),layer=pad.layers[zone],audio=card.querySelector('audio');audio.pause();audio.src=layer.sample;audio.currentTime=0;card.querySelector('.layer-status').textContent=layer.name+' · MIDI '+layer.velocityStart+'–'+layer.velocityEnd;card.querySelector('[data-wave]').href=layer.sample;card.querySelector('[data-replay]').href=layer.recipe;card.querySelectorAll('[data-zone]').forEach(b=>b.setAttribute('aria-pressed',String(Number(b.dataset.zone)===zone)));}function setVelocity(){const v=Number(midi.value),zone=pads[0].layers.findIndex(l=>v>=l.velocityStart&&v<=l.velocityEnd);value.textContent=v+' · '+pads[0].layers[zone].name;cards.forEach(c=>select(c,zone));}function setVolume(){const v=Number(volume.value);document.querySelectorAll('audio').forEach(a=>a.volume=v);volumeValue.textContent=Math.round(v*100)+'%';}for(const card of cards)for(const button of card.querySelectorAll('[data-zone]'))button.addEventListener('click',()=>select(card,Number(button.dataset.zone)));midi.addEventListener('input',setVelocity);volume.addEventListener('input',setVolume);document.getElementById('stop').addEventListener('click',()=>document.querySelectorAll('audio').forEach(a=>{a.pause();a.currentTime=0;}));setVolume();})();</script></html>
        """.trimIndent() + "\n"
    }
}
