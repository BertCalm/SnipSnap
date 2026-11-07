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
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.mpc3.Mpc3Note
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

/** A finite, dry performance trial; both MPC programs use the same unchanged native renders. */
object NacreKitGenerator {
    const val KIT_NAME = "SnipSnap Nacre Kit"
    const val ARCHIVE_NAME = "SnipSnap-Nacre-Kit.zip"
    private const val FRAMES = 264_600

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "supply only the destination root directory" }
        val archive = generate(File(args.firstOrNull() ?: "../testkit"))
        println("Wrote ${archive.absolutePath}: sixteen NACRE one-shots, MPC 3, MPC 2, listening page and editable recipes")
    }

    fun generate(outputRoot: File): File {
        val root = outputRoot.canonicalFile
        require(root.isDirectory || root.mkdirs()) { "could not create $root" }
        val repo = repositoryRoot()
        val revision = git(repo, "rev-parse", "HEAD")
        val dirty = git(repo, "status", "--porcelain").isNotEmpty()
        // Stage inside the destination filesystem. A failed render or verification leaves
        // the preceding downloadable pack intact instead of publishing half a new kit.
        val work = Files.createTempDirectory(root.toPath(), ".nacre-kit-").toFile()
        val destination = File(work, KIT_NAME)
        try {
            val arranged = SynthKits.nacre()
            require(arranged.size == 16 && arranged.all { it != null }) { "NACRE must supply sixteen pads" }
            val recipes = arranged.mapIndexed { index, entry ->
                val pad = requireNotNull(entry)
                require(pad.drumClass == DrumClass.TONAL && pad.oneShot)
                require(pad.takes.isEmpty() && pad.softVariants.isEmpty())
                require(pad.snip.sampleRate == 44_100 && pad.snip.channels == 1 && pad.snip.frameCount == FRAMES)
                require(pad.snip.samples.all { it.isFinite() } && pad.snip.peak() <= .950001f)
                val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                val patch = recipe.patch as? NacrePatch ?: error("pad ${index + 1} has no NACRE patch")
                require(recipe.fx == null && recipe.board == null && recipe.pedals == null &&
                    recipe.terminalFadeMs == null && recipe.treatment == null && recipe.amount == null && !recipe.alias)
                require(patch.model == 3 && patch.seed == 7L && patch.macros.getValue("HOLD") == 0f)
                val restored = PadRecipe.fromJsonText(recipe.toJsonText())
                require(restored == recipe) { "pad ${index + 1} lost saved performance fields" }
                val replay = restored.render()
                require(replay.sampleRate == pad.snip.sampleRate && replay.channels == pad.snip.channels &&
                    replay.samples.contentEquals(pad.snip.samples)) { "pad ${index + 1} recipe changed its native audio" }
                recipe
            }
            val rendered = File(work, "rendered")
            val kit = KitAssembler.assembleArranged(KIT_NAME, arranged, rendered, key = KeySpec.parse("Cminpent"))
            require(kit.pads.map { it.slot } == (1..16).toList())
            require(kit.pads.all { it.oneShot && it.drumClass == DrumClass.TONAL && it.muteGroup == 0 &&
                it.chain == null && it.velocityLayers.isEmpty() && it.tuneCoarse == 0 && it.tuneFine == 0 })
            val staging = Preflight.check(kit, rendered)
            require(!staging.blocked()) { "NACRE staging failed preflight: $staging" }
            val result = Mpc3Exporter.exportTrack(kit, rendered, destination, mpc2Twin = true)
            require(result.samples.size == 16 && result.program.isFile)
            val data = result.samples.first().parentFile
            require(result.samples.all { it.parentFile == data })
            require(kit.pads.map { it.sampleFile }.toSet() == result.samples.map { it.name }.toSet())
            val restored = KitStore.load(data)
            require(restored.copy(pads = restored.pads.map { it.copy(source = it.source - "rawSha256") }) == kit) {
                "exported kit lost saved recipes or settings"
            }
            val exported = Preflight.check(restored, data)
            require(!exported.blocked()) { "NACRE export failed preflight: $exported" }
            val mpc2 = File(data, "$KIT_NAME.xpm")
            require(mpc2.isFile)
            val readBack = listOf(
                ExportOutcome(ExportFormat.MPC3_TRACK, result.program, data, result.findings),
                ExportOutcome(ExportFormat.PROGRAM_FOLDER, mpc2, null, result.findings),
            ).flatMap { ExportReadBack.verify(kit, it) }
            require(!readBack.blocked()) { "NACRE export read-back failed: $readBack" }
            verifyPlayback(kit, result.program, mpc2)

            val padRecords = kit.pads.zip(recipes).mapIndexed { index, (pad, recipe) ->
                val patch = recipe.patch as NacrePatch
                val sample = File(data, pad.sampleFile)
                val info = WavInfo.read(sample)
                require(info.sampleRate == 44_100 && info.channels == 1 && info.bitsPerSample == 24 && info.frameCount == FRAMES.toLong())
                require(sample.readBytes().contentEquals(wavBytes(requireNotNull(arranged[index]).snip))) {
                    "pad ${pad.slot} export changed native gain, pitch or tail"
                }
                require(sha256(sample) == sha256(File(rendered, pad.sampleFile)))
                require(restored.pads.single { it.slot == pad.slot }.source["rawSha256"] == sha256(sample))
                val recipeFile = File(destination, "recipes/${PadNoteMap.labelForPad(pad.slot)}.json")
                recipeFile.parentFile.mkdirs()
                recipeFile.writeText(recipe.toJsonText() + "\n")
                require(PadRecipe.fromJsonText(recipeFile.readText()) == recipe)
                val decoded = WavReader.read(sample)
                require(decoded.samples.all { it.isFinite() } && decoded.samples.last() == 0f)
                linkedMapOf<String, Any?>(
                    "slot" to pad.slot, "pad" to PadNoteMap.labelForPad(pad.slot),
                    "name" to pad.displayName, "preset" to pad.source.getValue("preset"),
                    "voice" to patch.voice.name, "class" to "TONAL", "playback" to "ONE_SHOT",
                    "oneShot" to true, "loop" to false, "muteGroup" to 0, "effects" to false,
                    "pitchMidi" to patch.midi, "note" to Scales.nameOf(patch.midi),
                    "triggerMidi" to linkedMapOf("mpc2" to PadNoteMap.noteForPad(pad.slot), "mpc3" to Mpc3Note.noteFor(pad.slot)),
                    "velocity" to patch.velocity, "seed" to patch.seed.toString(), "model" to patch.model,
                    "macros" to patch.macros, "frames" to FRAMES, "durationSeconds" to 6,
                    "exportGain" to 1, "padMixerLevel" to pad.level,
                    "sample" to sample.relativeTo(destination).invariantSeparatorsPath, "sampleSha256" to sha256(sample),
                    "recipe" to recipeFile.relativeTo(destination).invariantSeparatorsPath, "recipeSha256" to sha256(recipeFile),
                    "savedRecipe" to recipe.toJsonValue(), "metrics" to metrics(decoded),
                )
            }
            val source = copySources(repo, destination, revision, dirty)
            val manifest = linkedMapOf<String, Any?>(
                "schemaVersion" to 1, "engine" to NacrePatch.ENGINE, "name" to KIT_NAME,
                "padCount" to 16, "model" to 3, "renderSource" to "PadRecipe.render",
                "class" to "TONAL", "playback" to "ONE_SHOT", "effects" to false,
                "scope" to "Sixteen finite six-second renders for an MPC loading and performance trial.",
                "layout" to "A01–A08 Pearl Close in C minor pentatonic at .65; A09–A10 Coral Reply and Kelp Passage at .65; A11–A16 soft/hard pairs of the three presets, all at C3.",
                "audio" to linkedMapOf("sampleRate" to 44_100, "channels" to 1, "format" to "PCM_24", "exportGain" to 1),
                "source" to source,
                "programs" to linkedMapOf(
                    "mpc3" to result.program.relativeTo(destination).invariantSeparatorsPath, "mpc3Sha256" to sha256(result.program),
                    "mpc2" to mpc2.relativeTo(destination).invariantSeparatorsPath, "mpc2Sha256" to sha256(mpc2),
                    "kit" to File(data, KitStore.FILE_NAME).relativeTo(destination).invariantSeparatorsPath,
                    "kitSha256" to sha256(File(data, KitStore.FILE_NAME)),
                ),
                "verification" to linkedMapOf("stagingPreflightPassed" to true, "exportPreflightPassed" to true,
                    "bothFormatsReadBackPassed" to true, "programPlaybackFieldsVerified" to true,
                    "allRecipesReplayExactNativeSamples" to true, "allExportsMatchNativePcm24" to true),
                "metricsBasis" to "Decoded shipped PCM24 WAVs; no additional gain, normalization, trim, fade or effects.",
                "performance" to linkedMapOf(
                    "nativeExcitation" to "Saved .30/.65/1.00 strike strengths are baked into separate pads; hardware velocity changes playback level.",
                    "distinctPads" to "Mute group and mute targets are zero, so different pads are not programmed to choke one another.",
                    "samePadRetrigger" to "MPC 2 programs retain Mono=True and Polyphony=1: a repeat on one pad may restart/cut its preceding tail. MPC 3 writes monophonic=false and polyphony=3; the numeric enum is not claimed as a voice count. Check each device.",
                    "browserPreview" to "Each player previews the exact exported WAV at browser unity; the manifest records the MPC program's separate padMixerLevel.",
                ),
                "acceptance" to "MPC hardware loading, repeated strikes, overlapping pads, dynamics and musical fit remain pending. Factory-bank promotion follows that listen.",
                "pads" to padRecords,
            )
            File(destination, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
            File(destination, "read-back.txt").writeText(readBack.joinToString("\n") { "${it.severity}: ${it.message}" } +
                "\nAdditional program-field checks passed: one-shot triggers, no loops, complete slice ends, no pitch shift and no mute groups or targets.\n")
            File(destination, "metrics.csv").writeText(buildString {
                append("pad,preset,pitchMidi,velocity,frames,peak,rms,dc,lastSecondPeak,lastSecondEnergyFraction\n")
                for (record in padRecords) {
                    val m = record.getValue("metrics") as Map<*, *>
                    append(listOf(record["pad"], record["preset"], record["pitchMidi"], record["velocity"], FRAMES,
                        m["peak"], m["rms"], m["dc"], m["lastSecondPeak"], m["lastSecondEnergyFraction"]).joinToString(","))
                    append('\n')
                }
            })
            File(destination, "README.txt").writeText(readme())
            File(destination, "index.html").writeText(listeningPage(padRecords))
            val checksummed = destination.walkTopDown().filter { it.isFile }
                .sortedBy { it.relativeTo(destination).invariantSeparatorsPath }.toList()
            File(destination, "SHA256SUMS.txt").writeText(checksummed.joinToString("") {
                "${sha256(it)}  ${it.relativeTo(destination).invariantSeparatorsPath}\n"
            })
            val archive = File(work, ARCHIVE_NAME)
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                for (file in destination.walkTopDown().filter { it.isFile }
                    .sortedBy { it.relativeTo(destination).invariantSeparatorsPath }) {
                    zip.putNextEntry(ZipEntry("$KIT_NAME/${file.relativeTo(destination).invariantSeparatorsPath}").also { it.time = 0L })
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            val checksum = File(work, "$ARCHIVE_NAME.sha256").apply { writeText("${sha256(archive)}  $ARCHIVE_NAME\n") }
            publish(listOf(destination, archive, checksum), root, work)
            return File(root, ARCHIVE_NAME)
        } finally {
            work.deleteRecursively()
        }
    }

    /** ReadBack deliberately does not compare one-shot; inspect the actual program fields too. */
    private fun verifyPlayback(kit: Kit, native: File, mpc2: File) {
        val project = Mpc3Project.read(native)
        val instruments = project.drumPrograms().single().getValue("instruments").arr()
        val nativeMap = project.programs().single().getValue("padNoteMap").obj().getValue("noteForPad").obj()
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = factory.newDocumentBuilder().parse(mpc2)
        val xml = document.getElementsByTagName("Instrument")
        val xmlMap = document.getElementsByTagName("PadNote")
        for (pad in kit.pads) {
            require(nativeMap.getValue("value${pad.slot - 1}").int() == Mpc3Note.noteFor(pad.slot))
            val xmlNote = xmlMap.item(pad.slot - 1) as Element
            require(xmlNote.getAttribute("number").toInt() == pad.slot &&
                xmlNote.getElementsByTagName("Note").item(0).textContent.toInt() == PadNoteMap.noteForPad(pad.slot))
            val instrument = instruments[pad.slot - 1].obj()
            require(instrument.getValue("triggerMode").int() == 0 && instrument.getValue("whichMuteGroup").int() == 0)
            require(instrument.getValue("coarseTune").int() == 0 && instrument.getValue("fineTune").int() == 0)
            require(instrument.getValue("muteTargets").obj().values.all { it.int() == 0 })
            val layer = instrument.getValue("layersv").arr().first().obj()
            require(layer.getValue("sampleFile").str() == pad.sampleFile && !layer.getValue("loop").bool())
            val slice = layer.getValue("sliceInfo").obj()
            require(slice.getValue("Start").int() == 0 && slice.getValue("End").int() == FRAMES && slice.getValue("LoopMode").int() == 0)
            val element = xml.item(pad.slot - 1) as Element
            fun text(tag: String) = element.getElementsByTagName(tag).item(0).textContent
            require(text("OneShot") == "True" && text("SliceLoop") == "0" && text("SliceEnd") == FRAMES.toString())
            require(text("SampleName") == pad.sampleStem && text("MuteGroup") == "0")
            require(text("TuneCoarse") == "0" && text("TuneFine") == "0")
            require((1..4).all { text("MuteTarget$it") == "0" })
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
        paths += listOf("KitAssembler.kt", "KitExporter.kt", "KitStore.kt", "Mpc3Exporter.kt", "ExportReadBack.kt", "Preflight.kt")
            .map { "kit/src/main/kotlin/com/snipsnap/kit/$it" }
        paths += listOf("json/src/main/kotlin/com/snipsnap/json/Json.kt", "mpc3/src/main/kotlin/com/snipsnap/mpc3/Mpc3TrackWriter.kt",
            "xpm/src/main/kotlin/com/snipsnap/xpm/XpmWriter.kt", "xpm/src/main/kotlin/com/snipsnap/xpm/PadNoteMap.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/NacreKitGenerator.kt", "synth/build.gradle.kts", "docs/NACRE.md")
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
        SNIPSNAP NACRE KIT — sixteen finite harp pads

        Copy this folder intact to the MPC card.
        MPC 3: load SnipSnap Nacre Kit.xtd and keep its _[TrackData] folder beside it.
        MPC 2: enter SnipSnap Nacre Kit_[TrackData] and load SnipSnap Nacre Kit.xpm.
        Both programs reference the same sixteen mono 44.1 kHz PCM24 WAVs.
        Trigger MIDI mappings differ: MPC 3 uses chromatic notes 36–51 for
        A01–A16; MPC 2 retains its original drum-pad lookup. The manifest records
        both, separately from the harp's baked pitchMidi.
        kit.json retains the editable SnipSnap performances; recipes/ has each
        complete PadRecipe separately. index.html previews the exact shipped WAVs.
        manifest.json, metrics.csv, source/ and SHA256SUMS.txt record the evidence.

        A01–A08: Pearl Close, medium strike .65 — C3, Eb3, F3, G3, Bb3, C4, Eb4, F4.
        A09: Coral Reply C3, medium .65. A10: Kelp Passage C3, medium .65.
        A11/A12: Pearl Close C3, soft .30 / hard 1.00.
        A13/A14: Coral Reply C3, soft .30 / hard 1.00.
        A15/A16: Kelp Passage C3, soft .30 / hard 1.00.

        Model 3, full six-second notes, seed 7 and the starting controls travel
        with every recipe. No rack effects, sample normalization, added fades,
        tuning offsets, loops, velocity layers or choke groups are added.
        The ordinary pad mixer level is recorded as padMixerLevel in manifest.json;
        browser previews play the unchanged WAVs at browser unity.
        Native excitation velocity is baked into the samples. MPC pad velocity
        changes playback level; it does not rerun the string solver.

        Different pads have no programmed choke relationship. MPC 2 retains
        Mono=True and Polyphony=1, so repeated hits on the SAME pad may cut/restart
        its preceding tail. MPC 3 writes monophonic=false and polyphony=3; that
        numeric enum is not claimed as a voice count. Check the device response.
        This is a finite pad program, with no held/keygroup or seamless-loop claim.

        HARDWARE TRIAL PENDING
        Check loading, full endings, overlapping notes on different pads, repeated
        hits on one pad, soft/hard differences and level against your drums.
        The three presets remain outside the global factory bank until that listen.

        Rebuild: ./gradlew :synth:generateNacreKit
    """.trimIndent() + "\n"

    private fun listeningPage(pads: List<Map<String, Any?>>): String {
        fun escape(text: Any?) = text.toString().replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        val cards = pads.joinToString("\n") { pad -> """
            <article><h2>${escape(pad["pad"])} · ${escape(pad["name"])}</h2>
            <p>${escape(pad["preset"])} · ${escape(pad["note"])} · strike ${escape(pad["velocity"])}</p>
            <audio controls preload="none" src="${escape(pad["sample"])}" aria-label="${escape(pad["pad"])} ${escape(pad["name"])}"></audio>
            <p><a href="${escape(pad["sample"])}" download>WAV</a> · <a href="${escape(pad["recipe"])}" download>Saved recipe</a></p></article>
        """.trimIndent() }
        return """
            <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Nacre · MPC starter kit</title>
            <style>:root{color-scheme:dark;font:16px/1.55 system-ui,sans-serif;background:#102526;color:#eef4ed}body{max-width:1050px;margin:0 auto;padding:32px 20px}h1{font-size:clamp(2rem,7vw,3.5rem);line-height:1.1}h2{font-size:1.05rem}a{color:#bce9ca}a:focus-visible,audio:focus-visible,button:focus-visible{outline:3px solid #ffd798;outline-offset:4px}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:16px}article{padding:18px;background:#1c3837;border:1px solid #3e5d55;border-radius:12px}audio{width:100%}.note{padding:18px;border-left:4px solid #d4bf8e;background:#183332}button{font:inherit;padding:8px 14px;background:#d4e7cc;color:#102526;border:0;border-radius:6px}</style>
            <header><p>SNIPSNAP / NACRE</p><h1>Sixteen pads.<br>One finite harp.</h1><p>A playable scale, coral replies and seaweed paths. These are the exact exported six-second WAVs at their native levels.</p>
            <p><a href="../$ARCHIVE_NAME" download>Download the complete MPC kit</a> · <a href="README.txt">Loading instructions</a> · <a href="manifest.json">Manifest</a></p></header>
            <section class="note" aria-label="Hardware trial"><strong>MPC trial pending</strong><p>Load the XTD on MPC 3, or the XPM inside its data folder on MPC 2. Keep the folder together. Try a run across A01–A08, then compare the three medium C3 sounds (A01, A09, A10) and their soft/hard pairs (A11–A16).</p>
            <p>Different pads have no programmed choke relationship. MPC 2 repeats on one pad may restart its previous tail. Compare distinct-pad overlap and same-pad repeats on the device. Native strike strength is baked into these samples; hardware velocity controls playback level.</p><p>Browser players use unity gain; the manifest records the MPC program's separate pad mixer levels.</p></section>
            <p><button id="stop" type="button">Stop all audio</button></p><main class="grid" aria-label="Nacre pad samples">$cards</main>
            <footer><p>Model 3 · mono 44.1 kHz PCM24 · full six-second tails · ordinary saved recipes · no added rack effects.</p><p><a href="metrics.csv">Measurements</a> · <a href="SHA256SUMS.txt">Checksums</a></p></footer>
            <script>document.getElementById('stop').addEventListener('click',()=>document.querySelectorAll('audio').forEach(a=>{a.pause();a.currentTime=0}));</script></html>
        """.trimIndent() + "\n"
    }
}
