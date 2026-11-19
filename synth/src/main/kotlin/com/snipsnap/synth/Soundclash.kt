package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.*

/** SOUNDCLASH: the breakcore family's sound system. Subs, reese, siren, rewind and echo,
 * the half of a breakcore track that is not the break.
 *
 * The characteristic gesture is: sub drop -> siren climb -> rewind -> slam.
 *
 * Sources: the approved Spore kick and snare, SKIN, [Siren], [Echo] and [Spring] from the
 * rack, and three things synthesized here: sine subs with a pitch fall, a two-saw reese
 * through the house filter, and a spin-back read at 4x so the fast backwards pass folds
 * nothing into the band. Echo repeats are set in beats at [bpm], so a throw lands on the grid.
 *
 * Velocity drives tone, not only level: harder opens the filters, drives the subs into
 * their harmonics and pushes the siren's grit. Every constant awaits the owner's listen. */
object Soundclash : BreakcoreKit {
    const val VERSION = 1
    override val id = "soundclash-breakcore"
    override val title = "Soundclash Breakcore"
    override val bpm = 175

    /** The key the tuned pads sit in: F. Sub Low on F1, Sub High on C2, the reese on F1. */
    const val ROOT_HZ = 43.65

    override val layout: List<Splinter.Part> = listOf(
        Splinter.Part("Slam Kick", DrumClass.KICK, 1f),
        Splinter.Part("Sub Drop", DrumClass.TONAL, .85f),
        Splinter.Part("Dub Rim", DrumClass.SNARE, .6f),
        Splinter.Part("Dub Snare", DrumClass.SNARE, .85f),
        Splinter.Part("Reese Stab", DrumClass.TONAL, .7f),
        Splinter.Part("Wobble Reese", DrumClass.TONAL, .65f),
        Splinter.Part("Siren", DrumClass.PERC, .5f),
        Splinter.Part("Laser", DrumClass.PERC, .45f),
        Splinter.Part("Horn Stab", DrumClass.TONAL, .55f),
        Splinter.Part("Rewind", DrumClass.PERC, .75f),
        Splinter.Part("Echo Clap", DrumClass.CLAP, .7f),
        Splinter.Part("Tight Hat", DrumClass.HAT_CLOSED, .4f, 1),
        Splinter.Part("Dub Hat", DrumClass.HAT_OPEN, .4f, 1),
        Splinter.Part("Sub Low", DrumClass.TONAL, .85f),
        Splinter.Part("Sub High", DrumClass.TONAL, .8f),
        Splinter.Part("Noise Sweep", DrumClass.PERC, .5f),
        Splinter.Part("Dub Roll", DrumClass.LOOP, .7f),
        Splinter.Part("Siren Rise", DrumClass.LOOP, .55f),
    )

    private fun take(t: Int, spread: Double) = listOf(0.0, -spread, spread)[t % 3] * (1 + t / 3)

    /** A sub: a sine falling from [startRatio] times its note onto it, held, then driven a little
     * into its harmonics (harder drives more), so a small speaker still hears where it is. */
    private fun sub(hz: Double, v: Double, t: Int, seconds: Double, startRatio: Double = 2.0, fallMs: Double = 40.0, tau: Double = .35): FloatArray {
        val n = Breakcore.frames(seconds); val f = hz * 2.0.pow(take(t, 4.0) / 1200)
        val s = Breakcore.sine(n) { i -> f * (1 + (startRatio - 1) * exp(-i / (fallMs / 1000 * Dsp.RATE))) }
        val e = Breakcore.env(n, tau, 2.0)
        return Breakcore.scale(Breakcore.drive(FloatArray(n) { s[it] * e[it] }, 1.0 + 1.6 * v), v.pow(.8))
    }

    /** The reese: two saws a few cents apart and a sine under them, through a low-pass that
     * opens with force. [cutoff] gives the filter at each frame, before velocity. */
    private fun reese(hz: Double, v: Double, t: Int, seconds: Double, cutoff: (Int) -> Double): FloatArray {
        val n = Breakcore.frames(seconds); val cents = 9 + take(t, 1.5)
        val a = Breakcore.saw(n, .13 * t) { hz * 2.0.pow(cents / 1200) }
        val b = Breakcore.saw(n, .61 + .29 * t) { hz * 2.0.pow(-cents / 1200) }
        val sub = Breakcore.sine(n) { hz }
        val raw = FloatArray(n) { (.5f * (a[it] + b[it]) + .6f * sub[it]) }
        val open = .35 + .65 * v
        val lp = Breakcore.svf(raw, 1.1f, 0) { i -> 120 + cutoff(i) * open }
        val gate = FloatArray(n) { i -> (min(1.0, i / (.004 * Dsp.RATE)) * min(1.0, (n - i) / (.03 * Dsp.RATE))).toFloat() }
        return Breakcore.scale(Breakcore.drive(FloatArray(n) { lp[it] * gate[it] }, 1.2 + 1.5 * v), v.pow(.8))
    }

    private fun clap(v: Double, t: Int): FloatArray {
        val n = Breakcore.frames(.35); val noise = Breakcore.noise(n, Dsp.seedFor("soundclash-clap", t))
        val bp = Breakcore.svf(noise, .8f, 1) { 900 + 500 * v }
        val env = FloatArray(n)
        for ((k, ms) in listOf(0.0, 9.0, 19.0, 31.0 + take(t, 2.0)).withIndex()) {
            val at = Breakcore.frames(ms / 1000); val tau = if (k == 3) .06 else .006
            for (i in at until n) env[i] += exp(-(i - at) / (tau * Dsp.RATE)).toFloat()
        }
        return Breakcore.scale(FloatArray(n) { bp[it] * env[it] }, .9 * v.pow(1.1))
    }

    /** One beat of a small break: what the rewind pulls back. */
    private fun phrase(v: Double, t: Int): FloatArray {
        val b = Breakcore.beat(bpm); val hat = { k: Int -> Splinter.skin(SkinVoice.HAT_CLOSED, v * .7, t * 8 + k, mapOf("DECAY" to .05f)) }
        return Breakcore.mix((2 * b).roundToInt(), 0 to Splinter.kick(v, t), 0 to hat(0), (b / 2).roundToInt() to hat(1),
            b.roundToInt() to Splinter.snare(v, t), b.roundToInt() to hat(2), (1.5 * b).roundToInt() to Splinter.kick(v * .7, t + 3))
    }

    override fun renderPad(pad: Int, velocity: Double, take: Int): Snip {
        require(pad in layout.indices && velocity.isFinite() && velocity in 0.0..1.0 && take >= 0)
        val v = velocity; val t = take; val beat = Breakcore.beat(bpm)
        if (v == 0.0) return Snip(FloatArray(Breakcore.frames(.1)), 1, Dsp.RATE)
        val g = GAINS[pad]
        fun echo(x: FloatArray, beats: Double = .75, repeat: Float = .5f, mix: Float = .45f) = Breakcore.echo(x, bpm, beats, repeat, .45f, mix)
        return when (pad) {
            0 -> Breakcore.finish(Breakcore.drive(Breakcore.mix(Breakcore.frames(.6), 0 to Splinter.kick(v, t, tune = .35, strike = .8, body = .6, click = .7),
                0 to sub(ROOT_HZ * 1.1, v, t, .55, startRatio = 1.6, tau = .16)), 1.3), g, .6)
            1 -> {
                // A sine sliding from 120 Hz to 32 over a second and a half.
                val n = Breakcore.frames(1.6); val s = Breakcore.sine(n) { i -> (32 + 88 * exp(-i / ((.45 + take(t, .04)) * Dsp.RATE))) * 2.0.pow(take(t, 8.0) / 1200) }
                val e = FloatArray(n) { i -> (min(1.0, i / (.005 * Dsp.RATE)) * min(1.0, (n - i) / (.3 * Dsp.RATE))).toFloat() }
                Breakcore.finish(Breakcore.scale(Breakcore.drive(FloatArray(n) { s[it] * e[it] }, 1.0 + 1.8 * v), v.pow(.8)), g, 1.6)
            }
            2 -> Breakcore.finish(echo(Splinter.skin(SkinVoice.STICK, v, t, mapOf("TUNE" to .6f, "DECAY" to .2f, "BODY" to .4f, "CLICK" to .5f)), repeat = .55f, mix = .5f), g, 1.8)
            3 -> Breakcore.finish(echo(Spring.process(Snip(Splinter.snare(v, t, tune = .45, body = .55, wires = .5), 1, Dsp.RATE),
                mapOf("SIZE" to .55f, "TONE" to .5f, "MIX" to .4f)).samples), g, 1.8)
            4 -> Breakcore.finish(reese(ROOT_HZ, v, t, .45) { i -> 250 + 1500 * exp(-i / (.12 * Dsp.RATE)) }, g, .5)
            5 -> {
                // The reese held two beats, its filter opening and closing on every eighth.
                val period = beat / 2
                Breakcore.finish(reese(ROOT_HZ, v, t, 2 * beat / Dsp.RATE) { i -> 150 + 1700 * (.5 - .5 * cos(2 * PI * i / period)) }, g, 1.0)
            }
            6, 7 -> {
                val voice = if (pad == 6) SirenVoice.WAIL else SirenVoice.LASER
                val m = Siren.defaults(voice) + mapOf("HOLD" to .12f, "GRIT" to (.25 + .45 * v).toFloat(), "RATE" to (Siren.defaults(voice).getValue("RATE") + take(t, .03)).toFloat())
                Breakcore.finish(echo(Breakcore.scale(Siren.render(voice, m).samples, v.pow(1.1)), repeat = .55f), g, 2.0)
            }
            8 -> {
                // A stab: root, fifth and octave on D, falling three semitones in its last 80 ms.
                val n = Breakcore.frames(.26); val fall = Breakcore.frames(.18)
                val bend = { i: Int -> 2.0.pow(-3.0 * max(0, i - fall) / (n - fall) / 12) }
                val chord = listOf(1.0, 1.4983, 2.0).mapIndexed { k, r -> Breakcore.saw(n, .21 * k + .1 * t) { i -> 146.83 * r * bend(i) * 2.0.pow(take(t, 3.0) / 1200) } }
                val raw = FloatArray(n) { i -> chord.sumOf { it[i].toDouble() }.toFloat() / 3 }
                val lp = Breakcore.svf(raw, 1.3f, 0) { 900 + 3200 * v }
                val gate = FloatArray(n) { i -> (min(1.0, i / (.005 * Dsp.RATE)) * min(1.0, (n - i) / (.02 * Dsp.RATE))).toFloat() }
                Breakcore.finish(echo(Breakcore.scale(FloatArray(n) { lp[it] * gate[it] }, v.pow(.9)), beats = .5, repeat = .4f, mix = .35f), g, 1.3)
            }
            9 -> {
                // The spin-back: the phrase plays, a hand pulls the record back, it spins backwards and slows.
                val n = Breakcore.frames(1.2); val grab = Breakcore.frames(.33 + take(t, .015)); val pull = Breakcore.frames(.12)
                Breakcore.finish(Breakcore.spin(phrase(v, t), n) { i -> when {
                    i < grab -> 1.0
                    i < grab + pull -> 1.0 - 4.0 * (i - grab) / pull
                    else -> -3.0 * exp(-(i - grab - pull) / (.35 * Dsp.RATE))
                } }, g, 1.2)
            }
            10 -> Breakcore.finish(echo(clap(v, t), repeat = .5f, mix = .4f), g, 1.8)
            11 -> Breakcore.finish(Splinter.skin(SkinVoice.HAT_CLOSED, v, t, mapOf("DECAY" to .04f, "TONE" to .65f)), g, .5)
            12 -> Breakcore.finish(echo(Splinter.skin(SkinVoice.HAT_OPEN, v, t, mapOf("DECAY" to .25f, "TONE" to .6f)), beats = .5, repeat = .45f, mix = .35f), g, 1.6)
            13 -> Breakcore.finish(sub(ROOT_HZ, v, t, .9), g, .9)
            14 -> Breakcore.finish(sub(ROOT_HZ * 1.4983, v, t, .9), g, .9)
            15 -> {
                val n = beat.roundToInt(); val noise = Breakcore.noise(n, Dsp.seedFor("soundclash-sweep", t))
                val bp = Breakcore.svf(noise, .5f, 1) { i -> 250 * (6000.0 / 250).pow(1 - i.toDouble() / n) }
                Breakcore.finish(echo(Breakcore.scale(FloatArray(n) { i -> bp[i] * (1 - .6f * i / n) }, .8 * v.pow(1.1)), repeat = .55f), g, 1.8)
            }
            16 -> {
                // Sixteenths for two beats, thirty-seconds for one, a last hit, then the echo carries the fourth beat.
                val hits = List(8) { it * beat / 4 } + List(8) { 2 * beat + it * beat / 8 } + listOf(3 * beat)
                val parts = hits.mapIndexed { k, at -> at.roundToInt() to Breakcore.scale(Splinter.snare(v * (.4 + .6 * k / hits.lastIndex), t * 32 + k, tune = .45, body = .5, wires = .55), 1.0) }
                Breakcore.finish(echo(Breakcore.mix((4 * beat).roundToInt(), *parts.toTypedArray()), repeat = .7f, mix = .55f), g, 2.4)
            }
            17 -> {
                // A square wail whose rate speeds up from 3 to 12 per second while its pitch climbs an
                // octave over four beats, with noise opening underneath.
                val n = (4 * beat).roundToInt(); var lfo = 0.0
                val hz = DoubleArray(n) { i -> val p = i.toDouble() / n; lfo += (3 + 9 * p) / Dsp.RATE
                    (440 * 2.0.pow(p)) * (1 + .12 * sin(2 * PI * lfo)) }
                val sq = Breakcore.saw(n, .3 * t) { hz[it] }.let { a -> val b = Breakcore.saw(n, .3 * t + .5) { hz[it] }; FloatArray(n) { a[it] - b[it] } }
                val noise = Breakcore.svf(Breakcore.noise(n, Dsp.seedFor("soundclash-rise", t)), .7f, 1) { i -> 300 * (25.0).pow(i.toDouble() / n) }
                val out = FloatArray(n) { i -> val p = i.toFloat() / n; (.25f + .75f * p) * (.35f * sq[i] + .5f * p * noise[i]) }
                Breakcore.finish(echo(Breakcore.scale(Breakcore.drive(out, 1.0 + v), v.pow(1.1)), beats = .5, repeat = .4f, mix = .3f), g, 2.6)
            }
            else -> error("pad")
        }
    }

    private val GAINS = doubleArrayOf(0.59, 0.724, 1.28, 1.056, 0.539, 0.559, 3.299, 3.202, 0.959, 0.987, 1.405, 1.312, 1.31, 0.724, 0.754, 0.849, 0.953, 0.842)
}
