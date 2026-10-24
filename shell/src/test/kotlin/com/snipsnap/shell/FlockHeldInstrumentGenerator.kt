package com.snipsnap.shell

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Scales
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.FlockHeld
import com.snipsnap.synth.FlockPatch
import com.snipsnap.synth.FlockPlayback
import com.snipsnap.synth.FlockPresets
import com.snipsnap.synth.FlockVoice
import com.snipsnap.synth.KeyNote
import com.snipsnap.xpm.WavInfo
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Production held RIBBON package and exported-PCM reference playback, without changing its voicing. */
object FlockHeldInstrumentGenerator {
    const val NAME = "SnipSnap Flock Ribbon Held"
    const val ARCHIVE = "SnipSnap-Flock-Ribbon-Held.zip"
    private const val RATE = 44_100

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply only the output root directory." }
        val repository = generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val output = File(args.firstOrNull() ?: "../testkit/flock-held-instrument").canonicalFile
        require(output != repository && !repository.toPath().startsWith(output.toPath())) { "Choose a generated output directory." }
        require(output.isDirectory || output.mkdirs()) { "Could not create $output" }
        val bundle = File(output, NAME)
        require(!bundle.exists() || bundle.deleteRecursively()) { "Could not clear generated bundle $bundle" }
        bundle.mkdirs()
        val patch = FlockPresets.forVoice(FlockVoice.RIBBON).first { it.name == "Traveling Turn" }
            .copy(playback = FlockPlayback.HELD, releaseSeconds = .6f)
        require(patch.seed == 42001L && patch.model == 2 && patch.midi == 60 && patch.velocity == 1f)
        val spec = FlockPadMaker.Spec(patch)
        val roots = FlockPadMaker.zoneMidis(spec)
        require(roots == (48..72 step 3).toList())
        val captures = linkedMapOf<Int, FlockHeld.Held>()
        val renderTimes = linkedMapOf<Int, Double>()
        // Serial capture bounds peak memory on the same route as MAKE INSTRUMENT.
        for (midi in roots) {
            val started = System.nanoTime()
            captures[midi] = FlockHeld.render(patch, midi)
            renderTimes[midi] = millis(started)
            println("captured ${Scales.nameOf(midi)} in ${format(renderTimes.getValue(midi))} ms")
        }
        val started = System.nanoTime()
        val cached = FlockHeld.render(patch)
        val cachedMillis = millis(started)
        require(cached.snip.samples.contentEquals(captures.getValue(patch.midi).snip.samples)) { "Cached capture changed audio." }
        val notes = roots.map { midi -> captures.getValue(midi).let { KeyNote(it.snip, it.loopStartFrame.toLong()) } }
        FlockPadMaker.export(NAME, spec, notes, bundle)
        val sidecarFile = InstrumentStore.sidecar(bundle, NAME)
        val instrument = InstrumentStore.read(sidecarFile)
        require(instrument.zones.map { it.rootNote } == roots && instrument.release == patch.releaseSeconds)
        require(instrument.zones.first().lowNote == roots.first() && instrument.zones.last().highNote == roots.last())
        require(File(bundle, "$NAME.xty").isFile && File(bundle, "${NAME}_[TrackData]/$NAME.xpm").isFile)
        val recipeFile = File(bundle, "$NAME.patch.json")
        require(FlockPatch.fromJsonText(recipeFile.readText()) == patch) { "Export lost the saved source state." }
        val decoded = instrument.sampleFiles.associateWith { file -> WavReader.read(File(bundle, file)) }
        val zones = instrument.zones.map { zone ->
            val file = File(bundle, zone.sample)
            val original = captures.getValue(zone.rootNote)
            val sample = decoded.getValue(zone.sample)
            val info = WavInfo.read(file)
            require(info.sampleRate == RATE && info.channels == 1 && info.bitsPerSample == 24)
            require(info.frameCount == zone.frameCount && sample.frameCount == original.loopEndExclusive)
            require(zone.loopStartFrame == original.loopStartFrame.toLong())
            require(WavReader.readSmpl(file) == SmplChunk(zone.rootNote, SmplChunk.Loop(zone.loopStartFrame, zone.frameCount))) {
                "WAV sampler bounds disagree with the exported zone."
            }
            val error = sample.samples.indices.maxOf { abs(sample.samples[it] - original.snip.samples[it]) }
            require(error <= 2e-7f) { "PCM24 encoding error $error exceeds its precision." }
            linkedMapOf<String, Any?>(
                "rootMidi" to zone.rootNote, "note" to Scales.nameOf(zone.rootNote), "lowMidi" to zone.lowNote,
                "highMidi" to zone.highNote, "file" to zone.sample, "sha256" to sha(file),
                "frames" to zone.frameCount, "loopStartFrame" to zone.loopStartFrame,
                "loopEndExclusive" to zone.frameCount, "loopSeconds" to (zone.frameCount - zone.loopStartFrame).toDouble() / RATE,
                "renderMillis" to renderTimes.getValue(zone.rootNote), "maxPcm24Error" to error,
                "decodedAudio" to audioStats(sample, zone.rootNote), "capture" to captureStats(original),
            )
        }
        val loaded = InstrumentEngine.Loaded(instrument, RATE, decoded.mapValues { it.value.samples })
        val playback = arrayListOf<Map<String, Any?>>()
        for (midi in listOf(roots.first(), patch.midi, roots.last())) {
            val file = File(bundle, "listening/held_${midi}_${Scales.nameOf(midi)}.wav")
            playback += playback(file, bundle, loaded, midi, 1f, 30, "exported instrument, native root", patch.velocity)
        }
        // Source velocity changes the simulation and level. These are separate
        // captures, unlike touching the already-rendered instrument more softly.
        val sourceVelocities = listOf(.25f, .6f, 1f).map { velocity ->
            val sourcePatch = patch.copy(velocity = velocity)
            val sourceStarted = System.nanoTime()
            val held = FlockHeld.render(sourcePatch)
            val elapsed = millis(sourceStarted)
            val tag = (velocity * 100).toInt().toString().padStart(3, '0')
            val sourceFile = File(bundle, "source-velocities/source_$tag.wav")
            WavWriter.write(sourceFile, held.snip, smpl = SmplChunk(patch.midi,
                SmplChunk.Loop(held.loopStartFrame.toLong(), held.loopEndExclusive.toLong())))
            val sourceRecipe = File(bundle, "source-velocities/source_$tag.patch.json").apply { writeText(sourcePatch.toJsonText() + "\n") }
            require(FlockPatch.fromJsonText(sourceRecipe.readText()) == sourcePatch)
            val encoded = WavReader.read(sourceFile)
            val sourceZone = InstrumentStore.Zone(patch.midi, patch.midi, patch.midi, sourceFile.name,
                encoded.frameCount.toLong(), held.loopStartFrame.toLong())
            val sourceLoaded = InstrumentEngine.Loaded(InstrumentStore.Instrument(NAME, patch.releaseSeconds, listOf(sourceZone)),
                RATE, listOf(encoded.samples))
            playback += playback(File(bundle, "listening/source_velocity_$tag.wav"), bundle, sourceLoaded,
                patch.midi, 1f, 10, "saved source velocity, native playback velocity 1", velocity)
            linkedMapOf<String, Any?>("sourceVelocity" to velocity, "renderMillis" to elapsed,
                "expectedCacheHit" to (velocity == patch.velocity),
                "sampleFile" to relative(bundle, sourceFile), "recipeFile" to relative(bundle, sourceRecipe),
                "capture" to captureStats(held), "decodedAudio" to audioStats(encoded, patch.midi))
        }
        for (velocity in listOf(.25f, .6f, 1f)) {
            val tag = (velocity * 100).toInt().toString().padStart(3, '0')
            playback += playback(File(bundle, "listening/touch_velocity_$tag.wav"), bundle, loaded,
                patch.midi, velocity, 4, "same exported source; native touch changes playback gain", patch.velocity)
        }
        val sourcePaths = listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/Flock.kt", "synth/src/main/kotlin/com/snipsnap/synth/FlockHeld.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/FlockPatch.kt", "synth/src/main/kotlin/com/snipsnap/synth/FlockPresets.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/FlockRibbonSustain.kt", "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/FlockLegacyGoldenTest.kt",
            "shell/src/main/kotlin/com/snipsnap/shell/FlockPadMaker.kt", "shell/src/main/kotlin/com/snipsnap/shell/InstrumentEngine.kt",
            "shell/src/main/kotlin/com/snipsnap/shell/KeyHit.kt", "kit/src/main/kotlin/com/snipsnap/kit/OneNote.kt",
            "kit/src/main/kotlin/com/snipsnap/kit/InstrumentStore.kt", "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavReader.kt", "audio/src/main/kotlin/com/snipsnap/audio/SmplChunk.kt",
            "mpc3/src/main/kotlin/com/snipsnap/mpc3/Mpc3TrackWriter.kt", "xpm/src/main/kotlin/com/snipsnap/xpm/KeygroupWriter.kt",
            "shell/src/test/kotlin/com/snipsnap/shell/FlockHeldInstrumentGenerator.kt", "shell/build.gradle.kts",
        )
        val sources = sourcePaths.associateWith { path ->
            val file = File(repository, path)
            file.copyTo(File(bundle, "source/$path").apply { parentFile.mkdirs() })
            sha(file)
        }
        File(bundle, "README.txt").writeText(readme())
        writeJson(File(bundle, "manifest.json"), linkedMapOf(
            "schemaVersion" to 1, "engine" to "FLOCK", "voice" to "RIBBON", "playback" to "HELD",
            "title" to NAME, "sourceCommit" to git(repository, "rev-parse", "HEAD"),
            "workingTreeDirty" to git(repository, "status", "--porcelain").isNotEmpty(), "sourceFilesSha256" to sources,
            "patch" to patch.toJsonValue(), "sampleRate" to RATE, "channels" to 1, "sampleBits" to 24,
            "nativeFormat" to "MPC3 keygroup .xty", "compatibilityFormat" to "MPC2 keygroup .xpm",
            "loopEndConvention" to "exclusive; RIFF smpl stores end minus one",
            "zoneCoverage" to listOf(roots.first(), roots.last()), "maxTranspositionSemitones" to 1,
            "captureCache" to linkedMapOf("maxEntries" to FlockHeld.CACHE_MAX_ENTRIES,
                "maxBytes" to FlockHeld.CACHE_MAX_BYTES, "repeatMillis" to cachedMillis, "repeatAudioExact" to true),
            "renderTimingLimit" to "Wall-clock Kotlin JVM measurements on this worker. Nine zone captures start cold in a fresh process; repeat and source-velocity-1 reuse cached captures. JIT warmup varies; these are not Android latency measurements.",
            "zones" to zones, "sourceVelocityCaptures" to sourceVelocities, "referencePlayback" to playback,
            "verified" to listOf("complete source recipe round trip", "phone sidecar and PCM24 reload",
                "WAV smpl root/bounds", "cached capture byte identity", "reference held playback and note-off silence"),
            "hardwareHeard" to false, "phoneHeard" to false,
            "limitations" to listOf("A captured movement repeats; the equations are not made periodic.",
                "Keyboard touch velocity changes gain; source velocity must be saved and rendered to change the disturbance.",
                "Reference playback uses InstrumentEngine, not Android's native player or MPC hardware.",
                "Capture-only leveling differs from the accepted long comparison's shared gain."),
        ))
        val checksums = bundle.walkTopDown().filter { it.isFile }.sortedBy { relative(bundle, it) }
            .joinToString("\n") { "${sha(it)}  ${relative(bundle, it)}" } + "\n"
        File(bundle, "SHA256SUMS.txt").writeText(checksums)
        val archive = File(output, ARCHIVE)
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            bundle.walkTopDown().filter { it.isFile }.sortedBy { relative(output, it) }.forEach { file ->
                zip.putNextEntry(ZipEntry(relative(output, file)).apply { time = 0L })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        File(output, "$ARCHIVE.sha256").writeText("${sha(archive)}  $ARCHIVE\n")
        println("${archive.absolutePath}: ${archive.length()} bytes; SHA-256 ${sha(archive)}")
        println("9 exported zones, ${playback.size} reference clips; phone/MPC listening remains unverified.")
    }

    private fun playback(file: File, bundle: File, loaded: InstrumentEngine.Loaded, midi: Int,
        velocity: Float, heldSeconds: Int, role: String, sourceVelocity: Float): Map<String, Any?> {
        val engine = InstrumentEngine(loaded, RATE)
        require(engine.noteOn(midi, velocity))
        val heldFrames = heldSeconds * RATE
        val tailFrames = ceil((loaded.instrument.release.toDouble() + .1) * RATE).toInt()
        val out = FloatArray(heldFrames + tailFrames)
        val block = FloatArray(512)
        fun render(from: Int, frames: Int) {
            var at = 0
            while (at < frames) {
                val count = minOf(256, frames - at)
                engine.render(block, count)
                for (i in 0 until count) {
                    require(block[i * 2] == block[i * 2 + 1]) { "Mono reference channels diverged." }
                    out[from + at + i] = block[i * 2]
                }
                at += count
            }
        }
        render(0, heldFrames)
        require(engine.activeNotes == listOf(midi)) { "Held note stopped before note-off." }
        engine.noteOff(midi)
        render(heldFrames, tailFrames)
        require(engine.activeNotes.isEmpty() && out.takeLast(RATE / 20).all { it == 0f }) { "Note-off did not reach silence." }
        val snip = Snip(out, 1, RATE)
        require(snip.samples.all { it.isFinite() } && snip.peak() < 1f)
        WavWriter.write(file, snip, WavWriter.BitDepth.PCM_16)
        val zone = requireNotNull(loaded.instrument.zoneFor(midi))
        val ratio = 2.0.pow((midi - zone.rootNote) / 12.0)
        val wraps = arrayListOf<Map<String, Any?>>()
        var frame = zone.frameCount / ratio
        val period = (zone.frameCount - zone.loopStartFrame) / ratio
        while (frame < heldFrames) {
            val i = ceil(frame).toInt()
            wraps += linkedMapOf("frame" to i, "seconds" to i.toDouble() / RATE,
                "step" to abs(out[i] - out[i - 1]),
                "slopeChange" to abs(out[i + 1] - 2f * out[i] + out[i - 1]))
            frame += period
        }
        return linkedMapOf("file" to relative(bundle, file), "sha256" to sha(file), "role" to role,
            "midi" to midi, "sourceVelocity" to sourceVelocity, "touchVelocity" to velocity,
            "heldSeconds" to heldSeconds, "noteOffFrame" to heldFrames, "releaseSeconds" to loaded.instrument.release,
            "silentAfterRelease" to true, "wraps" to wraps, "audio" to audioStats(snip, midi))
    }

    private fun audioStats(snip: Snip, midi: Int): Map<String, Any?> {
        val expected = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val pitches = listOf(2f, 4f, 6f).filter { it + .25f <= snip.durationSeconds }.map { second ->
            val pitch = Pitch.detect(snip, second, .25f)
            linkedMapOf("fromSeconds" to second, "hz" to pitch?.hz, "confidence" to pitch?.confidence,
                "centsFromRoot" to pitch?.let { 1200.0 * ln(it.hz / expected) / ln(2.0) })
        }
        return linkedMapOf("frames" to snip.frameCount, "seconds" to snip.durationSeconds,
            "peak" to snip.peak(), "mean" to snip.samples.sumOf { it.toDouble() } / snip.samples.size,
            "rms" to sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size),
            "loudness" to Loudness.of(snip), "pitchWindows" to pitches)
    }

    private fun captureStats(held: FlockHeld.Held): Map<String, Any?> {
        val d = held.diagnostics
        val s = d.endpointSearch
        return linkedMapOf("version" to FlockHeld.VERSION, "sourceFrames" to d.sourceFrames,
            "sourceSeconds" to d.sourceFrames.toDouble() / RATE, "captureFrames" to held.snip.frameCount,
            "loopStartFrame" to held.loopStartFrame, "loopEndExclusive" to held.loopEndExclusive,
            "captureGain" to d.commonGain, "outputPeak" to d.outputPeak, "blendCorrelation" to d.blendCorrelation,
            "nominalLoopFrames" to s.nominalLoopFrames, "selectedLoopFrames" to s.selectedLoopFrames,
            "searchRadiusFrames" to s.radiusFrames, "evaluatedCandidates" to s.evaluatedCandidates,
            "baselineMin50msDb" to s.baselineMin50msDb, "baselineMax50msDb" to s.baselineMax50msDb,
            "selectedMin50msDb" to s.selectedMin50msDb, "selectedMax50msDb" to s.selectedMax50msDb)
    }

    private fun readme() = """
        FLOCK RIBBON HELD instrument

        Gesture: an arriving oscillator flock becomes captured moving shimmer,
        repeats while the key is held, and fades over 0.6 seconds after note-off.

        MPC 3: open "$NAME.xty" beside its _[TrackData] folder.
        MPC 2: open "$NAME.xpm" inside the _[TrackData] folder.
        SnipSnap: keep the .instrument.json beside the same sample folder.
        The .patch.json retains the editable source recipe, including exact seed,
        source note/velocity, renderer model, held-capture version and release.

        Nine native mono PCM24 zones cover MIDI 48 through 72 (C3 through C5).
        Inner notes transpose at most one semitone. All files have real attack,
        a two-second loop start, and a roughly six-second captured movement.
        Loop ends in manifest/phone metadata are exclusive; RIFF stores end - 1.

        LISTENING
        held_*.wav: actual exported PCM24 through the JVM reference player at
        low/root/high native notes, held 30 seconds then released.
        source_velocity_*.wav: saved source velocity .25/.6/1, each separately
        captured, held 10 seconds at touch velocity 1, then released. The saved
        patches and attack-plus-loop WAVs are in source-velocities/.
        touch_velocity_*.wav: the same exported source, touch velocity .25/.6/1,
        held four seconds then released. Touch changes playback gain only.
        Listening WAVs are PCM16 mono, without matching or added effects.

        manifest.json records Kotlin worker timings, capture power, actual wraps,
        pitch estimates and source hashes. Timings include JVM warmup effects;
        these are not target-phone latency measurements. The reference player
        checks note-off silence. It does not prove Android or MPC playback.

        The owner heard the preceding long sustain study. This production
        instrument and its registers/velocities still need phone/MPC listening.
        Capture-only leveling differs from the long study's shared comparison gain.
        Finite GATHER/RIBBON/SPLIT patches and HOLD 2-6 seconds keep their meaning.

        Rebuild: ./gradlew --no-daemon :shell:generateFlockHeldInstrument
        Optional output: -PflockHeldOutput=/absolute/path/to/output
        Design: docs/FLOCK_RIBBON_INSTRUMENT.md in the SnipSnap repository.

        Why it is built this way
        - The original onset lets a fresh note arrive before the repeated body.
        - A captured movement uses the existing sample player and native note-off.
        - Nearby native roots limit how much transposition changes motion speed.
        - Saved source velocity changes the flock; keyboard touch scales playback.
        - The risky choice is repeating one captured movement. Listen across wraps.
    """.trimIndent() + "\n"

    private fun millis(started: Long) = (System.nanoTime() - started) / 1_000_000.0
    private fun format(value: Double) = "%.2f".format(Locale.ROOT, value)
    private fun relative(root: File, file: File) = file.relativeTo(root).invariantSeparatorsPath
    private fun git(root: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().readText().trim()
        require(process.waitFor() == 0) { "Could not read source provenance: $result" }
        return result
    }
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    }
    private fun writeJson(file: File, value: Any?) = file.writeText(Json.write(json(value)) + "\n")
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> value.toDouble().let { require(it.isFinite()); JsonValue.Num(it) }
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported JSON value ${value.javaClass.name}")
    }
}
