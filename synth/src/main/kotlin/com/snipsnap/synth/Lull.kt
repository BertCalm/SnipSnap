package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/** LULL: the quiet side of the breakcore family. Something tender, then a sudden stop that
 * hands the track to the break. Piano chords in A minor (Am7, Fmaj7, Cadd9, G6: i, VI, III, VII),
 * strings and choir held under them, a music box, a bell, a worn tape, vinyl dust, and the
 * gestures a quiet track ends on: a reversed inhale, a tape stop, a ritardando echo, a swell
 * that is cut on the downbeat.
 *
 * Sources: [Oven] FELT for every piano note, [Spool] STRING and CHOIR, [Keys.musicBox], [Halobar]
 * bells, [Arco] cello and [Tape]. Cents never come from TUNE, which snaps to semitones: a take's
 * tuning is a constant-speed [Breakcore.spin] of the finished chord. Takes differ by strum order,
 * strum spread, per-note level and those cents. Velocity softens the hammer and lengthens the
 * strum. Every constant awaits the owner's listen. */
object Lull : BreakcoreKit {
    const val VERSION = 1
    const val ID = "lull-breakcore"
    override val id = ID
    override val title = "LULL"
    override val bpm = 170

    override val layout: List<Splinter.Part> = listOf(
        Splinter.Part("Piano Am", DrumClass.TONAL, .8f),
        Splinter.Part("Piano F", DrumClass.TONAL, .8f),
        Splinter.Part("Piano C", DrumClass.TONAL, .8f),
        Splinter.Part("Piano G", DrumClass.TONAL, .8f),
        Splinter.Part("Strings Am", DrumClass.TONAL, .65f),
        Splinter.Part("Choir Am", DrumClass.TONAL, .65f),
        Splinter.Part("Box A", DrumClass.TONAL, .5f),
        Splinter.Part("Box C", DrumClass.TONAL, .5f),
        Splinter.Part("Box E", DrumClass.TONAL, .5f),
        Splinter.Part("Bell", DrumClass.TONAL, .55f),
        Splinter.Part("Reverse Piano", DrumClass.PERC, .7f),
        Splinter.Part("Worn Piano", DrumClass.TONAL, .75f),
        Splinter.Part("Cello A", DrumClass.TONAL, .7f),
        Splinter.Part("Crackle", DrumClass.PERC, .3f),
        Splinter.Part("Piano Stop", DrumClass.PERC, .85f),
        Splinter.Part("Glass", DrumClass.TONAL, .45f),
        Splinter.Part("Fading Repeat", DrumClass.LOOP, .7f),
        Splinter.Part("Swell Rise", DrumClass.LOOP, .6f),
    )

    /** Chord voicings as MIDI notes, low to high: Am7, Fmaj7, Cadd9, G6. */
    private val VOICINGS = listOf(
        intArrayOf(45, 52, 60, 64, 67), intArrayOf(41, 48, 57, 64),
        intArrayOf(48, 55, 62, 64), intArrayOf(43, 50, 59, 64),
    )
    /** The held triad under the pads: A3 C4 E4 (Spool's TUNE reaches no lower than C3). */
    private val TRIAD = intArrayOf(57, 60, 64)

    private fun shape(v: Double) = v.pow(.8)
    private fun cents(t: Int, spread: Double) = listOf(0.0, -spread, spread)[t % 3] * (1 + t / 3)
    private fun detune(x: FloatArray, cents: Double): FloatArray = Breakcore.spin(x, x.size) { 2.0.pow(cents / 1200) }

    /** The first [seconds] of [x], its last [fade] seconds a raised cosine if it had to be cut. */
    private fun fit(x: FloatArray, seconds: Double, fade: Double = .35): FloatArray {
        val n = Breakcore.frames(seconds)
        if (x.size <= n) return x
        return x.copyOf(n).also { Dsp.fadeTailCosine(it, fade.toFloat()) }
    }

    // ---- sources, cached per (velocity, take) so the every-pad gate stays fast ----

    private val notes = ConcurrentHashMap<Pair<Int, Double>, FloatArray>()
    private val chords = ConcurrentHashMap<Triple<Int, Double, Int>, FloatArray>()
    private val spools = ConcurrentHashMap<List<Any>, FloatArray>()

    private fun pianoNote(midi: Int, v: Double): FloatArray = notes.getOrPut(midi to v) {
        // A felt piano: the hammer stays soft at every velocity (velocity itself brightens inside OVEN).
        Oven.render(OvenVoice.FELT, mapOf("HAMMER" to (.06 + .14 * v).toFloat()), midi, v.toFloat()).samples
    }

    /** Strum order by take: up, down, then from the middle outwards. */
    private fun strumRank(n: Int, t: Int): IntArray = when (t % 3) {
        0 -> IntArray(n) { it }
        1 -> IntArray(n) { n - 1 - it }
        else -> { val order = (0 until n).sortedWith(compareBy({ abs(2 * it - (n - 1)) }, { it })); IntArray(n) { order.indexOf(it) } }
    }

    /** One piano chord: the notes summed with a strum (about 4 ms end to end when hard, 12 when soft),
     * each a touch different in level per take, then the whole chord a few cents off by take. */
    private fun chord(c: Int, v: Double, t: Int): FloatArray = chords.getOrPut(Triple(c, v, t)) {
        val voicing = VOICINGS[c]; val n = voicing.size; val rank = strumRank(n, t)
        val r = Random(Dsp.seedFor("lull-chord", c, t).toLong())
        val stepMs = (4 + 8 * (1 - v)) / (n - 1)
        val out = FloatArray(Breakcore.frames(3.2))
        for ((k, midi) in voicing.withIndex()) {
            val src = pianoNote(midi, v); val at = Breakcore.frames(rank[k] * stepMs / 1000)
            val w = (1.0 - .4 * k / (n - 1)) * (1 + .06 * r.nextGaussian())
            for (j in src.indices) if (at + j in out.indices) out[at + j] += (src[j] * w).toFloat()
        }
        val x = detune(out, cents(t, 3.0))
        Dsp.fadeTailCosine(x, .4f)
        x
    }

    /** A held triad from [Spool] (each note its own machine seed), [gate] seconds, [seconds] long. */
    private fun spoolChord(voice: SpoolVoice, v: Double, t: Int, gate: Double, seconds: Double): FloatArray =
        spools.getOrPut(listOf(voice, v, t, gate, seconds)) {
            val out = FloatArray(Breakcore.frames(seconds))
            for ((k, midi) in TRIAD.withIndex()) {
                val machine = SpoolMachine.forVoice(voice).copy(gateSeconds = gate.toFloat(), sourceSeconds = 2f, seed = Dsp.seedFor("lull-spool", voice, t, k))
                val src = Spool.render(voice, mapOf("TUNE" to (midi - 48) / 24f), machine, v.toFloat()).samples
                for (j in src.indices) if (j in out.indices) out[j] += src[j] * .7f
            }
            val x = detune(out, cents(t, 3.0))
            Dsp.fadeTailCosine(x, .25f)
            x
        }

    /** Raised-cosine swell: 0 to 1 over [seconds], then 1. */
    private fun swell(n: Int, seconds: Double): FloatArray {
        val a = max(1, Breakcore.frames(seconds))
        return FloatArray(n) { i -> if (i >= a) 1f else (.5 - .5 * cos(PI * i / a)).toFloat() }
    }

    /** The held pad: a slow swell and a softened top. Harder swells faster and opens up. */
    private fun heldPad(voice: SpoolVoice, v: Double, t: Int): FloatArray {
        val x = fit(spoolChord(voice, v, t, 1.8, 2.0), 1.1, .3)
        val sw = swell(x.size, .32 - .2 * v)
        val lp = Breakcore.svf(x, 1.3f, 0) { 2200 + 5500 * v }
        return FloatArray(x.size) { lp[it] * sw[it] }
    }

    private fun box(midi: Int, v: Double, t: Int): FloatArray {
        val src = detune(Keys.musicBox(midi).samples, cents(t, 4.0))
        val lp = Breakcore.svf(src, 1.2f, 0) { 3000 + 9000 * v }
        // The box has one dynamic, so the strike is added: a tick of band-passed noise, harder when harder.
        val tick = Breakcore.svf(Breakcore.noise(Breakcore.frames(.03), Dsp.seedFor("lull-box", midi, t)), .8f, 1) { 2800 + 2000 * v }
        val env = Breakcore.env(tick.size, .004, .2)
        val out = lp.copyOf()
        for (i in tick.indices) out[i] += (tick[i] * env[i] * (.1 + .35 * v)).toFloat()
        return out
    }

    private fun halobar(voice: HalobarVoice, midi: Int, v: Double, t: Int, touch: Double, extra: Map<String, Float>): FloatArray {
        val macros = Halobar.defaults(voice) + mapOf("TUNE" to Halobar.tuneForMidi(voice, midi), "TOUCH" to touch.toFloat()) + extra
        val src = Halobar.renderTake(voice, macros, v.toFloat(), Dsp.seedFor("lull-halobar", voice, midi), t).samples
        return detune(src, cents(t, 4.0))
    }

    private fun crackle(v: Double, t: Int): FloatArray {
        val n = Breakcore.beat(bpm).roundToInt(); val r = Random(Dsp.seedFor("lull-crackle", t).toLong())
        // Candidate clicks at up to about 70 a second; each is kept if its draw is under the density,
        // so a harder pad holds every click a softer one does.
        val density = .25 + .75 * v; val clicks = FloatArray(n)
        repeat(26) {
            val at = r.nextInt(n - 3); val u = r.nextDouble(); val a = (-ln(1 - r.nextDouble() * .97)).coerceAtMost(2.5) * (if (r.nextBoolean()) 1 else -1)
            if (u < density) { clicks[at] += a.toFloat(); clicks[at + 1] -= (a * .6).toFloat() }
        }
        val dust = Breakcore.svf(clicks, .7f, 1) { 2600 + 1800 * v }
        val w = Breakcore.noise(n, Dsp.seedFor("lull-hiss", t)); var b0 = 0.0; var b1 = 0.0; var b2 = 0.0
        val hiss = FloatArray(n) { i -> // Kellet's three-pole pink approximation
            val x = w[i]; b0 = .99765 * b0 + x * .099046; b1 = .963 * b1 + x * .2965164; b2 = .57 * b2 + x * 1.0526913
            ((b0 + b1 + b2 + x * .1848) * .1).toFloat() }
        return Breakcore.fadeIn(FloatArray(n) { (dust[it] * .5 + hiss[it] * .12).toFloat() })
    }

    /** A tape machine stopping: it plays [hold] seconds, then the speed eases to zero over [stop] seconds.
     * Pitch and speed fall together because the one read speed does both; the level follows its square root. */
    private fun tapeStop(src: FloatArray, hold: Double, stop: Double, curve: Double): FloatArray {
        val h = Breakcore.frames(hold); val s = Breakcore.frames(stop)
        val speed = { i: Int -> if (i < h) 1.0 else (1 - (i - h).toDouble() / s).coerceAtLeast(0.0).pow(curve) }
        val y = Breakcore.spin(src, h + s, speed)
        return FloatArray(y.size) { (y[it] * sqrt(speed(it))).toFloat() }
    }

    /** The onsets (in frames) of the repeats of Fading Repeat: gaps grow by 1.22 each, a few ms of jitter by take. */
    internal fun repeatOnsets(v: Double, t: Int): List<Int> {
        val beat = Breakcore.beat(bpm); val r = Random(Dsp.seedFor("lull-repeat", t).toLong())
        val out = ArrayList<Int>(); var at = 0.0; var gap = beat * (.62 - .14 * v)
        while (at < 3.9 * beat) {
            out += if (out.isEmpty()) 0 else (at + (r.nextGaussian() * .003 * Dsp.RATE).coerceIn(-.007 * Dsp.RATE, .007 * Dsp.RATE)).roundToInt()
            at += gap; gap *= 1.22
        }
        return out
    }

    override fun renderPad(pad: Int, velocity: Double, take: Int): Snip {
        require(pad in layout.indices && velocity.isFinite() && velocity in 0.0..1.0 && take >= 0)
        val v = velocity; val t = take; val beat = Breakcore.beat(bpm)
        if (v == 0.0) return Snip(FloatArray(Breakcore.frames(.1)), 1, Dsp.RATE)
        val g = GAINS[pad] * shape(v)
        return when (pad) {
            0, 1, 2, 3 -> Breakcore.finish(chord(pad, v, t), g, 3.2)
            4 -> Breakcore.finish(heldPad(SpoolVoice.STRING, v, t), g, 1.1)
            5 -> Breakcore.finish(heldPad(SpoolVoice.CHOIR, v, t), g, 1.1)
            6, 7, 8 -> Breakcore.finish(box(intArrayOf(81, 84, 88)[pad - 6], v, t), g, 2.4)
            9 -> Breakcore.finish(fit(halobar(HalobarVoice.TUBULAR_BELLS, 69, v, t, .2 + .5 * v, mapOf("SPACE" to .25f, "MOTION" to .1f)), 3.2), g, 3.2)
            10 -> {
                // The inhale: the first stretch of the chord, backwards, so it swells and ends on its own attack.
                val n = (beat * (.7 + .4 * v)).roundToInt()
                Breakcore.finish(chord(0, v, t).copyOf(n).also { it.reverse() }, g, 2.0)
            }
            11 -> {
                // Through the tape: heavy wobble and age, then a low-pass near 3 kHz. The wow phase is the
                // take: the note is fed in after a lead-in of silence that is cut off the output again.
                val lead = Breakcore.frames(listOf(0.0, .21, .47)[t % 3] + .6 * (t / 3))
                val src = FloatArray(lead) + chord(0, v, t)
                val taped = Tape.process(Snip(src, 1, Dsp.RATE), mapOf("WOBBLE" to (.5 + .35 * v).toFloat(), "AGE" to .85f, "DRIVE" to .3f)).samples
                val out = taped.copyOfRange(lead, taped.size)
                Breakcore.finish(Breakcore.svf(out, 1.3f, 0) { 3000.0 }, g, 3.0)
            }
            12 -> {
                val m = Arco.defaults(ArcoVoice.CELLO) + mapOf("TUNE" to 9 / 24f, "BOW" to (.3 + .4 * v).toFloat(),
                    "GRIP" to (.55 + .04 * (t % 3)).toFloat())
                val x = fit(detune(Arco.render(ArcoVoice.CELLO, m).samples, cents(t, 4.0)), 1.1, .3)
                Breakcore.finish(x, g, 1.1)
            }
            13 -> Breakcore.finish(crackle(v, t), g, .36)
            14 -> Breakcore.finish(tapeStop(chord(0, v, t), .18, .52 - .2 * v, listOf(1.4, 1.0, 1.8)[t % 3]), g, 1.0)
            15 -> Breakcore.finish(fit(halobar(HalobarVoice.GLOCKENSPIEL, 88, v, t, .25 + .5 * v, mapOf("SPACE" to .3f)), .9, .3), g, .9)
            16 -> {
                // The chord again on slowing eighths, each repeat quieter and darker than the one before.
                val onsets = repeatOnsets(v, t); val src = chord(0, v, t)
                val last = onsets.last() + Breakcore.frames(2.2); val out = FloatArray(last)
                // One voice, as a sampler pad retriggers: each repeat chokes the one before it with a 4 ms
                // fade that ends on the new onset, so every repeat is heard as an attack, not a smear.
                val fade = Breakcore.frames(.004)
                for ((k, at) in onsets.withIndex()) {
                    val part = fit(src, 2.2, .4)
                    val dark = Breakcore.svf(part, 1.2f, 0) { max(700.0, 9000 * .55.pow(k)) }
                    val level = .62.pow(k)
                    val end = onsets.getOrNull(k + 1)?.minus(at) ?: dark.size
                    for (j in 0 until min(dark.size, end)) {
                        val choke = if (end - j < fade) (end - j).toDouble() / fade else 1.0
                        out[at + j] += (dark[j] * level * choke).toFloat()
                    }
                }
                Breakcore.finish(out, g, last / Dsp.RATE.toDouble())
            }
            17 -> {
                // Reversed choir with strings under it, opening and rising for four beats, cut on the downbeat.
                val n = (4 * beat).roundToInt()
                val choir = spoolChord(SpoolVoice.CHOIR, v, t, 1.8, 2.0).copyOf(n).also { it.reverse() }
                val strings = spoolChord(SpoolVoice.STRING, v, t, 1.8, 2.0).copyOf(n)
                val open = { i: Int -> val p = i.toDouble() / n; 500 + (1500 + 7000 * v) * p * p }
                val c = Breakcore.svf(choir, 1.3f, 0, open); val s = Breakcore.svf(strings, 1.3f, 0, open)
                val out = FloatArray(n) { i -> val p = i.toDouble() / n; (p.pow(2.0) * .6 * c[i] + p.pow(2.5) * .5 * s[i]).toFloat() }
                Breakcore.finish(out, g, 4 * beat / Dsp.RATE)
            }
            else -> error("pad")
        }
    }

    /** Fixed per-pad gain: the full-force peak over all three takes sits at .85, except the bell and the cello, whose
     * long steady bodies are held to the pianos' loudness by RMS (about .16) instead. */
    private val GAINS = doubleArrayOf(0.672, 0.725, 0.756, 0.719, 0.761, 0.824, 0.757, 0.703, 0.604, 1.63, 0.706, 0.625, 0.976, 1.8, 0.672, 1.643, 0.672, 1.533)
}
