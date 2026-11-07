package com.snipsnap.shell

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.Keys
import com.snipsnap.synth.LibrationKeys
import com.snipsnap.synth.LibrationPresets
import com.snipsnap.synth.LibrationTouchKeys
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

/** Two-touch instruments and performances of their actual exported PCM24 files. */
object LibrationTouchAuditionGenerator {
    private const val RATE = 44_100
    private const val MONITOR_GAIN = .75f
    private const val RELEASE = .6f
    private data class Event(val frame: Int, val midi: Int, val velocity: Float?)

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "Supply a new output directory" }
        val repository = File(System.getProperty("user.dir")).canonicalFile
        val output = File(args.single()).canonicalFile
        require(!output.exists() || output.listFiles().isNullOrEmpty()) { "Keep earlier audition artifacts intact" }
        require(git(repository, "status", "--porcelain").isEmpty()) { "Commit source before rendering" }
        val commit = git(repository, "rev-parse", "HEAD")
        val sources = git(repository, "ls-files").lines().filter {
            "Libration" in it || it in listOf("shell/build.gradle.kts",
                "shell/src/main/kotlin/com/snipsnap/shell/InstrumentEngine.kt",
                "shell/src/main/kotlin/com/snipsnap/shell/KeyHit.kt",
                "kit/src/main/kotlin/com/snipsnap/kit/InstrumentStore.kt",
                "kit/src/main/kotlin/com/snipsnap/kit/OneNote.kt",
                "mpc3/src/main/kotlin/com/snipsnap/mpc3/Mpc3TrackWriter.kt",
                "xpm/src/main/kotlin/com/snipsnap/xpm/KeygroupWriter.kt")
        }.sorted().associateWith { sha(File(repository, it).readBytes()) }
        File(output, "audio").mkdirs()
        File(output, "downloads").mkdirs()
        val clips = arrayListOf<Map<String, Any?>>()
        val instruments = arrayListOf<Map<String, Any?>>()
        for (preset in listOf("ROUND KEYS", "FINE KEYS", "SPLIT PATH")) {
            val finite = LibrationPresets.all().single { it.name == preset }
            val patch = finite.copy(macros = finite.macros + ("HOLD" to 1f), heldModel = 1)
            val id = patch.voiceName.lowercase(Locale.ROOT)
            val name = "Libration ${id.replaceFirstChar { it.titlecase(Locale.ROOT) }} Touch"
            val spec = LibrationPadMaker.Spec(patch, RELEASE, touchLayers = true)
            val session = LibrationKeys.Session()
            val captures = LibrationPadMaker.zoneMidis(spec).map { midi ->
                println("$id: MIDI $midi, soft + hard")
                midi to LibrationTouchKeys.capture(patch, midi, session)
            }
            val bundle = File(output, "instruments/$id").apply { mkdirs() }
            LibrationPadMaker.exportLayers(name, spec, captures.map { it.second.map { layer -> layer.note } }, bundle)
            val restored = LibrationPadMaker.Spec.fromJsonText(LibrationPadMaker.recipeFile(bundle, name).readText())
            require(restored == spec)
            val instrument = InstrumentStore.read(InstrumentStore.sidecar(bundle, name))
            val samples = instrument.sampleFiles.associateWith { WavReader.read(File(bundle, it)).samples }
            val loaded = InstrumentEngine.Loaded(instrument, RATE, samples)
            val rows = captures.flatMapIndexed { index, (midi, layers) ->
                val zone = instrument.zones[index]
                layers.mapIndexed { layerIndex, layer ->
                    val saved = zone.playableLayers[layerIndex]
                    val file = File(bundle, saved.sample)
                    val decoded = WavReader.read(file)
                    require(decoded.channels == 1 && decoded.sampleRate == RATE)
                    require(WavReader.readSmpl(file) == SmplChunk(midi, SmplChunk.Loop(saved.loopStartFrame, saved.frameCount)))
                    require(saved.velStart..saved.velEnd == layer.velocityWindow)
                    require(decoded.samples.size == layer.note.snip.frameCount)
                    require(decoded.samples.indices.all { abs(decoded.samples[it] - layer.note.snip.samples[it]) < 2e-7 })
                    val seam = Keys.seamError(decoded.samples, saved.loopStartFrame.toInt())
                    require(seam < Keys.MAX_SEAM_ERROR)
                    linkedMapOf<String, Any?>("rootMidi" to midi, "strength" to layer.strength,
                        "velocityStart" to saved.velStart, "velocityEnd" to saved.velEnd,
                        "sample" to saved.sample, "sampleGain" to layer.sampleGain,
                        "sourceRecipe" to layer.sourcePatch.toJsonValue(), "sha256" to sha(file.readBytes()),
                        "frames" to saved.frameCount, "loopStartFrame" to saved.loopStartFrame,
                        "pcm24SeamError" to seam, "cycleError" to layer.diagnostics.cycleError,
                        "stateError" to layer.diagnostics.stateError, "phaseError" to layer.diagnostics.phaseError,
                        "rootFitCents" to layer.diagnostics.rootCents, "rawPeak" to layer.diagnostics.rawPeak,
                        "renderMilliseconds" to layer.diagnostics.totalMilliseconds)
                }
            }
            writeJson(File(bundle, "capture-evidence.json"), rows)
            val root = captures.single { it.first == patch.midi }.second
            for ((index, velocity) in listOf(32, 96).withIndex()) {
                val note = root[index].note
                val period = note.snip.frameCount - note.loopStartFrame.toInt()
                val held = note.loopStartFrame.toInt() + period * 2 + RATE / 4
                val events = listOf(Event(0, patch.midi, velocity / 127f), Event(held, patch.midi, null))
                clips += clip(output, "$id-${if (index == 0) "soft" else "hard"}",
                    "${patch.voiceName}: ${if (index == 0) "soft" else "hard"} long hold", id, "held",
                    performance(loaded, events, held + frames(RELEASE.toDouble() + .15)),
                    mapOf("midiVelocity" to velocity, "keyUpSeconds" to held.toDouble() / RATE,
                        "wrapSeconds" to listOf(note.snip.frameCount.toDouble() / RATE,
                            (note.snip.frameCount + period).toDouble() / RATE)))
            }
            val boundaryEvents = listOf(62, 63, 64, 65).flatMapIndexed { index, velocity ->
                listOf(Event(frames(index * 1.5), 60, velocity / 127f), Event(frames(index * 1.5 + .7), 60, null))
            }
            clips += clip(output, "$id-boundary", "${patch.voiceName}: velocities 62 / 63 / 64 / 65", id, "boundary",
                performance(loaded, boundaryEvents, frames(6.0)), mapOf("velocities" to listOf(62, 63, 64, 65)))
            val phraseEvents = listOf(48, 55, 60, 67, 72, 60).flatMapIndexed { index, midi ->
                val velocity = listOf(32, 96, 63, 64, 96, 32)[index]
                listOf(Event(frames(index * .85), midi, velocity / 127f), Event(frames(index * .85 + .65), midi, null))
            }
            clips += clip(output, "$id-phrase", "${patch.voiceName}: alternating touches across C3–C5", id, "phrase",
                performance(loaded, phraseEvents, frames(6.0)))
            val zip = File(output, "downloads/$id-touch.zip")
            archive(bundle, zip)
            val files = bundle.walkTopDown().filter { it.isFile }.sortedBy { relative(bundle, it) }.map { file ->
                mapOf("url" to relative(output, file), "zipPath" to "$id/${relative(bundle, file)}",
                    "bytes" to file.length(), "sha256" to sha(file.readBytes()))
            }.toList()
            instruments += mapOf("id" to id, "name" to name, "preset" to preset, "patch" to patch.toJsonValue(),
                "zones" to captures.size, "samples" to instrument.sampleFiles.size,
                "releaseSeconds" to RELEASE, "rootMidi" to patch.midi,
                "captureRows" to rows, "files" to files, "localArchive" to relative(output, zip),
                "archiveBytes" to zip.length(), "archiveSHA256" to sha(zip.readBytes()))
        }
        writeJson(File(output, "manifest.json"), mapOf("version" to 1, "touchModel" to 1,
            "sourceCommit" to commit, "createdAt" to Instant.now().toString(),
            "monitorGain" to MONITOR_GAIN, "instruments" to instruments, "clips" to clips,
            "gainRule" to "Relative capture velocity^.7 removed once; common .94 peak guard; host supplies live note gain."))
        writeJson(File(output, "source-provenance.json"), mapOf("sourceCommit" to commit, "sha256" to sources))
        println("Generated ${instruments.size} two-touch instruments and ${clips.size} performances at $output")
    }

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
