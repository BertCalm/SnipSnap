package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * OHM model 2 keeps the plucked foreground present while the companions enter.
 *
 * The initial displacement contains one smooth fundamental period beside the colored pick:
 * a seed may change the stroke without randomly removing its root. LENGTH sets that string's
 * 0.9..6.3 second T60, while the whistle's finite pressure is much quieter than in model 1.
 * Its octave remains an octave: this linear bridge does not convert it to the fundamental.
 *
 * Three running string ports still meet Strings.Bridge. The membrane's lowest mode stays
 * above 1.6 times the note, protecting the fundamental, and coupling is additionally limited
 * to spend at most 20% of the foreground's intrinsic round-trip logarithmic loss at its root.
 * This is a bound on fundamental loading, not a promise to preserve every upper partial.
 * Pitch compensation charges the actual membrane phase to each string. Every loop's own
 * feedback stays below one; no negative damping or feedback limiter replenishes lost energy.
 * Fixed coefficients are passive. GATHER changes only bounded, slow coupling and tuning;
 * its time-varying case is measured separately rather than inferred from the static proof.
 *
 * TOUCH changes displacement/pick balance, strike position, whistle attack/harmonics and
 * glass excitation. BODY places broad opposing cavity and bridge formants in the acoustic
 * readout. MEDDLING opens smoothly staged replies and changes whistle/glass balance.
 * COMMUNE progressively reduces independent radiation, closes pitch wander and opens the
 * reciprocal bridge. At exactly MEDDLING 0 all COMMUNE positions remain the same solo.
 *
 * STEADY reply onsets are fixed in seconds; LENGTH extends their release and source horizon
 * instead of merely stretching one identical sequence across a longer file. GATHER retains
 * an authored 0..10% solo, 10..28% entry and 43..72% communion trajectory. Its source onsets
 * are at 13, 28, 43, 58 and 70% of the file, with finite releases and natural remaining tails.
 */
object OhmV2 {
    const val MODEL_VERSION = 2
    const val ROOT_MIDI = 60
    const val MIDI_MIN = 48
    const val MIDI_MAX = 72

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("TOUCH", .5f), MacroSpec("BODY", .5f), MacroSpec("LENGTH", .65f),
        MacroSpec("MEDDLING", .55f), MacroSpec("COMMUNE", .45f),
    )

    private const val RATE = Dsp.RATE
    private const val INTERNAL_RATE = RATE * Dsp.OVERSAMPLE
    private const val CONTROL_FRAMES = Dsp.OVERSAMPLE * 44
    private const val PORTS = 3
    private const val MAX_COUPLING = .10f
    private const val REFERENCE_GAIN = .75f
    private const val LOG_1000 = 6.907755278982137
    private val STEADY_ONSETS = doubleArrayOf(.12, .52, .96, 1.42, 1.92)
    private val GATHER_ONSETS = doubleArrayOf(.13, .28, .43, .58, .70)

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    private fun settled(macros: Map<String, Float>): Map<String, Float> {
        val result = defaults().toMutableMap()
        for ((key, value) in macros) if (key in result) {
            require(value.isFinite()) { "OHM $key must be finite" }
            result[key] = value.coerceIn(0f, 1f)
        }
        return result
    }

    private fun smooth(x: Double): Double = x.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }

    internal fun relationship(progress: Double, meddling: Float, commune: Float, gesture: OhmGesture): Pair<Float, Float> {
        if (meddling == 0f) return 0f to 0f
        return when (gesture) {
            OhmGesture.STEADY -> meddling to commune
            OhmGesture.GATHER -> (meddling * smooth((progress - .10) / .18)).toFloat() to
                (commune * smooth((progress - .43) / .29)).toFloat()
        }
    }

    private fun bridgeTuned(hz: Float, coupling: Float, membrane: Strings.Membrane): Float {
        if (coupling == 0f) return hz
        val h = membrane.response(hz.toDouble())
        val k = 2.0 * coupling / PORTS
        val phase = atan2(-k * h[1], 1.0 - k * h[0])
        return (hz * 2 * PI / (2 * PI + phase)).toFloat()
    }

    /** Only reduce coupling, rather than trying to recover membrane losses with powered feedback. */
    private fun guardedCoupling(root: Float, t60: Float, membrane: Strings.Membrane): Float {
        val h = membrane.response(root.toDouble())
        val budget = .20 * LOG_1000 / (root * t60)
        fun cost(c: Double): Double {
            val k = 2 * c / PORTS
            return -ln(hypot(1 - k * h[0], -k * h[1]))
        }
        if (cost(MAX_COUPLING.toDouble()) <= budget) return MAX_COUPLING
        var low = 0.0
        var high = MAX_COUPLING.toDouble()
        repeat(24) {
            val mid = (low + high) / 2
            if (cost(mid) <= budget) low = mid else high = mid
        }
        return low.toFloat()
    }

    private fun feedback(hz: Float, loopHz: Float, t60: Float): Float {
        val w = 2 * PI * hz / INTERNAL_RATE
        val pole = exp(-2 * PI * loopHz / INTERNAL_RATE)
        val magnitude = (1 - pole) / sqrt(1 + pole * pole - 2 * pole * cos(w)) * cos(w / 2)
        return (exp(-LOG_1000 / (hz * t60)) / magnitude).toFloat().coerceAtMost(.9995f)
    }

    private fun loop(hz: Float, loopHz: Float, t60: Float): Strings.Loop {
        val tune = Strings.tune(hz * .96f, loopHz, INTERNAL_RATE)
        return Strings.Loop(tune.n, tune.a, feedback(hz, loopHz, t60), loopHz, INTERNAL_RATE)
    }

    /** Continuous reply staging: no hard on/off threshold and no common level rescaling. */
    private fun replyStrength(activity: Float, reply: Int): Float {
        if (activity <= 0f) return 0f
        val opening = when (reply) {
            0 -> 1.0
            1 -> smooth((activity - .05) / .30)
            2 -> smooth((activity - .25) / .40)
            3 -> smooth((activity - .50) / .35)
            else -> smooth((activity - .70) / .30)
        }
        return (activity.toDouble().pow(.85) * opening).toFloat()
    }

    internal data class Probe(
        val snip: Snip,
        val foreground: FloatArray,
        val whistle: FloatArray,
        val glass: FloatArray,
        val maxState: Float,
    )

    internal fun probe(
        macros: Map<String, Float> = emptyMap(), midi: Int = ROOT_MIDI, velocity: Float = 1f,
        seed: Int = 1, gesture: OhmGesture = OhmGesture.STEADY,
        companionExcitation: Boolean = true, recordPorts: Boolean = true,
    ): Probe {
        require(midi in MIDI_MIN..MIDI_MAX) { "OHM midi must be $MIDI_MIN..$MIDI_MAX: $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "OHM velocity must be 0..1: $velocity" }
        val m = settled(macros)
        val touch = m.getValue("TOUCH")
        val body = m.getValue("BODY")
        val length = m.getValue("LENGTH")
        val meddling = m.getValue("MEDDLING")
        val commune = m.getValue("COMMUNE")
        val seconds = 1.8 + 4.2 * length
        val frames = (seconds * RATE).toInt()
        val count = frames * Dsp.OVERSAMPLE
        if (velocity == 0f) {
            val taps = Array(PORTS) { FloatArray(if (recordPorts) frames else 0) }
            return Probe(Snip(FloatArray(frames), 1, RATE), taps[0], taps[1], taps[2], 0f)
        }

        val root = Keys.midiHz(midi)
        val frequencies = floatArrayOf(root, root * 2, root * 3)
        // A high loop corner permits the long fundamental decay without feedback above one.
        // Excitation and radiation still set the warm sound; upper partials decay faster naturally.
        val loopHz = floatArrayOf(root * (24 + 16 * touch), root * 24, root * 38)
        val decay = floatArrayOf(.9f + 5.4f * length, .24f + 1.25f * length, .18f + 1.0f * length)
        val loops = Array(PORTS) { loop(frequencies[it], loopHz[it], decay[it]) }
        val membrane = Strings.Membrane(INTERNAL_RATE)
        membrane.tune(
            floatArrayOf(root * (1.6f + .32f * (1 - body)), root * (3.4f + 1 - body), root * (6.2f + 1.6f * (1 - body))),
            floatArrayOf(12f + 3 * body, 17f + 4 * body, 23f + 6 * body),
            floatArrayOf(.40f, .32f, .20f),
        )
        val bridge = Strings.Bridge(PORTS, membrane)
        val couplingCeiling = guardedCoupling(root, decay[0], membrane)
        val primaryPeriod = (INTERNAL_RATE / root).roundToInt()
        val pickHz = Dsp.expMap(touch, root * 2, minOf(root * 34, 10_000f)) * (.65f + .35f * velocity)
        val primary = Strings.pluckExciter(
            Strings.tune(root, loopHz[0], INTERNAL_RATE).n, root, pickHz, .34f - .25f * touch,
            Dsp.seedFor("OHM", MODEL_VERSION, midi, seed, "pluck"), INTERNAL_RATE, count,
        )
        val glassBurst = Strings.pluckExciter(
            Strings.tune(root * 3, loopHz[2], INTERNAL_RATE).n, root * 3,
            Dsp.expMap(touch, root * 6, minOf(root * 45, 14_000f)), .24f - .16f * touch,
            Dsp.seedFor("OHM", MODEL_VERSION, midi, seed, "glass"), INTERNAL_RATE, count,
        )
        val onsets = if (gesture == OhmGesture.STEADY) STEADY_ONSETS.copyOf()
            else DoubleArray(GATHER_ONSETS.size) { GATHER_ONSETS[it] * seconds }
        val glassStarts = IntArray(onsets.size) { ((onsets[it] + .07) * INTERNAL_RATE).roundToInt() }
        val breathSeconds = if (gesture == OhmGesture.STEADY) .16 + .4 * length else .075 * seconds
        // At the normal .65 LENGTH the fifth reply finishes before release begins. Shorter
        // lengths still close the source earlier; long lengths leave the full natural tail.
        val sourceEnd = if (gesture == OhmGesture.STEADY) .40 + 3.0 * length else .82 * seconds
        val attackFraction = .34 - .26 * touch
        val random = Random(Dsp.seedFor("OHM", MODEL_VERSION, midi, seed, "breath"))
        var phase = random.nextDouble()
        val wanderPhase = random.nextDouble() * 2 * PI
        val glassColor = Array(2) { Dsp.Biquad() }
        for (j in glassColor.indices) {
            val hz = root * if (j == 0) 4.113f else 5.61f
            val q = (PI * hz * (.12 + .35 * length) / LOG_1000).toFloat()
            glassColor[j].bandpass(hz, q, INTERNAL_RATE)
        }
        // A small body's upper bridge formant and a larger cavity's lower formant trade places.
        // These are broad radiation shapes, not extra excitation or an effects rack.
        val cavity = Dsp.Biquad().also {
            it.peaking(root * (1.75f - .50f * body), -5f + 13 * body, .85f, INTERNAL_RATE)
        }
        val bridgeFormant = Dsp.Biquad().also {
            it.peaking(root * (4.8f - 1.5f * body), 5f - 10 * body, .9f, INTERNAL_RATE)
        }
        val outputLp = Dsp.OnePole(INTERNAL_RATE)
        val raw = FloatArray(count)
        val ports = if (recordPorts) Array(PORTS) { FloatArray(count) } else emptyArray()
        val reflected = FloatArray(PORTS)
        val back = FloatArray(PORTS)
        val voice = FloatArray(PORTS)
        var activity = 0f
        var agreement = 0f
        var coupling = 0f
        var whistleHz = root * 2f
        var maxState = 0f
        val sourceGain = velocity.pow(.65f)
        val primaryGain = .18f + .30f * touch
        val displacementGain = .045f - .017f * touch
        val whistleGain = .014f + .016f * touch
        val whistleLoss = (1 - exp(-LOG_1000 / (root * 2 * decay[1]))).toFloat()
        val replyGains = FloatArray(onsets.size)
        for (i in 0 until count) {
            if (i % 8192 == 0 && Thread.currentThread().isInterrupted) throw InterruptedException("OHM render cancelled")
            val progress = i.toDouble() / count
            val time = i.toDouble() / INTERNAL_RATE
            if (i % CONTROL_FRAMES == 0) {
                val relation = relationship(progress, meddling, commune, gesture)
                activity = relation.first
                agreement = relation.second
                coupling = couplingCeiling * activity * agreement
                val cents = (17 * sin(2 * PI * .71 * time + wanderPhase) +
                    5 * sin(2 * PI * 1.13 * time + .7 * wanderPhase)) * (1 - .97 * agreement)
                whistleHz = (root * 2 * 2.0.pow(cents / 1200)).toFloat()
                val glassHz = (root * 3 * 2.0.pow(-18.0 * (1 - agreement) / 1200)).toFloat()
                loops[0].retune(bridgeTuned(root, coupling, membrane))
                loops[1].retune(bridgeTuned(whistleHz, coupling, membrane))
                loops[2].retune(bridgeTuned(glassHz, coupling, membrane))
                for (j in replyGains.indices) replyGains[j] = replyStrength(activity, j)
            }
            for (j in 0 until PORTS) reflected[j] = loops[j].reflected()
            val radiated = bridge.couple(reflected, coupling, back)
            val sourceRelease = 1 - smooth((time - sourceEnd) / .12)
            var whistleInput = 0f
            var glassInput = 0f
            if (activity > 0f && companionExcitation && sourceRelease > 0) {
                var breath = 0.0
                for (j in onsets.indices) {
                    val age = (time - onsets[j]) / breathSeconds
                    if (age > 0 && age < 1) {
                        val opening = smooth(age / attackFraction)
                        val closing = 1 - smooth((age - .40) / .60)
                        breath += replyGains[j] * opening * closing
                    }
                    val struck = i - glassStarts[j]
                    if (struck in glassBurst.indices) {
                        glassInput += (.10f + .09f * touch) * replyGains[j] * activity.pow(.6f) * glassBurst[struck]
                    }
                }
                val tone = sin(2 * PI * phase) + (.025 + .20 * touch) * sin(4 * PI * phase)
                whistleInput = (whistleGain * breath * sourceRelease * whistleLoss * tone).toFloat()
                glassInput *= sourceRelease.toFloat()
            }
            phase += whistleHz / INTERNAL_RATE
            phase -= floor(phase)
            val displacement = if (i < primaryPeriod) displacementGain * sin(2 * PI * i / primaryPeriod).toFloat() else 0f
            val picked = if (i in primary.indices) primaryGain * primary[i] else 0f
            voice[0] = loops[0].inject(back[0] + displacement + picked)
            voice[1] = loops[1].inject(back[1] + whistleInput)
            voice[2] = loops[2].inject(back[2] + glassInput)
            var coloredGlass = voice[2]
            for (j in glassColor.indices) coloredGlass += (.22f + .28f * touch) * glassColor[j].process(voice[2])
            val independent = 1 - .80f * agreement
            val mixed = voice[0] + independent * (.55f * voice[1] + .68f * coloredGlass) + (.18f + .36f * agreement) * radiated
            val shaped = bridgeFormant.process(cavity.process(mixed))
            val warm = outputLp.lp(shaped, minOf(10_000f, root * (14 - 4 * body)))
            raw[i] = REFERENCE_GAIN * sourceGain * warm
            for (j in 0 until PORTS) {
                maxState = maxOf(maxState, abs(voice[j]))
                if (recordPorts) ports[j][i] = REFERENCE_GAIN * sourceGain * voice[j]
            }
            check(raw[i].isFinite() && maxState < 8f) { "OHM internal state lost its numerical headroom" }
        }
        val output = finish(raw)
        val taps = if (recordPorts) Array(PORTS) { finish(ports[it]) } else Array(PORTS) { FloatArray(0) }
        return Probe(Snip(output, 1, RATE), taps[0], taps[1], taps[2], maxState)
    }

    private fun finish(raw: FloatArray): FloatArray {
        val out = Dsp.decimate(raw, RATE)
        val dc = Dsp.OnePole(RATE)
        for (i in out.indices) out[i] -= dc.lp(out[i], 3f)
        Dsp.fadeTail(out, 120f, RATE)
        return out
    }

    fun renderRaw(
        macros: Map<String, Float> = emptyMap(), midi: Int = ROOT_MIDI, velocity: Float = 1f,
        seed: Int = 1, gesture: OhmGesture = OhmGesture.STEADY,
    ): Snip = probe(macros, midi, velocity, seed, gesture, recordPorts = false).snip

    fun render(
        macros: Map<String, Float> = emptyMap(), midi: Int = ROOT_MIDI, velocity: Float = 1f,
        seed: Int = 1, gesture: OhmGesture = OhmGesture.STEADY,
    ): Snip {
        val result = renderRaw(macros, midi, velocity, seed, gesture)
        Dsp.levelTo(result.samples, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        val playingGain = velocity.pow(.8f)
        for (i in result.samples.indices) result.samples[i] *= playingGain
        return result
    }
}
