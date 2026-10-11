package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * PRISM's dry listening matrix: genuine Kotlin DSP, prelevel raw WAVs beside
 * the shared [AuditionLevel], three registers at two velocities, five-point
 * timbral sweeps, four interaction grids, extremes, presets and held loops.
 *
 * Run `./gradlew :synth:generatePrismAudition`. The listening page consumes
 * the adjacent flat `manifest.json`; rendering never needs a web synthesizer.
 */
object PrismAuditionGenerator {
    private val TIMBRAL = listOf("GRAIN", "DRIFT", "SHAPE", "STRIKE", "BODY")
    private val STEPS = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    private val STUDY_VOICES = listOf(PrismVoice.KEY, PrismVoice.VEIL)
    private val INTERACTIONS = listOf(
        "GRAIN" to "SHAPE",
        "DRIFT" to "STRIKE",
        "BODY" to "GRAIN",
        "DRIFT" to "SHAPE",
    )
    private val VOICE_ROLES = mapOf(
        PrismVoice.KEY to "Rounded harmonic key",
        PrismVoice.PLUCK to "Clear short articulation",
        PrismVoice.ORGAN to "Solid harmonic sustain",
        PrismVoice.BASS to "Root-bearing low body",
        PrismVoice.GLASS to "Sparse bright resonance",
        PrismVoice.VEIL to "Slowly separating harmonic texture",
    )
    private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private const val MATCHED_TARGET = 0.03

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1 && args.none { it.startsWith("--") }) { "usage: PrismAuditionGenerator [output-directory]" }
        val root = File(args.firstOrNull() ?: "../testkit/prism-audition").absoluteFile
        require(root.isDirectory || root.mkdirs()) { "cannot create $root" }
        val packStarted = System.nanoTime()
        val clips = mutableListOf<Map<String, Any?>>()
        val ids = mutableSetOf<String>()

        fun clip(
            section: String,
            id: String,
            label: String,
            description: String,
            voice: PrismVoice,
            overrides: Map<String, Float> = emptyMap(),
            midi: Int = Prism.rootMidi(voice) + 12,
            velocity: Float = 1f,
            template: PrismTemplate = Prism.templateFor(voice),
            extra: Map<String, Any?> = emptyMap(),
        ) {
            require(ids.add(id)) { "duplicate audition clip $id" }
            val semitones = midi - Prism.rootMidi(voice)
            require(semitones in 0..Prism.TUNE_SEMITONES) { "$voice MIDI $midi is outside its pitch range" }
            val macros = Prism.defaults(voice) + overrides +
                ("TUNE" to (overrides["TUNE"] ?: (semitones.toFloat() / Prism.TUNE_SEMITONES)))
            val started = System.nanoTime()
            val probe = Prism.probe(voice, macros, velocity, template, normalize = false)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
            val raw = Snip(probe.samples, channels = 1, sampleRate = Dsp.RATE)
            val loop = Prism.isLoop(macros.getValue("HOLD"))
            require(raw.samples.isNotEmpty() && raw.samples.all { it.isFinite() }) { "$id has empty or non-finite audio" }
            require(raw.peak() < 1f) { "$id raw peak ${raw.peak()} would clip in PCM" }
            val seam = if (loop) probe.seamError else null
            if (loop) require(requireNotNull(seam) < Keys.MAX_SEAM_ERROR) { "$id held seam $seam exceeds ${Keys.MAX_SEAM_ERROR}" }
            val cycleError = if (loop) cycleError(requireNotNull(probe.previousCycle), probe.samples) else null
            val nominalMidi = Prism.midiFor(voice, macros.getValue("TUNE"))
            val nominalHz = 440.0 * 2.0.pow((nominalMidi - 69.0) / 12.0)
            val pitchDeviationCents = 1200.0 * ln(probe.effectiveHz / nominalHz) / ln(2.0)
            val additivePeak = probe.raw.maxOfOrNull { abs(it.toDouble()) } ?: 0.0
            val matched = AuditionLevel.level(raw)
            val rawPath = "raw/$section/$id.wav"
            val matchedPath = "matched/$section/$id.wav"
            val sampler = SmplChunk(
                rootNote = nominalMidi,
                loop = if (loop) SmplChunk.Loop(0, raw.frameCount.toLong()) else null,
            )
            WavWriter.write(File(root, rawPath), raw, WavWriter.BitDepth.PCM_24, smpl = sampler)
            WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24, smpl = sampler)
            val row = linkedMapOf<String, Any?>(
                "id" to id,
                "label" to label,
                "description" to description,
                "section" to section,
                "voice" to voice.name,
                "note" to nominalMidi,
                "noteName" to noteName(nominalMidi),
                "effectiveHz" to probe.effectiveHz,
                "nominalHz" to nominalHz,
                "carrierPitchDeviationCents" to pitchDeviationCents,
                "rawAdditivePeak" to additivePeak,
                "loopCycleError" to cycleError,
                "velocity" to velocity,
                "macros" to macros,
                "raw" to rawPath,
                "matched" to matchedPath,
                "loop" to loop,
                "loopStartFrame" to if (loop) 0 else null,
                "loopEndFrameExclusive" to if (loop) raw.frameCount else null,
                "frames" to raw.frameCount,
                "seconds" to raw.durationSeconds,
                "elapsedMs" to elapsedMs,
                "msPerRenderedSecond" to elapsedMs / raw.durationSeconds,
                "metrics" to linkedMapOf("raw" to metrics(raw, seam), "matched" to metrics(matched, seam)),
            )
            row.putAll(extra)
            clips += row
            println("[${clips.size}] $section/$id: ${raw.frameCount} frames, ${elapsedMs.roundToInt()} ms${if (loop) ", loop seam $seam" else ""}")
        }

        // A voice's exact supported low, middle and high registers, each
        // played softly and loudly; no FX or additional envelopes.
        for (voice in PrismVoice.entries) {
            for ((register, semitones) in listOf("low" to 0, "middle" to 12, "high" to 24)) {
                for ((touch, velocity) in listOf("soft" to 0.25f, "loud" to 1f)) {
                    val midi = Prism.rootMidi(voice) + semitones
                    clip(
                        "voices", "${voice.name.lowercase()}_${register}_$touch",
                        "${voice.name} ${noteName(midi)} · $touch",
                        "${VOICE_ROLES.getValue(voice)}. Default timbre at velocity ${fmt(velocity)}.",
                        voice, midi = midi, velocity = velocity,
                        extra = mapOf("register" to register),
                    )
                }
            }
        }

        // Curated dry patches only. A patch's template and saved pitch are
        // preserved; held presets remain genuine loop-only files.
        for (voice in PrismVoice.entries) {
            for (patch in PrismPresets.forVoice(voice)) {
                val macros = Prism.defaults(voice) + patch.macros
                val midi = Prism.rootMidi(voice) + (macros.getValue("TUNE") * Prism.TUNE_SEMITONES).roundToInt()
                clip(
                    "presets", "preset_${slug(patch.name)}", patch.name,
                    "${VOICE_ROLES.getValue(voice)}. ${macroLine(macros)}.",
                    voice, patch.macros, midi, patch.velocity, patch.template,
                    extra = mapOf("patch" to patch.toJsonValue()),
                )
            }
        }

        // A continuous five-point sweep of every timbral macro on the
        // rounded key and slow texture, with other controls held fixed.
        for (voice in STUDY_VOICES) {
            for (macro in TIMBRAL) {
                for ((step, value) in STEPS.withIndex()) {
                    clip(
                        "sweeps", "${voice.name.lowercase()}_${macro.lowercase()}_$step",
                        "${voice.name} · $macro ${fmt(value)}",
                        "$macro at ${fmt(value)}; the other controls at the voice defaults.",
                        voice, mapOf(macro to value),
                        extra = mapOf("macro" to macro, "value" to value, "step" to step),
                    )
                }
            }
        }

        // All four corners of the four required pairs, on both study voices.
        for (voice in STUDY_VOICES) {
            for ((a, b) in INTERACTIONS) {
                for (va in listOf(0f, 1f)) {
                    for (vb in listOf(0f, 1f)) {
                        clip(
                            "interactions", "${voice.name.lowercase()}_${a.lowercase()}${va.toInt()}_${b.lowercase()}${vb.toInt()}",
                            "${voice.name} · $a ${va.toInt()} × $b ${vb.toInt()}",
                            "Interaction corner; the other controls at the voice defaults.",
                            voice, mapOf(a to va, b to vb),
                            extra = mapOf("pair" to listOf(a, b), "values" to listOf(va, vb)),
                        )
                    }
                }
            }
        }

        val allHigh = TIMBRAL.associateWith { 1f }
        for (voice in PrismVoice.entries) {
            clip(
                "extremes", "${voice.name.lowercase()}_all_high", "${voice.name} · all timbral controls 1",
                "Every timbral macro at its maximum. Finite note, HOLD 0.",
                voice, allHigh + ("HOLD" to 0f),
            )
            clip(
                "hold", "${voice.name.lowercase()}_hold_default", "${voice.name} · held default",
                "One seamless sustain region, default timbre, HOLD 1. The onset is excluded.",
                voice, mapOf("HOLD" to 1f), extra = mapOf("variant" to "default"),
            )
            clip(
                "hold", "${voice.name.lowercase()}_hold_extreme", "${voice.name} · held extreme",
                "One seamless sustain region, every timbral macro at 1, HOLD 1. The onset is excluded.",
                voice, allHigh + ("HOLD" to 1f), extra = mapOf("variant" to "extreme"),
            )
        }
        for (voice in STUDY_VOICES) {
            for ((variant, hold) in listOf("threshold-below" to Prism.LOOP_THRESHOLD - 0.001f, "threshold-at" to Prism.LOOP_THRESHOLD)) {
                clip(
                    "hold", "${voice.name.lowercase()}_hold_$variant", "${voice.name} · HOLD ${fmt(hold)}",
                    if (variant == "threshold-below") "Just below the loop threshold: a finite note with its onset and decay."
                    else "At the loop threshold: only the seamless held region.",
                    voice, mapOf("HOLD" to hold), extra = mapOf("variant" to variant),
                )
            }
        }

        val presetCount = PrismVoice.entries.sumOf { PrismPresets.forVoice(it).size }
        check(clips.size == 140 + presetCount) { "incomplete audition matrix: ${clips.size} clips" }
        require(clips.size <= 160) { "audition matrix exceeds its 160-clip limit" }
        val sections = listOf(
            section("voices", "Voices", "Six identities across three registers, played softly and loudly."),
            section("presets", "Presets", "Curated dry patches, ready for sonic review."),
            section("sweeps", "Macro sweeps", "Five steps per timbral control on KEY and VEIL; other controls stay at their defaults."),
            section("interactions", "Interactions", "Four corners of GRAIN × SHAPE, DRIFT × STRIKE, BODY × GRAIN, and DRIFT × SHAPE."),
            section("extremes", "Extremes", "Every timbral control at 1, a finite note on every voice."),
            section("hold", "HOLD", "Default and extreme loop-only regions on every voice, plus threshold probes on KEY and VEIL."),
        )
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1,
            "engine" to "PRISM",
            "modelVersion" to Prism.MODEL_VERSION,
            "generatedAt" to Instant.now().toString(),
            "sampleRate" to Dsp.RATE,
            "internalSampleRate" to Dsp.RATE * Dsp.OVERSAMPLE,
            "channels" to 1,
            "bitDepth" to 24,
            "matchedLoudnessTarget" to MATCHED_TARGET,
            "loudnessMeasure" to "RMS of the loudest 200 ms window (AuditionLevel / Loudness.of)",
            "rawDescription" to "Conditioned engine output before shared loudness processing; no FX.",
            "holdThreshold" to Prism.LOOP_THRESHOLD,
            "loopSeamThreshold" to Keys.MAX_SEAM_ERROR,
            "clipCount" to clips.size,
            "totalRenderMs" to clips.sumOf { it.getValue("elapsedMs") as Double },
            "elapsedPackMs" to (System.nanoTime() - packStarted) / 1_000_000.0,
            "sections" to sections,
            "clips" to clips,
        )
        File(root, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        val page = requireNotNull(PrismAuditionGenerator::class.java.getResourceAsStream("/audition/prism-audition.html")) {
            "the listening page is missing from synth/src/test/resources/audition/prism-audition.html"
        }
        File(root, "index.html").outputStream().use { output -> page.use { it.copyTo(output) } }
        println("wrote ${clips.size} raw/matched clip pairs, manifest.json and index.html under $root")
    }

    private fun section(id: String, label: String, description: String) =
        linkedMapOf("id" to id, "label" to label, "description" to description)

    private fun noteName(midi: Int) = NOTE_NAMES[midi % 12] + (midi / 12 - 1)

    private fun slug(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private fun fmt(value: Float) = "%.3f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')

    private fun macroLine(macros: Map<String, Float>) = TIMBRAL.joinToString(" · ") { "$it ${fmt(macros.getValue(it))}" }

    /** Difference energy over signal energy across two independently rendered complete cycles. */
    private fun cycleError(previous: FloatArray, current: FloatArray): Double {
        require(previous.size == current.size) { "held evidence cycles have different frame counts" }
        var difference = 0.0
        var signal = 0.0
        for (i in current.indices) {
            val d = current[i] - previous[i].toDouble()
            difference += d * d
            signal += current[i].toDouble() * current[i]
        }
        return if (signal > 0) difference / signal else 0.0
    }

    private fun metrics(snip: Snip, seamError: Double?): Map<String, Any?> {
        var sum = 0.0
        var squared = 0.0
        var peak = 0.0
        for (sample in snip.samples) {
            val v = sample.toDouble()
            sum += v
            squared += v * v
            peak = maxOf(peak, abs(v))
        }
        return linkedMapOf(
            "peak" to peak,
            "rms" to sqrt(squared / snip.samples.size),
            "dc" to sum / snip.samples.size,
            "loudness" to Loudness.of(snip),
            "seamError" to seamError,
        )
    }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) { "non-finite evidence: $value" } })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("unsupported manifest value ${value::class}")
    }
}
