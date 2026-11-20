package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/** STROBE: the 1992 rave of the breakcore family, made from synthesis. A detuned pulse and saw stack that
 * scoops up into its note and dives away (the Whine; its name is a gesture, not a brand), a hard bright piano
 * stab, a punchy organ bass, an orchestra hit, a clean bleep over a sub, and a break from a slower tempo played
 * faster, so its pitch rose with its speed.
 *
 * The characteristic gesture is: a whine dives -> the piano stab answers -> a sped-up break runs under both.
 *
 * Sources: [Oven] GRAND for the piano, [Tonewheel] STAB for the organ, [Glint] VOWEL for the voice stab, SPORE
 * kick and snare and SKIN hats for the break, [Room], [Ensemble] and [Crunch] from the rack, and four things
 * synthesized here: the Whine stack, the saw chord stab, the bleeps and the kick. Cents never come from TUNE
 * (every engine snaps it to semitones); a take's tuning is a constant-speed [Breakcore.spin] of the finished
 * note, or a constant ratio on the oscillator's own frequency. Takes differ by cents, voicing spread, timing and
 * seed. Velocity opens the filters, drives harder and, for the Whine, deepens the scoop.
 * Every constant awaits the owner's listen. */
object Strobe : BreakcoreKit {
    const val VERSION = 1
    const val ID = "strobe-breakcore"
    override val id = ID
    override val title = "STROBE"
    override val bpm = 145

    /** The tempo the break was played at before it was sped up to [bpm]: the speed ratio is the pitch rise (about 4 semitones). */
    const val BREAK_PLAYED_BPM = 115

    override val layout: List<Splinter.Part> = listOf(
        Splinter.Part("Whine", DrumClass.TONAL, .75f, 2),
        Splinter.Part("Whine Dive", DrumClass.TONAL, .75f, 2),
        Splinter.Part("Whine Stab", DrumClass.TONAL, .7f, 2),
        Splinter.Part("Rave Piano", DrumClass.TONAL, .8f, 3),
        Splinter.Part("Piano Low", DrumClass.TONAL, .8f, 3),
        Splinter.Part("Organ Bass", DrumClass.TONAL, .75f),
        Splinter.Part("Saw Stab", DrumClass.TONAL, .7f),
        Splinter.Part("Orchestra Hit", DrumClass.PERC, .85f),
        Splinter.Part("Bleep", DrumClass.TONAL, .55f),
        Splinter.Part("Bleep Bass", DrumClass.TONAL, .8f),
        Splinter.Part("Vox Stab", DrumClass.TONAL, .55f),
        Splinter.Part("Rave Kick", DrumClass.KICK, 1f),
        Splinter.Part("Rave Snare", DrumClass.SNARE, .9f),
        Splinter.Part("Closed Hat", DrumClass.HAT_CLOSED, .4f, 1),
        Splinter.Part("Open Hat", DrumClass.HAT_OPEN, .4f, 1),
        Splinter.Part("Sped Break", DrumClass.LOOP, .75f),
        Splinter.Part("Stab Roll", DrumClass.LOOP, .7f),
        Splinter.Part("Whine Rise", DrumClass.LOOP, .6f),
    )

    private fun shape(v: Double) = v.pow(.8)
    private fun cents(t: Int, spread: Double) = listOf(0.0, -spread, spread)[t % 3] * (1 + t / 3)
    private fun detune(x: FloatArray, cents: Double): FloatArray = Breakcore.spin(x, x.size) { 2.0.pow(cents / 1200) }
    private fun seconds(frames: Int) = frames.toDouble() / Dsp.RATE

    /** The first [seconds] of [x], its last [fade] seconds a raised cosine if it had to be cut. */
    private fun fit(x: FloatArray, seconds: Double, fade: Double = .1): FloatArray {
        val n = Breakcore.frames(seconds)
        if (x.size <= n) return x
        return x.copyOf(n).also { Dsp.fadeTailCosine(it, fade.toFloat()) }
    }

    /** A linear attack of [attackMs] and a linear release over the last [releaseMs]. */
    private fun gate(n: Int, attackMs: Double, releaseMs: Double): FloatArray {
        val a = max(1, Breakcore.frames(attackMs / 1000)); val r = max(1, Breakcore.frames(releaseMs / 1000))
        return FloatArray(n) { i -> (min(1.0, i.toDouble() / a) * min(1.0, (n - i).toDouble() / r)).toFloat() }
    }

    // ---- the Whine: a thick, buzzing stack ----

    /** Four detuned saws, two pulse pairs with PWM (a saw minus a second saw whose frequency swings, so the
     * gap between them, the pulse width, swings), and an octave-sub saw; through a low-pass, a chorus and a
     * clipper. [hz] is the stack's centre pitch per frame (scoop, dive and rise live there). Vibrato is added
     * here, from [vibFrom] seconds on. */
    private fun whine(v: Double, t: Int, n: Int, hz: (Int) -> Double, cutoff: (Int) -> Double, level: (Int) -> Double, vibFrom: Double? = .2): FloatArray {
        val r = Random(Dsp.seedFor("strobe-whine", t).toLong())
        val spread = 20.0 * (1 + .15 * listOf(0, -1, 1)[t % 3])    // cents, the voicing spread by take
        val tune = 2.0.pow(cents(t, 4.0) / 1200)
        val vibRate = 5.6 + .35 * listOf(0, -1, 1)[t % 3]
        val base = DoubleArray(n) { i ->
            val s = i.toDouble() / Dsp.RATE
            val vib = if (vibFrom == null) 0.0 else {
                val ramp = ((s - vibFrom) / .2).coerceIn(0.0, 1.0)
                18.0 * ramp * sin(2 * PI * vibRate * s)                // +-18 cents
            }
            hz(i) * tune * 2.0.pow(vib / 1200)
        }
        fun at(c: Double): (Int) -> Double { val k = 2.0.pow(c / 1200); return { i -> base[i] * k } }
        val saws = listOf(-1.0, -.4, .4, 1.0).map { Breakcore.saw(n, r.nextDouble(), at(it * spread)) }
        val pwm = listOf(-.7 to 2.3, .7 to 3.4).map { (c, rate) ->
            val p0 = r.nextDouble(); val centre = at(c * spread); val lfoHz = rate + .25 * (t % 3)
            val a = Breakcore.saw(n, p0, centre)
            val b = Breakcore.saw(n, (p0 + .5) % 1.0) { i -> centre(i) * (1 + .02 * cos(2 * PI * lfoHz * i / Dsp.RATE)) }
            FloatArray(n) { (a[it] - b[it]) * .5f }
        }
        val sub = Breakcore.saw(n, r.nextDouble()) { base[it] / 2 }
        val raw = FloatArray(n) { i ->
            (.15f * (saws[0][i] + saws[1][i] + saws[2][i] + saws[3][i]) + .22f * (pwm[0][i] + pwm[1][i]) + .4f * sub[i])
        }
        val lp = Breakcore.svf(raw, 1.0f, 0, cutoff)
        val chorus = Ensemble.process(Snip(lp, 1, Dsp.RATE), mapOf("DEPTH" to .7f, "RATE" to (.5 + .06 * (t % 3)).toFloat(), "WIDTH" to 0f)).samples
        return Breakcore.drive(FloatArray(n) { chorus[it] * level(it).toFloat() * 1.4f }, 1.0 + 2.0 * v)
    }

    private fun scoop(s: Double, v: Double): Double = -(3 + 2 * v) * (1 - s / .08).coerceAtLeast(0.0).pow(2)

    private fun whinePad(v: Double, t: Int): FloatArray {
        val n = Breakcore.frames(1.2); val env = gate(n, 3.0, 70.0)
        return whine(v, t, n, { i -> 110.0 * 2.0.pow(scoop(i.toDouble() / Dsp.RATE, v) / 12) }, { 3200 + 9000 * v },
            { i -> env[i] * exp(-i / (2.4 * Dsp.RATE)) })
    }

    /** Hold on A2, then an octave down over one beat, then hold on A1. */
    private fun whineDive(v: Double, t: Int): FloatArray {
        val hold = .15; val beat = Breakcore.beat(bpm) / Dsp.RATE
        val n = Breakcore.frames(hold + beat + .45); val env = gate(n, 3.0, 90.0)
        return whine(v, t, n, { i -> val s = i.toDouble() / Dsp.RATE
            110.0 * 2.0.pow((scoop(s, v) - 12 * ((s - hold) / beat).coerceIn(0.0, 1.0)) / 12) }, { 2600 + 9000 * v },
            { i -> env[i] * exp(-i / (2.0 * Dsp.RATE)) })
    }

    private fun whineStab(v: Double, t: Int): FloatArray {
        val n = Breakcore.frames(.2); val env = gate(n, 2.0, 45.0)
        return whine(v, t, n, { i -> 164.81 * 2.0.pow(scoop(i.toDouble() / Dsp.RATE, v) / 12) }, { i -> 1800 + (2500 + 9000 * v) * exp(-i / (.06 * Dsp.RATE)) },
            { i -> env[i] * exp(-i / (.16 * Dsp.RATE)) }, vibFrom = null)
    }

    /** An octave up over four beats, the filter opening, noise rising underneath. */
    private fun whineRise(v: Double, t: Int): FloatArray {
        val n = (4 * Breakcore.beat(bpm)).roundToInt()
        val w = whine(v, t, n, { i -> 110.0 * 2.0.pow(i.toDouble() / n) }, { i -> val p = i.toDouble() / n; 350 + (2500 + 9500 * v) * p.pow(1.5) },
            { i -> val p = i.toDouble() / n; .3 + .7 * p }, vibFrom = .2)
        val noise = Breakcore.svf(Breakcore.noise(n, Dsp.seedFor("strobe-rise", t)), .8f, 1) { i -> 400 * (14.0).pow(i.toDouble() / n) }
        return FloatArray(n) { i -> val p = i.toFloat() / n; w[i] + (.12f + .25f * v.toFloat()) * p * p * noise[i] }
    }

    // ---- the piano stab ----

    private val notes = ConcurrentHashMap<Pair<Int, Double>, FloatArray>()
    private val stabs = ConcurrentHashMap<Triple<Int, Double, Int>, FloatArray>()
    /** Am9 (A2 G3 C4 E4 B4), and the same a fourth lower. */
    private val CHORDS = listOf(intArrayOf(45, 55, 60, 64, 71), intArrayOf(40, 50, 55, 59, 66))

    private fun pianoNote(midi: Int, v: Double): FloatArray = notes.getOrPut(midi to v) {
        Oven.render(OvenVoice.GRAND, mapOf("HAMMER" to (.7 + .25 * v).toFloat(), "RING" to .3f, "HALO" to .1f), midi, v.toFloat()).samples
    }

    private fun strumRank(n: Int, t: Int): IntArray = when (t % 3) {
        0 -> IntArray(n) { it }
        1 -> IntArray(n) { n - 1 - it }
        else -> { val order = (0 until n).sortedWith(compareBy({ abs(2 * it - (n - 1)) }, { it })); IntArray(n) { order.indexOf(it) } }
    }

    /** The stab: the five notes with a tight strum (2 ms end to end when hard, 7 when soft), a take's own cents,
     * cut to 380 ms; then a gentle [Crunch], a lift above 2.8 kHz and a light clip, harder when harder. */
    private fun stab(c: Int, v: Double, t: Int): FloatArray = stabs.getOrPut(Triple(c, v, t)) {
        val voicing = CHORDS[c]; val n = voicing.size; val rank = strumRank(n, t)
        val r = Random(Dsp.seedFor("strobe-stab", c, t).toLong())
        val stepMs = (2 + 5 * (1 - v)) / (n - 1)
        val out = FloatArray(Breakcore.frames(1.0))
        for ((k, midi) in voicing.withIndex()) {
            val src = pianoNote(midi, v); val at = Breakcore.frames(rank[k] * stepMs / 1000)
            val w = (1.0 - .35 * k / (n - 1)) * (1 + .06 * r.nextGaussian())
            for (j in src.indices) if (at + j in out.indices) out[at + j] += (src[j] * w).toFloat()
        }
        val x = fit(detune(out, cents(t, 4.0)), .38, .14)
        val y = Crunch.process(Snip(x, 1, Dsp.RATE), mapOf("BITS" to (.3 + .1 * v).toFloat(), "RATE" to .1f, "TONE" to .85f, "GRIT" to (.2 + .35 * v).toFloat())).samples
        val bright = Breakcore.svf(y, 1.0f, 2) { 2800.0 }
        Breakcore.drive(FloatArray(y.size) { (y[it] + bright[it] * (.2 + .45 * v)).toFloat() }, 1.0 + v)
    }

    /** Where Stab Roll's hits land, in beats: eighths, sixteenths, then thirty-seconds for the last two beats. */
    internal fun rollOnsetBeats(): List<Double> =
        listOf(0.0, .5) + List(4) { 1 + it * .25 } + List(8) { 2 + it * .125 } + List(8) { 3 + it * .125 }

    private fun stabRoll(v: Double, t: Int): FloatArray {
        val beat = Breakcore.beat(bpm); val r = Random(Dsp.seedFor("strobe-roll", t).toLong())
        val beats = rollOnsetBeats()
        val onsets = beats.mapIndexed { k, b ->
            val jitter = if (k == 0) 0.0 else (r.nextGaussian() * .0012 * Dsp.RATE).coerceIn(-.003 * Dsp.RATE, .003 * Dsp.RATE)
            (b * beat + jitter).roundToInt()
        }
        val src = stab(0, v, t)
        val up = (0..3).map { s -> Breakcore.spin(src, src.size) { 2.0.pow(s / 12.0) } }       // a semitone higher each beat
        val out = FloatArray(onsets.last() + src.size); val fade = Breakcore.frames(.002)
        for ((k, at) in onsets.withIndex()) {
            val part = up[min(3, beats[k].toInt())]; val level = .3 + .7 * k / onsets.lastIndex
            val end = onsets.getOrNull(k + 1)?.minus(at) ?: part.size
            for (j in 0 until min(part.size, end)) {
                val choke = if (end - j < fade) (end - j).toDouble() / fade else 1.0
                out[at + j] += (part[j] * level * choke).toFloat()
            }
        }
        return out
    }

    // ---- the rest of the voices ----

    private fun organBass(v: Double, t: Int): FloatArray {
        val m = Tonewheel.defaults(TonewheelVoice.STAB) + mapOf("TUNE" to 0f, "PERC" to (.55 + .4 * v).toFloat(),
            "DIRT" to (.2 + .5 * v).toFloat(), "WARBLE" to (.3 + .15 * (t % 3)).toFloat())
        val src = detune(Tonewheel.render(TonewheelVoice.STAB, m, .16f).samples, cents(t, 4.0))
        val x = fit(src, .45, .12)
        val lp = Breakcore.svf(x, 1.1f, 0) { 1500 + 9000 * v }
        return Breakcore.drive(lp, 1.0 + .8 * v)
    }

    /** Seven saws on Am: A2 E3 A3 C4 E4, and A3 and C4 again a few cents off; through a resonant low-pass that falls fast. */
    private fun sawStab(v: Double, t: Int): FloatArray {
        val n = Breakcore.frames(.4); val r = Random(Dsp.seedFor("strobe-sawstab", t).toLong())
        val spread = 9.0 * (1 + .2 * listOf(0, -1, 1)[t % 3]); val tune = 2.0.pow(cents(t, 4.0) / 1200)
        val voices = listOf(110.0 to 0.0, 164.81 to 0.0, 220.0 to -spread, 261.63 to spread, 329.63 to 0.0, 220.0 to spread, 261.63 to -spread)
        val saws = voices.map { (f, c) -> Breakcore.saw(n, r.nextDouble()) { f * tune * 2.0.pow(c / 1200) } }
        val raw = FloatArray(n) { i -> saws.sumOf { it[i].toDouble() }.toFloat() / 5f }
        val lp = Breakcore.svf(raw, .4f, 0) { i -> 450 + (3500 + 5500 * v) * exp(-i / (.07 * Dsp.RATE)) }
        val e = gate(n, 2.0, 40.0)
        return Breakcore.drive(FloatArray(n) { lp[it] * e[it] * exp(-it / (.12 * Dsp.RATE)).toFloat() }, 1.2 + 1.5 * v)
    }

    /** Saw brass on Am (five notes, each doubled a few cents off), a noise burst and a timpani-like thump, in a room. */
    private fun orchestraHit(v: Double, t: Int): FloatArray {
        val n = Breakcore.frames(.7); val r = Random(Dsp.seedFor("strobe-hit", t).toLong())
        val spread = 8.0 * (1 + .2 * listOf(0, -1, 1)[t % 3]); val tune = 2.0.pow(cents(t, 5.0) / 1200)
        val saws = listOf(110.0, 164.81, 220.0, 261.63, 329.63).flatMap { f -> listOf(-spread, spread).map { c ->
            Breakcore.saw(n, r.nextDouble()) { f * tune * 2.0.pow(c / 1200) } } }
        val brass = Breakcore.svf(FloatArray(n) { i -> saws.sumOf { it[i].toDouble() }.toFloat() / 6f }, 1.0f, 0) { i ->
            1800 + (2500 + 6000 * v) * exp(-i / (.12 * Dsp.RATE)) }
        val be = FloatArray(n) { i -> (min(1.0, i / (.003 * Dsp.RATE)) * (.6 * exp(-i / (.09 * Dsp.RATE)) + .4 * exp(-i / (.3 * Dsp.RATE)))).toFloat() }
        val noise = Breakcore.svf(Breakcore.noise(n, Dsp.seedFor("strobe-hit-noise", t)), .8f, 1) { 2800.0 }
        val ne = Breakcore.env(n, .045, .5)
        val thump = Breakcore.sine(n, .25) { i -> (52 + 90 * exp(-i / (.035 * Dsp.RATE))) * tune }
        val te = Breakcore.env(n, .28, 1.0)
        val dry = FloatArray(n) { i -> brass[i] * be[i] * .75f + noise[i] * ne[i] * (.3f + .3f * v.toFloat()) + thump[i] * te[i] * .7f }
        val roomed = Room.process(Snip(dry, 1, Dsp.RATE), mapOf("SIZE" to .55f, "WALLS" to .45f, "DISTANCE" to .5f)).samples
        return Breakcore.drive(roomed, 1.2 + v)
    }

    /** A clean bleep: a sine with a little square edge, and a sine an octave under it. */
    private fun bleep(v: Double, t: Int): FloatArray {
        val n = Breakcore.frames(.22); val f = 440.0 * 2.0.pow(cents(t, 6.0) / 1200)
        val sine = Breakcore.sine(n) { f }; val sub = Breakcore.sine(n) { f / 2 }
        val a = Breakcore.saw(n, 0.0) { f }; val b = Breakcore.saw(n, .5) { f }
        val edge = (.1 + .3 * v).toFloat()
        val e = FloatArray(n) { i -> (min(1.0, i / (.0006 * Dsp.RATE)) * exp(-i / (.07 * Dsp.RATE))).toFloat() }
        val body = Breakcore.drive(FloatArray(n) { i -> (.8f * sine[i] + edge * (a[i] - b[i]) * .5f + .45f * sub[i]) * e[i] }, 1.0 + v)
        return FloatArray(Breakcore.frames(.0007 * (t % 3))) + body
    }

    /** A sine sub on A1 and a short square click; harder drives the sub into its harmonics. */
    private fun bleepBass(v: Double, t: Int): FloatArray {
        val n = Breakcore.frames(60.0 / bpm); val f = 55.0 * 2.0.pow(cents(t, 5.0) / 1200)
        val sub = Breakcore.sine(n) { f }
        val e = FloatArray(n) { i -> (min(1.0, i / (.002 * Dsp.RATE)) * exp(-i / (.2 * Dsp.RATE)) * min(1.0, (n - i) / (.03 * Dsp.RATE))).toFloat() }
        val a = Breakcore.saw(n, 0.0) { 330.0 }; val b = Breakcore.saw(n, .5) { 330.0 }
        val click = Breakcore.svf(FloatArray(n) { (a[it] - b[it]) * .5f }, 1.0f, 0) { 2500.0 }
        val ce = Breakcore.env(n, .006 + .002 * (t % 3), .1)
        val body = Breakcore.drive(FloatArray(n) { sub[it] * e[it] }, 1.0 + 2.2 * v)
        return FloatArray(n) { body[it] + click[it] * ce[it] * (.15f + .3f * v.toFloat()) }
    }

    private fun voxStab(v: Double, t: Int): FloatArray {
        val m = Glint.defaults(GlintVoice.VOWEL) + mapOf("TUNE" to 19 / 24f, "DECAY" to .25f, "PEAK" to (.5 + .04 * listOf(0, -1, 1)[t % 3]).toFloat())
        val x = fit(detune(Glint.render(GlintVoice.VOWEL, m).samples, cents(t, 5.0)), .4, .12)
        val lp = Breakcore.svf(x, 1.1f, 0) { 2500 + 9000 * v }
        return Breakcore.drive(lp, 1.0 + .8 * v)
    }

    /** A 909-ish kick: a sine diving from high onto A1-ish, driven, with a hard noise click and a pitched ping on top. */
    private fun raveKick(v: Double, t: Int): FloatArray {
        val n = Breakcore.frames(.5)
        val f1 = 48.0 * 2.0.pow(cents(t, 6.0) / 1200); val f0 = 150 + 150 * v; val tau = .022 + .004 * (t % 3)
        val body = Breakcore.sine(n, .25) { i -> f1 + (f0 - f1) * exp(-i / (tau * Dsp.RATE)) }
        val e = Breakcore.env(n, .13, .5)
        val driven = Breakcore.drive(FloatArray(n) { body[it] * e[it] }, 2.2 + 2.4 * v)
        val click = Breakcore.svf(Breakcore.noise(n, Dsp.seedFor("strobe-kick-click", t)), .7f, 1) { 3000.0 + 250 * (t % 3) }
        val ce = Breakcore.env(n, .008, .1)
        val ping = Breakcore.sine(n) { 1800.0 + 200 * (t % 3) }
        val pe = Breakcore.env(n, .005, .1)
        val k = (2.4 + 1.0 * v).toFloat()
        return Breakcore.drive(FloatArray(n) { driven[it] + k * (click[it] * ce[it] + .5f * ping[it] * pe[it]) }, 1.3)
    }

    private fun raveSnare(v: Double, t: Int): FloatArray {
        val s = Splinter.snare(v, t, tune = .6, strike = .85, body = .45, wires = .75)
        val n = max(s.size, Breakcore.frames(.35))
        val bright = Breakcore.svf(Breakcore.noise(n, Dsp.seedFor("strobe-snare", t)), .8f, 1) { 2800.0 + 400 * v }
        val be = Breakcore.env(n, .05, .5)
        val dry = FloatArray(n) { i -> (if (i < s.size) s[i] else 0f) + bright[i] * be[i] * (.25f + .35f * v.toFloat()) }
        val roomed = Room.process(Snip(dry, 1, Dsp.RATE), mapOf("SIZE" to .2f, "WALLS" to .6f, "DISTANCE" to .45f)).samples
        return Breakcore.drive(roomed, 1.2 + 1.2 * v)
    }

    // ---- the sped-up break ----

    private data class BreakHit(val step: Int, val drum: Char, val force: Double)
    /** Sixteenths over one bar: kicks, backbeat snares with ghosts between, eighth-note hats and one open hat. */
    private val BREAK = buildList {
        listOf(0 to 1.0, 6 to .8, 10 to .9, 11 to .5).forEach { (s, f) -> add(BreakHit(s, 'k', f)) }
        listOf(4 to 1.0, 12 to 1.0, 7 to .35, 14 to .4, 15 to .5).forEach { (s, f) -> add(BreakHit(s, 's', f)) }
        for (s in 0 until 16 step 2) if (s != 14) add(BreakHit(s, 'h', if (s % 4 == 0) .8 else .55))
        add(BreakHit(14, 'o', .7))
    }

    private val bars = ConcurrentHashMap<Pair<Double, Int>, FloatArray>()

    /** One bar at [BREAK_PLAYED_BPM], rendered through a room, its tail wrapped onto the start so the bar loops. */
    private fun playedBar(v: Double, t: Int): FloatArray = bars.getOrPut(v to t) {
        val beat = Breakcore.beat(BREAK_PLAYED_BPM); val len = (4 * beat).roundToInt(); val r = Random(Dsp.seedFor("strobe-break", t).toLong())
        val dry = FloatArray(len + Breakcore.frames(.6))
        for ((i, h) in BREAK.withIndex()) {
            val at = (h.step * beat / 4 + if (h.step > 0) r.nextGaussian() * .0012 * Dsp.RATE else 0.0).roundToInt()
            val f = (v * h.force * (1 + .05 * r.nextGaussian())).coerceIn(0.0, 1.0); val strike = t * 64 + i
            val x = when (h.drum) {
                'k' -> Splinter.kick(f, strike, tune = .35, strike = .8, body = .6, click = .7)
                's' -> Splinter.snare(f, strike, tune = .5, strike = .8, body = .5, wires = .6)
                'h' -> Splinter.skin(SkinVoice.HAT_CLOSED, f, strike, mapOf("DECAY" to .05f, "TONE" to .65f))
                else -> Splinter.skin(SkinVoice.HAT_OPEN, f, strike, mapOf("DECAY" to .25f, "TONE" to .6f))
            }
            for (j in x.indices) if (at + j in dry.indices) dry[at + j] += x[j]
        }
        val roomed = Room.process(Snip(dry, 1, Dsp.RATE), mapOf("SIZE" to .3f, "WALLS" to .5f, "DISTANCE" to .45f)).samples
        FloatArray(len).also { for (i in roomed.indices) it[i % len] += roomed[i] }
    }

    /** One bar at [bpm]: the played bar read at [bpm] / [BREAK_PLAYED_BPM] times its speed. */
    internal fun barFrames(): Int = Breakcore.frames(4 * 60.0 / bpm)

    private fun spedBreak(v: Double, t: Int): FloatArray {
        val ratio = bpm.toDouble() / BREAK_PLAYED_BPM
        return Breakcore.drive(Breakcore.spin(playedBar(v, t), barFrames()) { ratio }, 1.2 + .8 * v)
    }

    override fun renderPad(pad: Int, velocity: Double, take: Int): Snip {
        require(pad in layout.indices && velocity.isFinite() && velocity in 0.0..1.0 && take >= 0)
        val v = velocity; val t = take
        if (v == 0.0) return Snip(FloatArray(Breakcore.frames(.1)), 1, Dsp.RATE)
        val g = GAINS[pad] * shape(v)
        return when (pad) {
            0 -> Breakcore.finish(whinePad(v, t), g, 1.2)
            1 -> whineDive(v, t).let { Breakcore.finish(it, g, seconds(it.size)) }
            2 -> Breakcore.finish(whineStab(v, t), g, .2)
            3 -> Breakcore.finish(stab(0, v, t), g, .42)
            4 -> Breakcore.finish(stab(1, v, t), g, .42)
            5 -> Breakcore.finish(organBass(v, t), g, .45)
            6 -> Breakcore.finish(sawStab(v, t), g, .4)
            7 -> Breakcore.finish(orchestraHit(v, t), g, 1.0)
            8 -> bleep(v, t).let { Breakcore.finish(it, g, seconds(it.size)) }
            9 -> Breakcore.finish(bleepBass(v, t), g, 60.0 / bpm)
            10 -> Breakcore.finish(voxStab(v, t), g, .4)
            11 -> Breakcore.finish(raveKick(v, t), g, .5)
            12 -> Breakcore.finish(raveSnare(v, t), g, .6)
            13 -> Breakcore.finish(Splinter.skin(SkinVoice.HAT_CLOSED, v, t, mapOf("DECAY" to .04f, "TONE" to .75f)), g, .5)
            14 -> Breakcore.finish(Splinter.skin(SkinVoice.HAT_OPEN, v, t, mapOf("DECAY" to .3f, "TONE" to .7f)), g, 1.2)
            15 -> Breakcore.finish(spedBreak(v, t), g, seconds(barFrames()))
            16 -> stabRoll(v, t).let { Breakcore.finish(it, g, seconds(it.size)) }
            17 -> whineRise(v, t).let { Breakcore.finish(it, g, seconds(it.size)) }
            else -> error("pad")
        }
    }

    /** Fixed per-pad gain, set once from the peak over all three velocities and all three takes: the loudest cell of each pad sits at .85. */
    private val GAINS = doubleArrayOf(0.616, 0.581, 0.697, 0.613, 0.604, 1.061, 0.787, 0.725, 0.855, 0.637, 0.842, 0.682, 0.841, 1.308, 1.335, 0.827, 0.645, 0.598)
}
