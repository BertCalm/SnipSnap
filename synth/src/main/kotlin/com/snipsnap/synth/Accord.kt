package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonValue
import java.util.Collections
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

enum class AccordVoice { BRUSH, WOOD, TINE }
enum class AccordAcousticProfile { BASE, REFERENCE, SOFT_ATTACK, BRIGHT_EDGE, LONG_RING }
enum class AccordInitializer { REFERENCE_SINE_20261022, PROJECT_SINE_V1 }

/**
 * Scattered contacts -> support-mediated adjustment -> collective or dispersed
 * pulses -> unforced ringing. Fifteen sustained mechanisms exchange inertial
 * reaction; the eight acoustic modes only read that history. Changing the note,
 * touch or material cannot reset the mechanisms or impose a gathering clock.
 *
 * The original accepted TINE ratio is 5.96. Profiles keep its attack, upper-mode
 * weight and ringing alternatives independent of the mechanical population.
 * Finite playback is implemented; nonzero HOLD is explicitly refused.
 */
object Accord {
    const val MODEL_VERSION = 1
    const val DEFAULT_SEED = 20261022L
    const val MIN_MIDI = 36
    const val MAX_MIDI = 72
    // Shorter excerpts were measured as KICK/TOM by the actual classifier.
    // Keep the finite instrument above its duration boundary, without forcing
    // metadata to disagree with the audio. The accepted eight seconds stays.
    const val MIN_SOURCE_SECONDS = .75
    const val MAX_SOURCE_SECONDS = 8.0
    private const val PRESSURE_RATE = 2000
    private const val MAX_TRACE_BYTES = 32L * 1024 * 1024
    private const val MAX_AUDIO_BYTES = 8L * 1024 * 1024

    fun supportsModel(model: Int): Boolean = model == MODEL_VERSION
    fun defaultProfile(voice: AccordVoice): AccordAcousticProfile =
        if (voice == AccordVoice.TINE) AccordAcousticProfile.REFERENCE else AccordAcousticProfile.BASE

    fun macrosFor(@Suppress("UNUSED_PARAMETER") voice: AccordVoice): List<MacroSpec> = listOf(
        MacroSpec("PACE", .5f, neutral = .5f),
        MacroSpec("COUPLING", 1f, neutral = 1f),
        MacroSpec("MISMATCH", .25f, neutral = .25f),
        MacroSpec("CONTACT", .5f, neutral = .5f),
        MacroSpec("RAFT", .5f, neutral = .5f),
        MacroSpec("HOLD", 0f, neutral = 0f),
    )

    fun defaults(voice: AccordVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    /** Convenience rendering tolerates UI noise; saved patches use strict validation. */
    fun settled(voice: AccordVoice, macros: Map<String, Float>): Map<String, Float> {
        val values = defaults(voice).toMutableMap()
        for ((name, value) in macros) {
            if (name in values && value.isFinite()) values[name] = value.coerceIn(0f, 1f)
        }
        require(values.getValue("HOLD") == 0f) { "ACCORD is finite; sustained HOLD is not implemented" }
        return Collections.unmodifiableMap(values)
    }

    fun scramble(voice: AccordVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> =
        Dsp.scrambleNear(defaults(voice) + near?.macros.orEmpty(), temperature, random) + ("HOLD" to 0f)

    fun rootMidi(@Suppress("UNUSED_PARAMETER") voice: AccordVoice): Int = 48
    fun frequencyFor(midi: Int): Double {
        require(midi in MIN_MIDI..MAX_MIDI)
        return 440.0 * 2.0.pow((midi - 69) / 12.0)
    }

    data class ContactEvent(val cycle: Double, val timeSeconds: Double, val mechanism: Int,
        val direction: Int, val canonicalSpeed: Double, val ordinal: Long)

    data class Diagnostics(
        val eventCount: Int,
        val rawPeak: Double,
        val conditionedPeak: Double,
        val outputPeak: Double,
        val outputRms: Double,
        val outputMean: Double,
        val calibrationGain: Double,
        val maximumPoleRadius: Double,
        val minimumD: Double,
        val relativeEnergyWorkError: Double,
        val solverSteps: Int,
        val preparedCycles: Int,
        val startR1: Double?,
        val endR1: Double?,
        val endR2: Double?,
        val physicsMilliseconds: Double,
        val calibrationMilliseconds: Double,
        val audioMilliseconds: Double,
        val renderMilliseconds: Double,
        val preparationCacheHit: Boolean,
        val audioCacheHit: Boolean,
    ) {
        fun toJsonValue(): JsonValue.Obj {
            fun n(value: Number) = JsonValue.Num(value.toDouble())
            fun optional(value: Double?): JsonValue = value?.let { n(it) } ?: JsonValue.Null
            return JsonValue.Obj(linkedMapOf(
                "eventCount" to n(eventCount), "rawPeak" to n(rawPeak),
                "conditionedPeak" to n(conditionedPeak), "outputPeak" to n(outputPeak),
                "outputRms" to n(outputRms), "outputMean" to n(outputMean),
                "calibrationGain" to n(calibrationGain), "maximumPoleRadius" to n(maximumPoleRadius),
                "minimumD" to n(minimumD), "relativeEnergyWorkError" to n(relativeEnergyWorkError),
                "solverSteps" to n(solverSteps), "preparedCycles" to n(preparedCycles),
                "startR1" to optional(startR1), "endR1" to optional(endR1), "endR2" to optional(endR2),
                "physicsMilliseconds" to n(physicsMilliseconds),
                "calibrationMilliseconds" to n(calibrationMilliseconds), "audioMilliseconds" to n(audioMilliseconds),
                "renderMilliseconds" to n(renderMilliseconds),
                "preparationCacheHit" to JsonValue.Bool(preparationCacheHit),
                "audioCacheHit" to JsonValue.Bool(audioCacheHit),
            ))
        }
    }

    data class Probe(val samples: Snip, val raw: Snip, val diagnostics: Diagnostics, val events: List<ContactEvent>)
    data class CacheSnapshot(val mechanicalPreparations: Long, val preparationHits: Long, val audioHits: Long,
        val mechanicalEntries: Int, val mechanicalBytes: Long, val audioEntries: Int, val audioBytes: Long,
        val roughnessEntries: Int, val roughnessBytes: Long)

    private data class TraceKey(val parameters: AccordDynamics.Parameters, val seed: Long,
        val initializer: AccordInitializer, val model: Int)
    private data class TraceLookup(val trace: AccordDynamics.Trace, val hit: Boolean)
    private data class AudioKey(val voice: AccordVoice, val macros: Map<String, Float>, val midi: Int,
        val velocity: Float, val seed: Long, val model: Int, val profile: AccordAcousticProfile,
        val initializer: AccordInitializer, val start: Double, val seconds: Double)
    private data class CalibrationKey(val voice: AccordVoice, val profile: AccordAcousticProfile, val midi: Int, val model: Int)
    private val cacheLock = Any()
    private val traces = LinkedHashMap<TraceKey, AccordDynamics.Trace>(8, .75f, true)
    private val audio = LinkedHashMap<AudioKey, Probe>(8, .75f, true)
    private var traceBytes = 0L
    private var audioBytes = 0L
    private var mechanicalPreparations = 0L
    private var preparationHits = 0L
    private var audioHits = 0L

    internal fun clearCaches() = synchronized(cacheLock) {
        traces.clear(); audio.clear()
        traceBytes = 0; audioBytes = 0
        mechanicalPreparations = 0; preparationHits = 0; audioHits = 0
        AccordAcoustics.clearRoughnessCache()
    }

    internal fun clearAudioCache() = synchronized(cacheLock) { audio.clear(); audioBytes = 0 }
    internal fun cacheSnapshot(): CacheSnapshot = synchronized(cacheLock) {
        val roughness = AccordAcoustics.roughnessCacheSnapshot()
        CacheSnapshot(mechanicalPreparations, preparationHits, audioHits, traces.size, traceBytes, audio.size, audioBytes,
            roughness.entries, roughness.bytes)
    }

    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("ACCORD render cancelled")
    }

    private fun elapsed(began: Long): Double = (System.nanoTime() - began) / 1e6

    private fun parameters(macros: Map<String, Float>): AccordDynamics.Parameters {
        val raft = macros.getValue("RAFT").toDouble()
        val mass = if (raft == .5) .6 else if (raft < .5) .05 * 12.0.pow(2 * raft) else .6 * 4.0.pow(2 * raft - 1)
        val damping = if (raft == .5) .05 else if (raft < .5) .005 * 10.0.pow(2 * raft) else .05 * 4.0.pow(2 * raft - 1)
        return AccordDynamics.Parameters(macros.getValue("COUPLING").toDouble(),
            .04 * macros.getValue("MISMATCH"), mass.coerceIn(.05, 2.4), damping.coerceIn(.005, .2))
    }

    private fun prepare(key: TraceKey, requiredEnd: Double): TraceLookup {
        checkCancelled()
        synchronized(cacheLock) {
            traces[key]?.takeIf { it.cycles >= requiredEnd }?.let {
                preparationHits++
                return TraceLookup(it, true)
            }
        }
        val cycles = (ceil(requiredEnd / 16.0).toInt() * 16).coerceAtLeast(16)
        val prepared = AccordDynamics.prepare(key.parameters, key.seed, key.initializer, cycles)
        checkCancelled()
        synchronized(cacheLock) {
            mechanicalPreparations++
            val raced = traces[key]
            if (raced != null && raced.cycles >= cycles) return TraceLookup(raced, false)
            if (prepared.retainedBytes <= 12L * 1024 * 1024) {
                traces.remove(key)?.let { traceBytes -= it.retainedBytes }
                while (traces.isNotEmpty() && (traces.size >= 6 || traceBytes + prepared.retainedBytes > MAX_TRACE_BYTES)) {
                    traceBytes -= traces.remove(traces.keys.first())!!.retainedBytes
                }
                traces[key] = prepared
                traceBytes += prepared.retainedBytes
            }
        }
        return TraceLookup(prepared, false)
    }

    /** Evaluate nonlinear pressure after state interpolation, as in the accepted fixture. */
    private fun pressure(trace: AccordDynamics.Trace, start: Double, seconds: Double, pace: Double): DoubleArray {
        val count = Math.rint(seconds * PRESSURE_RATE).toInt()
        val out = DoubleArray(count)
        val state = DoubleArray(AccordDynamics.STATE_SIZE)
        for (j in out.indices) {
            if (j % PRESSURE_RATE == 0) checkCancelled()
            trace.compatibilityStateAt(start + pace * j / PRESSURE_RATE, state)
            var sum = 0.0
            for (i in 0 until AccordDynamics.COUNT) {
                val theta = state[i] / .17
                val gate = max(0.0, 1.0 - theta * theta)
                sum += gate * gate * min(abs(state[15 + i]) / .8, 2.0) / 15
            }
            out[j] = sum
        }
        return out
    }

    private fun condition(voice: AccordVoice, profile: AccordAcousticProfile, midi: Int, velocity: Float,
        macros: Map<String, Float>, trace: AccordDynamics.Trace, seed: Long, start: Double, seconds: Double,
        pace: Double): AccordAcoustics.Result = AccordAcoustics.renderConditioned(
        AccordAcoustics.configuration(voice, profile, midi, velocity, macros.getValue("CONTACT"), macros.getValue("RAFT")),
        trace.events, pressure(trace, start, seconds, pace), seconds, seed, start, pace,
    )

    /**
     * This recipe is fixed by the model, never the user's edited performance.
     * Calibrating each velocity or each COUPLING setting would erase dynamics.
     * A shared hard-contact/body probe reserves headroom for the same note.
     */
    internal fun referenceCalibrationGain(voice: AccordVoice, profile: AccordAcousticProfile, midi: Int): Double =
        computeReferenceCalibration(CalibrationKey(voice, profile, midi, MODEL_VERSION))

    private fun calibration(key: CalibrationKey): Double {
        require(key.model == AccordCalibration.MODEL_VERSION) { "ACCORD reference calibration model mismatch" }
        return AccordCalibration.gain(key.voice, key.profile, key.midi)
    }

    private fun computeReferenceCalibration(key: CalibrationKey): Double {
        val macros = defaults(key.voice)
        val trace = prepare(TraceKey(parameters(macros), DEFAULT_SEED,
            AccordInitializer.REFERENCE_SINE_20261022, key.model), 24.0).trace
        val normal = condition(key.voice, key.profile, key.midi, .75f, macros, trace, DEFAULT_SEED, 8.0, 8.0, 2.0)
        val desired = AccordAcoustics.calibrationGain(normal.samples, 8.0)
        val hard = condition(key.voice, key.profile, key.midi, 1f,
            macros + ("CONTACT" to 1f) + ("RAFT" to 1f), trace, DEFAULT_SEED, 8.0, 8.0, 2.0)
        val hardPeak = hard.samples.maxOf { abs(it.toDouble()) }
        val gain = min(desired, .90 / max(hardPeak, 1e-12))
        require(gain.isFinite() && gain > 0) { "ACCORD invalid reference calibration" }
        checkCancelled()
        return gain
    }

    fun render(patch: AccordPatch): Snip = probe(patch).samples
    fun probe(patch: AccordPatch): Probe = probe(patch.voice, patch.macros, patch.midi, patch.velocity,
        patch.seed, patch.model, patch.acousticProfile, patch.initializer, patch.sourceStartCycles, patch.sourceSeconds)

    fun render(voice: AccordVoice, macros: Map<String, Float> = emptyMap(), midi: Int = 48,
        velocity: Float = .75f, seed: Long = DEFAULT_SEED, model: Int = MODEL_VERSION,
        acousticProfile: AccordAcousticProfile = defaultProfile(voice),
        initializer: AccordInitializer = AccordInitializer.REFERENCE_SINE_20261022,
        sourceStartCycles: Double = 8.0, sourceSeconds: Double = 8.0): Snip =
        probe(voice, macros, midi, velocity, seed, model, acousticProfile, initializer, sourceStartCycles, sourceSeconds).samples

    fun probe(voice: AccordVoice, macros: Map<String, Float> = emptyMap(), midi: Int = 48,
        velocity: Float = .75f, seed: Long = DEFAULT_SEED, model: Int = MODEL_VERSION,
        acousticProfile: AccordAcousticProfile = defaultProfile(voice),
        initializer: AccordInitializer = AccordInitializer.REFERENCE_SINE_20261022,
        sourceStartCycles: Double = 8.0, sourceSeconds: Double = 8.0): Probe {
        val began = System.nanoTime()
        checkCancelled()
        val m = settled(voice, macros)
        require(supportsModel(model)) { "unsupported ACCORD model $model" }
        require(midi in MIN_MIDI..MAX_MIDI) { "ACCORD midi must be $MIN_MIDI..$MAX_MIDI" }
        require(velocity.isFinite() && velocity in 0f..1f) { "ACCORD velocity must be 0..1" }
        require(sourceStartCycles.isFinite() && sourceStartCycles in 0.0..8.0) { "ACCORD sourceStartCycles must be 0..8" }
        require(sourceSeconds.isFinite() && sourceSeconds in MIN_SOURCE_SECONDS..MAX_SOURCE_SECONDS) { "ACCORD sourceSeconds out of range" }
        require(if (voice == AccordVoice.TINE) acousticProfile != AccordAcousticProfile.BASE else acousticProfile == AccordAcousticProfile.BASE) {
            "ACCORD $voice does not support $acousticProfile"
        }
        require(initializer != AccordInitializer.REFERENCE_SINE_20261022 || seed == DEFAULT_SEED) { "ACCORD reference initializer requires reference seed" }
        val key = AudioKey(voice, m, midi, velocity, seed, model, acousticProfile, initializer, sourceStartCycles, sourceSeconds)
        synchronized(cacheLock) {
            audio[key]?.let {
                audioHits++
                return owned(it, it.diagnostics.copy(physicsMilliseconds = 0.0, calibrationMilliseconds = 0.0,
                    audioMilliseconds = 0.0, renderMilliseconds = elapsed(began), audioCacheHit = true))
            }
        }
        val frames = AccordAcoustics.renderFrames(sourceSeconds, acousticProfile)
        if (velocity == 0f) {
            val silence = Snip(FloatArray(frames), 1, Dsp.RATE)
            return Probe(silence, silence.copy(samples = FloatArray(frames)),
                Diagnostics(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                    0, 0, null, null, null, 0.0, 0.0, 0.0, elapsed(began), false, false), emptyList())
        }
        val pace = 2.0.pow(2 * m.getValue("PACE").toDouble())
        val physicsBegan = System.nanoTime()
        val lookup = prepare(TraceKey(parameters(m), seed, initializer, model), sourceStartCycles + pace * sourceSeconds)
        val physicsMs = elapsed(physicsBegan)
        val calibrationBegan = System.nanoTime()
        val gain = calibration(CalibrationKey(voice, acousticProfile, midi, model))
        val calibrationMs = elapsed(calibrationBegan)
        val audioBegan = System.nanoTime()
        val result = condition(voice, acousticProfile, midi, velocity, m, lookup.trace, seed, sourceStartCycles, sourceSeconds, pace)
        val raw = Snip(result.samples, 1, Dsp.RATE)
        val output = FloatArray(raw.samples.size) { (raw.samples[it] * gain).toFloat() }
        require(output.all { it.isFinite() }) { "ACCORD non-finite acoustic output" }
        // The fixed gain is the energy policy. An unexpected peak must remain
        // visible to probes; no per-render normalizer changes soft/hard touch.
        Dsp.fadeTail(output)
        val head = min(output.size, (Dsp.RATE * .003).toInt())
        for (i in 0 until head) output[i] *= i.toFloat() / max(1, head - 1)
        val snip = Snip(output, 1, Dsp.RATE)
        val selected = lookup.trace.events.filter { it.cycle >= sourceStartCycles && it.cycle < sourceStartCycles + pace * sourceSeconds }
        val publicEvents = Collections.unmodifiableList(selected.map { ContactEvent(it.cycle,
            (it.cycle - sourceStartCycles) / pace, it.mechanism, it.direction, it.canonicalSpeed, it.ordinal) })
        val mechanical = lookup.trace.diagnostics
        fun order(cycle: Double, harmonic: Int = 1): Double? = lookup.trace.phaseOrderAt(cycle, harmonic).takeIf { it.isFinite() }
        val diagnostics = Diagnostics(selected.size, result.rawPeak, raw.peak().toDouble(), snip.peak().toDouble(),
            sqrt(output.sumOf { it.toDouble() * it } / output.size), output.sumOf { it.toDouble() } / output.size,
            gain, result.maximumPoleRadius, mechanical.minD, mechanical.relativeEnergyWorkError,
            mechanical.steps, lookup.trace.cycles, order(sourceStartCycles), order(sourceStartCycles + pace * sourceSeconds - 1e-9),
            order(sourceStartCycles + pace * sourceSeconds - 1e-9, 2), physicsMs, calibrationMs, elapsed(audioBegan),
            elapsed(began), lookup.hit, false)
        val probe = Probe(snip, raw, diagnostics, publicEvents)
        checkCancelled()
        val bytes = (snip.samples.size.toLong() + raw.samples.size) * 4 + selected.size * 64L
        synchronized(cacheLock) {
            audio.remove(key)?.let { audioBytes -= audioSize(it) }
            if (bytes <= MAX_AUDIO_BYTES) {
                while (audio.isNotEmpty() && audioBytes + bytes > MAX_AUDIO_BYTES) audioBytes -= audioSize(audio.remove(audio.keys.first())!!)
                audio[key] = owned(probe)
                audioBytes += bytes
            }
        }
        return probe
    }

    private fun audioSize(probe: Probe): Long = (probe.samples.samples.size.toLong() + probe.raw.samples.size) * 4 + probe.events.size * 64L
    private fun owned(probe: Probe, diagnostics: Diagnostics = probe.diagnostics): Probe = probe.copy(
        samples = probe.samples.copy(samples = probe.samples.samples.copyOf()),
        raw = probe.raw.copy(samples = probe.raw.samples.copyOf()), diagnostics = diagnostics)

    /** A long finite sample has LOOP filing, not a claim of seamless playback. */
    fun drumClassFor(patch: AccordPatch): DrumClass {
        settled(patch.voice, patch.macros)
        return if (AccordAcoustics.renderFrames(patch.sourceSeconds, patch.acousticProfile) > (1.5 * Dsp.RATE).toInt())
            DrumClass.LOOP else Classifier.classify(render(patch.copy(velocity = .75f))).drumClass
    }

    fun drumClassFor(voice: AccordVoice, macros: Map<String, Float> = emptyMap(),
        acousticProfile: AccordAcousticProfile = defaultProfile(voice), sourceSeconds: Double = 8.0): DrumClass =
        drumClassFor(AccordPatch("Accord classification", voice, macros, acousticProfile = acousticProfile, sourceSeconds = sourceSeconds))
}
