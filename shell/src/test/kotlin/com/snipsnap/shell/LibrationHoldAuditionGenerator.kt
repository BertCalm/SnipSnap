package com.snipsnap.shell

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.synth.LibrationKeys
import com.snipsnap.synth.LibrationPatch
import com.snipsnap.synth.LibrationPresets
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Three existing finite recipes in the independently versioned held transport.
 * Every instrument performance reloads the exported PCM24 files. Key-up uses
 * InstrumentEngine's real amplitude release, with one common monitor gain.
 * No historical audition folder or finite preset is edited by this generator.
 */
object LibrationHoldAuditionGenerator {
    private const val RATE = 44_100
    private const val MONITOR_GAIN = .75f
    private const val RELEASE = .6f
    private const val MAX_ASSET_BYTES = 25L * 1024 * 1024
    private data class Event(val frame: Int, val midi: Int, val velocity: Float?)

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "Supply the output directory" }
        val repository = File(System.getProperty("user.dir")).canonicalFile
        val output = File(args.single()).canonicalFile
        for (name in listOf("libration-audition", "libration-audition-r2", "libration-musical")) {
            val frozen = File(repository, "testkit/$name").canonicalFile.toPath()
            require(!output.toPath().startsWith(frozen) && !frozen.startsWith(output.toPath())) {
                "Keep HOLD separate from the frozen finite auditions"
            }
        }
        require(git(repository, "status", "--porcelain").isEmpty()) { "Commit source before rendering the handoff" }
        val sourceCommit = git(repository, "rev-parse", "HEAD")
        val sources = git(repository, "ls-files").lines().filter { path ->
            path.endsWith(".kt") && ("Libration" in path || path.endsWith("InstrumentEngine.kt")) ||
                path in listOf("shell/build.gradle.kts", "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
                    "synth/src/main/kotlin/com/snipsnap/synth/Modes.kt")
        }.sorted().associateWith { sha(File(repository, it).readBytes()) }
        for (dir in listOf("audio", "downloads", "instruments")) File(output, dir).mkdirs()
        val clips = arrayListOf<Map<String, Any?>>()
        val instruments = arrayListOf<Map<String, Any?>>()
        for (presetName in listOf("ROUND KEYS", "FINE KEYS", "SPLIT PATH")) {
            val finite = LibrationPresets.all().single { it.name == presetName }
            val patch = finite.copy(macros = finite.macros + ("HOLD" to 1f), heldModel = LibrationKeys.MODEL_VERSION)
            val id = patch.voice.name.lowercase(Locale.ROOT)
            val name = "Libration ${patch.voice.name.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }}"
            val spec = LibrationPadMaker.Spec(patch, RELEASE)
            val session = LibrationKeys.Session()
            val measured = LibrationPadMaker.zoneMidis(spec).map { midi ->
                println("$id: MIDI $midi")
                midi to session.render(patch, midi)
            }
            val root = measured.single { it.first == patch.midi }.second
            val makerRoot = LibrationPadMaker.renderZone(spec, patch.midi)
            require(makerRoot.loopStartFrame == root.note.loopStartFrame &&
                makerRoot.snip.samples.contentEquals(root.note.snip.samples)) { "Maker and diagnostic renderer disagree" }
            val bundle = File(output, "instruments/$id").apply { mkdirs() }
            LibrationPadMaker.export(name, spec, measured.map { it.second.note }, bundle, overwrite = true)
            File(bundle, "source.patch.json").writeText(patch.toJsonText() + "\n")
            require(LibrationPatch.fromJsonText(File(bundle, "source.patch.json").readText()) == patch)
            val saved = InstrumentStore.read(InstrumentStore.sidecar(bundle, name))
            require(saved.release == RELEASE)
            val wavs = saved.sampleFiles.associateWith { WavReader.read(File(bundle, it)).samples }
            val loaded = InstrumentEngine.Loaded(saved, RATE, wavs)
            val rows = measured.mapIndexed { index, (midi, rendered) ->
                val zone = saved.zones[index]
                val wav = File(bundle, zone.sample)
                val decoded = WavReader.read(wav)
                val note = rendered.note
                val d = rendered.diagnostics
                require(decoded.channels == 1 && decoded.sampleRate == RATE && decoded.frameCount == note.snip.frameCount)
                require(zone.rootNote == midi && zone.loopStartFrame == note.loopStartFrame)
                require(WavReader.readSmpl(wav) == SmplChunk(midi, SmplChunk.Loop(zone.loopStartFrame, zone.frameCount)))
                val pcmError = decoded.samples.indices.maxOf { abs(decoded.samples[it] - note.snip.samples[it]).toDouble() }
                require(pcmError < 2e-7) { "PCM24 error $pcmError" }
                val pcmSeam = Keys.seamError(decoded.samples, zone.loopStartFrame.toInt())
                require(pcmSeam < Keys.MAX_SEAM_ERROR)
                linkedMapOf<String, Any?>(
                    "midi" to midi, "lowNote" to zone.lowNote, "highNote" to zone.highNote,
                    "audio" to relative(output, wav), "frames" to zone.frameCount,
                    "loopStartFrame" to zone.loopStartFrame, "loopFrames" to zone.frameCount - zone.loopStartFrame,
                    "seamError" to d.seamError, "pcm24SeamError" to pcmSeam, "pcm24MaxError" to pcmError,
                    "cycleError" to d.cycleError, "phaseError" to d.phaseError, "stateError" to d.stateError,
                    "rootFitCents" to d.rootCents, "maxCorrectionHz" to d.maxCorrectionHz,
                    "rawPeak" to d.rawPeak, "totalMilliseconds" to d.totalMilliseconds,
                    "particleCount" to d.particleCount, "sourceJacobiDrift" to d.sourceJacobiDrift,
                    "sha256" to sha(wav.readBytes()),
                )
            }
            val loopFrames = root.note.snip.frameCount - root.note.loopStartFrame.toInt()
            val off = root.note.loopStartFrame.toInt() + (loopFrames * 2.25).roundToInt()
            clips += clip(output, "$id-held", "$presetName · hold through two wraps", id, "held",
                performance(loaded, listOf(Event(0, patch.midi, 1f), Event(off, patch.midi, null)), off + frames(RELEASE + .15)),
                mapOf("noteOffSeconds" to off.toDouble() / RATE, "loopStartSeconds" to root.note.loopStartFrame.toDouble() / RATE,
                    "loopSeconds" to loopFrames.toDouble() / RATE, "wrapsBeforeNoteOff" to 2.25,
                    "releaseSeconds" to RELEASE))
            clips += clip(output, "$id-finite", "$presetName · original finite note", id, "finite", finite.render(),
                mapOf("sourceSeconds" to finite.sourceSeconds, "patch" to finite.toJsonText()))
            val events = listOf(48, 55, 60, 67, 72).flatMapIndexed { index, midi ->
                listOf(Event(frames(index * .8), midi, .85f), Event(frames(index * .8 + .65), midi, null))
            }
            clips += clip(output, "$id-phrase", "$presetName · keyboard phrase", id, "phrase",
                performance(loaded, events, frames(5.0)), mapOf("events" to events.sortedBy { it.frame }.map {
                    mapOf("time" to it.frame.toDouble() / RATE, "midi" to it.midi, "velocity" to it.velocity)
                }))
            for (touch in listOf(.3f, 1f)) {
                val note = session.note(patch.copy(velocity = touch))
                val one = oneZone(note, patch.midi, RELEASE)
                val noteOff = frames(5.0)
                clips += clip(output, "$id-touch-${(touch * 100).roundToInt()}", "$presetName · authored touch $touch", id, "touch",
                    performance(one, listOf(Event(0, patch.midi, 1f), Event(noteOff, patch.midi, null)), noteOff + frames(RELEASE + .15)),
                    mapOf("authoredTouch" to touch, "liveVelocity" to 1.0, "noteOffSeconds" to 5.0,
                        "description" to "Touch re-renders excitation here. The exported keyboard has one authored touch per zone; live key velocity changes gain."))
            }
            if (id == "gather") for (release in listOf(.15f, .6f, 1.5f)) {
                val noteOff = frames(2.5)
                clips += clip(output, "release-${(release * 100).roundToInt()}", "Key-up release · ${release}s", id, "release",
                    performance(oneZone(root.note, patch.midi, release), listOf(Event(0, patch.midi, 1f), Event(noteOff, patch.midi, null)),
                        noteOff + frames(release + .15)), mapOf("noteOffSeconds" to 2.5, "releaseSeconds" to release))
            }
            writeJson(File(bundle, "render-evidence.json"), mapOf("sourceCommit" to sourceCommit,
                "sourceModel" to patch.model, "heldModel" to patch.heldModel, "zones" to rows))
            File(bundle, "README.txt").writeText("""
                $name · LIBRATION held model ${patch.heldModel}, finite source model ${patch.model}
                Source: $sourceCommit. Saved source: $presetName, MIDI ${patch.midi}, touch ${patch.velocity}.

                Extract the complete ZIP. MPC 3: load the .xty. MPC 2: load the .xpm inside the
                adjacent _[TrackData] folder. Keep the sample folder and program together.
                Zones cover MIDI ${saved.zones.first().lowNote}..${saved.zones.last().highNote}.
                Each PCM24 WAV includes its root and sustain loop in a smpl chunk.
                On SnipSnap, use MAKE INSTRUMENT; Shelf Share/Open does not import keyboard ZIPs.

                The initial attack is real. The last recurring period is repeated indefinitely.
                Its trajectory descriptors traverse forward and back; the audio runs forward.
                Live key velocity changes gain. Each zone has one authored excitation touch.
                Key-up fades amplitude over $RELEASE seconds; it does not compute a new physical tail.
                These are software-verified packages, awaiting owner listening and phone/MPC checks.
            """.trimIndent() + "\n")
            val zip = File(output, "downloads/$id-instrument.zip")
            archive(bundle, zip)
            instruments += linkedMapOf("id" to id, "name" to presetName, "voice" to patch.voice.name,
                "patch" to patch.toJsonValue(), "releaseSeconds" to RELEASE, "zones" to rows,
                "download" to relative(output, zip), "downloadBytes" to zip.length(), "downloadSha256" to sha(zip.readBytes()))
            println("$id: ${rows.size} zones, ${zip.length()} ZIP bytes")
        }
        writeJson(File(output, "manifest.json"), linkedMapOf(
            "schemaVersion" to 1, "engine" to "LIBRATION", "heldModel" to LibrationKeys.MODEL_VERSION,
            "generatedAt" to Instant.now().toString(), "sourceCommit" to sourceCommit, "workingTreeDirty" to false,
            "sampleRate" to RATE, "internalOversample" to 4, "monitorGain" to MONITOR_GAIN,
            "player" to "InstrumentEngine JVM reference; held and phrase clips reload exported PCM24 WAVs",
            "instruments" to instruments, "clips" to clips,
            "limitations" to listOf("New held transport awaits owner listening and phone/MPC hardware checks.",
                "Periodic descriptor readout of literal trajectories, not a claim of periodic gravitational motion.",
                "Key-up uses amplitude release, not a newly computed physical tail.",
                "One authored touch per zone. Live key velocity changes playback gain.",
                "Render timings are serial cloud JVM measurements, not phone timings.")))
        writeJson(File(output, "source-provenance.json"), mapOf("sourceCommit" to sourceCommit, "sha256" to sources))
        require(output.walkTopDown().filter { it.isFile }.all { it.length() < MAX_ASSET_BYTES }) { "Asset exceeds hosting file limit" }
        require(git(repository, "rev-parse", "HEAD") == sourceCommit && git(repository, "status", "--porcelain").isEmpty()) {
            "Source changed during generation"
        }
        println("Wrote ${instruments.size} instruments and ${clips.size} clips to $output")
    }

    private fun oneZone(note: KeyNote, midi: Int, release: Float): InstrumentEngine.Loaded = InstrumentEngine.Loaded(
        InstrumentStore.Instrument("LIBRATION AUDITION", release,
            listOf(InstrumentStore.Zone(midi, midi, midi, "note.wav", note.snip.frameCount.toLong(), note.loopStartFrame))),
        RATE, listOf(note.snip.samples))

    private fun performance(loaded: InstrumentEngine.Loaded, events: List<Event>, totalFrames: Int): Snip {
        val player = InstrumentEngine(loaded, RATE)
        val mono = FloatArray(totalFrames)
        val block = FloatArray(1024)
        var position = 0
        fun renderUntil(end: Int) {
            require(end in position..totalFrames)
            while (position < end) {
                val frames = minOf(512, end - position)
                player.render(block, frames)
                for (i in 0 until frames) {
                    require(abs(block[2 * i] - block[2 * i + 1]) < 1e-6)
                    mono[position + i] = block[2 * i]
                }
                position += frames
            }
        }
        for (event in events.sortedBy { it.frame }) {
            renderUntil(event.frame)
            if (event.velocity == null) player.noteOff(event.midi)
            else require(player.noteOn(event.midi, event.velocity))
        }
        renderUntil(totalFrames)
        require(player.activeNotes.isEmpty() && mono.takeLast(RATE / 25).all { it == 0f }) { "A key did not release" }
        return Snip(mono, 1, RATE)
    }

    private fun clip(output: File, id: String, title: String, instrument: String, kind: String,
        snip: Snip, metadata: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        require(snip.samples.all { it.isFinite() } && snip.peak() <= .941f)
        val file = File(output, "audio/$id.wav")
        WavWriter.write(file, snip.copy(samples = FloatArray(snip.samples.size) { snip.samples[it] * MONITOR_GAIN }), WavWriter.BitDepth.PCM_16)
        return linkedMapOf("id" to id, "title" to title, "instrument" to instrument, "kind" to kind,
            "audio" to relative(output, file), "seconds" to snip.durationSeconds, "nativePeak" to snip.peak(),
            "nativeRms" to sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size), "sha256" to sha(file.readBytes())) + metadata
    }

    private fun archive(bundle: File, zip: File) {
        val files = bundle.walkTopDown().filter { it.isFile }.sortedBy { relative(bundle, it) }.toList()
        ZipOutputStream(zip.outputStream().buffered()).use { stream ->
            for (file in files) {
                stream.putNextEntry(ZipEntry("${bundle.name}/${relative(bundle, file)}").apply { time = 0L })
                file.inputStream().use { it.copyTo(stream) }
                stream.closeEntry()
            }
        }
        ZipFile(zip).use { saved ->
            require(saved.size() == files.size)
            for (file in files) {
                val bytes = saved.getInputStream(saved.getEntry("${bundle.name}/${relative(bundle, file)}")).use { it.readBytes() }
                require(bytes.contentEquals(file.readBytes())) { "ZIP round-trip changed ${file.name}" }
            }
        }
    }

    private fun frames(seconds: Double) = (seconds * RATE).roundToInt()
    private fun relative(root: File, file: File) = file.relativeTo(root).invariantSeparatorsPath
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it) }
    private fun writeJson(file: File, value: Any?) = file.writeText(Json.write(json(value)) + "\n")
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associateTo(LinkedHashMap()) { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported JSON value ${value.javaClass.name}")
    }
    private fun git(root: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().use { it.readText() }
        require(process.waitFor() == 0) { "Cannot read source provenance: $result" }
        return result.trim()
    }
}
