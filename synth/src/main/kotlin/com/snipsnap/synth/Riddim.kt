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
    /** Tuned wood. BEATER is the mallet, yarn (0) to hard (1); 0.5 is the wood of rounds 1 and 2 exactly. */
    WOOD(DrumClass.PERC, 0.60, listOf("TUNE", "DECAY", "BEATER")),
    METAL(DrumClass.PERC, 2.00, listOf("TUNE", "DECAY", "WAVE")),
    /** A small, high, metal bell: the "ting" of a Brazilian funk groove (round 3). */
    TING(DrumClass.PERC, 0.50, listOf("TUNE", "DECAY")),
    /** A real ratchet: a wooden gear flicking a wooden tongue, clicks you can count (round 3, the owner's choice). */
    RATCHET(DrumClass.PERC, 0.80, listOf("TUNE", "DECAY", "WAVE")),
    /** One bar of rimshots, eighths to 32nds, rising three semitones (Clave Dancehall's A16). */
    RIM_ROLL(DrumClass.PERC, Riddim.rollSeconds(Riddim.DEFAULT_SPEED), listOf("TUNE", "DECAY", "SPEED")),
    /** One bar of snare: two beats of sixteenths, two of 32nds (Dembow's A16). */
    SNARE_ROLL(DrumClass.PERC, Riddim.rollSeconds(Riddim.DEFAULT_SPEED), listOf("TUNE", "DECAY", "SPEED")),
    /** One bar of closed hats speeding to sextuplet 32nds and rising nine semitones (Boom Riddim's A16). */
    HAT_RISER(DrumClass.PERC, Riddim.rollSeconds(Riddim.DEFAULT_SPEED), listOf("TUNE", "DECAY", "SPEED")),
    /** One bar of ratchet turns, eighths to 32nds, rising five semitones (Baile Funk's A16). */
    RATCHET_RISER(DrumClass.PERC, Riddim.rollSeconds(Riddim.DEFAULT_SPEED), listOf("TUNE", "DECAY", "WAVE", "SPEED")),
    /** One bar of CALABASH's nder, two beats of sixteenths and two of 32nds into the downbeat (Sabar Swing's A16). */
    NDER_ROLL(DrumClass.PERC, Riddim.rollSeconds(Riddim.DEFAULT_SPEED), listOf("TUNE", "SPEED")),
    /** A marimba player's roll: 32nds on one wood for a whole bar, growing (Wood and Bronze's A16). */
    WOOD_TREMOLO(DrumClass.PERC, Riddim.rollSeconds(Riddim.DEFAULT_SPEED), listOf("TUNE", "DECAY", "BEATER", "SPEED")),
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

    private val DEFAULTS = mapOf("TUNE" to .5f, "DECAY" to .5f, "DRIVE" to .5f, "WAVE" to .5f, "BEATER" to .5f, "SPEED" to DEFAULT_SPEED)
    /** The kit pads that roll start from the voice the roll strikes. Built on first use: [RiddimVoice]'s entries ask
     * [rollSeconds] while they are being made, so a value built when this object starts could meet them unset. */
    private val ROLL_DEFAULTS: Map<RiddimVoice, Map<String, Float>> by lazy { mapOf(
        RiddimVoice.SNARE_ROLL to mapOf("TUNE" to (.5f - 2f / 12f), "DECAY" to .4f),
        RiddimVoice.HAT_RISER to mapOf("DECAY" to .3f),
        RiddimVoice.RATCHET_RISER to mapOf("DECAY" to 0f),
    ) }

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
        RiddimVoice.BOOM, RiddimVoice.SLIDE, RiddimVoice.WOOD, RiddimVoice.METAL, RiddimVoice.TING, RiddimVoice.RATCHET,
        RiddimVoice.RATCHET_RISER, RiddimVoice.WOOD_TREMOLO -> 12
        else -> 6
    }

    /** The TUNE value that moves [voice] [semitones] from its default note. */
    fun tuneFor(voice: RiddimVoice, semitones: Int): Float = 0.5f + semitones / (2f * semitoneSpan(voice))

    /** SPEED for a kit's tempo. */
    fun speedFor(bpm: Double): Float = ((bpm - MIN_BPM) / (MAX_BPM - MIN_BPM)).toFloat().coerceIn(0f, 1f)

    internal fun bpmOf(speed: Float): Double = MIN_BPM + (MAX_BPM - MIN_BPM) * speed.coerceIn(0f, 1f)

    /** A roll's render length: one bar at its tempo plus 0.6 s for the last stroke to ring. */
    fun rollSeconds(speed: Float): Double = 4 * 60.0 / bpmOf(speed) + 0.6

    /** A `when`, not a set built when this object starts: see [ROLL_DEFAULTS]. */
    fun isRoll(voice: RiddimVoice) = when (voice) {
        RiddimVoice.RIM_ROLL, RiddimVoice.SNARE_ROLL, RiddimVoice.HAT_RISER, RiddimVoice.RATCHET_RISER,
        RiddimVoice.NDER_ROLL, RiddimVoice.WOOD_TREMOLO -> true
        else -> false
    }

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
            RiddimVoice.TING -> ting(m, v, strike)
            RiddimVoice.RATCHET -> ratchet(m, v, strike)
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

    /**
     * Tuned wood, default F#3; TUNE spans an octave each way. BEATER is the mallet (added after the owner heard round
     * 3: "wood might need more of an attack"). At 0.5 it is exactly the wood of rounds 1 and 2: the mallet is
     * unchanged and nothing is added. Measured on the owner's wood loops, a wood hit's first 8 ms centre at 1.5 to
     * 2.4 kHz; WOOD's centred at 0.3 to 0.5 kHz, almost all fundamental. Above 0.5 a harder mallet presses for less
     * time, lifts the bar's own upper modes (they still die faster than the fundamental) and adds a knock.
     */
    private fun wood(m: Map<String, Double>, v: Double, k: Int): DoubleArray {
        val note = 54 + Math.rint((m.g("TUNE") - 0.5) * 24).toInt()
        val beater = m.g("BEATER")
        val width = 2.0.pow((0.5 - beater) * 2)                  // 1 at 0.5; hard presses half as long, yarn twice
        val knock = max(0.0, (beater - 0.5) * 2)                 // 0 up to the middle, 1 at a hard mallet
        val modes = Array(TUNED_BAR.size) { i ->
            val mode = TUNED_BAR[i]
            doubleArrayOf(mode[0], mode[1] * (1 + UPPER_BOOST * knock).pow(i), mode[2])
        }
        val f0 = hz(note.toDouble())
        val y = struck(f0, modes, 0.06 + 0.2 * m.g("DECAY"), v, k, 0.6, 3.0 * width, 0.5 * width, "wood")
        if (knock > 0) {
            val tock = knock(f0, v, k, 3.0 * width, 0.5 * width)
            val g = knock * v.pow(1.2)
            for (i in y.indices) y[i] += g * tock[i]
        }
        return y
    }

    /** How far a hard mallet lifts the bar's upper modes, and the knock's level: a hard hit's first 8 ms near 1.4 to
     * 1.6 kHz (the owner's woody loop: 1.5 kHz). */
    private const val UPPER_BOOST = 3.0
    private const val KNOCK_GAIN = 6.0
    /** The tock's own ringing (Hz, gain, T60 s), fixed in frequency: a mallet head and a stiff block do not follow
     * the note. Tuning these to the note could not lift a low wood past about 950 Hz. */
    private val WOOD_KNOCK = arrayOf(doubleArrayOf(1900.0, 1.0, .012), doubleArrayOf(2750.0, .8, .009), doubleArrayOf(4100.0, .5, .006))

    /** The tock of a mallet landing: short fixed modes and a burst of contact noise, struck by the same mallet pulse,
     * so a softer stroke (a wider pulse) still reaches less of them and stays darker. */
    private fun knock(f0: Double, v: Double, k: Int, softMs: Double, hardMs: Double): DoubleArray {
        val r = rng("wood-knock", Math.round(f0 * 100), vKey(v), k)
        val n = n(0.6)
        val w = max(2, ((softMs + (hardMs - softMs) * v) * 1e-3 * FS * (1 + r.normal(0.0, 0.05))).toInt())
        val pulse = DoubleArray(n)
        for (i in 0 until min(w, n)) pulse[i] = 0.5 * (1 - cos(TAU * i / w))
        val y = DoubleArray(n)
        for (mode in WOOD_KNOCK) {
            val f = mode[0] * 2.0.pow(r.normal(0.0, 30.0) / 1200)             // each take lands a little apart
            val g = mode[1] * 10.0.pow(r.uniform(-2.0, 2.0) / 20)
            val ring = resonator(pulse, f, mode[2] * (1 + r.normal(0.0, 0.08)))
            for (i in 0 until n) y[i] += g * ring[i]
        }
        val contact = CalabashDsp.band(DoubleArray(n) { r.normal(0.0, 1.0) * pulse[it] }, 1500.0, 7000.0)
        return DoubleArray(n) { KNOCK_GAIN * (y[it] + 0.25 * contact[it]) }
    }

    // ------------------------------------------------------------------ round 3: the Brazilian funk tops

    /** The ting loop's bell measured four close partials, 3234, 3309, 3715 and 3875 Hz: two pairs that beat, the
     * shimmer of a small hand bell. (ratio, gain, t60 scale.) */
    private val TING_BAR = arrayOf(doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(1.023, 0.8, 0.9), doubleArrayOf(1.149, 0.6, 0.7),
        doubleArrayOf(1.198, 0.45, 0.6), doubleArrayOf(2.31, 0.15, 0.3))

    /** A small high bell, default 3234 Hz, TUNE an octave either way. A metal beater runs 0.4 ms soft to 0.06 ms hard,
     * so a soft stroke cannot reach the upper pair. -20 dB lands near the measured 48 ms. */
    private fun ting(m: Map<String, Double>, v: Double, k: Int): DoubleArray {
        val f0 = 3234.0 * 2.0.pow((m.g("TUNE") - 0.5) * 24 / 12)
        return struck(f0, TING_BAR, 0.08 + 0.38 * m.g("DECAY"), v, k, 0.5, 0.4, 0.06, "ting")
    }

    /**
     * A real ratchet, the owner's choice from the A/B (2026-11-19). A wooden gear, turned, flicks a wooden tongue:
     * separate clicks, 20 to 40 a second, that can be counted. Each click rings the tongue (about 1.6, 2.4 and 3.3 kHz)
     * and the hollow box it sits on (420 Hz). DECAY is how long the turn lasts (0.12 to 0.6 s), WAVE how fast the gear
     * turns. A harder turn is faster and brighter; a softer one slower, with more box, so darker.
     */
    private fun ratchet(m: Map<String, Double>, v: Double, k: Int): DoubleArray {
        val r = rng("ratchet-wood", k, vKey(v))
        val length = 0.12 + 0.48 * m.g("DECAY")
        val rate = (20 + 20 * m.g("WAVE")) * (0.7 + 0.45 * v)
        val n = n(max(length + 0.15, RiddimVoice.RATCHET.seconds))     // the voice's full length at any DECAY
        val x = DoubleArray(n)
        val w = max(2, (0.0004 * FS).toInt())
        var t = 0.0
        while (t < length) {
            val level = (1.0 - 0.35 * t / length) * r.uniform(0.7, 1.0)          // the turn loses a little force
            val a = (t * FS).toInt()
            for (i in 0 until w) if (a + i < n) x[a + i] += level * 0.5 * (1 - cos(TAU * i / w))
            val slow = 1 + 0.4 * (t / length).pow(2)                          // the gear slows at the end
            t += (1 / rate) * slow * (1 + r.normal(0.0, 0.08))
        }
        val shift = 2.0.pow((m.g("TUNE") - 0.5) * 2)
        val tongue = DoubleArray(n)
        for ((f, g, t60) in listOf(Triple(1600.0, 1.0, .025), Triple(2400.0, .7, .018), Triple(3300.0, .45, .012))) {
            val ring = resonator(x, f * shift * 2.0.pow(r.normal(0.0, 15.0) / 1200), t60)
            for (i in 0 until n) tongue[i] += g * ring[i]
        }
        val box = resonator(x, 420 * shift, .04)
        val snap = CalabashDsp.band(DoubleArray(n) { x[it] * r.normal(0.0, 1.0) }, 2000.0, 8000.0)
        return DoubleArray(n) { (0.3 + 0.7 * v) * (tongue[it] + 0.3 * snap[it]) + (1.2 - 0.6 * v) * 0.6 * box[it] }
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
            RiddimVoice.RIM_ROLL, RiddimVoice.RATCHET_RISER -> listOf(Triple(0.0, 2.0, 0.5), Triple(2.0, 3.0, 0.25), Triple(3.0, 4.0, 0.125))
            RiddimVoice.SNARE_ROLL, RiddimVoice.NDER_ROLL -> listOf(Triple(0.0, 2.0, 0.25), Triple(2.0, 4.0, 0.125))
            RiddimVoice.WOOD_TREMOLO -> listOf(Triple(0.0, 4.0, 0.125))
            else -> listOf(Triple(0.0, 2.0, 0.25), Triple(2.0, 3.0, 0.125), Triple(3.0, 4.0, 1.0 / 12))
        }
        val beats = ArrayList<Double>()
        for ((a, b, step) in grid) {
            var x = a
            while (x < b - 1e-9) { beats += x; x += step }
        }
        val rise = when (voice) { RiddimVoice.RIM_ROLL -> 3.0; RiddimVoice.HAT_RISER -> 9.0; RiddimVoice.RATCHET_RISER -> 5.0; else -> 0.0 }
        return beats.map { x ->
            val frac = x / 4
            // The tremolo grows evenly, as a marimba player's roll does; the others crescendo late.
            val scale = if (voice == RiddimVoice.WOOD_TREMOLO) 0.25 + 0.75 * frac else 0.3 + 0.7 * frac.pow(1.3)
            Triple(x, scale, rise * frac)
        }
    }

    private fun roll(voice: RiddimVoice, m: Map<String, Double>, v: Double, take: Int): FloatArray {
        val struckVoice = when (voice) {
            RiddimVoice.RIM_ROLL -> RiddimVoice.RIMSHOT
            RiddimVoice.SNARE_ROLL -> RiddimVoice.SNARE
            RiddimVoice.RATCHET_RISER -> RiddimVoice.RATCHET
            RiddimVoice.WOOD_TREMOLO -> RiddimVoice.WOOD
            RiddimVoice.NDER_ROLL -> null                       // CALABASH's nder, struck below
            else -> RiddimVoice.HAT_CLOSED
        }
        val speed = m.g("SPEED").toFloat()
        val beat = 60.0 / bpmOf(speed)
        val n = (rollSeconds(speed) * RATE).toInt()
        val out = DoubleArray(n)
        val span = if (struckVoice != null) semitoneSpan(struckVoice) else 6
        val baseSemis = (m.g("TUNE") - 0.5) * 2 * span
        for ((i, stroke) in rollStrokes(voice).withIndex()) {
            val (at, scale, semis) = stroke
            val tune = 0.5 + (baseSemis + semis) / (2 * span)
            val force = max(0.05, v * scale)
            // Every stroke is its own strike: the take picks a block of 64 strike indices.
            val y = if (struckVoice == null) {
                Calabash.stroke(CalabashVoice.NDER, mapOf("TUNE" to tune.toFloat()), force.toFloat(), take * 64 + i).samples
            } else {
                // The struck voice's own controls, from the roll's: TUNE carries the climb, SPEED stays with the roll.
                val hm = struckVoice.controls.associateWith { if (it == "TUNE") tune else m[it] ?: DEFAULTS.getValue(it).toDouble() }
                hit(struckVoice, hm, force, take * 64 + i)
            }
            val a = (at * beat * RATE).toInt()
            for (j in y.indices) if (a + j < n) out[a + j] += y[j].toDouble()
        }
        var p = 0.0
        for (x in out) p = max(p, abs(x))
        val g = if (p > 0) PEAK / p else 0.0
        return FloatArray(n) { (out[it] * g).toFloat() }
    }
}
