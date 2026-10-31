package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Acvs
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.FilamentPatch
import com.snipsnap.synth.FilamentPresets
import com.snipsnap.synth.FilamentTouchKeys
import com.snipsnap.synth.FilamentVoice
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/** Manual FILAMENT keys handoff: production notes and package, then playback from exported files. */
object FilamentKeysAuditionGenerator {
    private const val RATE = 44_100

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 3) { "Supply an optional output directory, THREAD/PULSE/CURL/ALL and a touch-layers boolean." }
        val repository = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile && File(it, ".git").exists() }
        // Capture source state before creating generated files, including a custom output path.
        val sourceCommit = git(repository, "rev-parse", "HEAD")
        val sourceDirty = git(repository, "status", "--porcelain").isNotEmpty()
        val selection = args.getOrNull(1) ?: "THREAD"
        val touchLayers = args.getOrNull(2)?.toBooleanStrict() ?: false
        val touchTag = if (touchLayers) "-touch" else ""
        val voices = if (selection == "ALL") FilamentVoice.entries.toList() else listOf(FilamentVoice.valueOf(selection))
        val directory = if (selection == "ALL") "held" else selection.lowercase(Locale.ROOT)
        val output = File(args.firstOrNull() ?: File(repository, "testkit/filament-$directory$touchTag-keys").path).canonicalFile
        require(!output.exists() || output.listFiles()?.isEmpty() == true) { "Use a fresh output directory: $output" }
        check(output.mkdirs() || output.isDirectory)
        val bundles = voices.map { voice ->
            val destination = if (voices.size == 1) output else File(output, voice.name.lowercase(Locale.ROOT))
            generate(destination, voice, sourceCommit, sourceDirty, touchLayers)
        }
        if (voices.size > 1) {
            val manifest = File(output, "manifest.json").apply { writeText(Json.write(json(linkedMapOf(
                "engine" to "FILAMENT", "model" to 3, "sourceCommit" to sourceCommit,
                "workingTreeDirty" to sourceDirty, "nativeRootCount" to bundles.sumOf { InstrumentStore.list(it).single().second.zones.size },
                "nativeSampleCount" to bundles.sumOf { InstrumentStore.list(it).single().second.sampleFiles.size },
                "velocityLayersPerRoot" to if (touchLayers) FilamentTouchKeys.TOUCHES.size else 1,
                "instruments" to bundles.map { bundle -> linkedMapOf(
                    "directory" to bundle.name, "manifest" to "${bundle.name}/manifest.json",
                    "manifestSha256" to sha(File(bundle, "manifest.json")),
                ) },
                "physicalPhoneValidated" to false, "mpcHardwareValidated" to false,
                "ownerListeningAcceptance" to false,
            ))) + "\n") }
            val readme = File(output, "README.txt").apply { writeText(
                "FILAMENT HELD INSTRUMENTS\n\nTHREAD, PULSE and CURL each have native C3/C4/C5 roots, ${if (touchLayers) "three velocity layers" else "one velocity layer"}, real onset and marked sustain.\n" +
                "Each program folder includes MPC 3/MPC 2 files, its complete source recipe, native WAVs and an exported-file playback demo.\n" +
                "The demos hold every root/layer through its first wrap, then release. Their reference-player gain is ${InstrumentEngine.VOICE_LEVEL}; native WAVs remove source velocity gain once for host playback.\n" +
                "Use each program folder's README and manifest for loading instructions, frame markers and hashes.\n" +
                "Physical phone/MPC acceptance and owner listening acceptance remain open.\n",
            ) }
            archive(output, "filament-held$touchTag-keys.zip", bundles, listOf(manifest, readme), "manifest.json")
        }
    }

    private fun generate(output: File, voice: FilamentVoice, sourceCommit: String, sourceDirty: Boolean, touchLayers: Boolean): File {
        check(output.mkdirs() || output.isDirectory)
        val lower = voice.name.lowercase(Locale.ROOT)
        val touchTag = if (touchLayers) "-touch" else ""
        val name = "Filament ${lower.replaceFirstChar { it.uppercaseChar() }} ${if (touchLayers) "Touch " else ""}Keys"
        val bundle = File(output, name).apply { check(mkdirs()) }
        val patch = FilamentPresets.forVoice(voice).first()
        require(patch.model == 3 && patch.velocity == 1f)
        val spec = FilamentPadMaker.Spec(patch, touchLayers = touchLayers)
        val roots = FilamentPadMaker.zoneMidis(spec)
        val observedRenderMs = mutableListOf<Double>()
        val notes = roots.map { midi ->
            val start = System.nanoTime()
            FilamentPadMaker.renderLayers(spec, midi).also { layers ->
                observedRenderMs += (System.nanoTime() - start) / 1e6
                layers.forEachIndexed { index, note ->
                    println("$voice ${Scales.nameOf(midi)} layer${index + 1}: ${note.snip.frameCount} frames, loop at ${note.loopStartFrame}")
                }
            }
        }
        val program = FilamentPadMaker.exportLayers(name, spec, notes, bundle)
        val data = File(bundle, Mpc3TrackWriter.trackDataDirName(name))
        val sourceFile = File(data, FilamentPadMaker.PATCH_FILE)
        require(FilamentPatch.fromJsonText(sourceFile.readText()) == patch)
        val captureRecipe = File(data, FilamentPadMaker.TOUCH_FILE)
        if (touchLayers) {
            val recipe = Json.parse(captureRecipe.readText()).obj()
            require(recipe.getValue("touchVersion").int() == FilamentTouchKeys.RENDER_VERSION)
            require(recipe.getValue("excitationVelocities").arr().map { it.num() } == FilamentTouchKeys.TOUCHES.map { (patch.velocity * it).toDouble() })
            for ((rootIndex, row) in recipe.getValue("sourcesByRoot").arr().withIndex()) {
                val saved = row.obj()
                require(saved.getValue("midi").int() == roots[rootIndex])
                saved.getValue("layers").arr().forEachIndexed { index, value ->
                    require(FilamentPatch.fromJsonValue(value) == FilamentTouchKeys.sourcePatch(patch, roots[rootIndex], FilamentTouchKeys.TOUCHES[index]))
                }
            }
        } else require(!captureRecipe.exists())
        val (sidecar, instrument) = InstrumentStore.list(bundle).single()
        val decoded = instrument.sampleFiles.associateWith { WavReader.read(File(sidecar.parentFile, it)) }
        val rows = instrument.zones.flatMapIndexed { rootIndex, zone -> zone.playableLayers.mapIndexed { index, layer ->
            val file = File(sidecar.parentFile, layer.sample)
            val read = decoded.getValue(layer.sample)
            val source = notes[rootIndex][index]
            val strength = if (touchLayers) FilamentTouchKeys.TOUCHES[index] else 1f
            val sourcePatch = if (touchLayers) FilamentTouchKeys.sourcePatch(patch, zone.rootNote, strength)
                else patch.copy(midi = zone.rootNote, macros = patch.macros + ("HOLD" to 1f))
            val sheet = requireNotNull(WavReader.readSmpl(file))
            val loop = requireNotNull(sheet.loop)
            require(zone.rootNote == roots[rootIndex] && sheet.rootNote == roots[rootIndex])
            require(loop.startFrame == source.loopStartFrame && loop.endFrameExclusive == read.frameCount.toLong())
            require(read.channels == 1 && read.sampleRate == RATE && read.frameCount == source.snip.frameCount)
            val pcmError = read.samples.indices.maxOf { abs(read.samples[it] - source.snip.samples[it]) }
            require(pcmError < 2e-7f) { "Export changed source PCM beyond 24-bit precision: $pcmError" }
            linkedMapOf<String, Any?>(
                "rootMidi" to zone.rootNote, "rootName" to Scales.nameOf(zone.rootNote),
                "lowMidi" to zone.lowNote, "highMidi" to zone.highNote,
                "layerIndex" to index + 1, "captureStrength" to strength,
                "velocityWindow" to listOf(layer.velStart, layer.velEnd), "nativeSourceVelocity" to sourcePatch.velocity,
                "heldKeySource" to sourcePatch.toJsonValue(),
                "nativeFile" to relative(bundle, file), "sha256" to sha(file),
                "channels" to read.channels, "sampleRate" to read.sampleRate, "pcmBits" to 24,
                "frames" to read.frameCount, "loopStartFrame" to loop.startFrame,
                "loopEndFrameExclusive" to loop.endFrameExclusive, "attackRetained" to (loop.startFrame > 0),
                "maxPcm24Error" to pcmError, "observedRootRenderMs" to observedRenderMs[rootIndex],
            )
        } }
        val nativeTrack = File(bundle, "$name.xty")
        require(Acvs.read(nativeTrack).payloadText == Mpc3TrackWriter().keygroupPayloadText(program))
        val compatibility = File(data, "$name.xpm")
        val xpm = compatibility.readText()
        for (note in notes.flatten()) require("<SliceLoopStart>${note.loopStartFrame}</SliceLoopStart>" in xpm)

        // The demo uses decoded package WAVs and their exported markers, never another synth render.
        val loaded = InstrumentEngine.Loaded(instrument, RATE, decoded.mapValues { it.value.samples })
        val engine = InstrumentEngine(loaded, RATE)
        val releaseFrames = ((instrument.release + .1f) * RATE).toInt()
        val presses = instrument.zones.flatMap { zone -> zone.playableLayers.map { layer -> zone to layer } }
        val holds = presses.map { (_, layer) -> layer.frameCount.toInt() + (layer.frameCount - layer.loopStartFrame).toInt() + RATE / 8 }
        val demoFrames = holds.sum() + presses.size * (releaseFrames + RATE / 4)
        val demo = FloatArray(demoFrames * 2)
        val block = FloatArray(512)
        fun play(at: Int, frames: Int) {
            var done = 0
            while (done < frames) {
                val count = minOf(256, frames - done)
                engine.render(block, count)
                block.copyInto(demo, (at + done) * 2, 0, count * 2)
                done += count
            }
        }
        var at = 0
        val events = presses.mapIndexed { index, (zone, layer) ->
            val midi = zone.rootNote
            val velocity = if (touchLayers) ((layer.velStart + layer.velEnd) / 2) / 127f else 1f
            val begin = at
            val hit = requireNotNull(KeyHit.resolve(instrument, midi, velocity) { decoded[it]?.frameCount?.toLong() })
            check(hit.sampleFile == layer.sample && hit.loopStartFrame == layer.loopStartFrame)
            check(engine.noteOn(midi, velocity))
            play(at, holds[index])
            at += holds[index]
            val noteOff = at
            val wrap = begin + layer.frameCount.toInt()
            val postWrapRms = rms(demo, (wrap + RATE / 20) * 2, (wrap + RATE / 10) * 2)
            check(postWrapRms > 1e-6) { "The demo stopped instead of wrapping at MIDI $midi" }
            engine.noteOff(midi)
            play(at, releaseFrames)
            at += releaseFrames
            check(engine.activeNotes.isEmpty()) { "The demo did not release MIDI $midi" }
            check(demo.sliceArray((at - RATE / 40) * 2 until at * 2).all { abs(it) < 1e-6f })
            at += RATE / 4
            linkedMapOf<String, Any?>(
                "midi" to midi, "noteVelocity" to velocity, "sampleFile" to layer.sample,
                "velocityWindow" to listOf(layer.velStart, layer.velEnd),
                "noteOnFrame" to begin, "firstWrapFrame" to wrap,
                "noteOffFrame" to noteOff, "releaseFinishedFrame" to at - RATE / 4,
                "heldPastFirstWrapFrames" to noteOff - wrap, "postWrapRms" to postWrapRms,
                "releasedToSilence" to true,
            )
        }
        val demoFile = WavWriter.write(File(bundle, "$lower-held$touchTag-demo.wav"), Snip(demo, 2, RATE))
        val manifest = linkedMapOf<String, Any?>(
            "engine" to "FILAMENT", "voice" to voice.name, "model" to patch.model,
            "generatedAt" to Instant.now().toString(), "sourceCommit" to sourceCommit,
            "workingTreeDirty" to sourceDirty,
            "sourcePatch" to patch.toJsonValue(), "sourcePatchFile" to relative(bundle, sourceFile),
            "sourcePatchSha256" to sha(sourceFile), "programReleaseSeconds" to instrument.release,
            "nativeRootCount" to roots.size, "nativeSampleCount" to rows.size, "velocityLayersPerRoot" to spec.layerCount,
            "captureRecipeFile" to if (touchLayers) relative(bundle, captureRecipe) else null,
            "zones" to rows, "mpc3File" to relative(bundle, nativeTrack),
            "mpc2File" to relative(bundle, compatibility), "phoneSidecar" to relative(bundle, sidecar),
            "demo" to linkedMapOf(
                "file" to relative(bundle, demoFile), "sha256" to sha(demoFile),
                "channels" to 2, "sampleRate" to RATE, "pcmBits" to 24,
                "player" to "InstrumentEngine reference player using exported InstrumentStore files",
                "noteVelocity" to if (touchLayers) null else 1, "hostVoiceGain" to InstrumentEngine.VOICE_LEVEL,
                "events" to events,
                "gainNote" to "Native zone files remove each capture's velocity^.72 once for host playback. The stereo demo includes the reference player's fixed host gain and the event's note velocity. No loudness matching is applied.",
            ),
            "validation" to linkedMapOf(
                "exportPcmAndSamplerMarkers" to true, "mpc3ContainerReadBack" to true,
                "phoneSidecarReadBack" to true, "referencePlayerHeldPastFileEnd" to true,
                "referencePlayerNoteOffRelease" to true, "physicalPhoneValidated" to false,
                "mpcHardwareValidated" to false, "ownerListeningAcceptance" to false,
            ),
        )
        File(bundle, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        File(bundle, "README.txt").writeText(readme(instrument.release, patch.name, name, lower, touchLayers))
        check(bundle.walkTopDown().count { it.isFile && it.extension == "wav" } == roots.size * spec.layerCount + 1)
        archive(output, "filament-$lower$touchTag-keys.zip", listOf(bundle), emptyList(), "$name/manifest.json")
        println("${roots.size * spec.layerCount} native $voice attack/sustain captures and one exported-file playback demo; physical phone/MPC acceptance remains open.")
        return bundle
    }

    private fun archive(output: File, archiveName: String, bundles: List<File>, extras: List<File>, manifestPath: String) {
        val archive = File(output, archiveName)
        val entries = bundles.flatMap { bundle ->
            bundle.walkTopDown().filter { it.isFile }.map { "${bundle.name}/${relative(bundle, it)}" to it }.toList()
        }.plus(extras.map { it.name to it }).sortedBy { it.first }
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            for ((path, file) in entries) {
                zip.putNextEntry(ZipEntry(path).apply { time = 0L })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        ZipFile(archive).use { zip ->
            check(zip.size() == entries.size)
            for ((path, file) in entries) {
                val entry = requireNotNull(zip.getEntry(path))
                check(zip.getInputStream(entry).use { it.readBytes() }.contentEquals(file.readBytes()))
            }
        }
        File(output, "archive.json").writeText(Json.write(json(linkedMapOf(
            "archive" to archiveName, "bytes" to archive.length(), "sha256" to sha(archive),
            "zipEntriesVerified" to entries.size, "manifest" to manifestPath,
        ))) + "\n")
        println("${archive.path}: ${archive.length()} bytes, SHA-256 ${sha(archive)}")
    }

    private fun readme(release: Float, preset: String, name: String, lower: String, touchLayers: Boolean): String = """
        ${name.uppercase(Locale.ROOT)}

        $preset, saved model 3, native C3/C4/C5, hard-source velocity 1.
        The mono PCM24 WAVs in the TrackData folder retain the real attack
        and a marked sustain loop. Each root has ${if (touchLayers) "three independent velocity layers" else "one full-range velocity layer"}.
        ${if (touchLayers) "Soft/mid/hard scale source velocity and INJECT by .35/.65/1. MIDI windows are 1-42, 43-84 and 85-127. Each layer keeps its own source recipe and loop; the hard capture is unchanged." else "The source recipe selects timbre; playback velocity supplies gain."}
        Program release is $release seconds after note-off. Source settings are
        preserved in filament.patch.json beside the WAVs.
        ${if (touchLayers) "filament.instrument.recipe.json records every derived held-key source and the capture version." else ""}

        Load the .xty and its adjacent TrackData folder for MPC 3. The .xpm twin
        inside TrackData is the MPC 2 program. Keep the files together. The phone
        reads the adjacent $name.instrument.json sidecar.
        Ordinary WAV players play
        through the marked sustain once and stop; they do not hold the key.

        $lower-held${if (touchLayers) "-touch" else ""}-demo.wav plays C3, C4 and C5${if (touchLayers) " at all three touch strengths" else ""} from the exported files using
        SnipSnap's reference instrument player. Every note passes the end of its
        source file, wraps in sustain and then releases. This stereo demo includes
        the player's fixed voice gain and each press's note velocity. Native WAVs
        remove the source velocity gain once for the host. No listening
        normalization, external FX or extra synthesis pass is used for the demo.

        The manifest records exact roots, frame markers, source settings, hashes
        and the demo's note-on/note-off positions. Package read-back and reference
        playback are checked. Physical phone playback, MPC hardware import and
        the owner's listening acceptance remain unverified.
    """.trimIndent() + "\n"

    private fun rms(samples: FloatArray, start: Int, end: Int): Double =
        sqrt((start until end).sumOf { samples[it].toDouble() * samples[it] } / (end - start))

    private fun relative(root: File, file: File) = file.relativeTo(root).invariantSeparatorsPath
    private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble())
        is Map<*, *> -> JsonValue.Obj(value.entries.associateTo(linkedMapOf()) { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported manifest value ${value.javaClass.name}")
    }

    private fun git(repository: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repository.path) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "Cannot read source provenance: $output" }
        return output.trim()
    }
}
