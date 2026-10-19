package com.snipsnap.synth

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

/** Twelve actual JVM-engine renders: three projections, four common CHAOS/LEASH conditions. */
object VentAuditionGenerator {
    private const val MIDI = 60
    private const val VELOCITY = .8f
    private const val SEED = 17
    private const val MODEL = 1
    private val voices = listOf(VentVoice.ROUND, VentVoice.WIRE, VentVoice.TEAR)

    private data class Condition(
        val id: String, val title: String, val description: String,
        val chaos: Float, val leash: Float,
    )

    private val conditions = listOf(
        Condition("anchored", "Anchored", "Low CHAOS, strong LEASH: the restrained reference.", .15f, .95f),
        Condition("chaotic", "Chaotic", "High CHAOS, strong LEASH: keep the anchoring while changing the orbit.", .8f, .95f),
        Condition("loose", "Loose", "High CHAOS, partial LEASH: relax the periodic anchoring of the trajectory.", .8f, .4f),
        Condition("free", "Free", "High CHAOS, zero LEASH: no periodic anchoring; MIDI sets traversal speed.", .8f, 0f),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/vent-audition").absoluteFile
        root.mkdirs()
        val started = System.nanoTime()
        val clips = mutableListOf<Map<String, Any?>>()
        val reports = mutableListOf<Map<String, Any?>>()

        for (voice in voices) for (condition in conditions) {
            val id = "${voice.name.lowercase()}-${condition.id}"
            val macros = Vent.defaultsFor(voice) + mapOf(
                "CHAOS" to condition.chaos, "LEASH" to condition.leash, "LENGTH" to .7f,
            )
            val title = "${voiceTitle(voice)} / ${condition.title}"
            val patch = VentPatch(title, voice, macros, MIDI, VELOCITY, SEED, MODEL)
            val began = System.nanoTime()
            val detailed = Vent.renderDetailed(voice, macros, MIDI, VELOCITY, SEED, MODEL)
            val native = detailed.snip
            requireAudio(id, "native", native)
            check(detailed.raw.all { it.isFinite() && abs(it) < 1f }) { "$id: invalid internal raw audio" }
            check(detailed.diagnostics.integrationFailures == 0) { "$id: emergency integration resets occurred" }
            check(detailed.diagnostics.maxState.isFinite() && detailed.diagnostics.maxState <= 256.0) { "$id: invalid orbit state" }
            check(detailed.diagnostics.modelTimeStep > 0.0 && detailed.diagnostics.modelTimeStep <= .0200001) { "$id: invalid integration step" }

            // A repeat and a serialized PadRecipe must recreate every floating-point sample.
            val repeated = patch.render()
            check(sameBits(native, repeated)) { "$id: engine rerender differs" }
            val recipe = PadRecipe(patch = patch)
            val recipeText = recipe.toJsonText()
            val restored = PadRecipe.fromJsonText(recipeText)
            check(restored.patch == patch) { "$id: patch JSON roundtrip differs" }
            check(sameBits(native, restored.render())) { "$id: recipe replay differs" }

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
            val pitchWindow = minOf(.25, duration * .3)
            val pitchStart = maxOf(.05, (duration - pitchWindow) * .5)
            val pitch = Pitch.detect(native, pitchStart.toFloat(), pitchWindow.toFloat())
            val targetHz = 440.0 * 2.0.pow((MIDI - 69) / 12.0)
            val metrics = metrics(matched) + linkedMapOf(
                "nativePeak" to native.peak(), "nativeLoudness" to Loudness.of(native),
                "pitchHz" to pitch?.hz,
                "pitchCents" to pitch?.let { 1200 * ln(it.hz / targetHz) / ln(2.0) },
                "pitchConfidence" to pitch?.confidence,
            )
            val hashes = linkedMapOf<String, Any?>(
                "nativeSamplesSha256" to samplesHash(native.samples),
                "matchedSamplesSha256" to samplesHash(matched.samples),
                "nativeWavSha256" to fileHash(File(root, files.getValue("native"))),
                "matchedWavSha256" to fileHash(File(root, files.getValue("matched"))),
                "recipeSha256" to fileHash(File(root, files.getValue("recipe"))),
            )
            val diagnostics = engineDiagnostics(detailed.diagnostics)
            reports += linkedMapOf(
                "id" to id, "voice" to voice.name, "macros" to macros,
                "deterministic" to true, "recipeRoundtrip" to true,
                "native" to metrics(native), "matched" to metrics(matched),
                "pitchEstimator" to linkedMapOf(
                    "method" to "SnipSnap Pitch.detect: autocorrelation with spectral refinement",
                    "fromSeconds" to pitchStart, "windowSeconds" to pitchWindow,
                    "hz" to pitch?.hz, "confidence" to pitch?.confidence,
                    "errorCentsFromC4" to metrics["pitchCents"],
                    "note" to "A local estimate, not a pitch-lock guarantee; null means no confident period was found.",
                ),
                "engine" to diagnostics, "hashes" to hashes,
                "renderAndReplayMillis" to (System.nanoTime() - began) / 1e6,
            )
            clips += linkedMapOf(
                "id" to id, "group" to voice.name, "condition" to condition.id,
                "title" to title, "description" to condition.description,
                "midi" to MIDI, "note" to "C4", "velocity" to VELOCITY, "seed" to SEED,
                "macros" to macros, "durationSeconds" to duration,
                "files" to files, "metrics" to metrics, "waveform" to waveform(matched.samples),
                "hashes" to hashes,
            )
            println("VENT ${clips.size}/12: $id — native peak ${native.peak()}, pitch ${pitch?.hz ?: "unresolved"} Hz")
        }
        check(clips.size == 12 && clips.map { it["id"] }.distinct().size == 12) { "Expected twelve unique clips" }

        val summary = linkedMapOf<String, Any?>(
            "clipCount" to clips.size, "finite" to true, "unclipped" to true,
            "deterministic" to true, "recipeRoundtrip" to true,
            "integrationFailures" to 0,
            "acceptance" to "Awaiting owner listening; numeric checks do not establish musical quality.",
            "nativeNote" to "Native WAVs are the engine's normal rendered output including its normal conditioning and leveling.",
            "matchedNote" to "Matched WAVs apply AuditionLevel.level: loudest 200 ms RMS target .03, with a .99 peak guard.",
            "pitchNote" to "Pitch is estimated from a central 250 ms window where available. Zero LEASH does not enforce concert pitch.",
        )
        val provenance = linkedMapOf<String, Any?>(
            "sourceCommit" to git("rev-parse", "HEAD"),
            "sourceTreeDirty" to git("status", "--porcelain").isNotEmpty(),
            "generatedAt" to Instant.now().toString(),
        )
        val manifest = linkedMapOf<String, Any?>(
            "engine" to "VENT", "model" to MODEL,
            "title" to "VENT / First audition",
            "description" to "Twelve dry finite sounds generated from a Rössler trajectory: three intended characters, four CHAOS/LEASH comparisons.",
            "note" to "C4", "midi" to MIDI, "velocity" to VELOCITY, "seed" to SEED,
            "sampleRate" to Dsp.RATE, "channels" to 1,
            "groups" to voices.map { voice -> linkedMapOf(
                "id" to voice.name, "title" to voiceTitle(voice), "description" to voiceDescription(voice),
            ) },
            "conditions" to conditions.map { linkedMapOf(
                "id" to it.id, "title" to it.title, "description" to it.description,
                "chaos" to it.chaos, "leash" to it.leash,
            ) },
            "clips" to clips, "diagnostics" to summary,
        ) + provenance
        File(root, "manifest.json").writeText(Json.write(json(manifest)))
        File(root, "diagnostics.json").writeText(Json.write(json(linkedMapOf(
            "engine" to "VENT", "model" to MODEL, "summary" to summary,
            "clips" to reports, "generationSeconds" to (System.nanoTime() - started) / 1e9,
        ) + provenance)))
        writeZip(root, clips)
        println("VENT wrote twelve dry clips, native/matched PCM24 WAVs, recipes and measurements to ${root.path}")
    }

    private fun requireAudio(id: String, label: String, snip: Snip) {
        check(snip.channels == 1 && snip.sampleRate == Dsp.RATE) { "$id: unexpected $label format" }
        check(snip.samples.isNotEmpty() && snip.samples.all { it.isFinite() }) { "$id: nonfinite or empty $label audio" }
        check(snip.peak() < 1f) { "$id: clipped $label audio (${snip.peak()})" }
        check(snip.peak() > 1e-6f) { "$id: silent $label audio" }
    }

    private fun sameBits(a: Snip, b: Snip): Boolean = a.channels == b.channels && a.sampleRate == b.sampleRate &&
        a.samples.size == b.samples.size && a.samples.indices.all { a.samples[it].toRawBits() == b.samples[it].toRawBits() }

    private fun metrics(snip: Snip): Map<String, Any?> {
        val samples = snip.samples
        val mean = samples.sumOf { it.toDouble() } / samples.size
        val rms = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
        return linkedMapOf("peak" to snip.peak(), "rms" to rms, "dc" to mean, "loudness" to Loudness.of(snip))
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

    private fun voiceTitle(voice: VentVoice) = when (voice) {
        VentVoice.ROUND -> "Round"
        VentVoice.WIRE -> "Wire"
        VentVoice.TEAR -> "Tear"
        else -> error("The original audition accepts model-1 voices only")
    }

    private fun engineDiagnostics(d: VentDiagnostics): Map<String, Any?> = linkedMapOf(
        "rawPeak" to d.rawPeak, "rawRms" to d.rawRms, "rawDc" to d.rawDc,
        "maxState" to d.maxState, "stateRms" to d.stateRms,
        "integrationFailures" to d.integrationFailures, "leashResets" to d.leashResets,
        "measuredOrbitPeriod" to d.measuredOrbitPeriod, "modelTimeStep" to d.modelTimeStep,
        "rawNote" to "Internal diagnostics before shared melodic leveling. Native WAVs contain normal engine output.",
        "resetNote" to "leashResets count intended note-cycle anchoring; integrationFailures count emergency resets and must be zero.",
    )

    private fun voiceDescription(voice: VentVoice) = when (voice) {
        VentVoice.ROUND -> "A rounded starting point; listen for the orbit's body and motion."
        VentVoice.WIRE -> "A brighter starting point; listen for a narrow edge and shifting overtones."
        VentVoice.TEAR -> "A rougher starting point; listen for excursions and a tearing texture."
        else -> error("The original audition accepts model-1 voices only")
    }

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

    /** Explicit asset list excludes a previous audition.zip and unrelated stale files. */
    private fun writeZip(root: File, clips: List<Map<String, Any?>>) {
        val assets = mutableListOf("manifest.json", "diagnostics.json")
        for (clip in clips) {
            val files = clip.getValue("files") as Map<*, *>
            assets += files.values.map { it as String }
        }
        // A publication step may have already supplied these standalone page files.
        for (name in listOf("index.html", "app.js", "styles.css")) if (File(root, name).isFile) assets += name
        ZipOutputStream(File(root, "audition.zip").outputStream().buffered()).use { zip ->
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
