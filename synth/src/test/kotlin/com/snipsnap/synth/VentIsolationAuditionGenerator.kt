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

/** A four-cell experiment: CHAOS × LEASH, with one unsaturated linear readout. */
object VentIsolationAuditionGenerator {
    private const val EXPERIMENT = "VENT_ISOLATION"
    private const val VERSION = 1
    private const val MIDI = 60
    private const val VELOCITY = .8f
    private const val SEED = 17
    private const val SECONDS = 8.0

    private data class Condition(
        val id: String, val title: String, val description: String,
        val chaos: Float, val leash: Float,
    )

    private val conditions = listOf(
        Condition("low-anchored", "Low CHAOS · Strong LEASH", "CHAOS .15, LEASH .95: the restrained reference.", .15f, .95f),
        Condition("high-anchored", "High CHAOS · Strong LEASH", "CHAOS .8, LEASH .95: raise CHAOS while keeping the LEASH and readout fixed.", .8f, .95f),
        Condition("low-free", "Low CHAOS · Zero LEASH", "CHAOS .15, LEASH 0: remove periodic anchoring; the readout stays fixed.", .15f, 0f),
        Condition("high-free", "High CHAOS · Zero LEASH", "CHAOS .8, LEASH 0: high CHAOS with no periodic anchoring; the readout stays fixed.", .8f, 0f),
    )

    /** This diagnostic specification deliberately cannot be mistaken for a normal VentPatch. */
    private data class IsolationRecipe(
        val chaos: Float, val leash: Float, val midi: Int,
        val velocity: Float, val seed: Int, val seconds: Double,
        val experiment: String = EXPERIMENT, val version: Int = VERSION,
        val engineModel: Int = Vent.MODEL,
        val readout: Map<String, Any?> = readoutSpec(),
    ) {
        fun render(): VentRender {
            check(experiment == EXPERIMENT && version == VERSION && engineModel == Vent.MODEL) {
                "Unsupported VENT isolation recipe"
            }
            // These are fixed experiment settings, not arbitrary macros accepted by the engine API.
            check(json(readout) == json(readoutSpec())) {
                "Isolation recipe readout differs from this experiment implementation"
            }
            return Vent.renderIsolation(chaos, leash, midi, velocity, seed, seconds)
        }

        fun toJsonText(): String = Json.write(json(linkedMapOf(
            "experiment" to experiment, "version" to version, "engineModel" to engineModel,
            "chaos" to chaos, "leash" to leash, "midi" to midi, "velocity" to velocity,
            "seed" to seed, "seconds" to seconds, "readout" to readout,
        )))

        companion object {
            fun fromJsonText(text: String): IsolationRecipe {
                val o = Json.parse(text).obj()
                check(o.keys == setOf("experiment", "version", "engineModel", "chaos", "leash", "midi", "velocity", "seed", "seconds", "readout")) {
                    "Unexpected isolation recipe fields"
                }
                val readout = o.getValue("readout").obj()
                check(readout.keys == readoutSpec().keys) { "Unexpected isolation readout fields" }
                return IsolationRecipe(
                    chaos = o.getValue("chaos").num().toFloat(),
                    leash = o.getValue("leash").num().toFloat(),
                    midi = o.getValue("midi").int(),
                    velocity = o.getValue("velocity").num().toFloat(),
                    seed = o.getValue("seed").int(),
                    seconds = o.getValue("seconds").num(),
                    experiment = o.getValue("experiment").str(),
                    version = o.getValue("version").int(),
                    engineModel = o.getValue("engineModel").int(),
                    readout = readout.mapValues { (_, value) -> unjson(value) },
                )
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/vent-isolation-audition").absoluteFile
        root.mkdirs()
        val started = System.nanoTime()
        val clips = mutableListOf<Map<String, Any?>>()
        val reports = mutableListOf<Map<String, Any?>>()

        for (condition in conditions) {
            val id = condition.id
            val recipe = IsolationRecipe(condition.chaos, condition.leash, MIDI, VELOCITY, SEED, SECONDS)
            val began = System.nanoTime()
            val detailed = recipe.render()
            val native = detailed.snip
            requireAudio(id, "native", native)
            check(native.frameCount == (SECONDS * Dsp.RATE).toInt()) { "$id: unexpected isolation duration" }
            check(detailed.raw.all { it.isFinite() && abs(it) < 1f }) { "$id: invalid internal raw audio" }
            check(detailed.diagnostics.integrationFailures == 0) { "$id: emergency integration resets occurred" }
            check(detailed.diagnostics.signalPath == "isolation-linear") { "$id: unexpected synthesis path" }
            check(detailed.diagnostics.maxState.isFinite() && detailed.diagnostics.maxState <= 256.0) { "$id: invalid orbit state" }
            check(detailed.diagnostics.modelTimeStep > 0.0 && detailed.diagnostics.modelTimeStep <= .0200001) { "$id: invalid integration step" }

            // Compare both final and pre-level sample bits, directly and from the saved diagnostic recipe.
            val repeated = recipe.render()
            check(sameBits(native, repeated.snip) && sameBits(detailed.raw, repeated.raw)) { "$id: engine rerender differs" }
            val recipeText = recipe.toJsonText()
            val restored = IsolationRecipe.fromJsonText(recipeText)
            check(restored.toJsonText() == recipeText) { "$id: diagnostic recipe JSON roundtrip differs" }
            val replayed = restored.render()
            check(sameBits(native, replayed.snip) && sameBits(detailed.raw, replayed.raw)) { "$id: diagnostic recipe replay differs" }

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

            val centralPitch = pitch(native, 3.875)
            val metrics = metrics(matched) + linkedMapOf(
                "nativePeak" to native.peak(), "nativeLoudness" to Loudness.of(native),
                "pitchHz" to centralPitch["hz"], "pitchCents" to centralPitch["errorCentsFromC4"],
                "pitchConfidence" to centralPitch["confidence"],
            )
            val windows = listOf(.5, 1.5, 2.5, 3.5, 4.5, 5.25).map { from ->
                val window = Snip(native.samples.copyOfRange((from * Dsp.RATE).toInt(), ((from + .5) * Dsp.RATE).toInt()), 1, Dsp.RATE)
                linkedMapOf(
                    "fromSeconds" to from, "windowSeconds" to .5,
                    "native" to metrics(window), "centroidHz" to centroid(window),
                    "pitch" to pitch(native, from + .125),
                )
            }
            val hashes = linkedMapOf<String, Any?>(
                "rawSamplesSha256" to samplesHash(detailed.raw),
                "nativeSamplesSha256" to samplesHash(native.samples),
                "matchedSamplesSha256" to samplesHash(matched.samples),
                "nativeWavSha256" to fileHash(File(root, files.getValue("native"))),
                "matchedWavSha256" to fileHash(File(root, files.getValue("matched"))),
                "recipeSha256" to fileHash(File(root, files.getValue("recipe"))),
            )
            reports += linkedMapOf(
                "id" to id, "experiment" to EXPERIMENT,
                "chaos" to condition.chaos, "leash" to condition.leash,
                "deterministic" to true, "recipeRoundtrip" to true, "rawDeterministic" to true,
                "native" to metrics(native), "matched" to metrics(matched),
                "matchedGain" to (matched.peak() / native.peak()),
                "pitchEstimator" to centralPitch, "bodyWindows" to windows,
                "bodyWindowsNote" to "Six 500 ms windows before the release. Pitch uses each window's central 250 ms; centroid is power weighted over overlapping 4096-point Hann FFTs. These describe change over time, not musical acceptance.",
                "engine" to engineDiagnostics(detailed.diagnostics), "hashes" to hashes,
                "renderAndReplayMillis" to (System.nanoTime() - began) / 1e6,
            )
            clips += linkedMapOf(
                "id" to id, "group" to "ISOLATION", "condition" to condition.id,
                "title" to condition.title, "description" to condition.description,
                "midi" to MIDI, "note" to "C4", "velocity" to VELOCITY, "seed" to SEED,
                "macros" to linkedMapOf("CHAOS" to condition.chaos, "LEASH" to condition.leash, "PUSH" to 0),
                "durationSeconds" to native.durationSeconds.toDouble(),
                "files" to files, "metrics" to metrics, "waveform" to waveform(matched.samples),
                "hashes" to hashes,
            )
            println("VENT isolation ${clips.size}/4: $id — native peak ${native.peak()}, pitch ${centralPitch["hz"] ?: "unresolved"} Hz")
        }
        check(clips.size == 4 && clips.map { it["id"] }.distinct().size == 4) { "Expected four unique isolation clips" }

        val summary = linkedMapOf<String, Any?>(
            "clipCount" to clips.size, "finite" to true, "unclipped" to true,
            "deterministic" to true, "recipeRoundtrip" to true, "rawDeterministic" to true,
            "integrationFailures" to 0,
            "acceptance" to "Awaiting owner listening; numerical checks do not establish musical quality.",
            "nativeNote" to "Native WAVs use the shared melodic level convention after the linear unsaturated readout, fixed filter, DC blocking, envelope and antialias decimation. Native does not mean unconditioned raw state.",
            "matchedNote" to "Matched WAVs apply AuditionLevel.level: loudest 200 ms RMS target .03, with a .99 peak guard and one scalar gain per clip.",
            "pitchNote" to "Local pitch estimates do not guarantee pitch lock. Zero LEASH leaves MIDI as a traversal-speed reference.",
            "envelopeNote" to "Every clip has the same 6 ms attack and raised-cosine release from 5.76 to 8 seconds. Body measurements precede the release.",
            "recipeNote" to "Standalone VENT_ISOLATION experiment specifications replay this internal diagnostic path, not ordinary PadRecipe/VentPatch synthesis.",
        )
        val provenance = linkedMapOf<String, Any?>(
            "sourceCommit" to git("rev-parse", "HEAD"),
            "sourceTreeDirty" to git("status", "--porcelain").isNotEmpty(),
            "generatedAt" to Instant.now().toString(),
        )
        val manifest = linkedMapOf<String, Any?>(
            "engine" to "VENT", "model" to Vent.MODEL, "experiment" to EXPERIMENT, "experimentVersion" to VERSION,
            "title" to "VENT / Isolation audition",
            "description" to "Four eight-second finite sounds: low/high CHAOS × strong/zero LEASH. The linear projection is identical, saturation is removed, the 10 kHz filter is fixed and PUSH is zero.",
            "note" to "C4", "midi" to MIDI, "velocity" to VELOCITY, "seed" to SEED,
            "sampleRate" to Dsp.RATE, "channels" to 1, "readout" to readoutSpec(),
            "groups" to listOf(linkedMapOf(
                "id" to "ISOLATION", "title" to "Isolation", "description" to "One fixed linear readout; compare CHAOS at each LEASH setting, then compare LEASH at each CHAOS setting.",
            )),
            "conditions" to conditions.map { linkedMapOf(
                "id" to it.id, "title" to it.title, "description" to it.description,
                "chaos" to it.chaos, "leash" to it.leash,
            ) },
            "clips" to clips, "diagnostics" to summary,
        ) + provenance
        File(root, "manifest.json").writeText(Json.write(json(manifest)))
        File(root, "diagnostics.json").writeText(Json.write(json(linkedMapOf(
            "engine" to "VENT", "model" to Vent.MODEL, "experiment" to EXPERIMENT,
            "experimentVersion" to VERSION, "readout" to readoutSpec(), "summary" to summary,
            "clips" to reports, "generationSeconds" to (System.nanoTime() - started) / 1e9,
        ) + provenance)))
        writeZip(root, clips)
        println("VENT wrote four isolation clips, native/matched PCM24 WAVs, diagnostic recipes and measurements to ${root.path}")
    }

    private fun readoutSpec(): Map<String, Any?> = linkedMapOf(
        "linear" to true,
        "projection" to linkedMapOf(
            "x" to VentIsolationSpec.X_WEIGHT, "y" to VentIsolationSpec.Y_WEIGHT,
            "z" to VentIsolationSpec.Z_WEIGHT, "normalization" to "fixed-coefficients",
        ),
        "outputGain" to VentIsolationSpec.GAIN,
        "cutoffHz" to VentIsolationSpec.FILTER_CUTOFF_HZ,
        "dcCutoffHz" to VentIsolationSpec.DC_CUTOFF_HZ,
        "push" to 0, "saturation" to false, "zCompression" to false,
        "attackSeconds" to VentIsolationSpec.ATTACK_SECONDS,
        "releaseStartFraction" to VentIsolationSpec.RELEASE_START_FRACTION,
        "velocityExponent" to VentIsolationSpec.VELOCITY_EXPONENT,
        "seedVoice" to VentIsolationSpec.SEED_VOICE.name,
        "nativeLevelConvention" to "Dsp.MELODIC_LOUDNESS_TARGET",
    )

    private fun requireAudio(id: String, label: String, snip: Snip) {
        check(snip.channels == 1 && snip.sampleRate == Dsp.RATE) { "$id: unexpected $label format" }
        check(snip.samples.isNotEmpty() && snip.samples.all { it.isFinite() }) { "$id: nonfinite or empty $label audio" }
        check(snip.peak() < 1f) { "$id: clipped $label audio (${snip.peak()})" }
        check(snip.peak() > 1e-6f) { "$id: silent $label audio" }
    }

    private fun sameBits(a: Snip, b: Snip): Boolean = a.channels == b.channels && a.sampleRate == b.sampleRate && sameBits(a.samples, b.samples)
    private fun sameBits(a: FloatArray, b: FloatArray): Boolean = a.size == b.size && a.indices.all { a[it].toRawBits() == b[it].toRawBits() }

    private fun metrics(snip: Snip): Map<String, Any?> {
        val samples = snip.samples
        val mean = samples.sumOf { it.toDouble() } / samples.size
        val rms = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
        return linkedMapOf("peak" to snip.peak(), "rms" to rms, "dc" to mean, "loudness" to Loudness.of(snip))
    }

    private fun pitch(snip: Snip, from: Double): Map<String, Any?> {
        val pitch = Pitch.detect(snip, from.toFloat(), .25f)
        val targetHz = 440.0 * 2.0.pow((MIDI - 69) / 12.0)
        return linkedMapOf(
            "method" to "SnipSnap Pitch.detect: autocorrelation with spectral refinement",
            "fromSeconds" to from, "windowSeconds" to .25,
            "hz" to pitch?.hz, "confidence" to pitch?.confidence,
            "errorCentsFromC4" to pitch?.let { 1200 * ln(it.hz / targetHz) / ln(2.0) },
            "note" to "A local estimate, not a pitch-lock guarantee; null means no confident period was found.",
        )
    }

    /** Aggregate power-weighted brightness; the same 4096-point Hann analysis for every body window. */
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

    private fun engineDiagnostics(d: VentDiagnostics): Map<String, Any?> = linkedMapOf(
        "rawPeak" to d.rawPeak, "rawRms" to d.rawRms, "rawDc" to d.rawDc,
        "maxState" to d.maxState, "stateRms" to d.stateRms,
        "integrationFailures" to d.integrationFailures, "leashResets" to d.leashResets,
        "measuredOrbitPeriod" to d.measuredOrbitPeriod, "modelTimeStep" to d.modelTimeStep,
        "signalPath" to d.signalPath,
        "rawNote" to "Internal linear-readout diagnostics before shared melodic leveling. Native WAVs contain the conditioned, shared-level output.",
        "resetNote" to "leashResets count intended note-cycle anchoring; integrationFailures count emergency resets and must be zero.",
    )

    private fun samplesHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putInt(sample.toRawBits())
        return digest(bytes.array())
    }
    private fun fileHash(file: File) = digest(file.readBytes())
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "Unable to record source provenance: $result" }
        return result
    }

    /** Keep this experiment independent of the original twelve-clip pack and exclude unrelated stale assets. */
    private fun writeZip(root: File, clips: List<Map<String, Any?>>) {
        val assets = mutableListOf("manifest.json", "diagnostics.json")
        for (clip in clips) {
            val files = clip.getValue("files") as Map<*, *>
            assets += files.values.map { it as String }
        }
        ZipOutputStream(File(root, "isolation.zip").outputStream().buffered()).use { zip ->
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
        else -> error("Unsupported isolation JSON value ${value.javaClass.name}")
    }

    private fun unjson(value: JsonValue): Any? = when (value) {
        is JsonValue.Null -> null
        is JsonValue.Bool -> value.value
        is JsonValue.Num -> value.value
        is JsonValue.Str -> value.value
        is JsonValue.Obj -> value.entries.mapValues { (_, item) -> unjson(item) }
        is JsonValue.Arr -> value.items.map { unjson(it) }
    }
}
