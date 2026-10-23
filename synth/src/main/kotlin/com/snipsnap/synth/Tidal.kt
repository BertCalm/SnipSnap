package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Two source characters for the finite first-listen proof. */
enum class TidalVoice { PAIR, STREAM }

/**
 * TIDAL: restricted encounter -> persistent cohorts -> allocated harmonic excitation.
 *
 * The 2,048 test particles are controls, never independent notes. Each of 32 original
 * cohorts keeps its harmonic membership and source population. A complex resonator's
 * slowly varying amplitude is integrated exactly at the control rate, then its carrier
 * is synthesized at 4x. Bounded excitation drives those amplitudes while extended
 * material exists; ending the supply leaves a passive modal decay. Gravity supplies
 * geometry, not audio energy. All voicing constants are first-listen hypotheses.
 *
 * This version is finite only. HOLD is retained at zero in recipes and any positive
 * value fails; replaying a complete sample is not a reconstructed held encounter.
 */
object Tidal {
    const val MODEL_VERSION = 2
    const val MIN_MIDI = 36
    const val MAX_MIDI = 84
    const val DEFAULT_MIDI = 60
    const val DEFAULT_SEED = 0L
    private const val CONTROL_RATE = 240
    private const val SOURCE_GAIN = .22
    private const val ROOT_GAIN = .115
    private const val TAIL_SECONDS = 1.8
    private const val TWO_PI = 2.0 * PI
    private const val TABLE_SIZE = 16_384
    private val sine = DoubleArray(TABLE_SIZE + 1) { sin(TWO_PI * it / TABLE_SIZE) }
    // Both source models use the same solver. Keys contain every preparation input;
    // note, velocity, source model and pickup do not affect restricted trajectories.
    private val preparationCache = object : LinkedHashMap<TidalDynamics.Settings, TidalDynamics.Prepared>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TidalDynamics.Settings, TidalDynamics.Prepared>?): Boolean = size > 8
    }
    private fun preparation(settings: TidalDynamics.Settings, cancelled: () -> Boolean): TidalDynamics.Prepared {
        checkCancelled(cancelled)
        synchronized(preparationCache) { preparationCache[settings] }?.let { return it }
        val prepared = TidalDynamics.prepare(settings, cancelled)
        checkCancelled(cancelled)
        synchronized(preparationCache) { preparationCache[settings] = prepared }
        return prepared
    }

    fun supportsModel(model: Int): Boolean = model == 1 || model == 2
    fun rootMidi(@Suppress("UNUSED_PARAMETER") voice: TidalVoice): Int = DEFAULT_MIDI
    fun macrosFor(voice: TidalVoice, model: Int = MODEL_VERSION): List<MacroSpec> {
        require(supportsModel(model)) { "unsupported TIDAL model $model" }
        val a = if (voice == TidalVoice.PAIR && model == 1) floatArrayOf(.35f, .35f, .20f, .65f, .25f)
            else if (voice == TidalVoice.PAIR) floatArrayOf(.35f, .78f, .20f, .42f, .45f)
            else floatArrayOf(.45f, .70f, .20f, .30f, .80f)
        return listOf(MacroSpec("APPROACH", a[0], neutral = .45f),
            MacroSpec("PASSAGE", a[1], neutral = .55f), MacroSpec("TILT", a[2], neutral = .35f),
            MacroSpec("BIND", a[3], neutral = .50f), MacroSpec("TAIL", a[4], neutral = .45f),
            MacroSpec("HOLD", 0f, neutral = 0f))
    }
    fun defaults(voice: TidalVoice, model: Int = MODEL_VERSION): Map<String, Float> = macrosFor(voice, model).associate { it.name to it.default }
    fun settled(voice: TidalVoice, macros: Map<String, Float>, model: Int = MODEL_VERSION): Map<String, Float> {
        val base = defaults(voice, model)
        require(macros.keys.all { it in base }) { "unknown TIDAL macro" }
        require(macros.values.all { it.isFinite() && it in 0f..1f }) { "TIDAL macros must be finite and in 0..1" }
        val result = base + macros
        require(result.getValue("HOLD") == 0f) { "TIDAL finite models require HOLD zero" }
        return result
    }
    fun drumClassFor(voice: TidalVoice, macros: Map<String, Float> = emptyMap()): DrumClass {
        settled(voice, macros)
        // Long-sample filing metadata; this buffer is a finite one-shot, not a sustain loop.
        return DrumClass.LOOP
    }
    fun scramble(voice: TidalVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice, (near as? TidalPatch)?.model ?: MODEL_VERSION)
        return Dsp.scrambleNear(base + near?.macros.orEmpty().filterKeys { it in base },
            temperature.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .35f, random) + ("HOLD" to 0f)
    }

    internal data class Options(
        val noTidalGradient: Boolean = false,
        val fixedTailEnvelope: Boolean = false,
        val singleDescriptor: Boolean = false,
        val reassignedIdentity: Boolean = false,
        val normalize: Boolean = false,
        val oversample: Int = Dsp.OVERSAMPLE,
        val particleCount: Int = 2_048,
        val step: Double = .005,
    )
    internal data class Diagnostics(
        val rawPeak: Double, val rawRms: Double, val rawDc: Double,
        val tailRms: Double, val coreRms: Double, val pitchHz: Double,
        val durationSeconds: Double, val maxCents: Double,
        val preparationMillis: Double, val synthesisMillis: Double,
        val sourceCutoffSeconds: Double, val maxSourceAllocation: Double,
        val maxModalAmplitude: Double, val maxFrequencyHz: Double,
    )
    internal data class TraceFrame(val timeSeconds: Double, val extension: List<Double>,
        val coreAllocation: Double, val tailAllocation: Double, val modeAmplitudes: List<Double>)
    internal data class Probe(val samples: FloatArray, val rawMix: FloatArray, val core: FloatArray,
        val tail: FloatArray, val populationA: FloatArray, val populationB: FloatArray,
        val dynamics: TidalDynamics.Prepared, val trace: List<TraceFrame>, val diagnostics: Diagnostics)

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("TIDAL render cancelled")
    }
    private fun smooth(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
    private fun guard(hz: Double): Double = 1 - smooth((hz - 12_000) / 6_000)
    private fun osc(phase: Double): Double {
        val x = (phase - kotlin.math.floor(phase)) * TABLE_SIZE
        val i = x.toInt().coerceIn(0, TABLE_SIZE - 1)
        return sine[i] + (sine[i + 1] - sine[i]) * (x - i)
    }
    private fun rms(a: FloatArray): Double = sqrt(a.sumOf { it.toDouble() * it } / a.size.coerceAtLeast(1))
    private data class Node(val cohortAmplitude: DoubleArray, val cohortHz: DoubleArray,
        val modalAmplitude: DoubleArray, val modalHz: DoubleArray, val rootAmplitude: Double,
        val extension: DoubleArray, val coreAllocation: Double, val tailAllocation: Double,
        val contactPhase: DoubleArray = DoubleArray(0))

    internal fun probe(
        voice: TidalVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = .75f, seed: Long = DEFAULT_SEED, model: Int = MODEL_VERSION,
        options: Options = Options(), cancelled: () -> Boolean = { false },
    ): Probe {
        require(supportsModel(model)) { "unsupported TIDAL model $model" }
        require(midi in MIN_MIDI..MAX_MIDI) { "TIDAL MIDI must be $MIN_MIDI..$MAX_MIDI" }
        require(velocity.isFinite() && velocity in 0f..1f) { "TIDAL velocity must be finite and in 0..1" }
        require(options.oversample in listOf(1, 4, 8)) { "TIDAL oversample must be 1, 4 or 8" }
        val m = settled(voice, macros, model)
        val textured = model == 2
        val modesPerPopulation = if (textured) 16 else 8
        val modes = modesPerPopulation * 2
        checkCancelled(cancelled)
        val prepStart = System.nanoTime()
        val prepared = preparation(TidalDynamics.Settings(
            approach = m.getValue("APPROACH").toDouble(), passage = m.getValue("PASSAGE").toDouble(),
            tilt = m.getValue("TILT").toDouble(), bind = m.getValue("BIND").toDouble(), seed = seed,
            particleCount = options.particleCount, step = options.step, noTidalGradient = options.noTidalGradient,
        ), cancelled)
        val prepMillis = (System.nanoTime() - prepStart) / 1e6
        val synthStart = System.nanoTime()
        val hz = Keys.midiHz(midi).toDouble()
        val v = velocity.toDouble()
        val count = prepared.frames.first().cohorts.size
        require(count == 32) { "TIDAL requires 32 persistent cohorts" }
        val supplyEnd = prepared.durationSeconds
        val totalSeconds = supplyEnd + TAIL_SECONDS
        val intervals = ceil(totalSeconds * CONTROL_RATE).toInt()
        val dt = 1.0 / CONTROL_RATE
        val tailMacro = m.getValue("TAIL").toDouble()
        val corePickup = 1.0 - .82 * tailMacro
        val tailPickup = .20 + 1.08 * tailMacro
        val effort = v.pow(.85)
        val harmonics = IntArray(count) { 1 + (it % 16) % modesPerPopulation }
        // These ratios belong to the original material, so stretched metal remains metal
        // when displaced. Its first two partials retain an exact musical root anchor.
        fun ratio(population: Int, n: Int): Double = if (!textured || population == 0 || n <= 2) n.toDouble()
            else n * sqrt(1 + .0014 * (n * n - 4))
        val profiles = Array(2) { p -> DoubleArray(count) { i ->
            val n = harmonics[i].toDouble()
            val stream = voice == TidalVoice.STREAM
            if (textured) {
                if (p == 0) {
                    val odd = if (n.toInt() % 2 == 1) 1.0 else .46
                    val formant = 1 + 1.8 * exp(-((n * hz - 1_650) / 850).pow(2))
                    odd * formant / n.pow(.82)
                } else {
                    val cluster = 1 + 1.35 * exp(-((n - 5.5) / 3.2).pow(2))
                    (if (n == 1.0) .70 else 1.0) * cluster / n.pow(.62)
                }
            } else if (p == 0) 1 / n.pow(if (stream) 1.25 else 1.45)
            else (if (n == 1.0) .62 else 1.18) / n.pow(if (stream) .80 else .92)
        } }
        // Fixed note-specific budget over eligible carriers, never a tail-branch normalizer.
        val profileSum = DoubleArray(2) { p -> prepared.frames.first().cohorts.indices.sumOf { i ->
            val c = prepared.frames.first().cohorts[i]
            if (c.population == p) c.weight * profiles[p][i] * guard(ratio(p, harmonics[i]) * hz * 2.0.pow(15.0 / 1200)) else 0.0
        }.coerceAtLeast(1e-20) }
        val modeState = DoubleArray(modes)
        val contactRandom = Random(Dsp.seedFor("TIDAL-CONTACT", model, seed.toString(), midi))
        val contactState = DoubleArray(count)
        val contactPhase = DoubleArray(count)
        val nodes = ArrayList<Node>(intervals + 1)
        var frameIndex = 0
        var maxCents = 0.0
        var maxAllocation = 0.0
        var maxModal = 0.0
        var maxFrequency = hz
        val fixedPeak = prepared.frames.maxOf { f -> f.cohorts.sumOf { it.weight * it.extension } }
        for (k in 0..intervals) {
            if (k % 32 == 0) checkCancelled(cancelled)
            val t = k * dt
            while (frameIndex + 1 < prepared.frames.lastIndex && prepared.frames[frameIndex + 1].timeSeconds < t) frameIndex++
            val left = prepared.frames[frameIndex]
            val right = prepared.frames[min(frameIndex + 1, prepared.frames.lastIndex)]
            val mix = if (right.timeSeconds > left.timeSeconds)
                ((t - left.timeSeconds) / (right.timeSeconds - left.timeSeconds)).coerceIn(0.0, 1.0) else 0.0
            fun blend(i: Int, read: (TidalDynamics.Cohort) -> Double): Double =
                read(left.cohorts[i]) * (1 - mix) + read(right.cohorts[i]) * mix
            val ext = DoubleArray(count) { blend(it) { c -> c.extension } }
            val concentration = DoubleArray(count) { blend(it) { c -> c.concentration } }
            val motion = DoubleArray(count) { blend(it) { c -> c.motion } }
            val stretch = DoubleArray(count) { blend(it) { c -> c.stretch } }
            val affinity = DoubleArray(count) { blend(it) { c -> c.affinity } }
            if (options.singleDescriptor) {
                for (a in listOf(ext, concentration, motion, stretch, affinity)) {
                    val average = a.indices.sumOf { a[it] * left.cohorts[it].weight }
                    a.fill(average)
                }
            }
            if (options.fixedTailEnvelope) {
                val since = t - prepared.diagnostics.encounterTimeSeconds
                ext.fill(if (since < 0) 0.0 else fixedPeak * smooth(since / .12) * exp(-since / 2.1))
            }
            val encounter = (left.encounter * (1 - mix) + right.encounter * mix).coerceIn(0.0, 1.0)
            val supply = smooth(t / (.035 + .055 * (1 - v))) *
                (1 - smooth((t - (supplyEnd - 1.1)) / 1.1)) * effort
            val amplitude = DoubleArray(count)
            val frequencies = DoubleArray(count)
            val modalDrive = DoubleArray(modes)
            val modalMotion = DoubleArray(modes)
            var coreAllocation = 0.0
            var tailAllocation = 0.0
            for (i in 0 until count) {
                val c = left.cohorts[i]
                val original = c.population
                val reassigned = if (options.reassignedIdentity) smooth((.65 - affinity[i]) / .30) else 0.0
                val shape = (profiles[original][i] * (1 - reassigned) + profiles[1 - original][i] * reassigned) /
                    profileSum[original]
                val n = harmonics[i]
                val cents = if (n == 1) 0.0 else (motion[i] - .5) * 2 * (3 + 9 * stretch[i])
                maxCents = max(maxCents, abs(cents))
                frequencies[i] = ratio(original, n) * hz * 2.0.pow(cents / 1200)
                maxFrequency = max(maxFrequency, frequencies[i])
                val extension = smooth((ext[i] - .025) / .32)
                val tailShare = .85 * extension
                val coreShare = 1 - tailShare
                coreAllocation += c.weight * coreShare
                tailAllocation += c.weight * tailShare
                val brightness = if (textured) 1 + .55 * encounter * (n - 1) / 15.0
                    else 1 + (.10 + .30 * m.getValue("APPROACH")) * encounter * (n - 1) / 7.0
                // A dragged string sustains a contact texture; metal starts with a hard
                // partial-dependent strike and is re-excited as the encounter compresses it.
                // The clocked random contact is interpolated below, not fresh audio-rate
                // white noise, so the same excitation survives 4x/8x reference rendering.
                val excitation = if (!textured) 1.0 else if (original == 0) {
                    val bow = smooth(t / (.10 + .08 * (1 - v)))
                    val grain = .82 + .18 * osc(t * (1.7 + .11 * n) + .13 * i)
                    bow * (.74 + .35 * exp(-t / (.18 + 1.2 / n)) + .55 * encounter * concentration[i]) * grain
                } else {
                    .22 + .78 * exp(-t / (.12 + 1.25 / sqrt(n.toDouble()))) +
                        .80 * encounter * (.35 + .65 * concentration[i])
                }
                if (textured && t <= supplyEnd) {
                    contactState[i] = .58 * contactState[i] + .42 * (contactRandom.nextDouble() * 2 - 1)
                    val rubbing = (if (original == 0) .24 else .10 + .35 * exp(-t / .09)) * sqrt((n - 1) / 15.0)
                    contactPhase[i] = contactState[i] * rubbing * (.35 + .65 * motion[i]) *
                        (.40 + .60 * encounter) * min(1.0, excitation)
                }
                val sourceSupply = if (textured && original == 1) {
                    smooth(t / (.004 + .012 * (1 - v))) *
                        (1 - smooth((t - (supplyEnd - 1.1)) / 1.1)) * effort
                } else supply
                val a = (if (textured) .30 else SOURCE_GAIN) * .5 * c.weight * shape * brightness * excitation * sourceSupply * guard(frequencies[i])
                amplitude[i] = a * coreShare * (.72 + .28 * concentration[i]) * corePickup
                val mode = original * modesPerPopulation + n - 1
                modalDrive[mode] += a * tailShare * (1 - .3 * reassigned)
                modalDrive[(1 - original) * modesPerPopulation + n - 1] += a * tailShare * .3 * reassigned
                modalMotion[mode] += cents * c.weight * tailShare
            }
            maxAllocation = max(maxAllocation, coreAllocation + tailAllocation)
            val modalHz = DoubleArray(modes) { j ->
                val n = j % modesPerPopulation + 1
                // Fundamental stays exactly anchored. Other modes retain restrained motion.
                val cents = if (n == 1) 0.0 else modalMotion[j].coerceIn(-3.0, 3.0)
                ratio(j / modesPerPopulation, n) * hz * 2.0.pow(cents / 1200)
            }
            val modalAmp = DoubleArray(modes)
            for (j in 0 until modes) {
                val n = j % modesPerPopulation + 1
                val t60 = (.90 + 1.35 * tailMacro) / (1 + .065 * (n - 1))
                val decay = exp(-kotlin.math.ln(1000.0) * dt / t60)
                // Exact positive envelope update of a driven complex damped mode.
                modeState[j] = modeState[j] * decay + modalDrive[j] * (1 - decay)
                modalAmp[j] = modeState[j] * tailPickup * guard(modalHz[j])
                maxModal = max(maxModal, modeState[j])
            }
            nodes += Node(amplitude, frequencies, modalAmp, modalHz,
                (if (textured) .045 else ROOT_GAIN) * (if (voice == TidalVoice.STREAM) .83 else 1.0) * supply * corePickup * (1 - .20 * tailAllocation), ext,
                coreAllocation, tailAllocation, if (textured) contactPhase.copyOf() else DoubleArray(0))
        }

        val frames = (totalSeconds * Dsp.RATE).toInt()
        val rate = Dsp.RATE * options.oversample
        val raw = Array(4) { FloatArray(frames * options.oversample) } // core A/B, tail A/B
        val phases = Dsp.phases(count + modes + 2, Dsp.seedFor("TIDAL", model, seed.toString(), midi))
        val coherentPhases = phases.copyOfRange(0, 16)
        // Initial same-harmonic cohorts fuse. Their phase integrates independently thereafter.
        if (!textured) for (i in 0 until count) phases[i] = coherentPhases[(i / 16) * 8 + harmonics[i] - 1]
        for (i in 0 until count) phases[i] *= .20
        val rootPhase = phases.last()
        for (sample in raw[0].indices) {
            if (sample % 8192 == 0) checkCancelled(cancelled)
            val position = sample.toDouble() / rate * CONTROL_RATE
            val k = position.toInt().coerceAtMost(nodes.lastIndex - 1)
            val frac = (position - k).coerceIn(0.0, 1.0)
            val a = nodes[k]; val b = nodes[k + 1]
            var coreA = 0.0; var coreB = 0.0; var tailA = 0.0; var tailB = 0.0
            for (i in 0 until count) {
                val amp = a.cohortAmplitude[i] * (1 - frac) + b.cohortAmplitude[i] * frac
                val f = a.cohortHz[i] * (1 - frac) + b.cohortHz[i] * frac
                val contact = if (textured) a.contactPhase[i] * (1 - frac) + b.contactPhase[i] * frac else 0.0
                val x = amp * osc(phases[i] + contact)
                phases[i] += f / rate
                if (i < 16) coreA += x else coreB += x
            }
            val root = (a.rootAmplitude * (1 - frac) + b.rootAmplitude * frac) * osc(rootPhase + hz * sample / rate)
            coreA += .54 * root; coreB += .46 * root
            for (j in 0 until modes) {
                val amp = a.modalAmplitude[j] * (1 - frac) + b.modalAmplitude[j] * frac
                val f = a.modalHz[j] * (1 - frac) + b.modalHz[j] * frac
                val p = count + j
                // Contact belongs to the displaced material too. Once supply ends the
                // stored contact phase freezes, leaving an unforced decaying carrier.
                val contact = if (textured) .60 * (a.contactPhase[j] * (1 - frac) + b.contactPhase[j] * frac) else 0.0
                val x = amp * osc(phases[p] + contact)
                phases[p] += f / rate
                if (j < modesPerPopulation) tailA += x else tailB += x
            }
            raw[0][sample] = coreA.toFloat(); raw[1][sample] = coreB.toFloat()
            raw[2][sample] = tailA.toFloat(); raw[3][sample] = tailB.toFloat()
        }
        val down = raw.map { buffer ->
            checkCancelled(cancelled)
            Tide.bandLimit(buffer, rate)
            checkCancelled(cancelled)
            val output = when (options.oversample) {
                1 -> buffer
                4 -> Dsp.decimate(buffer, Dsp.RATE)
                else -> {
                    // Dsp.decimate assumes exactly 4x. Preserve the actual 8x source rate.
                    val four = Resampler.resample(Snip(buffer, 1, rate), Dsp.RATE * 4)
                    Dsp.decimate(four.samples, Dsp.RATE)
                }
            }
            val dc = Dsp.OnePole(Dsp.RATE)
            for (i in output.indices) output[i] -= dc.lp(output[i], 8f)
            // A short final guard follows a genuine 1.8-second passive decay.
            Dsp.fadeTail(output)
            output
        }
        val core = FloatArray(frames) { down[0][it] + down[1][it] }
        val tail = FloatArray(frames) { down[2][it] + down[3][it] }
        val popA = FloatArray(frames) { down[0][it] + down[2][it] }
        val popB = FloatArray(frames) { down[1][it] + down[3][it] }
        val mix = FloatArray(frames) { core[it] + tail[it] }
        check(mix.all { it.isFinite() }) { "TIDAL non-finite audio" }
        val peak = mix.maxOf { abs(it.toDouble()) }
        check(peak < .96) { "TIDAL raw coherent-sum budget exceeded: $peak" }
        val out = mix.copyOf()
        checkCancelled(cancelled)
        if (options.normalize && v > 0) Dsp.levelTo(out, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET * velocity.pow(.65f))
        val trace = nodes.indices.filter { it % 8 == 0 || it == nodes.lastIndex }.map { k ->
            TraceFrame(k * dt, nodes[k].extension.toList(), nodes[k].coreAllocation,
                nodes[k].tailAllocation, nodes[k].modalAmplitude.toList())
        }
        val diagnostics = Diagnostics(peak, rms(mix), mix.sumOf { it.toDouble() } / mix.size,
            rms(tail), rms(core), hz, frames.toDouble() / Dsp.RATE, maxCents, prepMillis,
            (System.nanoTime() - synthStart) / 1e6, supplyEnd, maxAllocation, maxModal, maxFrequency)
        return Probe(out, mix, core, tail, popA, popB, prepared, trace, diagnostics)
    }

    fun render(voice: TidalVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = .75f, seed: Long = DEFAULT_SEED, model: Int = MODEL_VERSION): Snip =
        Snip(probe(voice, macros, midi, velocity, seed, model, Options(normalize = true)).samples,
            channels = 1, sampleRate = Dsp.RATE)
}
