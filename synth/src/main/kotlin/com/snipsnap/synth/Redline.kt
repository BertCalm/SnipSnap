package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.*

/** REDLINE: the breakcore family's speedcore kit, at 250 BPM. The kick is the bassline.
 *
 * The characteristic gesture is: pitch dive -> distorted body held on a note -> noise
 * snare -> feedback scream.
 *
 * The kick is synthesized here, not a sampled drum distorted: a sine diving from twelve
 * times its note onto the note in about 10 ms, a 3 ms click, then SPLINTER's oversampled
 * clipper. Its body holds a pitch, so four pads are the same kick on root, minor third,
 * fifth and octave of F#, and a pattern across them is a bassline. SPLINTER's Gabber Kick
 * is one drum clipped; this kit tunes the clipping.
 *
 * Velocity is drive: a soft hit clips less and is cleaner, not only quieter. The snares,
 * hats and crash are filtered noise through the same clipper; the scream is noise through
 * a nearly self-oscillating band-pass, swept and clipped. Every constant awaits the
 * owner's listen. */
object Redline : BreakcoreKit {
    const val VERSION = 1
    override val id = "redline-breakcore"
    override val title = "Redline Breakcore"
    override val bpm = 250

    /** F#1. The tuned kicks sit on 1, 6/5, 3/2 and 2 of it (equal-tempered). */
    const val ROOT_HZ = 46.25

    override val layout: List<Splinter.Part> = listOf(
        Splinter.Part("Terror Kick", DrumClass.KICK, 1f),
        Splinter.Part("Kick Third", DrumClass.KICK, .95f),
        Splinter.Part("Kick Fifth", DrumClass.KICK, .95f),
        Splinter.Part("Kick Octave", DrumClass.KICK, .9f),
        Splinter.Part("Short Kick", DrumClass.KICK, .95f),
        Splinter.Part("Kick Stutter", DrumClass.KICK, .9f),
        Splinter.Part("Noise Snare", DrumClass.SNARE, .8f),
        Splinter.Part("Burst Clap", DrumClass.CLAP, .75f),
        Splinter.Part("Saw Hat", DrumClass.HAT_CLOSED, .4f, 1),
        Splinter.Part("Noise Hat", DrumClass.HAT_OPEN, .4f, 1),
        Splinter.Part("Crash Noise", DrumClass.PERC, .45f),
        Splinter.Part("Scream", DrumClass.PERC, .55f),
        Splinter.Part("Feedback Stab", DrumClass.PERC, .5f),
        Splinter.Part("Bit Kick", DrumClass.KICK, .9f),
        Splinter.Part("Gate Noise", DrumClass.PERC, .5f),
        Splinter.Part("Tom Drop", DrumClass.TOM, .7f),
        Splinter.Part("Snare Roll", DrumClass.LOOP, .7f),
        Splinter.Part("Scream Rise", DrumClass.LOOP, .55f),
    )

    private fun take(t: Int, spread: Double) = listOf(0.0, -spread, spread)[t % 3] * (1 + t / 3)
    private fun semis(s: Int) = 2.0.pow(s / 12.0)

    /** The terror kick on [hz], [seconds] long; [drive] is scaled by force. */
    private fun kick(hz: Double, v: Double, t: Int, seconds: Double = .4, drive: Double = 60.0): FloatArray {
        val n = Breakcore.frames(seconds); val f = hz * 2.0.pow(take(t, 5.0) / 1200); val dive = .010 * (1 + take(t, .08))
        val body = Breakcore.sine(n) { i -> f * (1 + 11 * exp(-i / (dive * Dsp.RATE))) }
        val hold = Breakcore.frames(.015)
        val env = FloatArray(n) { i -> (if (i < hold) 1.0 else exp(-(i - hold) / (seconds * .42 * Dsp.RATE))).toFloat() }
        val click = Breakcore.svf(Breakcore.noise(Breakcore.frames(.003), Dsp.seedFor("redline-click", t)), 1.4f, 2) { 2500.0 }
        val raw = FloatArray(n) { i -> body[i] * env[i] + (if (i < click.size) .5f * click[i] else 0f) }
        // A low note's edges are slow, so a soft clip rounds them and the top falls away above
        // about 1 kHz (measured). Heavy drive sharpens the edges; a presence lift after the clipper,
        // as on a distorted kick channel, puts the zz on top. Softer hits drive far less.
        val clipped = Splinter.clip(Breakcore.scale(raw, .9), 1 + drive * v.pow(1.5))
        val presence = Dsp.Biquad().apply { peaking(2500f, 6f, .8f) }
        val lifted = FloatArray(clipped.size) { presence.process(clipped[it]) }
        return Breakcore.scale(Breakcore.svf(lifted, 1.4f, 0) { 9000.0 }, .6 * v.pow(.5))
    }

    /** Filtered noise with a decay, clipped by force: the snares, hats and crash. */
    private fun noiseHit(seed: String, v: Double, t: Int, seconds: Double, tau: Double, mode: Int, hz: Double, k: Float, drive: Double): FloatArray {
        val n = Breakcore.frames(seconds)
        val f = Breakcore.svf(Breakcore.noise(n, Dsp.seedFor(seed, t)), k, mode) { hz * (1 + take(t, .04)) }
        val e = Breakcore.env(n, tau, .5)
        return Breakcore.scale(Splinter.clip(FloatArray(n) { f[it] * e[it] }, 1 + drive * v), .7 * v.pow(.6))
    }

    private fun snare(v: Double, t: Int): FloatArray {
        val n = Breakcore.frames(.22)
        val noise = noiseHit("redline-snare", 1.0, t, .22, .07, 1, 1800.0, .6f, 0.0)
        val tone = Breakcore.sine(n) { i -> 175 + 25 * exp(-i / (.02 * Dsp.RATE)) }
        val e = Breakcore.env(n, .05, .5)
        val raw = FloatArray(n) { i -> .6f * noise[i] + .7f * tone[i] * e[i] }
        return Breakcore.scale(Splinter.clip(Breakcore.scale(raw, v), 2 + 6 * v), .75 * v.pow(.5))
    }

    /** Noise through a band-pass so narrow it nearly rings on its own, swept, then clipped. */
    private fun scream(v: Double, t: Int, seconds: Double, from: Double, to: Double, swell: Boolean): FloatArray {
        val n = Breakcore.frames(seconds)
        val bp = Breakcore.svf(Breakcore.noise(n, Dsp.seedFor("redline-scream", t)), .12f, 1) { i -> from * (to / from).pow(i.toDouble() / n) * (1 + take(t, .03)) }
        val e = FloatArray(n) { i -> val p = i.toDouble() / n
            (min(1.0, i / (.01 * Dsp.RATE)) * (if (swell) .2 + .8 * p else 1 - .5 * p) * min(1.0, (n - i) / (.02 * Dsp.RATE))).toFloat() }
        return Breakcore.scale(Splinter.clip(FloatArray(n) { bp[it] * e[it] * 4 }, 1 + 6 * v), .7 * v.pow(.6))
    }

    override fun renderPad(pad: Int, velocity: Double, take: Int): Snip {
        require(pad in layout.indices && velocity.isFinite() && velocity in 0.0..1.0 && take >= 0)
        val v = velocity; val t = take; val beat = Breakcore.beat(bpm)
        if (v == 0.0) return Snip(FloatArray(Breakcore.frames(.1)), 1, Dsp.RATE)
        val g = GAINS[pad]
        return when (pad) {
            0, 1, 2, 3 -> Breakcore.finish(kick(ROOT_HZ * semis(listOf(0, 3, 7, 12)[pad]), v, t), g, .45)
            4 -> Breakcore.finish(kick(ROOT_HZ, v, t, .14, 80.0), g, .16)
            5 -> {
                // Four thirty-seconds of a short kick, then the full one on the next beat's first sixteenth.
                val step = (beat / 8).roundToInt()
                Breakcore.finish(Breakcore.mix(4 * step + Breakcore.frames(.45),
                    *(0..3).map { k -> k * step to Splinter.chop(kick(ROOT_HZ, v * (.6 + .1 * k), t * 8 + k, .14, 80.0), step) }.toTypedArray(),
                    4 * step to kick(ROOT_HZ, v, t)), g, .75)
            }
            6 -> Breakcore.finish(snare(v, t), g, .25)
            7 -> {
                val n = Breakcore.frames(.3)
                val noise = Breakcore.svf(Breakcore.noise(n, Dsp.seedFor("redline-clap", t)), .7f, 1) { 1300.0 }
                val env = FloatArray(n)
                for ((k, ms) in listOf(0.0, 7.0, 15.0, 24.0 + take(t, 1.5)).withIndex()) {
                    val at = Breakcore.frames(ms / 1000); val tau = if (k == 3) .05 else .004
                    for (i in at until n) env[i] += exp(-(i - at) / (tau * Dsp.RATE)).toFloat()
                }
                Breakcore.finish(Breakcore.scale(Splinter.clip(FloatArray(n) { noise[it] * env[it] * v.toFloat() }, 3 + 6 * v), .7 * v.pow(.5)), g, .3)
            }
            8 -> Breakcore.finish(noiseHit("redline-hat", v, t, .06, .012, 2, 7000.0, 1.0f, 2.0), g, .06)
            9 -> Breakcore.finish(noiseHit("redline-open", v, t, .3, .09, 2, 6000.0, .6f, 2.0), g, .3)
            10 -> {
                // Noise and six inharmonic partials, the crash's metal, clipped together.
                val n = Breakcore.frames(1.1); val e = Breakcore.env(n, .3, .5)
                val metal = listOf(3150.0, 4270.0, 5190.0, 6630.0, 7480.0, 8810.0).mapIndexed { k, hz -> Breakcore.sine(n, .17 * k + .05 * t) { hz } }
                val noise = Breakcore.svf(Breakcore.noise(n, Dsp.seedFor("redline-crash", t)), 1f, 2) { 4000.0 }
                val raw = FloatArray(n) { i -> (.5f * noise[i] + .12f * metal.sumOf { it[i].toDouble() }.toFloat()) * e[i] * v.toFloat() }
                Breakcore.finish(Breakcore.scale(Splinter.clip(raw, 2 + 4 * v), .7 * v.pow(.5)), g, 1.1)
            }
            11 -> Breakcore.finish(scream(v, t, .6, 800.0, 2400.0, false), g, .6)
            12 -> {
                // A comb tuned to A4 fed back at .97 and struck by a click and a breath of noise.
                val n = Breakcore.frames(.4); val d = (Dsp.RATE / (440.0 * 2.0.pow(take(t, 6.0) / 1200))).roundToInt()
                val excite = Breakcore.noise(Breakcore.frames(.008), Dsp.seedFor("redline-stab", t))
                val y = FloatArray(n)
                for (i in 0 until n) y[i] = (if (i < excite.size) excite[i] * v.toFloat() else 0f) + (if (i >= d) .97f * y[i - d] else 0f)
                val e = FloatArray(n) { i -> min(1.0, (n - i) / (.05 * Dsp.RATE)).toFloat() }
                Breakcore.finish(Breakcore.scale(Splinter.clip(FloatArray(n) { y[it] * e[it] * .5f }, 2 + 6 * v), .6 * v.pow(.5)), g, .4)
            }
            13 -> {
                val k = kick(ROOT_HZ, v, t)
                Breakcore.finish(Crunch.process(Snip(k, 1, Dsp.RATE), mapOf("BITS" to .9f, "RATE" to .7f, "TONE" to .6f, "GRIT" to .4f)).samples, g, .45)
            }
            14 -> {
                // Noise opened and shut on every thirty-second for one beat.
                val n = beat.roundToInt(); val gate = beat / 8
                val lp = Breakcore.svf(Breakcore.noise(n, Dsp.seedFor("redline-gate", t)), 1f, 0) { 4000.0 }
                val raw = FloatArray(n) { i -> if ((i / gate).toInt() % 2 == 0) lp[i] * v.toFloat() else 0f }
                Breakcore.finish(Breakcore.scale(Splinter.clip(Breakcore.fadeIn(raw), 2 + 4 * v), .7 * v.pow(.5)), g, .3)
            }
            15 -> {
                val n = Breakcore.frames(.4); val s = Breakcore.sine(n, .1 * t) { i -> (90 + 130 * exp(-i / ((.08 + take(t, .01)) * Dsp.RATE))) * 2.0.pow(take(t, 15.0) / 1200) }
                val e = Breakcore.env(n, .12, 1.0)
                Breakcore.finish(Breakcore.scale(Splinter.clip(FloatArray(n) { s[it] * e[it] * v.toFloat() }, 2 + 4 * v), .75 * v.pow(.5)), g, .4)
            }
            16 -> {
                // Thirty-seconds for four beats at one rate: no speeding up, only louder and dirtier.
                val step = beat / 8
                val parts = (0 until 32).map { k -> (k * step).roundToInt() to Splinter.chop(snare(v * (.3 + .7 * k / 31), t * 32 + k), step.roundToInt()) }
                Breakcore.finish(Breakcore.mix((4 * beat).roundToInt() + Breakcore.frames(.25), *parts.toTypedArray(),
                    (4 * beat).roundToInt() to snare(v, t * 32 + 32)), g, 1.3)
            }
            17 -> Breakcore.finish(scream(v, t, 4 * beat / Dsp.RATE, 400.0, 5000.0, true), g, 1.0)
            else -> error("pad")
        }
    }

    private val GAINS = doubleArrayOf(0.61, 0.609, 0.613, 0.62, 0.599, 0.587, 0.906, 0.972, 0.845, 0.788, 0.783, 0.861, 0.881, 0.535, 0.889, 0.954, 0.767, 0.804)
}
