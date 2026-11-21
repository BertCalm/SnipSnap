package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FERRY's candidate voices (listening round ferry-voices-1, 20 Nov 2026). None is offered anywhere until the owner
 * keeps it ([Ferry.KEPT_VOICES]). Round 1 kept TINE (21 Nov 2026) and asked for the rest to be "More distinctive": they
 * now carry an instrument layer of their own over the network below ([FerryCandidateLayers], round ferry-voices-2),
 * CHIME is renamed PEAL, RELAY is renamed BATON and VANE (a vibraphone bar over its tube) is added. TINE is unchanged.
 *
 * Every candidate is the same instrument as PASSAGE: a passive complex network da/dt = (i H - Gamma) a, struck
 * once by displacing some of its states, observed through pickups, compiled once into [FerryNetwork.Compiled]
 * and stepped the same way. What changes is the graph, which states are struck and which are heard:
 *  - TWIN: two unison strings per partial share a lossy bridge (Weinreich's coupled piano strings). The hammer
 *    hits both a little unevenly, so the part that pushes the bridge dies fast (the prompt sound) and the part
 *    that does not rings on (the aftersound), beating slowly. MATCH sets how far the pair is apart.
 *  - SWELL: the struck wood has no pickup at all. All you hear is what the handoff carries into the wire, so
 *    the note grows out of silence and breathes as energy passes back and forth.
 *  - TARAB: a bright plucked string under four sympathetic strings (octave, fifth, twelfth, double octave),
 *    slightly apart, so a shimmering halo rings on after the pluck (a sitar's or sarod's tarab).
 *  - PEAL (was CHIME): a handbell. Each partial is a pair of nearly equal modes (a bell is never perfectly round); the
 *    strike fills one of each pair and the coupling turns the energy round the bell, so the tone wavers.
 *  - BATON (was RELAY): a chain of three materials, wood to wire to glass, one way along: a dark knock, a bright wire
 *    bloom, then a pure glass tone that is left ringing.
 *  - TINE (the spare): a kalimba. The tine's inharmonic modes drive a wooden box whose modes stay at fixed
 *    pitches whatever the note, so the box colours every note differently; the tine is retuned so the
 *    note stays in tune against the box's pull.
 *
 * HANDOFF scales every link (the same pace law as PASSAGE), MATCH sets each voice's detuning, LOSS scales every
 * damping rate (the same law as PASSAGE) and sets the observation window. Touch is the strike: velocity changes
 * which partials the strike fills (a soft strike leaves mostly the fundamental), and after the house leveling a
 * soft note stays quieter (the level follows velocity to [LEVEL_POWER], never levelled back).
 */
internal object FerryCandidates {
    /** Level after leveling follows velocity to this power: soft .35 is 9 dB under hard, .7 is 3 dB under. */
    const val LEVEL_POWER = 1.0
    /** A long ring ends on a cosine release over the last part of the window, never on a cut. */
    const val RELEASE_SECONDS = .9f
    private const val TOP_HZ = 15_000.0
    /** The house melodic loudness, 2 dB under, so a hard strike's peak stays under the ceiling. */
    private val LOUDNESS = Dsp.MELODIC_LOUDNESS_TARGET * 10.0.pow(-2.0 / 20)

    /** A mode: frequency, amplitude T60 (seconds), pickup weight and initial displacement. */
    private class Graph {
        val hz = ArrayList<Double>()
        val gamma = ArrayList<Double>()
        val pickup = ArrayList<Double>()
        val initial = ArrayList<Double>()
        val links = ArrayList<Triple<Int, Int, Double>>()

        fun mode(f: Double, t60: Double, pick: Double, start: Double = 0.0): Int {
            if (f >= TOP_HZ) return -1
            hz += f; gamma += ln(1000.0) / t60; pickup += pick; initial += start
            return hz.size - 1
        }

        fun link(a: Int, b: Int, g: Double) { if (a >= 0 && b >= 0 && g != 0.0) links += Triple(a, b, g) }

        fun h(): Array<DoubleArray> {
            val n = hz.size
            val h = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 2 * PI * hz[i] else 0.0 } }
            for ((a, b, g) in links) { h[a][b] += g; h[b][a] += g }
            return h
        }
    }

    class Built(val compiled: FerryNetwork.Compiled, val initial: DoubleArray)

    private data class Key(val voice: FerryVoice, val midi: Int, val handoff: Int, val match: Int, val loss: Int, val velocity: Int)
    private val cache = object : LinkedHashMap<Key, Built>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Built>?) = size > 24
    }

    fun build(voice: FerryVoice, midi: Int, handoff: Float, match: Float, loss: Float, velocity: Float): Built {
        val key = Key(voice, midi, handoff.toRawBits(), match.toRawBits(), loss.toRawBits(), velocity.toRawBits())
        synchronized(cache) { cache[key]?.let { return it } }
        val f = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val pace = Ferry.pace(handoff)
        val lossScale = Ferry.lossScale(loss)
        val t = velocity.toDouble()
        val g = when (voice) {
            FerryVoice.TWIN -> twin(f, pace, match, t)
            FerryVoice.SWELL -> swell(f, pace, t)
            FerryVoice.TARAB -> tarab(f, pace, match, t)
            FerryVoice.PEAL -> chime(f, pace, match, t)
            FerryVoice.BATON -> relay(f, pace, t)
            FerryVoice.VANE -> vane(f, pace, t)
            FerryVoice.TINE -> tine(f, pace, t)
            FerryVoice.PASSAGE -> error("PASSAGE is not a candidate")
        }
        val h = g.h()
        if (voice == FerryVoice.TINE) retune(h, 0, f)
        val model = FerryNetwork.Model(h, g.gamma.map { it * lossScale }.toDoubleArray(), g.pickup.toDoubleArray(), 1.0, f)
        val built = Built(FerryNetwork.Compiled(model, Ferry.INTERNAL_RATE), g.initial.toDoubleArray())
        synchronized(cache) { return cache.getOrPut(key) { built } }
    }

    /** Links are in radians a second; under middle C they shrink so a low note's split stays a few cents. */
    private fun low(f: Double) = (f / 261.63).coerceAtMost(1.0)

    /** A strike of touch [t] fills partial [k] (0 = the root) by t^(steep * k): soft leaves mostly the root. */
    private fun hardness(t: Double, k: Int, steep: Double) = t.pow(steep * k)

    private fun twin(f: Double, pace: Double, match: Float, t: Double) = Graph().apply {
        val apart = .4 + 2.6 * (1 - match)
        for (n in 1..12) {
            val partial = f * n * sqrt(1 + .00035 * n * n) / sqrt(1 + .00035)
            val t60 = 9.0 / (1 + .35 * (n - 1)) * (if (f > 300) 300 / f else 1.0).pow(.5)
            val w = n.toDouble().pow(-1.1) * hardness(t, n - 1, 1.6)
            // Heard nearer the first string, so the part of the pair that leaves the bridge alone is heard too.
            val a = mode(partial, t60, .9, w)
            val b = mode(partial * 2.0.pow(apart / 1200), t60 * 1.05, .25, .7 * w)
            val bridge = mode(partial, .06, 1.0)
            link(a, bridge, (16.0 + 2.0 * n) * pace)
            link(b, bridge, (16.0 + 2.0 * n) * pace)
        }
    }

    private fun swell(f: Double, pace: Double, t: Double) = Graph().apply {
        // The struck wood is mute; its energy is heard only once it has crossed into the wire.
        for (n in 1..8) {
            val w = n.toDouble().pow(-.7) * hardness(t, n - 1, 1.8)
            val a = mode(f * n, 2.4 / (1 + .25 * (n - 1)), 0.0, w)
            val b = mode(f * n, 3.4 / (1 + .3 * (n - 1)), doubleArrayOf(.55, 1.0, .9, .8, .6, .5, .4, .3)[n - 1])
            link(a, b, (1.6 + .55 * n) * pace * (.65 + .35 * t))
        }
    }

    private fun tarab(f: Double, pace: Double, match: Float, t: Double) = Graph().apply {
        val apart = 3.0 + 4.0 * (1 - match)
        val main = IntArray(12)
        for (n in 1..12) {
            // Plucked near the bridge: a bright comb with no strong gaps in the first dozen partials.
            val w = abs(sin(n * PI * .13)) / n.toDouble().pow(.35) * hardness(t, n - 1, 1.4) * (if (n == 1) 2.5 else 1.0) /
                (1 + (n * f / 3500).pow(2))
            main[n - 1] = mode(f * n, 2.2 / (1 + .12 * (n - 1)), 1.0, w)
        }
        // Octave, fifth, twelfth and double octave: the played string keeps its own root; the halo rings above it.
        val tarabs = doubleArrayOf(2.0, 1.5, 3.0, 4.0)
        val detune = doubleArrayOf(1.0, -1.0, .6, -.6)
        for (s in tarabs.indices) for (m in 1..6) {
            val nominal = f * tarabs[s] * m
            val n = Math.round(tarabs[s] * m * 2) / 2.0
            if (n != Math.floor(n) || n > 12) continue
            val r = mode(nominal * 2.0.pow(detune[s] * apart / 1200), 5.5 / (1 + .15 * (m - 1)), .8)
            link(main[n.toInt() - 1], r, (1.1 + .25 * n) * pace * low(f))
        }
    }

    private fun chime(f: Double, pace: Double, match: Float, t: Double) = Graph().apply {
        val ratios = doubleArrayOf(1.0, 2.0, 3.0, 4.2, 5.4, 6.8, 8.3)
        val weights = doubleArrayOf(1.0, .2, .9, .7, .6, .5, .4)
        // Each pair is split evenly about its partial by a fixed share of it (7 cents at MATCH 1), so the
        // beat is about a hertz at middle C and the pair's centre stays on the partial.
        val split = (7.0 + 9.0 * (1 - match)) / 1200
        for (k in ratios.indices) {
            val p = f * ratios[k]
            val t60 = 6.5 / (1 + .55 * k)
            val w = weights[k] * hardness(t, k, 1.5)
            val a = mode(p * 2.0.pow(-split / 2), t60, 1.0, w)
            val b = mode(p * 2.0.pow(split / 2), t60 * .9, .8)
            link(a, b, (2.4 + .6 * k) * pace * low(f))
        }
    }

    private fun relay(f: Double, pace: Double, t: Double) = Graph().apply {
        val wood = doubleArrayOf(1.0, .42, .2, .1, .05)
        val wire = IntArray(6)
        val woodModes = IntArray(5)
        for (n in 1..5) woodModes[n - 1] = mode(f * n, .45 / (1 + .3 * (n - 1)), wood[n - 1],
            n.toDouble().pow(-.6) * hardness(t, n - 1, 1.6))
        for (n in 1..6) wire[n - 1] = mode(f * n, 1.6 / (1 + .2 * (n - 1)), .35 + .2 * n)
        for (n in 1..5) link(woodModes[n - 1], wire[n - 1], (4.0 + 1.5 * n) * pace)
        val glass = intArrayOf(mode(f, 7.0, 1.3), mode(2 * f, 4.5, .5))
        link(wire[0], glass[0], 2.4 * pace)
        link(wire[1], glass[1], 2.0 * pace)
    }

    private fun tine(f: Double, pace: Double, t: Double) = Graph().apply {
        // The tine: a clamped bar's ratios. Index 0 is the root the retune holds on the note.
        val ratios = doubleArrayOf(1.0, 6.27, 17.55)
        val tine = IntArray(3)
        for (k in ratios.indices) tine[k] = mode(f * ratios[k], doubleArrayOf(3.2, .5, .15)[k], doubleArrayOf(.5, .9, .6)[k],
            doubleArrayOf(1.0, .55, .3)[k] * hardness(t, k, 2.6))
        // The box: fixed pitches, short and woody, whatever the note.
        val body = doubleArrayOf(205.0, 340.0, 560.0, 910.0, 1480.0, 2350.0)
        for ((i, hz) in body.withIndex()) {
            val b = mode(hz, .22 / (1 + .2 * i), 1.6)
            link(tine[0], b, 26.0 * pace)
            link(tine[1], b, 40.0 * pace)
        }
    }

    private fun vane(f: Double, pace: Double, t: Double) = Graph().apply {
        // A vibraphone bar tuned 1:4:10 over its tube: the tube is a mode on the root that the bar fills.
        val ratios = doubleArrayOf(1.0, 4.0, 10.0)
        val bar = IntArray(3)
        for (k in ratios.indices) bar[k] = mode(f * ratios[k], doubleArrayOf(5.0, 1.4, .5)[k], doubleArrayOf(.6, .5, .3)[k],
            doubleArrayOf(1.0, .4, .15)[k] * hardness(t, k, 2.0))
        val tube = mode(f, 1.2, 1.0)
        link(bar[0], tube, 6.0 * pace * low(f))
    }

    /** Shift state [index]'s diagonal until the eigenmode it leads sits exactly at [hz]. */
    private fun retune(h: Array<DoubleArray>, index: Int, hz: Double) {
        repeat(4) {
            val (values, q) = Modes.symmetricEigen(h)
            var best = 0
            for (j in values.indices) if (abs(q[index][j]) > abs(q[index][best])) best = j
            h[index][index] += 2 * PI * hz - values[best]
        }
    }

    fun body(voice: FerryVoice, m: Map<String, Float>, midi: Int, velocity: Float, model: Int): FloatArray {
        require(model == Ferry.MODEL_VERSION) { "FERRY $voice is a model ${Ferry.MODEL_VERSION} voice" }
        val loss = m.getValue("LOSS")
        val frames = Ferry.observationFrames(loss)
        if (velocity == 0f) return FloatArray(frames)
        val built = build(voice, midi, m.getValue("HANDOFF"), m.getValue("MATCH"), loss, velocity)
        val raw = built.compiled.render(built.initial, frames * Dsp.OVERSAMPLE)
        Tide.bandLimit(raw, Ferry.INTERNAL_RATE)
        val out = Dsp.decimate(raw, Dsp.RATE)
        val mean = out.sumOf { it.toDouble() } / out.size.coerceAtLeast(1)
        val hp = Dsp.OnePole(Dsp.RATE)
        for (i in out.indices) {
            val x = out[i] - mean.toFloat()
            out[i] = x - hp.lp(x, 20f)
        }
        if (FerryCandidateLayers.layered(voice)) FerryCandidateLayers.apply(voice, out, midi, velocity, m)
        Dsp.fadeTailCosine(out, minOf(RELEASE_SECONDS, frames * .3f / Dsp.RATE))
        // Leveled by loudness alone, then touched, then kept under the ceiling: a ceiling applied before the touch
        // would pull a bright hard strike down to the level of a medium one.
        val loud = com.snipsnap.audio.Loudness.of(out, 1, Dsp.RATE)
        val gain = if (loud > 1e-6f) (LOUDNESS / loud * velocity.toDouble().pow(LEVEL_POWER)).toFloat() else 1f
        for (i in out.indices) out[i] *= gain
        Dsp.limitPeak(out, .99f)
        Dsp.fadeTail(out)
        return out
    }
}
