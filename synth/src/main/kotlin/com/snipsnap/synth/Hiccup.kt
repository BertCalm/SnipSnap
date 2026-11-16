package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** The eight HICCUP pads: the five drums, a tom, and two toy-chip percussion sounds. */
enum class HiccupVoice { KICK, SNARE, CLAP, HAT_CLOSED, HAT_OPEN, TOM, BLIP, SQUEAK }

/**
 * HICCUP, a glitch drum engine: one clean hit is written into a short memory, then a seeded fault script re-reads
 * that memory. The re-read snags at a point, repeats a slice, lets the repeats shorten until they are a pitch, and
 * loses a little more fidelity on every generation. The gesture is hit, snag, ratchet, buzz, cut.
 *
 * Round 1 is the Kotlin port of the Python prototype (`docs/prototypes/hiccup/hiccup_proto.py`) as the spec
 * (`docs/superpowers/specs/2026-11-14-hiccup-glitch-drum-engine-design.md`) amends it. Nothing has been heard.
 *
 * The code is three units in the order the signal runs:
 *
 *     [source] one clean hit at 4x ─► [script] up to five fault events ─► [reread] one read-position map ─► output stage
 *
 * - [source] synthesizes the hit. TOY makes the chip cheaper in three ways at once (a frame clock on the envelopes,
 *   a squarer wave, a slower noise clock). Velocity is contact hardness, so it moves sweep depth, click level and
 *   band edges instead of only the level.
 * - [script] draws its random values once per voice and macros only scale them. A knob turn therefore never
 *   reshuffles the pattern, and FAULT only ever adds events.
 * - [reread] walks the memory by a read-position map. CRUSH is loss per re-read, so the hit as played is nearly clean
 *   and the damage collects on the repeats. The path is one way, with no feedback, so nothing needs bounding.
 *
 * The output stage is the house one: band-limit at 4x, decimate, DC block, peak-match to 0.95, fade the tail. The
 * crushed staircase is made at the internal rate and band-limited once, so a crushed pad is broadband by design.
 */
object Hiccup {

    const val RATE = Dsp.RATE
    private const val FS_DEFAULT = Dsp.RATE * Dsp.OVERSAMPLE
    private val CORE = Core(FS_DEFAULT)

    /** Bump when a pad's audio changes; saved patches carry it. */
    const val MODEL_VERSION = 2

    /** The takes a pad may name: 0 is the plain strike, 1 to [MAX_TAKE] the dealt round-robin takes. */
    const val MAX_TAKE = 4

    /** A pad never runs past this, under the classifier's 1.5 s loop line. */
    internal const val MAX_SECONDS = 1.4

    /** Fault slots per voice. */
    internal const val SLOTS = 5

    /** The raised-cosine ramp at every discontinuous cut. A hard gate clicks; this still ticks, on purpose. */
    private const val EDGE_SECONDS = 0.35e-3

    /** Repeats shorter than this are heard as a pitch, so their period is snapped to a note. */
    private const val SNAP_BELOW_SECONDS = 0.012

    /** The shortest repeat the reader will play. */
    private const val MIN_REPEAT_SECONDS = 0.0006

    /** Nothing before this moment of the hit is ever replaced by a buzz: an at-attack event is placed to end at it. R3 fix 1. */
    internal const val GUARD_SECONDS = 0.018

    /** Every buzz in every voice snaps to a minor pentatonic over this one pitch, Hz, so the kit buzzes in one key. R3 fix 2. */
    internal const val KEY_HZ = 55.0

    /** The grid unit is the CHOP slice doubled or halved into [UNIT_MIN_SECONDS, UNIT_MAX_SECONDS). R3 fix 3. */
    internal const val UNIT_MIN_SECONDS = 0.040
    internal const val UNIT_MAX_SECONDS = 0.080

    /** Total stumble per hit is round(FAULT * this) grid units, at least one. R3 fix 4. */
    internal const val CAP_UNITS_PER_FAULT = 6.0

    /** The CHOP slice map: 0 is the slowest slice and 1 the fastest, exponential in between. */
    private const val CHOP_SLOW_SECONDS = 0.110
    private const val CHOP_FAST_SECONDS = 0.0016

    /** The CHOP slice for a macro value, seconds. */
    internal fun chopSlice(chop: Double): Double = expMap(chop, CHOP_SLOW_SECONDS, CHOP_FAST_SECONDS)

    /**
     * The inverse of the CHOP slice map: the CHOP value whose slice lasts [seconds], so a kit can ask for a slice of an
     * exact duration (a tempo subdivision). Durations outside the map's reach clamp to 0 or 1.
     */
    fun chopFor(seconds: Float): Float {
        require(seconds.isFinite() && seconds > 0f) { "slice length must be positive and finite: $seconds" }
        return (ln(seconds / CHOP_SLOW_SECONDS) / ln(CHOP_FAST_SECONDS / CHOP_SLOW_SECONDS)).toFloat().coerceIn(0f, 1f)
    }

    /** The grid unit for a CHOP slice: the slice doubled or halved into [UNIT_MIN_SECONDS, UNIT_MAX_SECONDS). */
    internal fun gridUnit(base: Double): Double {
        var g = base
        while (g < UNIT_MIN_SECONDS) g *= 2.0
        while (g >= UNIT_MAX_SECONDS) g /= 2.0
        return g
    }

    private val PENTATONIC = doubleArrayOf(0.0, 3.0, 5.0, 7.0, 10.0, 12.0)

    // ------------------------------------------------------------------ macros and bookkeeping

    /** Every voice takes the same six macros. The hats differ only in the DECAY default (closed short, open long). */
    fun macrosFor(voice: HiccupVoice): List<MacroSpec> = listOf(
        MacroSpec("TUNE", 0.5f),
        MacroSpec("DECAY", when (voice) { HiccupVoice.HAT_CLOSED -> 0.15f; HiccupVoice.HAT_OPEN -> 0.70f; else -> 0.5f }),
        MacroSpec("TOY", 0.35f),
        MacroSpec("FAULT", 0.45f),
        MacroSpec("CHOP", 0.5f),
        MacroSpec("CRUSH", 0.35f),
    )

    fun defaults(voice: HiccupVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    /** [macros] over the defaults: unknown keys dropped, a non-finite value falls back, everything clamped 0..1. */
    fun settled(voice: HiccupVoice, macros: Map<String, Float>): Map<String, Float> {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k) && v.isFinite()) m[k] = v.coerceIn(0f, 1f)
        return m
    }

    /** What the kit files each pad as. BLIP and SQUEAK are percussion. */
    fun drumClassFor(voice: HiccupVoice): DrumClass = when (voice) {
        HiccupVoice.KICK -> DrumClass.KICK
        HiccupVoice.SNARE -> DrumClass.SNARE
        HiccupVoice.CLAP -> DrumClass.CLAP
        HiccupVoice.HAT_CLOSED -> DrumClass.HAT_CLOSED
        HiccupVoice.HAT_OPEN -> DrumClass.HAT_OPEN
        HiccupVoice.TOM -> DrumClass.TOM
        HiccupVoice.BLIP, HiccupVoice.SQUEAK -> DrumClass.PERC
    }

    /** SCRAMBLE near the defaults (there is no preset bank yet), the way SCUFF rolls. */
    fun scramble(voice: HiccupVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = if (near != null) base + near.macros.filterKeys { it in base } else base
        return Dsp.scrambleNear(seed, temperature, random)
    }

    /** The voice's root, Hz, before TUNE. The source sits on it and the short repeats snap to notes over it. "shape" */
    private fun rootHz(voice: HiccupVoice): Double = when (voice) {
        HiccupVoice.KICK -> 49.0
        HiccupVoice.SNARE -> 185.0
        HiccupVoice.CLAP -> 1100.0
        HiccupVoice.HAT_CLOSED, HiccupVoice.HAT_OPEN -> 8000.0
        HiccupVoice.TOM -> 110.0
        HiccupVoice.BLIP -> 880.0
        HiccupVoice.SQUEAK -> 1250.0
    }

    /** Render [voice] at [velocity] (0..1) as take [take] (0 is the plain strike; 1 to 4 are dealt takes). */
    fun render(voice: HiccupVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f, take: Int = 0): Snip =
        Snip(finish(renderPad(voice, macros, velocity, take)), channels = 1, sampleRate = RATE)

    /** The house ending for a pad: the mean and anything under 20 Hz removed, peak-matched, a 4 ms fade. */
    internal fun finish(buf: FloatArray): FloatArray {
        val out = dcBlock(buf)
        Dsp.normalize(out)
        Dsp.fadeTail(out, rate = RATE)
        return out
    }

    /**
     * One pad at the output rate, band-limited and decimated, not yet levelled. [oversample] is the internal rate over
     * the output rate; the engine uses 4. Only a probe passes 8, to see how much a crushed render depends on the rate
     * its staircase was made at.
     */
    internal fun renderPad(voice: HiccupVoice, macros: Map<String, Float>, velocity: Float, take: Int, oversample: Int = Dsp.OVERSAMPLE): FloatArray {
        val fs = RATE * oversample
        val raw = renderRaw(voice, macros, velocity, take, oversample)
        // Ten milliseconds of silence let the band-limit filters settle instead of cutting the last sample hard.
        val padded = FloatArray(raw.size + (0.01 * fs).toInt())
        for (i in raw.indices) padded[i] = raw[i].toFloat()
        Tide.bandLimit(padded, fs)
        val atFour = if (oversample == Dsp.OVERSAMPLE) padded else Resampler.resample(Snip(padded, 1, fs), RATE * Dsp.OVERSAMPLE).samples
        return Dsp.decimate(atFour, RATE)
    }

    /** The reader's output at the internal rate, before the output stage. */
    internal fun renderRaw(voice: HiccupVoice, macros: Map<String, Float>, velocity: Float, take: Int, oversample: Int = Dsp.OVERSAMPLE): DoubleArray {
        val core = if (oversample == Dsp.OVERSAMPLE) CORE else Core(RATE * oversample)
        val m = settled(voice, macros)
        val vel = velocity.coerceIn(0f, 1f).toDouble()
        val tk = takeFor(voice, take)
        val dry = core.source(voice, m, vel, tk)
        val events = script(voice, m, vel, tk, dry.size.toDouble() / (RATE * oversample))
        return core.reread(dry, events, m)
    }

    /** The memory at the engine's internal rate. */
    internal fun source(voice: HiccupVoice, m: Map<String, Float>, vel: Double, tk: Take): DoubleArray = CORE.source(voice, m, vel, tk)

    /** The reader at the engine's internal rate. */
    internal fun reread(dry: DoubleArray, events: List<Event>, m: Map<String, Float>): DoubleArray = CORE.reread(dry, events, m)

    // ------------------------------------------------------------------ takes

    /**
     * One take's four dealt dimensions and its own noise. [snag] is a fraction of the dry length added to every
     * non-attack event position, [slice] is added to every event's length draw before that draw is rounded to the
     * x1/2, x1 or x2 choice (so a take moves the choice of a slice near a boundary), [shrink] is added to the shrink draw, and
     * [strike] (-1..1) moves the source's contact: sweep time, click level and the small per-voice wobbles.
     */
    internal class Take(val snag: Double, val slice: Double, val shrink: Double, val strike: Double, val noise: Dsp.Noise)

    private val SNAG = doubleArrayOf(-0.12, -0.04, 0.04, 0.12)
    private val SLICE = doubleArrayOf(-0.40, -0.15, 0.15, 0.40)
    private val SHRINK = doubleArrayOf(-0.06, -0.02, 0.02, 0.06)
    private val STRIKE = doubleArrayOf(-0.75, -0.25, 0.25, 0.75)
    private val PERM_A = intArrayOf(0, 1, 2, 3)
    private val PERM_B = intArrayOf(2, 0, 3, 1)
    private val PERM_C = intArrayOf(1, 3, 0, 2)
    private val PERM_D = intArrayOf(3, 2, 1, 0)

    /**
     * Take 0 is the plain render. Takes 1 to 4 are dealt on a 4x4 Latin square, as SCUFF deals its takes: on each
     * of the four dimensions the four takes take the four strata in a different order, so no two takes share a
     * stratum on any dimension (the ETCH lesson: independent small wobbles read as one strike). Only the strike
     * dimension carries a jitter inside its stratum. The exist draws of the script never move, so every take has
     * the same events as take 0 at the same FAULT: one drum stumbling four ways.
     */
    internal fun takeFor(voice: HiccupVoice, take: Int): Take {
        val noise = Dsp.Noise(Dsp.seedFor("HICCUP", voice.name, "noise", take))
        if (take <= 0) return Take(0.0, 0.0, 0.0, 0.0, noise)
        val rng = Random(Dsp.seedFor("HICCUP", voice.name, "take", take))
        val k = (take - 1) % 4
        return Take(SNAG[PERM_A[k]], SLICE[PERM_B[k]], SHRINK[PERM_C[k]], STRIKE[PERM_D[k]] + (rng.nextDouble() - 0.5) * 0.3, noise)
    }

    // ------------------------------------------------------------------ small tools (Double, at FS)

    private fun lin(x: Double, lo: Double, hi: Double) = lo + (hi - lo) * x.coerceIn(0.0, 1.0)
    private fun expMap(x: Double, lo: Double, hi: Double) = lo * (hi / lo).pow(x.coerceIn(0.0, 1.0))
    private fun expMap(x: Float, lo: Double, hi: Double) = expMap(x.toDouble(), lo, hi)

    /**
     * The synthesis and the reader at one internal rate [FS]. The engine runs at [FS_DEFAULT]; a test can build one at
     * another rate to compare how much a crushed render depends on the rate its staircase was made at.
     */
    internal class Core(private val FS: Int) {
        private fun frames(seconds: Double) = (seconds * FS).toInt().coerceAtLeast(64)

        /** tanh from one exp: the chip wave runs six times per hat sample and the library tanh is the slow part. */
        private fun fastTanh(x: Double): Double = when {
            x > 20.0 -> 1.0
            x < -20.0 -> -1.0
            else -> 1.0 - 2.0 / (exp(2.0 * x) + 1.0)
        }

        /**
         * TOY quantity 1: envelopes read at a frame clock. 0 is continuous and 1 is 60 steps a second, so pitch and
         * level move in stairs, as on a chip that updates its envelope once a frame.
         */
        private fun stepped(n: Int, toy: Double): DoubleArray {
            val step = toy / 60.0
            return DoubleArray(n) { val t = it.toDouble() / FS; if (step < 1e-5) t else floor(t / step) * step }
        }

        /** TOY quantity 2: a sine pushed toward a square. tanh keeps the harmonic roll-off steep, unlike a hard clip. */
        private inner class Chip(toy: Double) {
            private val k = expMap(toy, 0.05, 9.0)
            private val norm = fastTanh(k)
            fun at(phase: Double) = fastTanh(k * sin(phase)) / norm
        }

        /** TOY quantity 3: noise from a clock. A fast clock is near white and a slow one is console hiss. */
        private fun clockedNoise(n: Int, toy: Double, noise: Dsp.Noise): DoubleArray {
            val clock = expMap(toy, 60000.0, 3500.0)
            val count = (n * clock / FS).toInt() + 2
            val values = DoubleArray(count) { noise.next().toDouble() }
            return DoubleArray(n) { values[min((it * clock / FS).toInt(), count - 1)] }
        }

        /**
         * A 12 dB per octave high-pass from two one-poles in series. The prototype used Butterworth sections; the
         * library has no Double high-pass, and exact equivalence is not needed because the source is shaped by ear.
         */
        private fun highpass(x: DoubleArray, hz: Double): DoubleArray {
            val a = Dsp.FixedOnePole(hz.toFloat(), FS)
            val b = Dsp.FixedOnePole(hz.toFloat(), FS)
            return DoubleArray(x.size) {
                val v = x[it].toFloat()
                val s = v - a.lp(v)
                (s - b.lp(s)).toDouble()
            }
        }

        /** A Butterworth low-pass of the library's TPT state-variable filter, stable up to the Nyquist. */
        private fun lowpass(x: DoubleArray, hz: Double): DoubleArray {
            val buf = FloatArray(x.size) { x[it].toFloat() }
            Dsp.TptSvf(FS).lowPassInPlace(buf, min(hz, FS * 0.45).toFloat(), 1.4142135f)
            return DoubleArray(buf.size) { buf[it].toDouble() }
        }

        private fun bandpass(x: DoubleArray, lo: Double, hi: Double) = lowpass(highpass(x, lo), hi)

        /** The running phase of a constant frequency, radians. */
        private fun phaseOf(hz: DoubleArray): DoubleArray {
            val ph = DoubleArray(hz.size)
            var acc = 0.0
            for (i in hz.indices) { acc += 2.0 * PI * hz[i] / FS; ph[i] = acc }
            return ph
        }

        /**
         * A source never ends on a step: its exponential tail is cut at 1.4 s or ends in a stair, so the last 10 ms
         * fall on a raised cosine. The reader treats the memory as ending in silence.
         */
        private fun fadeEnd(x: DoubleArray): DoubleArray {
            val f = min(x.size / 2, (0.01 * FS).toInt())
            for (i in 0 until f) x[x.size - 1 - i] *= 0.5 - 0.5 * cos(PI * i / f)
            return x
        }

        // ------------------------------------------------------------------ 1. source

        /**
         * One clean hit at the internal rate, not normalized: this is the memory the reader works on. [vel] is 0..1
         * contact hardness. Every random-looking wobble comes from the take's strike stratum, so the plain render
         * (take 0) has none and the dealt takes differ by a stated amount.
         */
        internal fun source(voice: HiccupVoice, m: Map<String, Float>, vel: Double, tk: Take): DoubleArray {
            val tune = 2.0.pow((m.getValue("TUNE") - 0.5) * 2.0)
            val toy = m.getValue("TOY").toDouble()
            val decay = m.getValue("DECAY")
            val hard = lin(vel, 0.35, 1.0)
            val wob = { amt: Double -> 1.0 + amt * tk.strike }
            val y = when (voice) {
                HiccupVoice.KICK -> membrane(decay, tune, toy, hard, tk, wob, rootHz(voice), 5.0)
                HiccupVoice.TOM -> membrane(decay, tune, toy, hard, tk, wob, rootHz(voice), 2.5)
                HiccupVoice.SNARE -> snare(decay, tune, toy, hard, tk, wob)
                HiccupVoice.HAT_CLOSED -> hat(expMap(decay, 0.014, 0.08), tune, toy, hard, tk)
                HiccupVoice.HAT_OPEN -> hat(expMap(decay, 0.012, 0.30), tune, toy, hard, tk)
                HiccupVoice.CLAP -> clap(decay, tune, toy, hard, tk, wob)
                HiccupVoice.BLIP -> blip(decay, tune, toy, hard, tk, wob)
                HiccupVoice.SQUEAK -> squeak(decay, tune, toy, hard, tk, wob)
            }
            return fadeEnd(y)
        }

        /**
         * KICK and TOM share a model: a pitch sweep down onto the root, a chip-wave body, and a short band-passed click.
         * The tom is rooted at 110 Hz and sweeps 2.5x where the kick sweeps 7x. The click lasts a few milliseconds, so
         * it is filtered over its first 30 ms only.
         */
        private fun membrane(decay: Float, tune: Double, toy: Double, hard: Double, tk: Take, wob: (Double) -> Double, root: Double, depth: Double): DoubleArray {
            val tau = expMap(decay, 0.06, 0.42)
            val n = frames(min(MAX_SECONDS, 6.5 * tau))
            val ts = stepped(n, toy)
            val sweepTime = 0.021 * wob(0.08)
            val sweep = depth * hard * wob(0.06)
            val hz = DoubleArray(n) { root * tune * (1.0 + sweep * exp(-ts[it] / sweepTime)) }
            val ph = phaseOf(hz)
            val chip = Chip(toy * 0.5)
            val nc = min(n, (0.03 * FS).toInt())
            val click = bandpass(clockedNoise(nc, toy, tk.noise), 1200.0, 5000.0)
            val clickGain = 0.15 * hard * wob(0.2)
            return DoubleArray(n) { i ->
                val body = chip.at(ph[i]) * exp(-ts[i] / tau)
                if (i < nc) body + click[i] * exp(-(i.toDouble() / FS) / 0.0025) * clickGain else body
            }
        }

        /** A chip tone with an inharmonic second partial under band-passed noise wires. */
        private fun snare(decay: Float, tune: Double, toy: Double, hard: Double, tk: Take, wob: (Double) -> Double): DoubleArray {
            val tau = expMap(decay, 0.05, 0.26)
            val n = frames(min(MAX_SECONDS, 6.5 * tau))
            val ts = stepped(n, toy)
            val hz = DoubleArray(n) { rootHz(HiccupVoice.SNARE) * tune * (1.0 + 0.6 * hard * exp(-ts[it] / 0.012)) }
            val ph = phaseOf(hz)
            val chip = Chip(toy)
            val ratio = 1.78 * wob(0.01)
            val wires = bandpass(clockedNoise(n, toy, tk.noise), 1400.0 * wob(0.05), 9500.0)
            return DoubleArray(n) { i ->
                val tone = (chip.at(ph[i]) + 0.5 * chip.at(ph[i] * ratio)) * exp(-ts[i] / (tau * 0.45))
                0.55 * tone + (0.6 + 0.6 * hard) * wires[i] * exp(-ts[i] / tau)
            }
        }

        private val HAT_RATIOS = doubleArrayOf(1.0, 1.342, 1.2312, 1.6532, 1.9523, 2.1523)

        /**
         * The shared hat: six chip squares at inharmonic ratios, high-passed, over a hiss. The closed hat passes a short
         * [tau] (its DECAY range is capped low) and the open hat a long one. Hardness lifts the metal's high-pass edge.
         */
        private fun hat(tau: Double, tune: Double, toy: Double, hard: Double, tk: Take): DoubleArray {
            val n = frames(min(MAX_SECONDS, 7.0 * tau + 0.01))
            val ts = stepped(n, toy)
            val chip = Chip(0.9)
            val base = 640.0 * tune
            val detune = DoubleArray(6) { 1.0 + 0.004 * tk.strike * (if (it % 2 == 0) 1.0 else -1.0) }
            val step = DoubleArray(6) { 2.0 * PI * base * HAT_RATIOS[it] * detune[it] / FS }
            val ring = DoubleArray(n) { i ->
                var s = 0.0
                for (j in 0 until 6) s += chip.at(step[j] * i)
                s / 6.0
            }
            val metal = highpass(ring, 8500.0 * (0.8 + 0.4 * hard))
            val hiss = highpass(clockedNoise(n, toy * 0.3, tk.noise), 11000.0)
            // The memory is band-limited here, before CRUSH. The square cluster has energy far above the output's
            // band, and a sample-and-hold at 32 kHz folds all of it back in, down to the bottom of the spectrum.
            val bright = lowpass(DoubleArray(n) { 1.0 * metal[it] + 0.35 * hiss[it] }, 18000.0)
            return DoubleArray(n) { bright[it] * exp(-ts[it] / tau) }
        }

        /** Three quick hands and then the room, all band-passed noise. TOY steps only the room's decay. */
        private fun clap(decay: Float, tune: Double, toy: Double, hard: Double, tk: Take, wob: (Double) -> Double): DoubleArray {
            val tau = expMap(decay, 0.05, 0.28)
            val n = frames(min(MAX_SECONDS, 6.0 * tau + 0.05))
            val ts = stepped(n, toy)
            val hands = DoubleArray(3) { it * 0.011 * wob(0.12) }
            val room = 0.034
            val noise = bandpass(clockedNoise(n, toy, tk.noise), 600.0 * tune * (0.8 + 0.4 * hard), 5500.0 * tune)
            return DoubleArray(n) { i ->
                val t = i.toDouble() / FS
                var env = 0.0
                for (k in 0 until 3) if (t >= hands[k]) env += exp(-(t - hands[k]) / 0.006) * (0.7 + 0.1 * k)
                if (t >= room) env += exp(-max(ts[i] - room, 0.0) / tau)
                noise[i] * env
            }
        }

        /** A pitched blip that falls 12 to 24 semitones in whole-semitone steps, always a little stepped. */
        private fun blip(decay: Float, tune: Double, toy: Double, hard: Double, tk: Take, wob: (Double) -> Double): DoubleArray {
            val tau = expMap(decay, 0.035, 0.30)
            val n = frames(min(MAX_SECONDS, 6.0 * tau))
            val ts = stepped(n, max(toy, 0.15))
            val sweepTime = tau * 0.5 * wob(0.08)
            val hz = DoubleArray(n) {
                val semis = -(12.0 + 12.0 * hard) * (1.0 - exp(-ts[it] / sweepTime))
                rootHz(HiccupVoice.BLIP) * tune * 2.0.pow((if (toy > 0.02) Math.rint(semis) else semis) / 12.0)
            }
            val ph = phaseOf(hz)
            val chip = Chip(0.3 + 0.7 * toy)
            return DoubleArray(n) { chip.at(ph[it]) * exp(-ts[it] / tau) }
        }

        /** A rising and falling reed with a slow vibrato under a half-sine envelope: a squeezed toy. */
        private fun squeak(decay: Float, tune: Double, toy: Double, hard: Double, tk: Take, wob: (Double) -> Double): DoubleArray {
            val dur = expMap(decay, 0.07, 0.32)
            val n = frames(dur + 0.03)
            val ts = stepped(n, toy)
            val hz = DoubleArray(n) {
                val x = (ts[it] / dur).coerceIn(0.0, 1.0)
                val t = it.toDouble() / FS
                rootHz(HiccupVoice.SQUEAK) * tune * (0.75 + (0.9 + 0.4 * hard) * sin(PI * x).pow(0.7)) * (1.0 + 0.03 * sin(2.0 * PI * 37.0 * wob(0.1) * t))
            }
            val ph = phaseOf(hz)
            val reedA = Chip(0.25 + 0.6 * toy)
            val reedB = Chip(0.2)
            return DoubleArray(n) { i ->
                val env = sin(PI * (i.toDouble() / FS / dur).coerceIn(0.0, 1.0)).pow(0.6)
                (reedA.at(ph[i]) + 0.35 * reedB.at(2.0 * ph[i])) * env
            }
        }

        // ------------------------------------------------------------------ 3. reader

        /**
         * CRUSH is loss per re-read. Generation 0 is the hit as played and keeps a quarter of the loss, so the attack
         * stays punchy; later generations approach the full amount, so the damage collects on the repeats.
         */
        private fun loss(crush: Double, generation: Int): Double = crush * (0.25 + 0.75 * (1.0 - exp(-generation / 2.5)))

        /** Writes read segments of the memory into [out]. Linear interpolation, one position map per segment. */
        private inner class Reader(val dry: DoubleArray, val peak: Double, val crush: Double, val out: DoubleArray) {
            var w = 0
            val full get() = w >= out.size
            private val edge = (EDGE_SECONDS * FS).toInt()

            private fun sample(idx: Double): Double {
                if (idx < 0.0 || idx > dry.size - 1) return 0.0
                val i = idx.toInt()
                val f = idx - i
                return if (f == 0.0 || i + 1 >= dry.size) dry[i] else dry[i] + (dry[i + 1] - dry[i]) * f
            }

            /**
             * Read [n] output frames starting at memory position [start]. With [reverse] the slice is read backward.
             * [speed0] to [speed1] is the read speed across the segment. A loss above zero holds the read position
             * (a sample-and-hold at the internal rate) and rounds to fewer levels, mid-tread so there is no DC and
             * quiet tails gate shut. At zero loss both are bypassed exactly.
             */
            fun segment(
                start: Double, n: Int, generation: Int, reverse: Boolean = false, speed0: Double = 1.0, speed1: Double = 1.0,
                gain: Double = 1.0, edgeIn: Boolean = false, edgeOut: Boolean = false,
            ) {
                val c = loss(crush, generation)
                val len = min(n, out.size - w)
                if (len <= 0) return
                val crushed = c > 1e-6
                val hold = if (crushed) FS / expMap(c, 44100.0, 1100.0) else 1.0
                val levels = if (crushed) expMap(c, 4096.0, 5.0) else 1.0
                val straight = speed0 == 1.0 && speed1 == 1.0
                for (k in 0 until len) {
                    val kk = if (crushed) floor(k / hold) * hold else k.toDouble()
                    val travel = if (straight) kk else speed0 * kk + (speed1 - speed0) * kk * kk / (2.0 * n)
                    var y = sample(start + if (reverse) n - 1 - travel else travel) * gain
                    if (crushed) y = Math.rint(y / peak * levels) / levels * peak
                    out[w + k] = y
                }
                val e = min(n / 2, edge)
                if (e > 0) {
                    if (edgeIn) for (k in 0 until min(e, len)) out[w + k] *= 0.5 - 0.5 * cos(PI * k / e)
                    if (edgeOut && len == n) for (k in 0 until e) out[w + len - 1 - k] *= 0.5 - 0.5 * cos(PI * k / e)
                }
                w += len
            }
        }

        /**
         * Read the [dry] memory through [events]. The hit plays through to each snag, the slice is read once as played
         * and then repeated, and the read then continues from the end of the slice, so a fault lengthens the hit. The
         * output is in the memory's own scale; the quantizer sees it scaled to a unit peak. With no events and no CRUSH
         * the output is the memory itself, bit for bit. The length is capped at [MAX_SECONDS].
         */
        internal fun reread(dry: DoubleArray, events: List<Event>, m: Map<String, Float>): DoubleArray {
            var peak = 0.0
            for (v in dry) peak = max(peak, abs(v))
            if (peak < 1e-12) peak = 1.0
            val r = Reader(dry, peak, m.getValue("CRUSH").toDouble(), DoubleArray((MAX_SECONDS * FS).toInt()))
            var cursor = 0
            for (e in events) {
                if (r.full) break
                val posS = (e.pos * FS).roundToInt().coerceIn(0, dry.size - 1)
                val lenS = max(8, (e.length * FS).roundToInt()).coerceAtMost(max(1, dry.size - posS))
                var playedThrough = false
                if (posS > cursor) {
                    r.segment(cursor.toDouble(), posS - cursor, 0, edgeIn = cursor > 0)
                    playedThrough = true
                }
                r.segment(posS.toDouble(), lenS, 0, edgeIn = !(playedThrough || r.w == 0), edgeOut = true)
                var elapsed = 0.0
                var elapsedS = 0
                val span = 1.5 * e.budget
                for ((i, ell) in repeatLengths(e.length, e.shrink, e.budget).withIndex()) {
                    if (r.full) break
                    val k = i + 1
                    // Rounded on the running total, so a long run of repeats does not drift off the budget.
                    val endS = ((elapsed + ell) * FS).roundToInt()
                    val ellS = max(1, endS - elapsedS)
                    val dive0 = if (e.dive) max(0.45, 1.0 - 0.55 * elapsed / e.budget) else 1.0
                    val dive1 = if (e.dive) max(0.45, 1.0 - 0.55 * (elapsed + ell) / e.budget) else 1.0
                    r.segment(
                        posS.toDouble(), ellS, k, reverse = e.reverse && k % 2 == 1, speed0 = dive0, speed1 = dive1,
                        gain = exp(-elapsed / span), edgeIn = true, edgeOut = true,
                    )
                    elapsed += ell
                    elapsedS += ellS
                }
                cursor = posS + lenS
            }
            if (!r.full && cursor < dry.size) r.segment(cursor.toDouble(), dry.size - cursor, 0, edgeIn = cursor > 0)
            return r.out.copyOf(r.w)
        }

    }

    // ------------------------------------------------------------------ 2. fault script

    /** One fault event: from [pos] a slice of [length] seconds is read once, then [repeats] more times, filling [budget] seconds exactly (see [repeatLengths]). */
    internal data class Event(
        val slot: Int,
        val pos: Double,
        val length: Double,
        val repeats: Int,
        val shrink: Double,
        val reverse: Boolean,
        val dive: Boolean,
        val budget: Double,
    )

    private const val EXIST = 0
    private const val POS = 1
    private const val LEN = 2
    private const val SHRINK_DRAW = 3
    private const val REV = 4
    private const val DIVE = 5

    /**
     * [SLOTS] rows of six draws in 0..1 (exist, position, length, shrink, reverse, dive), fixed per voice. They do
     * not depend on a macro or a take: macros scale them and takes move the geometry, so the pattern never reshuffles.
     */
    private fun draws(voice: HiccupVoice): Array<DoubleArray> {
        val noise = Dsp.Noise(Dsp.seedFor("HICCUP", voice.name, "script"))
        return Array(SLOTS) { DoubleArray(6) { min(0.9999, abs(noise.next().toDouble())) } }
    }

    /**
     * Below about 12 ms a repeat is heard as a pitch, so make it one: the repeat's frequency snaps to the nearest
     * minor-pentatonic note over [rootHz] (the script always passes [KEY_HZ]). Leaving that pitch to chance would put the buzz out of tune with the kit.
     */
    private fun snapToPitch(lengthS: Double, rootHz: Double): Double {
        if (lengthS >= SNAP_BELOW_SECONDS) return lengthS
        val semis = 12.0 * log2((1.0 / lengthS) / rootHz)
        val octave = floor(semis / 12.0)
        val within = semis - 12.0 * octave
        val nearest = PENTATONIC.minByOrNull { abs(it - within) } ?: 0.0
        return 1.0 / (rootHz * 2.0.pow((12.0 * octave + nearest) / 12.0))
    }

    private const val MIN_LEN_SECONDS = 0.001

    /**
     * The lengths of the repeats that fill [budget] seconds: each is [length] times [shrink] to the power of its index,
     * never under [MIN_REPEAT_SECONDS], and the last is cut to what is left. The reader and the script both use this,
     * so [Event.repeats] is what the reader plays.
     */
    internal fun repeatLengths(length: Double, shrink: Double, budget: Double): DoubleArray {
        val out = ArrayList<Double>()
        var elapsed = 0.0
        var k = 0
        while (budget - elapsed > MIN_REPEAT_SECONDS) {
            k++
            val ell = min(max(MIN_REPEAT_SECONDS, length * shrink.pow(k)), budget - elapsed)
            out.add(ell)
            elapsed += ell
        }
        return out.toDoubleArray()
    }

    /** The x1/2, x1 or x2 choice (as an exponent of two) a length draw makes, [offset] being the take's addition to the draw. */
    private fun sliceClass(uLen: Double, offset: Double): Int = Math.rint(((uLen + offset).coerceIn(0.0, 0.9999) - 0.5) * 2.0).toInt()

    /**
     * The events for one pad, sorted by position. A slot plays when FAULT (scaled by velocity, so soft hits stumble
     * less) passes its threshold. The thresholds rise with the slot's rank by its exist draw, so a higher FAULT only
     * adds slots.
     *
     * The groove fixes (spec, "As built: R3 groove fixes"): an at-attack event sits so that it ends at [GUARD_SECONDS]
     * and never eats the attack; every buzz snaps to one key ([KEY_HZ]); a slice is x1/2, x1 or x2 of the CHOP slice, an
     * event's budget is a whole number of grid units [gridUnit] and a non-attack position is a multiple of half a unit;
     * and the whole hit may stumble for round(fe * 6) units at most.
     *
     * Overlaps and the unit cap are settled in rank order on the take-neutral geometry: a slot that would read memory an
     * earlier-ranked slot already claimed is skipped, and a slot whose smallest cost (its slice in whole units, which
     * does not depend on FAULT) no longer fits the cap ends the admission. Settling by rank keeps the slot set
     * monotone in FAULT, and settling before the take's offsets keeps it identical across takes. The units are then dealt in rank
     * order, each slot getting what it wants (at least one) from what the cap leaves after the slots already dealt and the
     * smallest cost of the slots to come. The budget is that grant, or the take's slice in whole units if that is more. The take
     * moves the survivors, pushing a later one past an earlier one's slice if the offsets would cross them, and never drops one.
     */
    internal fun script(voice: HiccupVoice, m: Map<String, Float>, velocity: Double, tk: Take, drySeconds: Double): List<Event> {
        val d = draws(voice)
        val fe = m.getValue("FAULT") * lin(velocity, 0.55, 1.0)
        val base = chopSlice(m.getValue("CHOP").toDouble())
        val g = gridUnit(base)
        val half = g / 2.0
        val cap = max(1, Math.rint(fe * CAP_UNITS_PER_FAULT).toInt())
        val order = (0 until SLOTS).sortedBy { d[it][EXIST] }

        class Claim(val slot: Int, val attack: Boolean, val pos: Double, val length: Double, val minUnits: Int, var grant: Int = 1)
        val claims = ArrayList<Claim>()
        var reserved = 0
        for ((rank, i) in order.withIndex()) {
            val u = d[i]
            if (fe <= (rank + u[EXIST]) / SLOTS) break
            val attack = u[POS] < 0.34
            val length = snapToPitch(base * 2.0.pow(sliceClass(u[LEN], 0.0)), KEY_HZ)
            val pos = if (attack) max(0.0, GUARD_SECONDS - length)
            else max(half, Math.rint((u[POS] - 0.34) / 0.66 * 0.55 * drySeconds / half) * half)
            if (pos + length >= drySeconds) continue
            if (claims.any { pos < it.pos + it.length && it.pos < pos + length }) continue
            val minUnits = max(1, ceil(length / g - 1e-9).toInt())
            if (reserved > 0 && reserved + minUnits > cap) break
            reserved += minUnits
            claims.add(Claim(i, attack, pos, length, minUnits))
        }
        // Deal the units: each slot in rank order gets what it wants (at least one), from what the cap leaves after the
        // slots already dealt and the smallest cost of the slots still to come.
        var laterReserved = reserved
        var spent = 0
        for (c in claims) {
            laterReserved -= c.minUnits
            val want = lin(fe.toDouble(), 0.05, 0.26) * (0.6 + 0.8 * d[c.slot][LEN])
            val avail = max(1, cap - spent - laterReserved)
            c.grant = max(1, min(Math.rint(want / g).toInt(), avail))
            spent += max(c.grant, c.minUnits)
        }

        val events = ArrayList<Event>()
        var cursor = 0.0
        for (c in claims.sortedBy { it.pos }) {
            val u = d[c.slot]
            var length = snapToPitch(base * 2.0.pow(sliceClass(u[LEN], tk.slice)), KEY_HZ)
            var pos = if (c.attack) max(0.0, GUARD_SECONDS - length)
            else max(half, Math.rint((c.pos + tk.snag * drySeconds) / half) * half)
            if (pos < cursor) pos = ceil(cursor / half - 1e-9) * half
            if (pos + length >= drySeconds) length = c.length
            var tries = 0
            while (pos + length >= drySeconds && length > 2 * MIN_LEN_SECONDS && tries++ < 8) length = snapToPitch(length / 2.0, KEY_HZ)
            if (pos + length > drySeconds) pos = max(0.0, drySeconds - length)
            cursor = pos + length
            val shrinkDraw = (u[SHRINK_DRAW] + tk.shrink).coerceIn(0.0, 1.0)
            val shrink = if (shrinkDraw > 0.5) 1.0 - 0.44 * (shrinkDraw - 0.5) * fe else 1.0
            val budget = max(c.grant * g, ceil(length / g - 1e-9) * g)
            events.add(Event(
                slot = c.slot,
                pos = pos,
                length = length,
                repeats = repeatLengths(length, shrink, budget).size,
                shrink = shrink,
                reverse = u[REV] < 0.35 * fe,
                dive = u[DIVE] < 0.4 * fe,
                budget = budget,
            ))
        }
        return events
    }

    /** Subtract the mean, then a one-pole high-pass at 20 Hz: the house order. */
    internal fun dcBlock(x: FloatArray): FloatArray {
        var mean = 0.0
        for (v in x) mean += v
        mean /= x.size.coerceAtLeast(1)
        val rc = 1.0 / (2 * PI * 20.0)
        val alpha = rc / (rc + 1.0 / RATE)
        val out = FloatArray(x.size)
        var px = 0.0; var py = 0.0
        for (i in x.indices) {
            val v = x[i] - mean
            py = alpha * (py + v - px)
            px = v
            out[i] = py.toFloat()
        }
        return out
    }
}
