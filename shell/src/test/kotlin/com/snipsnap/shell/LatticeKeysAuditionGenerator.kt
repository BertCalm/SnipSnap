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
import com.snipsnap.synth.LatticeKeys
import com.snipsnap.synth.LatticePresets
import com.snipsnap.synth.LatticeVoice
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/** Actual TRACE keygroup delivery, read back through the normal sample player. */
object LatticeKeysAuditionGenerator {
    private const val RATE = 44_100
    private const val NAME = "Lattice Trace Keys"
    private const val RECIPE_VERSION = 1
    private val SOURCE_FILES = listOf(
        "synth/src/main/kotlin/com/snipsnap/synth/Lattice.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/LatticeKeys.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/LatticeMotion.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/LatticePatch.kt",
        "shell/src/main/kotlin/com/snipsnap/shell/LatticePadMaker.kt",
        "shell/src/main/kotlin/com/snipsnap/shell/InstrumentEngine.kt",
        "shell/src/main/kotlin/com/snipsnap/shell/KeyHit.kt",
        "kit/src/main/kotlin/com/snipsnap/kit/OneNote.kt",
        "kit/src/main/kotlin/com/snipsnap/kit/InstrumentStore.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
        "shell/src/test/kotlin/com/snipsnap/shell/LatticeKeysAuditionGenerator.kt",
    )

    private data class Event(val midi: Int, val on: Int, val off: Int, val velocity: Float = .75f)

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "--replay") {
            require(args.size == 3) { "usage: --replay recipe.json output.wav" }
            WavWriter.write(File(args[2]), replay(File(args[1])), WavWriter.BitDepth.PCM_16)
            println("Replayed held TRACE performance to ${args[2]}")
            return
        }
        require(args.size <= 1) { "supply an optional, fresh output directory" }
        val repo = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile && File(it, ".git").exists() }
        val output = File(args.firstOrNull() ?: File(repo, "testkit/lattice-keys-audition").path).canonicalFile
        require(!output.exists() || output.listFiles()?.isEmpty() == true) { "Use a fresh output directory: $output" }
        val revision = git(repo, "rev-parse", "HEAD")
        val dirty = git(repo, "status", "--porcelain", "--untracked-files=normal").isNotEmpty()
        val hashes = SOURCE_FILES.associateWith { sha(File(repo, it)) }
        val started = System.nanoTime()
        check(output.mkdirs() || output.isDirectory)
        val bundle = File(output, "keys/instruments/$NAME").apply { check(mkdirs()) }
        val spec = LatticePadMaker.Spec(LatticePresets.forVoice(LatticeVoice.TRACE).first())
        val roots = LatticePadMaker.zoneMidis(spec)
        val times = mutableListOf<Double>()
        val notes = roots.map { midi ->
            val before = System.nanoTime()
            LatticePadMaker.renderZone(spec, midi).also { note ->
                times += (System.nanoTime() - before) / 1e6
                println("TRACE ${Scales.nameOf(midi)}: ${note.snip.frameCount} frames, loop ${note.loopStartFrame}")
            }
        }
        val program = LatticePadMaker.export(NAME, spec, notes, bundle)
        val (sidecar, instrument) = InstrumentStore.list(bundle).single()
        check(instrument.zones.size == 9 && instrument.zones.first().lowNote == 48 && instrument.zones.last().highNote == 72)
        check(instrument.release == spec.releaseSeconds)
        check(LatticePadMaker.Spec.fromJsonText(LatticePadMaker.recipeFile(bundle, NAME).readText()) == spec)
        val decoded = instrument.sampleFiles.associateWith { WavReader.read(File(sidecar.parentFile, it)) }
        val loaded = InstrumentEngine.Loaded(instrument, RATE, decoded.mapValues { it.value.samples })
        val zoneRows = instrument.zones.mapIndexed { index, zone ->
            val file = File(sidecar.parentFile, zone.sample)
            val pcm = decoded.getValue(zone.sample)
            val sampler = requireNotNull(WavReader.readSmpl(file))
            val loop = requireNotNull(sampler.loop)
            val source = notes[index]
            check(sampler.rootNote == roots[index] && zone.rootNote == roots[index])
            check(pcm.channels == 1 && pcm.sampleRate == RATE && pcm.frameCount == source.snip.frameCount)
            check(loop.startFrame == source.loopStartFrame && loop.endFrameExclusive == pcm.frameCount.toLong())
            check(zone.loopStartFrame == source.loopStartFrame)
            val quantizationError = pcm.samples.indices.maxOf { abs(pcm.samples[it] - source.snip.samples[it]) }
            check(quantizationError < 2e-7f)
            linkedMapOf<String, Any?>(
                "rootMidi" to zone.rootNote, "rootName" to Scales.nameOf(zone.rootNote),
                "lowMidi" to zone.lowNote, "highMidi" to zone.highNote,
                "file" to relative(output, file), "sha256" to sha(file), "pcmBits" to 24,
                "frames" to pcm.frameCount, "loopStartFrame" to loop.startFrame,
                "loopEndFrameExclusive" to loop.endFrameExclusive,
                "maxPcm24Error" to quantizationError, "renderMillis" to times[index],
            )
        }
        val nativeTrack = File(bundle, "$NAME.xty")
        check(Acvs.read(nativeTrack).payloadText == Mpc3TrackWriter().keygroupPayloadText(program))
        val compatibility = File(bundle, "${Mpc3TrackWriter.trackDataDirName(NAME)}/$NAME.xpm")
        val xpm = compatibility.readText()
        notes.forEach { check("<SliceLoopStart>${it.loopStartFrame}</SliceLoopStart>" in xpm) }

        // These three proofs come from real independent synthesis cycles before any playback repeats.
        val proofRows = listOf(48, 60, 72).map { midi ->
            val proof = LatticeKeys.probe(spec.patch, midi)
            val folder = File(output, "keys/diagnostics/${Scales.nameOf(midi).lowercase(Locale.ROOT)}").apply { check(mkdirs()) }
            val samples = proof.note.snip.samples
            val prefix = WavWriter.write(File(folder, "attack-prefix.wav"), Snip(samples.copyOf(proof.prefixFrames), 1, RATE))
            val previous = WavWriter.write(File(folder, "actual-previous-cycle.wav"), Snip(proof.previousCycle, 1, RATE))
            val loop = WavWriter.write(File(folder, "actual-loop-cycle.wav"), Snip(samples.copyOfRange(proof.prefixFrames, samples.size), 1, RATE))
            linkedMapOf<String, Any?>(
                "midi" to midi, "prefixFrames" to proof.prefixFrames, "loopFrames" to proof.loopFrames,
                "prefixSeconds" to proof.prefixSeconds, "transitionEndSeconds" to proof.transitionEndSeconds,
                "requestedHz" to proof.requestedHz, "fittedHz" to proof.fittedHz,
                "pitchErrorCents" to proof.pitchErrorCents, "seamError" to proof.seamError,
                "cycleError" to proof.cycleError, "pcm16SeamError" to proof.pcm16SeamError,
                "pcm16MaxCycleDifference" to proof.pcm16MaxCycleDifference,
                "pcm24MaxCycleDifference" to proof.pcm24MaxCycleDifference,
                "stateError" to proof.stateError, "sourceGain" to proof.sourceGain,
                "loopStateStart" to proof.loopStateStart.toList(), "loopStateEnd" to proof.loopStateEnd.toList(),
                "directSustainPeak" to proof.directSustainPeak, "renderMillis" to proof.renderMillis,
                "files" to listOf(prefix, previous, loop).map { mapOf("file" to relative(output, it), "sha256" to sha(it)) },
                "scope" to "Native held source with its fixed velocity gain; package layers remove that gain once for host playback.",
            )
        }
        val source = linkedMapOf<String, Any?>(
            "engine" to "LATTICE", "voice" to "TRACE", "patchModel" to spec.patch.model,
            "heldModel" to LatticeKeys.MODEL_VERSION, "sourceCommit" to revision,
            "workingTreeDirty" to dirty, "sourceFilesSha256" to hashes,
            "sampleRate" to RATE, "oversample" to 4,
        )
        val fileHashes = (listOf(sidecar) + instrument.sampleFiles.map { File(sidecar.parentFile, it) })
            .associate { relative(output, it) to sha(it) }
        val releaseFrames = ceil(spec.releaseSeconds * RATE.toDouble()).toInt()
        fun hit(midi: Int, velocity: Float = .75f): KeyHit.Hit = requireNotNull(
            KeyHit.resolve(instrument, midi, velocity) { decoded[it]?.frameCount?.toLong() },
        )
        fun heldFrames(midi: Int): Int {
            val resolved = hit(midi)
            return ceil(resolved.frames / resolved.pitchRatio).toInt() +
                ceil((resolved.frames - resolved.loopStartFrame) / resolved.pitchRatio).toInt() + RATE / 8
        }
        fun clip(id: String, title: String, description: String, events: List<Event>): Map<String, Any?> {
            val frames = events.maxOf { it.off } + releaseFrames + RATE / 5
            val recipeFile = File(output, "keys/recipes/$id.json").apply { parentFile.mkdirs() }
            val recipe = linkedMapOf<String, Any?>(
                "version" to RECIPE_VERSION, "kind" to "lattice-keygroup-performance", "source" to source,
                "instrumentSidecar" to relative(output, sidecar), "exportedFilesSha256" to fileHashes,
                "sampleRate" to RATE, "frames" to frames, "releaseSeconds" to spec.releaseSeconds,
                "hostVoiceGain" to InstrumentEngine.VOICE_LEVEL, "sourceRecipe" to Json.parse(spec.toJsonText()),
                "events" to events.map { mapOf("midi" to it.midi, "velocity" to it.velocity, "noteOnFrame" to it.on, "noteOffFrame" to it.off) },
            )
            recipeFile.writeText(Json.write(json(recipe)) + "\n")
            val rendered = performance(loaded, events, frames)
            val replayed = replay(recipeFile)
            check(rendered.samples.contentEquals(replayed.samples)) { "$id recipe Float replay differs" }
            val audioFile = WavWriter.write(File(output, "keys/clips/$id.wav"), rendered, WavWriter.BitDepth.PCM_16)
            val replayBytes = ByteArrayOutputStream().also { WavWriter.write(it, replayed, WavWriter.BitDepth.PCM_16) }.toByteArray()
            check(audioFile.readBytes().contentEquals(replayBytes)) { "$id recipe PCM16 replay differs" }
            val pcm = WavReader.read(audioFile)
            val eventRows = events.map { event ->
                val zone = requireNotNull(instrument.zoneFor(event.midi))
                val resolved = hit(event.midi, event.velocity)
                val wrap = event.on + ceil(resolved.frames / resolved.pitchRatio).toInt()
                check(event.off > wrap + RATE / 10)
                val afterWrap = rms(pcm.samples, wrap + RATE / 40, wrap + RATE / 10)
                check(afterWrap > 1e-7) { "$id stopped instead of sustaining at ${event.midi}" }
                mapOf(
                    "midi" to event.midi, "velocity" to event.velocity, "noteOnFrame" to event.on,
                    "firstWrapFrame" to wrap, "noteOffFrame" to event.off,
                    "releaseFinishedFrame" to event.off + releaseFrames,
                    "heldPastFirstWrapFrames" to event.off - wrap, "postWrapRms" to afterWrap,
                    "zoneRootMidi" to zone.rootNote, "pitchRatio" to resolved.pitchRatio,
                )
            }
            check(pcm.samples.takeLast(RATE / 10).all { it == 0f }) { "$id did not release to silence" }
            val first = events.minBy { it.on }
            val resolved = hit(first.midi, first.velocity)
            val cues = listOf(
                mapOf("time" to first.on / RATE.toDouble(), "label" to "Attack and movement"),
                mapOf("time" to (first.on + ceil(resolved.loopStartFrame / resolved.pitchRatio)) / RATE.toDouble(), "label" to "Held sustain"),
                mapOf("time" to first.off / RATE.toDouble(), "label" to "Release the key"),
            )
            return linkedMapOf(
                "id" to id, "title" to title, "description" to description, "section" to "keys",
                "file" to relative(output, audioFile), "recipeFile" to relative(output, recipeFile),
                "sha256" to sha(audioFile), "duration" to frames / RATE.toDouble(),
                "waveform" to waveform(pcm.samples), "events" to eventRows, "cues" to cues,
                "metrics" to statistics(pcm.samples), "commonGain" to true,
            )
        }
        val chordNotes = listOf(48, 51, 55)
        val chordOff = chordNotes.mapIndexed { index, midi -> heldFrames(midi) + index * RATE / 3 }.max()
        val clips = listOf(
            clip("keys-c3-hold", "Hold C3, then let go", "The opening movement leads into a steady ringing tone. The key stays down through a full loop, then releases over 0.6 seconds.",
                listOf(Event(48, 0, heldFrames(48)))),
            clip("keys-c4-hold", "The same gesture, one octave up", "C4 uses its own rendered root. Listen across the marked sustain and the key release; no new strike happens at the loop.",
                listOf(Event(60, 0, heldFrames(60)))),
            clip("keys-chord", "A held C minor chord", "Three staggered key presses settle into the collector together. Every note wraps before all three keys release.",
                chordNotes.mapIndexed { index, midi -> Event(midi, index * RATE / 3, chordOff, .65f) }),
        )
        val validation = linkedMapOf<String, Any?>(
            "exportReadback" to true, "samplerMarkersReadback" to true, "mpc3ContainerReadback" to true,
            "recipesSampleIdentical" to true, "recipeReplayCount" to clips.size,
            "allClipsWrapAndRelease" to true, "independentCycleProofCount" to proofRows.size,
            "physicalPhoneValidated" to false, "mpcHardwareValidated" to false, "ownerListeningAcceptance" to false,
        )
        val measurements = linkedMapOf<String, Any?>(
            "generatedAt" to Instant.now().toString(), "keysEngine" to source, "sourceRecipe" to Json.parse(spec.toJsonText()),
            "instrument" to mapOf("name" to NAME, "releaseSeconds" to instrument.release, "range" to listOf(48, 72),
                "rootCount" to roots.size, "velocityLayers" to 1, "sourceVelocity" to spec.patch.velocity,
                "mpc3" to relative(output, nativeTrack), "mpc2" to relative(output, compatibility), "sidecar" to relative(output, sidecar)),
            "zones" to zoneRows, "independentCycleProofs" to proofRows, "clips" to clips, "keysValidation" to validation,
            "gainNote" to "One captured velocity-shaped spectrum. Export layers remove velocity^.72 once; the host applies event velocity and its fixed voice gain. No clip normalization or external effects.",
            "releaseNote" to "Note-off fades the looping sample through the existing host amplitude envelope; it does not synthesize a separate physical free-decay tail.",
            "renderSeconds" to (System.nanoTime() - started) / 1e9,
        )
        File(output, "keys-measurements.json").writeText(Json.write(json(measurements)) + "\n")
        File(bundle, "README.txt").writeText(readme(spec.releaseSeconds))
        val zip = archive(output, bundle)
        val manifest = linkedMapOf<String, Any?>(
            "revision" to 6, "schemaVersion" to 1, "title" to "Lattice · hold the ringing trace",
            "subtitle" to "TRACE as a keyboard instrument, with its movement, held tone and release.",
            "revisionTitle" to "Keep the movement.\nHold the ringing tone.",
            "revisionSummary" to "TRACE now has a separate C3–C5 keyboard instrument. Each key keeps the opening gesture, reaches a measured sustain loop, and fades when you let go.",
            "revisionNote" to "The accepted B one-shot remains unchanged. These keyboard captures keep the source running at a steady level after the attack; they are a new listening proposal.",
            "heroClipId" to "keys-c3-hold", "heroTitle" to "Hold C3, then let go",
            "heroEyebrow" to "TRACE · A KEY HELD THROUGH SUSTAIN",
            "heroDescription" to "Hear the moving pluck arrive at a held ringing tone. It passes the loop marker, wraps without another attack, then fades when the key is released.",
            "heroFootnote" to "Playback uses the exported keygroup WAVs and the normal reference player. Release is the host's 0.6 second fade. The accepted B reference remains below.",
            "generatedAt" to Instant.now().toString(), "keysEngine" to source, "keysValidation" to validation,
            "clips" to clips, "instrument" to (measurements.getValue("instrument")),
            "downloads" to mapOf("keygroups" to zip.name, "keysMeasurements" to "keys-measurements.json"),
            "archive" to mapOf("file" to zip.name, "sha256" to sha(zip), "bytes" to zip.length()),
        )
        File(output, "keys-manifest.json").writeText(Json.write(json(manifest)) + "\n")
        File(output, "KEYS-REPLAY.txt").writeText("""
            LATTICE TRACE KEYS, HELD MODEL ${LatticeKeys.MODEL_VERSION}

            Generate: ./gradlew --no-daemon :shell:generateLatticeKeysAudition -PlatticeKeysOutput=/fresh/output
            Replay: ./gradlew --no-daemon :shell:generateLatticeKeysAudition -PlatticeKeysRecipe=/path/keys/recipes/keys-c3-hold.json -PlatticeKeysReplayOutput=/path/replay.wav

            Keep recipes under keys/recipes and the exported instrument under keys/instruments.
            Replay checks the exported sidecar and each WAV SHA-256, then drives InstrumentEngine
            with the saved exact note-on/off frames. All three PCM16 performances replay byte-identically.
            keys/diagnostics contains independent synthesis cycles at C3/C4/C5 before playback repeats.
            keys-measurements.json records source fingerprints, cycle errors and package marker readback.
            Native package WAVs are PCM24; clips are PCM16. No loudness matching is applied.
            Physical phone/MPC listening and acceptance of the new held sound remain open.
            Source revision: $revision
        """.trimIndent() + "\n")
        println("Wrote 9 TRACE keygroups, 3 replay-checked performances and 3 independent cycle proofs in ${(System.nanoTime() - started) / 1e9}s")
    }

    private fun replay(recipeFile: File): Snip {
        val recipe = Json.parse(recipeFile.readText()).obj()
        require(recipe.getValue("version").int() == RECIPE_VERSION && recipe.getValue("kind").str() == "lattice-keygroup-performance")
        require(recipe.getValue("sampleRate").int() == RATE)
        val root = recipeFile.canonicalFile.parentFile.parentFile.parentFile
        fun inside(path: String): File = File(root, path).canonicalFile.also { require(it.toPath().startsWith(root.toPath())) }
        recipe.getValue("exportedFilesSha256").obj().forEach { (path, hash) ->
            require(sha(inside(path)) == hash.str()) { "Exported dependency changed: $path" }
        }
        val sidecar = inside(recipe.getValue("instrumentSidecar").str())
        val instrument = InstrumentStore.read(sidecar)
        val samples = instrument.sampleFiles.associateWith { WavReader.read(File(sidecar.parentFile, it)).samples }
        val events = recipe.getValue("events").arr().map { value ->
            val row = value.obj()
            Event(row.getValue("midi").int(), row.getValue("noteOnFrame").int(), row.getValue("noteOffFrame").int(), row.getValue("velocity").num().toFloat())
        }
        return performance(InstrumentEngine.Loaded(instrument, RATE, samples), events, recipe.getValue("frames").int())
    }

    private fun performance(loaded: InstrumentEngine.Loaded, events: List<Event>, frames: Int): Snip {
        require(events.all { it.on >= 0 && it.off > it.on && it.off < frames })
        val player = InstrumentEngine(loaded, RATE)
        val starts = events.groupBy { it.on }
        val stops = events.groupBy { it.off }
        val boundaries = (starts.keys + stops.keys + listOf(0, frames)).distinct().sorted()
        val out = FloatArray(frames)
        val block = FloatArray(512 * 2)
        for ((begin, end) in boundaries.zipWithNext()) {
            stops[begin]?.forEach { player.noteOff(it.midi) }
            starts[begin]?.forEach { check(player.noteOn(it.midi, it.velocity)) }
            var position = begin
            while (position < end) {
                val count = minOf(512, end - position)
                player.render(block, count)
                for (i in 0 until count) {
                    check(block[2 * i] == block[2 * i + 1])
                    out[position + i] = block[2 * i]
                }
                position += count
            }
        }
        check(player.activeNotes.isEmpty()) { "Performance ended before release completed" }
        return Snip(out, 1, RATE)
    }

    private fun archive(output: File, bundle: File): File {
        val files = bundle.walkTopDown().filter { it.isFile }.sortedBy { relative(bundle, it) }.toList()
        val archive = File(output, "lattice-trace-keys.zip")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            files.forEach { file ->
                zip.putNextEntry(ZipEntry("${bundle.name}/${relative(bundle, file)}").apply { time = 0 })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        ZipFile(archive).use { zip ->
            check(zip.size() == files.size)
            files.forEach { file -> check(zip.getInputStream(zip.getEntry("${bundle.name}/${relative(bundle, file)}")).use { it.readBytes() }.contentEquals(file.readBytes())) }
        }
        return archive
    }

    private fun readme(release: Float) = """
        LATTICE TRACE KEYS

        Nine native roots C3–C5, every minor third. The program covers MIDI 48–72.
        Each mono PCM24 sample contains its real onset followed by one settled loop.
        Keep the .xty with its TrackData folder for MPC 3. The MPC 2 .xpm twin is
        inside TrackData. The .instrument.json sidecar is for SnipSnap's player.
        $NAME.lattice-keys.json preserves the complete finite source patch and
        separately versioned held recipe. The finite B rendering stays unchanged.

        Keyboard mode keeps the source driving the collector after the opening
        movement. SPEED changes movement timing. The saved finite HOLD value does
        not determine keyboard note length: the key stays down until note-off.
        Release is the host amplitude fade, $release seconds; no separate physical
        free-decay tail is synthesized. One captured velocity-shaped spectrum is
        shared across velocities; the host supplies note-velocity gain.

        A normal WAV player plays the marked sustain once and stops. Load the
        keygroup program to hold a key. Exported-file reference playback, loop
        markers and source-cycle recurrence are measured. Physical phone/MPC
        import and owner acceptance of this new held sound remain open.
    """.trimIndent() + "\n"

    private fun waveform(samples: FloatArray): List<Double> {
        val peak = samples.maxOf { abs(it).toDouble() }.coerceAtLeast(1e-12)
        return (0 until 160).map { bin ->
            val begin = bin * samples.size / 160
            val end = ((bin + 1) * samples.size / 160).coerceAtLeast(begin + 1)
            (begin until end).maxOf { abs(samples[it]).toDouble() } / peak
        }
    }
    private fun rms(samples: FloatArray, begin: Int, end: Int): Double =
        sqrt((begin until end).sumOf { samples[it].toDouble() * samples[it] } / (end - begin))
    private fun statistics(samples: FloatArray): Map<String, Double> = mapOf(
        "peak" to samples.maxOf { abs(it).toDouble() }, "rms" to rms(samples, 0, samples.size),
        "dc" to samples.sumOf { it.toDouble() } / samples.size,
    )
    private fun relative(root: File, file: File) = file.relativeTo(root).invariantSeparatorsPath
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(Locale.ROOT, it) }
    private fun git(repo: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repo.path) + args).redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "Cannot read source provenance" }
        return result.trim()
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble())
        is Map<*, *> -> JsonValue.Obj(value.entries.associateTo(linkedMapOf()) { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported manifest type ${value.javaClass.name}")
    }
}
