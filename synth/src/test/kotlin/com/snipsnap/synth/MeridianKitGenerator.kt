package com.snipsnap.synth

import com.snipsnap.audio.Scales
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.OneNote
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import com.snipsnap.xpm.WavInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Sixteen finite drum pads and separate genuine held keygroups. The drum format's
 * normal export path has no sustained-loop contract, so a held source is captured
 * and faded there. Unchanged held samples use OneNote's existing typed keygroup
 * packaging door, with one source of truth for root, loop and program metadata.
 */
object MeridianKitGenerator {
    private const val HELD_LOW = 36
    private const val HELD_HIGH = 72
    private val nativeVelocities = listOf(.30f, .65f, 1f)
    private val velocityWindows = listOf(0..42, 43..84, 85..127)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Usage: MeridianKitGenerator [output-directory]" }
        println("MERIDIAN kit: ${generate(File(args.firstOrNull() ?: "../testkit")).absolutePath}")
    }

    /** Returns the finite-pad ZIP; each held keyboard has its own adjacent ZIP. */
    fun generate(root: File): File {
        check(root.isDirectory || root.mkdirs()) { "cannot create $root" }
        val work = File(root, ".meridian-work")
        val destination = File(root, MeridianKits.NAME)
        check(!work.exists() || work.deleteRecursively()) { "cannot replace generated staging $work" }
        try {
            val arranged = SynthKits.meridian().map { requireNotNull(it) }
            require(arranged.size == 16 && arranged.all { it.oneShot && it.takes.isEmpty() && it.softVariants.isEmpty() })
            // The first row walks C minor pentatonic. Contrast pads retain their
            // authored A3 root, so do not assign a misleading global kit key.
            val kit = KitAssembler.assembleArranged(MeridianKits.NAME, arranged, work)
            val recipes = kit.pads.map { PadRecipe.fromJsonValue(requireNotNull(it.recipe)) }
            for ((index, pad) in kit.pads.withIndex()) {
                val patch = recipes[index].patch as MeridianPatch
                val midi = actualMidi(patch)
                require(recipes[index].fx == null && patch.model == 2 && pad.oneShot)
                // Drum captures carry their known root, but deliberately no loop.
                WavWriter.write(File(work, pad.sampleFile), arranged[index].snip,
                    smpl = SmplChunk(midi))
            }
            check(!Preflight.check(kit, work).blocked()) { "Meridian staging failed preflight" }
            check(!destination.exists() || destination.deleteRecursively()) { "cannot replace generated kit $destination" }
            val exported = Mpc3Exporter.exportTrack(kit, work, destination, overwrite = true, mpc2Twin = true)
            val data = File(destination, Mpc3TrackWriter.trackDataDirName(kit.name))
            require(exported.samples.size == 16 && exported.samples.all { it.parentFile == data })
            require(exported.samples.map { it.name }.toSet() == kit.pads.map { it.sampleFile }.toSet())
            KitStore.save(kit, data)
            require(KitStore.load(data).pads.map { it.recipe } == kit.pads.map { it.recipe })
            val findings = Preflight.check(kit, data)
            check(!findings.blocked()) { "Meridian export failed preflight: $findings" }

            val pads = kit.pads.mapIndexed { index, pad ->
                val recipe = recipes[index]
                val patch = recipe.patch as MeridianPatch
                val sample = File(data, pad.sampleFile)
                val info = WavInfo.read(sample)
                val expected = ByteArrayOutputStream().also {
                    WavWriter.write(it, arranged[index].snip, smpl = SmplChunk(actualMidi(patch)))
                }.toByteArray()
                require(sample.readBytes().contentEquals(expected)) { "pad ${pad.slot} changed PCM or root metadata" }
                require(WavReader.readSmpl(sample) == SmplChunk(actualMidi(patch)))
                val recipeFile = File(destination, "recipes/A%02d.json".format(Locale.ROOT, pad.slot))
                recipeFile.parentFile.mkdirs()
                recipeFile.writeText(recipe.toJsonText() + "\n")
                require(PadRecipe.fromJsonText(recipeFile.readText()) == recipe)
                linkedMapOf<String, Any?>(
                    "slot" to pad.slot, "pad" to "A%02d".format(Locale.ROOT, pad.slot),
                    "name" to patch.name, "voice" to patch.voice.name,
                    "midi" to actualMidi(patch), "note" to Scales.nameOf(actualMidi(patch)),
                    "velocity" to patch.velocity, "model" to patch.model, "seedContext" to patch.seedContext,
                    "class" to pad.drumClass.name, "oneShot" to true,
                    "playback" to pad.source.getValue("playback"),
                    "sourceHeld" to Meridian.isLoop(patch.macros, patch.model),
                    "terminalFadeMs" to recipe.terminalFadeMs,
                    "loopStartFrame" to 0, "loopEndFrameExclusive" to 0,
                    "frames" to info.frameCount, "durationSeconds" to info.frameCount.toDouble() / info.sampleRate,
                    "sampleFile" to relative(sample, destination), "sampleSha256" to sha256(sample),
                    "recipeFile" to relative(recipeFile, destination), "recipeSha256" to sha256(recipeFile),
                    "recipe" to recipe.toJsonValue(),
                )
            }
            val held = MeridianPresets.all().filter { Meridian.isLoop(it.macros, it.model) }
                .map { exportHeld(it, destination, root) }
            require(held.size == 2) { "the starter release contains two sustained presets" }
            val archive = File(root, MeridianKits.ARCHIVE_NAME)
            val manifest = linkedMapOf<String, Any?>(
                "schemaVersion" to 1, "name" to MeridianKits.NAME, "engine" to MeridianPatch.ENGINE,
                "model" to 2, "padCount" to 16,
                "layout" to "A01-A08 C-minor-pentatonic native-note walk; A09-A16 saved presets, held-source finite captures last.",
                "audio" to linkedMapOf("sampleRate" to 44_100, "channels" to 1, "bitsPerSample" to 24),
                "playback" to "Sixteen finite one-shot drum pads; separate held keyboards carry genuine sustain loops.",
                "effects" to false,
                "sourceRevision" to git("rev-parse", "HEAD"),
                "sourceTreeDirty" to git("status", "--porcelain").isNotEmpty(),
                "files" to linkedMapOf(
                    "mpc3" to relative(exported.program, destination),
                    "mpc2" to relative(File(data, "${kit.name}.xpm"), destination),
                    "kitJson" to relative(File(data, KitStore.FILE_NAME), destination),
                    "archive" to relative(archive, destination),
                ),
                "pads" to pads, "heldInstruments" to held,
                "preflightPassed" to true,
                "hardwareLoadVerified" to false,
            )
            File(destination, "kit-manifest.json").writeText(Json.write(json(manifest)) + "\n")
            File(destination, "README.txt").writeText(readme(held))
            // Keep each downloadable asset bounded: the pad archive does not
            // silently include two additional multi-velocity keyboard packages.
            zip(destination, archive) { it.relativeTo(destination).invariantSeparatorsPath.startsWith("held/").not() }
            return archive
        } finally {
            work.deleteRecursively()
        }
    }

    private fun exportHeld(preset: MeridianPatch, destination: File, archiveRoot: File): Map<String, Any?> {
        val slug = preset.name.replace(' ', '-')
        val folder = File(destination, "held/$slug")
        val instrumentName = "Meridian ${preset.name}"
        val stems = linkedMapOf<String, Snip>()
        val zones = mutableListOf<Map<String, Any?>>()
        val midis = (HELD_LOW..HELD_HIGH step 3).toList()
        val keygroups = midis.mapIndexed { index, midi ->
            val low = if (index == 0) HELD_LOW else midi - 1
            val high = if (index == midis.lastIndex) HELD_HIGH else midi + 1
            val layerRows = mutableListOf<Map<String, Any?>>()
            val layers = nativeVelocities.mapIndexed { layerIndex, velocity ->
                val patch = preset.copy(midi = midi, velocity = velocity)
                val held = Meridian.renderHeld(patch.voice, patch.macros, patch.midi, patch.velocity, patch.seedContext)
                require(held.loopStartFrame > 256 && held.loopEndFrameExclusive == held.snip.frameCount)
                require(held.seamError < Keys.MAX_SEAM_ERROR && held.cycleError < Keys.MAX_SEAM_ERROR)
                val stem = "${slug.replace('-', '_')}_${midi}_V${layerIndex + 1}"
                stems[stem] = held.snip
                val recipe = PadRecipe(patch)
                val recipeFile = File(folder, "recipes/${stem}.json")
                recipeFile.parentFile.mkdirs()
                recipeFile.writeText(recipe.toJsonText() + "\n")
                val data = File(folder, Mpc3TrackWriter.trackDataDirName(instrumentName))
                val sample = File(data, "$stem.wav")
                val window = velocityWindows[layerIndex]
                layerRows += linkedMapOf<String, Any?>(
                    "nativeVelocity" to velocity, "velStart" to window.first, "velEnd" to window.last,
                    "midi" to actualMidi(patch), "sampleFile" to relative(sample, destination),
                    "recipeFile" to relative(recipeFile, destination),
                    "recipeSha256" to sha256(recipeFile),
                    "loopStartFrame" to held.loopStartFrame, "loopEndFrameExclusive" to held.loopEndFrameExclusive,
                    "frames" to held.snip.frameCount, "seamError" to held.seamError,
                    "cycleError" to held.cycleError, "stateError" to held.stateError,
                    "noiseStateError" to held.noiseStateError,
                    "rawPeak" to held.rawPeak, "peakEnergy" to held.peakEnergy,
                    "rootHz" to held.rootHz, "rootGridHz" to held.rootGridHz, "rootCents" to held.rootCents,
                    "effectiveSwingPeriodSeconds" to held.effectiveSwingPeriodSeconds,
                    "nominalSwingPeriodSeconds" to held.nominalSwingPeriodSeconds,
                )
                VelocityLayer(stem, held.snip.frameCount.toLong(), window.first, window.last,
                    loopStartFrame = held.loopStartFrame.toLong())
            }
            zones += linkedMapOf("midi" to midi, "lowNote" to low, "highNote" to high, "layers" to layerRows)
            Keygroup(low, high, midi, layers)
        }
        val program = KeygroupProgram(instrumentName, keygroups,
            volumeRelease = MeridianKits.HELD_RELEASE_SECONDS)
        OneNote.writePackage(instrumentName, program, stems, folder, overwrite = true, spanKeyboard = false)
        val completedZones = zones.map { zone ->
            @Suppress("UNCHECKED_CAST")
            val layers = zone.getValue("layers") as List<Map<String, Any?>>
            val completed = layers.map { layer ->
                val sample = File(destination, layer.getValue("sampleFile") as String)
                val decoded = WavReader.read(sample)
                val sheet = requireNotNull(WavReader.readSmpl(sample))
                val start = (layer.getValue("loopStartFrame") as Int)
                val end = (layer.getValue("loopEndFrameExclusive") as Int)
                require(sheet.rootNote == (layer.getValue("midi") as Int) &&
                    sheet.loop == SmplChunk.Loop(start.toLong(), end.toLong()))
                val pcmSeam = Keys.seamError(decoded.samples, start)
                require(pcmSeam.isFinite() && pcmSeam < Keys.MAX_SEAM_ERROR) { "${sample.name} PCM24 loop did not close: $pcmSeam" }
                layer + mapOf("sampleSha256" to sha256(sample), "pcm24SeamError" to pcmSeam)
            }
            zone + ("layers" to completed)
        }
        val data = File(folder, Mpc3TrackWriter.trackDataDirName(instrumentName))
        val archive = File(archiveRoot, "SnipSnap-Meridian-$slug.zip")
        val manifest = linkedMapOf<String, Any?>(
            "name" to instrumentName, "preset" to preset.name,
            "model" to preset.model, "seedContext" to preset.seedContext,
            "lowNote" to HELD_LOW, "highNote" to HELD_HIGH,
            "noteRange" to "C2-C5 only; notes outside MIDI 36-72 are silent.",
            "sampling" to "Thirteen native roots at minor thirds; intervening notes transpose by at most one semitone.",
            "clockAdaptation" to "Fixed alignment. Each native root retains its own measured pitch-compatible swing period.",
            "velocityLayers" to nativeVelocities,
            "programFile" to relative(File(folder, "$instrumentName.xty"), destination),
            "mpc2ProgramFile" to relative(File(data, "$instrumentName.xpm"), destination),
            "archiveFile" to relative(archive, destination),
            "zones" to completedZones,
            "hardwareLoadVerified" to false,
        )
        // The master index uses paths relative to the pad-bank directory. Each
        // separately downloadable keyboard also has a self-contained manifest
        // whose paths resolve after that keyboard archive is extracted alone.
        val packagePrefix = "held/$slug/"
        val packageZones = completedZones.map { zone ->
            @Suppress("UNCHECKED_CAST")
            val layers = zone.getValue("layers") as List<Map<String, Any?>>
            zone + ("layers" to layers.map { layer ->
                layer + mapOf(
                    "sampleFile" to (layer.getValue("sampleFile") as String).removePrefix(packagePrefix),
                    "recipeFile" to (layer.getValue("recipeFile") as String).removePrefix(packagePrefix),
                )
            })
        }
        val packageManifest = manifest + mapOf(
            "programFile" to relative(File(folder, "$instrumentName.xty"), folder),
            "mpc2ProgramFile" to relative(File(data, "$instrumentName.xpm"), folder),
            "archiveFile" to archive.name, "zones" to packageZones,
        )
        File(folder, "instrument-manifest.json").writeText(Json.write(json(packageManifest)) + "\n")
        File(folder, "README.txt").writeText("""
            $instrumentName

            MPC 3: load $instrumentName.xty with its _[TrackData] folder beside it.
            MPC 2: load $instrumentName.xpm from that same _[TrackData] folder.
            Notes C2-C5 (MIDI 36-72) only; notes outside that range are silent.
            Thirteen natively rendered roots at minor thirds, each with physical
            velocity layers .30, .65 and 1.0 across 0-42, 43-84 and 85-127.
            Between roots the sampler transposes by at most one semitone; that
            also changes swing timing slightly. At each native root, the held
            renderer chooses its documented pitch-compatible swing period.
            This is fixed-alignment HOLD, not a repeating material traversal.
            WAVs keep the actual second-cycle sustain loop and known MIDI root.
            recipes/ and instrument-manifest.json preserve each source and its
            model, noise context, loop bounds and exported-PCM seam measurement.
            Formats are verified in code. Hardware load/listening remains open.
        """.trimIndent() + "\n")
        zip(folder, archive)
        return manifest
    }

    private fun actualMidi(patch: MeridianPatch): Int =
        Meridian.midiFor(patch.voice, patch.macros["TUNE"] ?: .5f, patch.midi)

    private fun relative(file: File, root: File) = file.relativeTo(root).invariantSeparatorsPath

    private fun zip(directory: File, archive: File, accept: (File) -> Boolean = { true }) {
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            directory.walkTopDown().filter { it.isFile && accept(it) }
                .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }.forEach { file ->
                    zip.putNextEntry(ZipEntry(directory.name + "/" + relative(file, directory)).also { it.time = 0L })
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }

    private fun git(vararg args: String): String = runCatching {
        ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start().let { p ->
            val output = p.inputStream.bufferedReader().readText().trim()
            check(p.waitFor() == 0)
            output
        }
    }.getOrDefault("unavailable")

    private fun readme(held: List<Map<String, Any?>>): String = buildString {
        appendLine("SNIPSNAP MERIDIAN: sixteen dry finite pads and two separate sustained keyboards")
        appendLine()
        appendLine("MPC 3 drum kit: load ${MeridianKits.NAME}.xtd with its _[TrackData] folder beside it.")
        appendLine("MPC 2 drum kit: load ${MeridianKits.NAME}.xpm inside that _[TrackData] folder.")
        appendLine("All sixteen drum pads play once. A15-A16 capture held sources with an explicit 20 ms terminal fade.")
        appendLine("Their drum WAVs contain known root notes and no sustain loop. LOOP filing is not loop playback.")
        appendLine("A01-A08: C4 Eb4 F4 G4 Bb4 C5 Eb5 F5, with saved native excitation velocities.")
        appendLine("A09-A16: eight saved presets at their authored A3 reference, held-source captures last.")
        appendLine("All audio is dry mono 44.1 kHz PCM24. kit.json and recipes/ retain complete editable source recipes.")
        appendLine()
        appendLine("The two separate held keyboard ZIPs preserve genuine sampler loops and physical velocity layers:")
        held.forEach { appendLine("${it.getValue("preset")}: ${it.getValue("archiveFile")}") }
        appendLine("Each keyboard covers C2-C5 only with thirteen native roots and three native velocity layers.")
        appendLine("Each archive includes native MPC 3 .xty, MPC 2 .xpm, shared WAVs and complete recipes.")
        appendLine("The finite-pad ZIP deliberately excludes those keyboard folders. Download each keyboard separately.")
        appendLine("Hardware load and owner listening approval of this new bank remain open.")
    }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble())
        is Map<*, *> -> JsonValue.Obj(value.entries.associateTo(LinkedHashMap()) { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("cannot encode ${value::class.simpleName}")
    }
}
