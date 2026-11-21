package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Round ferry-voices-2: the owner heard round 1 (21 Nov 2026), kept TINE and asked for TWIN, SWELL, TARAB, CHIME and
 * RELAY to be "More distinctive": through FERRY's passive network alone they shared its strike, its envelope and its
 * body, and read as variations of one sound. As FILAMENT's kept round 2 did, each now carries a layer of its own,
 * a recognisable instrument with its own attack, sustain, motion and spectrum, and the voice's network plays
 * underneath at [bodyMix]. CHIME is now PEAL and RELAY is now BATON (the old names clash with a LIBRATION voice and
 * an engine). VANE is new, the spare.
 *  - TWIN: a piano's double string. Two strings a hair apart on every partial beat clearly (about a hertz at the
 *    root, MATCH widens it), a fast prompt sound over a long aftersound, stretched partials and a felt thump.
 *  - SWELL: a bowed swell that blooms. No strike: the note grows like a reversed piano for most of a second, its
 *    upper partials arriving only near the top, then it fades with a late vibrato and a breath of rosin.
 *  - TARAB: a sitar. A bright plucked string whose jawari buzz is a resonance sweeping down through the partials
 *    for about a second, under sympathetic strings (octave, fifth, twelfth) that bloom in late and shimmer.
 *  - PEAL: a handbell. A root and its twelfth, each a pair a fraction of a hertz apart, so the ring wavers slowly
 *    for many seconds; a hard metal clapper tick.
 *  - BATON: wood, then wire, then glass, one after the other: a short dry wooden knock, a bright stretched wire
 *    that swells in as the knock dies, then a pure glass tone that rises as the wire fades and is left ringing.
 *    HANDOFF sets how fast the baton passes.
 *  - VANE: a vibraphone. A soft-mallet bar tuned 1:4:10 with a long ring, its root pulsed by the motor's fan vanes
 *    at about 5.5 a second.
 * Touch is the mallet, hammer, plectrum or bow: soft leaves mostly the root, dark and slow; hard is bright, quick
 * and noisy. Every layer is a pure function of its arguments (seeded by voice and note).
 */
internal object FerryCandidateLayers {
    fun layered(voice: FerryVoice): Boolean = voice != FerryVoice.PASSAGE && voice != FerryVoice.TINE

    /** How much of the voice's network plays under the layer, after both are brought to the same loudness. */
    fun bodyMix(voice: FerryVoice): Double = when (voice) {
        FerryVoice.SWELL -> .30
        else -> .26
    }

    /** Mixes the voice's layer into [out] (the network, before release and leveling). */
    fun apply(voice: FerryVoice, out: FloatArray, midi: Int, velocity: Float, m: Map<String, Float>) {
        val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val v = velocity.toDouble()
        val loss = Ferry.lossScale(m.getValue("LOSS"))
        val random = Random(Dsp.seedFor("FERRY", "layer", voice.name, midi))
        val x = DoubleArray(out.size)
        when (voice) {
            FerryVoice.TWIN -> twin(x, hz, v, m.getValue("MATCH").toDouble(), loss, random)
            FerryVoice.SWELL -> swell(x, hz, v, loss, random)
            FerryVoice.TARAB -> tarab(x, hz, v, m.getValue("MATCH").toDouble(), loss, random)
            FerryVoice.PEAL -> peal(x, hz, v, m.getValue("MATCH").toDouble(), loss, random)
            FerryVoice.BATON -> baton(x, hz, v, Ferry.pace(m.getValue("HANDOFF")), loss, random)
            FerryVoice.VANE -> vane(x, hz, v, loss, random)
            else -> error("$voice has no layer")
        }
        VoiceLayerKit.mix(out, x, bodyMix(voice))
    }

    private val K = VoiceLayerKit
    private fun phase(random: Random) = random.nextDouble() * 2 * PI

    private fun twin(x: DoubleArray, hz: Double, v: Double, match: Double, loss: Double, random: Random) {
        // About a hertz at middle C, less below it so a low note's pair stays a few cents apart.
        val beat = (1.1 + 2.4 * (1 - match)) * (hz / 261.63).coerceAtMost(1.0)
        val stiffness = .0004
        val after = (11.0 * (261.63 / hz).pow(.45)).coerceIn(4.0, 16.0) / loss
        // The felt: a soft hammer is a low corner and a steep slope, a hard one opens the top.
        val corner = min(hz * (2.0 + 16.0 * v * v), 3_200.0)
        val slope = .5 + 1.6 * (1 - v)
        for (n in 1..30) {
            val fn = n * hz * sqrt(1 + stiffness * n * n) / sqrt(1 + stiffness)
            if (fn > 11_000.0) break
            val w = (.25 + .75 * abs(sin(PI * n / 7.4))) / n.toDouble().pow(slope) / (1 + (fn / corner).pow(2))
            val slow = after / (1 + .22 * (n - 1))
            // The first string sits on the partial, the second a beat above it.
            for ((f, a) in listOf(fn to 1.0, fn + beat * sqrt(n.toDouble()) to .85)) {
                val p = phase(random)
                // Prompt sound and aftersound: most of the strike dies fast, the rest rings on and beats.
                K.partial(x, f, .62 * a * w, .0015, slow / 7.0, phase = p)
                K.partial(x, f, .38 * a * w, .0015, slow, phase = p)
            }
        }
        K.burst(x, random, 0.0, .005, .5 * v.pow(1.5), 300.0 + 500.0 * v, 40.0)
    }

    private fun swell(x: DoubleArray, hz: Double, v: Double, loss: Double, random: Random) {
        val rise = 1.05 - .5 * v
        val fade = 3.0 / loss
        val envelope = DoubleArray(x.size) { i ->
            val t = i.toDouble() / K.RATE
            if (t < rise) ((1 - cos(PI * t / rise)) / 2).pow(1.7) else exp(-(t - rise) * ln(1000.0) / fade)
        }
        // A late vibrato, six cents at 5.2 Hz, coming in after the peak.
        val vibrato = DoubleArray(x.size) { i -> .0035 * sin(2 * PI * 5.2 * i / K.RATE) * K.rise(i, rise, rise + .6) }
        val slope = 1.25 + 1.3 * (1 - v)
        for (n in 1..24) {
            val f = n * hz
            if (f > 9_000.0) break
            val lag = 1.0 + .4 * (n - 1) * (1.3 - v)
            val gain = DoubleArray(x.size) { envelope[it].pow(lag) }
            val w = body(f) / n.toDouble().pow(slope)
            bowed(x, f, w, gain, vibrato, phase(random))
        }
        val rosin = DoubleArray(x.size) { envelope[it] }
        K.tail(x, random, 0.0, .001, 60.0, .06 * v * v, 7_000.0, 2_200.0, rosin)
    }

    /** A bowed instrument's wooden body: fixed resonances whatever the note (air, two wood modes, the bridge hill). */
    private fun body(f: Double): Double {
        var g = .08
        for ((centre, level) in listOf(280.0 to 1.0, 520.0 to .8, 1_100.0 to .55, 2_700.0 to .9)) g += level / (1 + ((f - centre) / (.15 * centre)).pow(2))
        return g
    }

    /** A sine whose pitch follows [vibrato] (a fraction of [hz]) and whose level is [gain]. */
    private fun bowed(x: DoubleArray, hz: Double, amp: Double, gain: DoubleArray, vibrato: DoubleArray, phase: Double) {
        var p = phase
        val w = 2 * PI * hz / K.RATE
        for (i in x.indices) {
            x[i] += amp * gain[i] * sin(p)
            p += w * (1 + vibrato[i])
        }
    }

    private fun tarab(x: DoubleArray, hz: Double, v: Double, match: Double, loss: Double, random: Random) {
        val ring = (3.6 * (130.81 / hz).pow(.15)).coerceIn(2.4, 5.0) / loss
        val slope = .5 + 1.4 * (1 - v)
        // The jawari: a resonance that starts high in the partials and sweeps down to the octave over about a second.
        // It never starts above 3 kHz, and the top is rolled off from 2.5 kHz, so the top of the range buzzes without
        // turning shrill.
        val sweep = .35 + .45 * v
        val high = min(4.0 + 26.0 * v, 2_000.0 / hz)
        val centre = DoubleArray(x.size) { i -> hz * (2.0 + high * exp(-i.toDouble() / K.RATE / sweep)) }
        val buzz = 2.4 * (.3 + .7 * (261.63 / hz).coerceAtMost(1.0))
        for (n in 1..40) {
            val f = n * hz
            if (f > 9_500.0) break
            val w = (if (n == 1) 1.5 else 1.0) / n.toDouble().pow(slope) * (.35 + .65 * abs(sin(PI * n * .11))) /
                (1 + (f / (2_500.0 * (261.63 / hz).pow(.5)).coerceIn(1_300.0, 2_500.0)).pow(2))
            val log2 = ln(2.0)
            val gain = if (n == 1) null else DoubleArray(x.size) { i ->
                val d = ln(f / centre[i]) / log2
                .4 + buzz * exp(-d * d / (2 * .4 * .4))
            }
            K.partial(x, f, w, .0012, ring / (1 + .035 * (n - 1)), phase = phase(random), gain = gain)
        }
        // The sympathetic strings: they bloom in late, a few cents off the played string's partials, and ring on.
        // Above middle C they thin out, so the top of the range keeps its played string in front.
        val apart = 4.0 + 6.0 * (1 - match)
        val thin = (261.63 / hz).coerceAtMost(1.0)
        for ((s, r) in doubleArrayOf(2.0, 1.5, 3.0, 4.0).withIndex()) for (m in 1..4) {
            val f = hz * r * m * 2.0.pow((if (s % 2 == 0) apart else -apart) / 1200)
            K.partial(x, f, .11 / m * (.5 + .5 * v) * thin.pow(1.0 + .5 * (r * m - 1.5)), .25 + .05 * s, 7.0 * thin.pow(.5) / loss,
                start = .03 + .03 * s, phase = phase(random))
        }
        K.burst(x, random, 0.0, .0015, .35 * v * v, 9_000.0, 1_500.0)
    }

    private fun peal(x: DoubleArray, hz: Double, v: Double, match: Double, loss: Double, random: Random) {
        val ratios = doubleArrayOf(1.0, 3.0, 4.18, 5.43, 6.8, 8.3, 10.2)
        val weights = doubleArrayOf(1.0, .6, .28, .2, .14, .09, .06)
        val ring = (10.0 * (261.63 / hz).pow(.35)).coerceIn(5.0, 14.0) / loss
        for (k in ratios.indices) {
            val f = hz * ratios[k]
            val w = weights[k] * if (k == 0) 1.0 else v.pow(1.5 * k.toDouble().pow(.7))
            // Each tone is a pair a fraction of a hertz apart: the long, slow waver of a bell that is not quite round.
            val beat = (.55 + .3 * k) * (.7 + .9 * (1 - match))
            val t60 = ring / (1 + .45 * k)
            K.partial(x, f - beat / 2, w, .001, t60, phase = phase(random))
            K.partial(x, f + beat / 2, .8 * w, .001, t60 * .95, phase = phase(random))
        }
        K.burst(x, random, 0.0, .0012, .3 * v * v, 12_000.0, 3_000.0)
    }

    private fun baton(x: DoubleArray, hz: Double, v: Double, pace: Double, loss: Double, random: Random) {
        val s = 1.0 / sqrt(pace.coerceAtLeast(.05))
        // Wood: a short dry knock.
        val wood = doubleArrayOf(1.0, 2.57, 4.8)
        for (k in wood.indices) K.partial(x, hz * wood[k], doubleArrayOf(1.0, .5 * v, .25 * v * v)[k], .0012,
            .32 * s / (1 + .6 * k) / loss, phase = phase(random))
        K.burst(x, random, 0.0, .006 - .004 * v, .7 * v, 400.0 + 3_000.0 * v * v, 60.0)
        // Wire: a bright stretched string that swells in as the knock dies, and fades as the glass comes.
        val wireFade = DoubleArray(x.size) { i -> K.rise(i, .10 * s, .30 * s) * (1 - .9 * K.rise(i, .75 * s, 1.35 * s)) }
        val slope = .45 + 1.6 * (1 - v)
        for (n in 1..20) {
            val f = n * hz * sqrt(1 + .0012 * n * n) / sqrt(1.0012)
            if (f > 9_000.0) break
            K.partial(x, f, .9 / n.toDouble().pow(slope), .001, 2.2 * s / (1 + .08 * (n - 1)) / loss,
                start = .10 * s, phase = phase(random), gain = wireFade)
        }
        // Glass: a pure tone that rises as the wire fades and is left ringing, a slow beat in it.
        val glassIn = DoubleArray(x.size) { i -> K.rise(i, .6 * s, 1.25 * s) }
        K.partial(x, hz, .75, .001, 9.0 / loss, start = .6 * s, phase = phase(random), gain = glassIn)
        K.partial(x, hz + .4, .45, .001, 8.0 / loss, start = .6 * s, phase = phase(random), gain = glassIn)
        K.partial(x, 2 * hz, .05 * (.5 + .5 * v), .001, 5.0 / loss, start = .6 * s, phase = phase(random), gain = glassIn)
    }

    private fun vane(x: DoubleArray, hz: Double, v: Double, loss: Double, random: Random) {
        val ring = (7.0 * (261.63 / hz).pow(.35)).coerceIn(3.0, 10.0) / loss
        // The motor's vanes open and close the tube above the bar: the root pulses, the upper modes barely.
        val rate = 5.6
        val deep = DoubleArray(x.size) { i -> 1 - .72 * (.5 - .5 * cos(2 * PI * rate * i / K.RATE)) }
        val light = DoubleArray(x.size) { i -> 1 - .3 * (.5 - .5 * cos(2 * PI * rate * i / K.RATE)) }
        K.partial(x, hz, 1.0, .0015, ring, phase = phase(random), gain = deep)
        K.partial(x, 4 * hz, .01 + .5 * v.pow(2), .0015, ring / 3.5, phase = phase(random), gain = light)
        K.partial(x, 10 * hz, .16 * v.pow(3), .0015, ring / 9, phase = phase(random), gain = light)
        K.burst(x, random, 0.0, .0025, .03 + .45 * v.pow(2.5), 1_000.0 + 6_000.0 * v * v, 200.0)
    }
}
