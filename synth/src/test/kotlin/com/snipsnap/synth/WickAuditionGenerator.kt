package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * First WICK listening milestone: PLUCK and BUBBLE from the actual JVM engine.
 * Native, prelevel raw and matched audio have separate files; diagnostic options
 * have explicit replay recipes rather than masquerading as ordinary patches.
 * A failed held convergence remains visible in the manifest and fails the task.
 */
object WickAuditionGenerator {
    private const val RAW_EXPORT_GAIN = .5f
    private const val MAX_EXPORTED_SEAM = 1e-3
    private const val MAX_WRAP_STEP_RATIO = 8.0
    private val timbral = listOf("SPARK", "FEED", "CASCADE", "TIMBRE", "RECOVERY")
    private val steps = listOf(0f, .25f, .5f, .75f, 1f)

    private data class Case(
        val id: String,
        val title: String,
        val description: String,
        val kind: String,
        val voice: WickVoice,
        val groupId: String,
        val group: String,
        val macros: Map<String, Float> = Wick.defaults(voice),
        val midi: Int = Wick.rootMidi(voice),
        val velocity: Float = 1f,
        val options: Wick.Options = Wick.Options(),
        val macro: String? = null,
        val value: Float? = null,
        val comparison: String? = null,
        val variant: String? = null,
        val preset: WickPatch? = null,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/wick-audition").absoluteFile
        val quick = "--quick" in args
        root.mkdirs()
        // A previous successful manifest must not survive a failed new render.
        File(root, "manifest.json").delete()
        val entries = mutableListOf<Map<String, Any?>>()
        val failures = mutableListOf<Map<String, Any?>>()
        val sweepPrevious = mutableMapOf<String, Pair<Float, FloatArray>>()
        val comparisonBase = mutableMapOf<String, FloatArray>()
        val started = System.nanoTime()
        val cases = cases(quick)
        check(cases.map { it.id }.distinct().size == cases.size) { "Duplicate WICK audition clip ID" }

        for ((index, case) in cases.withIndex()) {
            val began = System.nanoTime()
            try {
                val probe = Wick.inspect(case.voice, case.macros, case.midi, case.velocity, case.options)
                val elapsed = (System.nanoTime() - began) / 1e6
                val native = Snip(probe.samples, 1, Dsp.RATE)
                val matched = AuditionLevel.level(native)
                val raw = Snip(FloatArray(probe.raw.size) { probe.raw[it] * RAW_EXPORT_GAIN }, 1, Dsp.RATE)
                check(probe.raw.isNotEmpty() && probe.samples.isNotEmpty()) { "empty audio" }
                check(listOf(probe.raw, native.samples, matched.samples).all { samples -> samples.all { it.isFinite() } }) { "non-finite audio" }
                check(raw.peak() < 1f) { "raw audio exceeds common PCM export gain: peak ${probe.diagnostics.rawPeak}" }
                check(native.peak() <= 1f && matched.peak() <= 1f) { "native/matched audio exceeds PCM range" }

                val metrics = LinkedHashMap(TangleMeasure.metrics(probe.raw, native, case.midi, elapsed))
                val onset = Snip(native.samples.copyOfRange(0, minOf(native.samples.size, (1.2 * Dsp.RATE).toInt())), 1, Dsp.RATE)
                val onsetClassification = Classifier.classify(onset)
                metrics["onsetClassifier"] = onsetClassification.drumClass.name
                metrics["onsetClassifierConfidence"] = onsetClassification.confidence
                metrics["onsetClassifierWindowSeconds"] = onset.durationSeconds
                metrics["matchedLoudness"] = Loudness.of(matched)
                metrics["nativeLoudness"] = Loudness.of(native)
                metrics["matchedPeak"] = matched.peak()
                metrics["rawExportGain"] = RAW_EXPORT_GAIN
                metrics["eventCount"] = probe.diagnostics.events.size
                val localFailures = mutableListOf<String>()
                val held = Wick.isLoop(case.macros.getValue("HOLD"))
                val loop = probe.diagnostics.loop
                var seamContextFile: String? = null
                if (held) {
                    if (loop == null) {
                        localFailures += "HOLD requested but no cycle diagnostics were supplied"
                    } else {
                        metrics["seamError"] = loop.seam
                        metrics["loopStateError"] = loop.stateError
                        metrics["loopAttempts"] = loop.attempts
                        if (!loop.converged) localFailures += "HOLD state did not converge"
                        if (!loop.seam.isFinite() || loop.seam >= MAX_EXPORTED_SEAM) localFailures += "independent-cycle seam ${loop.seam}"
                    }
                    val context = probe.seamContext
                    if (context == null || context.size < probe.samples.size + 256) {
                        localFailures += "HOLD has no independently generated preceding-cycle context"
                    } else {
                        // The renderer supplies previous and current independently generated
                        // periods. Apply exactly the export gain, encode, then read the PCM.
                        val gain = (TangleMeasure.rms(matched.samples) / TangleMeasure.rms(native.samples).coerceAtLeast(1e-20)).toFloat()
                        val contextSnip = Snip(FloatArray(context.size) { context[it] * gain }, 1, Dsp.RATE)
                        val encoded = ByteArrayOutputStream().also { WavWriter.write(it, contextSnip, WavWriter.BitDepth.PCM_16) }
                        val decoded = WavReader.read(encoded.toByteArray())
                        val loopStart = context.size - probe.samples.size
                        var differenceEnergy = 0.0
                        var referenceEnergy = 0.0
                        for (frame in loopStart - 256 until loopStart) {
                            val previous = decoded.samples[frame].toDouble()
                            val current = decoded.samples[frame + probe.samples.size].toDouble()
                            val difference = current - previous
                            differenceEnergy += difference * difference
                            referenceEnergy += previous * previous
                        }
                        val silentBoundary = referenceEnergy == 0.0 && differenceEnergy == 0.0
                        val exportedSeam = when {
                            referenceEnergy > 0.0 -> differenceEnergy / referenceEnergy
                            silentBoundary -> 0.0
                            else -> Double.POSITIVE_INFINITY
                        }
                        // Also inspect the actual exported end-to-start join. A silent
                        // comparison window alone must not hide a discontinuous onset.
                        val wrapStep = abs(decoded.samples[loopStart].toDouble() - decoded.samples.last())
                        var derivativeEnergy = 0.0
                        var derivativeCount = 0
                        for (frame in loopStart + 1 until minOf(loopStart + 256, decoded.samples.size)) {
                            val derivative = decoded.samples[frame].toDouble() - decoded.samples[frame - 1]
                            derivativeEnergy += derivative * derivative
                            derivativeCount++
                        }
                        for (frame in maxOf(loopStart + 1, decoded.samples.size - 255) until decoded.samples.size) {
                            val derivative = decoded.samples[frame].toDouble() - decoded.samples[frame - 1]
                            derivativeEnergy += derivative * derivative
                            derivativeCount++
                        }
                        val derivativeRms = sqrt(derivativeEnergy / derivativeCount.coerceAtLeast(1))
                        val wrapStepRatio = wrapStep / (derivativeRms + 1.0 / 32768.0)
                        metrics["exportedSeamError"] = exportedSeam
                        metrics["exportedSeamLimit"] = MAX_EXPORTED_SEAM
                        metrics["silentBoundary"] = silentBoundary
                        metrics["seamComparisonDifferenceEnergy"] = differenceEnergy
                        metrics["seamComparisonReferenceEnergy"] = referenceEnergy
                        metrics["exportedWrapStep"] = wrapStep
                        metrics["exportedWrapDerivativeRms"] = derivativeRms
                        metrics["exportedWrapStepRatio"] = wrapStepRatio
                        metrics["exportedWrapStepRatioLimit"] = MAX_WRAP_STEP_RATIO
                        metrics["exportedLoopJoinSamplesZero"] = decoded.samples[loopStart] == 0f && decoded.samples.last() == 0f
                        metrics["seamContextFrames"] = context.size
                        metrics["seamLoopStartFrame"] = context.size - probe.samples.size
                        seamContextFile = "diagnostics/${case.id}_seam-context.wav"
                        File(root, seamContextFile).also { it.parentFile.mkdirs() }.writeBytes(encoded.toByteArray())
                        if (!exportedSeam.isFinite() || exportedSeam >= MAX_EXPORTED_SEAM) localFailures += "PCM16 independent-cycle seam $exportedSeam"
                        if (!wrapStepRatio.isFinite() || wrapStepRatio > MAX_WRAP_STEP_RATIO) localFailures += "PCM16 wrap step ratio $wrapStepRatio exceeds $MAX_WRAP_STEP_RATIO"
                    }
                }

                if (case.kind == "sweep" || case.kind == "held-sweep") {
                    val key = case.groupId
                    sweepPrevious[key]?.let { (value, previous) ->
                        metrics["macroPreviousValue"] = value
                        metrics["macroDistance"] = TangleMeasure.distance(previous, probe.samples)
                        metrics["macroSpectralDistance"] = TangleMeasure.spectralDistance(previous, probe.samples)
                    }
                    sweepPrevious[key] = requireNotNull(case.value) to probe.samples
                }
                case.comparison?.let { pair ->
                    val base = comparisonBase[pair]
                    if (base == null) comparisonBase[pair] = probe.samples else {
                        metrics["comparisonDistance"] = TangleMeasure.distance(base, probe.samples)
                        metrics["comparisonSpectralDistance"] = TangleMeasure.spectralDistance(base, probe.samples)
                    }
                }
                if (case.kind == "defaults") {
                    val repeat = Wick.inspect(case.voice, case.macros, case.midi, case.velocity, case.options)
                    val deterministic = repeat.raw.contentEquals(probe.raw) && repeat.samples.contentEquals(probe.samples)
                    metrics["deterministic"] = deterministic
                    if (!deterministic) localFailures += "repeated render was not deterministic"
                }

                val patch = case.preset ?: WickPatch(case.title.take(14), case.voice, case.macros, case.midi, case.velocity)
                val isAblation = case.options.disableCascade || case.options.constantAvailability || case.options.gainOnly
                val patchFile = if (isAblation) null else "patches/${case.id}.json"
                val recipeFile = if (isAblation) "recipes/${case.id}.json" else null
                patchFile?.let { File(root, it).also { file -> file.parentFile.mkdirs() }.writeText(patch.toJsonText()) }
                recipeFile?.let {
                    writeJson(File(root, it), linkedMapOf(
                        "format" to "wick-diagnostic-recipe-v1", "description" to "Call Wick.inspect with this patch and these diagnostic options. This is not a standard patch.",
                        "patch" to patch.toJsonValue(), "options" to options(case.options),
                    ))
                }
                val file = "wav/${case.id}.wav"
                val nativeFile = "native/${case.id}.wav"
                val rawFile = "raw/${case.id}.wav"
                WavWriter.write(File(root, file), matched, WavWriter.BitDepth.PCM_16)
                WavWriter.write(File(root, nativeFile), native, WavWriter.BitDepth.PCM_24)
                WavWriter.write(File(root, rawFile), raw, WavWriter.BitDepth.PCM_24)
                val diagnosticsFile = "diagnostics/${case.id}.json"
                val diagnostics = diagnostics(probe.diagnostics)
                writeJson(File(root, diagnosticsFile), linkedMapOf(
                    "id" to case.id, "voice" to case.voice.name, "midi" to case.midi, "velocity" to case.velocity,
                    "macros" to case.macros, "options" to options(case.options), "metrics" to metrics,
                    "diagnostics" to diagnostics, "seamContextFile" to seamContextFile,
                    "status" to if (localFailures.isEmpty()) "ready" else "failed", "failures" to localFailures,
                ))
                entries += metadata(case) + linkedMapOf(
                    "file" to file, "nativeFile" to nativeFile, "rawFile" to rawFile,
                    "patchFile" to patchFile, "recipeFile" to recipeFile, "patch" to if (isAblation) null else patch.toJsonValue(),
                    "diagnosticsFile" to diagnosticsFile, "seamContextFile" to seamContextFile,
                    "metrics" to metrics, "diagnostics" to (diagnostics - "events" - "replaySourceEvents"),
                    "status" to if (localFailures.isEmpty()) "ready" else "failed", "failures" to localFailures,
                )
                if (localFailures.isNotEmpty()) failures += linkedMapOf("id" to case.id, "held" to held, "reasons" to localFailures)
            } catch (error: Exception) {
                val reason = "${error.javaClass.simpleName}: ${error.message}"
                failures += linkedMapOf("id" to case.id, "held" to Wick.isLoop(case.macros.getValue("HOLD")), "reasons" to listOf(reason))
                entries += metadata(case) + mapOf("status" to "failed", "failures" to listOf(reason))
                System.err.println("WICK ${case.id}: $reason")
            }
            if (index % 10 == 0 || index == cases.lastIndex) println("WICK ${index + 1}/${cases.size}: ${case.id}")
        }

        val higherRateReferences = mutableListOf<Map<String, Any?>>()
        for (voice in WickVoice.entries) {
            try {
                higherRateReferences += higherRateReference(root, voice)
            } catch (error: Exception) {
                val reason = "${error.javaClass.simpleName}: ${error.message}"
                failures += linkedMapOf("id" to "reference_${voice.name.lowercase()}_84", "held" to false, "reasons" to listOf(reason))
                higherRateReferences += linkedMapOf("voice" to voice.name, "midi" to 84, "status" to "failed", "failure" to reason)
            }
        }
        val metrics = entries.mapNotNull { it["metrics"] as? Map<*, *> }
        fun maximum(key: String) = metrics.mapNotNull { (it[key] as? Number)?.toDouble() }.maxOrNull()
        val groups = cases.groupBy { it.groupId }.map { (id, members) -> linkedMapOf(
            "id" to id, "title" to members.first().group,
            "description" to groupDescription(members.first()), "clipIds" to members.map { it.id },
        ) }
        val summary = linkedMapOf<String, Any?>(
            "clipCount" to entries.size, "readyClips" to entries.count { it["status"] == "ready" },
            "failedCases" to failures, "loopFailures" to failures.filter { it["held"] == true },
            "heldClips" to entries.count { it["loop"] == true },
            "higherRateReferences" to higherRateReferences.size,
            "maxRawPeak" to maximum("rawPeak"), "maxExportedSeamError" to maximum("exportedSeamError"),
            "renderSeconds" to (System.nanoTime() - started) / 1e9,
            "deterministicDefaults" to entries.filter { it["kind"] == "defaults" }.associate { entry ->
                entry.getValue("voice").toString() to ((entry["metrics"] as? Map<*, *>)?.get("deterministic") ?: false)
            },
        )
        writeJson(File(root, "manifest.json"), linkedMapOf(
            "engine" to "WICK", "schemaVersion" to 1, "status" to if (failures.isEmpty()) "requires-owner-listening" else "render-failed",
            "title" to "WICK · First spark", "sampleRate" to Dsp.RATE, "channels" to 1, "oversampling" to 4,
            "generatedAt" to Instant.now().toString(), "quick" to quick, "rawExportGain" to RAW_EXPORT_GAIN,
            "rawExportNote" to "Raw PCM24 files preserve the prelevel waveform at one common 0.5 gain (-6.02 dB). Raw peak, RMS and DC metrics precede that gain. Native PCM24 files are the engine's normal dry output; matched PCM16 files use the shared 0.03 loudest-200-ms loudness target.",
            "holdNote" to "HOLD uses periodic source timing and periodic-state convergence. A causal-schedule-replay strategy is explicitly reported when used. The seam-context WAV holds an independently generated preceding period and current period, at the matched export gain; it is not a duplicated loop.",
            "pcmSeamNote" to "Exported PCM16 seam error is the squared difference between independently generated preceding/current final 256-frame windows, divided by preceding-window energy. If both windows are exactly zero, error is 0 and silentBoundary=true; a zero-energy reference with a nonzero difference fails. The actual loop join is screened separately: absolute wrap step divided by local derivative RMS plus one PCM16 unit must be at most 8. This numerical screening does not replace listening.",
            "scope" to "Two-voice listening prototype. No rack effects. Names and musical acceptance remain provisional. Ordinary MPC drum pads play the exported held material once.",
            "classifierNote" to "metrics.classifier and classifierConfidence describe the whole file; the house classifier labels files longer than 1.5 seconds LOOP before judging percussion. The separate onsetClassifier and onsetClassifierConfidence analyze only the first 1.2 seconds, exposing onset/percussion decisions independently of file duration. Neither classifier is a substitute for the requested-root pitch measurements or listening.",
            "listeningQuestions" to listOf(
                "Does one spark audibly start a chain of gate openings?", "Does disconnecting the cascade remove later articulations?",
                "Does rapid feeding reveal depletion and recovery memory?", "Does gain-only gating lose the changing brightness of the low-pass gates?",
                "Do both voices remain useful pitched samples?", "Do held patterns repeat without audible clicks or stutters?",
            ),
            "summary" to summary, "higherRateReferences" to higherRateReferences, "groups" to groups, "entries" to entries,
        ))
        for (name in listOf("index.html", "app.js", "style.css")) {
            WickAuditionGenerator::class.java.getResourceAsStream("/audition/wick/$name")?.use { source ->
                File(root, name).outputStream().use { source.copyTo(it) }
            }
        }
        println("WICK wrote ${entries.size} clips, ${failures.size} failures under ${root.absolutePath}")
        println(Json.write(json(summary)))
        check(failures.isEmpty()) { "WICK audition has ${failures.size} failed cases; see manifest.json before publishing" }
    }

    /** Higher-rate evidence only: differences are measured, without inventing an alias acceptance threshold. */
    private fun higherRateReference(root: File, voice: WickVoice): Map<String, Any?> {
        val macros = Wick.defaults(voice) + mapOf("SPARK" to .7f, "FEED" to .25f, "CASCADE" to .5f, "TIMBRE" to 1f, "RECOVERY" to .45f)
        val probes = listOf(4, 8).associateWith { rate ->
            Wick.inspect(voice, macros, midi = 84, options = Wick.Options(oversample = rate))
        }
        val spectra = probes.mapValues { (_, probe) -> referenceSpectrum(probe.raw) }
        val normal = spectra.getValue(4)
        val reference = spectra.getValue(8)
        val fullSpectrumDistance = normal.indices.sumOf { abs(normal[it] - reference[it]) } / 2
        val readings = probes.map { (rate, probe) ->
            check(probe.raw.all { it.isFinite() } && probe.samples.all { it.isFinite() }) { "$voice ${rate}x reference contains non-finite audio" }
            val raw = Snip(FloatArray(probe.raw.size) { probe.raw[it] * RAW_EXPORT_GAIN }, 1, Dsp.RATE)
            check(raw.peak() < 1f) { "$voice ${rate}x reference exceeds common raw PCM export gain" }
            val file = "references/${voice.name.lowercase()}_84_${rate}x_raw.wav"
            WavWriter.write(File(root, file), raw, WavWriter.BitDepth.PCM_24)
            val spectrum = spectra.getValue(rate)
            val above10k = spectrum.indices.filter { it.toDouble() * Dsp.RATE / REFERENCE_FFT_SIZE >= 10_000 }.sumOf { spectrum[it] }
            linkedMapOf<String, Any?>(
                "oversampling" to rate, "rawFile" to file, "rawExportGain" to RAW_EXPORT_GAIN,
                "rawPeak" to probe.diagnostics.rawPeak, "rawRms" to probe.diagnostics.rawRms, "rawDc" to probe.diagnostics.rawDc,
                "requestedRootEnergyShare" to TangleMeasure.toneShare(probe.raw, Keys.midiHz(84).toDouble()),
                "normalizedTwelveBandPower" to TangleMeasure.spectrum(probe.raw).toList(),
                "powerAbove10kFraction" to above10k, "eventCount" to probe.diagnostics.events.size,
                "maxFilterState" to probe.diagnostics.maxFilterState,
            )
        }
        return linkedMapOf(
            "voice" to voice.name, "midi" to 84, "velocity" to 1f, "requestedRootHz" to Keys.midiHz(84),
            "macros" to macros, "oversampling" to listOf(4, 8), "status" to "measured-requires-review",
            "description" to "Bright high-register 4x production render versus an 8x numerical reference at the same requested note. Both raw signals are measured before leveling. Spectral differences can include event timing and numerical filtering; this does not isolate alias power or establish a pass threshold.",
            "normalizedFullSpectrumDistance4x8x" to fullSpectrumDistance,
            "normalizedTwelveBandDistance4x8x" to TangleMeasure.spectralDistance(probes.getValue(4).raw, probes.getValue(8).raw),
            "gainIndependentWaveformDistance4x8x" to TangleMeasure.distance(probes.getValue(4).raw, probes.getValue(8).raw),
            "spectrumWindowStartSeconds" to .1, "spectrumFftSize" to REFERENCE_FFT_SIZE,
            "measurements" to readings,
        )
    }

    private const val REFERENCE_FFT_SIZE = 16_384

    private fun referenceSpectrum(samples: FloatArray): DoubleArray {
        val start = minOf((Dsp.RATE * .1).toInt(), (samples.size - REFERENCE_FFT_SIZE).coerceAtLeast(0))
        val magnitude = Fft.magnitudeSpectrum(samples.copyOfRange(start, minOf(samples.size, start + REFERENCE_FFT_SIZE)), REFERENCE_FFT_SIZE)
        val power = DoubleArray(magnitude.size) { if (it == 0) 0.0 else magnitude[it].toDouble() * magnitude[it] }
        val total = power.sum().coerceAtLeast(1e-30)
        return DoubleArray(power.size) { power[it] / total }
    }

    private fun metadata(case: Case): Map<String, Any?> = linkedMapOf(
        "id" to case.id, "title" to case.title, "description" to case.description, "kind" to case.kind,
        "voice" to case.voice.name, "group" to case.group, "groupId" to case.groupId,
        "midi" to case.midi, "velocity" to case.velocity, "macros" to case.macros, "options" to options(case.options),
        "loop" to Wick.isLoop(case.macros.getValue("HOLD")), "macro" to case.macro, "value" to case.value,
        "comparison" to case.comparison, "variant" to case.variant,
    )

    private fun cases(quick: Boolean): List<Case> = buildList {
        for (voice in WickVoice.entries) add(Case(
            "default_${voice.name.lowercase()}", "${voice.name} · first spark", "Dry voice default at MIDI ${Wick.rootMidi(voice)}, velocity 1. " +
                (if (voice == WickVoice.BUBBLE) "Calmer FEED 25% baseline; the original FEED 65% sound is preserved as Busy Bubbles under Range & recipes. " else "") +
                "The same initial excitation and release network used by every normal patch.",
            "defaults", voice, "defaults", "Start here · two dry voices",
        ))
        for (preset in WickPresets.all().filter { it.name != "Small Spark" }) add(Case(
            "preset_${slug(preset.name)}", preset.name,
            if (preset.name == "Busy Bubbles") "Original BUBBLE default, preserved at FEED 65%. Compare with the calmer FEED 25% first spark; all other controls, pitch and velocity match."
            else "Provisional ${preset.voice.name.lowercase()} recipe. Dry engine output, with no rack effects.",
            "preset", preset.voice, "presets", "Seven provisional patches", preset.macros, preset.midi, preset.velocity, preset = preset,
        ))
        val base = Wick.defaults(WickVoice.BUBBLE) + mapOf("SPARK" to .65f, "FEED" to .85f, "CASCADE" to .90f, "RECOVERY" to .90f)
        val comparisons = listOf(
            Triple("cascade", "Release transmission", Wick.Options(disableCascade = true)),
            Triple("depletion", "Excitation availability", Wick.Options(constantAvailability = true)),
            Triple("lpg", "Brightness and opening", Wick.Options(gainOnly = true)),
        )
        for ((id, title, options) in comparisons) for (altered in listOf(false, true)) add(Case(
            "mechanism_${id}_${if (altered) "off" else "on"}",
            if (!altered) "Full network" else when (id) { "cascade" -> "Cascade disconnected"; "depletion" -> "Constant availability"; else -> "Gain-only gates" },
            when (id) {
                "cascade" -> "Matched pair: same source, pulse recipe and recovery; the altered version prevents release events from reaching the next gate."
                "depletion" -> "Matched pair: same controls; the altered version makes available excitation constant, removing depletion memory."
                else -> "Matched pair: same controls; the altered version removes the low-pass gate's opening-to-brightness coupling."
            }, "comparison", WickVoice.BUBBLE, "mechanism-$id", title, base,
            options = if (altered) options else Wick.Options(), comparison = id, variant = if (altered) "ablated" else "full",
        ))
        for (voice in WickVoice.entries) for (macro in timbral) for (value in if (quick) listOf(0f, 1f) else steps) add(Case(
            "sweep_${voice.name.lowercase()}_${macro.lowercase()}_${tag(value)}", "$macro ${(value * 100).toInt()}%",
            "${voice.name}: only $macro changes. All companion controls retain this voice's defaults; finite sample, loudness matched.",
            "sweep", voice, "sweep-${voice.name.lowercase()}-${macro.lowercase()}", "${voice.name} · $macro",
            Wick.defaults(voice) + (macro to value), macro = macro, value = value,
        ))
        if (!quick) for (macro in listOf("FEED", "RECOVERY")) for (value in steps) add(Case(
            "held_sweep_bubble_${macro.lowercase()}_${tag(value)}", "Held $macro ${(value * 100).toInt()}%",
            "BUBBLE HOLD: one recurrent period. Only $macro changes; companion controls retain BUBBLE defaults.",
            "held-sweep", WickVoice.BUBBLE, "held-sweep-bubble-${macro.lowercase()}", "BUBBLE HOLD · $macro",
            Wick.defaults(WickVoice.BUBBLE) + mapOf("HOLD" to 1f, macro to value), macro = macro, value = value,
        ))
        for (voice in WickVoice.entries) for ((id, title, controls) in listOf(
            Triple("default", "Default held pattern", emptyMap()),
            Triple("feed_zero", "FEED 0 · sustain floor", mapOf("FEED" to 0f)),
            Triple("dense_slow", "High feed · slow recovery", mapOf("FEED" to 1f, "RECOVERY" to 1f, "CASCADE" to 1f)),
            Triple("cascade_zero", "CASCADE 0 · no transmission", mapOf("CASCADE" to 0f)),
        )) add(Case(
            "hold_${voice.name.lowercase()}_$id", title,
            "${voice.name}: a converged held cycle. The dense/slow setting includes the final-to-first return link. Loop strategy and independently generated PCM seam evidence are available in diagnostics.",
            "hold", voice, "hold-${voice.name.lowercase()}", "${voice.name} · held patterns",
            Wick.defaults(voice) + controls + ("HOLD" to 1f),
        ))
        if (!quick) for (voice in WickVoice.entries) for (midi in listOf(24, 48, 84)) for (velocity in listOf(.25f, .65f, 1f)) add(Case(
            "range_${voice.name.lowercase()}_${midi}_${tag(velocity)}", "${note(midi)} · velocity ${(velocity * 100).toInt()}%",
            "MIDI $midi, velocity $velocity, with ${voice.name} defaults. Matching removes output level as the main explanation for velocity changes; native and raw versions retain it.",
            "range", voice, "range-${voice.name.lowercase()}", "${voice.name} · register and velocity", midi = midi, velocity = velocity,
        ))
        if (!quick) for ((a, b, title) in listOf(
            Triple("FEED", "RECOVERY", "FEED × RECOVERY · repeated excitation"),
            Triple("SPARK", "CASCADE", "SPARK × CASCADE · chain reach"),
        )) for (x in listOf(0f, 1f)) for (y in listOf(0f, 1f)) add(Case(
            "interaction_${a.lowercase()}${tag(x)}_${b.lowercase()}${tag(y)}", "$a ${(x * 100).toInt()}% · $b ${(y * 100).toInt()}%",
            "BUBBLE: one of four control-pair corners. Other controls use voice defaults. CASCADE 1 includes the bounded final-to-first link.",
            "interaction", WickVoice.BUBBLE, "interaction-${a.lowercase()}-${b.lowercase()}", title,
            Wick.defaults(WickVoice.BUBBLE) + mapOf(a to x, b to y),
        ))
    }

    private fun groupDescription(case: Case): String = when (case.kind) {
        "defaults" -> "Listen first to the two dry prototypes. The initial spark drives a shared pitched source through three interacting low-pass gates."
        "preset" -> "Seven provisional recipes, including the original Busy Bubbles reference, Held Glow and Held Bubbles. The two starting voices include the calmer BUBBLE default. Every clip uses its saved pitch, velocity and six controls."
        "comparison" -> case.description
        "sweep" -> "Five settings: 0, 25, 50, 75 and 100%. Companion controls stay at this voice's defaults."
        "held-sweep" -> "Five settings in a sustained periodic pattern. Each clip supplies cycle-state and exported PCM seam evidence."
        "hold" -> "Default, minimum feed, dense slow recovery and disconnected transmission. These files contain one held period; turn on repeat to audition the wrap."
        "range" -> "C1, C3 and C6, each at 25%, 65% and 100% velocity. Both native and matched playback are available."
        else -> "Four corners of the interaction, with companion controls at BUBBLE defaults."
    }

    private fun options(options: Wick.Options): Map<String, Any?> = linkedMapOf(
        "disableCascade" to options.disableCascade, "constantAvailability" to options.constantAvailability,
        "gainOnly" to options.gainOnly, "oversample" to options.oversample,
    )

    private fun diagnostics(d: Wick.Diagnostics): Map<String, Any?> = linkedMapOf(
        "rawPeak" to d.rawPeak, "rawRms" to d.rawRms, "rawDc" to d.rawDc,
        "eventCount" to d.events.size, "maxQueueDepth" to d.maxQueueDepth, "rejectedEvents" to d.rejectedEvents, "endedAdmissions" to d.endedAdmissions,
        "minAvailability" to d.minAvailability, "maxAvailability" to d.maxAvailability,
        "maxCharge" to d.maxCharge, "maxOpening" to d.maxOpening, "maxFilterState" to d.maxFilterState,
        "events" to d.events.map(::event),
        "replaySourceEvents" to d.replaySourceEvents.map(::event),
        "replaySourcePeriodStartSeconds" to d.replaySourcePeriodStartSeconds,
        "loop" to d.loop?.let { loop -> linkedMapOf(
            "periodFrames" to loop.periodFrames, "periodSeconds" to loop.periodSeconds, "attempts" to loop.attempts,
            "stateError" to loop.stateError, "seam" to loop.seam, "converged" to loop.converged, "strategy" to loop.strategy,
            "audioError" to loop.audioError, "requestedHz" to loop.requestedHz, "actualHz" to loop.actualHz,
        ) },
    )

    private fun event(event: Wick.Event): Map<String, Any?> = linkedMapOf(
        "id" to event.id, "parentEventId" to event.parentEventId,
        "timeSeconds" to event.timeSeconds, "parentStage" to event.parentStage, "releaseSeconds" to event.releaseSeconds,
        "destination" to event.destination, "requestedStrength" to event.requestedStrength,
        "admittedStrength" to event.admittedStrength, "availabilityBefore" to event.availabilityBefore,
        "availabilityAfter" to event.availabilityAfter, "kind" to event.kind,
        "derivation" to event.derivation, "sourceEventId" to event.sourceEventId,
    )

    private fun tag(value: Float) = "%03d".format((value * 100).toInt())
    private fun slug(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    private fun note(midi: Int) = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)

    private fun writeJson(file: File, value: Any?) {
        file.parentFile.mkdirs()
        file.writeText(Json.write(json(value)) + "\n")
    }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is Boolean -> JsonValue.Bool(value)
        is Number -> if (value.toDouble().isFinite()) JsonValue.Num(value.toDouble()) else JsonValue.Null
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("Unsupported WICK audition JSON ${value.javaClass.name}")
    }
}
