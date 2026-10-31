package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** Controlled original/decay comparison, with a complete experimental processing recipe. */
object VentKnuckleAuditionGenerator {
    private const val MATCH_TARGET = .03f
    private const val PEAK_GUARD = .99f
    private data class Levelled(val snip: Snip, val gain: Float)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/vent-knuckle-audition").absoluteFile
        check(root.mkdirs() || root.isDirectory)
        check(File(root, "audio").mkdirs() || File(root, "audio").isDirectory)
        check(File(root, "recipes").mkdirs() || File(root, "recipes").isDirectory)
        val patch = VentKnuckleExperiment.patch
        val source = VentKnuckleExperiment.source(patch)
        check(source.diagnostics.integrationFailures == 0)
        check(source.diagnostics.signalPath == "clean-linear")
        val rawHash = VentKnuckleExperiment.samplesHash(source.raw)
        val traceHash = VentKnuckleExperiment.traceHash(source.trace)
        val ordinary = PadRecipe(patch)
        check(sameBits(source.snip, ordinary.render())) { "Original experiment differs from the normal factory recipe" }
        val originalSourceRecipe = ordinary.toJsonText()
        check(sameBits(source.snip, PadRecipe.fromJsonText(originalSourceRecipe).render()))
        File(root, "recipes/source-pad.json").writeText(originalSourceRecipe + "\n")

        val recipes = VentKnuckleExperiment.Variant.entries.associateWith { variant ->
            val text = VentKnuckleExperiment.recipe(patch, source, variant)
            File(root, "recipes/${variant.id}.json").writeText(text + "\n")
            text
        }
        val hits = VentKnuckleExperiment.Variant.entries.associateWith { variant ->
            val hit = VentKnuckleExperiment.render(source, variant)
            requireAudio(hit)
            check(sameBits(hit, VentKnuckleExperiment.replay(recipes.getValue(variant)))) { "${variant.id}: experimental recipe replay differs" }
            check(hit.frameCount == source.snip.frameCount) { "Envelope changed the hit's duration" }
            hit
        }
        // Equal source hashes are causal evidence: this experiment only multiplies an envelope.
        check(rawHash == VentKnuckleExperiment.samplesHash(source.raw)) { "Experiment mutated source raw audio" }
        check(traceHash == VentKnuckleExperiment.traceHash(source.trace)) { "Experiment mutated the source trajectory" }
        val originalBody = bodyChange(hits.getValue(VentKnuckleExperiment.Variant.ORIGINAL))
        val candidateBody = bodyChange(hits.getValue(VentKnuckleExperiment.Variant.TIGHT_DECAY))
        check(originalBody > 0.0 && candidateBody < -12.0 && originalBody - candidateBody > 15.0) {
            "Pulse Knuckle envelope experiment did not reproduce a clear body-energy contrast: $originalBody / $candidateBody dB"
        }

        val comparisons = listOf("hit", "rhythm").map { kind ->
            val variants = VentKnuckleExperiment.Variant.entries.map { variant ->
                val native = if (kind == "hit") hits.getValue(variant) else VentKnuckleExperiment.rhythm(hits.getValue(variant))
                if (kind == "rhythm") check(sameBits(native, VentKnuckleExperiment.rhythm(VentKnuckleExperiment.replay(recipes.getValue(variant))))) {
                    "${variant.id}: rhythm recipe replay differs"
                }
                requireAudio(native)
                val matched = level(native)
                requireAudio(matched.snip)
                val files = linkedMapOf(
                    "native" to "audio/$kind-${variant.id}-native.wav",
                    "matched" to "audio/$kind-${variant.id}-matched.wav",
                    "recipe" to "recipes/${variant.id}.json",
                )
                WavWriter.write(File(root, files.getValue("native")), native, WavWriter.BitDepth.PCM_24)
                WavWriter.write(File(root, files.getValue("matched")), matched.snip, WavWriter.BitDepth.PCM_24)
                val hashes = linkedMapOf(
                    "native" to fileHash(File(root, files.getValue("native"))),
                    "matched" to fileHash(File(root, files.getValue("matched"))),
                    "recipe" to fileHash(File(root, files.getValue("recipe"))),
                    "nativeSamplesFloat32leSha256" to VentKnuckleExperiment.samplesHash(native.samples),
                    "matchedSamplesFloat32leSha256" to VentKnuckleExperiment.samplesHash(matched.snip.samples),
                )
                linkedMapOf<String, Any?>(
                    "id" to variant.id, "title" to variant.title,
                    "description" to if (variant == VentKnuckleExperiment.Variant.ORIGINAL)
                        "The existing Pulse Knuckle factory sound, rendered without the experimental decay."
                    else "The same orbit and conditioning, with an optional amplitude decay beginning after 6 ms; 180 ms T60.",
                    "files" to files, "durationSeconds" to native.durationSeconds,
                    "waveform" to waveform(matched.snip), "nativeWaveform" to waveform(native),
                    "metrics" to metrics(matched.snip), "nativeMetrics" to metrics(native),
                    "matchedGain" to matched.gain, "hashes" to hashes,
                    "envelopeBody" to if (kind == "hit") bodyWindows(native) else null,
                    "pitch" to if (kind == "hit") pitch(native, patch.midi) else null,
                )
            }
            linkedMapOf<String, Any?>(
                "id" to kind,
                "title" to if (kind == "hit") "One hit" else "Eight strikes",
                "description" to if (kind == "hit")
                    "Compare the existing 200 ms hit against the same passage with a falling body."
                else "Two sparse bars at 92 BPM. The timing, saved synthesis touch and event gains are identical between variants.",
                "variants" to variants,
            )
        }

        val sourceRoot = File(git("rev-parse", "--show-toplevel"))
        val sourceFiles = listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/Vent.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/VentModel2.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/VentPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/VentKitPresets.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/PadRecipe.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/VentKnuckleExperiment.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/VentKnuckleAuditionGenerator.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Cleanup.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Pitch.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Fft.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
            "json/src/main/kotlin/com/snipsnap/json/Json.kt",
        ).associateWith { fileHash(File(sourceRoot, it)) }
        val provenance = linkedMapOf<String, Any?>(
            "sourceCommit" to git("rev-parse", "HEAD"),
            "sourceTreeDirty" to git("status", "--porcelain").isNotEmpty(),
            "sourceFilesSha256" to sourceFiles, "generatedAt" to Instant.now().toString(),
        )
        val matchedNote = "Matched mode independently scales each variant to the loudest-200-ms Loudness.of target .03, with a .99 sample-peak guard. This is one scalar per file, so the envelope shape remains intact. It differs from the earlier kit page's shared Dry/Effects pair gain."
        val nativeNote = "Original native is the exact normal Pulse Knuckle synth output. Tight-decay native applies the experimental amplitude envelope to that render's conditioned/decimated raw readout, then the existing melodic level and 4 ms tail stages. Neither mode preserves pre-level synthesis-velocity gain."
        val diagnostics = linkedMapOf<String, Any?>(
            "finite" to true, "sampleUnclipped" to true, "integrationFailures" to source.diagnostics.integrationFailures,
            "originalNormalRecipeIdentity" to true, "experimentalRecipeReplay" to true,
            "rhythmRecipeReplay" to true, "sourceRawUnchanged" to true, "sourceTrajectoryUnchanged" to true,
            "originalBodyChangeDb" to originalBody, "candidateBodyChangeDb" to candidateBody,
            "bodyContrastDb" to originalBody - candidateBody,
            "rawPeak" to source.diagnostics.rawPeak, "rawRms" to source.diagnostics.rawRms,
            "rawDc" to source.diagnostics.rawDc, "maxState" to source.diagnostics.maxState,
            "measurementNote" to "These are source and render measurements. They establish controlled replay and envelope contrast, not musical preference, alias freedom, physical realism or device performance. The owner has not selected this proposed candidate.",
        )
        val manifest = linkedMapOf<String, Any?>(
            "experiment" to "VENT_KNUCKLE_ENVELOPE_V1", "title" to "Pulse Knuckle / Original and tight decay",
            "description" to "One saved orbit. Two amplitude shapes. A proposed decay profile compared with the existing factory hit, alone and in a sparse rhythm.",
            "sampleRate" to Dsp.RATE, "channels" to 1, "bitDepth" to 24,
            "sourcePatch" to patch.toJsonValue(),
            "sourcePadRecipe" to "recipes/source-pad.json",
            "processing" to linkedMapOf(
                "original" to VentKnuckleExperiment.processing(VentKnuckleExperiment.Variant.ORIGINAL),
                "tightDecay" to VentKnuckleExperiment.processing(VentKnuckleExperiment.Variant.TIGHT_DECAY),
            ),
            "sourceRawFloat32leSha256" to rawHash, "sourceTrajectoryFloat64leSha256" to traceHash,
            "nativeNote" to nativeNote, "matchedNote" to matchedNote,
            "matchTarget" to MATCH_TARGET, "samplePeakGuard" to PEAK_GUARD,
            "rhythm" to VentKnuckleExperiment.rhythmRecipe(),
            "comparisons" to comparisons, "diagnostics" to diagnostics,
            "downloads" to linkedMapOf("audio" to "knuckle.zip"),
        ) + provenance
        File(root, "manifest.json").writeText(Json.write(VentKnuckleExperiment.json(manifest)) + "\n")
        File(root, "diagnostics.json").writeText(Json.write(VentKnuckleExperiment.json(linkedMapOf(
            "experiment" to "VENT_KNUCKLE_ENVELOPE_V1", "summary" to diagnostics,
            "sourceRawFloat32leSha256" to rawHash, "sourceTrajectoryFloat64leSha256" to traceHash,
            "comparisons" to comparisons, "nativeNote" to nativeNote, "matchedNote" to matchedNote,
        ) + provenance)) + "\n")
        File(root, "README.txt").writeText("""
            Pulse Knuckle envelope experiment

            Original is the unchanged VENT model-2 Pulse Knuckle factory hit.
            Tight decay is proposed: gain remains one until 6 ms, then follows an
            exponential amplitude fall with a 180 ms T60. It changes no trajectory,
            force, projection, pitch control, seed or saved synthesis touch.

            $nativeNote

            $matchedNote

            Both rhythm variants use the same eight-event recipe at 92 BPM.
            recipes/source-pad.json is an ordinary dry PadRecipe. The two other
            recipes are explicit experiment specifications, not loadable PadRecipes.
            The test-source VentKnuckleExperiment.replay method reproduces them;
            see the source commit and source-file hashes in manifest.json.

            The existing factory kit is not replaced. Listen to decide whether the
            optional decay improves this hit's role. This is not a listening verdict.
        """.trimIndent() + "\n")
        val files = mutableListOf("manifest.json", "diagnostics.json", "README.txt", "recipes/source-pad.json")
        for (comparison in comparisons) for (variant in comparison.getValue("variants") as List<*>) {
            val v = variant as Map<*, *>
            files += (v["files"] as Map<*, *>).values.map { it as String }
        }
        ZipOutputStream(File(root, "knuckle.zip").outputStream().buffered()).use { zip ->
            for (name in files.distinct().sorted()) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                File(root, name).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        println("VENT Knuckle wrote eight PCM24 WAVs, three recipes, measurements and knuckle.zip to ${root.path}")
        println("Envelope body change: original $originalBody dB; candidate $candidateBody dB")
    }

    /** Independent file gains avoid making perceived loudness choose an envelope by default. */
    private fun level(snip: Snip): Levelled {
        var gain = MATCH_TARGET / Loudness.of(snip).coerceAtLeast(1e-9f)
        if (snip.peak() * gain > PEAK_GUARD) gain = PEAK_GUARD / snip.peak()
        val out = snip.samples.copyOf()
        for (i in out.indices) out[i] *= gain
        return Levelled(Snip(out, snip.channels, snip.sampleRate), gain)
    }

    private fun requireAudio(snip: Snip) {
        check(snip.channels == 1 && snip.sampleRate == Dsp.RATE && snip.samples.isNotEmpty())
        check(snip.samples.all { it.isFinite() && abs(it) < 1f })
        check(snip.peak() > 1e-6f)
    }

    private fun sameBits(a: Snip, b: Snip): Boolean = a.channels == b.channels && a.sampleRate == b.sampleRate &&
        a.samples.size == b.samples.size && a.samples.indices.all { a.samples[it].toRawBits() == b.samples[it].toRawBits() }

    private fun metrics(snip: Snip): Map<String, Any?> = linkedMapOf(
        "peak" to snip.peak(), "rms" to sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size),
        "dc" to snip.samples.sumOf { it.toDouble() } / snip.samples.size, "loudness" to Loudness.of(snip),
    )

    private fun windowRms(snip: Snip, from: Double, to: Double): Double {
        val a = (from * snip.samples.size).toInt(); val b = (to * snip.samples.size).toInt()
        return sqrt((a until b).sumOf { snip.samples[it].toDouble() * snip.samples[it] } / (b - a))
    }

    private fun bodyChange(snip: Snip): Double =
        20.0 * log10(windowRms(snip, .35, .65) / windowRms(snip, .05, .25))

    private fun bodyWindows(snip: Snip): Map<String, Any?> = linkedMapOf(
        "earlyFraction" to listOf(.05, .25), "middleFraction" to listOf(.35, .65),
        "earlyRms" to windowRms(snip, .05, .25), "middleRms" to windowRms(snip, .35, .65),
        "middleVsEarlyDb" to bodyChange(snip),
        "note" to "RMS at 10-50 ms versus 70-130 ms for the 200 ms hit; scalar output matching cannot change this ratio.",
    )

    private fun pitch(snip: Snip, midi: Int): Map<String, Any?> {
        val found = Pitch.detect(snip, .01f, .12f)
        val targetHz = 440.0 * 2.0.pow((midi - 69) / 12.0)
        return linkedMapOf(
            "fromSeconds" to .01, "windowSeconds" to .12, "hz" to found?.hz, "confidence" to found?.confidence,
            "errorCentsFromNominal" to found?.let { 1200 * ln(it.hz / targetHz) / ln(2.0) },
            "note" to "A local autocorrelation/spectral estimate on this free struck accent, not a pitch-lock guarantee. Null means no confident local period was found.",
        )
    }

    private fun waveform(snip: Snip): List<Double> {
        val count = minOf(128, snip.samples.size)
        val peak = snip.peak().coerceAtLeast(1e-9f)
        return (0 until count).map { bin ->
            val from = bin * snip.samples.size / count; val to = (bin + 1) * snip.samples.size / count
            (from until to).maxOf { abs(snip.samples[it]) }.toDouble() / peak
        }
    }

    private fun fileHash(file: File): String = VentKnuckleExperiment.hash(file.readBytes())

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "Unable to record source provenance: $output" }
        return output
    }
}
