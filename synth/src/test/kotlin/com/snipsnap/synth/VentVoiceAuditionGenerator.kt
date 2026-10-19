package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Twelve ordinary model-2 PadRecipes: three clean gestures, four controlled comparisons. */
object VentVoiceAuditionGenerator {
    private const val MIDI = 60
    private const val VELOCITY = .8f
    private const val SEED = 17
    private const val DEFAULT_SECONDS = 3.0
    private val defaultLength = (ln(DEFAULT_SECONDS / .18) / ln(8.0 / .18)).toFloat()
    private val voices = listOf(VentVoice.PULSE, VentVoice.COIL, VentVoice.PLUME)

    private data class Condition(
        val id: String, val title: String, val description: String,
        val velocity: Float = VELOCITY, val push: Float = .6f,
        val length: Float = defaultLength,
    )

    private val conditions = listOf(
        Condition("default", "Default", "The reference: velocity .8, PUSH .6 and a three-second finite note."),
        Condition("soft-touch", "Soft touch", "Velocity .25; all six controls, note and seed remain at the reference settings.", velocity = .25f),
        Condition("no-push", "No push", "PUSH 0; velocity .8 and every other setting remain at the reference values.", push = 0f),
        Condition("long-note", "Long note", "LENGTH 1: an eight-second finite note, with every other reference setting retained.", length = 1f),
    )

    private data class Evidence(val raw: FloatArray, val trace: List<VentTrace>)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/vent-voice-audition").absoluteFile
        root.mkdirs()
        val started = System.nanoTime()
        val clips = mutableListOf<Map<String, Any?>>()
        val reports = mutableListOf<Map<String, Any?>>()
        val evidence = mutableMapOf<String, Evidence>()

        for (voice in voices) for (condition in conditions) {
            val id = "${voice.name.lowercase()}-${condition.id}"
            val macros = linkedMapOf(
                "CHAOS" to .65f, "LEASH" to .55f, "VIEW" to .5f,
                "DAMP" to .25f, "PUSH" to condition.push, "LENGTH" to condition.length,
            )
            val title = "${voiceTitle(voice)} / ${condition.title}"
            val patch = VentPatch(title, voice, macros, MIDI, condition.velocity, SEED, Vent.CLEAN_MODEL)
            val began = System.nanoTime()
            val detailed = render(patch)
            val native = detailed.snip
            requireRender(id, detailed)
            check(if (condition.push == 0f) detailed.diagnostics.externalForceIntegral == 0.0
                else detailed.diagnostics.externalForceIntegral > 0.0) { "$id: unexpected added-force integral" }
            val expectedSeconds = if (condition.id == "long-note") 8.0 else DEFAULT_SECONDS
            check(abs(native.durationSeconds - expectedSeconds) < 2.0 / Dsp.RATE) { "$id: unexpected duration" }

            // Both the ordinary recipe and the detailed renderer must reproduce exact sample bits.
            val repeated = render(patch)
            check(sameBits(native, repeated.snip) && sameBits(detailed.raw, repeated.raw)) { "$id: direct rerender differs" }
            val recipe = PadRecipe(patch = patch)
            check(recipe.fx == null && !recipe.alias) { "$id: audition recipe must be dry" }
            val recipeText = recipe.toJsonText()
            val restored = PadRecipe.fromJsonText(recipeText)
            check(restored == recipe && restored.patch == patch) { "$id: ordinary recipe JSON roundtrip differs" }
            check(restored.toJsonText() == recipeText) { "$id: ordinary recipe JSON serialization differs" }
            check(sameBits(native, restored.render())) { "$id: ordinary PadRecipe replay differs" }
            val restoredPatch = restored.patch as VentPatch
            val replayed = render(restoredPatch)
            check(sameBits(native, replayed.snip) && sameBits(detailed.raw, replayed.raw)) { "$id: raw recipe replay differs" }
            check(sameTrace(detailed.trace, repeated.trace) && sameTrace(detailed.trace, replayed.trace)) { "$id: trajectory replay differs" }

            val matched = AuditionLevel.level(native)
            requireAudio(id, "matched", matched)
            val files = linkedMapOf(
                "native" to "audio/$id-native.wav",
                "matched" to "audio/$id-matched.wav",
                "recipe" to "recipes/$id.json",
            )
            WavWriter.write(File(root, files.getValue("native")), native, WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root, files.getValue("matched")), matched, WavWriter.BitDepth.PCM_24)
            File(root, files.getValue("recipe")).also { it.parentFile.mkdirs() }.writeText(recipeText)

            val duration = native.durationSeconds.toDouble()
            val centralPitch = pitch(native, maxOf(.05, (duration - .25) * .5))
            val clipMetrics = metrics(matched) + linkedMapOf(
                "nativePeak" to native.peak(), "nativeLoudness" to Loudness.of(native),
                "pitchHz" to centralPitch["hz"], "pitchCents" to centralPitch["errorCentsFromC4"],
                "pitchConfidence" to centralPitch["confidence"], "centroidHz" to centroid(native),
            )
            val windowSeconds = minOf(.5, duration * .1)
            val windows = listOf(.08, .22, .36, .50, .64).map { fraction ->
                val from = duration * fraction
                val window = Snip(native.samples.copyOfRange(
                    (from * Dsp.RATE).toInt(), ((from + windowSeconds) * Dsp.RATE).toInt(),
                ), 1, Dsp.RATE)
                linkedMapOf(
                    "fromSeconds" to from, "windowSeconds" to windowSeconds,
                    "native" to metrics(window), "centroidHz" to centroid(window),
                    "pitch" to pitch(native, from + (windowSeconds - .25).coerceAtLeast(0.0) * .5),
                )
            }
            val hashes = linkedMapOf<String, Any?>(
                "rawSamplesSha256" to samplesHash(detailed.raw),
                "nativeSamplesSha256" to samplesHash(native.samples),
                "matchedSamplesSha256" to samplesHash(matched.samples),
                "trajectorySha256" to traceHash(detailed.trace),
                "nativeWavSha256" to fileHash(File(root, files.getValue("native"))),
                "matchedWavSha256" to fileHash(File(root, files.getValue("matched"))),
                "recipeSha256" to fileHash(File(root, files.getValue("recipe"))),
            )
            reports += linkedMapOf(
                "id" to id, "voice" to voice.name, "condition" to condition.id,
                "model" to Vent.CLEAN_MODEL, "midi" to MIDI, "velocity" to condition.velocity,
                "seed" to SEED, "macros" to macros,
                "deterministic" to true, "recipeRoundtrip" to true,
                "rawDeterministic" to true, "trajectoryDeterministic" to true,
                "native" to metrics(native), "matched" to metrics(matched),
                "matchedGain" to matched.peak() / native.peak(),
                "pitchEstimator" to centralPitch, "dynamicWindows" to windows,
                "dynamicWindowsNote" to "Five windows across the early and middle passage. The final window may contain a voice's finite envelope or release. Pitch uses a local 250 ms window; centroid is power weighted over overlapping 4096-point Hann FFTs. These describe change, not musical acceptance.",
                "engine" to engineDiagnostics(detailed.diagnostics), "hashes" to hashes,
                "renderAndReplayMillis" to (System.nanoTime() - began) / 1e6,
            )
            clips += linkedMapOf(
                "id" to id, "group" to voice.name, "condition" to condition.id,
                "title" to title, "description" to condition.description,
                "model" to Vent.CLEAN_MODEL, "midi" to MIDI, "note" to "C4",
                "velocity" to condition.velocity, "seed" to SEED, "macros" to macros,
                "durationSeconds" to duration, "files" to files, "metrics" to clipMetrics,
                "waveform" to waveform(matched.samples), "hashes" to hashes,
            )
            evidence[id] = Evidence(detailed.raw, detailed.trace)
            println("VENT clean voices ${clips.size}/12: $id — native peak ${native.peak()}, pitch ${centralPitch["hz"] ?: "unresolved"} Hz")
        }
        check(clips.size == 12 && clips.map { it["id"] }.distinct().size == 12) { "Expected twelve unique clips" }

        val pushComparisons = voices.map { voice ->
            val id = voice.name.lowercase()
            val pushed = evidence.getValue("$id-default")
            val unpushed = evidence.getValue("$id-no-push")
            check(pushed.trace.size == unpushed.trace.size && !sameTrace(pushed.trace, unpushed.trace)) {
                "$id: PUSH must alter the actual recorded trajectory"
            }
            check(!sameBits(pushed.raw, unpushed.raw)) { "$id: PUSH did not change the raw readout" }
            linkedMapOf(
                "voice" to voice.name, "reference" to "$id-default", "comparison" to "$id-no-push",
                "trajectoryChanged" to true, "rawAudioChanged" to true,
                "rawRelativeRmsDifference" to relativeRmsDifference(pushed.raw, unpushed.raw),
                "traceRmsDifference" to traceRmsDifference(pushed.trace, unpushed.trace),
            )
        }
        val sourceRoot = File(git("rev-parse", "--show-toplevel"))
        val sourceFiles = listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/Vent.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/VentModel2.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/VentPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/VentVoiceAuditionGenerator.kt",
        ).associateWith { fileHash(File(sourceRoot, it)) }
        val provenance = linkedMapOf<String, Any?>(
            "sourceCommit" to git("rev-parse", "HEAD"),
            "sourceTreeDirty" to git("status", "--porcelain").isNotEmpty(),
            "sourceFilesSha256" to sourceFiles, "generatedAt" to Instant.now().toString(),
        )
        val summary = linkedMapOf<String, Any?>(
            "clipCount" to clips.size, "finite" to true, "unclipped" to true,
            "deterministic" to true, "recipeRoundtrip" to true,
            "rawDeterministic" to true, "trajectoryDeterministic" to true,
            "integrationFailures" to 0, "pushChangesTrajectory" to true,
            "acceptance" to "Awaiting owner listening to these three new intended characters. 'Sounds solid' approved the preceding isolation foundation, not PULSE, COIL or PLUME.",
            "sourceNote" to "All three model-2 voices use unsaturated linear state readouts. Their intended differences arise from projection motion and forces inside the trajectory, rather than a saturation or filter ladder.",
            "nativeNote" to "Native WAVs contain the engine's linear readout, conditioning, finite envelope, antialias decimation and shared melodic level convention. They are not unconditioned state data.",
            "matchedNote" to "Matched WAVs apply AuditionLevel.level: loudest 200 ms RMS target .03, with a .99 peak guard and one scalar gain per clip. Native already uses the engine's shared melodic level convention; neither mode preserves pre-level velocity gain. Soft touch compares velocity's influence on trajectory and timbre.",
            "pitchNote" to "Local estimates do not guarantee pitch lock or identify every overtone. Null means no confident local period was found.",
            "recipeNote" to "Every saved recipe is an ordinary dry PadRecipe containing a model-2 VentPatch, not an isolation experiment specification.",
            "measurementNote" to "Numeric checks prove finite unclipped samples, stable integration and exact local replay. Measured differences do not establish audibility, useful character, alias freedom or musical acceptance.",
        )
        val manifest = linkedMapOf<String, Any?>(
            "engine" to "VENT", "model" to Vent.CLEAN_MODEL,
            "title" to "VENT / Clean voices audition",
            "description" to "Twelve dry finite passages: three new intended gestures, compared at a common note, orbit setting, readout damping and seed. No effects or engine saturation.",
            "note" to "C4", "midi" to MIDI, "velocity" to VELOCITY, "seed" to SEED,
            "sampleRate" to Dsp.RATE, "channels" to 1,
            "readout" to linkedMapOf(
                "linear" to true, "saturation" to false, "zCompression" to false,
                "normalization" to "shared fixed XYZ reference scales",
                "xScale" to 12.0, "yScale" to 12.0, "zScale" to 60.0,
                "outputGain" to .18, "filter" to "common linear two-pole DAMP mapping",
                "dcCutoffHz" to 12.0, "attackSeconds" to .006,
                "releaseStartFraction" to .72, "velocityExponent" to .35,
            ),
            "commonMacros" to linkedMapOf(
                "CHAOS" to .65f, "LEASH" to .55f, "VIEW" to .5f,
                "DAMP" to .25f, "PUSH" to .6f, "LENGTH" to defaultLength,
            ),
            "groups" to voices.map { voice -> linkedMapOf(
                "id" to voice.name, "title" to voiceTitle(voice), "description" to voiceDescription(voice),
            ) },
            "conditions" to conditions.map { linkedMapOf(
                "id" to it.id, "title" to it.title, "description" to it.description,
                "velocity" to it.velocity, "push" to it.push, "length" to it.length,
            ) },
            "clips" to clips, "diagnostics" to summary,
        ) + provenance
        File(root, "manifest.json").writeText(Json.write(json(manifest)))
        File(root, "diagnostics.json").writeText(Json.write(json(linkedMapOf(
            "engine" to "VENT", "model" to Vent.CLEAN_MODEL, "summary" to summary,
            "clips" to reports, "pushComparisons" to pushComparisons,
            "generationSeconds" to (System.nanoTime() - started) / 1e9,
        ) + provenance)))
        writeZip(root, clips)
        println("VENT wrote twelve clean voice clips, native/matched PCM24 WAVs, ordinary recipes and measurements to ${root.path}")
    }

    private fun render(patch: VentPatch): VentRender =
        Vent.renderDetailed(patch.voice, patch.macros, patch.midi, patch.velocity, patch.seed, patch.model)

    private fun requireRender(id: String, render: VentRender) {
        requireAudio(id, "native", render.snip)
        check(render.raw.size == render.snip.samples.size && render.raw.all { it.isFinite() && abs(it) < 1f }) { "$id: invalid internal raw audio" }
        val d = render.diagnostics
        check(d.signalPath == "clean-linear") { "$id: unexpected signal path ${d.signalPath}" }
        check(d.integrationFailures == 0) { "$id: emergency integration resets occurred" }
        check(d.maxState.isFinite() && d.maxState <= 256.0) { "$id: invalid orbit state" }
        check(d.modelTimeStep.isFinite() && d.modelTimeStep > 0.0 && d.modelTimeStep <= .0200001) { "$id: invalid integration step" }
        check(render.trace.isNotEmpty() && render.trace.all {
            it.seconds.isFinite() && it.x.isFinite() && it.y.isFinite() && it.z.isFinite()
        }) { "$id: missing or nonfinite recorded trajectory" }
    }

    private fun requireAudio(id: String, label: String, snip: Snip) {
        check(snip.channels == 1 && snip.sampleRate == Dsp.RATE) { "$id: unexpected $label format" }
        check(snip.samples.isNotEmpty() && snip.samples.all { it.isFinite() }) { "$id: nonfinite or empty $label audio" }
        check(snip.peak() < 1f) { "$id: clipped $label audio (${snip.peak()})" }
        check(snip.peak() > 1e-6f) { "$id: silent $label audio" }
    }

    private fun sameBits(a: Snip, b: Snip): Boolean =
        a.channels == b.channels && a.sampleRate == b.sampleRate && sameBits(a.samples, b.samples)
    private fun sameBits(a: FloatArray, b: FloatArray): Boolean =
        a.size == b.size && a.indices.all { a[it].toRawBits() == b[it].toRawBits() }
    private fun sameTrace(a: List<VentTrace>, b: List<VentTrace>): Boolean =
        a.size == b.size && a.indices.all { i ->
            val x = a[i]; val y = b[i]
            x.seconds.toRawBits() == y.seconds.toRawBits() && x.x.toRawBits() == y.x.toRawBits() &&
                x.y.toRawBits() == y.y.toRawBits() && x.z.toRawBits() == y.z.toRawBits()
        }

    private fun metrics(snip: Snip): Map<String, Any?> = linkedMapOf(
        "peak" to snip.peak(),
        "rms" to sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size),
        "dc" to snip.samples.sumOf { it.toDouble() } / snip.samples.size,
        "loudness" to Loudness.of(snip),
    )

    private fun pitch(snip: Snip, from: Double): Map<String, Any?> {
        val found = Pitch.detect(snip, from.toFloat(), .25f)
        val targetHz = 440.0 * 2.0.pow((MIDI - 69) / 12.0)
        return linkedMapOf(
            "method" to "SnipSnap Pitch.detect: autocorrelation with spectral refinement",
            "fromSeconds" to from, "windowSeconds" to .25,
            "hz" to found?.hz, "confidence" to found?.confidence,
            "errorCentsFromC4" to found?.let { 1200 * ln(it.hz / targetHz) / ln(2.0) },
            "note" to "A local estimate, not a pitch-lock guarantee; null means no confident period was found.",
        )
    }

    /** Identical 4096-point Hann power analysis for every measured passage. */
    private fun centroid(snip: Snip): Double? {
        val size = 4096
        var total = 0.0
        var weighted = 0.0
        for (from in 0..(snip.samples.size - size) step size / 2) {
            val magnitudes = Fft.magnitudeSpectrum(snip.samples.copyOfRange(from, from + size), size)
            for (bin in 1 until magnitudes.size) {
                val power = magnitudes[bin].toDouble().pow(2)
                total += power
                weighted += power * Fft.binToHz(bin, size, snip.sampleRate)
            }
        }
        return if (total > 1e-20) weighted / total else null
    }

    private fun waveform(samples: FloatArray): List<Double> {
        val peak = samples.maxOf { abs(it) }.coerceAtLeast(1e-9f)
        val count = minOf(128, samples.size)
        return (0 until count).map { bin ->
            val from = bin * samples.size / count
            val to = (bin + 1) * samples.size / count
            (from until to).maxOf { abs(samples[it]) }.toDouble() / peak
        }
    }

    private fun relativeRmsDifference(a: FloatArray, b: FloatArray): Double {
        check(a.size == b.size)
        var energy = 0.0
        var delta = 0.0
        for (i in a.indices) {
            energy += a[i].toDouble().pow(2)
            delta += (a[i].toDouble() - b[i]).pow(2)
        }
        return sqrt(delta / energy.coerceAtLeast(1e-30))
    }

    private fun traceRmsDifference(a: List<VentTrace>, b: List<VentTrace>): Double {
        check(a.size == b.size)
        return sqrt(a.indices.sumOf { i ->
            (a[i].x - b[i].x).pow(2) + (a[i].y - b[i].y).pow(2) + (a[i].z - b[i].z).pow(2)
        } / (3.0 * a.size))
    }

    private fun voiceTitle(voice: VentVoice): String = when (voice) {
        VentVoice.PULSE -> "Pulse"
        VentVoice.COIL -> "Coil"
        VentVoice.PLUME -> "Plume"
        else -> error("Not a clean audition voice: $voice")
    }

    private fun voiceDescription(voice: VentVoice): String = when (voice) {
        VentVoice.PULSE -> "Intended gesture: a struck orbit. Listen for the onset and the motion that follows it."
        VentVoice.COIL -> "Intended gesture: a moving view through X/Y/Z. Listen for change within the passage."
        VentVoice.PLUME -> "Intended gesture: pressure sustained inside the orbit. Listen for how the body develops."
        else -> error("Not a clean audition voice: $voice")
    }

    private fun engineDiagnostics(d: VentDiagnostics): Map<String, Any?> = linkedMapOf(
        "rawPeak" to d.rawPeak, "rawRms" to d.rawRms, "rawDc" to d.rawDc,
        "maxState" to d.maxState, "stateRms" to d.stateRms,
        "integrationFailures" to d.integrationFailures, "leashResets" to d.leashResets,
        "measuredOrbitPeriod" to d.measuredOrbitPeriod, "modelTimeStep" to d.modelTimeStep,
        "signalPath" to d.signalPath,
        "externalForceIntegral" to d.externalForceIntegral, "projectionSpan" to d.projectionSpan,
        "forceNote" to "Model-time integral of added gesture-force magnitude, excluding LEASH. It is exactly zero in No push; it is not a measurement of physical work or musical quality.",
        "rawNote" to "Internal linear-readout audio after conditioning and antialias decimation, before shared melodic leveling. Native WAVs contain the shared-level output.",
        "resetNote" to "leashResets count intended note-cycle anchoring; integrationFailures count emergency resets and must be zero.",
    )

    private fun samplesHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putInt(sample.toRawBits())
        return digest(bytes.array())
    }

    private fun traceHash(trace: List<VentTrace>): String {
        val bytes = ByteBuffer.allocate(trace.size * 32).order(ByteOrder.LITTLE_ENDIAN)
        for (point in trace) {
            bytes.putLong(point.seconds.toRawBits()).putLong(point.x.toRawBits())
                .putLong(point.y.toRawBits()).putLong(point.z.toRawBits())
        }
        return digest(bytes.array())
    }

    private fun fileHash(file: File): String = digest(file.readBytes())
    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "Unable to record source provenance: $result" }
        return result
    }

    /** Explicit file list keeps the new pack separate from earlier audition assets and archives. */
    private fun writeZip(root: File, clips: List<Map<String, Any?>>) {
        val assets = mutableListOf("manifest.json", "diagnostics.json")
        for (clip in clips) assets += (clip.getValue("files") as Map<*, *>).values.map { it as String }
        ZipOutputStream(File(root, "voices.zip").outputStream().buffered()).use { zip ->
            for (name in assets.sorted()) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                File(root, name).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is Boolean -> JsonValue.Bool(value)
        is Number -> {
            check(value.toDouble().isFinite()) { "Nonfinite diagnostic value" }
            JsonValue.Num(value.toDouble())
        }
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("Unsupported audition JSON value ${value.javaClass.name}")
    }
}
