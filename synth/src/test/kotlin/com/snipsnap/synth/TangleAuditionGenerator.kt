package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlin.math.abs

/**
 * Reproducible listening delivery from the real JVM engine, with no browser sound substitute.
 * The full probe matrix covers all six voices; the listening page keeps two representative
 * macro-sweep and interaction voices so publication does not require hundreds of duplicate files.
 * Numeric checks establish bounded behavior; the owner must still judge the musical result.
 */
object TangleAuditionGenerator {
    // One fixed export gain retains raw waveform shape and relative levels within PCM range.
    private const val RAW_EXPORT_GAIN = .5f
    private val timbral = listOf("TANGLE", "CHASE", "CATCH", "BITE", "WEIGHT")
    private val steps = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    private val pairs = listOf("TANGLE" to "CATCH", "CHASE" to "CATCH", "TANGLE" to "BITE", "WEIGHT" to "TANGLE")

    private data class Case(
        val id: String, val title: String, val description: String, val kind: String,
        val voice: TangleVoice, val group: String, val macros: Map<String, Float>,
        val midi: Int = Tangle.rootMidi(voice), val velocity: Float = 1f,
        val publish: Boolean = true, val macro: String? = null, val value: Float? = null,
        val capture: Boolean? = null, val preset: TanglePatch? = null,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/tangle-audition")
        val quick = "--quick" in args
        root.mkdirs()
        val entries = mutableListOf<Map<String, Any?>>()
        val reports = mutableListOf<Map<String, Any?>>()
        val sweepSamples = mutableMapOf<String, Pair<Float, FloatArray>>()
        val determinism = linkedMapOf<String, Boolean>()
        val started = System.nanoTime()

        val cases = cases(quick)
        for ((index, case) in cases.withIndex()) {
            val began = System.nanoTime()
            val probe = Tangle.probe(case.voice, case.macros, case.midi, case.velocity)
            val raw = Snip(probe.samples, channels = 1, sampleRate = Dsp.RATE)
            val isHold = Tangle.isLoop(case.macros.getValue("HOLD"))
            val loop = if (isHold) Tangle.renderLoopMeasured(case.voice, case.macros, case.midi, case.velocity) else null
            val shipped = if (loop != null) Snip(loop.loop, channels = 1, sampleRate = Dsp.RATE) else {
                val samples = probe.samples.copyOf()
                Dsp.levelTo(samples, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
                Snip(samples, channels = 1, sampleRate = Dsp.RATE)
            }
            val elapsed = (System.nanoTime() - began) / 1e6
            val metrics = LinkedHashMap(TangleMeasure.metrics(probe.raw, shipped, case.midi, elapsed))
            val matched = AuditionLevel.level(shipped)
            metrics["matchedLoudness"] = Loudness.of(matched)
            metrics["rawSampleRate"] = probe.sampleRate
            metrics["rawExportGain"] = RAW_EXPORT_GAIN
            if (loop != null) {
                metrics["seamError"] = loop.seam
                metrics["loopStateError"] = loop.stateError
                metrics["loopAttempts"] = loop.attempts
                val gain = Loudness.of(matched) / Loudness.of(shipped).coerceAtLeast(1e-12f)
                val context = Snip(FloatArray(loop.seamContext.size) { loop.seamContext[it] * gain }, 1, Dsp.RATE)
                val encoded = ByteArrayOutputStream().also { WavWriter.write(it, context, WavWriter.BitDepth.PCM_16) }
                metrics["exportedSeamError"] = Keys.seamError(WavReader.read(encoded.toByteArray()).samples, loop.loopStartFrame)
            }
            if (case.kind == "sweep") {
                val key = "${case.voice}:${case.macro}"
                val previous = sweepSamples[key]
                if (previous != null) {
                    metrics["macroDistance"] = TangleMeasure.distance(previous.second, probe.samples)
                    metrics["macroSpectralDistance"] = TangleMeasure.spectralDistance(previous.second, probe.samples)
                    metrics["macroPreviousValue"] = previous.first
                }
                sweepSamples[key] = case.value!! to probe.samples
            }
            if (case.kind == "defaults") {
                val repeated = Tangle.probe(case.voice, case.macros, case.midi, case.velocity)
                determinism[case.voice.name] = probe.raw.contentEquals(repeated.raw) && probe.samples.contentEquals(repeated.samples)
                metrics["deterministic"] = determinism.getValue(case.voice.name)
            }
            val patch = case.preset ?: TanglePatch(case.title.take(14).uppercase(), case.voice, case.macros, case.midi, case.velocity)
            val diagnostics = diagnostics(probe.diagnostics)
            val report = linkedMapOf<String, Any?>(
                "id" to case.id, "voice" to case.voice.name, "kind" to case.kind,
                "midi" to case.midi, "velocity" to case.velocity, "macros" to case.macros,
                "metrics" to metrics, "diagnostics" to diagnostics, "published" to case.publish,
            )
            reports += report
            check(metrics["finite"] == true) { "${case.id}: non-finite samples" }
            check(probe.diagnostics.rawPeak < 1.5) { "${case.id}: raw peak ${probe.diagnostics.rawPeak} exceeds internal budget" }
            check(abs(shipped.samples.average()) < 0.015) { "${case.id}: DC ${shipped.samples.average()}" }
            if (loop != null) check(loop.seam < 1e-3) { "${case.id}: loop seam ${loop.seam}" }
            if (loop != null) check((metrics["exportedSeamError"] as Double) < 1e-3) { "${case.id}: exported loop seam ${metrics["exportedSeamError"]}" }
            if (case.publish) {
                val file = "wav/${case.id}.wav"
                val rawFile = "raw/${case.id}.wav"
                val patchFile = "patches/${case.id}.json"
                WavWriter.write(File(root, file), matched, WavWriter.BitDepth.PCM_16)
                val rawExport = Snip(FloatArray(raw.samples.size) { raw.samples[it] * RAW_EXPORT_GAIN }, 1, Dsp.RATE)
                check(rawExport.samples.all { abs(it) < 1f }) { "${case.id}: raw PCM export exceeds unity at the common gain" }
                WavWriter.write(File(root, rawFile), rawExport, WavWriter.BitDepth.PCM_16)
                File(root, patchFile).also { it.parentFile.mkdirs() }.writeText(patch.toJsonText())
                entries += linkedMapOf(
                    "id" to case.id, "title" to case.title, "description" to case.description,
                    "kind" to case.kind, "voice" to case.voice.name, "group" to case.group,
                    "midi" to case.midi, "velocity" to case.velocity,
                    "macro" to case.macro, "value" to case.value, "capture" to case.capture,
                    "file" to file, "rawFile" to rawFile, "patchFile" to patchFile,
                    "patch" to patch.toJsonValue(), "metrics" to metrics,
                    "diagnostics" to diagnostics,
                )
            }
            if (index % 12 == 0 || index == cases.lastIndex) println("TANGLE ${index + 1}/${cases.size}: ${case.id} (${elapsed.toInt()} ms)")
        }

        // A higher-rate reference exposes high-register spectral changes separately from finite-output checks.
        val references = (if (quick) listOf(TangleVoice.METAL) else TangleVoice.entries).map { voice ->
            val macros = Tangle.defaults(voice) + timbral.associateWith { 1f }
            val normal = Tangle.probe(voice, macros, midi = 84, oversample = 4, durationSeconds = 1.2f)
            val reference = Tangle.probe(voice, macros, midi = 84, oversample = 8, durationSeconds = 1.2f)
            val highReference = Tangle.probe(voice, macros, midi = 84, oversample = 16, durationSeconds = 1.2f)
            for (probe in listOf(normal, reference, highReference)) {
                check(probe.raw.all { it.isFinite() } && probe.samples.all { it.isFinite() }) { "$voice higher-rate reference is not finite" }
                check(probe.diagnostics.rawPeak < 1.5) { "$voice higher-rate reference raw peak exceeded budget" }
            }
            linkedMapOf<String, Any?>(
                "voice" to voice.name, "midi" to 84, "oversampling" to listOf(4, 8, 16),
                "spectralDistance" to TangleMeasure.spectralDistance(normal.samples, reference.samples),
                "spectralDistance4x8x" to TangleMeasure.spectralDistance(normal.samples, reference.samples),
                "spectralDistance4x16x" to TangleMeasure.spectralDistance(normal.samples, highReference.samples),
                "spectralDistance8x16x" to TangleMeasure.spectralDistance(reference.samples, highReference.samples),
                "rawPeak4x" to normal.diagnostics.rawPeak, "rawPeak8x" to reference.diagnostics.rawPeak, "rawPeak16x" to highReference.diagnostics.rawPeak,
                "description" to "normalized 12-band power-spectrum distance; an engineering reference, not a claim that all remaining energy is aliasing",
            )
        }
        val metrics = reports.map { it.getValue("metrics") as Map<*, *> }
        fun maximum(key: String) = metrics.mapNotNull { (it[key] as? Number)?.toDouble() }.maxOrNull()
        val standard = reports.filter { it["kind"] in setOf("defaults", "note", "velocity", "preset") }
        val drumReadings = standard.filter { (it["metrics"] as Map<*, *>)["classifier"] in TangleMeasure.drumClasses.map { c -> c.name } }.map { it["id"] }
        fun pitchFailures(cases: List<Map<String, Any?>>): Map<String, Any?> {
            val detectable = cases.filter { (it["midi"] as Int) >= 28 }
            val noConfidence = detectable.filter { ((it["metrics"] as Map<*, *>)["pitchConfidence"] as? Number)?.toDouble()?.let { c -> c < .3 } ?: true }.map { it["id"] }
            val detectorErrors = detectable.filter { ((it["metrics"] as Map<*, *>)["detectorErrorCents"] as? Number)?.toDouble()?.let { c -> abs(c) > 50 } ?: false }.map { it["id"] }
            val rootErrors = cases.filter { ((it["metrics"] as Map<*, *>)["fundamentalErrorCents"] as? Number)?.toDouble()?.let { c -> abs(c) > 25 } ?: false }.map { it["id"] }
            return linkedMapOf("cases" to cases.size, "houseDetectorEligibleCases" to detectable.size,
                "withoutConfidentPitchCount" to noConfidence.size, "withoutConfidentPitch" to noConfidence,
                "detectorErrorOver50CentsCount" to detectorErrors.size, "detectorErrorOver50Cents" to detectorErrors,
                "rootComponentErrorOver25CentsCount" to rootErrors.size, "rootComponentErrorOver25Cents" to rootErrors)
        }
        val summary = linkedMapOf<String, Any?>(
            "probeCases" to reports.size, "listeningClips" to entries.size,
            "presetCount" to entries.count { it["kind"] == "preset" },
            "finiteCases" to metrics.count { it["finite"] == true },
            "deterministicVoices" to determinism, "standardCasesClassifiedAsDrums" to drumReadings,
            "standardDrumClassificationFailureCount" to drumReadings.size,
            "standardPitchResults" to pitchFailures(standard), "allPitchResults" to pitchFailures(reports),
            "maxRawPeak" to maximum("rawPeak"), "maxDcMagnitude" to metrics.maxOf { abs((it["dc"] as Number).toDouble()) },
            "maxHoldSeamError" to maximum("seamError"), "maxLoopStateError" to maximum("loopStateError"),
            "maxExportedHoldSeamError" to maximum("exportedSeamError"),
            "renderSeconds" to (System.nanoTime() - started) / 1e9,
        )
        val machine = linkedMapOf("os" to System.getProperty("os.name"), "arch" to System.getProperty("os.arch"),
            "processors" to Runtime.getRuntime().availableProcessors(), "java" to System.getProperty("java.version"))
        val acceptance = linkedMapOf<String, Any?>(
            "status" to "requires-owner-listening", "numericChecks" to "finite, raw peak < 1.5, DC magnitude < .015, HOLD seam < 1e-3",
            "listeningQuestions" to listOf("Are moderate presets playable?", "Does CHASE change which oscillator leads?", "Does CATCH audibly settle and release?", "Do held controls feel generated by the oscillator encounters?", "Do extreme settings retain pitch and distinction?", "Do HOLD loops retain capture without clicks or stutters?"),
            "holdConstraint" to "HOLD constrains both the encounter/capture control schedule and the oscillator phase trajectory to a repeating period derived from the same causal interaction. A periodic phase-rate correction keeps that trajectory recurrent while previous oscillator outputs still drive modulation. The full synthesis state must converge before exporting; unconstrained free-running behavior becomes a repeating catch sequence.",
        )
        val header = linkedMapOf<String, Any?>("engine" to "TANGLE", "schemaVersion" to 1,
            "sampleRate" to Dsp.RATE, "oversampling" to 4, "generatedAt" to Instant.now().toString(),
            "rawExportGain" to RAW_EXPORT_GAIN,
            "rawExportNote" to "Raw PCM WAVs use one common 0.5 gain (−6.02 dB) so the prelevel waveform fits PCM without clipping. Raw peak/RMS/DC and internal bounds are measured before this export gain; relative raw levels and waveform shape are preserved.",
            "machine" to machine, "quick" to quick, "notes" to listOf(24, 36, 48, 60, 84),
            "summary" to summary, "acceptance" to acceptance)
        File(root, "manifest.json").writeText(Json.write(json(header + ("entries" to entries))))
        File(root, "probes.json").writeText(Json.write(json(header + mapOf("cases" to reports, "higherRateReferences" to references))))
        val page = TangleAuditionGenerator::class.java.getResourceAsStream("/audition/tangle-audition.html")
            ?: error("Missing synth/src/test/resources/audition/tangle-audition.html")
        File(root, "index.html").outputStream().use { out -> page.use { it.copyTo(out) } }
        check(determinism.values.all { it }) { "non-deterministic voice: $determinism" }
        check(drumReadings.isEmpty()) { "standard pitched cases read as drums: $drumReadings" }
        println("TANGLE wrote ${entries.size} listening clips, ${reports.size} probe cases, patches and page under ${root.absolutePath}")
        println(Json.write(json(summary)))
    }

    private fun cases(quick: Boolean): List<Case> = buildList {
        val voices = if (quick) listOf(TangleVoice.BASS, TangleVoice.LEAD, TangleVoice.METAL) else TangleVoice.entries
        for (voice in voices) {
            val notes = if (quick) listOf(Tangle.rootMidi(voice)) else listOf(24, Tangle.rootMidi(voice), 84)
            for (midi in notes) for (velocity in if (quick) listOf(1f) else listOf(0.35f, 0.7f, 1f)) {
                val kind = if (midi == Tangle.rootMidi(voice) && velocity == 1f) "defaults" else if (midi == Tangle.rootMidi(voice)) "velocity" else "note"
                add(Case("default_${voice.name.lowercase()}_${midi}_${(velocity * 100).toInt()}", "${voice.name} ${noteName(midi)} V${(velocity * 100).toInt()}",
                    "Voice defaults, MIDI $midi, velocity ${velocity}. Empty effects chain.", kind, voice, "Voice / register / velocity", Tangle.defaults(voice), midi, velocity,
                    publish = velocity == 1f || midi == Tangle.rootMidi(voice)))
            }
        }
        for (preset in if (quick) TanglePresets.all().filterIndexed { index, _ -> index % 6 == 0 } else TanglePresets.all()) {
            add(Case("preset_${preset.voice.name.lowercase()}_${preset.name.lowercase().replace(' ', '_')}", preset.name,
                "Factory ${TanglePresets.groupFor(preset).name.lowercase()} recipe at ${noteName(preset.midi)}. ${if (Tangle.isLoop(preset.macros.getValue("HOLD"))) "Seamless held variant." else "Finite one-shot."}",
                "preset", preset.voice, TanglePresets.groupFor(preset).name, preset.macros, preset.midi, preset.velocity, preset = preset))
        }
        for (voice in voices) for (macro in timbral) for (value in if (quick) listOf(0f, 0.5f, 1f) else steps) {
            val neutral = mapOf("TANGLE" to 0.30f, "CHASE" to 0.50f, "CATCH" to 0.25f, "BITE" to 0.40f, "WEIGHT" to 0.45f, "HOLD" to 0f)
            add(Case("sweep_${voice.name.lowercase()}_${macro.lowercase()}_${(value * 100).toInt()}", "$macro ${(value * 100).toInt()}%",
                "${voice.name}: only $macro changes; other timbral controls are neutral. Loudness matched after synthesis.", "sweep", voice, "${voice.name} / $macro", neutral + (macro to value),
                publish = voice in listOf(TangleVoice.BASS, TangleVoice.LEAD), macro = macro, value = value))
        }
        for (voice in voices) for (enabled in listOf(false, true)) {
            add(Case("capture_${voice.name.lowercase()}_${if (enabled) "on" else "off"}", "CATCH ${if (enabled) "ON" else "OFF"}",
                "${voice.name}: TANGLE .25, CATCH ${if (enabled) "1" else "0"}. The two clips share the same loudness; raw downloads preserve prelevel behavior.",
                "capture", voice, "${voice.name} / capture comparison", Tangle.defaults(voice) + mapOf("TANGLE" to 0.25f, "CATCH" to if (enabled) 1f else 0f), capture = enabled))
        }
        for (voice in voices) add(Case("hold_${voice.name.lowercase()}", "${voice.name} HOLD", "One converged synthesis period, with encounter-derived capture and held control repeating. Enable repeat to inspect the wrap.",
            "hold", voice, "Held loops", Tangle.defaults(voice) + mapOf("HOLD" to 1f, "CATCH" to 0.7f)))
        if (!quick) for (voice in voices) for ((a, b) in pairs) for (x in listOf(0f, 1f)) for (y in listOf(0f, 1f)) {
            add(Case("pair_${voice.name.lowercase()}_${a.lowercase()}${x.toInt()}_${b.lowercase()}${y.toInt()}", "$a ${x.toInt()} / $b ${y.toInt()}",
                "${voice.name}: one of the four pair corners; all remaining controls at voice defaults.", "interaction", voice, "${voice.name} / $a × $b", Tangle.defaults(voice) + mapOf(a to x, b to y),
                publish = voice in listOf(TangleVoice.BASS, TangleVoice.METAL)))
        }
        for (voice in voices) for (midi in if (quick) listOf(84) else listOf(24, Tangle.rootMidi(voice), 84)) {
            add(Case("extreme_${voice.name.lowercase()}_$midi", "${voice.name} ALL HIGH", "Every timbral macro at 1, MIDI $midi, maximum velocity. HOLD remains finite so the tail can be checked.",
                "extreme", voice, "Bounds / extremes", Tangle.defaults(voice) + timbral.associateWith { 1f }, midi,
                publish = midi == Tangle.rootMidi(voice) || quick))
        }
    }

    private fun diagnostics(d: Tangle.Diagnostics): Map<String, Any?> = linkedMapOf(
        "rawPeak" to d.rawPeak, "rawRms" to d.rawRms, "rawDc" to d.rawDc,
        "minFrequencyHz" to d.minFrequencyHz, "maxFrequencyHz" to d.maxFrequencyHz,
        "minPulseWidth" to d.minPulseWidth, "maxPulseWidth" to d.maxPulseWidth,
        "maxPhaseCorrectionHz" to d.maxPhaseCorrectionHz, "maxFilterState" to d.maxFilterState,
        "captureEventCount" to d.captureEvents.size, "heldEventCount" to d.heldEvents.size,
        "captureEvents" to d.captureEvents.map { linkedMapOf("seconds" to it.seconds, "state" to it.state.name, "phaseError" to it.phaseError, "strength" to it.strength) },
        "heldEvents" to d.heldEvents.map { linkedMapOf("seconds" to it.seconds, "value" to it.value, "sourceA" to it.sourceA, "sourceB" to it.sourceB, "phaseError" to it.phaseError, "encounter" to it.encounter, "relationship" to it.relationship) },
        "loop" to d.loop?.let { linkedMapOf("periodFrames" to it.periodFrames, "periodSeconds" to it.periodSeconds, "attempts" to it.attempts, "stateError" to it.stateError, "conditioningStateError" to it.conditioningStateError, "seam" to it.seam, "converged" to it.converged, "strategy" to it.strategy) },
    )

    private fun noteName(midi: Int) = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is Boolean -> JsonValue.Bool(value)
        is Number -> if (value.toDouble().isFinite()) JsonValue.Num(value.toDouble()) else JsonValue.Null
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("Unsupported audition JSON value ${value.javaClass.name}")
    }
}
