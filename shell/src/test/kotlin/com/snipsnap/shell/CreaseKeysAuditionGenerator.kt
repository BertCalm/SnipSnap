package com.snipsnap.shell

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Scales
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.Crease
import com.snipsnap.synth.CreaseKeyProbe
import com.snipsnap.synth.CreaseKeys
import com.snipsnap.synth.CreasePatch
import com.snipsnap.synth.CreasePresets
import com.snipsnap.synth.Keys
import com.snipsnap.synth.Patches
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The approved Paper Keys and Ink Ribbon positions played as held instruments.
 * CreaseKeys supplies the continuous onset and converged loop; CreasePadMaker
 * and OneNote supply the same dual-MPC/phone package the app makes. The audition
 * then reloads its PCM24 WAVs and plays real note-on/note-off events through the
 * JVM reference InstrumentEngine. No clip gets an independent loudness gain.
 *
 * This output is separate from the preserved 87-clip model-3 listening set.
 * Run :shell:generateCreaseKeysAudition after committing the tested source.
 */
object CreaseKeysAuditionGenerator {
    private const val RATE = 44_100
    private const val MAX_ASSET_BYTES = 25L * 1024 * 1024
    private const val RELEASE_SECONDS = .6f
    private const val SEAM_LIMIT = 1e-3

    private data class Zone(val midi: Int, val probe: CreaseKeyProbe)
    private data class Event(val frame: Int, val midi: Int, val velocity: Float?)
    private data class Performance(val snip: Snip, val activeAtEnd: List<Int>)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply only the output directory." }
        val repository = repositoryRoot()
        val output = File(args.firstOrNull() ?: "../testkit/crease-keys-audition").canonicalFile
        val protected = listOf("crease-audition", "crease-audition-r2", "crease-audition-r3")
            .map { File(repository, "testkit/$it").canonicalFile }
        require(protected.none { output.toPath().startsWith(it.toPath()) || it.toPath().startsWith(output.toPath()) }) {
            "Keep the held-key audition separate from all preserved Crease auditions."
        }
        require(git(repository, "status", "--porcelain").isEmpty()) {
            "Commit the tested source before generating the held-key handoff."
        }
        val sourceCommit = git(repository, "rev-parse", "HEAD")
        val sources = git(repository, "ls-files").lines().filter { path ->
            path.endsWith(".kt") && (path.contains("/src/main/") || path.endsWith("/CreaseKeysAuditionGenerator.kt")) ||
                path == "shell/build.gradle.kts"
        }.sorted().associateWith { sha(File(repository, it)) }
        output.mkdirs()
        File(output, "audio").mkdirs()
        File(output, "downloads").mkdirs()
        val clips = ArrayList<Map<String, Any?>>()
        val instruments = ArrayList<Map<String, Any?>>()
        val allZones = ArrayList<Map<String, Any?>>()
        for (id in listOf("paper-keys", "ink-ribbon")) {
            val preset = CreasePresets.all().single { it.id == id }
            val patch = preset.toPatch()
            require(patch.model == 3 && patch.midi == 60 && patch.velocity == .8f)
            val spec = CreasePadMaker.Spec(patch, releaseSeconds = RELEASE_SECONDS)
            val midis = CreasePadMaker.zoneMidis(spec)
            val zones = midis.map { midi ->
                println("$id: render MIDI $midi")
                val probe = CreaseKeys.probe(patch, midi)
                checkProbe(id, midi, probe)
                Zone(midi, probe)
            }
            // Verify the app-facing route uses exactly the probe whose evidence is exported.
            val root = zones.single { it.midi == patch.midi }
            val makerRoot = CreasePadMaker.renderZone(spec, patch.midi)
            require(makerRoot.loopStartFrame == root.probe.note.loopStartFrame &&
                makerRoot.snip.samples.contentEquals(root.probe.note.snip.samples)) {
                "$id: the public maker and measured continuous renderer disagree"
            }
            val packageRoot = File(output, "instruments/$id")
            packageRoot.mkdirs()
            val name = "Crease ${preset.name}"
            CreasePadMaker.export(name, spec, zones.map { it.probe.note }, packageRoot, overwrite = true)
            val recipe = File(packageRoot, "source.patch.json")
            recipe.writeText(patch.toJsonText() + "\n")
            require(Patches.fromJsonText(recipe.readText()) == patch) { "$id: native patch JSON changed saved metadata" }
            val sidecar = InstrumentStore.sidecar(packageRoot, name)
            val instrument = InstrumentStore.read(sidecar)
            require(instrument.release == RELEASE_SECONDS && instrument.zones.map { it.rootNote } == midis)
            val samples = instrument.sampleFiles.associateWith { path ->
                val audio = WavReader.read(File(packageRoot, path))
                require(audio.channels == 1 && audio.sampleRate == RATE)
                audio.samples
            }
            val loaded = InstrumentEngine.Loaded(instrument, RATE, samples)
            val zoneRows = zones.mapIndexed { index, zone ->
                val saved = instrument.zones[index]
                val wav = File(packageRoot, saved.sample)
                val decoded = WavReader.read(wav)
                require(decoded.frameCount == zone.probe.note.snip.frameCount &&
                    saved.loopStartFrame == zone.probe.note.loopStartFrame)
                require(WavReader.readSmpl(wav) == SmplChunk(zone.midi,
                    SmplChunk.Loop(saved.loopStartFrame, saved.frameCount))) {
                    "$id MIDI ${zone.midi}: exported WAV lost its root or loop"
                }
                val seam = Keys.seamError(decoded.samples, saved.loopStartFrame.toInt())
                require(seam.isFinite() && seam < SEAM_LIMIT) { "$id MIDI ${zone.midi}: PCM24 seam $seam" }
                val body = Snip(decoded.samples.copyOfRange(saved.loopStartFrame.toInt(), decoded.samples.size), 1, RATE)
                val pitch = Pitch.detect(body, fromSec = .1f)
                val diagnostics = zone.probe.diagnostics
                linkedMapOf<String, Any?>(
                    "midi" to zone.midi, "lowNote" to saved.lowNote, "highNote" to saved.highNote,
                    "audio" to relative(output, wav), "loopStartFrame" to saved.loopStartFrame,
                    "loopFrames" to saved.frameCount - saved.loopStartFrame,
                    "metrics" to (audioMetrics(decoded) + linkedMapOf(
                        "prefixSeconds" to zone.probe.prefixSeconds,
                        "transitionEndSeconds" to zone.probe.transitionEndSeconds,
                        "loopSeconds" to (saved.frameCount - saved.loopStartFrame).toDouble() / RATE,
                        "pcm24SeamError" to seam, "nativeSeamError" to diagnostics.seamError,
                        "stateError" to diagnostics.stateError, "converged" to diagnostics.converged,
                        "prerollCycles" to diagnostics.prerollCycles,
                        "renderSeconds" to diagnostics.renderNanos / 1e9,
                        "requestedHz" to diagnostics.requestedHz, "renderedHz" to diagnostics.renderedHz,
                        "pitchHz" to pitch?.hz, "pitchConfidence" to pitch?.confidence,
                        "pitchCents" to pitch?.let { 1200 * ln(it.hz / Scales.midiToHz(zone.midi).toDouble()) / ln(2.0) },
                        "rawPeak" to diagnostics.rawPeak, "rawRms" to diagnostics.rawRms,
                        "rawDc" to diagnostics.rawDc, "minMapDerivative" to diagnostics.minMapDerivative,
                        "maxMapDerivative" to diagnostics.maxMapDerivative,
                        "maxMapCurvature" to diagnostics.maxMapCurvature,
                        "maxAbsState" to diagnostics.maxAbsState,
                    )),
                    "sha256" to sha(wav), "nativeFloat32Sha256" to floatHash(zone.probe.note.snip.samples),
                    "prelevelFloat32Sha256" to floatHash(zone.probe.prelevel),
                )
            }
            allZones += zoneRows
            val loopSeconds = (root.probe.note.snip.frameCount - root.probe.prefixFrames).toDouble() / RATE
            val holdSeconds = root.probe.prefixSeconds + 1.25 * loopSeconds
            val longOff = frames(holdSeconds)
            val long = performance(loaded, listOf(Event(0, patch.midi, 1f), Event(longOff, patch.midi, null)),
                longOff + frames(RELEASE_SECONDS.toDouble() + .2))
            clips += writeClip(output, "$id-held", "${preset.name}: hold, loop and release", id, "held", patch,
                long, 1f, holdSeconds,
                "A real key press reaches the converged gesture, wraps once, then fades after key-up.") +
                mapOf("prefixSeconds" to root.probe.prefixSeconds, "loopSeconds" to loopSeconds,
                    "wrapsBeforeNoteOff" to 1.25)
            val off = frames(3.0)
            val touchRows = listOf("soft" to .35f, "loud" to 1f).map { (label, touch) ->
                val played = performance(loaded, listOf(Event(0, patch.midi, touch), Event(off, patch.midi, null)),
                    off + frames(RELEASE_SECONDS.toDouble() + .2))
                val row = writeClip(output, "$id-$label", "${preset.name}: $label touch", id, "velocity", patch,
                    played, touch, 3.0,
                    "The same saved-velocity sample at touch $touch. Key touch changes playback gain; excitation stays at ${patch.velocity}.") +
                    mapOf("pair" to "$id-touch", "mode" to label)
                played to row
            }
            val soft = touchRows[0].first.snip.samples
            val loud = touchRows[1].first.snip.samples
            val gainError = soft.indices.maxOf { abs(soft[it] - loud[it] * .35f).toDouble() }
            require(gainError < 1e-7) { "$id: soft/loud clips no longer differ only by player gain: $gainError" }
            clips += touchRows.map { it.second + mapOf("gainOnlyMaxError" to gainError) }
            val phraseEvents = listOf(0 to 0, 3 to 1, 7 to 2, 10 to 3, 12 to 4).flatMap { (semitone, index) ->
                val onset = index * .7
                listOf(Event(frames(onset), patch.midi + semitone, .75f),
                    Event(frames(onset + if (index == 4) 1.8 else .55), patch.midi + semitone, null))
            }
            val phrase = performance(loaded, phraseEvents, frames(5.5))
            clips += writeClip(output, "$id-phrase", "${preset.name}: C minor pentatonic phrase", id, "phrase", patch,
                phrase, .75f, null,
                "C4, E-flat4, G4, B-flat4, C5 through the exported zone map; each note releases after key-up.") +
                mapOf("events" to phraseEvents.sortedBy { it.frame }.map {
                    linkedMapOf("time" to it.frame.toDouble() / RATE, "midi" to it.midi,
                        "action" to if (it.velocity == null) "off" else "on", "velocity" to it.velocity)
                })
            File(packageRoot, "README.txt").writeText(packageReadme(name, patch, midis, sourceCommit))
            writeJson(File(packageRoot, "render-evidence.json"), linkedMapOf(
                "sourceCommit" to sourceCommit, "renderVersion" to CreaseKeys.RENDER_VERSION,
                "sourcePatch" to "source.patch.json", "releaseSeconds" to RELEASE_SECONDS, "zones" to zoneRows,
            ))
            val archive = File(output, "downloads/$id-instrument.zip")
            val zipRows = archive(packageRoot, archive)
            require(archive.length() < MAX_ASSET_BYTES) {
                "$id package is ${archive.length()} bytes; keep it off hosting until an ordinary ZIP below 25 MiB is available"
            }
            instruments += linkedMapOf(
                "id" to id, "name" to preset.name, "voice" to patch.voice.name, "model" to patch.model,
                "midi" to patch.midi, "velocity" to patch.velocity, "macros" to patch.macros,
                "releaseSeconds" to RELEASE_SECONDS, "zoneMidis" to midis,
                "recipe" to relative(output, recipe), "sidecar" to relative(output, sidecar),
                "description" to preset.description,
                "prefixSeconds" to root.probe.prefixSeconds, "loopSeconds" to loopSeconds,
                "trace" to trace(root.probe), "zones" to zoneRows,
                "packs" to listOf(linkedMapOf("file" to relative(output, archive), "label" to "$name instrument ZIP",
                    "bytes" to archive.length(), "sha256" to sha(archive), "entryCount" to zipRows.size,
                    "zipRoundTripByteExact" to true)),
            )
            println("$id: ${zones.size} zones; ${archive.length()} archive bytes; held key-up at %.3f s".format(Locale.ROOT, holdSeconds))
        }
        val zoneMetrics = allZones.map { it.getValue("metrics") as Map<*, *> }
        val summary = linkedMapOf(
            "instrumentCount" to instruments.size, "zoneCount" to allZones.size, "clipCount" to clips.size,
            "maxPcm24SeamError" to zoneMetrics.maxOf { (it["pcm24SeamError"] as Number).toDouble() },
            "maxStateError" to zoneMetrics.maxOf { (it["stateError"] as Number).toDouble() },
            "maxPrefixSeconds" to zoneMetrics.maxOf { (it["prefixSeconds"] as Number).toDouble() },
            "maxAbsPitchCents" to zoneMetrics.mapNotNull { (it["pitchCents"] as? Number)?.toDouble()?.let(::abs) }.maxOrNull(),
            "allZonesConverged" to zoneMetrics.all { it["converged"] == true },
            "allPerformancesReleased" to true, "unchangedPlayerGain" to true,
        )
        writeJson(File(output, "manifest.json"), linkedMapOf(
            "schemaVersion" to 1, "engine" to "CREASE", "kind" to "held-instruments",
            "title" to "CREASE held keys", "generatedAt" to Instant.now().toString(),
            "sourceCommit" to sourceCommit, "workingTreeDirty" to false,
            "renderVersion" to CreaseKeys.RENDER_VERSION, "sampleRate" to RATE,
            "player" to "InstrumentEngine JVM reference", "instruments" to instruments, "clips" to clips,
            "summary" to summary, "limitations" to listOf(
                "The JVM reference player rendered these performances; phone and MPC hardware playback are unverified.",
                "One saved-velocity sample per zone. Key touch scales playback gain and does not re-render excitation.",
                "Keyboard note-off applies the instrument amplitude release; it does not change the baked deformation state.",
                "The original finite notes and held-loop recipes retain their renderers and audio.",
            ),
        ))
        writeJson(File(output, "source-provenance.json"), linkedMapOf("sourceCommit" to sourceCommit, "sha256" to sources))
        val assets = output.walkTopDown().filter { it.isFile }.toList()
        require(assets.all { it.length() < MAX_ASSET_BYTES }) { "An audition asset exceeds the hosting limit" }
        require(git(repository, "rev-parse", "HEAD") == sourceCommit && git(repository, "status", "--porcelain").isEmpty()) {
            "Source changed while generating; regenerate after committing it"
        }
        println("Wrote ${instruments.size} instruments, ${allZones.size} zones and ${clips.size} actual key performances to $output")
    }

    private fun checkProbe(id: String, midi: Int, probe: CreaseKeyProbe) {
        val note = probe.note
        require(note.snip.channels == 1 && note.snip.sampleRate == RATE && note.snip.samples.all { it.isFinite() })
        require(note.snip.peak() >= .001f && note.snip.peak() < 1f && probe.prelevel.all { it.isFinite() })
        require(probe.prefixFrames == note.loopStartFrame.toInt() && probe.prefixFrames >= 256)
        require(probe.diagnostics.converged && probe.diagnostics.stateError <= Crease.LOOP_STATE_TOLERANCE) {
            "$id MIDI $midi: the continuous state did not converge"
        }
        require(probe.diagnostics.seamError < SEAM_LIMIT && Keys.seamError(note.snip.samples, probe.prefixFrames) < SEAM_LIMIT)
        require(probe.previousCycle.size == note.snip.frameCount - probe.prefixFrames)
        require(probe.prefixFrames >= probe.previousCycle.size &&
            note.snip.samples.copyOfRange(probe.prefixFrames - probe.previousCycle.size, probe.prefixFrames)
                .contentEquals(probe.previousCycle)) { "$id MIDI $midi: the preceding real cycle is missing from the onset" }
        require(probe.loopStateStart.isNotEmpty() && probe.loopStateStart.size == probe.loopStateEnd.size)
        val independentlyMeasured = probe.loopStateStart.indices.maxOf { abs(probe.loopStateStart[it] - probe.loopStateEnd[it]) }
        require(independentlyMeasured <= Crease.LOOP_STATE_TOLERANCE)
    }

    private fun performance(loaded: InstrumentEngine.Loaded, events: List<Event>, totalFrames: Int): Performance {
        val player = InstrumentEngine(loaded, RATE)
        val out = FloatArray(totalFrames * 2)
        var position = 0
        fun renderUntil(end: Int) {
            require(end in position..totalFrames)
            while (position < end) {
                val count = minOf(512, end - position)
                val block = FloatArray(count * 2)
                player.render(block, count)
                block.copyInto(out, position * 2)
                position += count
            }
        }
        for (event in events.sortedBy { it.frame }) {
            renderUntil(event.frame)
            if (event.velocity == null) player.noteOff(event.midi)
            else require(player.noteOn(event.midi, event.velocity)) { "No exported zone covers MIDI ${event.midi}" }
        }
        renderUntil(totalFrames)
        require(player.activeNotes.isEmpty()) { "A released note is still sounding: ${player.activeNotes}" }
        return Performance(Snip(out, 2, RATE), player.activeNotes)
    }

    private fun writeClip(output: File, id: String, title: String, instrument: String, section: String,
        patch: CreasePatch, played: Performance, touch: Float, noteOff: Double?, description: String): Map<String, Any?> {
        val snip = played.snip
        require(snip.samples.all { it.isFinite() } && snip.peak() < .9f) { "$id reaches the reference player's soft-clip region" }
        val tail = snip.samples.takeLast(RATE / 25)
        require(tail.all { it == 0f } && played.activeAtEnd.isEmpty()) { "$id did not finish its release" }
        val file = File(output, "audio/$id.wav")
        WavWriter.write(file, snip, WavWriter.BitDepth.PCM_16)
        return linkedMapOf("id" to id, "title" to title, "instrument" to instrument, "section" to section,
            "audio" to relative(output, file), "midi" to patch.midi, "velocity" to touch,
            "savedVelocity" to patch.velocity, "model" to patch.model, "noteOffSeconds" to noteOff,
            "durationSeconds" to snip.durationSeconds, "description" to description,
            "metrics" to (audioMetrics(snip) + mapOf("activeNotesAtEnd" to 0, "tail20msRms" to 0.0)),
            "sha256" to sha(file), "nativeFloat32Sha256" to floatHash(snip.samples))
    }

    private fun audioMetrics(snip: Snip): Map<String, Any?> {
        val x = snip.samples
        return linkedMapOf("durationSeconds" to snip.durationSeconds, "frames" to snip.frameCount,
            "channels" to snip.channels, "peak" to snip.peak(),
            "rms" to sqrt(x.sumOf { it.toDouble() * it } / x.size),
            "dc" to x.sumOf { it.toDouble() } / x.size)
    }

    private fun trace(probe: CreaseKeyProbe): List<Map<String, Any?>> {
        val t = probe.trace
        if (t.timeSeconds.isEmpty()) return emptyList()
        return (0 until minOf(256, t.timeSeconds.size)).map { n ->
            val i = if (t.timeSeconds.size == 1) 0 else n * (t.timeSeconds.size - 1) / (minOf(256, t.timeSeconds.size) - 1)
            linkedMapOf("time" to t.timeSeconds[i], "pressure" to t.pressure[i],
                "deformationA" to t.deformationA[i], "deformationB" to t.deformationB[i],
                "loadA" to t.loadA[i], "loadB" to t.loadB[i])
        }
    }

    private fun archive(bundle: File, target: File): List<String> {
        val files = bundle.walkTopDown().filter { it.isFile }.sortedBy { relative(bundle, it) }.toList()
        ZipOutputStream(target.outputStream().buffered()).use { out ->
            out.setLevel(Deflater.BEST_COMPRESSION)
            for (file in files) {
                out.putNextEntry(ZipEntry("${bundle.name}/${relative(bundle, file)}").apply { time = 0L })
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return ZipFile(target).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size == files.size)
            files.zip(entries).map { (file, entry) ->
                require(entry.name == "${bundle.name}/${relative(bundle, file)}")
                require(zip.getInputStream(entry).use { sha(it.readBytes()) } == sha(file))
                entry.name
            }
        }
    }

    private fun packageReadme(name: String, patch: CreasePatch, midis: List<Int>, source: String) = """
        $name

        Source: $source. CREASE model ${patch.model}, held-key render revision ${CreaseKeys.RENDER_VERSION}.
        source.patch.json preserves the approved saved sound, including all six controls,
        MIDI ${patch.midi} and saved excitation velocity ${patch.velocity}.
        Making this instrument changes transport to continuous onset and settled sustain.

        Extract the whole folder. Load the .xty for MPC 3, or the .xpm inside the
        _[TrackData] folder for MPC 2. SnipSnap reads the adjacent .instrument.json.
        Keep the program, sidecar and sample folder together. Roots: ${midis.joinToString()}.
        The zones cover ${midis.first()}..${midis.last()} with one saved-velocity layer.
        Each WAV also stores its root and sustain-loop markers in a smpl chunk.

        Key touch changes playback gain. It does not re-render excitation or timbre.
        Key-up fades the whole voice over $RELEASE_SECONDS seconds in the player.
        The deformation continues as baked into the sample; release is not a new
        physical recovery simulation. No FX or per-clip loudness normalization.

        Packaging, exported samples and JVM reference playback are checked by the
        generator. Phone playback and MPC hardware loading have not been verified.
    """.trimIndent() + "\n"

    private fun frames(seconds: Double) = (seconds * RATE).roundToInt()
    private fun relative(root: File, file: File) = file.relativeTo(root).invariantSeparatorsPath
    private fun floatHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putFloat(sample)
        return sha(bytes.array())
    }
    private fun sha(file: File): String = file.inputStream().buffered().use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it) }
    private fun writeJson(file: File, value: Any?) = file.writeText(Json.write(json(value)) + "\n")
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) { "Nonfinite JSON metric: $value" } })
        is Map<*, *> -> JsonValue.Obj(value.entries.associateTo(LinkedHashMap()) { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported JSON value ${value.javaClass.name}")
    }
    private fun repositoryRoot(): File = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, ".git").exists() }
    private fun git(repository: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repository.path) + args).redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().use { it.readText() }
        require(process.waitFor() == 0) { "Cannot read source provenance: $result" }
        return result.trim()
    }
}
