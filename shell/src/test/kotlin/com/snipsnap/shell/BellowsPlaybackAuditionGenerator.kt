package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.Bellows
import com.snipsnap.synth.BellowsKeys
import com.snipsnap.synth.BellowsPatch
import com.snipsnap.synth.BellowsPresets
import com.snipsnap.synth.BellowsVoice
import com.snipsnap.synth.KeyNote
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A listening proof of the unchanged model-2 source against the instrument the shop exports.
 * The program path reads the real PCM24 WAVs and instrument sidecar back from disk, then
 * resolves its zones, velocity layers, repitching, loop and release through [InstrumentEngine].
 * This is the JVM playback reference; it is not a recording of a phone or an MPC.
 *
 * Each A/B pair uses the same notes, timing, .35 voice-bus level and one shared output gain.
 * The native path retains BellowsKeys.note's continuous velocity timbre and velocity^.60
 * level. The program uses the three exported timbres and the player's single linear gain.
 * Matching each side separately would hide precisely the touch difference this proof tests.
 */
object BellowsPlaybackAuditionGenerator {
    internal const val RATE = WavWriter.MPC_SAMPLE_RATE
    private const val BPM = 96
    private const val RELEASE = .6f
    private const val SILENCE_SECONDS = .02

    internal data class Event(val midi: Int, val startFrame: Int, val offFrame: Int, val velocity: Float)
    private data class Performance(val kind: String, val title: String, val description: String, val events: List<Event>)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply at most one output directory." }
        val output = File(args.firstOrNull() ?: "../testkit/bellows-playback-audition").canonicalFile
        output.mkdirs()
        val repository = repositoryRoot()
        val paths = listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/Bellows.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/BellowsKeys.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/BellowsPresets.kt",
            "shell/src/main/kotlin/com/snipsnap/shell/BellowsPadMaker.kt",
            "shell/src/main/kotlin/com/snipsnap/shell/InstrumentEngine.kt",
            "shell/src/main/kotlin/com/snipsnap/shell/KeyHit.kt",
            "kit/src/main/kotlin/com/snipsnap/kit/OneNote.kt",
            "kit/src/main/kotlin/com/snipsnap/kit/InstrumentStore.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavReader.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
            "shell/src/test/kotlin/com/snipsnap/shell/BellowsPlaybackAuditionGenerator.kt",
        )
        val provenance = linkedMapOf(
            "sourceCommit" to git(repository, "rev-parse", "HEAD"),
            "workingTreeDirty" to git(repository, "status", "--porcelain").isNotEmpty(),
            "sourceSha256" to paths.associateWith { sha(File(repository, it)) },
        )
        generate(output, provenance)
    }

    internal fun generate(output: File, provenance: Map<String, Any?> = emptyMap()) {
        output.mkdirs()
        val comparisons = ArrayList<Map<String, Any?>>()
        val releases = ArrayList<Map<String, Any?>>()
        val programs = ArrayList<Map<String, Any?>>()
        for (voice in BellowsVoice.entries) {
            val patch = BellowsPresets.forVoice(voice).first()
            val spec = BellowsPadMaker.Spec(patch, RELEASE)
            val slug = voice.name.lowercase(Locale.ROOT)
            val name = "Bellows ${display(voice)} Keys"
            val destination = File(output, "programs/$slug").apply { mkdirs() }
            val rendered = BellowsPadMaker.zoneMidis(spec).map { BellowsPadMaker.renderZone(spec, it) }
            BellowsPadMaker.export(name, spec, rendered, destination, overwrite = true)
            val sidecar = InstrumentStore.sidecar(destination, name)
            val loaded = loadProgram(sidecar)
            require(loaded.instrument.zones.size == 9 && loaded.instrument.sampleFiles.size == 27)
            require(loaded.instrument.zones.map { it.rootNote } == (48..72 step 3).toList())
            require(loaded.instrument.playableRelease == RELEASE)
            programs += linkedMapOf(
                "voice" to voice.name, "name" to name, "sidecar" to relative(output, sidecar),
                "sidecarSha256" to sha(sidecar), "sampleCount" to 27,
                "pitchRoots" to loaded.instrument.zones.map { it.rootNote },
                "velocityWindows" to BellowsPadMaker.VELOCITY_WINDOWS.map { listOf(it.first, it.last) },
                "sourceVelocities" to BellowsPadMaker.VELOCITIES,
                "sampleSha256" to loaded.instrument.sampleFiles.associateWith { sha(File(destination, it)) },
                "patch" to patch.toJsonValue(),
            )
            val nativeCache = HashMap<Pair<Int, Float>, KeyNote>()
            for (performance in performances()) {
                val frames = performance.events.maxOf { it.offFrame } +
                    (RELEASE * RATE).roundToInt() + (SILENCE_SECONDS * RATE).roundToInt()
                val native = renderNative(patch, performance.events, frames, RELEASE, nativeCache)
                val program = renderProgram(loaded, performance.events, frames)
                // InstrumentEngine soft-clips above .9; native phone playback clamps at 1.
                // Staying below .9 proves neither bus changes this comparison's waveform.
                require(native.peak() < .9f && program.peak() < .9f) {
                    "$voice ${performance.kind}: playback bus clipping would confound the comparison"
                }
                val commonGain = 1f
                val id = "playback-$slug-${performance.kind}-m${Bellows.MODEL_VERSION}"
                val nativeFile = "wav/$id-native.wav"
                val programFile = "wav/$id-program.wav"
                writeChecked(File(output, nativeFile), native)
                writeChecked(File(output, programFile), program)
                val eventRows = performance.events.map { event -> eventRow(loaded, event) }
                val loopCrossings = performance.events.count { event ->
                    val hit = requireNotNull(KeyHit.resolve(loaded.instrument, event.midi, event.velocity) {
                        loaded.layerSamples[it]?.size?.toLong()
                    })
                    (event.offFrame - event.startFrame) * hit.pitchRatio > hit.frames
                }
                if (performance.kind != "boundary") require(loopCrossings > 0)
                comparisons += linkedMapOf(
                    "id" to id, "voice" to voice.name,
                    "title" to "${display(voice)} · ${performance.title}", "kind" to performance.kind,
                    "description" to performance.description,
                    "nativeFile" to nativeFile, "programFile" to programFile,
                    "nativeSha256" to sha(File(output, nativeFile)), "programSha256" to sha(File(output, programFile)),
                    "durationSeconds" to frames.toDouble() / RATE, "commonGain" to commonGain,
                    "releaseSeconds" to RELEASE, "bpm" to BPM, "events" to eventRows,
                    "programLoopCrossingNotes" to loopCrossings,
                    "nativeMetrics" to metrics(native), "programMetrics" to metrics(program),
                    "patch" to patch.toJsonValue(),
                )
                println("BELLOWS playback: $voice ${performance.kind}, ${frames.toDouble() / RATE}s, common gain $commonGain")
            }
            val short = BellowsPadMaker.preview(spec.copy(releaseSeconds = BellowsPadMaker.RELEASE_MIN_SECONDS))
            val long = BellowsPadMaker.preview(spec.copy(releaseSeconds = BellowsPadMaker.RELEASE_MAX_SECONDS))
            require(long.frameCount > short.frameCount && short.samples.last() == 0f && long.samples.last() == 0f)
            val holdFrames = (6.0 * RATE).roundToInt()
            require(short.samples.copyOfRange(0, holdFrames).contentEquals(long.samples.copyOfRange(0, holdFrames)))
            val id = "release-$slug-m${Bellows.MODEL_VERSION}"
            val shortFile = "wav/$id-short.wav"
            val longFile = "wav/$id-long.wav"
            writeChecked(File(output, shortFile), short)
            writeChecked(File(output, longFile), long)
            releases += linkedMapOf(
                "id" to id, "voice" to voice.name, "title" to "${display(voice)} · release",
                "shortFile" to shortFile, "longFile" to longFile,
                "shortSha256" to sha(File(output, shortFile)), "longSha256" to sha(File(output, longFile)),
                "holdSeconds" to 6.0, "midi" to 60, "velocity" to .65f,
                "shortReleaseSeconds" to BellowsPadMaker.RELEASE_MIN_SECONDS,
                "longReleaseSeconds" to BellowsPadMaker.RELEASE_MAX_SECONDS,
                "shortDurationSeconds" to short.frameCount.toDouble() / RATE,
                "longDurationSeconds" to long.frameCount.toDouble() / RATE,
                "shortMetrics" to metrics(short), "longMetrics" to metrics(long),
                "commonGain" to 1, "adapter" to "BellowsPadMaker.preview",
            )
        }
        val manifest = linkedMapOf(
            "schemaVersion" to 1, "engine" to "BELLOWS", "model" to Bellows.MODEL_VERSION,
            "sampleRate" to RATE, "channels" to 1, "bitDepth" to 24,
            "provenance" to provenance,
            "playbackNotes" to listOf(
                "Program audio reads exported PCM24 WAVs and .instrument.json through InstrumentEngine and KeyHit.",
                "This is the JVM playback reference, not recorded phone or MPC hardware.",
                "Both sides use the same event frames, a .35 voice-bus level and shared output gain 1. No independent loudness matching.",
                "Native uses exact-note synthesis and continuous velocity timbre/level. Program uses nine pitch roots and three velocity layers with linear host velocity.",
                "All compared buses stay below .9, so the JVM soft clip and native hard clamp are inactive.",
                "WHEEL comparisons are melody-only. The tracking drone is disabled and no separate tonic bed is mixed.",
                "Phrase and chords repeat the existing musical demo's note/touch schedule. Boundaries isolates the edges of the exported zones and velocity windows.",
                "Release files use the actual MAKE INSTRUMENT preview: six-second hold, chosen release and ending silence.",
            ),
            "programs" to programs, "comparisons" to comparisons, "releaseComparisons" to releases,
            "ledger" to linkedMapOf(
                "verified" to "Production export, sidecar/WAV readback, KeyHit zone/layer resolution and InstrumentEngine playback paths.",
                "measured" to "Generated PCM peaks/RMS, exact event frames, sample hashes, loop-crossing counts and zero-ending release previews.",
                "proposed" to "Use these comparisons to decide whether sampled-instrument touch needs a later change. Source model 2 is unchanged.",
                "heard" to "Awaiting owner listening; no phone or MPC hardware was used.",
            ),
            "reproduce" to "./gradlew :shell:generateBellowsPlaybackAudition -PbellowsPlaybackOutput=/absolute/output/path",
        )
        File(output, "playback-manifest.json").writeText(Json.write(json(manifest)) + "\n")
        File(output, "PLAYBACK-README.txt").writeText("""
            BELLOWS sampled-instrument listening proof
            Four unchanged model-2 voices; twelve native/program comparisons and four release pairs.
            Compare native versus program at the same listening volume. Do not normalize the files separately.
            The program path reads the exported PCM24 sample package and sidecar, then uses the JVM player.
            The phone shares KeyHit's mapping but has a different output bus; these clips stay below either bus's clipping threshold.
            This is not an MPC emulator and no hardware playback was measured.
            WHEEL is melody-only here. Existing tonic-bed demos and accepted source auditions remain separate.
            playback-manifest.json records exact event frames, resolved zones/layers, gains, peaks, source hashes and scope.
            Reproduce: ./gradlew :shell:generateBellowsPlaybackAudition -PbellowsPlaybackOutput=/absolute/output/path
        """.trimIndent() + "\n")
        println("BELLOWS playback proof: ${comparisons.size} A/B pairs, ${releases.size} release pairs, ${File(output, "playback-manifest.json")}")
    }

    private fun performances(): List<Performance> {
        fun event(midi: Int, beat: Double, hold: Double, velocity: Float) = Event(
            midi, (beat * 60.0 / BPM * RATE).roundToInt(),
            ((beat + hold) * 60.0 / BPM * RATE).roundToInt(), velocity,
        )
        val phrase = listOf(60, 63, 67, 65, 63, 60, 58, 60).mapIndexed { i, midi ->
            event(midi, i * .5, .38, listOf(.35f, .65f, 1f, .65f)[i % 4])
        } + event(60, 4.5, 10.0, .65f)
        val chords = listOf(60, 63, 67, 70).mapIndexed { i, midi ->
            event(midi, 0.0, 10.0, listOf(.65f, .55f, .8f, .45f)[i])
        } + listOf(60, 65, 67).map { event(it, 12.0, 3.0, .65f) }
        val boundaries = listOf(42, 43, 84, 85).mapIndexed { i, velocity ->
            event(60, i * 1.5, .7, velocity / 127f)
        } + listOf(61, 62, 64, 65, 49, 50, 70, 71, 72).mapIndexed { i, midi ->
            event(midi, 6.5 + i * 1.0, .6, .65f)
        }
        return listOf(
            Performance("phrase", "a little air", "The same C-minor phrase and soft-to-firm touch as the native demo, followed by a 6.25-second C4 hold and .6-second release.", phrase),
            Performance("chord", "room to hold", "The same Cm7 and Csus4 notes/touch as the native demo. Long notes cross their sustain loops. WHEEL is melody-only on both sides, without the separate tonic bed.", chords),
            Performance("boundary", "touch and zone edges", "C4 at MIDI velocities 42, 43, 84 and 85 crosses both layer boundaries. Then notes 61/62, 64/65, 49/50 and 70/71 cross pitch-zone boundaries at the same .65 touch, ending on the C5 top note (72).", boundaries),
        )
    }

    internal fun loadProgram(sidecar: File): InstrumentEngine.Loaded {
        val instrument = InstrumentStore.read(sidecar)
        val samples = instrument.sampleFiles.associateWith { path ->
            val file = File(sidecar.parentFile, path)
            val snip = WavReader.read(file)
            require(snip.channels == 1 && snip.sampleRate == RATE)
            require(snip.samples.all { it.isFinite() && abs(it) <= 1f })
            val zone = instrument.zones.first { z -> z.playableLayers.any { it.sample == path } }
            val layer = zone.playableLayers.first { it.sample == path }
            val sampler = requireNotNull(WavReader.readSmpl(file))
            require(snip.frameCount.toLong() == layer.frameCount && sampler.rootNote == zone.rootNote)
            require(sampler.loop?.startFrame == layer.loopStartFrame && sampler.loop?.endFrameExclusive == layer.frameCount)
            snip.samples
        }
        return InstrumentEngine.Loaded(instrument, RATE, samples)
    }

    /** Split render blocks at event frames so timing does not depend on the engine's chunk size. */
    internal fun renderProgram(loaded: InstrumentEngine.Loaded, events: List<Event>, frames: Int): Snip {
        require(events.all { it.startFrame >= 0 && it.offFrame > it.startFrame && it.offFrame < frames })
        val engine = InstrumentEngine(loaded, RATE)
        val on = events.groupBy { it.startFrame }
        val off = events.groupBy { it.offFrame }
        val boundaries = (on.keys + off.keys + listOf(0, frames)).sorted()
        val output = FloatArray(frames)
        val block = FloatArray(512 * 2)
        for ((start, end) in boundaries.zipWithNext()) {
            off[start]?.forEach { engine.noteOff(it.midi) }
            on[start]?.forEach { require(engine.noteOn(it.midi, it.velocity)) }
            var frame = start
            while (frame < end) {
                val count = minOf(512, end - frame)
                engine.render(block, count)
                for (i in 0 until count) {
                    require(block[i * 2] == block[i * 2 + 1]) { "JVM reference ceased to be dual mono" }
                    output[frame + i] = block[i * 2]
                }
                frame += count
            }
        }
        require(engine.activeNotes.isEmpty()) { "Performance ended before every release finished" }
        return Snip(output, 1, RATE)
    }

    private fun renderNative(patch: BellowsPatch, events: List<Event>, frames: Int, release: Float,
                             cache: MutableMap<Pair<Int, Float>, KeyNote>): Snip {
        val output = FloatArray(frames)
        val melody = BellowsPadMaker.melodyPatch(patch)
        val releaseStep = 1f / (release * RATE).coerceAtLeast(1f)
        for (event in events) {
            val note = cache.getOrPut(event.midi to event.velocity) {
                BellowsKeys.note(melody.voice, melody.macros, event.midi, event.velocity, melody.gesture, melody.model)
            }
            val source = note.snip.samples
            val loop = note.loopStartFrame.toInt()
            require(loop > 0 && loop < source.size - 1)
            val period = source.size - loop
            var level = 1f
            for (frame in event.startFrame until frames) {
                if (frame >= event.offFrame) {
                    level -= releaseStep
                    if (level <= 0f) break
                }
                val local = frame - event.startFrame
                val index = if (local < source.size) local else loop + (local - loop) % period
                output[frame] += source[index] * InstrumentEngine.VOICE_LEVEL * level
            }
        }
        return Snip(output, 1, RATE)
    }

    private fun eventRow(loaded: InstrumentEngine.Loaded, event: Event): Map<String, Any?> {
        val zone = requireNotNull(loaded.instrument.zoneFor(event.midi))
        val midiVelocity = (event.velocity * 127).roundToInt().coerceIn(1, 127)
        val layer = requireNotNull(zone.layerFor(midiVelocity))
        val hit = requireNotNull(KeyHit.resolve(loaded.instrument, event.midi, event.velocity) { loaded.layerSamples[it]?.size?.toLong() })
        return linkedMapOf(
            "midi" to event.midi, "startFrame" to event.startFrame, "offFrame" to event.offFrame,
            "velocity" to event.velocity, "midiVelocity" to midiVelocity, "zoneRoot" to zone.rootNote,
            "layerVelocityWindow" to listOf(layer.velStart, layer.velEnd),
            "layerSourceVelocity" to BellowsPadMaker.VELOCITIES[zone.playableLayers.indexOf(layer)],
            "sampleFile" to hit.sampleFile, "pitchRatio" to hit.pitchRatio,
            "loopStartFrame" to hit.loopStartFrame, "sourceFrames" to hit.frames,
            "hostGain" to hit.gain,
        )
    }

    private fun writeChecked(file: File, snip: Snip) {
        require(snip.channels == 1 && snip.sampleRate == RATE)
        require(snip.samples.all { it.isFinite() && abs(it) < .9f } && snip.samples.last() == 0f)
        WavWriter.write(file, snip, WavWriter.BitDepth.PCM_24)
        val decoded = WavReader.read(file)
        require(decoded.frameCount == snip.frameCount && decoded.channels == 1 && decoded.sampleRate == RATE)
        require(decoded.samples.last() == 0f && decoded.samples.all { it.isFinite() && abs(it) < .9f })
        require(WavReader.readSmpl(file) == null) { "Performance WAV must play once rather than looping" }
        require(decoded.samples.indices.all { abs(decoded.samples[it] - snip.samples[it]) < 2e-7f })
    }

    private fun metrics(snip: Snip) = linkedMapOf(
        "peak" to snip.peak(), "rms" to sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size),
        "frames" to snip.frameCount, "finite" to true, "lastSample" to snip.samples.last(),
    )
    private fun display(voice: BellowsVoice) = voice.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercaseChar() }
    private fun relative(root: File, file: File) = file.relativeTo(root).invariantSeparatorsPath
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(Locale.ROOT, it) }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble())
        is Map<*, *> -> JsonValue.Obj(value.entries.associateTo(LinkedHashMap()) { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported JSON type ${value.javaClass.name}")
    }
    private fun repositoryRoot(): File = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, ".git").exists() }
    private fun git(repository: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repository.path) + arguments).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        require(process.waitFor() == 0) { "Cannot read source provenance: $output" }
        return output.trim()
    }
}
