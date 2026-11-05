package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
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
 * These choices bound the network's energy gain; coherent peaks still need headroom.
 *
 * Geometry is fixed during a render. The ordered network is compiled into equivalent delayed,
 * contractively filtered taps. A held source repeats exactly; the real delay, modal and DC state
 * is run until it recurs. Adjacent output cycles are synthesized independently, never copied or
 * crossfaded to manufacture a seam. All mappings are first-listen musical hypotheses.
 */
object Filament {
    const val MODEL_VERSION = 3
    const val ORIGINAL_MODEL = 1
    const val CHARACTER_MODEL = 2
    const val MIN_MIDI = 36
    const val MAX_MIDI = 84
    private const val RATE = Dsp.RATE
    private const val HIGH_RATE = RATE * Dsp.OVERSAMPLE

    /**
     * Recursive states below this are set to exactly zero. A long tail otherwise decays into
     * subnormal doubles, which the processor handles on a slow path: the render-speed scan
     * (docs/ENGINE_STANDARD_SCAN_2026_11.md) measured FILAMENT's knob-turn renders at 6 to
     * 11 s, almost all of it in these filters, and flooring them cut that by six to nine
     * times. 1e-100 is ACCORD's floor (AccordAcoustics.filterExcitation): about -2000 dB, far
     * above where subnormals begin (2.2e-308) and far below any 24-bit sample step, so the
     * published WAVs FilamentCharacterTest pins are unchanged.
     */
    internal const val TINY_STATE = 1e-100

    /** [x], or exactly zero when it has decayed below [TINY_STATE]. */
    @Suppress("NOTHING_TO_INLINE")
    internal inline fun floored(x: Double): Double = if (abs(x) < TINY_STATE) 0.0 else x
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
        val disableExchange: Boolean = false,
        val freezeMemory: Boolean = false,
        val referenceOversampling: Int = Dsp.OVERSAMPLE,
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
        val haloGainBound: Double? = null,
        val haloNormalization: Double? = null,
        val haloModes: List<HaloTrace> = emptyList(),
        val allocationBasis: String = "feed-forward splitter allocation",
        val exchange: ExchangeDiagnostics? = null,
    )

    data class HaloTrace(val frequencyHz: Double, val t60Seconds: Double, val weight: Double)

    /** Actual waveform energy integrals, separately from the nominal no-exchange plan. */
    data class ExchangeDiagnostics(
        val enabled: Boolean,
        val frozen: Boolean,
        val oversampling: Int,
        val sourceEnergy: Double,
        val launchedEnergy: Double,
        val retainedEnergy: Double,
        val escapedEnergy: Double,
        val absorbedEnergy: Double,
        val uncollectedEnergy: Double,
        val junctionInputEnergy: Double,
        val junctionOutputEnergy: Double,
        val maxJunctionError: Double,
        val maxSplitError: Double,
        val collectorThroughNorm: Double,
        val collectorSpillNorm: Double,
        val maxMemory: Double,
        val meanMemory: Double,
        val maxAngleRadians: Double,
        val routeEnergyChanges: List<Double>,
        val memorySpan: Double = 0.0,
        val energyScope: String = "complete finite propagation",
        val energyFrameCount: Long = 0,
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
        model: Int = MODEL_VERSION,
    ): Snip = render(voice, macros, midi, velocity, config, model, cancelled = { false })

    /** The ordinary renderer keeps the DSP, but does not allocate diagnostic stems or ledgers. */
    fun render(
        voice: FilamentVoice,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = rootMidi(voice),
        velocity: Float = 1f,
        config: FilamentConfig = FilamentConfig(),
        model: Int = MODEL_VERSION,
        cancelled: () -> Boolean,
    ): Snip = Snip(synthesize(voice, macros, midi, velocity, config, Options(), model,
        captureDiagnostics = false, cancelled = cancelled).samples, channels = 1, sampleRate = RATE)

    private fun requireActive(cancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("FILAMENT render cancelled")
    }

    /** The actual neighboring cycles are retained only by the held-instrument proof path. */
    internal data class HeldNoteEvidence(
        val note: KeyNote,
        val previous: FloatArray,
        val continuation: FloatArray,
        val cycleFrames: Int,
        val attempts: Int,
        val stateError: Double,
        val fullCycleError: Double,
        val actualHz: Double,
    )

    internal fun keyNote(patch: FilamentPatch, midi: Int = patch.midi,
                         cancelled: () -> Boolean = { false }): KeyNote =
        heldNote(patch, midi, cancelled, continuationEvidence = false).note

    internal fun keyNoteMeasured(patch: FilamentPatch, midi: Int = patch.midi,
                                 cancelled: () -> Boolean = { false }): HeldNoteEvidence =
        heldNote(patch, midi, cancelled, continuationEvidence = true)

    private fun heldNote(patch: FilamentPatch, midi: Int, cancelled: () -> Boolean,
                         continuationEvidence: Boolean): HeldNoteEvidence {
        require(FilamentKeys.supports(patch)) {
            "FILAMENT playable keys require a supported model-$MODEL_VERSION recipe"
        }
        return requireNotNull(synthesize(patch.voice, patch.macros + ("HOLD" to 1f), midi, patch.velocity,
            patch.config, Options(), patch.model, captureDiagnostics = false, cancelled = cancelled,
            retainOnset = true, continuationEvidence = continuationEvidence).held)
    }

    private data class RenderResult(val samples: FloatArray, val probe: Probe? = null,
                                    val held: HeldNoteEvidence? = null)

    internal data class TapSpec(
        val delay: Double,
        val power: Double,
        val lowHz: Double,
        val highHz: Double,
        val highMix: Double = .35,
        val dispersionHz: Double = 0.0,
        val dispersionMix: Double = 0.0,
    )
    internal data class Plan(
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
        model: Int,
    ): Plan {
        val length = m.getValue("LENGTH").toDouble()
        val inject = (.88 * m.getValue("INJECT") + .12 * velocity).coerceIn(0.0, 1.0)
        val bend = if (options.disableBend || config.perfectRetention) 0.0 else m.getValue("BEND").toDouble()
        val bundle = 3.0 * m.getValue("BUNDLE")
        val strandWeights = DoubleArray(STRANDS) { s ->
            (bundle - s + 1.0).coerceIn(0.0, 1.0) * doubleArrayOf(1.0, .66, .53, .44)[s]
        }
        val strandNorm = strandWeights.sumOf { it * it }
        val pathNarrow = if (model == ORIGINAL_MODEL) doubleArrayOf(.73, .15, .08, .04) else when (voice) {
            FilamentVoice.THREAD -> doubleArrayOf(.62, .20, .12, .06)
            FilamentVoice.PULSE -> doubleArrayOf(.56, .22, .14, .08)
            FilamentVoice.CURL -> doubleArrayOf(.52, .22, .17, .09)
        }
        val pathWide = if (model == ORIGINAL_MODEL) doubleArrayOf(.40, .25, .20, .15) else when (voice) {
            FilamentVoice.THREAD -> doubleArrayOf(.44, .23, .20, .13)
            FilamentVoice.PULSE -> doubleArrayOf(.40, .24, .21, .15)
            FilamentVoice.CURL -> doubleArrayOf(.38, .24, .22, .16)
        }
        val paths = DoubleArray(PATHS) { p -> pathNarrow[p] + inject * (pathWide[p] - pathNarrow[p]) }
        val common = if (model == ORIGINAL_MODEL) .003 + .115 * length * length else
            .002 + (when (voice) { FilamentVoice.THREAD -> .09; FilamentVoice.PULSE -> .13; FilamentVoice.CURL -> .115 }) * length * length
        val spread = if (model == ORIGINAL_MODEL) .001 + .052 * length else
            .002 + (when (voice) { FilamentVoice.THREAD -> .040; FilamentVoice.PULSE -> .070; FilamentVoice.CURL -> .055 }) * length
        val pathDistance = if (model == ORIGINAL_MODEL) doubleArrayOf(0.0, .19, .51, 1.0) else when (voice) {
            FilamentVoice.THREAD -> doubleArrayOf(0.0, .14, .43, 1.0)
            FilamentVoice.PULSE -> doubleArrayOf(0.0, .32, .67, 1.18)
            FilamentVoice.CURL -> doubleArrayOf(0.0, .10, .54, 1.28)
        }
        val baseProfile = when (config.profile) {
            FilamentProfile.EARLY -> doubleArrayOf(1.0, .26, .10, .035)
            FilamentProfile.LATE -> doubleArrayOf(.035, .10, .26, 1.0)
            FilamentProfile.COIL -> doubleArrayOf(.56, .78, .78, .56)
            FilamentProfile.VOICE -> if (model == ORIGINAL_MODEL) when (voice) {
                FilamentVoice.THREAD -> doubleArrayOf(.25, .45, .80, .30)
                FilamentVoice.PULSE -> doubleArrayOf(.20, .80, .35, .15)
                FilamentVoice.CURL -> doubleArrayOf(.055, .20, 1.0, .35)
            } else when (voice) {
                FilamentVoice.THREAD -> doubleArrayOf(.55, .80, 1.0, .45)
                FilamentVoice.PULSE -> doubleArrayOf(.15, .90, .50, .20)
                FilamentVoice.CURL -> doubleArrayOf(.04, .18, 1.0, .65)
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
                    val severity = bend.pow(if (model == ORIGINAL_MODEL) .85 else .68) * baseProfile[profileIndex]
                    val sensitivity = if (model == ORIGINAL_MODEL) doubleArrayOf(.64, 1.0, 1.40, 1.82)[p] else
                        (when (voice) {
                            FilamentVoice.THREAD -> doubleArrayOf(.85, 1.15, 1.55, 1.90)
                            FilamentVoice.PULSE -> doubleArrayOf(.65, 1.05, 1.50, 1.95)
                            FilamentVoice.CURL -> doubleArrayOf(.70, 1.10, 1.60, 2.0)
                        })[p]
                    val escapedFractionBeforeLoss = (severity * sensitivity).coerceIn(0.0, .97)
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
                    if (model == ORIGINAL_MODEL) leakage += TapSpec(delay, escapedPower, spillCutoff, hz * (.55 + .10 * p))
                    else {
                        val highRatio = when (voice) { FilamentVoice.THREAD -> 1.20; FilamentVoice.PULSE -> 1.60; FilamentVoice.CURL -> 2.20 }
                        val highMix = when (voice) { FilamentVoice.THREAD -> .82; FilamentVoice.PULSE -> .88; FilamentVoice.CURL -> .92 }
                        val dispersion = if (p == 0) 0.0 else hz * (1.7 + .65 * p + .21 * j)
                        leakage += TapSpec(delay, escapedPower, spillCutoff, hz * (highRatio + .12 * p), highMix,
                            dispersion, if (p == 0) 0.0 else .30 + .06 * p)
                    }
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
        val t60 = if (model == ORIGINAL_MODEL) (.52 + 1.0 * length) * if (voice == FilamentVoice.CURL) 1.12 else 1.0
            else when (voice) {
                FilamentVoice.THREAD -> .62 + .95 * length
                FilamentVoice.PULSE -> .48 + .85 * length
                FilamentVoice.CURL -> .82 + 1.05 * length
            }
        return Plan(branches, sites, endpoints, leakage, branches.sumOf { it.retained },
            branches.sumOf { it.escaped }, branches.sumOf { it.absorbed }, maxDelay,
            t60)
    }

    private class Tap(spec: TapSpec, totalPower: Double) {
        val delay = spec.delay * HIGH_RATE
        // sqrt(branchPower) * normalized collector coefficient sqrt(branchPower/totalPower).
        val gain = if (totalPower > 0.0) spec.power / sqrt(totalPower) else 0.0
        private val lowAlpha = if (spec.lowHz.isInfinite()) 1.0 else 1.0 - exp(-TWO_PI * spec.lowHz / HIGH_RATE)
        private val highAlpha = if (spec.highHz > 0.0) 1.0 - exp(-TWO_PI * spec.highHz / HIGH_RATE) else 0.0
        private val highMix = spec.highMix
        private val dispersion = if (spec.dispersionHz > 0.0) Allpass(spec.dispersionHz) else null
        private val dispersionMix = spec.dispersionMix
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
            val filtered = low - highMix * high
            val dispersive = dispersion
            return if (dispersive == null) gain * filtered else
                gain * ((1.0 - dispersionMix) * filtered + dispersionMix * dispersive.next(filtered))
        }

        fun state(out: MutableList<Double>) {
            out += low; out += high
            dispersion?.let { it.state(out); maxState = max(maxState, it.maxState) }
        }
    }

    /** A fixed allpass changes a route's dispersion, never its pitch or magnitude response. */
    internal class Allpass(hz: Double, rate: Int = HIGH_RATE, private val captureDiagnostics: Boolean = true) {
        private val omega = TWO_PI * hz / rate
        private val alpha = sin(omega) / 1.8
        private val a1 = -2.0 * cos(omega) / (1.0 + alpha)
        private val a2 = (1.0 - alpha) / (1.0 + alpha)
        private var s1 = 0.0
        private var s2 = 0.0
        var maxState = 0.0; private set
        fun next(x: Double): Double {
            val y = a2 * x + s1
            s1 = floored(a1 * x - a1 * y + s2)
            s2 = floored(x - a2 * y)
            if (captureDiagnostics) maxState = max(maxState, max(abs(s1), abs(s2)))
            return y
        }
        fun state(out: MutableList<Double>) { out += s1; out += s2 }
    }

    /** Double precision avoids cancellation in low-note, high-rate, long-tail biquads. */
    private class HaloMode(hz: Double, t60: Double, rate: Int = HIGH_RATE, private val captureDiagnostics: Boolean = true) {
        val omega = TWO_PI * hz / rate
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
            s1 = floored(-a1 * y + s2)
            s2 = floored(-b * x - a2 * y)
            if (captureDiagnostics) maxState = max(maxState, max(abs(s1), abs(s2)))
            return y
        }
        fun state(out: MutableList<Double>) { out += s1; out += s2 }

        fun magnitude(angularFrequency: Double): Double {
            val numerator = alpha * abs(sin(angularFrequency))
            val offset = cos(angularFrequency) - cos(omega)
            return numerator / sqrt(offset * offset + numerator * numerator)
        }
    }

    /**
     * Each constant-peak RBJ band is monotonic on either side of its center:
     * |H(w)| = alpha sin(w) / hypot(cos(w)-cos(w0), alpha sin(w)).
     * Partition at centers AND midpoints, so each interval's exact single-band maximum is at
     * an endpoint or its contained center. The triangle inequality then bounds the complete
     * complex parallel response by the sum of these maxima. Dividing by this conservative
     * maximum (with 2% reserve) proves a <=.98 induced energy gain for the entire bank, while
     * allowing each separated resonance to be heard. This is fixed filter calibration, never
     * per-render loudness makeup or normalization of a depleted/silent escaped branch.
     */
    private fun modalGainBound(modes: Array<HaloMode>, weights: DoubleArray): Double {
        val boundaries = ArrayList<Double>()
        boundaries += 0.0; boundaries += PI
        for (mode in modes) boundaries += mode.omega
        for (i in 0 until modes.size - 1) boundaries += (modes[i].omega + modes[i + 1].omega) * .5
        boundaries.sort()
        var maximum = 0.0
        for (i in 0 until boundaries.size - 1) {
            val lo = boundaries[i]
            val hi = boundaries[i + 1]
            var sum = 0.0
            for (j in modes.indices) {
                val mode = modes[j]
                val bound = if (mode.omega in lo..hi) 1.0 else max(mode.magnitude(lo), mode.magnitude(hi))
                sum += weights[j] * bound
            }
            maximum = max(maximum, sum)
        }
        return maximum
    }

    private class Dc(rate: Int = HIGH_RATE, private val captureDiagnostics: Boolean = true) {
        private val alpha = 1.0 - exp(-TWO_PI * 2.0 / rate)
        var state = 0.0
        var maxState = 0.0; private set
        fun next(x: Double): Double {
            state = floored(state + alpha * (x - state))
            if (captureDiagnostics) maxState = max(maxState, abs(state))
            return x - state
        }
    }

    private class Network(plan: Plan, hz: Double, collect: Double, voice: FilamentVoice, model: Int,
                          rate: Int = HIGH_RATE, private val captureDiagnostics: Boolean = true) {
        private val endpoints = plan.endpoints.filter { it.power > 0.0 }.map { Tap(it, plan.retained) }
        private val leakage = plan.leakage.filter { it.power > 0.0 }.map { Tap(it, plan.escaped) }
        private val ring = DoubleArray(ceil(plan.maxDelay * rate).toInt() + 4)
        private var position = 0
        private val original = model == ORIGINAL_MODEL
        private val ratios = if (original) doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0) else when (voice) {
            FilamentVoice.THREAD -> doubleArrayOf(1.0, 2.0, 3.0, 4.006, 5.012, 6.025, 8.045, 10.08)
            FilamentVoice.PULSE -> doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.012, 8.025, 10.04)
            FilamentVoice.CURL -> doubleArrayOf(1.0, 2.002, 3.006, 4.014, 5.026, 6.045, 8.080, 10.12)
        }
        private val modeWeights = if (original) doubleArrayOf(.55, .18, .10, .065, .04, .03, .02, .015) else when (voice) {
            FilamentVoice.THREAD -> doubleArrayOf(.20, .22, .18, .14, .10, .07, .05, .04)
            FilamentVoice.PULSE -> doubleArrayOf(.22, .17, .19, .13, .12, .08, .055, .035)
            FilamentVoice.CURL -> doubleArrayOf(.18, .14, .20, .10, .16, .09, .08, .05)
        }
        val haloModes = List(ratios.size) { i ->
            val t60 = if (original) plan.haloT60 / sqrt(ratios[i]) else when (voice) {
                FilamentVoice.THREAD -> plan.haloT60 / (1.0 + .35 * i)
                FilamentVoice.PULSE -> plan.haloT60 / (1.0 + .22 * i)
                FilamentVoice.CURL -> plan.haloT60 / sqrt(ratios[i]) * if (i >= 3) 1.15 else 1.0
            }
            HaloTrace(hz * ratios[i], t60, modeWeights[i])
        }
        private val modes = Array(ratios.size) { i -> HaloMode(haloModes[i].frequencyHz, haloModes[i].t60Seconds, rate, captureDiagnostics) }
        private val unnormalizedBound = if (original) 1.0 else modalGainBound(modes, modeWeights)
        val haloNormalization: Double? = if (original) null else .98 / unnormalizedBound
        val haloGainBound: Double? = if (original) null else unnormalizedBound * haloNormalization!!
        private val dc = Array(4) { Dc(rate, captureDiagnostics) }
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
            if (!original) halo *= haloNormalization!!
            source = dc[0].next(input)
            through = dc[1].next(t)
            escaped = dc[2].next(e)
            spill = dc[3].next(if (original) .40 * e + .60 * halo else .20 * e + .80 * halo)
            raw = throughMix * through + spillMix * spill
            if (captureDiagnostics) maxState = max(maxState, max(abs(input), max(abs(t), max(abs(e), max(abs(halo), abs(raw))))))
            position = (position + 1) % ring.size
        }

        /** Model 3 supplies genuinely propagated terminal channels to the unchanged collectors. */
        fun fromJunction(input: Double, t: Double, e: Double) {
            var halo = 0.0
            for (i in modes.indices) halo += modeWeights[i] * modes[i].next(e)
            halo *= haloNormalization!!
            source = dc[0].next(input)
            through = dc[1].next(t)
            escaped = dc[2].next(e)
            spill = dc[3].next(.20 * e + .80 * halo)
            raw = throughMix * through + spillMix * spill
            if (captureDiagnostics) maxState = max(maxState, max(abs(input), max(abs(t), max(abs(e), max(abs(halo), abs(raw))))))
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

    private class Cycle(size: Int, stems: Boolean) {
        val source = FloatArray(if (stems) size else 0)
        val through = FloatArray(if (stems) size else 0)
        val escaped = FloatArray(if (stems) size else 0)
        val spill = FloatArray(if (stems) size else 0)
        val raw = FloatArray(size)
        fun capture(i: Int, network: Network) {
            if (source.isNotEmpty()) {
                source[i] = network.source.toFloat(); through[i] = network.through.toFloat()
                escaped[i] = network.escaped.toFloat(); spill[i] = network.spill.toFloat()
            }
            raw[i] = network.raw.toFloat()
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
        model: Int,
        config: FilamentConfig,
        rate: Int = HIGH_RATE,
        cancelled: () -> Boolean = { false },
    ): FloatArray {
        if (model != ORIGINAL_MODEL) return sourceTable2(voice, m, hz, effort, velocity, frames, loop, carrierCycles, config, rate, cancelled)
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
            if (i and 1023 == 0) requireActive(cancelled)
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

    /**
     * Model 2's energy enters as a pitched spectrum whose partial families develop differently.
     * Carrier phases stay fixed. Upper families change their own excitation, and
     * the real path delays, selective loss and escaped modes decide when and where it is
     * heard. All envelopes are in [0,1], and the sum of harmonic amplitudes is bounded by effort.
     */
    private fun sourceTable2(
        voice: FilamentVoice,
        m: Map<String, Float>,
        hz: Double,
        effort: Double,
        velocity: Double,
        frames: Int,
        loop: Boolean,
        carrierCycles: Int,
        config: FilamentConfig,
        rate: Int = HIGH_RATE,
        cancelled: () -> Boolean = { false },
    ): FloatArray {
        val inject = m.getValue("INJECT").toDouble()
        val hold = if (loop) 1.0 else m.getValue("HOLD").toDouble()
        val recipe = when (voice) {
            FilamentVoice.THREAD -> doubleArrayOf(1.0, .66, .60, .43, .35, .28, .22, .18, .14, .11, .08, .06)
            FilamentVoice.PULSE -> doubleArrayOf(1.0, .88, .70, .58, .48, .39, .31, .26, .21, .17, .13, .10)
            FilamentVoice.CURL -> doubleArrayOf(1.0, .64, .82, .40, .62, .32, .40, .26, .30, .18, .21, .13)
        }
        val count = min(recipe.size, floor(16_000.0 / hz).toInt()).coerceAtLeast(1)
        val weights = DoubleArray(count) { h ->
            if (h == 0) recipe[h] else recipe[h] * (.55 + .65 * inject)
        }
        val normalization = weights.sum()
        for (i in weights.indices) weights[i] /= normalization
        val seeded = Random(Dsp.seedFor("filament-articulation-v2", config.seed, voice))
        val phaseA = seeded.nextDouble() * TWO_PI
        val phaseB = seeded.nextDouble() * TWO_PI
        val timing = .93 + .14 * seeded.nextDouble()
        val attack = (.004 + .018 * (1.0 - inject)) * (1.22 - .35 * velocity) * timing
        val duration = frames.toDouble() / rate
        val pulseSeconds = .155 + .12 * (1.0 - inject)
        val pulseTau = .110 + .030 * (1.0 - inject)
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
            if (i and 1023 == 0) requireActive(cancelled)
            val time = i.toDouble() / rate
            val phase = if (loop) TWO_PI * carrierCycles * i / frames else TWO_PI * hz * time
            val progress = i.toDouble() / frames
            var rootEnvelope: Double
            var middleEnvelope: Double
            var edgeEnvelope: Double
            if (loop) {
                when (voice) {
                    FilamentVoice.THREAD -> {
                        rootEnvelope = .92
                        middleEnvelope = .65 + .35 * (.5 + .5 * sin(TWO_PI * progress + phaseA))
                        edgeEnvelope = .20 + .80 * (.5 + .5 * sin(TWO_PI * 3.0 * progress + phaseB))
                    }
                    FilamentVoice.PULSE -> {
                        val pulse = (.5 - .5 * cos(TWO_PI * 6.0 * progress)).pow(3)
                        rootEnvelope = .36 + .64 * pulse
                        middleEnvelope = rootEnvelope * (.40 + .60 * (.5 + .5 * sin(TWO_PI * progress + phaseA)))
                        edgeEnvelope = rootEnvelope * (.30 + .70 * (.5 + .5 * sin(TWO_PI * 2.0 * progress + phaseB)))
                    }
                    FilamentVoice.CURL -> {
                        rootEnvelope = .88
                        middleEnvelope = .35 + .65 * (.5 + .5 * sin(TWO_PI * progress + phaseA))
                        edgeEnvelope = .15 + .85 * (.5 + .5 * sin(TWO_PI * 2.0 * progress + phaseB))
                    }
                }
            } else {
                val tailFade = ((duration - time) / .030).coerceIn(0.0, 1.0)
                fun opening(seconds: Double): Double = (1.0 - exp(-time / seconds)).pow(2)
                when (voice) {
                    FilamentVoice.THREAD -> {
                        rootEnvelope = opening(attack) * exp(-time / (.48 + .72 * hold))
                        middleEnvelope = opening(attack * .65) * exp(-time / (.36 + .60 * hold))
                        edgeEnvelope = opening(attack * .45) * exp(-time / (.14 + .30 * hold))
                    }
                    FilamentVoice.PULSE -> {
                        val pulseIndex = (time / pulseSeconds).toInt()
                        val local = (time - pulseIndex * pulseSeconds).coerceIn(0.0, pulseSeconds)
                        val pulse = max(0.0, pulseGain * (
                            pulseSums[0][pulseIndex] * exp(-local * pulseRates[0]) -
                                2.0 * pulseSums[1][pulseIndex] * exp(-local * pulseRates[1]) +
                                pulseSums[2][pulseIndex] * exp(-local * pulseRates[2])))
                        rootEnvelope = pulse * exp(-time / (.70 + .65 * hold))
                        middleEnvelope = rootEnvelope * exp(-time / (.85 + .8 * hold))
                        edgeEnvelope = rootEnvelope * (.20 + .80 * exp(-time / (.26 + .45 * hold)))
                    }
                    FilamentVoice.CURL -> {
                        rootEnvelope = opening(attack * 1.8) * exp(-max(0.0, time - .24 - .30 * hold) / (.43 + .65 * hold))
                        middleEnvelope = opening(.030 + .075 * (1.0 - inject)) *
                            exp(-max(0.0, time - .32) / (.82 + .65 * hold))
                        edgeEnvelope = opening(.095 + .18 * (1.0 - inject)) *
                            exp(-max(0.0, time - .45) / (1.0 + .65 * hold))
                    }
                }
                rootEnvelope *= tailFade
                middleEnvelope *= tailFade
                edgeEnvelope *= tailFade
            }
            var tone = 0.0
            for (h in weights.indices) {
                val envelope = when (h) { 0 -> rootEnvelope; in 1..3 -> middleEnvelope; else -> edgeEnvelope }
                tone += weights[h] * envelope * sin((h + 1.0) * phase)
            }
            (effort * tone).toFloat()
        }
    }

    private fun decimate(high: FloatArray, oversampling: Int = Dsp.OVERSAMPLE): FloatArray =
        if (oversampling == Dsp.OVERSAMPLE) Dsp.decimate(high, RATE) else {
            val half = Resampler.resample(Snip(high, channels = 1, sampleRate = RATE * oversampling), RATE * Dsp.OVERSAMPLE)
            Dsp.decimate(half.samples, RATE)
        }

    fun inspect(
        voice: FilamentVoice,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = rootMidi(voice),
        velocity: Float = 1f,
        config: FilamentConfig = FilamentConfig(),
        options: Options = Options(),
        model: Int = MODEL_VERSION,
    ): Probe = requireNotNull(synthesize(voice, macros, midi, velocity, config, options, model,
        captureDiagnostics = true, cancelled = { false }).probe)

    private fun synthesize(
        voice: FilamentVoice,
        macros: Map<String, Float>,
        midi: Int,
        velocity: Float,
        config: FilamentConfig,
        options: Options,
        model: Int,
        captureDiagnostics: Boolean,
        cancelled: () -> Boolean,
        retainOnset: Boolean = false,
        continuationEvidence: Boolean = false,
    ): RenderResult {
        requireActive(cancelled)
        require(model in ORIGINAL_MODEL..MODEL_VERSION) { "unsupported FILAMENT model $model" }
        require(options.referenceOversampling in listOf(4, 8)) { "FILAMENT reference oversampling must be 4 or 8" }
        require(model == MODEL_VERSION || options.referenceOversampling == Dsp.OVERSAMPLE) {
            "FILAMENT reference oversampling is a model-3 diagnostic"
        }
        require(midi in MIN_MIDI..MAX_MIDI) { "FILAMENT supports MIDI $MIN_MIDI..$MAX_MIDI (received $midi)" }
        require(velocity.isFinite() && velocity in 0f..1f) { "FILAMENT velocity must be finite and in 0..1" }
        val start = System.nanoTime()
        val m = settled(voice, macros)
        val oversampling = options.referenceOversampling
        val highRate = RATE * oversampling
        val loop = isLoop(m.getValue("HOLD"))
        val nominalHz = 440.0 * 2.0.pow((midi - 69.0) / 12.0)
        val cycles = max(1, (nominalHz * if (model == ORIGINAL_MODEL) .42 else 2.3).roundToInt())
        val periodFrames = if (loop) (cycles * RATE / nominalHz).roundToInt() else 0
        val hz = if (loop) cycles.toDouble() * RATE / periodFrames else nominalHz
        val effort = if (velocity == 0f) 0.0 else .62 * velocity.toDouble().pow(.72)
        val prepared = prepare(voice, m, hz, velocity.toDouble(), config, options, model)
        // Model 3's generic-send ablation delays every escaped leaf to one causal collection
        // time. It must not read a post-junction signal before that junction has happened.
        val plan = if (model == MODEL_VERSION && options.genericSpill) prepared.copy(
            sites = prepared.sites.map { it.copy(delaySeconds = prepared.maxDelay) },
            leakage = prepared.leakage.map { it.copy(delay = prepared.maxDelay) },
        ) else prepared
        val junction = if (model == MODEL_VERSION) FilamentJunction(plan, voice, m, hz, options, highRate, captureDiagnostics) else null
        val collectorPlan = if (junction == null) plan else plan.copy(endpoints = emptyList(), leakage = emptyList())
        val network = Network(collectorPlan, hz, m.getValue("COLLECT").toDouble(), voice, model, highRate, captureDiagnostics)
        fun advance(input: Double) {
            if (junction == null) network.next(input) else {
                junction.next(input)
                network.fromJunction(input, junction.through, junction.escaped)
            }
        }
        fun snapshot(): DoubleArray {
            val output = network.state()
            return if (junction == null) output else output + junction.state()
        }
        val preparationNanos = System.nanoTime() - start
        val synthesisStart = System.nanoTime()
        var previous: FloatArray? = null
        var attempts = 0
        var converged = !loop
        var seam: Double? = null
        var fullCycleError: Double? = null
        var stateError: Double? = null
        var acceptedExchange: ExchangeDiagnostics? = null
        val output: List<FloatArray>
        var onsetSamples: FloatArray? = null
        var followingSamples = FloatArray(0)
        if (!loop) {
            val sourceSeconds = if (model == ORIGINAL_MODEL) when (voice) {
                FilamentVoice.THREAD -> 1.70 + 2.3 * m.getValue("HOLD")
                FilamentVoice.PULSE -> 1.55 + 2.2 * m.getValue("HOLD")
                FilamentVoice.CURL -> 2.05 + 2.5 * m.getValue("HOLD")
            } else when (voice) {
                FilamentVoice.THREAD -> 1.95 + 1.95 * m.getValue("HOLD")
                FilamentVoice.PULSE -> 1.85 + 1.8 * m.getValue("HOLD")
                FilamentVoice.CURL -> 2.35 + 1.7 * m.getValue("HOLD")
            }
            val sourceFrames = (sourceSeconds * highRate).roundToInt()
            val source = sourceTable(voice, m, hz, effort, velocity.toDouble(), sourceFrames, false, 0, model, config, highRate, cancelled)
            val total = ceil((sourceSeconds + plan.maxDelay + 1.8 * plan.haloT60 + .08) * highRate).toInt()
            val capture = Cycle(total + (oversampling - total % oversampling) % oversampling, captureDiagnostics)
            for (i in capture.raw.indices) {
                if (i and 1023 == 0) requireActive(cancelled)
                advance(if (i < source.size) source[i].toDouble() else 0.0)
                capture.capture(i, network)
            }
            if (captureDiagnostics) snapshot()
            output = capture.arrays().map {
                requireActive(cancelled)
                if (it.isEmpty()) it else decimate(it, oversampling).also { samples -> Dsp.fadeTail(samples) }
            }
        } else {
            val highFrames = periodFrames * oversampling
            val source = sourceTable(voice, m, hz, effort, velocity.toDouble(), highFrames, true, cycles, model, config, highRate, cancelled)
            val recent = ArrayList<Cycle>()
            val onset = if (retainOnset) ArrayList<FloatArray>() else null
            var previousState: DoubleArray? = null
            val minAttempts = max(4, ceil(plan.maxDelay * highRate / highFrames).toInt() + 3)
            while (attempts < MAX_LOOP_ATTEMPTS) {
                junction?.beginWindow("returned held cycle")
                val capture = Cycle(highFrames, captureDiagnostics)
                for (i in source.indices) {
                    if (i and 1023 == 0) requireActive(cancelled)
                    advance(source[i].toDouble()); capture.capture(i, network)
                }
                onset?.add(capture.raw)
                recent += capture
                if (recent.size > 3) recent.removeAt(0)
                attempts++
                val state = snapshot()
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
                    if (captureDiagnostics) acceptedExchange = junction?.diagnostics()
                    break
                }
            }
            check(converged) { "FILAMENT $voice MIDI $midi HOLD did not settle after $attempts periods" }
            // A following, independently rendered cycle supplies the resampler's right guard.
            val future = Cycle(highFrames, captureDiagnostics)
            for (i in source.indices) {
                if (i and 1023 == 0) requireActive(cancelled)
                advance(source[i].toDouble()); future.capture(i, network)
            }
            if (captureDiagnostics) snapshot()
            recent += future
            val channels = Array(5) { channel -> FloatArray(if (captureDiagnostics || channel == 4) highFrames * 4 else 0) }
            for (c in 0 until 4) for (s in 0 until 5) {
                val values = recent[c].arrays()[s]
                if (values.isNotEmpty()) values.copyInto(channels[s], c * highFrames)
            }
            val resampled = channels.map {
                requireActive(cancelled)
                if (it.isEmpty()) it else decimate(it, oversampling)
            }
            output = resampled.map { if (it.isEmpty()) it else it.copyOfRange(periodFrames * 2, periodFrames * 3) }
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
            if (onset != null) {
                // Keep the actual zero-state beginning of this same periodic drive. A future
                // cycle supplies the FIR's right guard; the retained note ends at the accepted
                // cycle, so its loop marker never glues different excitation gestures together.
                onset += future.raw
                if (continuationEvidence) {
                    val guard = FloatArray(highFrames)
                    for (i in source.indices) {
                        if (i and 1023 == 0) requireActive(cancelled)
                        advance(source[i].toDouble()); guard[i] = network.raw.toFloat()
                    }
                    onset += guard
                }
                val history = FloatArray(onset.size * highFrames)
                onset.forEachIndexed { index, values -> values.copyInto(history, index * highFrames) }
                requireActive(cancelled)
                val resampledOnset = decimate(history, oversampling)
                onsetSamples = resampledOnset.copyOf(attempts * periodFrames)
                if (continuationEvidence) followingSamples = resampledOnset.copyOfRange(
                    attempts * periodFrames, (attempts + 1) * periodFrames)
            }
        }
        val source = output[0]
        val through = output[1]
        val escaped = output[2]
        val spill = output[3]
        val raw = output[4]
        requireActive(cancelled)
        val samples = FloatArray(raw.size) { (raw[it] * OUTPUT_GAIN).toFloat() }
        previous = previous?.let { p -> FloatArray(p.size) { (p[it] * OUTPUT_GAIN).toFloat() } }
        val held = onsetSamples?.let { onset ->
            val note = KeyNote(Snip(FloatArray(onset.size) { (onset[it] * OUTPUT_GAIN).toFloat() },
                channels = 1, sampleRate = RATE), (attempts - 1L) * periodFrames)
            HeldNoteEvidence(note, requireNotNull(previous),
                FloatArray(followingSamples.size) { (followingSamples[it] * OUTPUT_GAIN).toFloat() },
                periodFrames, attempts, requireNotNull(stateError), requireNotNull(fullCycleError), hz)
        }
        check(samples.all { it.isFinite() }) { "FILAMENT produced a non-finite sample" }
        if (!captureDiagnostics) return RenderResult(samples, held = held)
        var peak = 0.0
        var square = 0.0
        var sum = 0.0
        for (v in raw) { peak = max(peak, abs(v.toDouble())); square += v.toDouble() * v; sum += v }
        val diagnostics = Diagnostics(plan.branches, plan.sites, plan.branches.sumOf { it.launch },
            plan.retained, plan.escaped, plan.absorbed, peak, sqrt(square / raw.size.coerceAtLeast(1)),
            sum / raw.size.coerceAtLeast(1), effort, loop, converged, seam, hz, periodFrames, attempts,
            max(network.maxState, junction?.maxState ?: 0.0), preparationNanos, System.nanoTime() - synthesisStart, fullCycleError, stateError,
            network.haloGainBound, network.haloNormalization, if (model == ORIGINAL_MODEL) emptyList() else network.haloModes,
            if (junction == null) "feed-forward splitter allocation" else "nominal no-exchange splitter allocation; actual waveform ledger is exchange",
            acceptedExchange ?: junction?.diagnostics())
        check(samples.all { it.isFinite() }) { "FILAMENT produced a non-finite sample" }
        return RenderResult(samples, Probe(samples, raw, source, through, escaped, spill, previous, diagnostics), held)
    }
}
