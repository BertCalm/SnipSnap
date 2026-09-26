package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * TIDE — the West Coast engine (docs/SYNTH_ROADMAP.md, S9).
 *
 * RESIN cuts harmonics out of a rich wave; TIDE builds them into a plain
 * one. A sine is phase-modulated (WARP), bent through a wavefolder (FOLD),
 * and closed by a low-pass gate: one control that drives a VCA and a
 * low-pass together and lets go slowly, so brightness and level fall at
 * once and the tail slows as it fades. That coupling is the "bongo".
 *
 * Phase modulation, never frequency modulation: the carrier's frequency is
 * the note at every WARP, so TUNE, SPREAD and pitch detection stay honest.
 * WANDER nudges timbre and decay from a seeded random line and never
 * touches pitch. One-shots onto pads, like every other engine.
 *
 * One pitch movement is the gate's own: its low-pass closes through the
 * fundamental, and a filter whose cutoff moves past a partial shifts that
 * partial's phase as it goes, so a note sags a little mid-decay and comes
 * back once the gate has closed below it, the way a struck drum's pitch
 * falls. [cutoffAt] keeps it late and quiet; [TideTest] bounds it.
 */
enum class TideVoice { BONGO, DRIP, GONG, FLARE }

object Tide {

    const val TUNE_SEMITONES = 24

    /** The note TUNE 0 plays, MIDI: C3, C5, C3, C2. */
    fun rootMidi(voice: TideVoice): Int = when (voice) {
        TideVoice.BONGO -> 48
        TideVoice.DRIP -> 72
        TideVoice.GONG -> 48
        TideVoice.FLARE -> 36
    }

    /** RATIO's choices where it is a macro: whole numbers keep FLARE a note, the rest make GONG a bell. */
    val HARMONIC_RATIOS = floatArrayOf(1f, 2f, 3f, 4f)
    val INHARMONIC_RATIOS = floatArrayOf(1.4f, 2.7f, 3.5f, 4.2f, 5.8f)

    /**
     * Folder drive at FOLD 1, strike brightness: 1 is a clean sine. Was 6;
     * 8.4 since the edge audition (docs/SYNTH_ROADMAP.md, S9, "Revision —
     * the edge"), which asked for 40% more reach from FOLD and WARP both.
     */
    const val MAX_DRIVE = 8.4f

    /**
     * No note outlasts this, however far WANDER stretches DECAY: a pad is a
     * one-shot. The struck voices stop at 2 s; GONG and FLARE, which can
     * hold, at 4.
     */
    fun maxSecondsFor(voice: TideVoice): Float = when (voice) {
        TideVoice.BONGO, TideVoice.DRIP -> 2f
        TideVoice.GONG, TideVoice.FLARE -> 4f
    }

    /**
     * GLOW 1 keeps this much of the fold's depth once the gate has closed.
     * Measured before GLOW existed: with the fold following the gate all
     * the way down, WOOD BONGO had 13 harmonics within 40 dB of its
     * loudest in its first 40 ms and 3 by 120-280 ms; held at full depth,
     * 5. The fold was darkening a tail the gate was already darkening.
     */
    private const val GLOW_FOLD_FLOOR = 0.6f

    /**
     * GLOW 1 drives the gate's filter from `c^(1 - this)` instead of `c`,
     * so the filter closes behind the level rather than with it: at c 0.1
     * (the level already −26 dB) it is still `0.1^0.25 ≈ 0.56` open. The
     * filter was the biggest remover of all: held open, WOOD BONGO kept 6
     * harmonics at 120-280 ms where it had 3, SNARL FLARE 16 at 280-600 ms
     * where it had 5.
     */
    private const val GLOW_FILTER_LAG = 0.75f

    /** WARP 1's phase-modulation index. Was 3; 4.2 since the edge audition, as [MAX_DRIVE]. */
    const val MAX_INDEX = 4.2f

    // The edge: four movements inside the note, chosen at audition
    // ("g FURTHER", every lever at a moderate setting) to push TIDE from
    // tidy toward strange without losing the note. None of them is a
    // knob; they are how TIDE sounds. None of them moves the pitch, and
    // none of them touches a clean note: SWEEP and CROSS work through
    // WARP's index and vanish at WARP 0, WOBBLE's depth and index moves
    // scale with FOLD and WARP, and TILT and WOBBLE's bias fade in over
    // the bottom of FOLD ([EDGE_FOLD_IN]).

    /**
     * SWEEP: the modulator starts this much above its ratio (1 + 1.2 =
     * 2.2 times) and dives onto it with time constant [SWEEP_SECONDS], so
     * WARP's sidebands fall into place in the strike: a zap.
     */
    private const val SWEEP_DEPTH = 1.2f
    private const val SWEEP_SECONDS = 0.02f

    /**
     * CROSS: the folded output, fed back into the modulator's phase at
     * this many radians per unit. The fold and WARP stop being a chain and
     * start to argue, which roughens the growl.
     */
    internal const val CROSS = 0.75f

    /**
     * TILT: the fold's bias leans by up to this many radians, positive at
     * the strike and negative as the gate closes, so the even harmonics
     * turn over across the note.
     */
    private const val TILT = 0.405f

    /**
     * WOBBLE: three random lines stepped at [WOBBLE_HZ], smoothed over
     * [WOBBLE_SMOOTH_SECONDS]. They jitter the fold's depth and WARP's
     * index by up to ±[WOBBLE_DEPTH] and the fold's bias by up to
     * ±[WOBBLE_BIAS] radians, so a held note is never quite still. Seeded
     * from the recipe, so a pad plays the same way every time, and from
     * the take only when WANDER is up.
     */
    private const val WOBBLE_HZ = 11f
    private const val WOBBLE_SMOOTH_SECONDS = 0.006f
    private const val WOBBLE_DEPTH = 0.225f
    private const val WOBBLE_BIAS = 0.27f

    /**
     * CROSS's loop gain ceiling, `CROSS · index · drive`. Feeding a fold
     * back into its own modulator is clean under about 1, rough to about
     * 1.6 and noise past it, as feedback FM is. Mapped on a steady fold at
     * C3: index 1 and drive 6 kept the energy between harmonics 79 dB
     * down at CROSS 0.1, 30 at 0.4; full FOLD and full WARP (index 4.2,
     * drive 8.4) at CROSS 0.75 had none at all, 0.6 dB. 1.5 is the rough
     * side of the line on purpose, the grit the audition chose: SNARL
     * FLARE's strike sits right at it, and a full corner held to it keeps
     * its harmonics 25-40 dB clear, a snarl rather than a hiss.
     */
    private const val CROSS_LOOP = 1.5f

    /** TILT and WOBBLE's bias reach full strength at this FOLD, and are silent at FOLD 0: a clean note stays a sine. */
    private const val EDGE_FOLD_IN = 0.1f

    // The oomph, chosen at the character audition ("g OOMPH", THUMP and
    // BODY; DRIVE, CLICK and PUNCH were heard and left out). Built in,
    // like the edge: GONG and FLARE have no room for an eighth macro.

    /**
     * THUMP: the strike starts this many semitones sharp and falls onto
     * the note with time constant [THUMP_SECONDS], carrier and modulator
     * together, the way a drum head's pitch drops as it is hit: a knock.
     * Gone within 20 ms (13 cents left there), before anything reads the pitch.
     */
    private const val THUMP_SEMITONES = 7f
    private const val THUMP_SECONDS = 0.005f

    /**
     * BODY: a clean sine on the carrier's phase, this loud against the
     * gate's output, under the same VCA. The fold spreads a bright voice's
     * energy up the spectrum and leaves the note itself thin; measured
     * over the first 150 ms, SNARL FLARE's fundamental sat 28.3 dB under
     * the whole and FOLD BASS's 17.7; with BODY, 6.6 and 5.6. The struck
     * voices, whose fundamental already carried them, move under 1 dB.
     *
     * A fold and a RATIO above 1 can turn the note's own fundamental
     * upside down, and a sine added blind then cancels it: REED STAB
     * (RATIO 3) lost 2 dB of note and HOLLOW HORN 5. So BODY takes the
     * note's polarity, once per note: the sign of the whole note's
     * correlation with BODY's own sine, so it always adds to the note.
     * Once, not tracked: a running read of a fundamental 28 dB under the
     * fold (SNARL FLARE) wavers, and a BODY that follows it wavers too.
     */
    private const val BODY = 0.5f

    /**
     * How far past the note the brightest corner may reach, Hz. The fold's
     * drive times the phase modulation's bandwidth sets how high the
     * harmonics go, roughly `drive · (1 + index · ratio) · note`; where
     * full FOLD and full WARP would put that past this, both ease off
     * together ([reachAt]). Measured on a steady full-corner fold,
     * [bandLimit]ed at 4x: at 25.1 kHz (where the old maxima, drive 6 and
     * index 3, put DRIP's C6) DRIP's C7 and FLARE's C4 at RATIO 4 sat
     * 44.3-44.5 dB clean; at 22 kHz the worst, DRIP's C7, 45.7.
     */
    const val REACH_LIMIT_HZ = 22_000f

    /**
     * The fraction of [MAX_DRIVE] and [MAX_INDEX]'s travel a note at [hz]
     * can use with the modulator at [ratio]: 1 where the brightest corner
     * stays under [REACH_LIMIT_HZ], less above, solving
     * `(1 + (MAX_DRIVE − 1)·r)(1 + MAX_INDEX·ratio·r)·hz = REACH_LIMIT_HZ`.
     * Low notes get all of the edge's extra reach; DRIP's top octave and
     * FLARE's high RATIOs ease back to about what the old maxima gave them.
     */
    internal fun reachAt(hz: Float, ratio: Float = 1f): Float {
        val a = (MAX_DRIVE - 1f) * MAX_INDEX * ratio
        val b = (MAX_DRIVE - 1f) + MAX_INDEX * ratio
        val c = 1f - REACH_LIMIT_HZ / hz
        return ((-b + kotlin.math.sqrt(b * b - 4f * a * c)) / (2f * a)).coerceIn(0f, 1f)
    }

    /** How far WANDER can push fold, decay and WARP per note, as a fraction. */
    const val WANDER_REACH = 0.25f

    private const val ATTACK_SECONDS = 0.002f

    /** The gate's release slows by this much as it closes: `τ(c) = τ₀·(1 + SLOW·(1 − c))`. */
    private const val SLOW = 1.5f

    /** Seconds per τ₀ from the top of the gate to −60 dB, given [SLOW] and `gain = c^GAIN_CURVE`. */
    private val SECONDS_PER_TAU: Float = run {
        val cEnd = 0.001.pow(1.0 / GAIN_CURVE)
        ((1 + SLOW) * kotlin.math.ln(1.0 / cEnd) - SLOW * (1 - cEnd)).toFloat()
    }

    private const val GAIN_CURVE = 1.3

    /**
     * The gate's cutoff, closed, as a multiple of the note: under the
     * fundamental, so a closed gate is silence, not a dull note.
     */
    private const val CLOSED_RATIO = 0.5f

    /** The gate's cutoff, open: 64 times the note, never under 6 kHz, never over 18 kHz. */
    private const val OPEN_RATIO = 64f
    private const val OPEN_MIN_HZ = 6_000f
    private const val OPEN_MAX_HZ = 18_000f

    /**
     * The gate's cutoff at control [c], for a note at [hz]: exponential from
     * [CLOSED_RATIO] times the note to its open ceiling. Fully key-tracked,
     * so the gate passes the fundamental at the same depth on every note,
     * late and quiet (c ≈ 0.14 at C3, where the level is already −22 dB),
     * and never early: an absolute floor closed through a high note's
     * fundamental within its first 60 ms (DRIP C5 read −22 cents there).
     */
    internal fun cutoffAt(c: Float, hz: Float): Float {
        val closed = hz * CLOSED_RATIO
        val open = (hz * OPEN_RATIO).coerceIn(OPEN_MIN_HZ, OPEN_MAX_HZ)
        return closed * exp(kotlin.math.ln(open / closed) * c)
    }

    /** DRIP's pitch starts this far under the note and lands on it within [CHIRP_SECONDS]. */
    private const val CHIRP_DEPTH = 0.35f
    private const val CHIRP_SECONDS = 0.015f

    /** Per-voice nudge on top of [Dsp.MELODIC_LOUDNESS_TARGET], zeroed out awaiting the audition gate. */
    private val LOUDNESS_OFFSET: Map<TideVoice, Float> = TideVoice.entries.associateWith { 0f }

    fun macrosFor(voice: TideVoice): List<MacroSpec> = when (voice) {
        TideVoice.BONGO -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("FOLD", 0.3f), MacroSpec("WARP", 0.2f),
            MacroSpec("GLOW", 0.3f), MacroSpec("DECAY", 0.35f), MacroSpec("WANDER", 0.3f),
        )
        TideVoice.DRIP -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("FOLD", 0.2f), MacroSpec("WARP", 0.3f),
            MacroSpec("GLOW", 0.3f), MacroSpec("DECAY", 0.3f), MacroSpec("WANDER", 0.3f),
        )
        TideVoice.GONG -> listOf(
            MacroSpec("TUNE", 0.4f), MacroSpec("FOLD", 0.15f), MacroSpec("WARP", 0.45f),
            MacroSpec("RATIO", 0.5f), MacroSpec("GLOW", 0.6f), MacroSpec("DECAY", 0.6f), MacroSpec("WANDER", 0.25f),
        )
        TideVoice.FLARE -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("FOLD", 0.6f), MacroSpec("WARP", 0.3f),
            MacroSpec("RATIO", 0f), MacroSpec("GLOW", 0.7f), MacroSpec("DECAY", 0.5f), MacroSpec("WANDER", 0.2f),
        )
    }

    fun defaults(voice: TideVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    /** SCRAMBLE near a preset; see [Thump.scramble] (docs/SYNTH_UPGRADE.md, U2). */
    fun scramble(voice: TideVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = when {
            near != null -> base + near.macros.filterKeys { it in base }
            temperature >= 1f -> base
            else -> base + TidePresets.forVoice(voice).random(random).macros.filterKeys { it in base }
        }
        return Dsp.scrambleNear(seed, temperature, random)
    }

    /** The snapped MIDI note TUNE lands on. */
    fun midiFor(voice: TideVoice, tune: Float): Int =
        rootMidi(voice) + Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)

    fun frequencyFor(voice: TideVoice, tune: Float): Float =
        440f * 2f.pow((midiFor(voice, tune) - 69) / 12f)

    /** The modulator's ratio to the carrier: fixed at 1 on BONGO and DRIP, RATIO's snapped choice on GONG and FLARE. */
    internal fun ratioFor(voice: TideVoice, ratio: Float): Float {
        val table = when (voice) {
            TideVoice.BONGO, TideVoice.DRIP -> return 1f
            TideVoice.GONG -> INHARMONIC_RATIOS
            TideVoice.FLARE -> HARMONIC_RATIOS
        }
        return table[Math.round(ratio.coerceIn(0f, 1f) * (table.size - 1))]
    }

    /** DECAY as seconds from the strike to −60 dB, hold included. */
    internal fun lengthFor(voice: TideVoice, decay: Float): Float {
        val (lo, hi) = when (voice) {
            TideVoice.BONGO -> 0.12f to 1.5f
            TideVoice.DRIP -> 0.06f to 0.8f
            TideVoice.GONG -> 0.4f to 4f
            TideVoice.FLARE -> 0.25f to 4f
        }
        return Dsp.expMap(decay, lo, hi)
    }

    /**
     * How much of the note the gate holds fully open before it starts to
     * close, as a fraction of [lengthFor]: DECAY's top half on GONG and
     * FLARE, up to 60% at DECAY 1, so a long setting is a held note that
     * then rings out. The struck voices never hold: a hand drum is struck.
     */
    internal fun holdFractionFor(voice: TideVoice, decay: Float): Float = when (voice) {
        TideVoice.BONGO, TideVoice.DRIP -> 0f
        TideVoice.GONG, TideVoice.FLARE -> 0.6f * ((decay - 0.5f) / 0.5f).coerceIn(0f, 1f)
    }

    /** A small resonant pop on BONGO; the others close gently. SVF damping, 2 = no resonance. */
    private fun dampingFor(voice: TideVoice): Float = if (voice == TideVoice.BONGO) 1.2f else 1.4f

    /** A folded sine's even-harmonic tilt, radians at full fold, per voice. */
    private fun biasFor(voice: TideVoice): Float = when (voice) {
        TideVoice.BONGO -> 0.25f
        TideVoice.DRIP -> 0.1f
        TideVoice.GONG -> 0.4f
        TideVoice.FLARE -> 0.3f
    }

    /** FLARE's fold closes this much slower than its gate, so the harmonics bloom and linger. */
    private fun foldLag(voice: TideVoice): Float = if (voice == TideVoice.FLARE) 0.5f else 1f

    /**
     * The folder: a triangle core (asin of the input) through a sine shaper
     * of [drive]. At drive 1 a sine passes untouched; past it, each unit of
     * drive adds a fold. [bias] tilts the core before the shaper.
     */
    internal fun fold(x: Float, drive: Float, bias: Float): Float {
        val core = asin(x.coerceIn(-1f, 1f))
        return sin(drive * core + bias)
    }

    /**
     * The low-pass gate's control, 0..1, one sample per [next]: a 2 ms rise,
     * held fully open for [hold] of the note, then a release that slows as
     * it falls, reaching −60 dB (through `c^GAIN_CURVE`) [length] seconds
     * after the strike.
     */
    internal class Gate(private val length: Float, private val rate: Int, hold: Float = 0f) {
        private val holdSeconds = length * hold.coerceIn(0f, 0.9f)
        private val tau0 = (length - holdSeconds) / SECONDS_PER_TAU
        private var c = 0f
        private var i = 0
        fun next(): Float {
            val t = i++.toFloat() / rate
            c = if (t < ATTACK_SECONDS) {
                t / ATTACK_SECONDS
            } else if (t < ATTACK_SECONDS + holdSeconds) {
                1f
            } else {
                val tau = tau0 * (1f + SLOW * (1f - c))
                (c - c / (tau * rate)).coerceAtLeast(0f)
            }
            return c
        }
    }

    /** A per-note seed from what the note is: the same recipe and take always wander the same way. */
    internal fun seedFor(voice: TideVoice, macros: Map<String, Float>, take: Int): Int =
        Dsp.seedFor("TIDE", voice.name, macros.toSortedMap().entries.joinToString(","), take)

    /**
     * The raw synth loop at whatever [rate] the caller wants — split out of
     * [render] so the oversampled dispatch can be tested against a
     * native-rate render (VelvetTest has the reasoning).
     */
    internal fun synthesize(voice: TideVoice, macros: Map<String, Float>, rate: Int, take: Int = 0): FloatArray {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)

        val wander = m.getValue("WANDER")
        val random = Random(seedFor(voice, m, take))
        fun nudge(): Float = 1f + WANDER_REACH * wander * (random.nextFloat() * 2f - 1f)

        val hz = frequencyFor(voice, m.getValue("TUNE"))
        val ratio = ratioFor(voice, m["RATIO"] ?: 0f)
        val foldAmount = (m.getValue("FOLD") * nudge()).coerceIn(0f, 1f)
        // Squared, so the bottom of the knob is fine control and WARP 0 is no modulation at all.
        val warp = m.getValue("WARP")
        val reach = reachAt(hz, ratio)
        val index = MAX_INDEX * reach * warp * warp * nudge()
        val edgeIn = (foldAmount / EDGE_FOLD_IN).coerceAtMost(1f)
        val length = (lengthFor(voice, m.getValue("DECAY")) * nudge()).coerceAtMost(maxSecondsFor(voice))
        val hold = holdFractionFor(voice, m.getValue("DECAY"))
        val glow = m.getValue("GLOW")
        val foldFloor = GLOW_FOLD_FLOOR * glow
        val filterCurve = 1f - GLOW_FILTER_LAG * glow
        val bias = biasFor(voice)
        val damping = dampingFor(voice)
        val lag = foldLag(voice)

        // WANDER's line: random points every 1/8 s, cosine-joined, nudging
        // the folder's drive by up to ±20% over the note's life.
        val linePoints = FloatArray((length * 8f).toInt() + 3) { random.nextFloat() * 2f - 1f }
        fun line(t: Float): Float {
            val x = t * 8f
            val k = x.toInt().coerceAtMost(linePoints.size - 2)
            val f = x - k
            val w = (1f - kotlin.math.cos(PI.toFloat() * f)) / 2f
            return linePoints[k] * (1f - w) + linePoints[k + 1] * w
        }

        // WOBBLE's lines: fold depth, bias, WARP's index. Their own seed, so
        // adding them left WANDER's draws where they were.
        val wobbleRandom = Random(seedFor(voice, m, if (wander > 0f) take else 0) + 1)
        val wobbleSteps = Array(3) { FloatArray((maxSecondsFor(voice) * WOBBLE_HZ).toInt() + 2) { wobbleRandom.nextFloat() * 2f - 1f } }
        val wobble = FloatArray(3)
        val wobbleK = 1f - exp(-1f / (WOBBLE_SMOOTH_SECONDS * rate))
        var lastFolded = 0f
        var lastLoop = 0f

        val closedHz = hz * CLOSED_RATIO
        val span = kotlin.math.ln(cutoffAt(1f, hz) / closedHz)
        val cutoffCeiling = rate * 0.45f

        val out = FloatArray(((length + ATTACK_SECONDS) * rate).toInt().coerceAtLeast(64))
        val gate = Gate(length, rate, hold)
        val filter = Dsp.TptSvf(rate)
        // Its own slower gate, for FLARE's lingering bloom; the same gate elsewhere.
        val foldGate = Gate(length / lag, rate, hold)
        val ph = Dsp.phases(2, Dsp.seedFor("TIDE", voice.name))
        var carrier = ph[0]
        var mod = ph[1]
        // A DC blocker after the folder: a biased fold is not centred.
        val dcPole = exp(-2.0 * PI * 20.0 / rate).toFloat()
        var dcIn = 0f
        var dcOut = 0f
        // BODY's sine through the VCA, added once the note's polarity is known.
        val body = FloatArray(out.size)
        for (i in out.indices) {
            val t = i.toFloat() / rate
            val c = gate.next()
            val cFold = if (lag == 1f) c else foldGate.next()
            val chirp = if (voice == TideVoice.DRIP && t < CHIRP_SECONDS) {
                val left = 1f - t / CHIRP_SECONDS
                1f - CHIRP_DEPTH * left * left
            } else {
                1f
            }
            val thump = 2f.pow(THUMP_SEMITONES * exp(-t / THUMP_SECONDS) / 12f)
            carrier += hz * chirp * thump / rate
            val sweep = 1f + SWEEP_DEPTH * exp(-t / SWEEP_SECONDS)
            mod += hz * chirp * thump * ratio * sweep / rate
            val step = (t * WOBBLE_HZ).toInt().coerceAtMost(wobbleSteps[0].size - 1)
            for (k in 0 until 3) wobble[k] += wobbleK * (wobbleSteps[k][step] - wobble[k])
            // Held under CROSS_LOOP by last sample's index and drive: a sample late, harmlessly.
            val cross = if (lastLoop * CROSS > CROSS_LOOP) CROSS_LOOP / lastLoop else CROSS
            val modPhase = 2.0 * PI * mod + cross * lastFolded
            val pmIndex = index * (1f + WOBBLE_DEPTH * wobble[2]) * (0.35f + 0.65f * c)
            val pm = pmIndex * sin(modPhase).toFloat()
            val x = sin(2.0 * PI * carrier + pm).toFloat()
            val foldEnv = cFold + (1f - cFold) * foldFloor
            val depth = foldAmount * foldEnv * (1f + 0.2f * wander * line(t)) * (1f + WOBBLE_DEPTH * wobble[0])
            val drive = 1f + (MAX_DRIVE - 1f) * reach * depth.coerceIn(0f, 1f)
            val lean = edgeIn * (TILT * (2f * c - 1f) + WOBBLE_BIAS * wobble[1])
            val folded = fold(x, drive, bias * depth + lean)
            lastFolded = folded
            lastLoop = pmIndex * drive
            dcOut = folded - dcIn + dcPole * dcOut
            dcIn = folded
            val cFilter = if (filterCurve == 1f || c <= 0f) c else exp(filterCurve * kotlin.math.ln(c))
            val cutoff = (closedHz * exp(span * cFilter)).coerceAtMost(cutoffCeiling)
            filter.process(dcOut, cutoff, damping)
            val vca = if (c <= 0f) 0f else exp(GAIN_CURVE.toFloat() * kotlin.math.ln(c))
            out[i] = filter.low * vca
            body[i] = sin(2.0 * PI * carrier).toFloat() * vca
        }
        var correlation = 0.0
        for (i in out.indices) correlation += out[i] * body[i]
        val polarity = if (correlation < 0.0) -BODY else BODY
        for (i in out.indices) out[i] += polarity * body[i]
        return out
    }

    /** Where [bandLimit] starts rolling off: under the output's Nyquist, over anything TIDE means to play. */
    internal const val BAND_LIMIT_HZ = 19_500f

    /** Butterworth damping (1/Q) for four cascaded two-poles: an eighth-order low-pass. */
    private val BUTTERWORTH_8 = floatArrayOf(1.9616f, 1.6629f, 1.1111f, 0.3902f)

    /**
     * An eighth-order low-pass at [BAND_LIMIT_HZ], run at the oversampled
     * [rate] before [Dsp.decimate]. The decimator's first rejection above
     * the new Nyquist is only about 18 dB (its KDoc has the measurement),
     * which every other engine can live with; a fold at a high note cannot,
     * because it puts real energy just above 22 kHz. Measured on a steady
     * fold at C6 (drive 6, index 3), energy between harmonics sat 36.5 dB
     * under them at 4x, 37.1 dB at 8x and 45.3 dB at 4x with this filter:
     * the decimator's leak, not the render rate, set the floor.
     */
    internal fun bandLimit(buf: FloatArray, rate: Int) {
        for (k in BUTTERWORTH_8) {
            val f = Dsp.TptSvf(rate)
            for (i in buf.indices) {
                f.process(buf[i], BAND_LIMIT_HZ, k)
                buf[i] = f.low
            }
        }
    }

    fun render(voice: TideVoice, macros: Map<String, Float> = emptyMap(), take: Int = 0): Snip {
        // Folding is the most alias-prone move in synthesis: render at 4x
        // RATE so the folds' harmonics land above 22.05 kHz, then decimate.
        val raw = synthesize(voice, macros, RATE * Dsp.OVERSAMPLE, take)
        bandLimit(raw, RATE * Dsp.OVERSAMPLE)
        val out = Dsp.decimate(raw, RATE)
        Dsp.levelTo(out, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET + LOUDNESS_OFFSET.getValue(voice))
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }
}
