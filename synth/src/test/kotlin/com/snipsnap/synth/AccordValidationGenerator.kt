package com.snipsnap.synth

import com.snipsnap.json.Json
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Manual full-length native validation, deliberately outside the unit-test loop.
 * Actual performances retain the engine's frozen calibration. This generator
 * never normalizes a render or exports an overloaded WAV to conceal a failure.
 */
object AccordValidationGenerator {
    private data class Case(val patch: AccordPatch, val sections: MutableSet<String>)
    private val profiles = listOf(
        AccordVoice.BRUSH to AccordAcousticProfile.BASE,
        AccordVoice.WOOD to AccordAcousticProfile.BASE,
        AccordVoice.TINE to AccordAcousticProfile.REFERENCE,
        AccordVoice.TINE to AccordAcousticProfile.SOFT_ATTACK,
        AccordVoice.TINE to AccordAcousticProfile.BRIGHT_EDGE,
        AccordVoice.TINE to AccordAcousticProfile.LONG_RING,
    )
    private val resonanceMeasurements = ArrayList<Map<String, Any>>()

    private fun cases(quick: Boolean): List<Case> {
        val cases = linkedMapOf<String, Case>()
        fun add(section: String, voice: AccordVoice, profile: AccordAcousticProfile,
            midi: Int = 48, velocity: Float = 1f, macros: Map<String, Float> = emptyMap(),
            seed: Long = Accord.DEFAULT_SEED, start: Double = 8.0) {
            val patch = AccordPatch("Accord validation", voice, Accord.defaults(voice) + macros,
                midi = midi, velocity = velocity, seed = seed, acousticProfile = profile,
                initializer = if (seed == Accord.DEFAULT_SEED) AccordInitializer.REFERENCE_SINE_20261022
                              else AccordInitializer.PROJECT_SINE_V1,
                sourceStartCycles = start, sourceSeconds = 8.0)
            val key = Json.write(patch.toJsonValue())
            cases.getOrPut(key) { Case(patch, linkedSetOf()) }.sections += section
        }
        // All six accepted acoustic profiles retain their own static calibration.
        for ((voice, profile) in profiles) for (midi in if (quick) listOf(48) else listOf(36, 48, 60, 72)) {
            for (velocity in if (quick) listOf(.75f, 1f) else listOf(.3f, .75f, 1f)) {
                add("register_velocity", voice, profile, midi, velocity)
            }
        }
        // Keep each physical setting adjacent so all six voices can reuse the
        // same deterministic mechanics; cache eviction must affect cost only.
        val coupling = if (quick) listOf(0f, 1f) else listOf(0f, .5f, 1f)
        val mismatch = if (quick) listOf(0f, 1f) else listOf(0f, .25f, 1f)
        val raft = if (quick) listOf(0f, 1f) else listOf(0f, .5f, 1f)
        for (c in coupling) for (m in mismatch) for (r in raft) for ((voice, profile) in profiles) {
            add("mechanical_grid", voice, profile, macros = mapOf("COUPLING" to c, "MISMATCH" to m, "RAFT" to r))
        }
        val supports: List<Map<String, Float>> = if (quick) listOf(emptyMap())
            else listOf(emptyMap(), mapOf("COUPLING" to 0f), mapOf("RAFT" to 0f))
        for (support in supports) for (pace in listOf(0f, 1f)) for (contact in listOf(0f, 1f)) {
            for ((voice, profile) in profiles) add("pace_contact_extremes", voice, profile,
                macros = support + mapOf("PACE" to pace, "CONTACT" to contact))
        }
        for (pace in listOf(0f, .25f, .5f, .75f, 1f)) for ((voice, profile) in profiles) {
            add("pace_five_step_hard_contact", voice, profile,
                macros = mapOf("PACE" to pace, "CONTACT" to 1f))
        }
        // A resonator can be loudest between slider endpoints when a contact
        // harmonic lands on its root. Derive an actual candidate from a late
        // stable directional period, then render it through the public engine.
        val trace = AccordDynamics.prepare(cycles = 48)
        val crossings = trace.events.filter { it.mechanism == 0 && it.direction == 1 && it.cycle >= 32.0 }
        val period = (crossings.last().cycle - crossings.first().cycle) / (crossings.size - 1)
        for (midi in if (quick) listOf(48) else listOf(36, 48, 60, 72)) {
            val rootHz = Accord.frequencyFor(midi)
            val harmonic = ceil(rootHz * period / 8.0).toInt()
            val referenceHz = rootHz * period / (2.0 * harmonic)
            require(referenceHz in 1.0..4.0)
            val paceMacro = (ln(referenceHz) / (2.0 * ln(2.0))).toFloat().coerceIn(0f, 1f)
            resonanceMeasurements += linkedMapOf("midi" to midi, "measuredPeriodReferenceCycles" to period,
                "contactHarmonic" to harmonic, "desiredReferenceHz" to referenceHz, "paceMacro" to paceMacro,
                "basis" to "mechanism0 positive crossings in reference cycles32..48; candidate, not a locking assertion")
            for ((voice, profile) in profiles) add("period_derived_pace_resonance", voice, profile, midi = midi,
                macros = mapOf("PACE" to paceMacro, "CONTACT" to 1f))
        }
        val seeds = if (quick) listOf(Long.MIN_VALUE, Long.MAX_VALUE) else listOf(Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L)
        for (seed in seeds) for ((voice, profile) in listOf(
            AccordVoice.BRUSH to AccordAcousticProfile.BASE,
            AccordVoice.WOOD to AccordAcousticProfile.BASE,
            AccordVoice.TINE to AccordAcousticProfile.BRIGHT_EDGE)) {
            add("project_seed", voice, profile, seed = seed)
        }
        for ((voice, profile) in profiles) add("raw_scattered_start", voice, profile, start = 0.0)
        return cases.values.toList()
    }

    private fun actualMetrics(samples: FloatArray): Map<String, Any> {
        var peak = 0.0
        var mean = 0.0
        var energy = 0.0
        var finite = true
        for (sample in samples) {
            if (!sample.isFinite()) finite = false
            peak = max(peak, abs(sample.toDouble()))
            mean += sample
            energy += sample.toDouble() * sample
        }
        return linkedMapOf("finite" to finite, "peak" to peak,
            "dc" to mean / samples.size, "rms" to sqrt(energy / samples.size),
            "frames" to samples.size, "seconds" to samples.size.toDouble() / Dsp.RATE)
    }

    /** Inspect the un-faded conditioned continuation, then apply the stored gain. */
    private fun tailMetrics(probe: Accord.Probe): Map<String, Any> {
        val samples = probe.raw.samples
        val calibration = probe.diagnostics.calibrationGain
        fun rms(from: Int, to: Int): Double {
            var energy = 0.0
            for (i in from until to) energy += samples[i].toDouble() * samples[i]
            return sqrt(energy / max(1, to - from))
        }
        fun peak(from: Int, to: Int): Double {
            var value = 0.0
            for (i in from until to) value = max(value, abs(samples[i].toDouble()))
            return value
        }
        val fadeFrames = (Dsp.RATE * .004).toInt()
        val fadeStart = samples.size - fadeFrames
        val active = rms(0, minOf(samples.size, 8 * Dsp.RATE))
        val last100ms = rms(max(0, samples.size - Dsp.RATE / 10), samples.size)
        return linkedMapOf("conditionedActiveRmsBeforeCalibration" to active,
            "conditionedLast100msRmsBeforeCalibration" to last100ms,
            "preFadeLast100msRms" to last100ms * calibration,
            "preFadeLast100msRelativeToActiveDb" to 20 * log10(max(last100ms, 1e-30) / max(active, 1e-30)),
            "fadeEntryAbsoluteSample" to abs(samples[fadeStart].toDouble()) * calibration,
            "last4msBeforeFadePeak" to peak(max(0, fadeStart - fadeFrames), fadeStart) * calibration,
            "last4msWithoutFadePeak" to peak(fadeStart, samples.size) * calibration,
            "unforcedReleaseSeconds" to samples.size.toDouble() / Dsp.RATE - 8.0)
    }

    private fun performance(): Map<String, Any> {
        val patch = AccordPatch("Accord performance reference")
        Accord.clearCaches()
        val coldBegan = System.nanoTime()
        val cold = Accord.probe(patch)
        val coldWall = (System.nanoTime() - coldBegan) / 1e6
        Accord.clearAudioCache()
        val warmBegan = System.nanoTime()
        val warm = Accord.probe(patch)
        val warmWall = (System.nanoTime() - warmBegan) / 1e6
        val recallBegan = System.nanoTime()
        val recall = Accord.probe(patch)
        val recallWall = (System.nanoTime() - recallBegan) / 1e6
        check(cold.samples.samples.contentEquals(warm.samples.samples)) { "Preparation reuse changed Accord PCM" }
        check(cold.samples.samples.contentEquals(recall.samples.samples)) { "Audio recall changed Accord PCM" }
        return linkedMapOf(
            "machine" to mapOf("os" to System.getProperty("os.name"), "architecture" to System.getProperty("os.arch"),
                "jvm" to System.getProperty("java.vm.name"), "javaVersion" to System.getProperty("java.version"),
                "processors" to Runtime.getRuntime().availableProcessors()),
            "cold" to cold.diagnostics.toJsonValue(),
            "coldWallMilliseconds" to coldWall,
            "preparationHitAcousticRerender" to warm.diagnostics.toJsonValue(),
            "preparationHitAcousticWallMilliseconds" to warmWall,
            "exactAudioRecall" to recall.diagnostics.toJsonValue(),
            "exactAudioRecallWallMilliseconds" to recallWall,
            "byteIdentical" to true,
            "roughnessCache" to AccordAcoustics.roughnessCacheSnapshot().let {
                mapOf("entries" to it.entries, "bytes" to it.bytes, "hits" to it.hits, "generations" to it.generations)
            },
            "limitation" to "One desktop JVM timing per path; not a phone p95 acceptance measurement")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val quick = args.contains("--quick")
        val out = File(args.firstOrNull { !it.startsWith("--") } ?: "testkit/accord-validation").canonicalFile
        out.mkdirs()
        val began = System.nanoTime()
        val performance = performance()
        val cases = cases(quick)
        val rows = ArrayList<Map<String, Any?>>(cases.size)
        val failures = ArrayList<String>()
        var maximumPeak = 0.0
        var worstCase: Map<String, Any?>? = null
        for ((index, case) in cases.withIndex()) {
            val patch = case.patch
            try {
                val probe = Accord.probe(patch)
                val metrics = actualMetrics(probe.samples.samples)
                val peak = metrics.getValue("peak") as Double
                val finite = metrics.getValue("finite") as Boolean
                val dc = metrics.getValue("dc") as Double
                val row = linkedMapOf<String, Any?>("index" to index, "sections" to case.sections,
                    "patch" to patch.toJsonValue(), "actual" to metrics,
                    "tailBoundary" to tailMetrics(probe),
                    "diagnostics" to probe.diagnostics.toJsonValue())
                rows += row
                if (!finite) failures += "$index: nonfinite output"
                if (peak > .94001) failures += "$index: output peak $peak exceeds .94001"
                if (abs(dc) >= .005) failures += "$index: DC $dc exceeds .005"
                if (probe.diagnostics.minimumD <= 0.0) failures += "$index: nonpositive mechanical Schur denominator"
                if (peak > maximumPeak) { maximumPeak = peak; worstCase = row }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                failures += "$index: ${failure.javaClass.simpleName}: ${failure.message}"
                rows += linkedMapOf("index" to index, "sections" to case.sections,
                    "patch" to patch.toJsonValue(), "error" to failures.last())
            }
            if ((index + 1) % 12 == 0 || index == cases.lastIndex) {
                val checkpoint = linkedMapOf("mode" to if (quick) "quick" else "full", "completed" to rows.size,
                    "total" to cases.size, "maximumPeak" to maximumPeak, "worstCase" to worstCase,
                    "failureCount" to failures.size, "failures" to failures, "renders" to rows)
                AccordAuditionFiles.writeJson(File(out, "checkpoint.json"), checkpoint)
                println("Accord validation ${index + 1}/${cases.size}: peak=$maximumPeak failures=${failures.size}")
            }
        }
        val summary = linkedMapOf<String, Any?>(
            "status" to "Native JVM full-length headroom, finiteness and performance probes; no listening judgment",
            "mode" to if (quick) "quick" else "full", "sourceSecondsEveryCase" to 8.0,
            "renderedCases" to rows.size, "mechanicalGridValuesPerAxis" to if (quick) 2 else 3,
            "profiles" to profiles.map { "${it.first}/${it.second}" },
            "paceResonanceMeasurements" to resonanceMeasurements,
            "matchingPolicy" to "Actual fixed engine calibration; no per-render, per-velocity or per-case normalization",
            "maximumPeak" to maximumPeak, "worstCase" to worstCase,
            "requiredPeakCeiling" to .94001, "failureCount" to failures.size, "failures" to failures,
            "performance" to performance, "renders" to rows,
            "wallSeconds" to (System.nanoTime() - began) / 1e9,
            "limitations" to listOf("Finite sampled control/seed matrix, not an analytic all-seed bound",
                "Higher-rate alias comparison has not been run by this generator", "Desktop timing is not phone performance"))
        AccordAuditionFiles.writeJson(File(out, "report.json"), summary)
        println("Accord validation report: ${File(out, "report.json")}; maximum peak $maximumPeak")
        check(failures.isEmpty()) { "Accord validation found ${failures.size} failures; inspect report before changing frozen calibration" }
    }
}
