package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class FilamentVoice { THREAD, PULSE, CURL }
enum class FilamentProfile { VOICE, EARLY, LATE, COIL }

/** Saved geometry choices; generated taps and running filter state are deliberately not saved. */
data class FilamentConfig(
    val seed: Int = 0,
    val profile: FilamentProfile = FilamentProfile.VOICE,
    val perfectRetention: Boolean = false,
)

/**
 * A musical, feed-forward strand model, not an electromagnetic fiber solver.
 *
 * One bounded harmonic source is allocated to persistent strand/path IDs. Each ordered section
 * takes escaped and absorbed POWER from what remains. Its audio branches use the square roots
 * of those powers. A collector is a unit-length vector over ALL its branches, including every
 * escaped site; the halo is a convex mixture of unity-peak bandpasses and its actual input.
 * These choices bound the linear network's energy gain; coherent peaks still need headroom.
 *
 * Geometry is fixed during a render. The ordered network is compiled into equivalent delayed,
 * contractively filtered taps. A held source repeats exactly; the real delay, modal and DC state
 * is run until it recurs. Adjacent output cycles are synthesized independently, never copied or
 * crossfaded to manufacture a seam. All mappings are first-listen musical hypotheses.
 */
object Filament {
    const val MODEL_VERSION = 1
    const val MIN_MIDI = 36
    const val MAX_MIDI = 84
    private const val RATE = Dsp.RATE
    private const val HIGH_RATE = RATE * Dsp.OVERSAMPLE
    private const val STRANDS = 4
    private const val PATHS = 4
    private const val SECTIONS = 4
    private const val OUTPUT_GAIN = 1.20
    private const val LOOP_THRESHOLD = .99f
    private const val MAX_LOOP_ATTEMPTS = 40
    private const val TWO_PI = 2.0 * PI

    data class Options(
        val collapsePaths: Boolean = false,
        val genericSpill: Boolean = false,
        val disableBend: Boolean = false,
    )

    data class BranchTrace(
        val id: String,
        val strandId: Int,
        val pathId: Int,
        val launch: Double,
        val retained: Double,
        val escaped: Double,
        val absorbed: Double,
        val endpointSeconds: Double,
    )

    data class SiteTrace(
        val id: String,
        val strandId: Int,
        val pathId: Int,
        val sectionId: Int,
        val delaySeconds: Double,
        val retainedFraction: Double,
        val escapedFraction: Double,
        val absorbedFraction: Double,
        val incomingPower: Double,
        val retainedPower: Double,
        val escapedPower: Double,
        val absorbedPower: Double,
    )

    data class Diagnostics(
        val branches: List<BranchTrace>,
        val sites: List<SiteTrace>,
        val launch: Double,
        val retained: Double,
        val escaped: Double,
        val absorbed: Double,
        val rawPeak: Double,
        val rawRms: Double,
        val rawDc: Double,
        val sourceEffort: Double,
        val loop: Boolean,
        val loopConverged: Boolean,
        val seamError: Double?,
        val actualHz: Double,
        val periodFrames: Int,
        val loopAttempts: Int,
        val maxState: Double,
        val preparationNanos: Long,
        val synthesisNanos: Long,
        val fullCycleError: Double? = null,
        val stateError: Double? = null,
    )

    /** Every array is mono 44.1 kHz. Previous is the actual preceding cycle at samples' gain. */
    data class Probe(
        val samples: FloatArray,
        val raw: FloatArray,
        val source: FloatArray,
        val through: FloatArray,
        val escaped: FloatArray,
        val spill: FloatArray,
        val previous: FloatArray?,
        val diagnostics: Diagnostics,
    )

    fun rootMidi(voice: FilamentVoice): Int = when (voice) {
        FilamentVoice.THREAD, FilamentVoice.PULSE, FilamentVoice.CURL -> 48
    }

    fun macrosFor(voice: FilamentVoice): List<MacroSpec> {
        val d = when (voice) {
            FilamentVoice.THREAD -> floatArrayOf(.25f, .15f, .25f, 0f, .20f)
            FilamentVoice.PULSE -> floatArrayOf(.65f, .30f, .65f, .20f, .35f)
            FilamentVoice.CURL -> floatArrayOf(.45f, .65f, .45f, .15f, .50f)
        }
        return listOf(
            MacroSpec("INJECT", d[0], neutral = .40f),
            MacroSpec("BEND", d[1], neutral = .35f),
            MacroSpec("LENGTH", d[2], neutral = .40f),
            MacroSpec("BUNDLE", d[3], neutral = .30f),
            MacroSpec("COLLECT", d[4], neutral = .40f),
            MacroSpec("HOLD", 0f, neutral = 0f),
        )
    }

    fun defaults(voice: FilamentVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    fun settled(voice: FilamentVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).mapValues { (key, fallback) ->
            (macros[key]?.takeIf { it.isFinite() } ?: fallback).coerceIn(0f, 1f)
        }

    fun isLoop(hold: Float): Boolean = hold.isFinite() && hold >= LOOP_THRESHOLD

    fun drumClassFor(voice: FilamentVoice, macros: Map<String, Float> = emptyMap()): DrumClass =
        if (isLoop(settled(voice, macros).getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL

    fun scramble(
        voice: FilamentVoice,
        random: Random,
        temperature: Float = .35f,
        near: Patch? = null,
    ): Map<String, Float> {
        val base = defaults(voice)
        return Dsp.scrambleNear(base + (near?.macros ?: emptyMap()).filterKeys { it in base }, temperature, random) +
            ("HOLD" to 0f)
    }

    fun render(
        voice: FilamentVoice,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = rootMidi(voice),
        velocity: Float = 1f,
        config: FilamentConfig = FilamentConfig(),
    ): Snip = Snip(inspect(voice, macros, midi, velocity, config).samples, channels = 1, sampleRate = RATE)

    private data class TapSpec(val delay: Double, val power: Double, val lowHz: Double, val highHz: Double)
    private data class Plan(
        val branches: List<BranchTrace>,
        val sites: List<SiteTrace>,
        val endpoints: List<TapSpec>,
        val leakage: List<TapSpec>,
        val retained: Double,
        val escaped: Double,
        val absorbed: Double,
        val maxDelay: Double,
        val haloT60: Double,
    )

    private fun prepare(
        voice: FilamentVoice,
        m: Map<String, Float>,
        hz: Double,
        velocity: Double,
        config: FilamentConfig,
        options: Options,
    ): Plan {
        val length = m.getValue("LENGTH").toDouble()
        val inject = (.88 * m.getValue("INJECT") + .12 * velocity).coerceIn(0.0, 1.0)
        val bend = if (options.disableBend || config.perfectRetention) 0.0 else m.getValue("BEND").toDouble()
        val bundle = 3.0 * m.getValue("BUNDLE")
        val strandWeights = DoubleArray(STRANDS) { s ->
            (bundle - s + 1.0).coerceIn(0.0, 1.0) * doubleArrayOf(1.0, .66, .53, .44)[s]
        }
        val strandNorm = strandWeights.sumOf { it * it }
        val pathNarrow = doubleArrayOf(.73, .15, .08, .04)
        val pathWide = doubleArrayOf(.40, .25, .20, .15)
        val paths = DoubleArray(PATHS) { p -> pathNarrow[p] + inject * (pathWide[p] - pathNarrow[p]) }
        val common = .003 + .115 * length * length
        val spread = .001 + .052 * length
        val pathDistance = doubleArrayOf(0.0, .19, .51, 1.0)
        val baseProfile = when (config.profile) {
            FilamentProfile.EARLY -> doubleArrayOf(1.0, .26, .10, .035)
            FilamentProfile.LATE -> doubleArrayOf(.035, .10, .26, 1.0)
            FilamentProfile.COIL -> doubleArrayOf(.56, .78, .78, .56)
            FilamentProfile.VOICE -> when (voice) {
                FilamentVoice.THREAD -> doubleArrayOf(.25, .45, .80, .30)
                FilamentVoice.PULSE -> doubleArrayOf(.20, .80, .35, .15)
                FilamentVoice.CURL -> doubleArrayOf(.055, .20, 1.0, .35)
            }
        }
        val seeded = Random(Dsp.seedFor("filament-geometry", config.seed, voice))
        val offsets = DoubleArray(STRANDS) { if (it == 0) 0.0 else .88 + .24 * seeded.nextDouble() }
        val branches = ArrayList<BranchTrace>()
        val sites = ArrayList<SiteTrace>()
        val endpoints = ArrayList<TapSpec>()
        val leakage = ArrayList<TapSpec>()
        var maxDelay = 0.0
        for (s in 0 until STRANDS) {
            for (p in 0 until PATHS) {
                val launch = strandWeights[s] * strandWeights[s] / strandNorm * paths[p]
                // Diagnostic ablations keep the exact same render window and excitation stem.
                // Reserve the original complete roster's history even when paths are collapsed.
                maxDelay = max(maxDelay, common * (1.0 + .065 * s) +
                    spread * (pathDistance[p] + .10 * s * offsets[s]))
                val path = if (options.collapsePaths) 0 else p
                var endpoint = common * (1.0 + .065 * s) + spread * (pathDistance[path] + .10 * s * offsets[s])
                // Near-axis routes remain one in-budget root-bearing family, without detuning.
                // Other classes retain arbitrary fractional-period separation and interference.
                if (path == 0) endpoint = common + ((endpoint - common) * hz).roundToInt() / hz
                maxDelay = max(maxDelay, endpoint)
                var remaining = launch
                var allEscape = 0.0
                var allAbsorb = 0.0
                for (j in 0 until SECTIONS) {
                    val profileIndex = if (config.profile == FilamentProfile.VOICE) (j + s) % SECTIONS else j
                    val severity = bend.pow(.85) * baseProfile[profileIndex]
                    val escapedFractionBeforeLoss = (severity * doubleArrayOf(.64, 1.0, 1.40, 1.82)[p]).coerceIn(0.0, .97)
                    val absorbedFraction = if (config.perfectRetention) 0.0 else
                        (.0025 + .009 * length) * (1.0 + .24 * p) * (1.0 + .045 * s)
                    // Absorption precedes the split, consistently at every section.
                    val retainedFraction = (1.0 - absorbedFraction) * (1.0 - escapedFractionBeforeLoss)
                    val escapedFraction = (1.0 - absorbedFraction) * escapedFractionBeforeLoss
                    val escapedPower = remaining * escapedFraction
                    val absorbedPower = remaining * absorbedFraction
                    val retainedPower = remaining * retainedFraction
                    val delay = if (options.genericSpill) common + spread * .45 else endpoint * (j + 1.0) / SECTIONS
                    maxDelay = max(maxDelay, delay)
                    sites += SiteTrace("s$s-p$p-j$j", s, p, j, delay, retainedFraction, escapedFraction,
                        absorbedFraction, remaining, retainedPower, escapedPower, absorbedPower)
                    val spillCutoff = min(16_000.0, hz * (17.0 - 7.0 * length) / (1.0 + .14 * p + .065 * j))
                    leakage += TapSpec(delay, escapedPower, spillCutoff, hz * (.55 + .10 * p))
                    remaining = retainedPower
                    allEscape += escapedPower
                    allAbsorb += absorbedPower
                }
                branches += BranchTrace("s$s-p$p", s, p, launch, remaining, allEscape, allAbsorb, endpoint)
                val cutoff = if (config.perfectRetention) Double.POSITIVE_INFINITY else
                    min(16_000.0, hz * (11.0 - 6.0 * length) / (1.0 + .33 * p + .03 * s))
                endpoints += TapSpec(endpoint, remaining, cutoff, 0.0)
            }
        }
        return Plan(branches, sites, endpoints, leakage, branches.sumOf { it.retained },
            branches.sumOf { it.escaped }, branches.sumOf { it.absorbed }, maxDelay,
            (.52 + 1.0 * length) * if (voice == FilamentVoice.CURL) 1.12 else 1.0)
    }

    private class Tap(spec: TapSpec, totalPower: Double) {
        val delay = spec.delay * HIGH_RATE
        // sqrt(branchPower) * normalized collector coefficient sqrt(branchPower/totalPower).
        val gain = if (totalPower > 0.0) spec.power / sqrt(totalPower) else 0.0
        private val lowAlpha = if (spec.lowHz.isInfinite()) 1.0 else 1.0 - exp(-TWO_PI * spec.lowHz / HIGH_RATE)
        private val highAlpha = if (spec.highHz > 0.0) 1.0 - exp(-TWO_PI * spec.highHz / HIGH_RATE) else 0.0
        private var low = 0.0
        private var high = 0.0
        var maxState = 0.0; private set

        fun next(ring: DoubleArray, position: Int): Double {
            var location = position - delay
            if (location < 0.0) location += ring.size
            val index = floor(location).toInt()
            val fraction = location - index
            val x = ring[index] + fraction * (ring[(index + 1) % ring.size] - ring[index])
            low += lowAlpha * (x - low)
            high += highAlpha * (low - high)
            maxState = max(maxState, max(abs(low), abs(high)))
            // A convex lowpass/highpass mixture: no high-frequency or resonant gain boost.
            return gain * (low - .35 * high)
        }

        fun state(out: MutableList<Double>) { out += low; out += high }
    }

    /** Double precision avoids cancellation in low-note, high-rate, long-tail biquads. */
    private class HaloMode(hz: Double, t60: Double) {
        private val omega = TWO_PI * hz / HIGH_RATE
        private val q = max(.6, PI * hz * t60 / 6.907755278982137)
        private val alpha = sin(omega) / (2.0 * q)
        private val b = alpha / (1.0 + alpha)
        private val a1 = -2.0 * cos(omega) / (1.0 + alpha)
        private val a2 = (1.0 - alpha) / (1.0 + alpha)
        private var s1 = 0.0
        private var s2 = 0.0
        var maxState = 0.0; private set
        fun next(x: Double): Double {
            val y = b * x + s1
            s1 = -a1 * y + s2
            s2 = -b * x - a2 * y
            maxState = max(maxState, max(abs(s1), abs(s2)))
            return y
        }
        fun state(out: MutableList<Double>) { out += s1; out += s2 }
    }

    private class Dc {
        private val alpha = 1.0 - exp(-TWO_PI * 2.0 / HIGH_RATE)
        var state = 0.0
        var maxState = 0.0; private set
        fun next(x: Double): Double {
            state += alpha * (x - state)
            maxState = max(maxState, abs(state))
            return x - state
        }
    }

    private class Network(plan: Plan, hz: Double, collect: Double) {
        private val endpoints = plan.endpoints.filter { it.power > 0.0 }.map { Tap(it, plan.retained) }
        private val leakage = plan.leakage.filter { it.power > 0.0 }.map { Tap(it, plan.escaped) }
        private val ring = DoubleArray(ceil(plan.maxDelay * HIGH_RATE).toInt() + 4)
        private var position = 0
        private val ratios = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0)
        private val modeWeights = doubleArrayOf(.55, .18, .10, .065, .04, .03, .02, .015)
        private val modes = Array(ratios.size) { i -> HaloMode(hz * ratios[i], plan.haloT60 / sqrt(ratios[i])) }
        private val dc = Array(4) { Dc() }
        private val collectNorm = sqrt((1.0 - collect).pow(2) + collect * collect)
        private val throughMix = (1.0 - collect) / collectNorm
        private val spillMix = collect / collectNorm
        var maxState = 0.0
        var source = 0.0; private set
        var through = 0.0; private set
        var escaped = 0.0; private set
        var spill = 0.0; private set
        var raw = 0.0; private set

        fun next(input: Double) {
            ring[position] = input
            var t = 0.0
            var e = 0.0
            for (tap in endpoints) t += tap.next(ring, position)
            for (tap in leakage) e += tap.next(ring, position)
            var halo = 0.0
            for (i in modes.indices) halo += modeWeights[i] * modes[i].next(e)
            source = dc[0].next(input)
            through = dc[1].next(t)
            escaped = dc[2].next(e)
            spill = dc[3].next(.40 * e + .60 * halo)
            raw = throughMix * through + spillMix * spill
            maxState = max(maxState, max(abs(input), max(abs(t), max(abs(e), max(abs(halo), abs(raw))))))
            position = (position + 1) % ring.size
        }

        fun state(): DoubleArray {
            val result = ArrayList<Double>()
            for (tap in endpoints) { tap.state(result); maxState = max(maxState, tap.maxState) }
            for (tap in leakage) { tap.state(result); maxState = max(maxState, tap.maxState) }
            for (mode in modes) { mode.state(result); maxState = max(maxState, mode.maxState) }
            for (filter in dc) { result += filter.state; maxState = max(maxState, filter.maxState) }
            for (v in result) maxState = max(maxState, abs(v))
            // Canonical chronological history, independent of the circular write cursor. A
            // populated periodic input must recur here too, not only in downstream filters.
            val snapshot = DoubleArray(result.size + ring.size)
            for (i in result.indices) snapshot[i] = result[i]
            for (i in ring.indices) snapshot[result.size + i] = ring[(position + i) % ring.size]
            return snapshot
        }
    }

    private class Cycle(size: Int) {
        val source = FloatArray(size)
        val through = FloatArray(size)
        val escaped = FloatArray(size)
        val spill = FloatArray(size)
        val raw = FloatArray(size)
        fun capture(i: Int, network: Network) {
            source[i] = network.source.toFloat(); through[i] = network.through.toFloat()
            escaped[i] = network.escaped.toFloat(); spill[i] = network.spill.toFloat(); raw[i] = network.raw.toFloat()
        }
        fun arrays(): List<FloatArray> = listOf(source, through, escaped, spill, raw)
    }

    private fun harmonicWeights(inject: Double, voice: FilamentVoice, hz: Double): DoubleArray {
        val count = min(12, floor(16_000.0 / hz).toInt()).coerceAtLeast(1)
        val w = DoubleArray(count) { j ->
            if (j == 0) 1.0 else {
                val h = j + 1.0
                val richness = if (voice == FilamentVoice.PULSE) .24 + 1.20 * inject else .15 + .85 * inject
                richness / h.pow(1.35) * if (j % 2 == 0) .90 else 1.0
            }
        }
        val norm = w.sum()
        return DoubleArray(count) { w[it] / norm }
    }

    private fun sourceTable(
        voice: FilamentVoice,
        m: Map<String, Float>,
        hz: Double,
        effort: Double,
        velocity: Double,
        frames: Int,
        loop: Boolean,
        carrierCycles: Int,
    ): FloatArray {
        val inject = m.getValue("INJECT").toDouble()
        val hold = m.getValue("HOLD").toDouble()
        val weights = harmonicWeights(inject, voice, hz)
        val attack = (.004 + .020 * (1.0 - inject)) * (1.22 - .35 * velocity)
        val duration = frames.toDouble() / HIGH_RATE
        val pulseSeconds = .145 + .13 * (1.0 - inject)
        // Pulse tails overlap instead of resetting a substantial waveform to zero at each
        // trigger. Three geometric sums are the exact sum of all earlier smooth pulses:
        // (1-exp(-t/attack))^2 * exp(-t/tau). This preserves distinct attacks and a connected
        // low-note fundamental, rather than making a succession of bass-drum-like impacts.
        val pulseTau = .105 + .025 * (1.0 - inject)
        val pulseRates = doubleArrayOf(1.0 / pulseTau, 1.0 / pulseTau + 1.0 / attack,
            1.0 / pulseTau + 2.0 / attack)
        val pulseSums = Array(3) { k ->
            val decay = exp(-pulseRates[k] * pulseSeconds)
            DoubleArray(ceil(duration / pulseSeconds).toInt() + 1) { n ->
                (1.0 - decay.pow(n + 1)) / (1.0 - decay)
            }
        }
        val pulseGain = 1.0 - exp(-pulseSeconds / pulseTau)
        return FloatArray(frames) { i ->
            val time = i.toDouble() / HIGH_RATE
            val phase = if (loop) TWO_PI * carrierCycles * i / frames else TWO_PI * hz * time
            var tone = 0.0
            for (h in weights.indices) tone += weights[h] * sin((h + 1.0) * phase)
            val envelope = if (loop) {
                if (voice == FilamentVoice.PULSE) {
                    val pulsePhase = ((2.0 * i / frames) % 1.0)
                    (.5 - .5 * cos(TWO_PI * pulsePhase)).pow(3)
                } else .90
            } else {
                val onset = (1.0 - exp(-time / attack)).pow(2)
                val tailFade = ((duration - time) / .030).coerceIn(0.0, 1.0)
                if (voice == FilamentVoice.PULSE) {
                    val count = (time / pulseSeconds).toInt()
                    val local = (time - count * pulseSeconds).coerceIn(0.0, pulseSeconds)
                    val pulse = max(0.0, pulseGain * (
                        pulseSums[0][count] * exp(-local * pulseRates[0]) -
                            2.0 * pulseSums[1][count] * exp(-local * pulseRates[1]) +
                            pulseSums[2][count] * exp(-local * pulseRates[2])))
                    pulse * exp(-time / (.62 + .65 * hold)) * tailFade
                } else {
                    val tau = (if (voice == FilamentVoice.CURL) .34 else .23) + .72 * hold
                    val plateau = if (voice == FilamentVoice.CURL) .24 + .38 * hold else 0.0
                    onset * exp(-max(0.0, time - plateau) / tau) * tailFade
                }
            }
            (effort * envelope * tone).toFloat()
        }
    }

    private fun decimate(high: FloatArray): FloatArray = Dsp.decimate(high, RATE)

    fun inspect(
        voice: FilamentVoice,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = rootMidi(voice),
        velocity: Float = 1f,
        config: FilamentConfig = FilamentConfig(),
        options: Options = Options(),
    ): Probe {
        require(midi in MIN_MIDI..MAX_MIDI) { "FILAMENT supports MIDI $MIN_MIDI..$MAX_MIDI (received $midi)" }
        require(velocity.isFinite() && velocity in 0f..1f) { "FILAMENT velocity must be finite and in 0..1" }
        val start = System.nanoTime()
        val m = settled(voice, macros)
        val loop = isLoop(m.getValue("HOLD"))
        val nominalHz = 440.0 * 2.0.pow((midi - 69.0) / 12.0)
        val cycles = max(1, (nominalHz * .42).roundToInt())
        val periodFrames = if (loop) (cycles * RATE / nominalHz).roundToInt() else 0
        val hz = if (loop) cycles.toDouble() * RATE / periodFrames else nominalHz
        val effort = if (velocity == 0f) 0.0 else .62 * velocity.toDouble().pow(.72)
        val plan = prepare(voice, m, hz, velocity.toDouble(), config, options)
        val network = Network(plan, hz, m.getValue("COLLECT").toDouble())
        val preparationNanos = System.nanoTime() - start
        val synthesisStart = System.nanoTime()
        var previous: FloatArray? = null
        var attempts = 0
        var converged = !loop
        var seam: Double? = null
        var fullCycleError: Double? = null
        var stateError: Double? = null
        val output: List<FloatArray>
        if (!loop) {
            val sourceSeconds = when (voice) {
                FilamentVoice.THREAD -> 1.70 + 2.3 * m.getValue("HOLD")
                FilamentVoice.PULSE -> 1.55 + 2.2 * m.getValue("HOLD")
                FilamentVoice.CURL -> 2.05 + 2.5 * m.getValue("HOLD")
            }
            val sourceFrames = (sourceSeconds * HIGH_RATE).roundToInt()
            val source = sourceTable(voice, m, hz, effort, velocity.toDouble(), sourceFrames, false, 0)
            val total = ceil((sourceSeconds + plan.maxDelay + 1.8 * plan.haloT60 + .08) * HIGH_RATE).toInt()
            val capture = Cycle(total + (Dsp.OVERSAMPLE - total % Dsp.OVERSAMPLE) % Dsp.OVERSAMPLE)
            for (i in capture.raw.indices) {
                network.next(if (i < source.size) source[i].toDouble() else 0.0)
                capture.capture(i, network)
            }
            network.state()
            output = capture.arrays().map { decimate(it).also { samples -> Dsp.fadeTail(samples) } }
        } else {
            val highFrames = periodFrames * Dsp.OVERSAMPLE
            val source = sourceTable(voice, m, hz, effort, velocity.toDouble(), highFrames, true, cycles)
            val recent = ArrayList<Cycle>()
            var previousState: DoubleArray? = null
            val minAttempts = max(4, ceil(plan.maxDelay * HIGH_RATE / highFrames).toInt() + 3)
            while (attempts < MAX_LOOP_ATTEMPTS) {
                val capture = Cycle(highFrames)
                for (i in source.indices) { network.next(source[i].toDouble()); capture.capture(i, network) }
                recent += capture
                if (recent.size > 3) recent.removeAt(0)
                attempts++
                val state = network.state()
                val old = previousState
                var error = 0.0
                var scale = 0.0
                if (old != null) for (i in state.indices) {
                    error = max(error, abs(state[i] - old[i]))
                    scale = max(scale, max(abs(state[i]), abs(old[i])))
                }
                stateError = if (old == null) null else error / max(1e-12, scale)
                previousState = state
                if (attempts >= minAttempts && old != null && error <= 1e-9 + 1e-7 * scale) {
                    converged = true
                    break
                }
            }
            check(converged) { "FILAMENT $voice MIDI $midi HOLD did not settle after $attempts periods" }
            // A following, independently rendered cycle supplies the resampler's right guard.
            val future = Cycle(highFrames)
            for (i in source.indices) { network.next(source[i].toDouble()); future.capture(i, network) }
            network.state()
            recent += future
            val channels = Array(5) { FloatArray(highFrames * 4) }
            for (c in 0 until 4) for (s in 0 until 5) recent[c].arrays()[s].copyInto(channels[s], c * highFrames)
            val resampled = channels.map { decimate(it) }
            output = resampled.map { it.copyOfRange(periodFrames * 2, periodFrames * 3) }
            previous = resampled[4].copyOfRange(periodFrames, periodFrames * 2)
            val joined = previous + output[4]
            var differenceEnergy = 0.0
            var referenceEnergy = 0.0
            for (i in previous.indices) {
                val difference = previous[i].toDouble() - output[4][i]
                differenceEnergy += difference * difference
                referenceEnergy += previous[i].toDouble() * previous[i] + output[4][i].toDouble() * output[4][i]
            }
            fullCycleError = if (referenceEnergy == 0.0) 0.0 else 2.0 * differenceEnergy / referenceEnergy
            // Structural silence is a valid state, including nonzero excitation with no escaped
            // branch. Only two actually silent cycles get a zero-error result.
            seam = if (joined.all { it == 0f }) 0.0 else Keys.seamError(joined, previous.size)
            check(fullCycleError.isFinite() && fullCycleError < 1e-5) {
                "FILAMENT $voice MIDI $midi held full cycles differ (error $fullCycleError)"
            }
            check(seam.isFinite() && seam < 1e-3) {
                "FILAMENT $voice MIDI $midi held output does not recur (seam $seam)"
            }
        }
        val source = output[0]
        val through = output[1]
        val escaped = output[2]
        val spill = output[3]
        val raw = output[4]
        var peak = 0.0
        var square = 0.0
        var sum = 0.0
        for (v in raw) { peak = max(peak, abs(v.toDouble())); square += v.toDouble() * v; sum += v }
        val samples = FloatArray(raw.size) { (raw[it] * OUTPUT_GAIN).toFloat() }
        previous = previous?.let { p -> FloatArray(p.size) { (p[it] * OUTPUT_GAIN).toFloat() } }
        val diagnostics = Diagnostics(plan.branches, plan.sites, plan.branches.sumOf { it.launch },
            plan.retained, plan.escaped, plan.absorbed, peak, sqrt(square / raw.size.coerceAtLeast(1)),
            sum / raw.size.coerceAtLeast(1), effort, loop, converged, seam, hz, periodFrames, attempts,
            network.maxState, preparationNanos, System.nanoTime() - synthesisStart, fullCycleError, stateError)
        check(samples.all { it.isFinite() }) { "FILAMENT produced a non-finite sample" }
        return Probe(samples, raw, source, through, escaped, spill, previous, diagnostics)
    }
}
