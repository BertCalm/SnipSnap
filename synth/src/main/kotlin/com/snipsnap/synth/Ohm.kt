package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** STEADY keeps the relationship fixed; GATHER authors a finite solo, argument, then gathering. */
enum class OhmGesture { STEADY, GATHER }

/**
 * A dry, finite OHM-inspired experiment: one warm plucked string and two companions.
 * A wavering, sine-driven octave string is the whistle; a struck third-harmonic string,
 * heard through two quiet inharmonic resonances, is the glass. These are three running
 * waveguides, not completed engine recordings mixed together. At COMMUNE > 0 their
 * returning waves meet a shared passive membrane and are written back into all three
 * strings. A companion can therefore excite the foreground after its own pluck has died.
 *
 * MEDDLING controls the companions' finite breath/strike gestures. COMMUNE both closes
 * their pitch wander toward whole harmonics and opens the reciprocal bridge. At exactly
 * MEDDLING 0 the bridge is bypassed, sample for sample, whatever COMMUNE says.
 *
 * The bridge's fixed-coefficient scattering is contractive (Strings.Bridge). Its membrane
 * weights and poles never move during a note. Moving coupling is not covered by that
 * fixed-coefficient proof: here it is restricted to a slow, bounded 0..0.22 gesture and
 * measured before leveling. Loop feedback is strictly below one; the powered whistle
 * has finite, bounded excitation. There is no feedback limiter hiding an unstable core.
 *
 * GATHER's fractions refer to the complete 1.8..6 second file: 0..10% solo, MEDDLING
 * rises over 10..28%, argument over 28..43%, then COMMUNE rises over 43..72%. Macro
 * values are maxima. Breaths stop by 76% and the last glass strike is at 71%, leaving
 * the remaining time for natural decay; the final 120 ms is a quiet endpoint safeguard.
 * No HOLD/loop, reverb, captured source, bow, or interaction between separately played pads.
 */
object Ohm {
    const val MODEL_VERSION = 1
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
    private const val MAX_COUPLING = .22f
    /** One fixed gain for every raw render, not a per-patch normalization. */
    private const val REFERENCE_GAIN = .75f
    private const val LOG_1000 = 6.907755278982137

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    private fun settled(macros: Map<String, Float>): Map<String, Float> {
        val result = defaults().toMutableMap()
        for ((key, value) in macros) if (key in result) {
            require(value.isFinite()) { "OHM $key must be finite" }
            result[key] = value.coerceIn(0f, 1f)
        }
        return result
    }

    private fun smooth(x: Double): Double = x.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }

    /** Actual applied macro trajectories, also used by the audition's causal comparisons. */
    internal fun relationship(progress: Double, meddling: Float, commune: Float, gesture: OhmGesture): Pair<Float, Float> {
        if (meddling == 0f) return 0f to 0f
        return when (gesture) {
            OhmGesture.STEADY -> meddling to commune
            OhmGesture.GATHER -> (meddling * smooth((progress - .10) / .18)).toFloat() to
                (commune * smooth((progress - .43) / .29)).toFloat()
        }
    }

    /** The diagonal bridge phase is part of the string's round trip, just as its loop LP is. */
    private fun bridgeTuned(hz: Float, coupling: Float, membrane: Strings.Membrane): Float {
        if (coupling == 0f) return hz
        val h = membrane.response(hz.toDouble())
        val k = 2.0 * coupling / PORTS
        val phase = atan2(-k * h[1], 1.0 - k * h[0])
        return (hz * 2.0 * PI / (2.0 * PI + phase)).toFloat()
    }

    /** Match a finite T60 while accounting for the loop LP's magnitude, with passive headroom. */
    private fun feedback(hz: Float, loopHz: Float, t60: Float): Float {
        val w = 2.0 * PI * hz / INTERNAL_RATE
        val pole = exp(-2.0 * PI * loopHz / INTERNAL_RATE)
        val magnitude = (1 - pole) / sqrt(1 + pole * pole - 2 * pole * cos(w)) * cos(w / 2)
        return (exp(-LOG_1000 / (hz * t60)) / magnitude).toFloat().coerceAtMost(.9995f)
    }

    private fun loop(hz: Float, loopHz: Float, t60: Float): Strings.Loop {
        // All later tuning targets are higher; the delay line never needs to grow during retune.
        val tune = Strings.tune(hz * .96f, loopHz, INTERNAL_RATE)
        return Strings.Loop(tune.n, tune.a, feedback(hz, loopHz, t60), loopHz, INTERNAL_RATE)
    }

    private fun breath(progress: Double): Float {
        var value = 0.0
        for (start in BREATH_STARTS) {
            val age = (progress - start) / .14
            if (age > 0 && age < 1) value += sin(PI * age).pow(2)
        }
        return value.toFloat()
    }

    private val BREATH_STARTS = doubleArrayOf(.06, .32, .62)
    private val GLASS_STARTS = doubleArrayOf(.12, .35, .58, .71)

    /** Unleveled output and separately conditioned port readouts, not independently normalized stems. */
    internal data class Probe(
        val snip: Snip,
        val foreground: FloatArray,
        val whistle: FloatArray,
        val glass: FloatArray,
        val maxState: Float,
    )

    /**
     * companionExcitation=false silences only the companions' external sources. Their bridge
     * ports, pitch compensation and output readouts remain: a causal experiment can ask whether
     * their sources changed the foreground without confusing that with an output layer mute.
     */
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
            val silent = FloatArray(frames)
            return Probe(Snip(silent, 1, RATE), FloatArray(0), FloatArray(0), FloatArray(0), 0f)
        }

        val root = Keys.midiHz(midi)
        val frequencies = floatArrayOf(root, root * 2, root * 3)
        val loopHz = floatArrayOf(root * (12 + 8 * touch), root * 24, root * 35)
        val decay = floatArrayOf(.45f + 1.6f * length, .30f + .70f * length, .30f + .65f * length)
        val loops = Array(PORTS) { loop(frequencies[it], loopHz[it], decay[it]) }
        val membrane = Strings.Membrane(INTERNAL_RATE)
        val size = 1.25f - .49f * body
        membrane.tune(
            floatArrayOf(root * .72f * size, root * 2.35f * size, root * 4.7f * size),
            floatArrayOf(3.5f + 2 * body, 6f + 5 * body, 8f + 6 * body),
            floatArrayOf(.43f, .29f, .18f),
        )
        val bridge = Strings.Bridge(PORTS, membrane)
        val pickHz = Dsp.expMap(touch, 900f, 6500f) * (.65f + .35f * velocity)
        val primary = Strings.pluckExciter(
            Strings.tune(root, loopHz[0], INTERNAL_RATE).n, root, pickHz, .20f,
            Dsp.seedFor("OHM", MODEL_VERSION, midi, seed, "pluck"), INTERNAL_RATE, count,
        )
        val glassBurst = Strings.pluckExciter(
            Strings.tune(root * 3, loopHz[2], INTERNAL_RATE).n, root * 3, 11_000f, .12f,
            Dsp.seedFor("OHM", MODEL_VERSION, midi, seed, "glass"), INTERNAL_RATE, count,
        )
        val glassFrames = GLASS_STARTS.map { (it * count).toInt() }
        val random = Random(Dsp.seedFor("OHM", MODEL_VERSION, midi, seed, "breath"))
        var phase = random.nextDouble()
        val wanderPhase = random.nextDouble() * 2 * PI
        val glassColor = Array(2) { Dsp.Biquad() }
        for (j in glassColor.indices) {
            val hz = root * if (j == 0) 4.113f else 5.61f
            val q = (PI * hz * (.18 + .20 * length) / ln(1000.0)).toFloat()
            glassColor[j].bandpass(hz, q, INTERNAL_RATE)
        }
        val raw = FloatArray(count)
        val ports = if (recordPorts) Array(PORTS) { FloatArray(count) } else emptyArray()
        val reflected = FloatArray(PORTS)
        val back = FloatArray(PORTS)
        val voice = FloatArray(PORTS)
        val outputLp = Dsp.OnePole(INTERNAL_RATE)
        var activity = 0f
        var agreement = 0f
        var coupling = 0f
        var whistleHz = root * 2f
        var maxState = 0f
        val sourceGain = velocity.pow(.65f)
        val primaryGain = .13f + .07f * touch
        val whistleLoss = (1.0 - exp(-LOG_1000 / (root * 2 * decay[1]))).toFloat()
        for (i in 0 until count) {
            if (i % 8192 == 0 && Thread.currentThread().isInterrupted) throw InterruptedException("OHM render cancelled")
            val progress = i.toDouble() / count
            if (i % CONTROL_FRAMES == 0) {
                val relation = relationship(progress, meddling, commune, gesture)
                activity = relation.first
                agreement = relation.second
                coupling = MAX_COUPLING * activity * agreement
                val secondsNow = i.toDouble() / INTERNAL_RATE
                val cents = (13.0 * sin(2 * PI * .71 * secondsNow + wanderPhase) +
                    4.0 * sin(2 * PI * 1.13 * secondsNow + .7 * wanderPhase)) * (1 - .96 * agreement)
                whistleHz = (root * 2 * 2.0.pow(cents / 1200)).toFloat()
                val glassHz = (root * 3 * 2.0.pow(-9.0 * (1 - agreement) / 1200)).toFloat()
                loops[0].retune(bridgeTuned(root, coupling, membrane))
                loops[1].retune(bridgeTuned(whistleHz, coupling, membrane))
                loops[2].retune(bridgeTuned(glassHz, coupling, membrane))
            }
            for (j in 0 until PORTS) reflected[j] = loops[j].reflected()
            val radiated = bridge.couple(reflected, coupling, back)
            var whistleInput = 0f
            var glassInput = 0f
            if (activity > 0f && companionExcitation) {
                // Bounded finite sine pressure drives the actual whistle string, not a parallel output sine.
                whistleInput = .105f * activity * breath(progress) * whistleLoss * sin(2 * PI * phase).toFloat()
                for (start in glassFrames) {
                    val age = i - start
                    if (age in glassBurst.indices) glassInput += .085f * activity * glassBurst[age]
                }
            }
            phase += whistleHz / INTERNAL_RATE
            phase -= kotlin.math.floor(phase)
            voice[0] = loops[0].inject(back[0] + if (i in primary.indices) primaryGain * primary[i] else 0f)
            voice[1] = loops[1].inject(back[1] + whistleInput)
            voice[2] = loops[2].inject(back[2] + glassInput)
            var coloredGlass = voice[2]
            for (j in glassColor.indices) coloredGlass += .18f * glassColor[j].process(voice[2])
            // In communion the companions mostly enter through the common string/body; their
            // independent readout recedes while their excitation and reciprocal ports remain active.
            val independent = 1f - .8f * agreement
            val mixed = voice[0] + independent * (.48f * voice[1] + .60f * coloredGlass) + (.20f + .65f * body) * radiated
            // BODY changes the instrument's radiation spectrum, independent of any final level match.
            val warm = outputLp.lp(mixed, root * (11 - 7 * body))
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

    /** Linear conditioning only: every raw recording retains the same reference gain. */
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
        // Level the whole instrument once, then restore explicit playing dynamics.
        val playingGain = velocity.pow(.8f)
        for (i in result.samples.indices) result.samples[i] *= playingGain
        return result
    }
}
