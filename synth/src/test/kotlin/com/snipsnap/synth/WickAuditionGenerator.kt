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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WICK listening evidence: PLUCK, WOOD, BUBBLE, BREATH, FLICKER and RATTLE from the actual JVM engine.
 * Native, prelevel raw and matched audio have separate files; diagnostic options
 * have explicit replay recipes rather than masquerading as ordinary patches.
 * A failed held convergence remains visible in the manifest and fails the task.
 */
object WickAuditionGenerator {
    private const val RAW_EXPORT_GAIN = .5f
    private const val MAX_EXPORTED_SEAM = 1e-3
    private const val MAX_WRAP_STEP_RATIO = 8.0
    private const val BUBBLE_REFERENCE_REVISION = "140d951a72aaca5f3a42af6b33c484bf4b645d16"
    private const val RATTLE_REFERENCE_REVISION = "3b77d8bd34fdab803dee0504911ac95e32430896"
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
        val referenceRevision: String? = null,
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
                // A source choice is saved recipe state. Rendering only the voice/macros
                // would silently give every Hollow Reed / Rubbed Glass preset Warm Wire.
                val patch = case.preset ?: WickPatch(case.title.take(14), case.voice, case.macros,
                    case.midi, case.velocity, case.options.rattleSource ?: Wick.RattleSource.WARM_WIRE)
                val probe = Wick.inspect(case.voice, case.macros, case.midi, case.velocity, case.options,
                    rattleSource = patch.rattleSource)
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
                if (case.voice == WickVoice.BREATH) {
                    metrics["onsetClassifierDecayMs"] = onsetClassification.features.decayMs
                    metrics.putAll(onsetEnvelopeEvidence(onset.samples, onset.sampleRate))
                }
                metrics["matchedLoudness"] = Loudness.of(matched)
                metrics["nativeLoudness"] = Loudness.of(native)
                metrics["matchedPeak"] = matched.peak()
                metrics["rawExportGain"] = RAW_EXPORT_GAIN
                metrics["eventCount"] = probe.diagnostics.events.size
                if (case.groupId in setOf("bubble-tone", "breath-sustain", "flicker-register", "rattle-register", "rattle-reserve", "rattle-cohesion")) {
                    metrics["toneWindows"] = toneWindows(probe.raw, matched.samples, case.midi)
                    metrics["toneWindowMeasurement"] = "Fixed post-onset time windows: raw RMS, matched RMS, and coherent power at the requested root. The coherent share is a sinusoidal projection of the raw samples, with no pitch search; short-window energy and root share support comparison but do not establish pitch audibility."
                }
                val localFailures = mutableListOf<String>()
                val held = Wick.isLoop(case.macros.getValue("HOLD"))
                if (case.voice == WickVoice.RATTLE) metrics["bodyContinuity"] = bodyContinuity(probe.raw, held)
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
                    val repeat = Wick.inspect(case.voice, case.macros, case.midi, case.velocity, case.options,
                        rattleSource = patch.rattleSource)
                    val deterministic = repeat.raw.contentEquals(probe.raw) && repeat.samples.contentEquals(probe.samples)
                    metrics["deterministic"] = deterministic
                    if (!deterministic) localFailures += "repeated render was not deterministic"
                }

                val isDiagnostic = case.options.disableCascade || case.options.constantAvailability || case.options.gainOnly ||
                    case.options.legacyBubbleEnvelope || case.options.legacyRattleEnvelope
                val patchFile = if (isDiagnostic) null else "patches/${case.id}.json"
                val recipeFile = if (isDiagnostic) "recipes/${case.id}.json" else null
                patchFile?.let { File(root, it).also { file -> file.parentFile.mkdirs() }.writeText(patch.toJsonText()) }
                recipeFile?.let {
                    writeJson(File(root, it), linkedMapOf(
                        "format" to "wick-diagnostic-recipe-v1", "description" to "Call Wick.inspect with this patch and these diagnostic options. This is not a standard patch.",
                        "patch" to patch.toJsonValue(), "options" to options(case.options), "referenceRevision" to case.referenceRevision,
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
                    "referenceRevision" to case.referenceRevision,
                    "diagnostics" to diagnostics, "seamContextFile" to seamContextFile,
                    "status" to if (localFailures.isEmpty()) "ready" else "failed", "failures" to localFailures,
                ))
                entries += metadata(case) + linkedMapOf(
                    "file" to file, "nativeFile" to nativeFile, "rawFile" to rawFile,
                    "patchFile" to patchFile, "recipeFile" to recipeFile, "patch" to if (isDiagnostic) null else patch.toJsonValue(),
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

        val patterns = WickPatternGenerator.generate(root)
        entries += patterns.entries
        failures += patterns.failures
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
        ) } + listOf(patterns.group)
        val summary = linkedMapOf<String, Any?>(
            "clipCount" to entries.size, "readyClips" to entries.count { it["status"] == "ready" },
            "failedCases" to failures, "loopFailures" to failures.filter { it["held"] == true },
            "heldClips" to entries.count { it["loop"] == true },
            "patternClips" to entries.count { it["kind"] == "pattern" },
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
            "rawExportNote" to "Raw PCM24 files preserve the prelevel waveform at one common 0.5 gain (-6.02 dB). Raw peak, RMS and DC metrics precede that gain. Native single-note PCM24 files are the engine's normal dry output. Native musical-pattern PCM24 files instead apply one fixed 0.25 assembly gain to the sum of unlevelled raw note renders, preserving the source velocity response without per-hit leveling. Matched PCM16 files use the shared 0.03 loudest-200-ms loudness target on the complete clip.",
            "holdNote" to "HOLD uses periodic source timing and periodic-state convergence. A causal-schedule-replay strategy is explicitly reported when used. The seam-context WAV holds an independently generated preceding period and current period, at the matched export gain; it is not a duplicated loop.",
            "pcmSeamNote" to "Exported PCM16 seam error is the squared difference between independently generated preceding/current final 256-frame windows, divided by preceding-window energy. If both windows are exactly zero, error is 0 and silentBoundary=true; a zero-energy reference with a nonzero difference fails. The actual loop join is screened separately: absolute wrap step divided by local derivative RMS plus one PCM16 unit must be at most 8. This numerical screening does not replace listening.",
            "scope" to "Six-voice listening prototype with short musical phrases. No rack effects. Names and musical acceptance remain provisional. Ordinary MPC drum pads play the exported held material once.",
            "patternNote" to "Musical phrases share an exact eight-note score at 100 BPM. Native pattern WAVs use one fixed 0.25 assembly gain applied to the sum of probe.raw note renders; they bypass the engine's per-note loudness scaling so its velocity response survives. Raw files apply the same common 0.5 export gain as other clips. Only completed phrases are loudness matched. Complete note tails ring out after the two-bar score. Each note resets the engine state, while its own FEED and cascade can add articulations between scored onsets.",
            "classifierNote" to "metrics.classifier and classifierConfidence describe the whole file; the house classifier labels files longer than 1.5 seconds LOOP before judging percussion. The separate onsetClassifier and onsetClassifierConfidence analyze only the first 1.2 seconds, exposing onset/percussion decisions independently of file duration. BREATH's late swell can place its first near-peak late in that window: when no 20 dB decay is observed, the classifier reports only the remaining window as decay time and may label a sustained low note KICK or TOM. BREATH also reports onsetEnvelopePeakSeconds, onset20dBCrossingObserved, onsetPostPeakMinimumRatio and onsetDecayCensored using the same 1024-frame peak windows and 256-frame hop. The original classifier result is retained. Neither classifier is a substitute for requested-root pitch measurements or listening.",
            "listeningQuestions" to listOf(
                "Does the revised BUBBLE response make the underlying note clearer than the earlier pops at the same controls?",
                "Does one spark audibly start a chain of gate openings?", "Does disconnecting the cascade remove later articulations?",
                "Does rapid feeding reveal depletion and recovery memory?", "Does gain-only gating lose the changing brightness of the low-pass gates?",
                "Do all six voices remain useful pitched samples?", "Does BREATH keep a clear note through its soft overlapping openings?",
                "Does BREATH contrast usefully with the accepted PLUCK, WOOD and revised BUBBLE voices?",
                "Does FLICKER reveal brief metallic openings while retaining a clear requested note?",
                "Does FLICKER add a useful bright articulation beside the preceding voices?",
                "Does the revised RATTLE feel like one connected body with movement inside it, compared with the earlier repeated hits?",
                "Does RATTLE retain a clear note through its internal weakening and recovery?",
                "Does the RATTLE reserve comparison expose a useful difference from constant availability at the same controls?",
                "Do the same musical notes and soft/strong velocities retain each voice's character?", "Do held patterns repeat without audible clicks or stutters?",
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
        "referenceRevision" to case.referenceRevision,
    )

    private fun cases(quick: Boolean): List<Case> = buildList {
        for (voice in WickVoice.entries) add(Case(
            "default_${voice.name.lowercase()}", "${voice.name} · first spark", "Dry voice default at MIDI ${Wick.rootMidi(voice)}, velocity 1. " +
                (when (voice) {
                    WickVoice.BUBBLE -> "FEED 25% baseline with the revised gate response. Compare Earlier pops and Longer pitched tone at the same controls below. "
                    WickVoice.BREATH -> "Soft gate openings overlap around the requested note. Listen for a stable pitched body and a gradual release. "
                    WickVoice.FLICKER -> "Listen for brief bright metallic openings with a clear pitched body underneath. "
                    WickVoice.RATTLE -> "Listen for one connected pitched body with internal rattle movement and recovery. Compare Earlier repeated hits and Connected body at the same controls below. "
                    else -> ""
                }) +
                "The same initial excitation and release network used by every normal patch.",
            "defaults", voice, "defaults", "Start here · six dry voices",
        ))
        val toneControls = linkedMapOf("SPARK" to .40f, "FEED" to .25f, "CASCADE" to .70f, "TIMBRE" to .45f, "RECOVERY" to .50f, "HOLD" to 0f)
        for (midi in listOf(48, 60, 72)) for (reference in listOf(true, false)) add(Case(
            "bubble_tone_${midi}_${if (reference) "reference" else "candidate"}",
            "${note(midi)} · ${if (reference) "Earlier pops" else "Longer pitched tone"}",
            "BUBBLE at MIDI $midi, velocity 1: SPARK 40%, FEED 25%, CASCADE 70%, TIMBRE 45%, RECOVERY 50%, HOLD off. " +
                (if (reference) "Earlier response from revision 140d951, reproduced by the explicit legacy-envelope diagnostic option."
                else "Revised gate-response candidate. Listen for the underlying note and the spacing of articulations; musical acceptance is pending."),
            "comparison", WickVoice.BUBBLE, "bubble-tone", "BUBBLE · earlier pops and longer pitched tone", toneControls, midi,
            options = Wick.Options(legacyBubbleEnvelope = reference), comparison = "bubble-tone-$midi",
            variant = if (reference) "reference" else "candidate", referenceRevision = if (reference) BUBBLE_REFERENCE_REVISION else null,
        ))
        // Fixed controls keep this an articulation comparison, even if future defaults move.
        val cohesionControls = linkedMapOf("SPARK" to .65f, "FEED" to .50f, "CASCADE" to .65f,
            "TIMBRE" to .45f, "RECOVERY" to .65f, "HOLD" to 0f)
        for (midi in listOf(48, 60, 72)) for (reference in listOf(true, false)) add(Case(
            "rattle_cohesion_${midi}_${if (reference) "reference" else "candidate"}",
            "${note(midi)} · ${if (reference) "Earlier repeated hits" else "Connected body"}",
            "RATTLE at MIDI $midi, velocity 1: SPARK 65%, FEED 50%, CASCADE 65%, TIMBRE 45%, RECOVERY 65%, HOLD off. " +
                (if (reference) "Earlier response from revision 3b77d8b, reproduced by the explicit legacy-envelope diagnostic option."
                else "Earlier connected-body response at identical controls. Both historical examples retain the previous folded-FM body; the accepted Warm Wire default is auditioned separately."),
            "comparison", WickVoice.RATTLE, "rattle-cohesion", "RATTLE · repeated hits and connected body", cohesionControls, midi,
            options = Wick.Options(legacyRattleEnvelope = reference, rattleSource = Wick.RattleSource.CURRENT), comparison = "rattle-cohesion-$midi",
            variant = if (reference) "reference" else "candidate", referenceRevision = if (reference) RATTLE_REFERENCE_REVISION else null,
        ))
        val variations = WickPresets.all().filterNot {
            it.macros == Wick.defaults(it.voice) && it.midi == Wick.rootMidi(it.voice) && it.velocity == 1f &&
                (it.voice != WickVoice.RATTLE || it.rattleSource == Wick.RattleSource.WARM_WIRE)
        }
        for (preset in variations) add(Case(
            "preset_${slug(preset.name)}", preset.name,
            if (preset.name == "Busy Bubbles") "Busy BUBBLE recipe at FEED 65%, now using the revised gate response. The earlier-response A/B comparison uses FEED 25% with all other controls unchanged."
            else "Provisional ${preset.voice.name.lowercase()} recipe. Dry engine output, with no rack effects.",
            "preset", preset.voice, "presets", "${variations.size} provisional patches", preset.macros, preset.midi, preset.velocity, preset = preset,
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
        // BREATH has the same causal mechanisms, heard through longer, softer openings.
        // Use its unaltered defaults for these pairs so they explain the first listening clip.
        for ((id, title, options) in comparisons) for (altered in listOf(false, true)) add(Case(
            "mechanism_breath_${id}_${if (altered) "off" else "on"}",
            if (!altered) "BREATH · full network" else when (id) {
                "cascade" -> "BREATH · cascade disconnected"
                "depletion" -> "BREATH · constant availability"
                else -> "BREATH · gain-only gates"
            },
            when (id) {
                "cascade" -> "BREATH defaults at C4. The altered version disconnects release transmission; listen for what the later gate answers contribute to continuity."
                "depletion" -> "BREATH defaults at C4. The altered version keeps availability constant; compare the shape and strength of successive openings."
                else -> "BREATH defaults at C4. The altered version removes the low-pass gate's opening-to-brightness coupling; compare the swell and closing tone."
            }, "comparison", WickVoice.BREATH, "breath-mechanism-$id", "BREATH · $title",
            options = if (altered) options else Wick.Options(), comparison = "breath-$id",
            variant = if (altered) "ablated" else "full",
        ))
        for (midi in listOf(48, 60, 72)) add(Case(
            "breath_sustain_$midi", "BREATH · ${note(midi)} soft openings",
            "BREATH defaults at MIDI $midi, velocity 1. Compare the soft rise, pitched body, overlapping answers and finite ring-out across C3, C4 and C5. No effects or note-off envelope.",
            "sustain", WickVoice.BREATH, "breath-sustain", "BREATH · soft openings across registers", midi = midi,
        ))
        // Explain FLICKER's brief openings at the same defaults used for its first clip.
        for ((id, title, options) in comparisons) for (altered in listOf(false, true)) add(Case(
            "mechanism_flicker_${id}_${if (altered) "off" else "on"}",
            if (!altered) "FLICKER · full network" else when (id) {
                "cascade" -> "FLICKER · cascade disconnected"
                "depletion" -> "FLICKER · constant availability"
                else -> "FLICKER · gain-only gates"
            },
            when (id) {
                "cascade" -> "FLICKER defaults at C4. Disconnect release transmission to compare the bright answering flashes with the initial pitched body."
                "depletion" -> "FLICKER defaults at C4. Keep availability constant to compare the strength and spacing of successive openings."
                else -> "FLICKER defaults at C4. Remove the low-pass gate's opening-to-brightness coupling to compare each flash's changing tone."
            }, "comparison", WickVoice.FLICKER, "flicker-mechanism-$id", "FLICKER · $title",
            options = if (altered) options else Wick.Options(), comparison = "flicker-$id",
            variant = if (altered) "ablated" else "full",
        ))
        for (midi in listOf(48, 60, 72)) add(Case(
            "flicker_register_$midi", "FLICKER · ${note(midi)} metallic openings",
            "FLICKER defaults at MIDI $midi, velocity 1. Compare the bright openings, underlying requested note and answering chain across C3, C4 and C5. No effects.",
            "register", WickVoice.FLICKER, "flicker-register", "FLICKER · bright openings across registers", midi = midi,
        ))
        // RATTLE's connected body still uses WICK's actual releases and recoverable reserve.
        // Keep these mechanism pairs at the controlled defaults used by the first clip.
        for ((id, title, options) in comparisons) for (altered in listOf(false, true)) add(Case(
            "mechanism_rattle_${id}_${if (altered) "off" else "on"}",
            if (!altered) "RATTLE · full network" else when (id) {
                "cascade" -> "RATTLE · cascade disconnected"
                "depletion" -> "RATTLE · constant availability"
                else -> "RATTLE · gain-only gates"
            },
            when (id) {
                "cascade" -> "RATTLE defaults at C4. Disconnect release transmission to compare the answering motion with the primary pitched body."
                "depletion" -> "RATTLE defaults at C4. Keep availability constant to compare how internal excitation weakens and recovers."
                else -> "RATTLE defaults at C4. Remove the low-pass gate's opening-to-brightness coupling to compare the tone within the connected motion."
            }, "comparison", WickVoice.RATTLE, "rattle-mechanism-$id", "RATTLE · $title",
            options = if (altered) options else Wick.Options(), comparison = "rattle-$id",
            variant = if (altered) "ablated" else "full",
        ))
        for (midi in listOf(48, 60, 72)) add(Case(
            "rattle_register_$midi", "RATTLE · ${note(midi)} connected body",
            "RATTLE defaults at MIDI $midi, velocity 1. Compare the connected pitched body and its internal weakening and recovery across C3, C4 and C5. No effects.",
            "register", WickVoice.RATTLE, "rattle-register", "RATTLE · connected body across registers", midi = midi,
        ))
        // A busier supply makes reserve memory easier to compare without changing the first
        // listening clip. Only the explicit diagnostic option differs within the first pair.
        val rattleReserve = Wick.defaults(WickVoice.RATTLE) + ("FEED" to .80f)
        for (constant in listOf(false, true)) add(Case(
            "rattle_reserve_${if (constant) "constant" else "live"}",
            if (constant) "RATTLE · constant availability" else "RATTLE · recovering reserve",
            "RATTLE at C4, FEED 80%, with companion controls at voice defaults. " +
                (if (constant) "Availability is held constant by an explicit diagnostic option; compare the later pulses with the recovering-reserve version."
                else "The actual reserve depletes at each admission and recovers between pulses; compare the later pulses with constant availability."),
            "comparison", WickVoice.RATTLE, "rattle-reserve", "RATTLE · reserve and recovery", rattleReserve,
            options = Wick.Options(constantAvailability = constant), comparison = "rattle-reserve",
            variant = if (constant) "constant" else "live",
        ))
        for ((id, recovery) in listOf("fast" to .20f, "slow" to .85f)) add(Case(
            "rattle_recovery_$id", "RATTLE · $id recovery",
            "RATTLE at C4, FEED 80% and RECOVERY ${(recovery * 100).toInt()}%; other controls retain voice defaults. RECOVERY changes reserve return, gate lifetime and release-to-answer delay. Compare the recurring shape and admitted strengths; it is not a gain-only control.",
            "comparison", WickVoice.RATTLE, "rattle-reserve", "RATTLE · reserve and recovery",
            rattleReserve + ("RECOVERY" to recovery), comparison = "rattle-recovery", variant = id,
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
        if (!quick) for (macro in listOf("FEED", "RECOVERY")) for (value in steps) add(Case(
            "held_sweep_breath_${macro.lowercase()}_${tag(value)}", "Held $macro ${(value * 100).toInt()}%",
            "BREATH HOLD: one recurrent period. Only $macro changes; companion controls retain BREATH defaults. Repeat compares the continuity of soft openings after convergence.",
            "held-sweep", WickVoice.BREATH, "held-sweep-breath-${macro.lowercase()}", "BREATH HOLD · $macro",
            Wick.defaults(WickVoice.BREATH) + mapOf("HOLD" to 1f, macro to value), macro = macro, value = value,
        ))
        if (!quick) for (macro in listOf("FEED", "RECOVERY")) for (value in steps) add(Case(
            "held_sweep_flicker_${macro.lowercase()}_${tag(value)}", "Held $macro ${(value * 100).toInt()}%",
            "FLICKER HOLD: one recurrent period. Only $macro changes; companion controls retain FLICKER defaults. Repeat compares the metallic openings after convergence.",
            "held-sweep", WickVoice.FLICKER, "held-sweep-flicker-${macro.lowercase()}", "FLICKER HOLD · $macro",
            Wick.defaults(WickVoice.FLICKER) + mapOf("HOLD" to 1f, macro to value), macro = macro, value = value,
        ))
        if (!quick) for (macro in listOf("FEED", "RECOVERY")) for (value in steps) add(Case(
            "held_sweep_rattle_${macro.lowercase()}_${tag(value)}", "Held $macro ${(value * 100).toInt()}%",
            "RATTLE HOLD: one recurrent period. Only $macro changes; companion controls retain RATTLE defaults. Repeat compares continuity and reserve-dependent movement after convergence.",
            "held-sweep", WickVoice.RATTLE, "held-sweep-rattle-${macro.lowercase()}", "RATTLE HOLD · $macro",
            Wick.defaults(WickVoice.RATTLE) + mapOf("HOLD" to 1f, macro to value), macro = macro, value = value,
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

    private fun groupDescription(case: Case): String = if (case.groupId == "bubble-tone") {
        "Three matched pairs at C3, C4 and C5, velocity 1 and identical six controls. Earlier pops uses revision 140d951's envelope through an explicit diagnostic option; Longer pitched tone is the provisional revised response. Compare the underlying note and the articulation. Short-window measurements support listening without establishing audibility."
    } else if (case.groupId == "rattle-cohesion") {
        "Three matched pairs at C3, C4 and C5, velocity 1 and identical six controls. Earlier repeated hits reproduces revision 3b77d8b through an explicit diagnostic option. Connected body is the proposed response to the owner's request for a more cohesive gesture. Compare the body between openings and the sharpness of repeated attacks; descriptive RMS measurements support listening without establishing it."
    } else if (case.groupId == "rattle-reserve") {
        "Four C4 comparisons at FEED 80%. The first pair changes only live versus constant excitation availability. The second pair keeps the live reserve and changes RECOVERY from 20% to 85%, including its gate-lifetime and delay effects. Listen for a clear note, weakening later pulses and their return; diagnostic admissions show what the reserve actually did."
    } else when (case.kind) {
        "defaults" -> "Listen first to the six dry prototypes. The initial spark drives a shared pitched source through three interacting low-pass gates."
        "preset" -> "Provisional voice variations and held recipes. The accepted PLUCK, WOOD, BREATH, FLICKER and revised BUBBLE recipes remain available alongside RATTLE. Every clip uses its saved pitch, velocity and six controls."
        "comparison" -> case.description
        "sweep" -> "Five settings: 0, 25, 50, 75 and 100%. Companion controls stay at this voice's defaults."
        "held-sweep" -> "Five settings in a sustained periodic pattern. Each clip supplies cycle-state and exported PCM seam evidence."
        "hold" -> "Default, minimum feed, dense slow recovery and disconnected transmission. These files contain one held period; turn on repeat to audition the wrap."
        "range" -> "C1, C3 and C6, each at 25%, 65% and 100% velocity. Both native and matched playback are available."
        "sustain" -> "BREATH defaults at C3, C4 and C5. Listen for a soft rise, a clear note through successive openings, and a gradual finite release. Fixed-window raw energy and requested-root projection are measurements, not a listening verdict."
        "register" -> if (case.voice == WickVoice.RATTLE) "RATTLE defaults at C3, C4 and C5. Listen for one connected pitched body with recovering movement inside it. Fixed-window energy, continuity and root projection are measurements, not a listening verdict."
            else "FLICKER defaults at C3, C4 and C5. Listen for brief metallic openings and a clear underlying note. Fixed-window raw energy and requested-root projection are measurements, not a listening verdict."
        else -> "Four corners of the interaction, with companion controls at BUBBLE defaults."
    }

    private fun options(options: Wick.Options): Map<String, Any?> = linkedMapOf<String, Any?>(
        "disableCascade" to options.disableCascade, "constantAvailability" to options.constantAvailability,
        "gainOnly" to options.gainOnly, "oversample" to options.oversample,
        "legacyBubbleEnvelope" to options.legacyBubbleEnvelope,
        "legacyRattleEnvelope" to options.legacyRattleEnvelope,
    ).apply { options.rattleSource?.let { this["rattleSource"] = it.name } }

    /** Descriptive PCM evidence only; a higher floor or gentler rise does not prove musical cohesion. */
    private fun bodyContinuity(samples: FloatArray, held: Boolean): Map<String, Any?> {
        val window = (Dsp.RATE * .020).roundToInt()
        val first = minOf((Dsp.RATE * (if (held) .20 else .35)).roundToInt(), samples.size)
        val last = if (held) samples.size else minOf((Dsp.RATE * 2.30).roundToInt(), samples.size)
        val count = ((last - first) / window).coerceAtLeast(0)
        val envelope = DoubleArray(count) { index ->
            var energy = 0.0
            val from = first + index * window
            for (frame in from until from + window) energy += samples[frame].toDouble() * samples[frame]
            sqrt(energy / window)
        }
        fun quantile(values: DoubleArray, fraction: Double): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            val position = fraction * (sorted.size - 1)
            val lower = position.toInt()
            val upper = minOf(lower + 1, sorted.lastIndex)
            return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
        }
        val p10 = quantile(envelope, .10)
        val median = quantile(envelope, .50)
        val p90 = quantile(envelope, .90)
        val rises = DoubleArray((envelope.size - 1).coerceAtLeast(0)) { index ->
            maxOf(0.0, envelope[index + 1] - envelope[index])
        }
        return linkedMapOf(
            "measurement" to "Nonoverlapping 20 ms raw RMS bins. Finite clips use 0.35–2.30 s, excluding the initial strike and final tail; held clips use 0.20 s through the period end. Only complete bins are used. Percentiles use linear interpolation. Floor and positive-rise metrics divide by the median RMS, making them independent of overall level. These descriptors do not establish audible cohesion.",
            "fromSeconds" to first.toDouble() / Dsp.RATE,
            "toSeconds" to (first + count * window).toDouble() / Dsp.RATE,
            "windowFrames" to window, "windowSeconds" to window.toDouble() / Dsp.RATE, "windowCount" to count,
            "rms10thPercentile" to p10, "rmsMedian" to median, "rms90thPercentile" to p90,
            "rms10thPercentileOverMedian" to p10 / median.coerceAtLeast(1e-30),
            "rms90thTo10thContrastDb" to if (p10 > 0.0 && p90 > 0.0) 20.0 * log10(p90 / p10) else null,
            "positiveRmsRise95thPercentileOverMedian" to quantile(rises, .95) / median.coerceAtLeast(1e-30),
        )
    }

    /**
     * Mirror FeatureExtractor's onset envelope without changing its reported class. A soft swell
     * may reach 99% of its peak late in the 1.2 s crop. If no 20 dB crossing follows, the crop only
     * supplies a lower bound on decay; its short remaining duration is not an observed decay.
     */
    private fun onsetEnvelopeEvidence(samples: FloatArray, sampleRate: Int): Map<String, Any?> {
        val hop = 256
        val window = 1024
        val envelope = FloatArray(maxOf(1, samples.size / hop)) { index ->
            var peak = 0f
            val start = index * hop
            for (frame in start until minOf(samples.size, start + window)) peak = maxOf(peak, abs(samples[frame]))
            peak
        }
        val peak = envelope.maxOrNull() ?: 0f
        if (peak <= 0f) return linkedMapOf(
            "onsetEnvelopePeakSeconds" to null, "onset20dBCrossingObserved" to false,
            "onsetPostPeakMinimumRatio" to null, "onsetDecayCensored" to false,
        )
        val peakIndex = envelope.indexOfFirst { it >= peak * .99f }
        var minimum = peak
        var crossingObserved = false
        for (index in peakIndex until envelope.size) {
            minimum = minOf(minimum, envelope[index])
            if (envelope[index] <= peak * .1f) crossingObserved = true
        }
        return linkedMapOf(
            "onsetEnvelopePeakSeconds" to peakIndex.toDouble() * hop / sampleRate,
            "onset20dBCrossingObserved" to crossingObserved,
            "onsetPostPeakMinimumRatio" to minimum.toDouble() / peak,
            "onsetDecayCensored" to !crossingObserved,
        )
    }

    private fun toneWindows(raw: FloatArray, matched: FloatArray, midi: Int): List<Map<String, Any?>> {
        val hz = Keys.midiHz(midi).toDouble()
        return listOf(.05 to .15, .20 to .35, .50 to .65, .90 to 1.05).map { (fromSeconds, toSeconds) ->
            val from = minOf((fromSeconds * Dsp.RATE).toInt(), raw.size, matched.size)
            val to = minOf((toSeconds * Dsp.RATE).toInt(), raw.size, matched.size)
            var real = 0.0
            var imaginary = 0.0
            var rawEnergy = 0.0
            var matchedEnergy = 0.0
            for (frame in from until to) {
                val value = raw[frame].toDouble()
                val phase = 2.0 * PI * hz * frame / Dsp.RATE
                real += value * cos(phase)
                imaginary += value * sin(phase)
                rawEnergy += value * value
                matchedEnergy += matched[frame].toDouble() * matched[frame]
            }
            val count = (to - from).coerceAtLeast(1)
            linkedMapOf(
                "fromSeconds" to fromSeconds, "toSeconds" to toSeconds, "sampleCount" to to - from,
                "rawRms" to sqrt(rawEnergy / count), "matchedRms" to sqrt(matchedEnergy / count),
                "requestedRootHz" to hz,
                "requestedRootCoherentShare" to (2.0 * (real * real + imaginary * imaginary) / (count * rawEnergy).coerceAtLeast(1e-30)).coerceIn(0.0, 1.0),
            )
        }
    }

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
