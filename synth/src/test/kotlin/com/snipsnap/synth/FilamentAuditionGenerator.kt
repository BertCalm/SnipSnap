package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/** Real JVM renders and replayable evidence for the first FILAMENT listening milestone. */
object FilamentAuditionGenerator {
    // The same attenuation applies to every raw file and every prelevel stem.
    // Deliberate depletion must remain audible in these comparisons.
    private const val RAW_EXPORT_GAIN = .25f
    private const val MAX_SEAM_ERROR = 1e-3
    private const val MAX_WRAP_STEP_RATIO = 8.0
    // Geometry may change file length and its final fade of the DC-filter tail.
    // This prelevel tolerance is below one PCM24 unit at the common export gain.
    private const val SOURCE_COMPARISON_TOLERANCE = 2e-7
    private const val PACK_NAME = "filament-listening-pack.zip"
    private const val PACK_LIMIT_BYTES = 24L * 1024 * 1024
    private val steps = listOf(0f, .25f, .5f, .75f, 1f)

    private data class Case(
        val id: String,
        val title: String,
        val description: String,
        val group: String,
        val kind: String,
        val voice: FilamentVoice,
        val macros: Map<String, Float> = originalDefaults(voice),
        val midi: Int = Filament.rootMidi(voice),
        val velocity: Float = 1f,
        val config: FilamentConfig = FilamentConfig(),
        val options: Filament.Options = Filament.Options(),
        val stems: Boolean = false,
        val macro: String? = null,
        val value: Float? = null,
        val comparison: String? = null,
        val variant: String? = null,
        val preset: FilamentPatch? = null,
        val model: Int = Filament.ORIGINAL_MODEL,
        val requireFixedSource: Boolean = true,
        val comparisonPartnerId: String? = null,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/filament-audition").absoluteFile
        val quick = "--quick" in args
        root.mkdirs()
        File(root, "manifest.json").delete()
        File(root, PACK_NAME).delete()
        for (name in listOf("index.html", "styles.css", "app.js")) {
            val resource = "filament-audition/$name"
            val input = FilamentAuditionGenerator::class.java.classLoader.getResourceAsStream(resource)
                ?: error("Missing audition frontend resource $resource; run processTestResources after adding it")
            input.use { stream -> File(root, name).outputStream().use { stream.copyTo(it) } }
        }
        val cases = cases(quick)
        check(cases.map { it.id }.distinct().size == cases.size) { "Duplicate FILAMENT clip IDs" }
        val clips = mutableListOf<Map<String, Any?>>()
        val failures = mutableListOf<Map<String, Any?>>()
        val comparisonSources = mutableMapOf<String, FloatArray>()
        val comparisonAudio = mutableMapOf<String, FloatArray>()
        val comparisonIds = mutableMapOf<String, String>()
        val sweepAudio = mutableMapOf<String, Pair<Float, FloatArray>>()
        val sourceRevision = git("rev-parse", "HEAD")
        val sourceDirty = git("status", "--porcelain", "--untracked-files=normal")?.isNotEmpty()
        val started = System.nanoTime()

        for ((index, case) in cases.withIndex()) {
            try {
                val began = System.nanoTime()
                val probe = Filament.inspect(case.voice, case.macros, case.midi, case.velocity, case.config, case.options, model = case.model)
                val elapsedMs = (System.nanoTime() - began) / 1e6
                val d = probe.diagnostics
                val native = Snip(probe.samples, 1, Dsp.RATE)
                val matched = AuditionLevel.level(native)
                val arrays = listOf(probe.samples, probe.raw, probe.source, probe.through, probe.escaped, probe.spill)
                check(arrays.all { it.isNotEmpty() && it.size == probe.samples.size && it.all(Float::isFinite) }) {
                    "empty, mismatched or non-finite audio"
                }
                check(native.peak() <= 1f && matched.peak() <= 1f) { "native or matched output exceeds PCM range" }
                check(arrays.drop(1).all { peak(it) * RAW_EXPORT_GAIN < 1.0 }) {
                    "raw or stem exceeds the common PCM export gain"
                }
                val localFailures = mutableListOf<String>()
                if (d.loop != Filament.isLoop(case.macros.getValue("HOLD"))) {
                    localFailures += "HOLD request and rendered loop state disagree"
                }
                if (!listOf(d.launch, d.retained, d.escaped, d.absorbed, d.rawPeak, d.rawRms, d.rawDc, d.sourceEffort, d.actualHz, d.maxState).all { it.isFinite() }) {
                    localFailures += "non-finite engine diagnostics"
                }
                val budgetError = abs(d.launch - d.retained - d.escaped - d.absorbed)
                if (budgetError > 1e-9) localFailures += "allocation budget error $budgetError"
                if (d.branches.any { abs(it.launch - it.retained - it.escaped - it.absorbed) > 1e-9 }) {
                    localFailures += "a branch exceeds its allocation budget"
                }
                if (d.sites.any { abs(it.incomingPower - it.retainedPower - it.escapedPower - it.absorbedPower) > 1e-9 }) {
                    localFailures += "a bend site exceeds its incoming allocation"
                }
                val metrics = LinkedHashMap(TangleMeasure.metrics(probe.raw, native, case.midi, elapsedMs))
                val diagnostic = isDiagnostic(case.options)
                val newOrdinary = (case.model == Filament.MODEL_VERSION || case.group == "character") && !diagnostic
                val classifierGateApplies = !d.loop && (case.kind in setOf("defaults", "preset", "range") || newOrdinary && case.kind != "mechanism")
                metrics["classifierGateApplies"] = classifierGateApplies
                metrics["classifierGatePolicy"] = when {
                    d.loop -> "Held sustain is analyzed over 1.2 seconds of repeated periodic audio; classification is not a recurrence proof and does not gate a short held buffer."
                    classifierGateApplies -> "Standard finite defaults, presets, note/velocity clips and new musical examples must avoid percussion classes in the first 1.2 seconds."
                    case.kind == "mechanism" -> "Exempt diagnostic mechanism fixture; classification remains reported."
                    case.group == "collect" -> "Exempt collector-isolation fixture; classification remains reported."
                    case.kind == "limit" -> "Exempt structural-limit fixture, including intentional silence; classification remains reported."
                    case.kind == "sweep" -> "Exempt macro-extreme sweep; classification remains reported."
                    else -> "Exempt controlled geometry comparison; classification remains reported."
                }
                if (d.loop) {
                    val analysisFrames = (1.2 * Dsp.RATE).toInt()
                    val sustain = Snip(FloatArray(analysisFrames) { probe.samples[it % probe.samples.size] }, 1, Dsp.RATE)
                    val classification = Classifier.classify(sustain)
                    metrics["onsetClassifier"] = null
                    metrics["onsetDecayMs"] = null
                    metrics["sustainClassifier"] = classification.drumClass.name
                    metrics["sustainClassifierConfidence"] = classification.confidence
                    metrics["sustainDecayMs"] = classification.features.decayMs
                    metrics["sustainClassifierWindowSeconds"] = sustain.durationSeconds
                    metrics["sustainClassifierAnalysisOnly"] = true
                    metrics["standardFiniteClassifierPass"] = null
                } else {
                    val onset = Snip(probe.samples.copyOfRange(0, minOf(probe.samples.size, (1.2 * Dsp.RATE).toInt())), 1, Dsp.RATE)
                    val classification = Classifier.classify(onset)
                    metrics["onsetClassifier"] = classification.drumClass.name
                    metrics["onsetClassifierConfidence"] = classification.confidence
                    metrics["onsetClassifierWindowSeconds"] = onset.durationSeconds
                    metrics["onsetDecayMs"] = classification.features.decayMs
                    val pitched = classification.drumClass !in TangleMeasure.drumClasses
                    metrics["standardFiniteClassifierPass"] = if (classifierGateApplies) pitched else null
                    if (classifierGateApplies && !pitched) localFailures += "standard finite onset classified ${classification.drumClass} (decay ${classification.features.decayMs} ms)"
                }
                val sourceSnip = Snip(probe.source, 1, Dsp.RATE)
                val sourcePitch = Pitch.detect(sourceSnip, fromSec = .12f, windowSec = .3f)
                val requestedHz = Keys.midiHz(case.midi)
                val sourceFundamental = if (sourceSnip.durationSeconds > .45f) {
                    FineTuning.measuredHz(sourceSnip, requestedHz, fromSec = .12f, bodySeconds = .4f)
                } else null
                metrics["sourceMidi"] = case.midi
                metrics["sourcePitchHz"] = sourcePitch?.hz
                metrics["sourcePitchConfidence"] = sourcePitch?.confidence
                metrics["sourcePitchDetectorErrorCents"] = sourcePitch?.let { FineTuning.cents(it.hz.toDouble(), requestedHz.toDouble()) }
                metrics["sourceFundamentalHz"] = sourceFundamental
                metrics["sourceFundamentalErrorCents"] = sourceFundamental?.let { FineTuning.cents(it, requestedHz.toDouble()) }
                metrics["sourceRequestedRootEnergyShare"] = TangleMeasure.toneShare(probe.source, requestedHz.toDouble())
                metrics["sourcePitchDetectorRange"] = "40–1200 Hz; body window starts at 0.12 seconds. A constrained root-component estimate is separate from the house detector."
                val windows = analysisWindows(probe.raw, matched.samples, probe.source)
                metrics["analysisWindows"] = windows
                metrics["sourceBodyPowerCentroidHz"] = windows.first { it["id"] == "body" }["sourcePowerCentroidHz"]
                metrics["mixBodyPowerCentroidHz"] = windows.first { it["id"] == "body" }["mixPowerCentroidHz"]
                metrics["matchedLoudness"] = Loudness.of(matched)
                metrics["nativeLoudness"] = Loudness.of(native)
                metrics["matchedPeak"] = matched.peak()
                metrics["rawExportGain"] = RAW_EXPORT_GAIN
                metrics["sourceRms"] = TangleMeasure.rms(probe.source)
                metrics["throughRms"] = TangleMeasure.rms(probe.through)
                metrics["escapedRms"] = TangleMeasure.rms(probe.escaped)
                metrics["spillRms"] = TangleMeasure.rms(probe.spill)
                val collect = case.macros.getValue("COLLECT").toDouble()
                val collectNorm = sqrt((1.0 - collect) * (1.0 - collect) + collect * collect)
                val throughContribution = TangleMeasure.rms(probe.through) * (1.0 - collect) / collectNorm
                val spillContribution = TangleMeasure.rms(probe.spill) * collect / collectNorm
                metrics["weightedThroughRms"] = throughContribution
                metrics["weightedSpillRms"] = spillContribution
                metrics["weightedSpillIndependentPowerShare"] = spillContribution * spillContribution /
                    (throughContribution * throughContribution + spillContribution * spillContribution).coerceAtLeast(1e-30)
                metrics["sourceEffort"] = d.sourceEffort
                metrics["outputBufferBytes"] = arrays.sumOf { it.size.toLong() * 4L } + (probe.previous?.size?.toLong() ?: 0L) * 4L
                metrics["actualHz"] = d.actualHz
                metrics["periodFrames"] = d.periodFrames
                metrics["maxState"] = d.maxState
                metrics["preparationMs"] = d.preparationNanos / 1e6
                metrics["synthesisMs"] = d.synthesisNanos / 1e6
                metrics["branchCount"] = d.branches.size
                metrics["siteCount"] = d.sites.size
                metrics["budget"] = linkedMapOf(
                    "launch" to d.launch, "retained" to d.retained, "escaped" to d.escaped,
                    "absorbed" to d.absorbed, "error" to budgetError,
                )
                metrics["loopConverged"] = d.loopConverged
                metrics["seamError"] = d.seamError
                metrics["fullCycleError"] = d.fullCycleError
                metrics["stateError"] = d.stateError
                metrics["loopAttempts"] = d.loopAttempts
                metrics["haloGainBound"] = d.haloGainBound
                metrics["haloNormalization"] = d.haloNormalization
                if (case.config.perfectRetention) {
                    val silent = probe.escaped.all { it == 0f } && probe.spill.all { it == 0f }
                    metrics["perfectRetentionSpillSilent"] = silent
                    if (!silent) localFailures += "perfect retention produced escaped sound"
                }
                case.comparison?.let { key ->
                    comparisonSources[key]?.let { previous ->
                        val difference = maximumPaddedDifference(previous, probe.source)
                        metrics["identicalComparisonSource"] = difference == 0.0
                        metrics["comparisonSourceMatches"] = difference <= SOURCE_COMPARISON_TOLERANCE
                        metrics["comparisonSourceMaximumDifference"] = difference
                        metrics["comparisonSourceTolerance"] = SOURCE_COMPARISON_TOLERANCE
                        if (case.requireFixedSource && difference > SOURCE_COMPARISON_TOLERANCE) localFailures += "fixed-excitation comparison changed its source (maximum difference $difference)"
                    } ?: run { comparisonSources[key] = probe.source }
                    comparisonAudio[key]?.let { previous ->
                        metrics["comparisonDistance"] = TangleMeasure.distance(previous, probe.samples)
                        metrics["comparisonSpectralDistance"] = TangleMeasure.spectralDistance(previous, probe.samples)
                        val count = minOf(previous.size, probe.samples.size, (1.2 * Dsp.RATE).toInt())
                        val previousMatched = AuditionLevel.level(Snip(previous, 1, Dsp.RATE)).samples
                        metrics["comparisonGainMatchedSpectralDistance"] = TangleMeasure.spectralDistance(previousMatched.copyOf(count), matched.samples.copyOf(count))
                        metrics["comparisonAnalysisFrames"] = count
                        metrics["comparisonSpectralWindowFrames"] = 8192
                        metrics["comparisonSpectralWindowFromSeconds"] = .15
                        metrics["comparisonReferenceId"] = comparisonIds.getValue(key)
                    } ?: run { comparisonAudio[key] = probe.samples }
                    comparisonIds.putIfAbsent(key, case.id)
                }
                if (case.kind == "sweep") {
                    sweepAudio[case.group]?.let { (value, previous) ->
                        metrics["macroPreviousValue"] = value
                        metrics["macroDistance"] = TangleMeasure.distance(previous, probe.samples)
                        metrics["macroSpectralDistance"] = TangleMeasure.spectralDistance(previous, probe.samples)
                    }
                    sweepAudio[case.group] = requireNotNull(case.value) to probe.samples
                }
                if (case.kind == "defaults") {
                    val again = Filament.inspect(case.voice, case.macros, case.midi, case.velocity, case.config, case.options, model = case.model)
                    val deterministic = probe.raw.contentEquals(again.raw) && probe.samples.contentEquals(again.samples)
                    metrics["deterministic"] = deterministic
                    if (!deterministic) localFailures += "repeated render was not deterministic"
                }

                val files = linkedMapOf<String, String>(
                    "native" to "native/${case.id}.wav", "raw" to "raw/${case.id}.wav",
                    "matched" to "matched/${case.id}.wav", "recipe" to "recipes/${case.id}.json",
                    "diagnostics" to "diagnostics/${case.id}.json",
                )
                WavWriter.write(File(root, files.getValue("native")), native, WavWriter.BitDepth.PCM_24)
                WavWriter.write(File(root, files.getValue("raw")), scaled(probe.raw, RAW_EXPORT_GAIN), WavWriter.BitDepth.PCM_24)
                WavWriter.write(File(root, files.getValue("matched")), matched, WavWriter.BitDepth.PCM_16)
                if (case.stems) for ((name, samples) in listOf(
                    "source" to probe.source, "through" to probe.through,
                    "escaped" to probe.escaped, "spill" to probe.spill,
                )) {
                    files[name] = "stems/${case.id}_$name.wav"
                    WavWriter.write(File(root, files.getValue(name)), scaled(samples, RAW_EXPORT_GAIN), WavWriter.BitDepth.PCM_24)
                }
                if (d.loop) {
                    if (!d.loopConverged) localFailures += "held state did not converge"
                    if (d.seamError?.let { it.isFinite() && it < MAX_SEAM_ERROR } != true) {
                        localFailures += "independent-cycle seam ${d.seamError}"
                    }
                    val previous = probe.previous
                    if (previous == null || previous.size != probe.samples.size || previous.size < 256) {
                        localFailures += "HOLD has no complete independently generated preceding cycle"
                    } else {
                        val gain = (TangleMeasure.rms(matched.samples) / TangleMeasure.rms(probe.samples).coerceAtLeast(1e-20)).toFloat()
                        val context = scaled(previous + probe.samples, gain)
                        val encoded = ByteArrayOutputStream().also { WavWriter.write(it, context, WavWriter.BitDepth.PCM_16) }.toByteArray()
                        val decoded = WavReader.read(encoded).samples
                        metrics.putAll(pcmSeam(decoded, probe.samples.size))
                        files["seamContext"] = "diagnostics/${case.id}_seam-context.wav"
                        File(root, files.getValue("seamContext")).writeBytes(encoded)
                        if ((metrics.getValue("exportedSeamError") as Double) >= MAX_SEAM_ERROR) {
                            localFailures += "PCM16 independent-cycle seam ${metrics["exportedSeamError"]}"
                        }
                        if ((metrics.getValue("exportedFullCycleError") as Double) >= MAX_SEAM_ERROR) {
                            localFailures += "PCM16 independent complete-cycle error ${metrics["exportedFullCycleError"]}"
                        }
                        if ((metrics.getValue("exportedWrapStepRatio") as Double) > MAX_WRAP_STEP_RATIO) {
                            localFailures += "PCM16 loop join step ${metrics["exportedWrapStepRatio"]}"
                        }
                    }
                }

                val patch = case.preset ?: FilamentPatch(
                    name = case.title.take(14), voice = case.voice, macros = case.macros,
                    midi = case.midi, velocity = case.velocity, config = case.config, model = case.model,
                )
                check(patch.model == case.model) { "Case model ${case.model} disagrees with patch model ${patch.model}" }
                if (newOrdinary) {
                    val restored = Patches.fromJsonText(patch.toJsonText()) as FilamentPatch
                    val exact = restored == patch && restored.render().samples.contentEquals(probe.samples)
                    metrics["exactPatchReplay"] = exact
                    if (!exact) localFailures += "saved model ${case.model} patch did not replay exactly through the decoder and renderer"
                }
                if (!diagnostic) {
                    files["patch"] = "patches/${case.id}.json"
                    writeJson(File(root, files.getValue("patch")), patch.toJsonValue())
                }
                writeJson(File(root, files.getValue("recipe")), linkedMapOf(
                    "format" to "filament-audition-recipe-v1", "engineModel" to case.model,
                    "description" to "Replay with Filament.inspect(patch.voice, patch.macros, patch.midi, patch.velocity, patch.config, diagnosticOptions, model = patch.model). Raw and stem files share one export gain.",
                    "patch" to patch.toJsonValue(), "diagnosticOptions" to options(case.options),
                    "isDiagnostic" to diagnostic, "rawExportGain" to RAW_EXPORT_GAIN,
                ))
                writeJson(File(root, files.getValue("diagnostics")), linkedMapOf(
                    "id" to case.id, "model" to case.model, "metrics" to metrics, "diagnostics" to diagnostics(d),
                    "status" to if (localFailures.isEmpty()) "ready" else "failed", "failures" to localFailures,
                    "allocationNote" to "Branch and bend budgets partition launch power. Coherent output waveform energies need not add because delayed paths interfere.",
                ))
                clips += metadata(case) + linkedMapOf(
                    "loop" to d.loop, "files" to files, "metrics" to metrics,
                    "status" to if (localFailures.isEmpty()) "ready" else "failed", "failures" to localFailures,
                )
                if (localFailures.isNotEmpty()) failures += linkedMapOf("id" to case.id, "reasons" to localFailures)
            } catch (error: Exception) {
                val reason = "${error.javaClass.simpleName}: ${error.message}"
                failures += linkedMapOf("id" to case.id, "reasons" to listOf(reason))
                clips += metadata(case) + linkedMapOf("status" to "failed", "failures" to listOf(reason))
                System.err.println("FILAMENT ${case.id}: $reason")
            }
            println("FILAMENT ${index + 1}/${cases.size}: ${case.id}")
        }

        val metrics = clips.mapNotNull { it["metrics"] as? Map<*, *> }
        fun maximum(key: String) = metrics.mapNotNull { (it[key] as? Number)?.toDouble() }.maxOrNull()
        val summary = linkedMapOf<String, Any?>(
            "clipCount" to clips.size, "readyClips" to clips.count { it["status"] == "ready" },
            "heldClips" to clips.count { it["loop"] == true }, "presetCount" to cases.count { it.kind == "preset" },
            "standardFiniteClassifierCases" to metrics.count { it["classifierGateApplies"] == true },
            "standardFiniteClassifierFailures" to clips.filter { (it["metrics"] as? Map<*, *>)?.get("standardFiniteClassifierPass") == false }.map { it["id"] },
            "failedCases" to failures, "maxRawPeak" to maximum("rawPeak"),
            "maxExportedSeamError" to maximum("exportedSeamError"),
            "maxFullCycleError" to maximum("fullCycleError"), "maxStateError" to maximum("stateError"),
            "maxExportedWrapStepRatio" to maximum("exportedWrapStepRatio"),
            "renderSeconds" to (System.nanoTime() - started) / 1e9,
            "models" to cases.groupingBy { it.model.toString() }.eachCount(),
            "exactNewPatchReplays" to metrics.count { it["exactPatchReplay"] == true },
            "characterComparisons" to characterComparisons(clips),
            "deterministicDefaults" to clips.filter { it["kind"] == "defaults" }.associate { clip ->
                clip.getValue("voice").toString() to ((clip["metrics"] as? Map<*, *>)?.get("deterministic") ?: false)
            },
        )
        val groups = cases.groupBy { it.group }.map { (id, members) -> linkedMapOf(
            "id" to id, "title" to groupTitle(id), "description" to groupDescription(id), "clipIds" to members.map { it.id },
        ) }
        val packClipIds = cases.filter { it.group in setOf("character", "character-presets", "character-context") }.map { it.id }
        val packScope = linkedMapOf<String, Any?>(
            "description" to "Native audio for the ${packClipIds.size} character comparison, revised preset and musical context clips; all recipes, patches and JSON diagnostics for both models; all held PCM seam contexts. The original 88-clip corpus, full raw/matched files and isolated stems remain individually downloadable on the listening page.",
            "nativeClipIds" to packClipIds, "nativeClipCount" to packClipIds.size,
            "allRecipeCount" to clips.size, "fullAudioOnPage" to true,
        )
        writeJson(File(root, "manifest.json"), linkedMapOf(
            "schemaVersion" to 1, "engine" to "FILAMENT", "currentModel" to Filament.MODEL_VERSION,
            "engineModels" to listOf(Filament.ORIGINAL_MODEL, Filament.MODEL_VERSION),
            "title" to "FILAMENT · More to follow",
            "description" to "Compare the original and revised THREAD, PULSE and CURL, then play twelve revised recipes across musical registers. Bends still transfer guided sound into an escaping halo. The complete original listening corpus remains available; musical acceptance remains open.",
            "status" to if (failures.isEmpty()) "requires-owner-listening" else "render-failed",
            "sampleRate" to Dsp.RATE, "oversampling" to Dsp.OVERSAMPLE, "channels" to 1, "generatedAt" to Instant.now().toString(),
            "quick" to quick, "rawExportGain" to RAW_EXPORT_GAIN,
            "audioNote" to "Native PCM24 is the engine output. Matched PCM16 uses the shared loudest-200-ms target of 0.03. Raw PCM24 and all stems use the same fixed gain of 0.25 (-12.04 dB), preserving depletion and relative levels; raw metrics precede that gain. An intentionally silent collector stays silent.",
            "holdNote" to "Held files contain one converged cycle. Each seam-context PCM16 WAV contains a genuinely generated preceding cycle followed by the exported cycle, both at the same matched gain. No seam repair or duplicated-cycle proof is used.",
            "seamNote" to "Exported seam error compares independently generated corresponding 256-frame windows after PCM16 encoding. The complete-cycle error and the actual end-to-start step divided by local derivative RMS plus one PCM16 unit are also reported. These checks support listening; they do not establish musical acceptance.",
            "allocationNote" to "Budgets partition launch power at every branch and bend. Delayed coherent output energies need not add; THROUGH, escaped-input and halo SPILL are separately available in selected clips.",
            "comparisonNote" to "The character pairs share explicit controls, root, velocity and seed but select different saved renderer models; source equality is not required across versions. All original fixed-excitation guards remain active. Gain-matched spectral distance uses normalized 12-band power from the same 8192-frame window starting at 0.15 seconds inside a shared first-1.2-second analysis buffer. Early/body/late windows report source and mix power centroids and RMS without perceptual acceptance thresholds.",
            "collectorBalanceNote" to "Weighted THROUGH/SPILL RMS applies the collector coefficients to each stem. The independent power share excludes coherent cross terms and is a balance comparison, not an additive accounting of mixed output energy.",
            "classifierNote" to "Full-file classification can read LOOP because finite files exceed 1.5 seconds. Standard finite defaults, presets, note/velocity clips and all new musical character/context/sweep examples screen the first 1.2 seconds against percussion families. Original geometry, collector, diagnostic and extreme-sweep fixtures retain explicit exemptions. Held sustain classification uses 1.2 seconds of repeated periodic audio solely for analysis; independent preceding/current state and PCM measurements remain the recurrence evidence.",
            "runtime" to linkedMapOf(
                "javaVersion" to System.getProperty("java.version"), "vm" to System.getProperty("java.vm.name"),
                "os" to System.getProperty("os.name"), "architecture" to System.getProperty("os.arch"),
                "cpuModel" to cpuModel(), "processors" to Runtime.getRuntime().availableProcessors(),
                "heapMaximumBytes" to Runtime.getRuntime().maxMemory(),
                "usedHeapBytes" to Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() },
                "heapObservationNote" to "Used heap is observed while writing this manifest, not a profiled peak. Per-clip outputBufferBytes counts the six final-rate FloatArrays and actual preceding held cycle; it excludes transient oversampled DSP/encoding buffers and other JVM allocations.",
                "relevantVmArguments" to relevantVmArguments(),
                "sourceRevision" to sourceRevision, "sourceDirty" to sourceDirty,
                "rendererModelVersion" to Filament.MODEL_VERSION,
            ),
            "download" to PACK_NAME, "packScope" to packScope, "groups" to groups, "clips" to clips, "summary" to summary,
        ))
        if (failures.isEmpty()) zip(root, packClipIds.toSet(), cases.map { it.id }.toSet())
        println("FILAMENT: ${clips.size} clips, ${failures.size} failed; ${root.resolve("manifest.json")}")
        check(failures.isEmpty()) { "FILAMENT audition failed: ${failures.joinToString { it["id"].toString() }}; inspect manifest.json" }
    }

    private fun cases(quick: Boolean): List<Case> = buildList {
        for (voice in FilamentVoice.entries) {
            val controls = originalDefaults(voice) + mapOf(
                "INJECT" to .55f, "BEND" to .60f, "LENGTH" to .60f,
                "BUNDLE" to .65f, "COLLECT" to .48f, "HOLD" to 0f,
            )
            for ((variant, model) in listOf("original" to Filament.ORIGINAL_MODEL, "revised" to Filament.MODEL_VERSION)) add(Case(
                id = "character_${voice.name.lowercase()}_$variant", title = "${voice.name} · $variant",
                description = "${voice.name} model $model at C3 and velocity 70%. Both versions use identical explicit controls, seed and geometry configuration; the source and prepared paths can change with the renderer model. Compare matched colour and native level, then inspect the stems.",
                group = "character", kind = "character", voice = voice, macros = controls,
                midi = 48, velocity = .7f, config = FilamentConfig(seed = 17), stems = true,
                comparison = "character-${voice.name.lowercase()}", variant = variant, model = model,
                requireFixedSource = false,
                comparisonPartnerId = "character_${voice.name.lowercase()}_${if (variant == "original") "revised" else "original"}",
            ))
        }
        val revisedPresets = FilamentPresets.all()
        check(revisedPresets.size == 12 && revisedPresets.all { it.model == Filament.MODEL_VERSION }) {
            "Character presets must contain twelve explicit current-model recipes"
        }
        for (patch in revisedPresets) add(Case(
            id = "character_preset_${slug(patch.name)}", title = patch.name,
            description = "An expressive ${patch.voice.name} model ${patch.model} recipe at its saved root, velocity and controls. Its patch is decoded and rendered again to verify exact replay.",
            group = "character-presets", kind = "preset", voice = patch.voice, macros = patch.macros,
            midi = patch.midi, velocity = patch.velocity, config = patch.config, preset = patch, model = patch.model,
        ))
        for (voice in FilamentVoice.entries) {
            val recipe = revisedPresets.first { it.voice == voice && !Filament.isLoop(it.macros.getValue("HOLD")) }
            for ((role, midi, velocity) in listOf(Triple("low_soft", 36, .35f), Triple("upper_firm", 72, .9f))) {
                val patch = recipe.copy(name = "${voice.name} ${if (role == "low_soft") "Low" else "High"}", midi = midi, velocity = velocity)
                add(Case(
                    id = "character_context_${voice.name.lowercase()}_$role", title = "${voice.name} · ${note(midi)} · ${(velocity * 100).toInt()}%",
                    description = "${recipe.name} in a ${if (role == "low_soft") "low, softly played" else "higher, firmly played"} register. This is a musical context; both note and velocity change between the two examples. The original controlled pitch/velocity grid remains available below.",
                    group = "character-context", kind = "context", voice = voice, macros = patch.macros,
                    midi = midi, velocity = velocity, config = patch.config, preset = patch, model = patch.model,
                ))
            }
        }
        for ((macro, voice) in listOf("BEND" to FilamentVoice.CURL, "COLLECT" to FilamentVoice.THREAD)) {
            val controls = originalDefaults(voice) + mapOf("INJECT" to .65f, "BEND" to .7f, "LENGTH" to .7f, "BUNDLE" to .65f, "COLLECT" to .6f, "HOLD" to 0f)
            for (value in if (quick) listOf(0f, 1f) else steps) add(Case(
                id = "character_sweep_${macro.lowercase()}_${tag(value)}", title = "$macro ${(value * 100).toInt()}% · revised ${voice.name}",
                description = "Model 2 ${voice.name}: only $macro changes through five settings. Raw keeps the physical level movement, while matched playback helps compare colour. The same-source guard remains active within this sweep.",
                group = "character-sweep-${macro.lowercase()}", kind = "sweep", voice = voice,
                macros = controls + (macro to value), config = FilamentConfig(seed = 17, profile = FilamentProfile.COIL),
                macro = macro, value = value, comparison = "character-sweep-${macro.lowercase()}", model = Filament.MODEL_VERSION,
            ))
        }
        val mechanism = originalDefaults(FilamentVoice.PULSE) + mapOf("INJECT" to .65f, "BEND" to .8f, "LENGTH" to .9f, "BUNDLE" to 1f, "COLLECT" to .6f, "HOLD" to 0f)
        for ((variant, title, options) in listOf(
            Triple("full", "Revised ordered network", Filament.Options()),
            Triple("generic", "Revised generic spill", Filament.Options(genericSpill = true)),
            Triple("collapsed", "Revised collapsed delays", Filament.Options(collapsePaths = true)),
        )) add(Case(
            id = "character_mechanism_$variant", title = title,
            description = "Model 2 PULSE, with identical source and explicit controls. Compare ordered escape against a generic spill or collapsed path delays. Diagnostic options are saved separately from the base patch.",
            group = "character-mechanism", kind = "mechanism", voice = FilamentVoice.PULSE, macros = mechanism,
            config = FilamentConfig(profile = FilamentProfile.COIL), options = options, stems = true,
            comparison = "character-mechanism", variant = variant, model = Filament.MODEL_VERSION,
        ))
        val original = originalCases(quick)
        check(original.size == if (quick) 49 else 88) { "Original listening corpus changed size" }
        addAll(original)
    }

    private fun originalCases(quick: Boolean): List<Case> = buildList {
        for (voice in FilamentVoice.entries) add(Case(
            "default_${voice.name.lowercase()}", voice.name, "Dry default voice, with the uncollected source and both collected branches available as stems.",
            "intro", "defaults", voice, stems = true,
        ))
        val originalPresets = FilamentPresets.original()
        check(originalPresets.size == 12 && originalPresets.all { it.model == Filament.ORIGINAL_MODEL }) {
            "Original corpus must retain its twelve explicit model 1 recipes"
        }
        for (patch in originalPresets) add(Case(
            "preset_${slug(patch.name)}", patch.name, "A saved ${patch.voice.name} recipe at its recorded root, velocity and controls.",
            "presets", "preset", patch.voice, patch.macros, patch.midi, patch.velocity, patch.config, preset = patch,
        ))
        val voice = FilamentVoice.THREAD
        val base = originalDefaults(voice) + mapOf("INJECT" to .62f, "BEND" to .7f, "LENGTH" to .65f, "BUNDLE" to 0f, "COLLECT" to .45f, "HOLD" to 0f)
        val coil = FilamentConfig(profile = FilamentProfile.COIL)
        for (value in listOf(0f, .5f, 1f)) add(Case(
            "bend_${tag(value)}", "BEND ${(value * 100).toInt()}%", "The same excitation and launch budget, with progressively stronger bend escape. Compare raw THROUGH and SPILL to hear transfer without independent normalization.",
            "bend", "comparison", voice, base + ("BEND" to value), config = coil, stems = true,
            comparison = "bend", variant = tag(value),
        ))
        for (profile in listOf(FilamentProfile.EARLY, FilamentProfile.LATE, FilamentProfile.COIL)) add(Case(
            "placement_${profile.name.lowercase()}", profile.name, "Identical excitation and controls; only the ordered bend placement changes. EARLY and LATE make escape location audible; COIL distributes the bends.",
            "placement", "comparison", voice, base, config = FilamentConfig(profile = profile), stems = true,
            comparison = "placement", variant = profile.name,
        ))
        for (value in listOf(0f, 1f)) add(Case(
            "length_${tag(value)}", "LENGTH ${(value * 100).toInt()}%", "The same source traverses the shortest or longest strand geometry. Delays move; the excitation pitch stays fixed.",
            "length", "comparison", voice, base + ("LENGTH" to value), config = coil, stems = true,
            comparison = "length", variant = tag(value),
        ))
        for (value in listOf(0f, 1f)) add(Case(
            "bundle_${tag(value)}", if (value == 0f) "One strand" else "Four strands", "BUNDLE reallocates the same launch budget over one or four strands. Source and common raw gain stay fixed.",
            "bundle", "comparison", voice, base + ("BUNDLE" to value), config = coil, stems = true,
            comparison = "bundle", variant = tag(value),
        ))
        for (value in listOf(0f, .5f, 1f)) add(Case(
            "collect_${tag(value)}", when (value) { 0f -> "THROUGH only"; 1f -> "SPILL only"; else -> "THROUGH + SPILL" },
            "The same strand network and excitation; COLLECT selects the retained endpoint, the middle collection or the escaping halo. Use raw to compare their actual relative level.",
            "collect", "comparison", voice, base + ("COLLECT" to value), config = coil, stems = true,
            comparison = "collect", variant = tag(value),
        ))
        add(Case(
            "perfect_retention_spill", "Perfect retention · no spill", "A diagnostic physical limit: perfect retention with COLLECT on SPILL must export silence, even with BEND at maximum. The source and retained branch remain available as stems.",
            "retention", "limit", voice, base + mapOf("BEND" to 1f, "COLLECT" to 1f), config = coil.copy(perfectRetention = true), stems = true,
        ))
        val mechanism = originalDefaults(FilamentVoice.PULSE) + mapOf("INJECT" to .65f, "BEND" to .8f, "LENGTH" to .9f, "BUNDLE" to 1f, "COLLECT" to .6f, "HOLD" to 0f)
        for ((variant, title, options) in listOf(
            Triple("full", "Full ordered network", Filament.Options()),
            Triple("collapsed", "Paths collapsed to one delay", Filament.Options(collapsePaths = true)),
            Triple("generic", "Generic spill comparison", Filament.Options(genericSpill = true)),
            Triple("straight", "Bend escape disabled", Filament.Options(disableBend = true)),
        )) add(Case(
            "mechanism_$variant", title, "PULSE with identical source and controls. The replay recipe records the explicit diagnostic option; compare the ordered routing against each simplified mechanism.",
            "mechanism", "mechanism", FilamentVoice.PULSE, mechanism, config = coil, options = options, stems = true,
            comparison = "mechanism", variant = variant,
        ))
        val sweepVoices = linkedMapOf(
            "INJECT" to FilamentVoice.PULSE, "BEND" to FilamentVoice.THREAD,
            "LENGTH" to FilamentVoice.PULSE, "BUNDLE" to FilamentVoice.CURL, "COLLECT" to FilamentVoice.THREAD,
        )
        for ((macro, sweepVoice) in sweepVoices) for (value in if (quick) listOf(0f, 1f) else steps) add(Case(
            "sweep_${macro.lowercase()}_${tag(value)}", "$macro ${(value * 100).toInt()}%", "${sweepVoice.name}: only $macro changes; companion controls retain this voice's defaults. Native and raw preserve level movement, while matched playback helps compare texture.",
            "sweep-${macro.lowercase()}", "sweep", sweepVoice, originalDefaults(sweepVoice) + (macro to value), macro = macro, value = value,
        ))
        for (rangeVoice in FilamentVoice.entries) for (midi in if (quick) listOf(60) else listOf(36, 60, 84)) for (velocity in if (quick) listOf(1f) else listOf(.25f, .65f, 1f)) add(Case(
            "range_${rangeVoice.name.lowercase()}_${midi}_${tag(velocity)}", "${note(midi)} · velocity ${(velocity * 100).toInt()}%", "${rangeVoice.name} at MIDI $midi and velocity $velocity. All other settings use voice defaults; native and raw playback preserve velocity level differences.",
            "range-${rangeVoice.name.lowercase()}", "range", rangeVoice, midi = midi, velocity = velocity,
        ))
        for (heldVoice in FilamentVoice.entries) add(Case(
            "hold_${heldVoice.name.lowercase()}", "${heldVoice.name} · HOLD", "One converged held period. Enable repeat for playback; the diagnostics include the independent preceding/current cycle PCM context and measured loop join.",
            "hold", "hold", heldVoice, originalDefaults(heldVoice) + ("HOLD" to 1f), stems = true,
        ))
    }

    private fun metadata(case: Case): Map<String, Any?> = linkedMapOf(
        "id" to case.id, "title" to case.title, "description" to case.description, "group" to case.group,
        "kind" to case.kind, "voice" to case.voice.name, "model" to case.model, "midi" to case.midi, "velocity" to case.velocity,
        "macros" to case.macros, "config" to config(case.config), "options" to options(case.options),
        "macro" to case.macro, "value" to case.value, "comparison" to case.comparison,
        "variant" to case.variant, "preset" to case.preset?.name,
        "comparisonSourcePolicy" to if (case.requireFixedSource) "fixed-source" else "model-change",
        "comparisonPartnerId" to case.comparisonPartnerId,
    )

    private fun originalDefaults(voice: FilamentVoice): Map<String, Float> {
        val values = when (voice) {
            FilamentVoice.THREAD -> listOf(.25f, .15f, .25f, 0f, .20f)
            FilamentVoice.PULSE -> listOf(.65f, .30f, .65f, .20f, .35f)
            FilamentVoice.CURL -> listOf(.45f, .65f, .45f, .15f, .50f)
        }
        return listOf("INJECT", "BEND", "LENGTH", "BUNDLE", "COLLECT").zip(values).toMap() + ("HOLD" to 0f)
    }

    private fun isDiagnostic(options: Filament.Options) = options.collapsePaths || options.genericSpill || options.disableBend

    private fun analysisWindows(raw: FloatArray, matched: FloatArray, source: FloatArray): List<Map<String, Any?>> =
        listOf(Triple("early", .04, .18), Triple("body", .20, .55), Triple("late", .75, 1.15)).map { (id, from, to) ->
            val start = (from * Dsp.RATE).toInt().coerceAtMost(raw.size)
            val end = (to * Dsp.RATE).toInt().coerceAtMost(raw.size)
            val mixWindow = raw.copyOfRange(start, end)
            val sourceWindow = source.copyOfRange(start, end)
            val matchedWindow = matched.copyOfRange(start, end)
            linkedMapOf(
                "id" to id, "fromSeconds" to from, "toSeconds" to to, "availableFrames" to end - start,
                "rawRms" to mixWindow.takeIf { it.isNotEmpty() }?.let(TangleMeasure::rms),
                "matchedRms" to matchedWindow.takeIf { it.isNotEmpty() }?.let(TangleMeasure::rms),
                "sourceRms" to sourceWindow.takeIf { it.isNotEmpty() }?.let(TangleMeasure::rms),
                "mixPowerCentroidHz" to powerCentroid(mixWindow),
                "sourcePowerCentroidHz" to powerCentroid(sourceWindow),
            )
        }

    private fun powerCentroid(samples: FloatArray): Double? {
        if (samples.size < 2) return null
        val fftSize = 32768
        val real = FloatArray(fftSize)
        val imaginary = FloatArray(fftSize)
        for (i in samples.indices) real[i] = (samples[i] * (.5 - .5 * cos(2.0 * PI * i / (samples.size - 1)))).toFloat()
        Fft.forward(real, imaginary)
        var power = 0.0
        var weighted = 0.0
        for (i in 1..fftSize / 2) {
            val value = real[i].toDouble() * real[i] + imaginary[i].toDouble() * imaginary[i]
            power += value
            weighted += value * i * Dsp.RATE / fftSize
        }
        return if (power > 1e-30) weighted / power else null
    }

    private fun characterComparisons(clips: List<Map<String, Any?>>): List<Map<String, Any?>> = FilamentVoice.entries.map { voice ->
        fun excerpt(variant: String): Map<String, Any?> {
            val id = "character_${voice.name.lowercase()}_$variant"
            val clip = clips.first { it["id"] == id }
            val metrics = clip["metrics"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val keys = listOf(
                "sourceRequestedRootEnergyShare", "requestedRootEnergyShare", "sourceBodyPowerCentroidHz",
                "mixBodyPowerCentroidHz", "rawRms", "weightedThroughRms", "weightedSpillRms",
                "weightedSpillIndependentPowerShare", "analysisWindows", "comparisonGainMatchedSpectralDistance",
            )
            return linkedMapOf("id" to id, "model" to clip["model"], "status" to clip["status"]) + keys.associateWith { metrics[it] }
        }
        linkedMapOf("voice" to voice.name, "original" to excerpt("original"), "revised" to excerpt("revised"))
    }

    private fun config(c: FilamentConfig) = linkedMapOf<String, Any?>(
        "seed" to c.seed, "profile" to c.profile.name, "perfectRetention" to c.perfectRetention,
    )

    private fun options(o: Filament.Options) = linkedMapOf<String, Any?>(
        "collapsePaths" to o.collapsePaths, "genericSpill" to o.genericSpill, "disableBend" to o.disableBend,
    )

    private fun diagnostics(d: Filament.Diagnostics): Map<String, Any?> = linkedMapOf(
        "launch" to d.launch, "retained" to d.retained, "escaped" to d.escaped, "absorbed" to d.absorbed,
        "rawPeak" to d.rawPeak, "rawRms" to d.rawRms, "rawDc" to d.rawDc, "sourceEffort" to d.sourceEffort,
        "loop" to d.loop, "loopConverged" to d.loopConverged, "seamError" to d.seamError,
        "fullCycleError" to d.fullCycleError, "stateError" to d.stateError,
        "haloGainBound" to d.haloGainBound, "haloNormalization" to d.haloNormalization,
        "haloModes" to d.haloModes.map { mode -> linkedMapOf(
            "frequencyHz" to mode.frequencyHz, "t60Seconds" to mode.t60Seconds, "weight" to mode.weight,
        ) },
        "actualHz" to d.actualHz, "periodFrames" to d.periodFrames, "loopAttempts" to d.loopAttempts,
        "maxState" to d.maxState, "preparationNanos" to d.preparationNanos, "synthesisNanos" to d.synthesisNanos,
        "branches" to d.branches.map { b -> linkedMapOf(
            "id" to b.id, "strandId" to b.strandId, "pathId" to b.pathId,
            "launch" to b.launch, "retained" to b.retained, "escaped" to b.escaped, "absorbed" to b.absorbed,
            "endpointSeconds" to b.endpointSeconds,
        ) },
        "sites" to d.sites.map { s -> linkedMapOf(
            "id" to s.id, "strandId" to s.strandId, "pathId" to s.pathId, "sectionId" to s.sectionId,
            "delaySeconds" to s.delaySeconds, "retainedFraction" to s.retainedFraction,
            "escapedFraction" to s.escapedFraction, "absorbedFraction" to s.absorbedFraction,
            "incomingPower" to s.incomingPower, "retainedPower" to s.retainedPower,
            "escapedPower" to s.escapedPower, "absorbedPower" to s.absorbedPower,
        ) },
    )

    private fun pcmSeam(samples: FloatArray, period: Int): Map<String, Any?> {
        require(samples.size == period * 2 && period >= 256)
        var fullDifference = 0.0
        var fullReference = 0.0
        var seamDifference = 0.0
        var seamReference = 0.0
        for (i in 0 until period) {
            val previous = samples[i].toDouble()
            val difference = samples[i + period] - previous
            fullDifference += difference * difference
            fullReference += previous * previous
            if (i >= period - 256) {
                seamDifference += difference * difference
                seamReference += previous * previous
            }
        }
        fun relative(difference: Double, reference: Double) = when {
            reference > 0.0 -> difference / reference
            difference == 0.0 -> 0.0
            else -> Double.POSITIVE_INFINITY
        }
        var derivativeEnergy = 0.0
        var derivativeCount = 0
        for (i in period + 1 until minOf(period + 256, samples.size)) {
            val difference = samples[i].toDouble() - samples[i - 1]
            derivativeEnergy += difference * difference
            derivativeCount++
        }
        for (i in maxOf(period + 1, samples.size - 255) until samples.size) {
            val difference = samples[i].toDouble() - samples[i - 1]
            derivativeEnergy += difference * difference
            derivativeCount++
        }
        val derivativeRms = sqrt(derivativeEnergy / derivativeCount.coerceAtLeast(1))
        val wrapStep = abs(samples[period].toDouble() - samples.last())
        return linkedMapOf(
            "exportedSeamError" to relative(seamDifference, seamReference),
            "exportedFullCycleError" to relative(fullDifference, fullReference),
            "silentBoundary" to (seamReference == 0.0 && seamDifference == 0.0),
            "seamComparisonDifferenceEnergy" to seamDifference, "seamComparisonReferenceEnergy" to seamReference,
            "exportedWrapStep" to wrapStep, "exportedWrapDerivativeRms" to derivativeRms,
            "exportedWrapStepRatio" to wrapStep / (derivativeRms + 1.0 / 32768.0),
            "exportedSeamLimit" to MAX_SEAM_ERROR, "exportedWrapStepRatioLimit" to MAX_WRAP_STEP_RATIO,
            "seamContextFrames" to samples.size, "seamLoopStartFrame" to period,
        )
    }

    private fun maximumPaddedDifference(a: FloatArray, b: FloatArray): Double {
        var maximum = 0.0
        for (i in 0 until maxOf(a.size, b.size)) maximum = maxOf(maximum, abs(a.getOrElse(i) { 0f }.toDouble() - b.getOrElse(i) { 0f }))
        return maximum
    }

    private fun scaled(samples: FloatArray, gain: Float) = Snip(FloatArray(samples.size) { samples[it] * gain }, 1, Dsp.RATE)
    private fun peak(samples: FloatArray) = samples.maxOfOrNull { abs(it).toDouble() } ?: 0.0
    private fun tag(value: Float) = "%03d".format((value * 100).toInt())
    private fun slug(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    private fun note(midi: Int) = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)

    private fun groupTitle(id: String): String = when (id) {
        "character" -> "Original and revised · three voices"
        "character-presets" -> "Twelve expressive recipes"
        "character-context" -> "Across the keyboard"
        "character-sweep-bend" -> "Revised CURL · BEND"
        "character-sweep-collect" -> "Revised THREAD · COLLECT"
        "character-mechanism" -> "Revised routing · simplified comparisons"
        "intro" -> "Original three voices"
        "presets" -> "Original twelve recipes"
        "bend" -> "BEND · listen to the transfer"
        "placement" -> "Where the bend happens"
        "length" -> "Short and long geometry"
        "bundle" -> "One strand, four strands"
        "collect" -> "Follow each collector"
        "retention" -> "Perfect retention limit"
        "mechanism" -> "Does the routing matter?"
        "hold" -> "Held cycles"
        else -> when {
            id.startsWith("sweep-") -> "${id.removePrefix("sweep-").uppercase()} · five-step sweep"
            id.startsWith("range-") -> "${id.removePrefix("range-").uppercase()} · pitch and velocity"
            else -> id
        }
    }

    private fun groupDescription(id: String): String = when (id) {
        "character" -> "One model 1/model 2 pair for THREAD, PULSE and CURL. Each pair shares explicit controls, C3, velocity 70%, seed and profile. Hear matched colour, then compare native level and source/collector stems."
        "character-presets" -> "Four model 2 recipes per voice, including a held recipe. Every ordinary patch is decoded and rendered again to check exact saved-model replay."
        "character-context" -> "Each revised voice at C2/35% velocity and C5/90% velocity. These musical examples change both register and touch; the original controlled pitch/velocity grid remains below."
        "character-sweep-bend" -> "Model 2 CURL: five BEND settings with fixed excitation. Follow the changing balance and character of the escaping sound."
        "character-sweep-collect" -> "Model 2 THREAD: five COLLECT settings reveal the guided endpoint, their middle balance and the escaping halo. Raw preserves level differences."
        "character-mechanism" -> "Model 2 PULSE with the full network, a generic spill, and collapsed path delays. Explicit diagnostic options remain separate from the saved base patch."
        "intro" -> "Original model 1 THREAD, PULSE and CURL. Their original clip IDs and audio remain intact; source, THROUGH, escaped-input and SPILL stems are available."
        "presets" -> "The original twelve model 1 recipes: four per voice, including one held recipe. Their saved model stays explicit."
        "bend" -> "Same source, geometry, launch budget and raw gain. Only BEND changes: 0, 50 and 100%."
        "placement" -> "EARLY, LATE and COIL move bend escape along the same ordered strand. The excitation and visible controls stay fixed."
        "length" -> "Two strand lengths with the same excitation and controls."
        "bundle" -> "The launch budget is shared between one strand and four strands."
        "collect" -> "Hear the retained endpoint, the middle collection and the escaping halo. Use raw playback to preserve their level relationship."
        "retention" -> "A controlled limit: all guided power is retained, so the SPILL collector is silent."
        "mechanism" -> "One full network against collapsed path delays, a generic halo and disabled bend escape. Every variant supplies explicit diagnostic replay options."
        "hold" -> "One independently verified recurring cycle per voice. Enable repeat; open the PCM seam-context WAV to inspect the actual predecessor."
        else -> if (id.startsWith("sweep-")) "Five settings from 0 to 100%; companion controls stay at voice defaults." else "C2, C4 and C6 at 25%, 65% and 100% velocity. Native and raw retain velocity level; matched playback helps compare texture."
    }

    private fun git(vararg arguments: String): String? = runCatching {
        ProcessBuilder(listOf("git") + arguments).redirectErrorStream(true).start().let { process ->
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0) output else null
        }
    }.getOrNull()

    private fun cpuModel(): String? = runCatching {
        File("/proc/cpuinfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("model name") || it.startsWith("Hardware") }
                ?.substringAfter(':')?.trim()
        }
    }.getOrNull()

    private fun relevantVmArguments(): List<String> = ManagementFactory.getRuntimeMXBean().inputArguments.filter { argument ->
        listOf(
            "-Xmx", "-Xms", "-XX:MaxRAMPercentage=", "-XX:InitialRAMPercentage=",
            "-XX:MinRAMPercentage=", "-XX:MaxHeapSize=", "-XX:InitialHeapSize=",
            "-XX:ActiveProcessorCount=", "-XX:+UseG1GC", "-XX:+UseParallelGC",
            "-XX:+UseSerialGC", "-XX:+UseZGC",
        ).any { argument.startsWith(it) }
    }

    private fun zip(root: File, nativeClipIds: Set<String>, allClipIds: Set<String>) {
        val readme = File(root, "LISTENING-PACK.txt")
        readme.writeText("""
            FILAMENT · character listening iteration

            This curated pack contains ${nativeClipIds.size} native PCM24 clips covering the six
            original/revised voice comparisons, twelve revised expressive presets and six
            revised musical register/velocity contexts. It also contains every clip's
            recipe, ordinary patch where applicable, JSON diagnostics and every held
            independently generated predecessor/current PCM16 seam context.

            The manifest describes the complete ${allClipIds.size}-clip listening page.
            The original 88-clip model 1 corpus, raw/matched/stem files, macro sweeps and
            diagnostic mechanism audio remain individually downloadable on that page;
            those audio files are omitted here to
            keep this pack below the host's per-file limit. The manifest's packScope field
            lists the included native clip IDs. This is a curated audio/recipe pack rather
            than a complete offline copy of the listening website.

            Native files preserve the engine's ordinary dry output. Raw files and stems on
            the page use one shared gain of 0.25; matched files use the common 0.03
            loudest-200-ms target. An intentionally silent collector remains silent.
            Diagnostic ablation recipes keep options separate from their base patch.
            Each clip and every saved patch explicitly names model 1 or model 2; omitted
            patch model retains the original model 1 contract. The character comparisons
            intentionally allow changed excitation between renderer versions. Existing
            geometry comparisons keep their fixed-source guards.
            Numeric evidence supports listening and does not establish musical acceptance.
        """.trimIndent() + "\n")
        val destination = File(root, PACK_NAME)
        val files = root.walkTopDown().filter { file ->
            if (!file.isFile) false else {
                val relative = file.relativeTo(root).invariantSeparatorsPath
                relative == "LISTENING-PACK.txt" || relative == "manifest.json" ||
                    relative.startsWith("recipes/") && file.extension == "json" && file.nameWithoutExtension in allClipIds ||
                    relative.startsWith("patches/") && file.extension == "json" && file.nameWithoutExtension in allClipIds ||
                    relative.startsWith("diagnostics/") && (file.extension == "json" && file.nameWithoutExtension in allClipIds || file.name.endsWith("_seam-context.wav") && file.name.removeSuffix("_seam-context.wav") in allClipIds) ||
                    relative.startsWith("native/") && file.nameWithoutExtension in nativeClipIds
            }
        }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.toList()
        ZipOutputStream(destination.outputStream().buffered()).use { output ->
            for (file in files) {
                output.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(output) }
                output.closeEntry()
            }
        }
        check(destination.length() < PACK_LIMIT_BYTES) {
            "Listening pack is ${destination.length()} bytes; split the audio pack before publishing (limit $PACK_LIMIT_BYTES)"
        }
    }

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
        else -> error("Unsupported FILAMENT audition JSON ${value.javaClass.name}")
    }
}
