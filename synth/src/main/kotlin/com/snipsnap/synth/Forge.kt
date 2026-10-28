package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * FORGE - a hit formed against a die. The owner's hydroforming idea
 * (docs/superpowers/specs/2026-10-25-forge-section-design-brief.md) as a rack
 * section, so it works on a synthesized drum and on a captured chop alike.
 *
 * Four stages, each a plain DSP block with a physical name for what it does to
 * the hit, in order:
 *
 * 1. **The charge** (CHARGE, STANDOFF): a pressure front (a 0.1 ms rise into a
 *    0.5 ms decay, the broadband edge) over a 48 Hz decaying sine (the weight),
 *    added to the pad [STANDOFF_MAX_SECONDS] or less after it starts.
 * 2. **The medium** (CHARGE): up to [MAX_SECTIONS] second-order all-passes.
 *    They do not focus the hit (no all-pass can; it only delays each frequency
 *    differently, the brief's finding 1): they turn the click into a short
 *    rising sweep, which is what makes the folder below thick rather than thin.
 * 3. **The plastic flow** (YIELD): a slew detector reads how fast the signal is
 *    moving against this pad's own fastest moment. Where it moves faster than
 *    YIELD's threshold the material "flows": it is driven up to
 *    `1 + FOLD_DRIVE` times harder into a sine folder. Slow material passes
 *    untouched to the die. Changing the curve alone did nothing on a kick (the
 *    click is quiet under the body); the flow has to add drive (finding 4).
 * 4. **The die** (ARREST): a ceiling that closes from above the blank (1.25 of
 *    the peak, nothing touched) to 0.4 of it, its knee hardening from soft to
 *    a clamp as it closes. What the die removes is "heat": its envelope drives
 *    a short burst of band-limited noise, so a hard stop leaves a flash of
 *    grit at the moment of impact.
 *
 * No gain envelope anywhere (no attack, no release): nothing pumps and nothing
 * recovers, which is the brief's "zero springback". Level-relative like VALVE:
 * the pad is normalised to peak 1 going in and peak-matched coming out, so
 * FORGE changes the shape of the hit, never its peak. What ARREST lowers is the
 * crest factor (peak over RMS), and that is what reads as louder once a kit
 * levels the pad.
 *
 * At every neutral (CHARGE 0, STANDOFF 0, YIELD 1, ARREST 0) every stage is an
 * identity and only the 4x round trip and the 5 Hz DC blocker are left.
 *
 * Oversampled the way [Valve] is: [Valve.upsample] up, the stages at 4x,
 * [Tide.bandLimit] and [Dsp.decimate] down. The folder and the die both make
 * harmonics; the brief's prototype measured a triangle folder that aliased at
 * 4x, which is why the folder here is a sine (an entire function, so its
 * harmonics fall away instead of a corner's 1/k^2). [Valve]'s note on the
 * absolute 19.5 kHz corner applies here too: the round trip assumes 44.1 kHz.
 *
 * Every constant marked shape is a listening value waiting for the owner's
 * gate; none has been heard.
 */
object Forge {

    val MACROS: List<MacroSpec> = listOf(
        // Shock amplitude and the medium's depth together; 0 is no shock and no medium.
        MacroSpec("CHARGE", 0.5f),
        // Where the shock lands after the hit, 0 to 1 ms; inert at CHARGE 0 (there is no shock).
        MacroSpec("STANDOFF", 0.5f),
        // How fast material must move to flow, as a share of this pad's fastest moment.
        // 1 is rigid: nothing flows and the shaper is an identity, so AMT fades toward it.
        MacroSpec("YIELD", 0.5f, neutral = 1f),
        // The die: 0 stands above the blank and touches nothing; 1 is a hard stop at 0.4 of the peak.
        MacroSpec("ARREST", 0.5f),
    )

    /** The shock at CHARGE 1, re the pad's peak (shape). */
    internal const val SHOCK_MAX = 1.2f

    /** The shock's weight: a decaying sine at this pitch (shape: under every kick's body, over the 20 Hz blocker). */
    internal const val SHOCK_HZ = 48f

    /** Its decay to -60 dB (shape), shortened to the pad's own length on a short pad so the end never cuts a ringing sine. */
    internal const val SHOCK_T60_SECONDS = 0.25f

    /** The pressure front: a raised-cosine rise and an exponential fall (shape: energy to about 10 kHz). */
    private const val FRONT_RISE_SECONDS = 1.0e-4f
    private const val FRONT_DECAY_SECONDS = 5.0e-4f
    private const val FRONT_SHARE = 0.55f

    /** STANDOFF 1's delay. The brief's "sub-millisecond" (shape). */
    internal const val STANDOFF_MAX_SECONDS = 1.0e-3f

    /**
     * The medium's shape: how many second-order all-passes at CHARGE 1, the band their centres span,
     * and their Q. Only [SHIPPED] reaches a pad; the others exist so a tuning audition can render
     * candidates through the real section without a second copy of it.
     */
    internal data class Medium(val sections: Int, val loHz: Float, val hiHz: Float, val q: Float) {
        companion object {
            /** As heard at the gate (shape; the prototype's 24 spread a 0.2 ms click to 12.5 ms). */
            val SHIPPED = Medium(24, 300f, 6_000f, 0.7f)
        }
    }

    /** The medium at CHARGE 1: this many second-order all-passes. */
    internal val MAX_SECTIONS = Medium.SHIPPED.sections

    /** The slew detector's smoother (shape): fast enough to see a click, slow enough not to read every sample's wiggle. */
    internal const val DETECTOR_HZ = 2_000f

    /** YIELD's threshold, a share of the pad's peak slew: [YIELD_MIN_SHARE] at YIELD 0, all of it at YIELD 1. */
    internal const val YIELD_MIN_SHARE = 0.02f

    /** One "width" of the flow's onset: slew this many times the threshold is fully plastic (shape). */
    private const val FLOW_WIDTH = 3.0

    /** Fully plastic material is driven this much harder into the folder (shape; the prototype's 3). */
    internal const val FOLD_DRIVE = 3f

    /** The die's ceiling, re the normalised peak: above the blank at ARREST 0, closed at 1 (shape). */
    internal const val CEILING_OPEN = 1.25f
    internal const val CEILING_CLOSED = 0.4f

    /** Where the die's curve leaves the straight line, as a share of the ceiling: soft at ARREST 0, a clamp at 1 (shape). */
    internal const val KNEE_OPEN = 0.8f

    /** The heat: a band-limited noise burst following what the die removes (shape). */
    internal const val HEAT_GAIN = 0.35f
    private const val HEAT_FOLLOW_SECONDS = 5.0e-4f
    internal const val HEAT_LO_HZ = 3_000f
    internal const val HEAT_HI_HZ = 14_000f

    /** The DC blocker after the shaper and the die, VALVE's 5 Hz: the front and an asymmetric fold both leave an offset. */
    private const val DC_HZ = 5f

    /** The end fade when the medium or the shock is on, ms: an all-pass chain delays a pad's last milliseconds past its end. */
    private const val END_FADE_MS = 4f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: Random): Map<String, Float> = MACROS.associate { it.name to random.nextFloat() }

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip = process(snip, macros, oversample = true)

    /**
     * [oversample] false runs the stages at the snip's own rate: for the aliasing test, which proves its
     * probe sees fold-back, and the audition's fold-back A/B. The product always runs the 4x path with the
     * [Medium.SHIPPED] medium; [medium] is for the tuning audition's candidates.
     */
    internal fun process(snip: Snip, macros: Map<String, Float>, oversample: Boolean, medium: Medium = Medium.SHIPPED): Snip {
        val m = settled(macros)
        val inPeak = snip.peak()
        if (inPeak <= 0f || snip.frameCount == 0) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val rate = snip.sampleRate
        val channels = snip.channels
        val frames = snip.frameCount
        val workRate = if (oversample) rate * Dsp.OVERSAMPLE else rate
        val charge = m.getValue("CHARGE")

        // Up, charge and medium per channel. Every normalisation is one gain for all the channels (the
        // pad's own peak, then the loudest channel's after each stage), so a quieter channel stays quieter;
        // the detector's reference is likewise the loudest channel's fastest moment.
        var formed = Array(channels) { ch ->
            val x = FloatArray(frames) { f -> snip.samples[f * channels + ch] / inPeak }
            if (oversample) Valve.upsample(x, rate) else x
        }
        if (charge > 0f) {
            formed = Array(channels) { charged(formed[it], m, workRate) }
            normaliseTogether(formed)
            formed = Array(channels) { medium(formed[it], charge, workRate, medium) }
            normaliseTogether(formed)
        }
        val slews = Array(channels) { slew(formed[it], workRate) }
        var peakSlew = 0f
        for (s in slews) for (v in s) if (v > peakSlew) peakSlew = v

        val outs = Array(channels) { ch ->
            val flow = flowState(slews[ch], peakSlew, m.getValue("YIELD"))
            val y = shape(formed[ch], flow)
            die(y, m.getValue("ARREST"), workRate, HEAT_SEED)
            val dc = Dsp.FixedOnePole(DC_HZ, workRate)
            for (i in y.indices) y[i] = y[i] - dc.lp(y[i])
            if (oversample) {
                Tide.bandLimit(y, workRate)
                Dsp.decimate(y, rate)
            } else {
                y
            }
        }

        val out = FloatArray(frames * channels)
        for (f in 0 until frames) {
            for (ch in 0 until channels) out[f * channels + ch] = outs[ch].getOrElse(f) { 0f }
        }
        if (charge > 0f) Dsp.fadeTail(out, ms = END_FADE_MS, rate = rate, channels = channels)
        var outPeak = 0f
        for (v in out) {
            val a = abs(v)
            if (a > outPeak) outPeak = a
        }
        if (outPeak > 0f) {
            val k = inPeak / outPeak
            for (i in out.indices) out[i] *= k
        }
        return Snip(out, channels, rate)
    }

    /** Defaults with [macros] laid over: unknown keys dropped, values clamped, a non-finite value left at its default. */
    internal fun settled(macros: Map<String, Float>): Map<String, Float> {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k) && v.isFinite()) m[k] = v.coerceIn(0f, 1f)
        return m
    }

    /**
     * The heat's noise is one fixed take, the same on every pad, every channel and every setting: a knob
     * then changes only what the knob changes (a macro-seeded take reshuffled the grit on every turn, and
     * the brief's prototype let an unknown key move it), and identical channels stay identical.
     */
    internal val HEAT_SEED: Int = Dsp.seedFor("FORGE", "heat")

    // ---------- 1. the charge ----------

    /** [x] (normalised) plus the shock; the caller re-normalises every channel together. A copy at CHARGE 0. */
    internal fun charged(x: FloatArray, m: Map<String, Float>, rate: Int): FloatArray {
        val amp = m.getValue("CHARGE") * SHOCK_MAX
        if (amp <= 0f) return x
        val out = x.copyOf()
        val delay = (m.getValue("STANDOFF") * STANDOFF_MAX_SECONDS * rate).roundToInt()
        val t60 = min(SHOCK_T60_SECONDS, x.size.toFloat() / rate)
        val riseFrames = FRONT_RISE_SECONDS * rate
        for (i in delay until out.size) {
            val n = i - delay
            val t = n.toFloat() / rate
            val rise = if (n >= riseFrames) 1f else (0.5f - 0.5f * cos(PI * n / riseFrames).toFloat())
            val weight = sin(2 * PI * SHOCK_HZ * t).toFloat() * Dsp.envAt(t, t60)
            val front = exp(-t / FRONT_DECAY_SECONDS)
            out[i] += amp * rise * ((1f - FRONT_SHARE) * weight + FRONT_SHARE * front)
        }
        return out
    }

    // ---------- 2. the medium ----------

    /**
     * The k-th all-pass's centre: van der Corput's base-2 sequence on a log scale across the
     * medium's band, so the first few sections already span the range and one more section never
     * moves the ones before it (CHARGE adds a section, never re-tunes).
     */
    internal fun mediumHz(k: Int, medium: Medium = Medium.SHIPPED): Float {
        var n = k
        var denom = 1.0
        var u = 0.0
        while (n > 0) {
            denom *= 2.0
            u += (n and 1) / denom
            n = n shr 1
        }
        return (medium.loHz * (medium.hiHz / medium.loHz).toDouble().pow(u)).toFloat()
    }

    internal fun sectionsFor(charge: Float, medium: Medium = Medium.SHIPPED): Int = (charge * medium.sections).roundToInt()

    /** [x] through [sectionsFor] all-passes. The caller re-normalises: the medium spreads the hit but delivers all of its energy. */
    internal fun medium(x: FloatArray, charge: Float, rate: Int, medium: Medium = Medium.SHIPPED): FloatArray {
        val n = sectionsFor(charge, medium)
        if (n == 0) return x
        val out = x.copyOf()
        for (k in 0 until n) {
            val ap = Dsp.Biquad().apply { allpass(mediumHz(k, medium), medium.q, rate) }
            for (i in out.indices) out[i] = ap.process(out[i])
        }
        return out
    }

    // ---------- 3. the plastic flow ----------

    /** |dx/dt| in full scales per second, smoothed by a [DETECTOR_HZ] one-pole. */
    internal fun slew(x: FloatArray, rate: Int): FloatArray {
        val smooth = Dsp.FixedOnePole(DETECTOR_HZ, rate)
        var prev = if (x.isEmpty()) 0f else x[0]
        return FloatArray(x.size) { i ->
            val d = abs(x[i] - prev) * rate
            prev = x[i]
            smooth.lp(d)
        }
    }

    /**
     * How plastic each sample is, 0..1: 0 below YIELD's threshold, rising smoothly (a smoothstep in
     * log slew) to 1 at [FLOW_WIDTH] times it. At YIELD 1 the threshold is the peak slew itself, so
     * nothing flows.
     */
    internal fun flowState(slew: FloatArray, peakSlew: Float, yield: Float): FloatArray {
        if (peakSlew <= 0f || yield >= 1f) return FloatArray(slew.size)
        val threshold = peakSlew * Dsp.expMap(yield, YIELD_MIN_SHARE, 1f)
        val logThreshold = ln(threshold.toDouble())
        val logWidth = ln(FLOW_WIDTH)
        return FloatArray(slew.size) { i ->
            val s = slew[i]
            if (s <= threshold) {
                0f
            } else {
                val u = ((ln(s.toDouble()) - logThreshold) / logWidth).coerceIn(0.0, 1.0)
                (u * u * (3.0 - 2.0 * u)).toFloat()
            }
        }
    }

    /**
     * Slow material through untouched, plastic material folded: `(1 - p) x + p sin(π/2 (1 + FOLD_DRIVE p) x)`.
     * The sine fold is TIDE's family (`Tide.fold`), driven from the flow state rather than a knob.
     */
    internal fun shape(x: FloatArray, flow: FloatArray): FloatArray = FloatArray(x.size) { i ->
        val p = flow[i]
        if (p <= 0f) {
            x[i]
        } else {
            val drive = 1f + FOLD_DRIVE * p
            (1f - p) * x[i] + p * sin(0.5 * PI * drive * x[i]).toFloat()
        }
    }

    // ---------- 4. the die ----------

    internal fun ceilingFor(arrest: Float): Float = Dsp.lin(arrest, CEILING_OPEN, CEILING_CLOSED)

    internal fun kneeFor(arrest: Float): Float = Dsp.lin(arrest, KNEE_OPEN, 1f)

    /**
     * The die's curve on u = x / ceiling: the straight line up to the knee, then a tanh that meets it
     * with the same slope and never passes 1. A knee of 1 is a clamp.
     */
    internal fun dieCurve(u: Float, knee: Float): Float {
        val a = abs(u)
        if (a <= knee) return u
        if (knee >= 1f) return if (u > 0f) 1f else -1f
        val span = 1f - knee
        val y = knee + span * tanh(((a - knee) / span).toDouble()).toFloat()
        return if (u > 0f) y else -y
    }

    /** The die and its heat, in place on one channel. A copy at ARREST 0 on a normalised blank. */
    internal fun die(y: FloatArray, arrest: Float, rate: Int, seed: Int) {
        if (arrest <= 0f) return
        val ceiling = ceilingFor(arrest)
        val knee = kneeFor(arrest)
        val heatGain = arrest * HEAT_GAIN * ceiling
        val follow = 1f - exp(-1.0 / (HEAT_FOLLOW_SECONDS * rate)).toFloat()
        val noise = Dsp.Noise(seed)
        // The noise is band-limited before its envelope shapes it, so the burst never leaves energy near
        // the output Nyquist for the decimator to fold back (the prototype's finding 5): two band-passes
        // centred between [HEAT_LO_HZ] and [HEAT_HI_HZ], coefficients computed once.
        val hi = min(HEAT_HI_HZ, rate * 0.45f)
        val centre = sqrt(HEAT_LO_HZ * hi)
        val q = centre / (hi - HEAT_LO_HZ)
        val band1 = Dsp.Biquad().apply { bandpass(centre, q, rate) }
        val band2 = Dsp.Biquad().apply { bandpass(centre, q, rate) }
        var env = 0f
        for (i in y.indices) {
            val x = y[i]
            val formed = ceiling * dieCurve(x / ceiling, knee)
            env += follow * (abs(x - formed) - env)
            y[i] = formed + heatGain * env * band2.process(band1.process(noise.next()))
        }
    }

    /** Scales every channel by one gain so the loudest sample of them all is 1. */
    private fun normaliseTogether(xs: Array<FloatArray>) {
        var peak = 0f
        for (x in xs) for (v in x) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        if (peak > 0f) for (x in xs) for (i in x.indices) x[i] /= peak
    }
}
