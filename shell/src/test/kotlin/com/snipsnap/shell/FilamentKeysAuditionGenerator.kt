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

/** Manual THREAD keys handoff: production notes and package, then playback from exported files. */
object FilamentKeysAuditionGenerator {
    private const val RATE = 44_100
    private const val NAME = "Filament Thread Keys"
    private const val ARCHIVE = "filament-thread-keys.zip"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply only an optional output directory." }
        val repository = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile && File(it, ".git").exists() }
        val output = File(args.firstOrNull() ?: File(repository, "testkit/filament-thread-keys").path).canonicalFile
        require(!output.exists() || output.listFiles()?.isEmpty() == true) { "Use a fresh output directory: $output" }
        check(output.mkdirs() || output.isDirectory)
        val bundle = File(output, NAME).apply { check(mkdirs()) }
        val patch = FilamentPresets.forVoice(FilamentVoice.THREAD).single { it.name == "Glass Thread" }
        require(patch.model == 3 && patch.velocity == 1f)
        val spec = FilamentPadMaker.Spec(patch)
        val roots = FilamentPadMaker.zoneMidis(spec)
        val observedRenderMs = mutableListOf<Double>()
        val notes = roots.map { midi ->
            val start = System.nanoTime()
            FilamentPadMaker.renderZone(spec, midi).also {
                observedRenderMs += (System.nanoTime() - start) / 1e6
                println("THREAD ${Scales.nameOf(midi)}: ${it.snip.frameCount} frames, loop at ${it.loopStartFrame}")
            }
        }
        val program = FilamentPadMaker.export(NAME, spec, notes, bundle)
        val data = File(bundle, Mpc3TrackWriter.trackDataDirName(NAME))
        val sourceFile = File(data, FilamentPadMaker.PATCH_FILE)
        require(FilamentPatch.fromJsonText(sourceFile.readText()) == patch)
        val (sidecar, instrument) = InstrumentStore.list(bundle).single()
        val files = instrument.zones.map { File(sidecar.parentFile, it.sample) }
        val decoded = files.map(WavReader::read)
        val rows = instrument.zones.mapIndexed { index, zone ->
            val file = files[index]
            val read = decoded[index]
            val sheet = requireNotNull(WavReader.readSmpl(file))
            val loop = requireNotNull(sheet.loop)
            require(zone.rootNote == roots[index] && sheet.rootNote == roots[index])
            require(loop.startFrame == notes[index].loopStartFrame && loop.endFrameExclusive == read.frameCount.toLong())
            require(read.channels == 1 && read.sampleRate == RATE && read.frameCount == notes[index].snip.frameCount)
            val pcmError = read.samples.indices.maxOf { abs(read.samples[it] - notes[index].snip.samples[it]) }
            require(pcmError < 2e-7f) { "Export changed source PCM beyond 24-bit precision: $pcmError" }
            linkedMapOf<String, Any?>(
                "rootMidi" to zone.rootNote, "rootName" to Scales.nameOf(zone.rootNote),
                "lowMidi" to zone.lowNote, "highMidi" to zone.highNote,
                "velocityWindow" to listOf(1, 127), "nativeSourceVelocity" to patch.velocity,
                "nativeFile" to relative(bundle, file), "sha256" to sha(file),
                "channels" to read.channels, "sampleRate" to read.sampleRate, "pcmBits" to 24,
                "frames" to read.frameCount, "loopStartFrame" to loop.startFrame,
                "loopEndFrameExclusive" to loop.endFrameExclusive, "attackRetained" to (loop.startFrame > 0),
                "maxPcm24Error" to pcmError, "observedRenderMs" to observedRenderMs[index],
            )
        }
        val nativeTrack = File(bundle, "$NAME.xty")
        require(Acvs.read(nativeTrack).payloadText == Mpc3TrackWriter().keygroupPayloadText(program))
        val compatibility = File(data, "$NAME.xpm")
        val xpm = compatibility.readText()
        for (note in notes) require("<SliceLoopStart>${note.loopStartFrame}</SliceLoopStart>" in xpm)

        // The demo uses decoded package WAVs and their exported markers, never another synth render.
        val loaded = InstrumentEngine.Loaded(instrument, RATE, decoded.map { it.samples })
        val engine = InstrumentEngine(loaded, RATE)
        val releaseFrames = ((instrument.release + .1f) * RATE).toInt()
        val holds = instrument.zones.map { zone -> zone.frameCount.toInt() + (zone.frameCount - zone.loopStartFrame).toInt() + RATE / 8 }
        val demoFrames = holds.sum() + roots.size * (releaseFrames + RATE / 4)
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
        val events = roots.mapIndexed { index, midi ->
            val begin = at
            check(engine.noteOn(midi, 1f))
            play(at, holds[index])
            at += holds[index]
            val noteOff = at
            val wrap = begin + instrument.zones[index].frameCount.toInt()
            val postWrapRms = rms(demo, (wrap + RATE / 20) * 2, (wrap + RATE / 10) * 2)
            check(postWrapRms > 1e-6) { "The demo stopped instead of wrapping at MIDI $midi" }
            engine.noteOff(midi)
            play(at, releaseFrames)
            at += releaseFrames
            check(engine.activeNotes.isEmpty()) { "The demo did not release MIDI $midi" }
            check(demo.sliceArray((at - RATE / 40) * 2 until at * 2).all { abs(it) < 1e-6f })
            at += RATE / 4
            linkedMapOf<String, Any?>(
                "midi" to midi, "noteOnFrame" to begin, "firstWrapFrame" to wrap,
                "noteOffFrame" to noteOff, "releaseFinishedFrame" to at - RATE / 4,
                "heldPastFirstWrapFrames" to noteOff - wrap, "postWrapRms" to postWrapRms,
                "releasedToSilence" to true,
            )
        }
        val demoFile = WavWriter.write(File(bundle, "thread-held-demo.wav"), Snip(demo, 2, RATE))
        val manifest = linkedMapOf<String, Any?>(
            "engine" to "FILAMENT", "voice" to "THREAD", "model" to patch.model,
            "generatedAt" to Instant.now().toString(), "sourceCommit" to git(repository, "rev-parse", "HEAD"),
            "workingTreeDirty" to git(repository, "status", "--porcelain").isNotEmpty(),
            "sourcePatch" to patch.toJsonValue(), "sourcePatchFile" to relative(bundle, sourceFile),
            "sourcePatchSha256" to sha(sourceFile), "programReleaseSeconds" to instrument.release,
            "nativeRootCount" to roots.size, "velocityLayersPerRoot" to 1,
            "zones" to rows, "mpc3File" to relative(bundle, nativeTrack),
            "mpc2File" to relative(bundle, compatibility), "phoneSidecar" to relative(bundle, sidecar),
            "demo" to linkedMapOf(
                "file" to relative(bundle, demoFile), "sha256" to sha(demoFile),
                "channels" to 2, "sampleRate" to RATE, "pcmBits" to 24,
                "player" to "InstrumentEngine reference player using exported InstrumentStore files",
                "noteVelocity" to 1, "hostVoiceGain" to InstrumentEngine.VOICE_LEVEL,
                "events" to events,
                "gainNote" to "Native zone files are unmodified at source velocity 1. The stereo demo includes the reference player's fixed host gain; no loudness matching is applied.",
            ),
            "validation" to linkedMapOf(
                "exportPcmAndSamplerMarkers" to true, "mpc3ContainerReadBack" to true,
                "phoneSidecarReadBack" to true, "referencePlayerHeldPastFileEnd" to true,
                "referencePlayerNoteOffRelease" to true, "physicalPhoneValidated" to false,
                "mpcHardwareValidated" to false, "ownerListeningAcceptance" to false,
            ),
        )
        File(bundle, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        File(bundle, "README.txt").writeText(readme(instrument.release))
        check(bundle.walkTopDown().count { it.isFile && it.extension == "wav" } == 4)
        val archive = File(output, ARCHIVE)
        val entries = bundle.walkTopDown().filter { it.isFile }.sortedBy { relative(bundle, it) }.toList()
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            for (file in entries) {
                zip.putNextEntry(ZipEntry("$NAME/${relative(bundle, file)}").apply { time = 0L })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        ZipFile(archive).use { zip ->
            check(zip.size() == entries.size)
            for (file in entries) {
                val entry = requireNotNull(zip.getEntry("$NAME/${relative(bundle, file)}"))
                check(zip.getInputStream(entry).use { it.readBytes() }.contentEquals(file.readBytes()))
            }
        }
        File(output, "archive.json").writeText(Json.write(json(linkedMapOf(
            "archive" to ARCHIVE, "bytes" to archive.length(), "sha256" to sha(archive),
            "zipEntriesVerified" to entries.size, "manifest" to "$NAME/manifest.json",
        ))) + "\n")
        println("${archive.path}: ${archive.length()} bytes, SHA-256 ${sha(archive)}")
        println("Three native attack/sustain notes and one exported-file playback demo; physical phone/MPC acceptance remains open.")
    }

    private fun readme(release: Float): String = """
        FILAMENT THREAD KEYS

        Glass Thread, saved model 3, native C3/C4/C5, source velocity 1.
        The three mono PCM24 WAVs in the TrackData folder retain the real attack
        and a marked sustain loop. Each root has one full-range velocity layer.
        Program release is $release seconds after note-off. Source settings are
        preserved in filament.patch.json beside the WAVs.

        Load the .xty and its adjacent TrackData folder for MPC 3. The .xpm twin
        inside TrackData is the MPC 2 program. Keep the files together. The phone
        reads the adjacent instrument.json sidecar. Ordinary WAV players play
        through the marked sustain once and stop; they do not hold the key.

        thread-held-demo.wav plays C3, C4 and C5 from the exported files using
        SnipSnap's reference instrument player. Every note passes the end of its
        source file, wraps in sustain and then releases. This stereo demo includes
        the player's fixed voice gain; the three native WAVs do not. No listening
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
