package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Round chamber-voices-2: the owner heard round 1 (21 Nov 2026), kept GLAZE and asked for TIMBER, MASS, ALLOY and
 * BOUNCE to be "More distinctive": through CHAMBER's room network alone they shared its strike, envelope and body and
 * read as variations of one struck sound. As FILAMENT's kept round 2 did, each now carries a layer of its own, a
 * recognisable instrument with its own attack, sustain, motion and spectrum, and the voice's room network (its
 * instrument, its walls) plays underneath at [bodyMix]:
 *  - TIMBER: a marimba. A rosewood bar tuned 1:3.93:9.2 whose root is held up by its tube (the tube blooms in over
 *    20 ms and rings longer than the bar), a faint hollow tube twelfth, and a soft yarn-mallet thock.
 *  - MASS: a huge low drum. A felt beater on a big skin: the pitch drops a few semitones in the first 50 ms
 *    (the skin's tension relaxing), the drum's inharmonic modes, and the room booming back, a low noise bloom
 *    and the hall's own low modes. Hard adds the slap of the skin.
 *  - ALLOY: a steel pan. Harmonic partials 1, 2, 3, 4 whose pitch bends down from above as the note sounds,
 *    the octave blooming in after the root, each partial a pair a few hertz apart and the root and octave
 *    trading energy, the shimmer of a pan; a rubber-tipped stick's tick.
 *  - BOUNCE: a cimbalom. A course of three steel strings a hair apart, struck by a hand hammer that rebounds: two
 *    clear re-strikes follow (about 60 and 105 ms after the first, lighter, and damping what was ringing).
 *    Hard brings more and louder rebounds, soft one faint one.
 * Touch is the mallet, beater, stick or hammer: soft (velocity .3) leaves mostly the root, dark and gentle; hard (1) is
 * bright, quick and noisy; the default (.75) is between. Every layer is a pure function of its arguments.
 */
internal object ChamberCandidateLayers {
    fun layered(voice: ChamberVoice): Boolean =
        voice == ChamberVoice.TIMBER || voice == ChamberVoice.MASS || voice == ChamberVoice.ALLOY || voice == ChamberVoice.BOUNCE

    /** How much of the voice's room network plays under the layer, after both are brought to the same loudness. */
    fun bodyMix(voice: ChamberVoice): Double = when (voice) {
        ChamberVoice.MASS -> .30
        else -> .27
    }

    /** Mixes the voice's layer into [samples] (the network, before release and leveling); [touch] is -1 soft to +1 hard. */
    fun apply(voice: ChamberVoice, samples: FloatArray, midi: Int, touch: Double, seed: Long) {
        val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val h = ((touch + 1) / 2).coerceIn(0.0, 1.0)
        val random = Random(Dsp.seedFor("CHAMBER", "layer", voice.name, midi, seed))
        val x = DoubleArray(samples.size)
        when (voice) {
            ChamberVoice.TIMBER -> timber(x, hz, h, random)
            ChamberVoice.MASS -> mass(x, hz, h, random)
            ChamberVoice.ALLOY -> alloy(x, hz, h, random)
            ChamberVoice.BOUNCE -> bounce(x, hz, h, random)
            else -> error("$voice has no layer")
        }
        VoiceLayerKit.mix(samples, x, bodyMix(voice))
    }

    private val K = VoiceLayerKit
    private fun phase(random: Random) = random.nextDouble() * 2 * PI

    private fun timber(x: DoubleArray, hz: Double, h: Double, random: Random) {
        val ring = (2.2 * (261.63 / hz).pow(.55)).coerceIn(.35, 5.0)
        val p = phase(random)
        // The bar's root, then the tube on the same note: it blooms in over 20 ms and rings on after the bar.
        K.partial(x, hz, 1.0, .0025, ring, phase = p)
        K.partial(x, hz, .7, .02, ring * 1.4, phase = p)
        K.partial(x, 3 * hz, .035, .02, ring * .6, phase = phase(random))
        K.partial(x, 3.93 * hz, .03 + .3 * h.pow(1.3), .0025, ring / 3.2, phase = phase(random))
        K.partial(x, 9.2 * hz, .004 + .12 * h.pow(2.3), .0025, ring / 7, phase = phase(random))
        // The yarn mallet: a soft one lies on the bar longer and darker.
        K.burst(x, random, 0.0, .006 - .0045 * h, .35 * (.4 + .6 * h), 300.0 + 2_500.0 * h * h, 60.0)
    }

    private fun mass(x: DoubleArray, hz: Double, h: Double, random: Random) {
        val ratios = doubleArrayOf(1.0, 1.594, 2.136, 2.296, 2.653, 2.918, 3.156)
        val weights = doubleArrayOf(1.0, .55, .4, .3, .25, .18, .12)
        val ring = (2.6 * (65.41 / hz).pow(.35)).coerceIn(.6, 3.2)
        // The skin's tension relaxes as it swings back: the pitch starts high and drops within 50 ms.
        val bend = .05 + .17 * h
        for (k in ratios.indices) {
            val w = weights[k] * if (k == 0) 1.0 else (.1 + .9 * h).pow(1 + .8 * k)
            K.partial(x, hz * ratios[k], w, .003, ring / (1 + .7 * k), phase = phase(random), bend = bend, bendTau = .045)
        }
        K.burst(x, random, 0.0, .012 - .008 * h, .5, 100.0 + 1_400.0 * h * h, 30.0)
        K.burst(x, random, 0.0, .0015, .4 * h.pow(2), 9_000.0, 1_800.0)
        // The room booms back: a low bloom of noise and the hall's own low modes. A soft beater barely wakes the
        // hall; a hard one fills it, and brighter.
        val hall = .1 + .9 * h * h
        K.tail(x, random, .012, .07, 2.4, .4 * hall, (hz * (1.5 + 5 * h)).coerceIn(150.0, 2_000.0), 35.0)
        for ((i, f) in doubleArrayOf(41.0, 53.0, 67.0, 82.0, 97.0, 118.0).withIndex())
            K.partial(x, f, .15 * hall / (1 + .2 * i), .03, 1.6, start = .01, phase = phase(random))
    }

    private fun alloy(x: DoubleArray, hz: Double, h: Double, random: Random) {
        val ring = (2.4 * (261.63 / hz).pow(.5)).coerceIn(.6, 4.0)
        val weights = doubleArrayOf(1.0, .85, .5, .22, .1)
        val attacks = doubleArrayOf(.003, .035, .02, .01, .01)
        // The pan's pitch bends down from above as the note speaks.
        val bend = .008 + .022 * h
        // The root and the octave trade energy, about three times a second.
        val slosh = 3.1
        val root = DoubleArray(x.size) { i -> 1 - .25 * (.5 - .5 * cos(2 * PI * slosh * i / K.RATE)) }
        val octave = DoubleArray(x.size) { i -> 1 - .25 * (.5 + .5 * cos(2 * PI * slosh * i / K.RATE)) }
        for (k in weights.indices) {
            val n = k + 1
            val w = weights[k] * if (k < 2) 1.0 else (.3 + .7 * h).pow(1.3 * (k - 1))
            val gain = when (k) { 0 -> root; 1 -> octave; else -> null }
            val t60 = ring / (1 + .25 * k)
            K.partial(x, n * hz, w, attacks[k], t60, phase = phase(random), bend = bend, bendTau = .06, gain = gain)
            // Each partial's partner a few hertz up: the shimmer.
            K.partial(x, n * hz + 3.5 + 1.2 * n, .45 * w, attacks[k], t60 * .9, phase = phase(random), bend = bend, bendTau = .06)
        }
        K.burst(x, random, 0.0, .002, .2 * (.3 + .7 * h), 2_500.0 + 4_000.0 * h, 400.0)
    }

    private fun bounce(x: DoubleArray, hz: Double, h: Double, random: Random) {
        val ring = (5.0 * (261.63 / hz).pow(.5)).coerceIn(1.5, 8.0)
        val stiffness = .0009
        // The hand hammer and its rebounds: when (each on a whole number of the note's periods, so it lands with the
        // ringing string, not against it) and how hard. Each return re-strikes the course, a little brighter.
        val rebounds = listOf((.058 + .012 * (1 - h)) to (.3 + .45 * h), (.104 + .02 * (1 - h)) to (.08 + .35 * h))
            .map { (at, strength) -> Math.round(at * hz).coerceAtLeast(1L) / hz to strength }
        // Above C5 the strings darken, so the top of the range rings without turning shrill; low courses are wound and
        // jangle, so the hammer's corner never falls below a few hundred hertz.
        val above = (ln(hz / 523.25) / ln(2.0)).coerceIn(0.0, 1.0)
        val corner = (hz * (3.0 + 22.0 * h * h)).coerceAtLeast(400.0 + 1_600.0 * h).coerceAtMost((2_500.0 + 2_500.0 * h) / (1 + 1.5 * above))
        val slope = .45 + 1.25 * (1 - h) + 1.2 * above
        val cents = doubleArrayOf(-1.6, 0.0, 1.4)
        for (n in 1..28) {
            val fn = n * hz * sqrt(1 + stiffness * n * n) / sqrt(1 + stiffness)
            if (fn > 9_000.0) break
            // Struck near one end: the hammer's place thins a few partials but never the root.
            val place = if (n == 1) 1.3 else .15 + .85 * abs(sin(PI * n / 8.6))
            val w = place / n.toDouble().pow(slope) / (1 + (fn / corner).pow(2))
            val steps = DoubleArray(x.size) { i -> 1 + rebounds.sumOf { (at, strength) -> strength * (1 + .04 * n) * K.rise(i, at, at + .002) } }
            for (c in cents) K.partial(x, fn * 2.0.pow(c / 1200), w / 3, .0025, ring / (1 + .07 * (n - 1)), phase = phase(random), gain = steps)
        }
        // The hammer's click at each contact, a moment after it begins.
        for ((at, strength) in listOf(0.0 to 1.0) + rebounds)
            K.burst(x, random, at + .0005, .0012, .2 * strength * (.2 + .8 * h) * (1 - .6 * above), 8_000.0, 1_500.0)
    }
}
