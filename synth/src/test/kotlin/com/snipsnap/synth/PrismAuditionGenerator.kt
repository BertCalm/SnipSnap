package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
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
    private const val AUDITION_REVISION = "r2"
    private const val COMPARISON_GROUP = "glass-softening"
    private val R1_TEMPLATE = PrismTemplate(PrismSpectrum.BELL, PrismContour.RINGING)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1 && args.none { it.startsWith("--") }) { "usage: PrismAuditionGenerator [output-directory]" }
        val root = File(args.firstOrNull() ?: "../testkit/prism-audition").absoluteFile
        require(root.isDirectory || root.mkdirs()) { "cannot create $root" }
        val packStarted = System.nanoTime()
        val clips = mutableListOf<Map<String, Any?>>()
        val ids = mutableSetOf<String>()
        val r1Text = requireNotNull(PrismAuditionGenerator::class.java.getResourceAsStream("/audition/prism-r1-glass.json")) {
            "the original GLASS provenance fixture is missing"
        }.bufferedReader().use { it.readText() }
        val r1Manifest = fromJson(Json.parse(r1Text)) as Map<String, Any?>
        val r1Rows = (r1Manifest.getValue("clips") as List<*>).map { it as Map<String, Any?> }.associateBy { it.getValue("id") as String }
        val r1Revision = r1Manifest.getValue("sourceRevision") as String
        restoreR1Archive(root, r1Rows)
        File(root, "r1/manifest.json").apply { parentFile.mkdirs(); writeText(r1Text) }

        fun comparison(variant: String, midi: Int, revision: String, only: Boolean, sourceId: String? = null): Map<String, Any?> {
            val held = variant == "hold-default"
            val title = when (variant) {
                "default" -> "Default · ${noteName(midi)} · full velocity"
                "small-prism" -> "Small Prism · ${noteName(midi)} · full velocity"
                else -> "Default · ${noteName(midi)} · held loop"
            }
            return linkedMapOf(
                "comparisonGroup" to COMPARISON_GROUP,
                "comparisonPair" to "glass-$variant-${noteName(midi).lowercase()}",
                "comparisonTitle" to title,
                "comparisonLabel" to if (revision == "r1") "Original R1" else "Softer R2",
                "comparisonOnly" to only,
                "revision" to revision,
                "sourceId" to sourceId,
                "loopComparison" to held,
            )
        }

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
            archived: Map<String, Any?>? = null,
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
            val revision = extra["revision"] as? String ?: AUDITION_REVISION
            val prefix = when {
                revision == "r1" -> "r1/"
                voice == PrismVoice.GLASS && revision == "r2" -> "r2/"
                else -> ""
            }
            val rawPath = archived?.get("raw") as? String ?: "${prefix}raw/$section/$id.wav"
            val matchedPath = archived?.get("matched") as? String ?: "${prefix}matched/$section/$id.wav"
            val sampler = SmplChunk(
                rootNote = nominalMidi,
                loop = if (loop) SmplChunk.Loop(0, raw.frameCount.toLong()) else null,
            )
            val rawBytes = wavBytes(raw, sampler)
            val matchedBytes = wavBytes(matched, sampler)
            val hashes = linkedMapOf("raw" to sha256(rawBytes), "matched" to sha256(matchedBytes))
            if (archived != null) {
                // An explicit legacy template must reproduce the original published
                // PCM files exactly. Existing archived bytes are never overwritten.
                val originalHashes = archived.getValue("sha256") as Map<*, *>
                require(hashes == originalHashes) { "$id does not reproduce the original R1 WAVs: $hashes, expected $originalHashes" }
                preserveWav(File(root, rawPath), rawBytes, hashes.getValue("raw"))
                preserveWav(File(root, matchedPath), matchedBytes, hashes.getValue("matched"))
            } else {
                File(root, rawPath).apply { parentFile.mkdirs(); writeBytes(rawBytes) }
                File(root, matchedPath).apply { parentFile.mkdirs(); writeBytes(matchedBytes) }
            }
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
                "recipe" to PrismPatch(label, voice, macros, velocity, template).toJsonValue(),
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
                "sha256" to hashes,
                "revision" to revision,
            )
            if (voice == PrismVoice.GLASS) {
                row["reviewKey"] = if (revision == "r2") "prism-r2:$id" else archived?.get("id") ?: id
            }
            if (revision == "r1") {
                row["sourceRevision"] = r1Revision
                row["baselineOrigin"] = if (archived != null) "Original published WAVs; legacy replay verified byte-identical" else "Original R1 template replay through the unchanged additive core"
            }
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
                        extra = mapOf("register" to register) + if (voice == PrismVoice.GLASS && velocity == 1f)
                            comparison("default", midi, "r2", false) else emptyMap(),
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
                    extra = mapOf("patch" to patch.toJsonValue()) + if (patch.name == "Small Prism")
                        comparison("small-prism", midi, "r2", false) else emptyMap(),
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
                voice, mapOf("HOLD" to 1f), extra = mapOf("variant" to "default") + if (voice == PrismVoice.GLASS)
                    comparison("hold-default", Prism.rootMidi(voice) + 12, "r2", false) else emptyMap(),
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
        val baseClipCount = clips.size

        // Real R1 examples are retained from the first published pack. The
        // unchanged original BELL template additionally supplies the two
        // missing Small Prism registers, with the same controls as R2.
        val glass = PrismVoice.GLASS
        val smallSource = r1Rows.getValue("preset_small_prism")
        val smallMacros = (smallSource.getValue("macros") as Map<*, *>).mapValues { (_, value) -> (value as Number).toFloat() }
            .entries.associate { it.key as String to it.value }
        for ((register, midi) in listOf("low" to 60, "middle" to 72, "high" to 84)) {
            val originalId = "glass_${register}_loud"
            val original = r1Rows.getValue(originalId)
            val defaults = (original.getValue("macros") as Map<*, *>).entries.associate { it.key as String to (it.value as Number).toFloat() }
            clip(
                "voices", "glass_r1_default_${register}_loud", "R1 GLASS ${noteName(midi)} · original",
                "Original published R1 default at full velocity; unchanged controls beside the softer R2 template.",
                glass, defaults, midi = midi, template = R1_TEMPLATE,
                extra = comparison("default", midi, "r1", true, originalId), archived = original,
            )
            val tune = (midi - Prism.rootMidi(glass)) / Prism.TUNE_SEMITONES.toFloat()
            val controls = smallMacros + ("TUNE" to tune)
            val savedSmall = if (midi == 72) smallSource else null
            clip(
                "presets", "glass_r1_small_prism_$register", "R1 Small Prism ${noteName(midi)} · original",
                "Original R1 Small Prism controls and legacy BELL template, at ${noteName(midi)}.",
                glass, controls, midi = midi, template = R1_TEMPLATE,
                extra = comparison("small-prism", midi, "r1", true, if (midi == 72) "preset_small_prism" else null), archived = savedSmall,
            )
            if (midi != 72) {
                clip(
                    "presets", "glass_r2_small_prism_$register", "R2 Small Prism ${noteName(midi)} · softer",
                    "The same R1 Small Prism controls through the refined harmonic GLASS template, at ${noteName(midi)}.",
                    glass, controls, midi = midi,
                    extra = comparison("small-prism", midi, "r2", true),
                )
            }
        }
        val heldSource = r1Rows.getValue("glass_hold_default")
        val heldMacros = (heldSource.getValue("macros") as Map<*, *>).entries.associate { it.key as String to (it.value as Number).toFloat() }
        clip(
            "hold", "glass_r1_hold_default", "R1 GLASS C5 · original held loop",
            "Original published R1 held default, with identical controls beside the softer R2 held region.",
            glass, heldMacros, midi = 72, template = R1_TEMPLATE,
            extra = comparison("hold-default", 72, "r1", true, "glass_hold_default"), archived = heldSource,
        )
        check(clips.size == baseClipCount + 9) { "incomplete GLASS revision comparison" }
        check(clips.filter { it["comparisonGroup"] == COMPARISON_GROUP }.groupBy { it["comparisonPair"] }.values.all { pair ->
            pair.size == 2 && pair.map { it["revision"] }.toSet() == setOf("r1", "r2") &&
                pair[0]["note"] == pair[1]["note"] && pair[0]["macros"] == pair[1]["macros"]
        }) { "GLASS comparisons must pair the same pitch and controls across R1/R2" }
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
            "auditionRevision" to AUDITION_REVISION,
            "revisionDescription" to "GLASS R2: softer harmonic template. Other voices and Small Prism controls are unchanged.",
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
            "baseClipCount" to baseClipCount,
            "comparisonClipCount" to clips.count { it["comparisonGroup"] == COMPARISON_GROUP },
            "r1ArchiveVerifiedClipCount" to r1Rows.size,
            "r1ArchiveByteIdentical" to true,
            "baselineProvenance" to r1Manifest.filterKeys { it != "clips" },
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

    private fun wavBytes(snip: Snip, sampler: SmplChunk): ByteArray = ByteArrayOutputStream().use { output ->
        WavWriter.write(output, snip, WavWriter.BitDepth.PCM_24, smpl = sampler)
        output.toByteArray()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun preserveWav(file: File, replay: ByteArray, expectedHash: String) {
        if (file.exists()) require(sha256(file.readBytes()) == expectedHash) { "archived R1 audio changed: $file" }
        else file.apply { parentFile.mkdirs(); writeBytes(replay) }
    }

    /** Restore a fresh checkout's real R1 archive only after matching published PCM fingerprints. */
    private fun restoreR1Archive(root: File, rows: Map<String, Map<String, Any?>>) {
        for ((id, row) in rows) {
            val macros = (row.getValue("macros") as Map<*, *>).entries.associate { it.key as String to (it.value as Number).toFloat() }
            val velocity = (row.getValue("velocity") as Number).toFloat()
            val probe = Prism.probe(PrismVoice.GLASS, macros, velocity, R1_TEMPLATE, normalize = false)
            val raw = Snip(probe.samples, channels = 1, sampleRate = Dsp.RATE)
            val sampler = SmplChunk(
                rootNote = Prism.midiFor(PrismVoice.GLASS, macros.getValue("TUNE")),
                loop = if (Prism.isLoop(macros.getValue("HOLD"))) SmplChunk.Loop(0, raw.frameCount.toLong()) else null,
            )
            val originalHashes = row.getValue("sha256") as Map<*, *>
            for ((mode, snip) in listOf("raw" to raw, "matched" to AuditionLevel.level(raw))) {
                val bytes = wavBytes(snip, sampler)
                val expected = originalHashes[mode] as? String ?: error("$id has no original $mode fingerprint")
                require(sha256(bytes) == expected) { "$id $mode does not reproduce the original published R1 audio" }
                preserveWav(File(root, row.getValue(mode) as String), bytes, expected)
            }
            println("[baseline] verified original R1 $id")
        }
    }

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

    private fun fromJson(value: JsonValue): Any? = when (value) {
        JsonValue.Null -> null
        is JsonValue.Str -> value.value
        is JsonValue.Bool -> value.value
        is JsonValue.Num -> value.value
        is JsonValue.Obj -> value.entries.mapValues { fromJson(it.value) }
        is JsonValue.Arr -> value.items.map { fromJson(it) }
    }
}
