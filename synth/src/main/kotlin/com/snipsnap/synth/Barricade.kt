package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/** BARRICADE: the digital-hardcore kit of the breakcore family, at 165 BPM in E. Punk power chords through a
 * cranked amp, a tight punk kick and snare, and harsh noise where a voice would be.
 *
 * The characteristic gesture is: a power chord through a cranked amp -> a punk break -> noise where a voice
 * would be.
 *
 * The chords are [Magnet] CHUG notes (E2 B2 E3 and its neighbours, summed with a strum of a few milliseconds)
 * through [Valve] at MAGNET's landing amp with DRIVE and TONE raised (the risk gate measured that chord: the 1 to
 * 4 kHz band 6 dB under the full band, above 10 kHz 37 dB under, and 3.1 s of sustain above -30 dB). Cents never
 * come from TUNE (it snaps to semitones); a take's tuning is a constant-speed [Breakcore.spin] of the summed
 * chord, and a take also moves the strum order and spread, PICK, BLEND and the noise seeds. Velocity is amp drive,
 * a harder PICK and a brighter TONE. Palm Mute is cut by its own envelope (MUTE alone left it ringing 1.15 s).
 * The kick, snare and hat are SPORE and SKIN drums driven and lifted at 2.5 kHz; Bullhorn, Pick Slide, Cable
 * Pull, Crash and Noise Wall are synthesized here from noise. Every constant awaits the owner's listen. */
object Barricade : BreakcoreKit {
    const val VERSION = 1
    const val ID = "barricade-breakcore"
    override val id = ID
    override val title = "BARRICADE"
    override val bpm = 165

    override val layout: List<Splinter.Part> = listOf(
        Splinter.Part("Power E", DrumClass.TONAL, .8f, 1),
        Splinter.Part("Power G", DrumClass.TONAL, .8f, 1),
        Splinter.Part("Power A", DrumClass.TONAL, .8f, 1),
        Splinter.Part("Palm Mute E", DrumClass.TONAL, .8f, 1),
        Splinter.Part("Dive Bomb", DrumClass.PERC, .7f, 1),
        Splinter.Part("Feedback", DrumClass.PERC, .6f),
        Splinter.Part("Pick Slide", DrumClass.PERC, .6f),
        Splinter.Part("Punk Kick", DrumClass.KICK, 1f),
        Splinter.Part("Punk Snare", DrumClass.SNARE, .9f),
        Splinter.Part("Crash", DrumClass.PERC, .5f),
        Splinter.Part("Hat", DrumClass.HAT_CLOSED, .45f),
        Splinter.Part("Bullhorn", DrumClass.PERC, .7f),
        Splinter.Part("Cable Pull", DrumClass.PERC, .6f),
        Splinter.Part("Crushed Chord", DrumClass.TONAL, .7f, 1),
        Splinter.Part("Noise Wall", DrumClass.PERC, .6f),
        Splinter.Part("Open E", DrumClass.TONAL, .8f, 1),
        Splinter.Part("Chug Roll", DrumClass.LOOP, .8f),
        Splinter.Part("Feedback Rise", DrumClass.LOOP, .6f),
    )

    private const val E2 = 40; private const val B2 = 47; private const val E3 = 52
    private val POWER = listOf(intArrayOf(E2, B2, E3), intArrayOf(43, 50, 55), intArrayOf(45, 52, 57))

    private fun shape(v: Double) = v.pow(.8)
    private fun cents(t: Int, spread: Double) = listOf(0.0, -spread, spread)[t % 3] * (1 + t / 3)
    private fun ratioOf(t: Int, spread: Double) = 2.0.pow(cents(t, spread) / 1200)
    private fun seconds(frames: Int) = frames.toDouble() / Dsp.RATE
    private fun rng(name: String, t: Int) = Random(Dsp.seedFor("barricade", name, t).toLong())

    /** The first [seconds] of [x], its last [fade] seconds a raised cosine if it had to be cut. */
    private fun fit(x: FloatArray, seconds: Double, fade: Double = .15): FloatArray {
        val n = Breakcore.frames(seconds)
        if (x.size <= n) return x
        return x.copyOf(n).also { Dsp.fadeTailCosine(it, fade.toFloat()) }
    }

    // ---- the amp ----

    /** MAGNET's CHUG landing amp (VALVE) with [drive] and [tone] set. */
    private fun amp(x: FloatArray, drive: Float, tone: Float): FloatArray =
        Valve.process(Snip(x, 1, Dsp.RATE), Magnet.LANDING_VALVE.getValue(MagnetVoice.CHUG) + mapOf("DRIVE" to drive, "TONE" to tone)).samples

    /** Harder is more drive and a brighter tone: DRIVE .794 to .92 (MAGNET's own landing is .85), TONE .28 to .42, PICK .54 to .75 (the gate's hot amp and a PICK of .82 put Power E's spectral centroid at 2.9 kHz, too near the 3 kHz bar; PICK is what moves it). */
    private fun ampV(x: FloatArray, v: Double): FloatArray = amp(x, (.74 + .18 * v).toFloat(), (.22 + .20 * v).toFloat())

    // ---- the guitar ----

    private data class Key(val kind: String, val midis: List<Int>, val mute: Float, val v: Double, val t: Int, val alt: Int)
    private val notes = ConcurrentHashMap<Key, FloatArray>()
    private val chords = ConcurrentHashMap<Key, FloatArray>()
    private val amped = ConcurrentHashMap<Key, FloatArray>()

    /** One dry CHUG note. A harder hit is a harder PICK; each take nudges PICK and BLEND; [alt] 1 is an up-stroke, a little softer. */
    private fun note(midi: Int, mute: Float, v: Double, t: Int, alt: Int): FloatArray =
        notes.getOrPut(Key("note", listOf(midi), mute, v, t, alt)) {
            val pick = (.45 + .30 * v + listOf(0.0, -.04, .04)[t % 3] - .06 * alt).coerceIn(0.0, 1.0)
            val blend = listOf(1f, .9f, .8f)[t % 3]
            Magnet.render(MagnetVoice.CHUG, mapOf("TUNE" to (midi - 35) / 24f, "MUTE" to mute, "PICK" to pick.toFloat(), "BLEND" to blend)).samples
        }

    /** The notes summed (1 ms between the strings at the least, a few more when soft), divided by their number, then
     * a take's cents by a constant-speed spin. Take 1 strums the other way, take 2 wider. */
    private fun dry(midis: List<Int>, mute: Float, v: Double, t: Int, alt: Int = 0): FloatArray =
        chords.getOrPut(Key("dry", midis, mute, v, t, alt)) {
            val r = rng("strum", t); val n = midis.size
            val stepMs = (1.2 + 2.8 * (1 - v)) * (if (t % 3 == 2) 1.6 else 1.0)
            val order = if (t % 3 == 1) (n - 1 downTo 0).toList() else (0 until n).toList()
            val srcs = midis.map { note(it, mute, v, t, alt) }
            val out = FloatArray(srcs.maxOf { it.size } + Breakcore.frames(.02))
            for ((k, src) in srcs.withIndex()) {
                val at = Breakcore.frames(order.indexOf(k) * stepMs / 1000); val w = (1.0 + .05 * r.nextGaussian()) / n
                for (j in src.indices) out[at + j] += (src[j] * w).toFloat()
            }
            val k = ratioOf(t, 6.0)
            Breakcore.spin(out, out.size) { k }
        }

    private fun amped(midis: List<Int>, mute: Float, v: Double, t: Int, alt: Int = 0): FloatArray =
        amped.getOrPut(Key("amp", midis, mute, v, t, alt)) { ampV(dry(midis, mute, v, t, alt), v) }

    private fun power(chord: Int, v: Double, t: Int) = fit(amped(POWER[chord].toList(), .35f, v, t), 1.2)

    /** E2 and B2 with MUTE high, then an envelope that stops it in 150 ms: MUTE alone left it 1.15 s. */
    private fun palmMute(v: Double, t: Int, alt: Int = 0): FloatArray {
        val x = amped(listOf(E2, B2), .9f, v, t, alt); val tau = .040 + .012 * v
        val n = Breakcore.frames(.15)
        val y = FloatArray(n) { i -> (x[i] * exp(-i / (tau * Dsp.RATE))).toFloat() }
        Dsp.fadeTailCosine(y, .03f)
        return y
    }

    /** The E power chord with its pitch easing an octave down over .8 s, after a 250 ms hold, through the amp. */
    private fun diveBomb(v: Double, t: Int): FloatArray {
        val src = dry(POWER[0].toList(), .35f, v, t); val depth = 1.0 + cents(t, 2.0) / 1200
        val x = Breakcore.spin(src, Breakcore.frames(1.6)) { i ->
            val p = ((i.toDouble() / Dsp.RATE - .25) / .80).coerceIn(0.0, 1.0)
            2.0.pow(-depth * (1 - cos(PI * p)) / 2)
        }
        return fit(ampV(x, v), 1.6, .12)
    }

    private fun openE(v: Double, t: Int) = fit(amped(listOf(E2), .1f, v, t), 2.0, .3)

    private fun crushedChord(v: Double, t: Int): FloatArray =
        Crunch.process(Snip(power(0, v, t), 1, Dsp.RATE), mapOf("BITS" to .9f, "RATE" to .75f, "TONE" to .75f, "GRIT" to (.35f + .2f * v.toFloat()))).samples

    /** Palm Mute on sixteenths for two beats, then a gallop (a sixteenth and two thirty-seconds on every eighth) for two,
     * each hit cut where the next begins, up-strokes a little softer, louder to the end. */
    internal fun rollOnsetBeats(): List<Double> =
        List(8) { it * .25 } + (2..3).flatMap { b -> listOf(0.0, .25, .375, .5, .75, .875).map { b + it } }

    private fun chugRoll(v: Double, t: Int): FloatArray {
        val beat = Breakcore.beat(bpm); val r = rng("roll", t)
        val beats = rollOnsetBeats()
        val onsets = beats.mapIndexed { k, b ->
            val jitter = if (k == 0) 0.0 else (r.nextGaussian() * .0010 * Dsp.RATE).coerceIn(-.002 * Dsp.RATE, .002 * Dsp.RATE)
            (b * beat + jitter).roundToInt()
        }
        val hits = listOf(palmMute(v, t, 0), palmMute(v, t, 1)); val fade = Breakcore.frames(.002)
        val out = FloatArray(onsets.last() + hits[0].size)
        for ((k, at) in onsets.withIndex()) {
            val part = hits[k % 2]; val level = (.3 + .7 * k / onsets.lastIndex) * (if (k % 2 == 1) .88 else 1.0)
            val end = onsets.getOrNull(k + 1)?.minus(at) ?: part.size
            for (j in 0 until min(part.size, end)) {
                val choke = if (end - j < fade) (end - j).toDouble() / fade else 1.0
                out[at + j] += (part[j] * level * choke).toFloat()
            }
        }
        return out
    }

    // ---- feedback ----

    /** A near-sine on [hz] (a few cents a take, a slow vibrato that opens up, a faint third harmonic) times [level],
     * through a gentler amp than the chords', so the swell is also the amp breaking up (and, after the amp, the level itself swells). */
    private fun howl(n: Int, v: Double, t: Int, hz: (Int) -> Double, level: (Int) -> Double): FloatArray {
        val k = ratioOf(t, 10.0); val vib = 5.0 + .4 * (t % 3)
        val s = Breakcore.sine(n, .17 * t) { i -> hz(i) * k * (1 + .003 * sin(2 * PI * vib * i / Dsp.RATE) * min(1.0, i / (.4 * Dsp.RATE))) }
        val h3 = Breakcore.sine(n, .5) { i -> 3 * hz(i) * k }
        val raw = FloatArray(n) { i -> ((s[i] + .12f * h3[i]) * level(i)).toFloat() }
        // VALVE matches its output peak to its input's, so a clipped swell would come out level; the swell is put back after it.
        val y = amp(raw, (.55 + .30 * v).toFloat(), (.45 + .15 * v).toFloat())
        val z = FloatArray(n) { i -> (y[i] * level(i).pow(.6)).toFloat() }
        val p = z.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        return FloatArray(n) { z[it] / p }   // peak 1 at every force: a softer howl is softer by its gain and its amp, so the velocity order holds
    }

    private const val E5 = 659.26

    private fun feedback(v: Double, t: Int): FloatArray {
        val n = Breakcore.frames(1.6); val swell = 2 * 60.0 / bpm
        return howl(n, v, t, { E5 }) { i ->
            val s = i.toDouble() / Dsp.RATE
            (if (s < swell) .03 + .97 * (s / swell).pow(2) else 1.0) * min(1.0, (n - i) / (.25 * Dsp.RATE))
        }
    }

    private fun feedbackRise(v: Double, t: Int): FloatArray {
        val n = (4 * Breakcore.beat(bpm)).roundToInt()
        val h = howl(n, v, t, { i -> E5 * 2.0.pow(i.toDouble() / n) }) { i -> .1 + .9 * (i.toDouble() / n).pow(1.5) }
        val w = wall(n, v, t, "rise")
        return FloatArray(n) { i -> h[i] * .7f + w[i] * (.04 + .5 * (i.toDouble() / n).pow(2)).toFloat() }
    }

    // ---- noise ----

    /** Harsh noise: white noise through three resonant combs on E3, B3 and E4 (their peaks run up through the 1 to 4 kHz
     * band), clipped hard. [n] long, a take's cents on the combs. */
    private fun wall(n: Int, v: Double, t: Int, name: String): FloatArray {
        val src = Breakcore.noise(n, Dsp.seedFor("barricade-wall", name, t)); val sum = FloatArray(n)
        for ((hz, g) in listOf(164.81 to 1.0, 246.94 to .8, 329.63 to .8)) {
            val d = (Dsp.RATE / (hz * ratioOf(t, 15.0))).roundToInt(); val y = FloatArray(n); val fb = .91f + .02f * v.toFloat()
            for (i in 0 until n) y[i] = src[i] + (if (i >= d) fb * y[i - d] else 0f)
            for (i in 0 until n) sum[i] += (y[i] * g).toFloat()
        }
        val hp = Breakcore.svf(sum, .8f, 2) { 300.0 }
        val p = hp.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        return Splinter.clip(FloatArray(n) { hp[it] / p }, 3 + 8 * v)
    }

    private fun noiseWall(v: Double, t: Int): FloatArray {
        val n = Breakcore.beat(bpm).roundToInt()
        val x = wall(n, v, t, "pad"); val r = Breakcore.frames(.006)
        return Breakcore.fadeIn(FloatArray(n) { i -> x[i] * min(1.0, (n - i).toDouble() / r).toFloat() })
    }

    private fun pickSlide(v: Double, t: Int): FloatArray {
        val n = Breakcore.frames(.5); val k = 1 + listOf(0.0, -.04, .04)[t % 3]
        val hz = { i: Int -> 4000.0 * (300.0 / 4000.0).pow(i.toDouble() / n) * k }
        val bp = Breakcore.svf(Breakcore.noise(n, Dsp.seedFor("barricade-slide", t)), .45f, 1) { hz(it) }
        var ridge = rng("ridge", t).nextDouble()
        val raw = FloatArray(n) { i ->
            ridge += hz(i) * .06 / Dsp.RATE
            val e = min(1.0, i / (.005 * Dsp.RATE)) * (1 - .5 * i / n) * min(1.0, (n - i) / (.02 * Dsp.RATE))
            (bp[i] * (.55 + .45 * sin(2 * PI * ridge)) * e).toFloat()
        }
        return ampV(raw, v)
    }

    private fun bullhorn(v: Double, t: Int): FloatArray {
        val n = Breakcore.frames(.55); val r = rng("horn", t)
        val white = Breakcore.noise(n, Dsp.seedFor("barricade-horn", t)); val form = 1 + listOf(0.0, -.06, .06)[t % 3]
        // Two syllables, 0 to .24 s and .27 to .53 s, each gliding its formants a little downward as it is shouted.
        fun syl(i: Int): Double { val s = i.toDouble() / Dsp.RATE; return if (s < .25) s / .24 else if (s in .27..0.53) (s - .27) / .26 else -1.0 }
        val buzz = 105.0 + 8 * r.nextDouble()
        val f1 = Breakcore.svf(white, .30f, 1) { i -> val p = syl(i).coerceAtLeast(0.0); 700.0 * form * (1 - .12 * p) }
        val f2 = Breakcore.svf(white, .30f, 1) { i -> val p = syl(i).coerceAtLeast(0.0); 1200.0 * form * (1 + .25 * p) }
        val raw = FloatArray(n) { i ->
            val p = syl(i)
            val e = if (p < 0) 0.0 else min(1.0, p / .08) * (1 - .55 * p).coerceAtLeast(0.0) * min(1.0, (1 - p) / .08)
            val rasp = .4 + .6 * sin(PI * buzz * i / Dsp.RATE).pow(2)
            ((f1[i] + .8f * f2[i]) * rasp * e).toFloat()
        }
        val p = raw.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        val clipped = Splinter.clip(FloatArray(n) { raw[it] / p }, 2.5 + 6 * v)
        // The horn's own band: nothing below 350 Hz or above 3.5 kHz.
        return Breakcore.svf(Breakcore.svf(clipped, .9f, 2) { 350.0 }, .9f, 0) { 3500.0 }
    }

    /** 50 Hz mains hum with harmonics, then crackle as the contact breaks (the hum cutting in and out, clicks closer together), then a pop. */
    private fun cablePull(v: Double, t: Int): FloatArray {
        val r = rng("cable", t); val n = Breakcore.frames(.9)
        val crack = Breakcore.frames(.38); val pop = Breakcore.frames(.62)
        val amps = doubleArrayOf(1.0, .55, .6, .28, .35, .18, .2, .1); val ph = DoubleArray(8) { r.nextDouble() * 2 * PI }
        val out = FloatArray(n); var gate = 1.0; var target = 1.0; var hold = 0
        for (i in 0 until pop) {
            if (i >= crack && --hold <= 0) {
                val p = (i - crack).toDouble() / (pop - crack)
                target = if (r.nextDouble() < .2 + .6 * p) 0.0 else 1.0; hold = Breakcore.frames(.003 + .009 * r.nextDouble())
            }
            gate += (target - gate) * .03
            var h = 0.0; for (k in amps.indices) h += amps[k] * sin(2 * PI * 50.0 * (k + 1) * i / Dsp.RATE + ph[k])
            out[i] = (tanh(h * 1.2) * gate * (.22 + .12 * v)).toFloat()
            if (i >= crack) {
                val p = (i - crack).toDouble() / (pop - crack)
                if (r.nextDouble() < (.001 + .012 * p) * (.5 + v)) {
                    val a = (.2 + .8 * r.nextDouble()) * (if (r.nextBoolean()) 1 else -1)
                    for (j in 0 until 10) if (i + j < n) out[i + j] += (a * exp(-j / 2.0) * (.45 + .3 * v)).toFloat()
                }
            }
        }
        val hiss = Breakcore.svf(Breakcore.noise(pop, Dsp.seedFor("barricade-hiss", t)), 1f, 2) { 2000.0 }
        for (i in 0 until pop) out[i] += hiss[i] * .012f
        // The pop: a speaker cone thrown out and back, with a sharp click on the front.
        val body = Breakcore.sine(n - pop) { i -> 45 + 110 * exp(-i / (.012 * Dsp.RATE)) }
        val click = Breakcore.noise(Breakcore.frames(.003), Dsp.seedFor("barricade-pop", t))
        for (j in 0 until n - pop) {
            out[pop + j] += (body[j] * exp(-j / (.07 * Dsp.RATE)) * (.6 + .4 * v)).toFloat()
            if (j < click.size) out[pop + j] += click[j] * (.4 + .5 * v).toFloat() * (1 - j.toFloat() / click.size)
        }
        return out
    }

    // ---- the drums ----

    private fun lift(x: FloatArray, hz: Float = 2500f, db: Float = 6f): FloatArray {
        val b = Dsp.Biquad().apply { peaking(hz, db, .8f) }
        return FloatArray(x.size) { b.process(x[it]) }
    }

    private fun punkKick(v: Double, t: Int): FloatArray {
        val k = Splinter.kick(v, t, tune = .3, strike = .8, body = .55, click = .75)
        val clipped = Splinter.clip(k, 3 + 9 * v)
        return Breakcore.svf(lift(clipped), 1.4f, 0) { 9000.0 }
    }

    private fun punkSnare(v: Double, t: Int): FloatArray {
        val s = Splinter.snare(v, t, tune = .55, strike = .9, body = .4, wires = .85)
        val n = max(s.size, Breakcore.frames(.4))
        val crack = Breakcore.svf(Breakcore.noise(n, Dsp.seedFor("barricade-snare", t)), .8f, 1) { 3000.0 + 300 * v }
        val e = Breakcore.env(n, .04, .3)
        val dry = FloatArray(n) { i -> (if (i < s.size) s[i] else 0f) + crack[i] * e[i] * (.2f + .3f * v.toFloat()) }
        val roomed = Room.process(Snip(dry, 1, Dsp.RATE), mapOf("SIZE" to .25f, "WALLS" to .5f, "DISTANCE" to .4f)).samples
        return lift(Splinter.clip(roomed, 1.5 + 4 * v))
    }

    /** Noise and six inharmonic partials, the crash's metal, through a clipper. */
    private fun crash(v: Double, t: Int): FloatArray {
        val n = Breakcore.frames(1.6); val e = Breakcore.env(n, .5, .5)
        val metal = listOf(3150.0, 4270.0, 5190.0, 6630.0, 7480.0, 8810.0).mapIndexed { k, hz -> Breakcore.sine(n, .17 * k + .05 * t) { hz * (1 + cents(t, 8.0) / 1200) } }
        val noise = Breakcore.svf(Breakcore.noise(n, Dsp.seedFor("barricade-crash", t)), 1f, 2) { 3500.0 }
        val raw = FloatArray(n) { i -> (.5f * noise[i] + .12f * metal.sumOf { it[i].toDouble() }.toFloat()) * e[i] }
        return Splinter.clip(raw, 1.5 + 3 * v)
    }

    private fun hat(v: Double, t: Int): FloatArray =
        Breakcore.drive(Splinter.skin(SkinVoice.HAT_CLOSED, v, t, mapOf("DECAY" to .04f, "TONE" to .8f)), 1.5 + 1.5 * v)

    // ---- pads ----

    /** A pad before its gain: the samples and the seconds [Breakcore.finish] keeps. Also the probe's entry. */
    internal fun raw(pad: Int, v: Double, t: Int): Pair<FloatArray, Double> {
        fun sec(x: FloatArray) = x to seconds(x.size)
        return when (pad) {
            0, 1, 2 -> power(pad, v, t).let(::sec)
            3 -> palmMute(v, t).let(::sec)
            4 -> diveBomb(v, t).let(::sec)
            5 -> feedback(v, t).let(::sec)
            6 -> pickSlide(v, t).let(::sec)
            7 -> punkKick(v, t) to .45
            8 -> punkSnare(v, t) to .4
            9 -> crash(v, t) to 1.5
            10 -> hat(v, t) to .15
            11 -> bullhorn(v, t).let(::sec)
            12 -> cablePull(v, t).let(::sec)
            13 -> crushedChord(v, t).let(::sec)
            14 -> noiseWall(v, t).let(::sec)
            15 -> openE(v, t).let(::sec)
            16 -> chugRoll(v, t).let(::sec)
            17 -> feedbackRise(v, t).let(::sec)
            else -> error("pad")
        }
    }

    override fun renderPad(pad: Int, velocity: Double, take: Int): Snip {
        require(pad in layout.indices && velocity.isFinite() && velocity in 0.0..1.0 && take >= 0)
        if (velocity == 0.0) return Snip(FloatArray(Breakcore.frames(.1)), 1, Dsp.RATE)
        val (x, secs) = raw(pad, velocity, take)
        return Breakcore.finish(x, GAINS[pad] * shape(velocity), secs)
    }

    internal fun shapeOf(v: Double) = shape(v)

    /** Fixed per-pad gain, set once from the peak over all three velocities (after the velocity shape) and all three takes: the loudest cell of each pad sits at .85. */
    private val GAINS = doubleArrayOf(1.423, 1.370, 1.246, 1.131, 1.471, 0.862, 0.625, 0.391, 0.389, 0.550, 0.881, 0.342, 0.642, 1.401, 0.520, 1.204, 1.171, 0.570)
}
