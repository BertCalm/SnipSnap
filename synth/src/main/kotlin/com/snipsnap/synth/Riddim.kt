package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.CalabashDsp.Rng
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/** RIDDIM's voices: the electronic dancehall drums, the struck wood and bronze, and the three kit rolls. */
enum class RiddimVoice(val drumClass: DrumClass, internal val seconds: Double, internal val controls: List<String>) {
    KICK(DrumClass.KICK, 0.60, listOf("TUNE", "DECAY", "DRIVE")),
    DEMBOW_KICK(DrumClass.KICK, 0.70, listOf("TUNE", "DECAY", "DRIVE")),
    BOOM(DrumClass.KICK, 1.60, listOf("TUNE", "DECAY", "DRIVE")),
    SLIDE(DrumClass.KICK, 1.60, listOf("TUNE", "DECAY", "DRIVE")),
    RIMSHOT(DrumClass.SNARE, 0.25, listOf("TUNE", "DECAY")),
    CLAP(DrumClass.CLAP, 0.45, listOf("TUNE", "DECAY")),
    SNARE(DrumClass.SNARE, 0.45, listOf("TUNE", "DECAY")),
    HAT_CLOSED(DrumClass.HAT_CLOSED, 0.25, listOf("TUNE", "DECAY")),
    HAT_OPEN(DrumClass.HAT_OPEN, 0.66, listOf("TUNE", "DECAY")),
    WOOD(DrumClass.PERC, 0.60, listOf("TUNE", "DECAY")),
    METAL(DrumClass.PERC, 2.00, listOf("TUNE", "DECAY", "WAVE")),
    /** One bar of rimshots, eighths to 32nds, rising three semitones (Clave Dancehall's A16). */
    RIM_ROLL(DrumClass.PERC, Riddim.rollSeconds(Riddim.DEFAULT_SPEED), listOf("TUNE", "DECAY", "SPEED")),
    /** One bar of snare: two beats of sixteenths, two of 32nds (Dembow's A16). */
    SNARE_ROLL(DrumClass.PERC, Riddim.rollSeconds(Riddim.DEFAULT_SPEED), listOf("TUNE", "DECAY", "SPEED")),
    /** One bar of closed hats speeding to sextuplet 32nds and rising nine semitones (Boom Riddim's A16). */
    HAT_RISER(DrumClass.PERC, Riddim.rollSeconds(Riddim.DEFAULT_SPEED), listOf("TUNE", "DECAY", "SPEED")),
}

/**
 * RIDDIM: dancehall drums, a port of the Python prototype the owner approved by ear on 2026-11-18
 * (`prototype/riddim/riddim_proto.py` and `kits.py`; design and measurements in `docs/RIDDIM.md`).
 *
 * The voices were synthesized to targets measured from the owner's reference loops (pitch sweeps, decay times,
 * brightness, the wood and metal pitches); no audio from those loops is copied or fitted. What makes each voice:
 * - **The kicks and booms are swept sines.** The pitch falls from a bright swoop onto the note in tens of
 *   milliseconds; that fall is the punch. Soft clipping (DRIVE) adds harmonics a phone speaker can play.
 * - **Velocity changes character.** A soft kick starts its sweep lower and drives less; a soft snare's wires and a
 *   soft hat's sizzle are darker; a soft mallet presses longer and cannot excite the high modes.
 * - **A take is another strike.** Each take draws its own small differences (sweep start, glide, strike point on
 *   a bar, noise), so a repeated pad is a drum hit again, not a copy.
 * - **The rolls are the voice struck in time.** RIM_ROLL, SNARE_ROLL and HAT_RISER play one bar at SPEED's tempo
 *   (80 to 140 BPM; render receives no tempo, so a kit sets SPEED to its own BPM), each stroke a fresh hit.
 *
 * The Kotlin draws are another realisation of the same laws (CALABASH's [Rng]), so takes differ from the
 * prototype's sample for sample but not in kind; `RiddimTest` checks the laws. No HOLD: every hit is finite.
 */
object Riddim {

    const val RATE = Dsp.RATE
    private const val FS = Dsp.RATE * Dsp.OVERSAMPLE
    private const val TAU = 2 * PI
    /** Round 1's arithmetic, approved. Bump when a pad's audio changes; saved patches carry it. */
    const val MODEL_VERSION = 1
    /** The takes a pad may name, 0 to [MAX_TAKE]: four round-robin strikes. */
    const val MAX_TAKE = 3
    /** Every hit is peak-matched to this, as the prototype's renders were. */
    const val PEAK = 0.89
    /** SPEED maps linearly onto this tempo range for the rolls. */
    const val MIN_BPM = 80.0
    const val MAX_BPM = 140.0
    const val DEFAULT_SPEED = 0.5f

    private val DEFAULTS = mapOf("TUNE" to .5f, "DECAY" to .5f, "DRIVE" to .5f, "WAVE" to .5f, "SPEED" to DEFAULT_SPEED)
    /** The kit pads that roll start from the voice the roll strikes. */
    private val ROLL_DEFAULTS: Map<RiddimVoice, Map<String, Float>> = mapOf(
        RiddimVoice.SNARE_ROLL to mapOf("TUNE" to (.5f - 2f / 12f), "DECAY" to .4f),
        RiddimVoice.HAT_RISER to mapOf("DECAY" to .3f),
    )

    fun macrosFor(voice: RiddimVoice): List<MacroSpec> =
        voice.controls.map { MacroSpec(it, ROLL_DEFAULTS[voice]?.get(it) ?: DEFAULTS.getValue(it)) }

    fun defaults(voice: RiddimVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    /** [macros] over the defaults: unknown keys dropped, a non-finite value falls back, everything clamped 0..1. */
    fun settled(voice: RiddimVoice, macros: Map<String, Float>): Map<String, Float> {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k) && v.isFinite()) m[k] = v.coerceIn(0f, 1f)
        return m
    }

    fun drumClassFor(voice: RiddimVoice): DrumClass = voice.drumClass

    /** SCRAMBLE near the defaults (no preset bank yet), the way CALABASH rolls. */
    fun scramble(voice: RiddimVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = if (near != null) base + near.macros.filterKeys { it in base } else base
        return Dsp.scrambleNear(seed, temperature, random)
    }

    /** TUNE's span in semitones each way, per voice (kits write pads in semitones through [tuneFor]). */
    fun semitoneSpan(voice: RiddimVoice): Int = when (voice) {
        RiddimVoice.BOOM, RiddimVoice.SLIDE, RiddimVoice.WOOD, RiddimVoice.METAL -> 12
        else -> 6
    }

    /** The TUNE value that moves [voice] [semitones] from its default note. */
    fun tuneFor(voice: RiddimVoice, semitones: Int): Float = 0.5f + semitones / (2f * semitoneSpan(voice))

    /** SPEED for a kit's tempo. */
    fun speedFor(bpm: Double): Float = ((bpm - MIN_BPM) / (MAX_BPM - MIN_BPM)).toFloat().coerceIn(0f, 1f)

    internal fun bpmOf(speed: Float): Double = MIN_BPM + (MAX_BPM - MIN_BPM) * speed.coerceIn(0f, 1f)

    /** A roll's render length: one bar at its tempo plus 0.6 s for the last stroke to ring. */
    fun rollSeconds(speed: Float): Double = 4 * 60.0 / bpmOf(speed) + 0.6

    fun isRoll(voice: RiddimVoice) = voice == RiddimVoice.RIM_ROLL || voice == RiddimVoice.SNARE_ROLL || voice == RiddimVoice.HAT_RISER

    /** The render length of [voice] with [macros], in seconds (a roll's depends on SPEED). */
    fun secondsFor(voice: RiddimVoice, macros: Map<String, Float>): Double =
        if (isRoll(voice)) rollSeconds(settled(voice, macros).getValue("SPEED")) else voice.seconds

    /** One hit at 44.1 kHz, peak 0.89: [velocity] 0..1 is how hard, [take] 0..[MAX_TAKE] which of four strikes. */
    fun render(voice: RiddimVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f, take: Int = 0): Snip {
        val m = settled(voice, macros).mapValues { it.value.toDouble() }
        val v = (if (velocity.isFinite()) velocity.toDouble() else 1.0).coerceIn(0.05, 1.0)
        val k = take.coerceIn(0, MAX_TAKE)
        val out = if (isRoll(voice)) roll(voice, m, v, k) else hit(voice, m, v, k)
        check(out.all { it.isFinite() }) { "RIDDIM $voice rendered a non-finite sample" }
        return Snip(out, channels = 1, sampleRate = RATE)
    }

    // ------------------------------------------------------------------ one hit

    /** A finished hit from any strike index (the rolls strike far past [MAX_TAKE]). */
    private fun hit(voice: RiddimVoice, m: Map<String, Double>, v: Double, strike: Int): FloatArray {
        val raw = when (voice) {
            RiddimVoice.KICK -> kick(m, v, strike, 0.6, fEnd = 40.0, fStart = 190.0, tau = 0.028, t60 = 0.30, drv = 0.35, clickAmt = 0.35)
            RiddimVoice.DEMBOW_KICK -> kick(m, v, strike, 0.7, fEnd = 27.0, fStart = 140.0, tau = 0.030, t60 = 0.55, drv = 0.6, clickAmt = 0.25)
            RiddimVoice.BOOM -> boom(m, v, strike, glide = 0.025, fStart = 150.0)
            RiddimVoice.SLIDE -> boom(m, v, strike, glide = 0.09, fStart = 95.0)
            RiddimVoice.RIMSHOT -> rimshot(m, v, strike)
            RiddimVoice.CLAP -> clap(m, v, strike)
            RiddimVoice.SNARE -> snare(m, v, strike)
            RiddimVoice.HAT_CLOSED -> hat(m, v, strike, 0.09)
            RiddimVoice.HAT_OPEN -> hat(m, v, strike, 0.55)
            RiddimVoice.WOOD -> wood(m, v, strike)
            RiddimVoice.METAL -> metal(m, v, strike)
            else -> error("RIDDIM $voice is a roll")
        }
        return finish(raw, voice.seconds)
    }

    /** The house stage at 44.1 kHz: band-limit, decimate, DC out (20 Hz), a 50 ms cosine fade, peak 0.89. */
    private fun finish(raw: DoubleArray, seconds: Double): FloatArray {
        val f = FloatArray(raw.size) { raw[it].toFloat() }
        Tide.bandLimit(f, FS)
        val dec = Dsp.decimate(f, RATE)
        val len = min(dec.size, (seconds * RATE).toInt())
        var mean = 0.0
        for (x in dec) mean += x
        mean /= dec.size
        val rc = 1 / (TAU * 20.0)
        val al = rc / (rc + 1.0 / RATE)
        val out = DoubleArray(len)
        var px = 0.0
        var py = 0.0
        for (i in 0 until len) { val xi = dec[i] - mean; py = al * xi - al * px + al * py; px = xi; out[i] = py }
        val fade = min(len, (0.05 * RATE).toInt())
        for (q in 0 until fade) out[len - fade + q] *= 0.5 * (1 + cos(PI * q / (fade - 1).coerceAtLeast(1)))
        var p = 0.0
        for (x in out) p = max(p, abs(x))
        val g = if (p > 0) PEAK / p else 0.0
        return FloatArray(len) { (out[it] * g).toFloat() }
    }

    private fun rng(vararg parts: Any) = Rng(Dsp.seedFor("RIDDIM", *parts))

    /** Velocity as a seed part, so a zone's takes are its own strikes. */
    private fun vKey(v: Double) = round(v * 1000).toInt()

    private fun n(seconds: Double) = (seconds * FS).toInt()

    private fun Map<String, Double>.g(key: String) = getValue(key)

    // ------------------------------------------------------------------ building blocks

    /** A sine falling from fStart to fEnd with time constant tau; phase is the running sum of frequency. */
    private fun sweptSine(n: Int, fEnd: Double, fStart: Double, tau: Double): DoubleArray {
        val y = DoubleArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / FS
            phase += fEnd + (fStart - fEnd) * exp(-t / tau)
            y[i] = sin(TAU * phase / FS)
        }
        return y
    }

    /** Soft clipping: harmonics at 2x and 3x the note, so a 40 Hz boom reaches a phone speaker. 0 is clean. */
    private fun drive(x: DoubleArray, amount: Double): DoubleArray {
        if (amount <= 0) return x
        val g = 1 + 6 * amount
        val norm = tanh(g)
        return DoubleArray(x.size) { tanh(g * x[it]) / norm }
    }

    private fun envExp(i: Int, t60: Double) = exp(-6.9078 * (i.toDouble() / FS) / t60)

    /** numpy's `hanning(w)`: the symmetric Hann window. */
    private fun hanning(w: Int) = DoubleArray(w) { if (w == 1) 1.0 else 0.5 - 0.5 * cos(TAU * it / (w - 1)) }

    /** The beater: a short noise tick, high-passed; velocity makes it louder and brighter. */
    private fun click(n: Int, v: Double, r: Rng, widthMs: Double = 0.25): DoubleArray {
        val w = (widthMs * 1e-3 * FS).toInt()
        val c = DoubleArray(n)
        val h = hanning(w)
        for (i in 0 until min(w, n)) c[i] = r.uniform(-1.0, 1.0) * h[i]
        return CalabashDsp.hp(c, 1500 + 3000 * v)
    }

    /** One ringing mode, a two-pole filter: a struck bar's partial, or a drum circuit's ring. */
    private fun resonator(x: DoubleArray, f: Double, t60: Double): DoubleArray {
        val r = exp(-6.9078 / (t60 * FS))
        val w = TAU * f / FS
        return CalabashDsp.lfilter(doubleArrayOf(1 - r), doubleArrayOf(1.0, -2 * r * cos(w), r * r), x)
    }

    private fun peakNormalized(y: DoubleArray): DoubleArray {
        var p = 0.0
        for (x in y) p = max(p, abs(x))
        return if (p > 0) DoubleArray(y.size) { y[it] / p } else y
    }

    // ------------------------------------------------------------------ the electronic voices

    private fun kick(m: Map<String, Double>, v: Double, k: Int, seconds: Double, fEnd: Double, fStart: Double, tau: Double,
                     t60: Double, drv: Double, clickAmt: Double): DoubleArray {
        val r = rng("kick", fEnd, vKey(v), k)
        val n = n(seconds)
        val jit = 1 + r.normal(0.0, 0.02)
        val tune = 2.0.pow(m.g("TUNE") - 0.5)                       // +-6 semitones
        // A soft hit starts its sweep lower and drives the circuit less: rounder, not only quieter.
        val body = sweptSine(n, fEnd * tune, fStart * tune * (0.6 + 0.4 * v) * jit, tau * jit)
        val life = t60 * (0.85 + 0.15 * m.g("DECAY") * 2)
        val shaped = DoubleArray(n) { body[it] * envExp(it, life) * (0.6 + 0.4 * v) }
        val y = drive(shaped, drv * m.g("DRIVE") * 2 * (0.3 + 0.7 * v))
        val c = click(n, v, r)
        return DoubleArray(n) { y[it] + clickAmt * v * v * c[it] }   // v squared: a soft hit loses its beater fastest
    }

    /** The long tuned boom (BOOM) and its slow-glide twin (SLIDE): the kick is the bass line. Default note F1. */
    private fun boom(m: Map<String, Double>, v: Double, k: Int, glide: Double, fStart: Double): DoubleArray {
        val r = rng("boom", glide, vKey(v), k)
        val n = n(1.6)
        val note = 29 + Math.rint((m.g("TUNE") - 0.5) * 24).toInt()   // MIDI 29 = F1, 43.65 Hz
        // Each take varies the glide and the ring a little, so repeated notes are new strikes.
        val g = glide * (1 + r.normal(0.0, 0.12))
        val body = sweptSine(n, hz(note.toDouble()), fStart * (0.8 + 0.2 * v) * (1 + r.normal(0.0, 0.03)), g)
        val t60 = (0.6 + 1.6 * m.g("DECAY")) * (1 + r.normal(0.0, 0.04))
        val shaped = DoubleArray(n) { body[it] * envExp(it, t60) * min(1.0, (it.toDouble() / FS) / 0.002) * (0.55 + 0.45 * v) }
        val y = drive(shaped, m.g("DRIVE") * 0.9 * (0.5 + 0.5 * v))     // harder = more growl
        val c = click(n, v, r, 0.15)
        return DoubleArray(n) { y[it] + 0.12 * v * v * c[it] }
    }

    /** Digital rim: two bright resonators struck by a pulse, near 480 Hz and 1.08 kHz. */
    private fun rimshot(m: Map<String, Double>, v: Double, k: Int): DoubleArray {
        val r = rng("rim", vKey(v), k)
        val n = n(0.25)
        val tune = 2.0.pow(m.g("TUNE") - 0.5)
        val x = DoubleArray(n)
        val pulse = (0.0004 * FS).toInt()
        for (i in 0 until pulse) x[i] = 1.0
        for (i in 0 until n) x[i] += 0.15 * r.uniform(-1.0, 1.0) * envExp(i, 0.01)
        val lo = resonator(x, 480 * tune, 0.05)
        val hi = resonator(x, 1080 * tune * (1 + r.normal(0.0, 0.006)), 0.06 + 0.04 * m.g("DECAY"))
        val y = peakNormalized(DoubleArray(n) { 0.6 * lo[it] + hi[it] * (0.6 + 0.6 * v) })
        return drive(y, 0.4 + 0.4 * v)
    }

    /** Electronic clap: three or four quick noise bursts (hands not quite together), then a tail, near 1.2 kHz. */
    private fun clap(m: Map<String, Double>, v: Double, k: Int): DoubleArray {
        val r = rng("clap", vKey(v), k)
        val n = n(0.45)
        val noise = r.uniform(-1.0, 1.0, n)
        val spread = 1 + r.normal(0.0, 0.08)
        val gaps = doubleArrayOf(0.0, 0.009, 0.020, 0.028).map { it * spread }
        val bursts = if (v > 0.5) 4 else 3
        val e = DoubleArray(n)
        val tailAt = gaps.last()
        val tailTau = 0.02 + 0.04 * m.g("DECAY")
        for (i in 0 until n) {
            val t = i.toDouble() / FS
            for (b in 0 until bursts) {
                val tt = t - gaps[b]
                if (tt >= 0) e[i] += exp(-tt / 0.0025) * (0.8 + 0.2 * b / 3)
            }
            if (t >= tailAt) e[i] += exp(-(t - tailAt) / tailTau)
        }
        val centre = 1200 * 2.0.pow(m.g("TUNE") - 0.5) * (0.85 + 0.15 * v)
        val src = DoubleArray(n) { noise[it] * e[it] }
        val body = CalabashDsp.band(src, centre / 1.9, centre * 2.6)
        val air = CalabashDsp.hp(src, 4000.0)
        return DoubleArray(n) { body[it] + 0.15 * v * air[it] }
    }

    /** Snare: a short tuned body (about 207 and 262 Hz, the 'tock') plus bright noise wires (the 'tss'). */
    private fun snare(m: Map<String, Double>, v: Double, k: Int): DoubleArray {
        val r = rng("snare", vKey(v), k)
        val n = n(0.45)
        val tune = 2.0.pow(m.g("TUNE") - 0.5)
        val b1 = sweptSine(n, 207 * tune, 290 * tune, 0.01)
        val b2 = sweptSine(n, 262 * tune, 340 * tune, 0.008)
        val wires = CalabashDsp.band(r.uniform(-1.0, 1.0, n), 1500 + 1500 * v, 9000.0)
        val wireLife = 0.12 + 0.2 * m.g("DECAY")
        val y = DoubleArray(n) { 0.7 * (b1[it] + 0.6 * b2[it]) * envExp(it, 0.16) + (0.5 + 0.5 * v) * wires[it] * envExp(it, wireLife) * 1.4 }
        return drive(y, 0.3)
    }

    /** Six square oscillators at unrelated frequencies: the metallic cluster drum machines use for hats. */
    private val HAT_FREQS = doubleArrayOf(205.3, 304.4, 369.6, 522.7, 540.0, 800.0)
    private const val HAT_TOP_HZ = 20000.0

    /**
     * Band-limited squares by phasor rotation. The prototype summed odd harmonics to 40 kHz; the output stage
     * band-limits at 20 kHz before decimating, so harmonics above 20 kHz never reach the pad and are skipped here
     * (half the cost; the 18 to 20 kHz edge differs by what the 8th-order band-limit would have let through).
     */
    private fun squares(n: Int, r: Rng): DoubleArray {
        val y = DoubleArray(n)
        for (f0 in HAT_FREQS) {
            val f = f0 * (1 + r.normal(0.0, 0.002))
            val ph = r.uniform(0.0, 1.0)
            var h = 1
            while (h <= (HAT_TOP_HZ / f).toInt()) {
                val w = TAU * f * h / FS
                val cw = cos(w); val sw = sin(w)
                var c = cos(TAU * ph * h); var s = sin(TAU * ph * h)
                val a = 1.0 / h
                for (i in 0 until n) {
                    y[i] += s * a
                    val nc = c * cw - s * sw
                    s = s * cw + c * sw
                    c = nc
                }
                h += 2
            }
        }
        return y
    }

    private fun hat(m: Map<String, Double>, v: Double, k: Int, t60: Double): DoubleArray {
        val r = rng("hat", t60, vKey(v), k)
        val n = n(max(0.25, t60 * 1.2))
        val metal = squares(n, r)
        // Softer = darker. Capped below the band's 15 kHz top: the riser climbs nine semitones above TUNE, and from
        // TUNE 1 the bottom edge would pass the top (the approved renders reach 10.9 kHz, under the cap).
        val lo = min(12000.0, 6500 * 2.0.pow(m.g("TUNE") - 0.5) * (0.85 + 0.15 * v))
        val mix = DoubleArray(n) { 0.6 * metal[it] + 0.4 * r.uniform(-1.0, 1.0) * 3 }
        var y = CalabashDsp.band(mix, lo, 15000.0, 3)
        y = CalabashDsp.lp(y, 8000 + 9000 * v)                           // a soft hit loses its sizzle
        val life = t60 * (0.6 + 0.8 * m.g("DECAY"))
        return DoubleArray(n) { y[it] * envExp(it, life) * min(1.0, (it.toDouble() / FS) / 0.0004) }
    }

    // ------------------------------------------------------------------ the struck bars

    /** (ratio, gain, t60 scale). A marimba maker carves the underside so mode 2 lands on 4x and mode 3 near 10x. */
    private val TUNED_BAR = arrayOf(doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(3.98, 0.35, 0.35), doubleArrayOf(9.9, 0.12, 0.15))
    /** A plain bronze bar's inharmonic modes (2.76, 5.40, 8.93). */
    private val BRONZE_BAR = arrayOf(doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(2.76, 0.5, 0.7), doubleArrayOf(5.40, 0.28, 0.5),
        doubleArrayOf(8.93, 0.12, 0.35))

    private fun struck(f0: Double, modes: Array<DoubleArray>, t60Root: Double, v: Double, k: Int, seconds: Double,
                       malletSoftMs: Double, malletHardMs: Double, name: String): DoubleArray {
        val r = rng(name, Math.round(f0 * 100), vKey(v), k)
        val n = n(seconds)
        // The pulse width is the mallet: a pulse W seconds long cannot excite much above 2/W Hz, so softer is darker.
        val malletMs = malletSoftMs + (malletHardMs - malletSoftMs) * v
        val w = max(2, (malletMs * 1e-3 * FS * (1 + r.normal(0.0, 0.05))).toInt())
        val x = DoubleArray(n)
        for (i in 0 until min(w, n)) x[i] = 0.5 * (1 - cos(TAU * i / w))
        // A take moves the strike point: the upper modes re-weight by up to +-2.5 dB, the pitch by a cent or two.
        val y = DoubleArray(n)
        for ((i, mode) in modes.withIndex()) {
            val f = f0 * mode[0] * 2.0.pow(r.normal(0.0, 1.5) / 1200)
            if (f > 18000) continue
            val g = if (i == 0) mode[1] else mode[1] * 10.0.pow(r.uniform(-2.5, 2.5) / 20)
            val ring = resonator(x, f, t60Root * mode[2] * (1 + r.normal(0.0, 0.04)))
            for (j in 0 until n) y[j] += g * ring[j]
        }
        return y
    }

    /** Tuned wood, default F#3; TUNE spans an octave each way. */
    private fun wood(m: Map<String, Double>, v: Double, k: Int): DoubleArray {
        val note = 54 + Math.rint((m.g("TUNE") - 0.5) * 24).toInt()
        return struck(hz(note.toDouble()), TUNED_BAR, 0.06 + 0.2 * m.g("DECAY"), v, k, 0.6, 3.0, 0.5, "wood")
    }

    /** Keyed bronze played as a detuned pair: the two beat together (the gamelan's ombak). Default D5. */
    private fun metal(m: Map<String, Double>, v: Double, k: Int): DoubleArray {
        val note = 74 + Math.rint((m.g("TUNE") - 0.5) * 24).toInt()
        val f = hz(note.toDouble())
        val beat = 2 + 6 * m.g("WAVE")
        val t60 = 0.8 + 1.6 * m.g("DECAY")
        val a = struck(f, BRONZE_BAR, t60, v, k, 2.0, 1.5, 0.25, "metal")
        val b = struck(f + beat, BRONZE_BAR, t60, v, k + 101, 2.0, 1.5, 0.25, "metal")
        return DoubleArray(a.size) { a[it] + 0.85 * b[it] }
    }

    private fun hz(midi: Double) = 440.0 * 2.0.pow((midi - 69) / 12)

    // ------------------------------------------------------------------ the rolls (one bar at SPEED's tempo)

    /** (beat position, velocity scale, semitones) for each stroke. */
    internal fun rollStrokes(voice: RiddimVoice): List<Triple<Double, Double, Double>> {
        val grid = when (voice) {
            RiddimVoice.RIM_ROLL -> listOf(Triple(0.0, 2.0, 0.5), Triple(2.0, 3.0, 0.25), Triple(3.0, 4.0, 0.125))
            RiddimVoice.SNARE_ROLL -> listOf(Triple(0.0, 2.0, 0.25), Triple(2.0, 4.0, 0.125))
            else -> listOf(Triple(0.0, 2.0, 0.25), Triple(2.0, 3.0, 0.125), Triple(3.0, 4.0, 1.0 / 12))
        }
        val beats = ArrayList<Double>()
        for ((a, b, step) in grid) {
            var x = a
            while (x < b - 1e-9) { beats += x; x += step }
        }
        val rise = when (voice) { RiddimVoice.RIM_ROLL -> 3.0; RiddimVoice.HAT_RISER -> 9.0; else -> 0.0 }
        return beats.map { x -> val frac = x / 4; Triple(x, 0.3 + 0.7 * frac.pow(1.3), rise * frac) }
    }

    private fun roll(voice: RiddimVoice, m: Map<String, Double>, v: Double, take: Int): FloatArray {
        val struckVoice = when (voice) {
            RiddimVoice.RIM_ROLL -> RiddimVoice.RIMSHOT
            RiddimVoice.SNARE_ROLL -> RiddimVoice.SNARE
            else -> RiddimVoice.HAT_CLOSED
        }
        val speed = m.g("SPEED").toFloat()
        val beat = 60.0 / bpmOf(speed)
        val n = (rollSeconds(speed) * RATE).toInt()
        val out = DoubleArray(n)
        val span = semitoneSpan(struckVoice)
        val baseSemis = (m.g("TUNE") - 0.5) * 2 * span
        for ((i, stroke) in rollStrokes(voice).withIndex()) {
            val (at, scale, semis) = stroke
            val tune = 0.5 + (baseSemis + semis) / (2 * span)
            val hm = mapOf("TUNE" to tune, "DECAY" to m.g("DECAY"))
            // Every stroke is its own strike: the take picks a block of 64 strike indices.
            val y = hit(struckVoice, hm, max(0.05, v * scale), take * 64 + i)
            val a = (at * beat * RATE).toInt()
            for (j in y.indices) if (a + j < n) out[a + j] += y[j].toDouble()
        }
        var p = 0.0
        for (x in out) p = max(p, abs(x))
        val g = if (p > 0) PEAK / p else 0.0
        return FloatArray(n) { (out[it] * g).toFloat() }
    }
}
