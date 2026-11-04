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
 * Listening study derived from OhmV2 at SnipSnap commit 482c55a146b1569ba9ccad27e4ebd274ad398df4.
 * That renderer is unchanged from the auditioned 3b217d1c model-2 source.
 *
 * This object belongs only to the ohmReplyRingStudy source set. It is not a production model,
 * patch, factory recipe, GATHER gesture or held instrument. The primary displacement, source
 * seeds, physical string ports, bridge, root-loading guard and acoustic readout come from
 * model 2. The common seven-second file leaves room for the latest answer and its natural tail.
 *
 * REPLY compresses or spreads the existing five companion entries around model 2's schedule.
 * Its minimum 100 ms gap keeps the glass strokes separate. The whistle uses a smooth bounded
 * union of its pressure envelopes, rather than adding coherent overlapping pressure: this is
 * an experimental envelope law, not a loudness normalization or an extra sound layer.
 *
 * RING sets only the two companions' passive T60. Its midpoint reproduces model 2's side-port
 * damping at LENGTH .65, while LENGTH still controls the primary, breath length and minimum
 * source horizon. Whistle pressure uses a fixed accepted-midpoint conversion, so RING changes
 * passive loss without also changing the finite excitation. No per-note normalization is used.
 * The reported T60 values are mapping targets: the inherited .9995 feedback ceiling can make
 * actual high-register tails shorter, especially at the top of RING.
 * Changing companion damping can legitimately change a coupled primary through the bridge;
 * at COMMUNE zero, the primary is independent of both study controls.
 *
 * All constants shaping the two new controls are proposed and require listening. No model
 * number or production API is introduced, and model 2's seeds deliberately remain unchanged.
 */
object OhmReplyRingStudy {
    private const val SEED_MODEL = 2
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

    private fun ringDecay(value: Float, low: Float, middle: Float, high: Float): Float = when {
        value == 0f -> low
        value == .5f -> middle
        value == 1f -> high
        value < .5f -> (low * (middle / low).toDouble().pow(2.0 * value)).toFloat()
        else -> (middle * (high / middle).toDouble().pow(2.0 * value - 1.0)).toFloat()
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
        return Strings.Loop(tune.n, tune.a, feedback(hz, loopHz, t60), loopHz, INTERNAL_RATE, cacheLoopFilter = true)
    }

    /** Continuous reply staging: no hard on/off threshold and no common level rescaling. */
    private fun replyStrength(activity: Float, reply: Int, drive: Double): Float {
        if (activity <= 0f) return 0f
        val opening = when (reply) {
            0 -> 1.0
            1 -> smooth((activity - .05) / .30)
            2 -> smooth((activity - .25) / .40)
            3 -> smooth((activity - .50) / .35)
            else -> smooth((activity - .70) / .30)
        }
        return (drive * opening).toFloat()
    }

    data class Probe(
        val snip: Snip,
        val foreground: FloatArray,
        val whistle: FloatArray,
        val glass: FloatArray,
        val maxState: Float,
        val onsets: DoubleArray,
        /** Requested passive T60; the inherited feedback ceiling may shorten the actual tail. */
        val whistleT60: Float,
        /** Requested passive T60; the inherited feedback ceiling may shorten the actual tail. */
        val glassT60: Float,
        /** Maximum bounded whistle pressure envelope, before source gain and sinusoid. */
        val pressurePeak: Float,
        /** Release begins only after every finite pressure pulse and glass stroke finishes. */
        val sourceEndSeconds: Double,
    )

    fun probe(
        macros: Map<String, Float> = emptyMap(), midi: Int = ROOT_MIDI, velocity: Float = .8f,
        seed: Int = 731, reply: Float = .5f, ring: Float = .5f,
        companionExcitation: Boolean = true, recordPorts: Boolean = true,
    ): Probe {
        require(midi in MIDI_MIN..MIDI_MAX) { "OHM midi must be $MIDI_MIN..$MIDI_MAX: $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "OHM velocity must be 0..1: $velocity" }
        require(reply.isFinite() && reply in 0f..1f) { "OHM study REPLY must be 0..1: $reply" }
        require(ring.isFinite() && ring in 0f..1f) { "OHM study RING must be 0..1: $ring" }
        val m = settled(macros)
        val touch = m.getValue("TOUCH")
        val body = m.getValue("BODY")
        val length = m.getValue("LENGTH")
        val meddling = m.getValue("MEDDLING")
        val commune = m.getValue("COMMUNE")
        val seconds = 7.0
        val onsets = DoubleArray(STEADY_ONSETS.size) {
            (.06 + .12 * reply) + (.25 + 1.5 * reply) * (STEADY_ONSETS[it] - .12)
        }
        val glassDelay = .035 + .07 * reply
        val breathSeconds = .16 + .4 * length
        val whistleT60 = ringDecay(ring, .18f, 1.0525f, 2.8f)
        val glassT60 = ringDecay(ring, .12f, .83f, 2.2f)
        // Preserve LENGTH's old minimum horizon, but never truncate one of the five answers.
        // At MIDI48 and above the glass excitation is shorter than this conservative 25 ms.
        val sourceEnd = maxOf(.40 + 3.0 * length, onsets.last() + breathSeconds,
            onsets.last() + glassDelay + .025)
        val frames = (seconds * RATE).toInt()
        val count = frames * Dsp.OVERSAMPLE
        if (velocity == 0f) {
            val taps = Array(PORTS) { FloatArray(if (recordPorts) frames else 0) }
            return Probe(Snip(FloatArray(frames), 1, RATE), taps[0], taps[1], taps[2], 0f,
                onsets, whistleT60, glassT60, 0f, sourceEnd)
        }

        val root = Keys.midiHz(midi)
        val frequencies = floatArrayOf(root, root * 2, root * 3)
        // A high loop corner permits the long fundamental decay without feedback above one.
        // Excitation and radiation still set the warm sound; upper partials decay faster naturally.
        val loopHz = floatArrayOf(root * (24 + 16 * touch), root * 24, root * 38)
        val decay = floatArrayOf(.9f + 5.4f * length, whistleT60, glassT60)
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
            Dsp.seedFor("OHM", SEED_MODEL, midi, seed, "pluck"), INTERNAL_RATE, count,
        )
        val glassBurst = Strings.pluckExciter(
            Strings.tune(root * 3, loopHz[2], INTERNAL_RATE).n, root * 3,
            Dsp.expMap(touch, root * 6, minOf(root * 45, 14_000f)), .24f - .16f * touch,
            Dsp.seedFor("OHM", SEED_MODEL, midi, seed, "glass"), INTERNAL_RATE, count,
        )
        val glassStarts = IntArray(onsets.size) { ((onsets[it] + glassDelay) * INTERNAL_RATE).roundToInt() }
        check(glassBurst.size <= (.025 * INTERNAL_RATE).toInt()) {
            "OHM study glass stroke exceeded its declared finite source support"
        }
        val attackFraction = .34 - .26 * touch
        val random = Random(Dsp.seedFor("OHM", SEED_MODEL, midi, seed, "breath"))
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
        val outputLp = Dsp.FixedOnePole(minOf(10_000f, root * (14 - 4 * body)), INTERNAL_RATE)
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
        var pressurePeak = 0f
        val sourceGain = velocity.pow(.65f)
        val primaryGain = .18f + .30f * touch
        val displacementGain = .045f - .017f * touch
        val whistleGain = .014f + .016f * touch
        val glassGain = .10f + .09f * touch
        val whistleOvertone = .025 + .20 * touch
        val glassColorGain = .22f + .28f * touch
        // Keep model 2's LENGTH .65 pressure calibration fixed while RING changes passive loss.
        // Recomputing this from the variable T60 would turn RING into an opposing source-gain knob.
        val whistleLoss = (1 - exp(-LOG_1000 / (root * 2 * 1.0525f))).toFloat()
        val replyGains = FloatArray(onsets.size)
        var glassActivity = 0f
        val tuningTargets = FloatArray(PORTS) { Float.NaN }
        for (i in 0 until count) {
            if (i % 8192 == 0 && Thread.currentThread().isInterrupted) throw InterruptedException("OHM render cancelled")
            val time = i.toDouble() / INTERNAL_RATE
            if (i % CONTROL_FRAMES == 0) {
                activity = meddling
                agreement = if (meddling == 0f) 0f else commune
                coupling = couplingCeiling * activity * agreement
                val cents = (17 * sin(2 * PI * .71 * time + wanderPhase) +
                    5 * sin(2 * PI * 1.13 * time + .7 * wanderPhase)) * (1 - .97 * agreement)
                whistleHz = (root * 2 * 2.0.pow(cents / 1200)).toFloat()
                val glassHz = (root * 3 * 2.0.pow(-18.0 * (1 - agreement) / 1200)).toFloat()
                // Retuning changes only delay coefficients, not their state. Repeating an
                // identical target recalculates the same budget and allocates another Tuning.
                val rootTarget = bridgeTuned(root, coupling, membrane)
                val whistleTarget = bridgeTuned(whistleHz, coupling, membrane)
                val glassTarget = bridgeTuned(glassHz, coupling, membrane)
                if (rootTarget != tuningTargets[0]) { loops[0].retune(rootTarget); tuningTargets[0] = rootTarget }
                if (whistleTarget != tuningTargets[1]) { loops[1].retune(whistleTarget); tuningTargets[1] = whistleTarget }
                if (glassTarget != tuningTargets[2]) { loops[2].retune(glassTarget); tuningTargets[2] = glassTarget }
                val replyDrive = if (activity > 0f) activity.toDouble().pow(.85) else 0.0
                for (j in replyGains.indices) replyGains[j] = replyStrength(activity, j, replyDrive)
                glassActivity = activity.pow(.6f)
            }
            for (j in 0 until PORTS) reflected[j] = loops[j].reflected()
            val radiated = bridge.couple(reflected, coupling, back)
            val sourceRelease = 1 - smooth((time - sourceEnd) / .12)
            var whistleInput = 0f
            var glassInput = 0f
            if (activity > 0f && companionExcitation && sourceRelease > 0) {
                // Each factor is in [0,1]. The union preserves a hard pressure budget without
                // normalizing the audio or changing the number/strength of glass strokes.
                var unoccupied = 1.0
                for (j in onsets.indices) {
                    val age = (time - onsets[j]) / breathSeconds
                    if (age > 0 && age < 1) {
                        val opening = smooth(age / attackFraction)
                        val closing = 1 - smooth((age - .40) / .60)
                        unoccupied *= 1.0 - replyGains[j] * opening * closing
                    }
                    val struck = i - glassStarts[j]
                    if (struck in glassBurst.indices) {
                        glassInput += glassGain * replyGains[j] * glassActivity * glassBurst[struck]
                    }
                }
                val breath = 1.0 - unoccupied
                pressurePeak = maxOf(pressurePeak, breath.toFloat())
                val tone = sin(2 * PI * phase) + whistleOvertone * sin(4 * PI * phase)
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
            for (j in glassColor.indices) coloredGlass += glassColorGain * glassColor[j].process(voice[2])
            val independent = 1 - .80f * agreement
            val mixed = voice[0] + independent * (.55f * voice[1] + .68f * coloredGlass) + (.18f + .36f * agreement) * radiated
            val shaped = bridgeFormant.process(cavity.process(mixed))
            val warm = outputLp.lp(shaped)
            raw[i] = REFERENCE_GAIN * sourceGain * warm
            for (j in 0 until PORTS) {
                maxState = maxOf(maxState, abs(voice[j]))
                if (recordPorts) ports[j][i] = REFERENCE_GAIN * sourceGain * voice[j]
            }
            check(raw[i].isFinite() && maxState < 8f) { "OHM internal state lost its numerical headroom" }
        }
        val output = finish(raw)
        val taps = if (recordPorts) Array(PORTS) { finish(ports[it]) } else Array(PORTS) { FloatArray(0) }
        return Probe(Snip(output, 1, RATE), taps[0], taps[1], taps[2], maxState,
            onsets, whistleT60, glassT60, pressurePeak, sourceEnd)
    }

    private fun finish(raw: FloatArray): FloatArray {
        val out = Dsp.decimate(raw, RATE)
        val dc = Dsp.FixedOnePole(3f, RATE)
        for (i in out.indices) out[i] -= dc.lp(out[i])
        Dsp.fadeTail(out, 120f, RATE)
        return out
    }

    fun renderRaw(
        macros: Map<String, Float> = emptyMap(), midi: Int = ROOT_MIDI, velocity: Float = .8f,
        seed: Int = 731, reply: Float = .5f, ring: Float = .5f,
        companionExcitation: Boolean = true,
    ): Snip = probe(macros, midi, velocity, seed, reply, ring, companionExcitation,
        recordPorts = false).snip
}
