package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class MeridianVoice { SWEEP }

/**
 * A maintained precessing swing touches twelve independently
 * ringing surfaces. Geometric contact supplies new work, while old modal states
 * continue through the same fixed pickups. It is not a rotating output mix.
 *
 * The motion is an accelerated musical analogy, not an Earth-rate solver or a
 * closed mechanical collision system. Modes.Bank supplies exact damped rotations
 * with no springs or acoustic feedback, so the modal frequencies never follow the
 * swing. Wood, wire, glass and hollow metal are designed identities, not measured
 * material models. Their tables are first-listen hypotheses from the Python proof.
 *
 * Model 1 retains the original two-to-four-second finite gesture at every HOLD
 * setting. Model 2 keeps that path below the top step and adds a fixed-alignment
 * held swing at HOLD >= .975. The held path closes excitation and modal state;
 * it does not claim to repeat the full precessing material traversal.
 */
object Meridian {
    const val RATE = Dsp.RATE
    const val MODEL_VERSION = 2
    // Model one recipes keep their original acoustic-noise identity when a
    // later model adds a held adaptation or a different rendering contract.
    internal const val LEGACY_MODEL_VERSION = 1
    const val DEFAULT_MIDI = 57
    const val MIDI_MIN = 36
    const val MIDI_MAX = 96
    const val TUNE_SEMITONES = 24
    const val SURFACES = 12
    const val MODES_PER_SURFACE = 4
    private const val INTERNAL_RATE = RATE * Dsp.OVERSAMPLE
    private const val PICKUP = .16
    private const val HASH_START = -3750763034362895579L
    private const val HASH_MULTIPLIER = 1099511628211L
    private const val OUTPUT_DC_HZ = 18f

    internal enum class Material { WOOD, WIRE, GLASS, METAL }
    private data class MaterialShape(val ratios: DoubleArray, val gains: DoubleArray,
        val decay: DoubleArray, val noiseHz: Double, val catchSoftness: Double)

    private val shapes = arrayOf(
        MaterialShape(doubleArrayOf(1.0, 2.0, 3.98, 6.10), doubleArrayOf(.90, .55, .20, .05),
            doubleArrayOf(1.0, .60, .25, .12), 900.0, 1.30),
        MaterialShape(doubleArrayOf(1.0, 2.01, 3.02, 5.06), doubleArrayOf(.70, .55, .40, .20),
            doubleArrayOf(1.10, .90, .65, .40), 2000.0, .90),
        MaterialShape(doubleArrayOf(1.0, 2.70, 4.10, 6.60), doubleArrayOf(.65, .25, .24, .12),
            doubleArrayOf(1.0, .80, .60, .35), 3400.0, .68),
        MaterialShape(doubleArrayOf(1.0, 1.98, 3.19, 4.77), doubleArrayOf(.85, .42, .26, .13),
            doubleArrayOf(1.0, .70, .45, .30), 1500.0, 1.05),
    )
    // No repeated four-material rotational tile; opposite positions also differ.
    private val materialRing = arrayOf(Material.WOOD, Material.WIRE, Material.GLASS, Material.METAL,
        Material.WOOD, Material.GLASS, Material.WIRE, Material.METAL, Material.WOOD,
        Material.GLASS, Material.METAL, Material.WIRE)
    internal fun materialFor(surface: Int): Material = materialRing[surface]

    fun macrosFor(@Suppress("UNUSED_PARAMETER") voice: MeridianVoice): List<MacroSpec> = listOf(
        MacroSpec("TUNE", .5f, .5f), MacroSpec("LATITUDE", .80f, .80f),
        MacroSpec("LENGTH", .45f, .45f), MacroSpec("REACH", .60f, .60f),
        MacroSpec("SURFACE", .55f, .55f), MacroSpec("MEMORY", .40f, .40f),
        MacroSpec("HOLD", 0f, 0f))

    fun defaults(voice: MeridianVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    internal fun settled(voice: MeridianVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).mapValues { (key, default) ->
            macros[key]?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: default
        }

    fun scramble(voice: MeridianVoice, random: Random, temperature: Float = .35f,
        near: Patch? = null): Map<String, Float> = Dsp.scrambleNear(
        settled(voice, if (near?.engine == "MERIDIAN") near.macros else emptyMap()),
        if (temperature.isFinite()) temperature else .35f, random)

    fun midiFor(@Suppress("UNUSED_PARAMETER") voice: MeridianVoice, tune: Float, midi: Int = DEFAULT_MIDI): Int {
        require(midi in MIDI_MIN..MIDI_MAX) { "MERIDIAN midi out of $MIDI_MIN..$MIDI_MAX: $midi" }
        val t = if (tune.isFinite()) tune.coerceIn(0f, 1f) else .5f
        return midi + ((t - .5f) * TUNE_SEMITONES).roundToInt()
    }

    fun frequencyFor(voice: MeridianVoice, tune: Float, midi: Int = DEFAULT_MIDI): Float =
        Keys.midiHz(midiFor(voice, tune, midi))

    fun supportsModel(model: Int): Boolean = model in LEGACY_MODEL_VERSION..MODEL_VERSION

    fun isLoop(macros: Map<String, Float>, model: Int = MODEL_VERSION): Boolean {
        require(supportsModel(model)) { "unsupported meridian model $model" }
        return model >= 2 && (macros["HOLD"]?.takeIf { it.isFinite() } ?: 0f) >= .975f
    }

    /** A held result files as LOOP; finite model-1 filing is retained. */
    fun drumClassFor(@Suppress("UNUSED_PARAMETER") voice: MeridianVoice,
        macros: Map<String, Float> = emptyMap(), model: Int = MODEL_VERSION): DrumClass =
        if (isLoop(macros, model)) DrumClass.LOOP else DrumClass.TONAL

    data class HeldResult(
        val snip: Snip,
        val loopStartFrame: Int,
        val loopEndFrameExclusive: Int,
        val seamError: Double,
        val cycleError: Double,
        val stateError: Double,
        val loopFrames: Int,
        val swings: Int,
        val nominalSwingPeriodSeconds: Double,
        val effectiveSwingPeriodSeconds: Double,
        val rootHz: Double,
        val rootGridHz: Double,
        val rootCents: Double,
        val fixedOrientation: Double,
        val activeSurfaces: Int,
        val rawPeak: Double,
        val peakEnergy: Double,
        val inputHashes: LongArray,
        val roughnessHashes: LongArray,
        val noiseStateError: Double,
    )

    /** Two independently evolved held cycles. Only the second cycle is marked
     * for sampler repetition; the first has a short entry fade. The errors are
     * measured before that fade against the real preceding continuation. */
    fun renderHeld(voice: MeridianVoice, macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI, velocity: Float = 1f, seedContext: Int = 0): HeldResult {
        require(midi in MIDI_MIN..MIDI_MAX) { "MERIDIAN midi out of $MIDI_MIN..$MIDI_MAX: $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "MERIDIAN velocity must be in 0..1: $velocity" }
        stopIfUnwanted()
        val held = MeridianHeld.render(voice, settled(voice, macros), midi, velocity, seedContext)
        return HeldResult(held.snip, held.loopStartFrame, held.loopEndFrameExclusive,
            held.seamError, held.cycleError, held.stateError, held.loopFrames, held.swings,
            held.nominalSwingPeriodSeconds, held.effectiveSwingPeriodSeconds,
            held.rootHz, held.rootGridHz, held.rootCents, held.fixedOrientation,
            held.activeSurfaces, held.rawPeak, held.peakEnergy,
            held.inputHashes, held.roughnessHashes, held.noiseStateError)
    }

    internal fun activeSeconds(hold: Float): Double = 2.0 + 2.0 * hold.coerceIn(0f, 1f)
    internal fun baseT60(memory: Float): Double = .15 * 40.0.pow(memory.coerceIn(0f, 1f).toDouble())
    internal fun tailSeconds(memory: Float): Double = (.55 + .60 * baseT60(memory)).coerceAtMost(3.5)

    internal data class Diagnostics(
        val rawPeak: Double, val peakEnergy: Double, val rootHz: Double, val rootModalHz: DoubleArray,
        val activeSeconds: Double, val tailSeconds: Double, val geometryCacheHit: Boolean,
        val geometryPrepareNanos: Long, val perSurfaceInputEnergy: DoubleArray,
        val perSurfaceInputPeak: DoubleArray, val perSurfaceOutputEnergy: DoubleArray,
        val inputHashes: LongArray, val roughnessHashes: LongArray,
        val triggerCounts: IntArray, val maxContactWeightSum: Double,
    )

    internal data class Probe(val samples: FloatArray, val surfaceOutputs: Array<FloatArray>,
        val diagnostics: Diagnostics, val motion: MeridianMotion.Plan)

    internal data class SurfaceSetup(val frequencies: DoubleArray, val t60s: DoubleArray,
        val participation: DoubleArray, val pickup: DoubleArray,
        val noiseAlpha: Double, val noiseScale: Double, val pulse: DoubleArray) {
        fun newBank(released: Boolean = false): Modes.Bank = Modes.Bank(MODES_PER_SURFACE, INTERNAL_RATE).also { bank ->
            for (k in 0 until MODES_PER_SURFACE)
                bank.tune(k, frequencies[k], if (released) minOf(t60s[k], .08) else t60s[k])
        }
    }

    internal fun setups(hz: Double, surface: Double, memory: Float, reach: Double, velocity: Double): Array<SurfaceSetup> {
        val restrained = doubleArrayOf(.85, .35, .13, .05)
        val base = baseT60(memory)
        return Array(SURFACES) { j ->
            val shape = shapes[materialRing[j].ordinal]
            val frequencies = DoubleArray(MODES_PER_SURFACE) { k -> hz * ((k + 1.0) + surface * (shape.ratios[k] - (k + 1.0))) }
            val t60s = DoubleArray(MODES_PER_SURFACE) { k -> base * (1 + surface * (shape.decay[k] - 1)) }
            val gain = DoubleArray(MODES_PER_SURFACE) { k ->
                val upperFade = 1 - MeridianMotion.smooth((frequencies[k] - 14000) / 4000)
                (restrained[k] + surface * (shape.gains[k] - restrained[k])) *
                    (1 + .045 * sin((j + 1) * 1.7 + k)) * upperFade
            }
            // Drive and pickup share the participation square root, so the net
            // modal weight is gain. The constant pickup never follows the rotor.
            val participation = DoubleArray(MODES_PER_SURFACE) { sqrt(gain[it]) }
            val pickup = DoubleArray(MODES_PER_SURFACE) { PICKUP * participation[it] }
            val cutoff = (1400 + surface * (shape.noiseHz - 1400)) * (.65 + .35 * reach) * (.65 + .35 * velocity)
            val alpha = 1 - exp(-2 * PI * cutoff / INTERNAL_RATE)
            val q = 1 - alpha
            // Exact stationary variance of two identical one-poles driven by
            // uniform noise (variance 1/3); hardness changes color, not random RMS.
            val noiseScale = sqrt(3 * (1 + q).pow(3) / (alpha * (1 + q * q)))
            val soft = 1 + surface * (shape.catchSoftness - 1)
            val width = maxOf(8, ((.0035 - .002 * reach) * soft * (1.15 - .3 * velocity) * INTERNAL_RATE).roundToInt())
            val pulse = DoubleArray(width + 1) { i -> sin(PI * i / width).pow(2) }
            val sum = pulse.sum()
            for (i in pulse.indices) pulse[i] /= sum
            SurfaceSetup(frequencies, t60s, participation, pickup, alpha, noiseScale, pulse)
        }
    }

    /** Raw conditioned mono at RATE, before shared leveling unless [normalize].
     * [activeSeconds]/[tailSeconds] select bounded audition windows, not a second
     * sound design. [fixedAlignment] and [resetOnContact] are causal ablations.
     * Taps are optional because twelve extra decimations are diagnostic work.
     * Hashes cover active input samples only, so MEMORY-dependent tail length
     * cannot masquerade as reseeded roughness. Energy peaks are sampled every
     * 256 internal samples; raw output peak covers every sample. */
    internal fun renderProbe(voice: MeridianVoice, macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI, velocity: Float = 1f, seedContext: Int = 0,
        activeSeconds: Float? = null, tailSeconds: Float? = null,
        normalize: Boolean = false, taps: Boolean = false, fixedAlignment: Boolean = false,
        pMax: Double = MeridianMotion.MAX_PRECESSION, resetOnContact: Boolean = false): Probe =
        pass(voice, macros, midi, velocity, seedContext, activeSeconds, tailSeconds,
            normalize, taps, fixedAlignment, pMax, resetOnContact, diagnostics = true)

    private fun pass(voice: MeridianVoice, macros: Map<String, Float>, midi: Int, velocity: Float,
        seedContext: Int, activeOverride: Float?, tailOverride: Float?, normalize: Boolean,
        taps: Boolean, fixedAlignment: Boolean, pMax: Double, resetOnContact: Boolean,
        diagnostics: Boolean): Probe {
        require(midi in MIDI_MIN..MIDI_MAX) { "MERIDIAN midi out of $MIDI_MIN..$MIDI_MAX: $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "MERIDIAN velocity must be in 0..1: $velocity" }
        require(activeOverride == null || activeOverride.isFinite() && activeOverride in .05f..12f)
        require(tailOverride == null || tailOverride.isFinite() && tailOverride in .15f..4f)
        stopIfUnwanted()
        val m = settled(voice, macros)
        val active = activeOverride?.toDouble() ?: activeSeconds(m.getValue("HOLD"))
        val tail = tailOverride?.toDouble() ?: tailSeconds(m.getValue("MEMORY"))
        val prepared = MeridianMotion.prepare(m.getValue("LATITUDE").toDouble(), m.getValue("LENGTH").toDouble(),
            m.getValue("REACH").toDouble(), active, fixedAlignment = fixedAlignment, pMax = pMax)
        val motion = prepared.plan
        val hz = frequencyFor(voice, m.getValue("TUNE"), midi).toDouble()
        val reach = m.getValue("REACH").toDouble()
        val surface = m.getValue("SURFACE").toDouble()
        val configs = setups(hz, surface, m.getValue("MEMORY"), reach, velocity.toDouble())
        if (!diagnostics && !taps && !resetOnContact) {
            val raw = MeridianFinite.raw(configs, motion, active, tail, reach, velocity, voice, seedContext)
            // The public path needs only audio. Keep the diagnostic path below
            // independent so its taps and energy probes also check the faster
            // surface-at-a-time traversal against the original sample order.
            val empty = Diagnostics(0.0, 0.0, hz,
                DoubleArray(SURFACES * MODES_PER_SURFACE) { configs[it / MODES_PER_SURFACE].frequencies[it % MODES_PER_SURFACE] },
                active, tail, prepared.cacheHit, prepared.prepareNanos,
                DoubleArray(SURFACES), DoubleArray(SURFACES), DoubleArray(SURFACES),
                LongArray(SURFACES) { HASH_START }, LongArray(SURFACES) { HASH_START },
                IntArray(SURFACES) { motion.entries[it].size }, motion.maxWeightSum)
            return Probe(finish(raw, normalize), emptyArray(), empty, motion)
        }
        val banks = Array(SURFACES) { configs[it].newBank() }
        val count = ((active + tail) * RATE).roundToInt() * Dsp.OVERSAMPLE
        val activeFrames = ceil(active * INTERNAL_RATE).toInt()
        val raw = FloatArray(count)
        val rawTaps = if (taps) Array(SURFACES) { FloatArray(count) } else emptyArray()
        val noise = Array(SURFACES) { j -> Dsp.Noise(Dsp.seedFor("MERIDIAN", LEGACY_MODEL_VERSION, voice.name, seedContext, j)) }
        val noise1 = DoubleArray(SURFACES)
        val noise2 = DoubleArray(SURFACES)
        val nextEvent = IntArray(SURFACES)
        val didReset = BooleanArray(SURFACES)
        // The cached motion exposes unmodifiable event lists. Flatten their
        // immutable contents once: consulting the wrappers' size/get methods
        // twice per surface and sample dominated the first JVM profile.
        val eventFrames = Array(SURFACES) { j ->
            DoubleArray(motion.entries[j].size) { motion.entries[j][it].seconds * INTERNAL_RATE }
        }
        val eventStrengths = Array(SURFACES) { j ->
            DoubleArray(motion.entries[j].size) { motion.entries[j][it].strength }
        }
        val eventCounts = IntArray(SURFACES) { eventFrames[it].size }
        // A never-contacted surface stays exactly at zero. The public path
        // needs neither its unused roughness nor its zero modal rotations;
        // probes still advance every noise stream for the seed diagnostics.
        val renderedSurfaces = if (diagnostics) IntArray(SURFACES) { it } else {
            (0 until SURFACES).filter { j ->
                eventCounts[j] > 0 || (0 until motion.frameCount).any { motion.weight(it, j) > 0.0 }
            }.toIntArray()
        }
        val sounding = BooleanArray(SURFACES)
        val inputEnergy = DoubleArray(SURFACES)
        val inputPeak = DoubleArray(SURFACES)
        val outputEnergy = DoubleArray(SURFACES)
        val inputHashes = LongArray(SURFACES) { HASH_START }
        val roughnessHashes = LongArray(SURFACES) { HASH_START }
        val row0 = DoubleArray(SURFACES)
        val row1 = DoubleArray(SURFACES)
        var controlFrame = -1
        var peak = 0.0
        var energyPeak = 0.0
        var released = false
        val releaseFrame = maxOf(activeFrames, ((active + tail - .30) * INTERNAL_RATE).toInt())
        val brushGain = (.35 + .40 * reach) / sqrt(INTERNAL_RATE.toDouble())
        // Velocity changes injected work and hardness, before output leveling.
        val effort = velocity.toDouble().pow(1.35)
        for (i in 0 until count) {
            if (i and 4095 == 0) stopIfUnwanted()
            if (!released && i >= releaseFrame) {
                for (j in 0 until SURFACES) for (k in 0 until MODES_PER_SURFACE)
                    banks[j].tune(k, configs[j].frequencies[k], minOf(configs[j].t60s[k], .08))
                released = true
            }
            val at = i.toDouble() * MeridianMotion.CONTROL_RATE / INTERNAL_RATE
            val frame = at.toInt()
            val fraction = at - frame
            if (i < activeFrames && frame != controlFrame) {
                for (j in 0 until SURFACES) {
                    row0[j] = motion.weight(frame, j)
                    row1[j] = motion.weight(frame + 1, j)
                }
                controlFrame = frame
            }
            var sum = 0.0
            var energy = 0.0
            for (j in renderedSurfaces) {
                val config = configs[j]
                var increment = 0.0
                if (i < activeFrames) {
                    val n = noise[j].next()
                    noise1[j] += config.noiseAlpha * (n - noise1[j])
                    noise2[j] += config.noiseAlpha * (noise1[j] - noise2[j])
                    val weight = row0[j] + fraction * (row1[j] - row0[j])
                    increment = weight * brushGain * noise2[j] * config.noiseScale
                    while (nextEvent[j] < eventCounts[j] && i - eventFrames[j][nextEvent[j]] >= config.pulse.lastIndex) {
                        nextEvent[j]++
                        didReset[j] = false
                    }
                    if (nextEvent[j] < eventCounts[j]) {
                        val local = i - eventFrames[j][nextEvent[j]]
                        if (local >= 0) {
                            if (resetOnContact && !didReset[j]) {
                                banks[j] = config.newBank(released)
                                didReset[j] = true
                                sounding[j] = false
                            }
                            val n0 = local.toInt()
                            val f = local - n0
                            val value = config.pulse[n0] * (1 - f) + config.pulse[n0 + 1] * f
                            increment += eventStrengths[j][nextEvent[j]] * value
                        }
                    }
                    increment *= effort
                    if (diagnostics) {
                        inputEnergy[j] += increment * increment
                        inputPeak[j] = maxOf(inputPeak[j], abs(increment))
                        inputHashes[j] = (inputHashes[j] xor increment.toBits()) * HASH_MULTIPLIER
                        roughnessHashes[j] = (roughnessHashes[j] xor n.toRawBits().toLong()) * HASH_MULTIPLIER
                    }
                }
                // Bank.drive takes force × dt; an increment is the integrated
                // finite contact impulse. Twelve small banks avoid repeatedly
                // scanning a sparse participation vector across all 48 modes.
                if (increment != 0.0) {
                    banks[j].drive(config.participation, increment * INTERNAL_RATE)
                    sounding[j] = true
                }
                // Before the first drive, the exact damped rotation of the
                // zero state is zero. Once touched, every sample keeps evolving
                // through the original Bank arithmetic, however quiet its tail.
                val y = if (sounding[j]) {
                    banks[j].step()
                    banks[j].velocityAlong(config.pickup)
                } else 0.0
                sum += y
                if (taps) rawTaps[j][i] = y.toFloat()
                if (diagnostics) {
                    outputEnergy[j] += y * y / INTERNAL_RATE
                    if (i and 255 == 0) energy += banks[j].energy()
                }
            }
            raw[i] = sum.toFloat()
            if (diagnostics) {
                peak = maxOf(peak, abs(sum))
                energyPeak = maxOf(energyPeak, energy)
            }
        }
        check(raw.all { it.isFinite() }) { "MERIDIAN produced nonfinite internal output" }
        check(energyPeak.isFinite()) { "MERIDIAN modal energy is nonfinite" }
        val samples = finish(raw, normalize)
        val surfaceOutputs = if (taps) Array(SURFACES) { j -> finish(rawTaps[j], false) } else emptyArray()
        val d = Diagnostics(peak, energyPeak, hz, DoubleArray(SURFACES * MODES_PER_SURFACE) { i -> configs[i / MODES_PER_SURFACE].frequencies[i % MODES_PER_SURFACE] },
            active, tail, prepared.cacheHit, prepared.prepareNanos, inputEnergy, inputPeak, outputEnergy,
            inputHashes, roughnessHashes, IntArray(SURFACES) { motion.entries[it].size }, motion.maxWeightSum)
        return Probe(samples, surfaceOutputs, d, motion)
    }

    private fun stopIfUnwanted() {
        if (Thread.currentThread().isInterrupted)
            throw java.util.concurrent.CancellationException("MERIDIAN render no longer wanted")
    }

    /** Same order as GYRE: pre-decimation band limit, shared 4× decimator,
     * DC removal, optional house melodic leveling, and finite-only tail fade.
     * The state-preserving damper already brings the actual bank down before
     * the last fade, rather than using a file fade to hide growing states. */
    internal fun finish(raw: FloatArray, normalize: Boolean = true): FloatArray {
        stopIfUnwanted()
        Tide.bandLimit(raw, INTERNAL_RATE)
        stopIfUnwanted()
        val out = Dsp.decimate(raw, RATE)
        stopIfUnwanted()
        var mean = 0.0
        for (x in out) mean += x
        mean /= out.size.coerceAtLeast(1)
        val hp = Dsp.OnePole(RATE)
        for (i in out.indices) {
            val x = (out[i] - mean).toFloat()
            out[i] = x - hp.lp(x, OUTPUT_DC_HZ)
        }
        if (normalize) Dsp.levelTo(out, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return out
    }

    fun render(voice: MeridianVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f, seedContext: Int = 0, model: Int = MODEL_VERSION): Snip {
        require(supportsModel(model)) { "unsupported meridian model $model" }
        if (isLoop(macros, model)) return renderHeld(voice, macros, midi, velocity, seedContext).snip
        return Snip(pass(voice, macros, midi, velocity, seedContext, null, null, normalize = true,
            taps = false, fixedAlignment = false, pMax = MeridianMotion.MAX_PRECESSION,
            resetOnContact = false, diagnostics = false).samples,
            channels = 1, sampleRate = RATE)
    }
}
