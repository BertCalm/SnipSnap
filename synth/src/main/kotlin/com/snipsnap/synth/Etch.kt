package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.random.Random

/** ETCH's six voices: two waves, then four mechanisms ([Etch.config]). The owner chose all six on 2026-11-12. */
enum class EtchVoice { SAW, SQUARE, RUBBER, WHISTLE, GRIT, DUB }

/**
 * ETCH: a squelching step-line bass, a drive that glues the steps, and a tempo delay into a reverb.
 *
 * The characteristic gesture is a sixteen-step line, accents that bite and build, slides that carry the pitch and
 * keep the filter open, a drive that evens out the peaks, and dotted-eighth repeats falling into a room behind it.
 * The owner supplied the references (a groovebox jam and a 303-style box) and the pedal chain (a bass overdrive,
 * then a delay and reverb pedal); see docs/ETCH.md.
 *
 * This is a port of the Python prototype the owner accepted by ear on 2026-11-12 (`prototype/etch/etch_proto.py`
 * at round 3, "I'm good with evening in audition", read as "everything"). Every constant below is the prototype's;
 * the comments say why each is there. The only intended differences are the house output stage ([Tide.bandLimit],
 * [Dsp.decimate], [Dsp.levelTo] to loudness instead of the harness's RMS match) and the round-robin jitter source.
 *
 * The chain, in the order a pedalboard would put it:
 * ```
 * note or line ─► VOICE (4x) ─► BITE (4x) ─► bandLimit + decimate ─► SPACE (1x) ─► DC, level, fade
 *                 saw/square,    clean/drive                         tempo delay ─► reverb
 *                 ladder, accent blend
 * ```
 * HOLD below [LOOP_HOLD_AT] is how long the note is held (a TONAL one-shot, always under [LOOP_MIN_SECONDS]); from
 * [LOOP_HOLD_AT] the voice plays its sixteen-step line as a seamless two-bar LOOP. Velocity is the accent: a harder
 * hit opens the filter further, decays faster and charges the accent capacitor, so it changes the tone, not only
 * the level. Each round-robin take ([take]) moves five things a listener hears, dealt out so no two of four takes
 * share a setting.
 */
object Etch {
    const val MODEL_VERSION = 1
    const val MIDI_MIN = 24
    const val MIDI_MAX = 60
    const val DEFAULT_MIDI = 36
    const val TUNE_SEMITONES = 24

    /** From here HOLD plays the line as a LOOP. */
    const val LOOP_HOLD_AT = 0.85f

    /** The classifier's LOOP line ([com.snipsnap.audio.Classifier] keeps its own constant private). */
    const val LOOP_MIN_SECONDS = 1.5f

    /** A one-shot never runs past this, so it always files TONAL. */
    const val ONE_SHOT_MAX_SECONDS = 1.45

    /** Bars in a LOOP, and the passes rendered before the kept one so the delay and reverb tails wrap. */
    const val LOOP_BARS = 2
    private const val WARM_PASSES = 2

    private const val FS = RATE * Dsp.OVERSAMPLE
    private const val OUTPUT_DC_HZ = 20f

    // ---- tone (round 3, accepted 2026-11-12) ---------------------------------------------------------------------
    // The owner heard round 2 as "a bit mid heavy, like dollar store headphones": the round-1 cap on the cutoff
    // (1.2 kHz, ceiling 3.5 kHz) had come from a brightness measure that stopped at 5 to 10 kHz. These are round 3's.

    /** CUTOFF's base range, Hz, exponential. */
    private const val CUT_LO = 110.0
    private const val CUT_HI = 2000.0

    /** A high-pass in the resonance feedback, so the peak does not hollow out the bass. */
    private const val FB_HP_HZ = 140.0

    /** A soft ceiling on the opened cutoff: however far envelope and accents push, the peak stays musical. */
    private const val FC_MAX = 8000.0

    /** The ladder's output blends its fourth, third and second poles: a gentler slope keeps the saw's top. */
    private const val OUT_W4 = 0.4
    private const val OUT_W3 = 0.3
    private const val OUT_W2 = 0.3

    /** BITE's post-clip low-pass at BITE 0 and 1, and the pedal's HIGH control: a shelf that keeps the sizzle. */
    private const val BITE_LP_AT_ZERO = 14000.0
    private const val BITE_LP_AT_ONE = 9000.0
    private const val BITE_HIGH_DB = 4.0
    private const val BITE_HIGH_HZ = 3000.0

    // ---- voices ------------------------------------------------------------------------------------------------

    /**
     * What makes each voice a different instrument, not a preset. Every switch is off for SAW and SQUARE.
     * [sub] is a square one octave down mixed before the filter (RUBBER); [pre] drives a wavefolder before the
     * filter, so the filter sweeps the folds (GRIT); [keytrack] is octaves of cutoff per octave of pitch, so the peak
     * follows the note (WHISTLE); [kScale] pushes the resonance past self-oscillation and [inScale] lowers the level
     * into the filter so its own tone dominates (WHISTLE); [dub] runs every echo through an envelope-driven band-pass
     * inside the delay loop, so each echo squelches again (DUB).
     */
    internal class Config(
        val square: Boolean, val sub: Double = 0.0, val pre: Double = 0.0, val keytrack: Double = 0.0,
        val kScale: Double = 1.0, val inScale: Double = 1.0, val dub: Boolean = false,
    )

    internal fun config(voice: EtchVoice): Config = when (voice) {
        EtchVoice.SAW -> Config(square = false)
        EtchVoice.SQUARE -> Config(square = true)
        EtchVoice.RUBBER -> Config(square = true, sub = 0.85)
        EtchVoice.WHISTLE -> Config(square = false, keytrack = 1.0, kScale = 1.15, inScale = 0.6)
        EtchVoice.GRIT -> Config(square = false, pre = 6.0)
        EtchVoice.DUB -> Config(square = true, dub = true)
    }

    /** TUNE CUTOFF PEAK SWEEP BITE SPACE, then HOLD. Velocity is the accent, not a macro. */
    fun macrosFor(voice: EtchVoice): List<MacroSpec> {
        // CUTOFF, PEAK, SWEEP, BITE, SPACE: the accepted round-3 defaults.
        val (cutoff, peak, sweep, bite, space) = when (voice) {
            EtchVoice.SAW, EtchVoice.SQUARE -> listOf(.45f, .70f, .50f, .45f, .40f)
            EtchVoice.RUBBER -> listOf(.25f, .30f, .30f, .30f, .20f)
            EtchVoice.WHISTLE -> listOf(.45f, .95f, .20f, .35f, .35f)
            EtchVoice.GRIT -> listOf(.35f, .70f, .55f, .50f, .15f)
            EtchVoice.DUB -> listOf(.20f, .60f, .40f, .30f, .85f)
        }
        return listOf(
            MacroSpec("TUNE", .5f, .5f), MacroSpec("CUTOFF", cutoff, .5f), MacroSpec("PEAK", peak, 0f),
            MacroSpec("SWEEP", sweep, 0f), MacroSpec("BITE", bite, 0f), MacroSpec("SPACE", space, 0f),
            MacroSpec("HOLD", HOLD_DEFAULT, 0f),
        )
    }

    /** HOLD's default: a 0.48 s gate, the prototype's audition note (0.45 s). */
    private const val HOLD_DEFAULT = .4f

    fun defaults(voice: EtchVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    /** Every macro finite and in 0..1, unknown keys dropped, absent ones at the voice's default. */
    fun settled(voice: EtchVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).mapValues { (key, default) -> (macros[key]?.takeIf { it.isFinite() } ?: default).coerceIn(0f, 1f) }

    /** A roll near [near] (or near the defaults). HOLD stays under [LOOP_HOLD_AT], in the one-shot stage. */
    fun scramble(voice: EtchVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> {
        val rolled = Dsp.scrambleNear(settled(voice, if (near?.engine == EtchPatch.ENGINE) near.macros else emptyMap()), temperature, random)
        return rolled + ("HOLD" to rolled.getValue("HOLD").coerceAtMost(LOOP_HOLD_AT - .05f))
    }

    /** The played note: the patch's root, moved by TUNE (plus or minus 12 semitones), held inside the register. */
    fun midiFor(tune: Float, midi: Int? = null): Int =
        ((midi ?: DEFAULT_MIDI) + (((tune.takeIf { it.isFinite() } ?: .5f).coerceIn(0f, 1f) - .5f) * TUNE_SEMITONES).roundToInt())
            .coerceIn(MIDI_MIN, MIDI_MAX)

    fun isLoop(hold: Float): Boolean = hold >= LOOP_HOLD_AT

    /** LOOP from [LOOP_HOLD_AT], otherwise TONAL: a one-shot is capped at [ONE_SHOT_MAX_SECONDS]. */
    fun drumClassFor(voice: EtchVoice, macros: Map<String, Float> = emptyMap()): DrumClass =
        if (isLoop(settled(voice, macros).getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL

    /** How long a one-shot's gate is held: 0.1 s at HOLD 0, 0.9 s just under the loop. */
    internal fun gateSeconds(hold: Float): Double = 0.1 + 0.8 * (hold / LOOP_HOLD_AT).coerceIn(0f, 1f)

    // ---- lines -------------------------------------------------------------------------------------------------

    /** One sequencer step: semitones above the root (null is a rest), its velocity, and whether it slides into the next. */
    internal class Step(val note: Int?, val velocity: Double, val slide: Boolean)

    /** A sixteen-step line at a tempo. */
    internal class Line(val bpm: Double, val steps: List<Step>)

    /**
     * Compact pattern text, as in the prototype: `0`, `12a` (accent), `7s` (slide into the next), `13as`, `-` (rest).
     * An accent is velocity 1.0, a plain step 0.6.
     */
    internal fun parse(text: String): List<Step> = text.trim().split(Regex("\\s+")).map { tok ->
        if (tok == "-") Step(null, 0.6, false)
        else Step(tok.trimEnd('a', 's').toInt(), if ('a' in tok) 1.0 else 0.6, 's' in tok)
    }

    /**
     * Two original lines written for the audition from the references' measurements (genre habits: root pedal,
     * octave jumps, a semitone push, off-beat accents; neither reference copied). LINE A: about 112 BPM, sparse.
     * LINE B: about 130 BPM, busy. Neither slides across its own end, so a loop's passes are identical.
     */
    private val LINE_A = Line(112.0, parse("0a - 0 12s 7 - 0a 3 - 0 10s 12a - 7 0 5"))
    private val LINE_B = Line(130.0, parse("0a 0 12s 13 0 0a 12 - 0 15s 12a 0 6 12s 13a 0"))

    /** SQUARE and DUB play the darker, slower line A; the rest play line B, as in the audition. */
    internal fun lineFor(voice: EtchVoice): Line = when (voice) {
        EtchVoice.SQUARE, EtchVoice.DUB -> LINE_A
        else -> LINE_B
    }

    // ---- round robin -------------------------------------------------------------------------------------------

    /**
     * One round-robin take. The owner heard round 1's takes (small independent random wobbles) as one strike, so
     * each take moves five things a listener hears: how far the filter opens ([RR_CUT]), how long its envelope
     * decays ([RR_DEC]), how much it rings ([RR_RES]), a pitch dip of up to [RR_SCOOP] cents at the onset that settles
     * onto the exact note, and attack speed. With four takes each dimension's four positions are dealt by a different
     * permutation, so no two takes share a position on any dimension. Take 0 is the plain render.
     */
    internal class Take(
        val phase0: Double, val driftCut: Double, val driftDec: Double, val kTake: Double,
        val scoopCents: Double, val attackSeconds: Double,
    )

    private const val RR_CUT = 0.07
    private const val RR_DEC = 0.48
    private const val RR_RES = 0.18
    private const val RR_SCOOP = 40.0
    private const val RR_ATT_LO = 0.0012
    private const val RR_ATT_HI = 0.0055
    private val STRATA = doubleArrayOf(-0.75, -0.25, 0.25, 0.75)
    private val PERM_CUT = intArrayOf(0, 2, 3, 1)
    private val PERM_DEC = intArrayOf(2, 0, 1, 3)
    private val PERM_RES = intArrayOf(3, 1, 0, 2)
    private val PERM_SCOOP = intArrayOf(1, 3, 0, 2)
    private val PERM_ATT = intArrayOf(2, 3, 1, 0)
    private val PLAIN = Take(0.0, 1.0, 1.0, 1.0, 0.0, 0.002)

    internal fun takeFor(seed: Int, take: Int): Take {
        if (take <= 0) return PLAIN
        val rng = Random(Dsp.seedFor("ETCH", seed, take))
        val k = (take - 1) % 4
        // Every draw is made in the same order on every take, so one dimension never depends on another's value.
        val jitter = DoubleArray(5) { (rng.nextDouble() - 0.5) * 0.3 }
        val phase0 = rng.nextDouble()
        fun pos(perm: IntArray, j: Int) = STRATA[perm[k]] + jitter[j]
        return Take(
            phase0 = phase0,
            driftCut = 1.0 + RR_CUT * pos(PERM_CUT, 0),
            driftDec = 1.0 + RR_DEC * pos(PERM_DEC, 1),
            kTake = 1.0 + RR_RES * pos(PERM_RES, 2),
            scoopCents = RR_SCOOP * (0.5 + 0.5 * pos(PERM_SCOOP, 3)),
            attackSeconds = RR_ATT_LO + (RR_ATT_HI - RR_ATT_LO) * (0.5 + 0.5 * pos(PERM_ATT, 4)),
        )
    }

    // ---- render ------------------------------------------------------------------------------------------------

    /** The one-shot, or from [LOOP_HOLD_AT] the line as a seamless loop. [take] 0 is the plain render. */
    fun render(voice: EtchVoice, macros: Map<String, Float> = emptyMap(), midi: Int? = null, velocity: Float = 1f,
        take: Int = 0, seed: Int = 0): Snip {
        val m = settled(voice, macros)
        val v = velocity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        val out = if (isLoop(m.getValue("HOLD"))) renderLoop(voice, m, midi, v, take, seed) else renderOneShot(voice, m, midi, v, take, seed)
        return Snip(out, channels = 1, sampleRate = RATE)
    }

    /**
     * How a one-shot ends, for the round-K2 classifier study only: [ACCEPTED] is the accepted sound and the only
     * shape [render] uses. [minGate] floors the held time, [releaseSeconds] is the amplifier's release time constant
     * after the gate, and [lowLiftOct] opens the filter on low notes (full at MIDI 24, none from MIDI 48).
     */
    internal data class OneShotShape(val minGate: Double = 0.0, val releaseSeconds: Double = 0.007, val lowLiftOct: Double = 0.0,
        val releaseLowSeconds: Double? = null) {
        /** The release at [note]: [releaseSeconds] from MIDI 48 up, rising to [releaseLowSeconds] at MIDI 24 when set. */
        fun releaseAt(note: Int): Double = releaseLowSeconds?.let { low ->
            releaseSeconds + (low - releaseSeconds) * ((48.0 - note) / 24.0).coerceIn(0.0, 1.0)
        } ?: releaseSeconds

        companion object { val ACCEPTED = OneShotShape() }
    }

    /** A one-shot in [shape]; loops are never affected. For the classifier study. */
    internal fun renderOneShotShaped(voice: EtchVoice, macros: Map<String, Float>, midi: Int?, velocity: Float,
        shape: OneShotShape, take: Int = 0, seed: Int = 0): Snip {
        val m = settled(voice, macros)
        require(!isLoop(m.getValue("HOLD"))) { "a shaped render is a one-shot" }
        return Snip(renderOneShot(voice, m, midi, velocity.coerceIn(0f, 1f), take, seed, shape), channels = 1, sampleRate = RATE)
    }

    private fun renderOneShot(voice: EtchVoice, m: Map<String, Float>, midi: Int?, velocity: Float, take: Int, seed: Int,
        shape: OneShotShape = OneShotShape.ACCEPTED): FloatArray {
        val note = midiFor(m.getValue("TUNE"), midi)
        val gate = maxOf(gateSeconds(m.getValue("HOLD")), shape.minGate)
        val space = m.getValue("SPACE").toDouble()
        // The release, then room for SPACE's first repeats; never past the TONAL line.
        val tail = if (shape == OneShotShape.ACCEPTED) 0.45 else maxOf(0.45, 5.0 * shape.releaseAt(note))
        val seconds = min(gate + tail + 0.5 * space, ONE_SHOT_MAX_SECONDS)
        val frames = (seconds * FS).roundToInt()
        val gateFrames = (gate * FS).roundToInt().coerceAtMost(frames)
        val c = Controls(frames)
        c.target.fill(note.toDouble())
        c.velocity.fill(velocity.toDouble())
        c.trig[0] = true
        for (i in 0 until gateFrames) c.gate[i] = true
        c.releaseSeconds = shape.releaseAt(note)
        c.cutLiftOct = shape.lowLiftOct * ((48.0 - note) / 24.0).coerceIn(0.0, 1.0)
        val raw = voiceKernel(voice, m, c, takeFor(seed, take))
        bite(raw, m.getValue("BITE").toDouble())
        var out = toOutputRate(raw)
        out = space(out, space, lineFor(voice).bpm, config(voice).dub)
        removeDc(out)
        Dsp.levelTo(out, RATE, Dsp.MELODIC_LOUDNESS_TARGET * velocity.pow(.35f))
        Dsp.fadeTail(out)
        return out
    }

    /**
     * The line as one seamless loop of [LOOP_BARS] bars. It is rendered for [WARM_PASSES] extra passes first and
     * the whole chain runs over all of them, so the delay and reverb tails from the end of the loop already sound at
     * its start. The kept pass is checked against the pass that really preceded it ([Keys.requireSeam]): a loop that
     * does not close throws rather than shipping a click.
     */
    private fun renderLoop(voice: EtchVoice, m: Map<String, Float>, midi: Int?, velocity: Float, take: Int, seed: Int): FloatArray {
        val line = lineFor(voice)
        val root = midiFor(m.getValue("TUNE"), midi)
        val steps = List(LOOP_BARS) { line.steps }.flatten()
        val stepFrames = (FS * 60.0 / line.bpm / 4.0).roundToInt()
        val passFrames = stepFrames * steps.size
        val c = Controls(passFrames * (WARM_PASSES + 1))
        val flat = List(WARM_PASSES + 1) { steps }.flatten()
        var last = root.toDouble()
        for ((j, st) in flat.withIndex()) {
            val a = j * stepFrames
            val b = a + stepFrames
            val prev = if (j > 0) flat[j - 1] else flat.last()
            val next = if (j + 1 < flat.size) flat[j + 1] else flat.first()
            if (st.note == null) {
                for (i in a until b) c.target[i] = last
                continue
            }
            val note = (root + st.note).toDouble()
            last = note
            // The line's own accents ride on the pad's velocity: a soft pad plays a softer line.
            val v = st.velocity * (0.4 + 0.6 * velocity)
            for (i in a until b) { c.target[i] = note; c.velocity[i] = v }
            if (prev.note != null && prev.slide) for (i in a until b) c.slide[i] = true else c.trig[a] = true
            val held = st.slide && next.note != null
            val end = if (held) b else a + (stepFrames * LINE_GATE).toInt()
            for (i in a until end) c.gate[i] = true
        }
        val raw = voiceKernel(voice, m, c, takeFor(seed, take))
        bite(raw, m.getValue("BITE").toDouble())
        val full = toOutputRate(raw)
        val wet = space(full, m.getValue("SPACE").toDouble(), line.bpm, config(voice).dub)
        removeDc(wet)
        val loopFrames = passFrames / Dsp.OVERSAMPLE
        Keys.requireSeam("ETCH $voice line at MIDI $root", wet, wet.size - loopFrames)
        val loop = wet.copyOfRange(wet.size - loopFrames, wet.size)
        Dsp.levelTo(loop, RATE, Dsp.MELODIC_LOUDNESS_TARGET * velocity.pow(.35f))
        return loop
    }

    /** A line step's gate, as a share of the step (the prototype's 0.55). */
    private const val LINE_GATE = 0.55

    /** Per-sample controls at the internal rate. */
    internal class Controls(n: Int) {
        val target = DoubleArray(n)
        val gate = BooleanArray(n)
        val trig = BooleanArray(n)
        val slide = BooleanArray(n)
        val velocity = DoubleArray(n)
        /** The amplifier's release time constant after the gate (the accepted 7 ms). */
        var releaseSeconds = 0.007
        /** Octaves added to the cutoff for the whole render (0 in the accepted sound). */
        var cutLiftOct = 0.0
    }

    // ---- the voice ---------------------------------------------------------------------------------------------

    /**
     * The voice at the internal rate: oscillator, the accent and slide circuits, and the ladder. A port of the
     * prototype's `_voice_kernel`, sample for sample.
     */
    internal fun voiceKernel(voice: EtchVoice, m: Map<String, Float>, c: Controls, t: Take): DoubleArray {
        val cfg = config(voice)
        val n = c.target.size
        val out = DoubleArray(n)
        val fs = FS.toDouble()
        val peak = m.getValue("PEAK").toDouble()
        val sweep = m.getValue("SWEEP").toDouble()
        val cutOct = ln(CUT_LO * (CUT_HI / CUT_LO).pow(m.getValue("CUTOFF").toDouble()) / 20.0) / LN2 + c.cutLiftOct
        // Slide: a one-pole RC on the pitch CV. 100 k and 220 n is a 22 ms time constant (synth-diy, 2012, on the
        // original circuit), settled in about 66 ms.
        val aGlide = exp(-1.0 / (0.022 * fs))
        // SWEEP couples the envelope's depth with its decay: the hand rides both together on the reference box.
        val envOct = 0.6 + 3.6 * sweep
        val decS = (0.12 + 1.6 * sweep * sweep) * t.driftDec
        val aDecNorm = exp(-1.0 / (decS * fs))
        val aDecAcc = exp(-1.0 / (0.16 * fs))          // accented notes decay short, fixed
        val aAtt = exp(-1.0 / (t.attackSeconds * fs))
        val aRel = exp(-1.0 / (c.releaseSeconds * fs))
        val aHold = exp(-1.0 / (2.5 * fs))             // a slow sag while held
        // The accent capacitor charges fast from the accented envelope and discharges slower, slower still at high
        // PEAK, so a run of accents climbs (x0x notes: the sweep "becomes more prominent as the Resonance pot is
        // increased").
        val aCapUp = exp(-1.0 / (0.012 * fs))
        val aCapDn = exp(-1.0 / ((0.12 + 0.35 * peak) * fs))
        val k = 4.15 * peak * cfg.kScale * t.kTake    // self-oscillates near PEAK 1
        val aScoop = exp(-1.0 / (0.012 * fs))
        val aFbHp = exp(-2.0 * PI * FB_HP_HZ / fs)
        val aSq = exp(-2.0 * PI * 9000.0 / fs)         // the square's edges, softened a little
        val makeUp = 1.0 + 0.5 * k / 4.15
        val accentSweep = 1.2 + 2.2 * peak
        var cv = c.target[0]
        var ph = t.phase0
        var ph2 = t.phase0
        var scoop = 0.0
        var fenv = 0.0
        var aenv = 0.0
        var accCap = 0.0
        var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var fbHp = 0.0; var fbHpX = 0.0; var sqLp = 0.0
        var curVel = 0.6
        var curAcc = 0.0
        var fdec = aDecNorm
        var lastCv = Double.NaN
        var hz = 0.0
        for (i in 0 until n) {
            if (c.trig[i]) {
                fenv = 1.0
                // Restart the cycle on every new (non-slid) note: a loop's passes stay identical and its seam closes.
                ph = t.phase0
                ph2 = t.phase0
                scoop = t.scoopCents / 100.0
                curVel = c.velocity[i]
                // The accent: nothing below velocity 0.55, full at 1.0, smooth between.
                val x = ((curVel - 0.55) / 0.45).coerceIn(0.0, 1.0)
                curAcc = x * x * (3.0 - 2.0 * x)
                fdec = aDecNorm + (aDecAcc - aDecNorm) * curAcc
            }
            cv = if (c.slide[i]) c.target[i] + (cv - c.target[i]) * aGlide else c.target[i]
            val pitch = if (t.scoopCents != 0.0) { scoop *= aScoop; cv + scoop } else cv
            if (pitch != lastCv) { hz = 440.0 * 2.0.pow((pitch - 69.0) / 12.0); lastCv = pitch }
            val dt = hz / fs
            // A polyBLEP saw.
            ph += dt
            if (ph >= 1.0) ph -= 1.0
            var saw = 2.0 * ph - 1.0
            if (ph < dt) { val x = ph / dt; saw -= x + x - x * x - 1.0 }
            else if (ph > 1.0 - dt) { val x = (ph - 1.0) / dt; saw -= x * x + x + x + 1.0 }
            var osc = if (cfg.square) {
                // A 50% square shaped from the saw, as the reference circuit does: no even harmonics, which is what
                // tells it from the saw. Gain 25 keeps the edges short (2nd harmonic -44 dB, 3rd -9.3 dB). Round 1's
                // 60/40 pulse measured within 2 dB of the saw after the filter, and the owner heard them as one.
                val sq = tanh(25.0 * saw) * 0.7
                sqLp = sq + (sqLp - sq) * aSq
                sqLp
            } else saw
            if (cfg.sub > 0.0) {
                // RUBBER: a square one octave down, from its own polyBLEP saw, under the main wave.
                val dt2 = 0.5 * dt
                ph2 += dt2
                if (ph2 >= 1.0) ph2 -= 1.0
                var saw2 = 2.0 * ph2 - 1.0
                if (ph2 < dt2) { val x = ph2 / dt2; saw2 -= x + x - x * x - 1.0 }
                else if (ph2 > 1.0 - dt2) { val x = (ph2 - 1.0) / dt2; saw2 -= x * x + x + x + 1.0 }
                osc = (osc + cfg.sub * tanh(25.0 * saw2) * 0.7) / (1.0 + 0.5 * cfg.sub)
            }
            if (cfg.pre > 0.0) {
                // GRIT: a wavefolder before the filter, a little asymmetric. Velocity sets how many times it folds,
                // so a soft hit stays near the plain wave and a hard one makes clusters of harmonics for the filter
                // to sweep. A plain clip kept the saw's harmonic shape (2.9 to 3.3 dB from SAW).
                val pd = cfg.pre * (0.08 + 0.92 * curVel * curVel)
                osc = sin(pd * osc + 0.3) - SIN_03
            }
            fenv *= fdec
            val accDrive = fenv * curAcc
            accCap = if (accDrive > accCap) accDrive + (accCap - accDrive) * aCapUp else accDrive + (accCap - accDrive) * aCapDn
            if (c.gate[i]) {
                aenv = 1.0 + (aenv - 1.0) * aAtt
                if (aenv > 0.98) aenv *= aHold
            } else aenv *= aRel
            // The cutoff: the base, the envelope (deeper when accented), and the accent capacitor; WHISTLE adds the
            // note. The take's drift scales the whole opening, so accented takes vary too.
            val track = if (cfg.keytrack != 0.0) cfg.keytrack * (cv - 36.0) / 12.0 else 0.0
            val oct = (cutOct + track + envOct * fenv * (1.0 - 0.35 * curAcc) + accentSweep * accCap) * t.driftCut
            val raw = 20.0 * exp(oct * LN2)
            val fc = FC_MAX * tanh(raw / FC_MAX)
            val g = 1.0 - exp(-2.0 * PI * fc / fs)
            // Resonance fed back through a high-pass, then four tanh-saturated one-pole stages.
            fbHp = aFbHp * (fbHp + s4 - fbHpX)
            fbHpX = s4
            val u = tanh(osc * 0.9 * cfg.inScale - k * fbHp)
            s1 += g * (u - s1)
            s2 += g * (tanh(s1) - s2)
            s3 += g * (tanh(s2) - s3)
            s4 += g * (s3 - s4)
            val y = (OUT_W4 * s4 + OUT_W3 * s3 + OUT_W2 * s2) * makeUp
            out[i] = y * aenv * (0.62 + 0.38 * curAcc + 0.25 * (curVel - 0.6))
        }
        return out
    }

    private val LN2 = ln(2.0)
    private val SIN_03 = sin(0.3)

    // ---- BITE (4x) ---------------------------------------------------------------------------------------------

    /**
     * A clean/drive blend, after the bass overdrive's BALANCE control. The drive branch carries the whole signal,
     * so the loudest moments (accents, the resonant peak) hit the clip hardest: that is the compression that glues
     * the steps. A pre-emphasis shelf feeds the peak harder than the fundamental, a matching de-emphasis puts the
     * balance back, a low-pass and the pedal's HIGH shelf shape the sizzle, and the branch is level-matched to the
     * clean signal so BITE changes character, not loudness. At 0 it is an exact bypass. In place, at [FS].
     */
    internal fun bite(x: DoubleArray, amount: Double) {
        if (amount <= 0.0) return
        val g = 10.0.pow(lerp(amount, 2.0, 24.0) / 20.0)
        val mix = lerp(amount, 0.3, 0.85)
        val fs = FS.toDouble()
        val emphasis = Butter.highpass1(500.0, fs).run(x)
        val d = DoubleArray(x.size) { tanh(g * (x[it] + 1.8 * emphasis[it]) + 0.12) - TANH_012 }
        val low = Butter.lowpass1(500.0, fs).run(d)
        for (i in d.indices) d[i] = low[i] + (d[i] - low[i]) / 2.8
        val shaped = Butter.lowpass2(lerp(amount, BITE_LP_AT_ZERO, BITE_LP_AT_ONE), fs).run(d)
        val top = Butter.highpass1(BITE_HIGH_HZ, fs).run(shaped)
        val lift = 10.0.pow(BITE_HIGH_DB / 20.0) - 1.0
        for (i in shaped.indices) shaped[i] += lift * top[i]
        val match = (rms(x) + 1e-12) / (rms(shaped) + 1e-12)
        for (i in x.indices) x[i] = (1.0 - mix) * x[i] + mix * shaped[i] * match
    }

    private val TANH_012 = tanh(0.12)

    // ---- SPACE (1x) --------------------------------------------------------------------------------------------

    /**
     * A tempo delay (dotted eighth), then a reverb on the whole signal: the reference pedal's manual puts the reverb
     * after the delay. SPACE raises the delay's level and repeats together and grows the room. Each repeat loses lows
     * and highs, so the repeats sit above the bass. DUB's delay re-squelches every echo ([dubDelay]). At 0 it is an
     * exact bypass.
     */
    internal fun space(x: FloatArray, amount: Double, bpm: Double, dub: Boolean): FloatArray {
        if (amount <= 0.0) return x
        val r = RATE.toDouble()
        val dry = DoubleArray(x.size) { x[it].toDouble() }
        val d = (r * 60.0 / bpm * DOTTED_EIGHTH).roundToInt()
        val wet = if (dub) dubDelay(dry, d, lerp(amount, 0.35, 0.78), exp(-2.0 * PI * 180.0 / r))
        else delay(dry, d, lerp(amount, 0.2, 0.62), exp(-2.0 * PI * lerp(amount, 3800.0, 2600.0) / r), exp(-2.0 * PI * 260.0 / r))
        val delayMix = lerp(amount, 0.25, 0.6)
        val y = DoubleArray(dry.size) { dry[it] + delayMix * wet[it] }
        val t60 = lerp(amount, 0.5, 3.2)
        val send = Butter.highpass2(220.0, r).run(y)
        val room = fdn(send, t60, exp(-2.0 * PI * 4500.0 / r))
        val roomMix = lerp(amount, 0.12, 0.38) * 2.0
        return FloatArray(y.size) { (y[it] + roomMix * room[it]).toFloat() }
    }

    private const val DOTTED_EIGHTH = 0.75

    /** The delay line: tone in the loop (each repeat loses lows and highs), bounded by a tanh. */
    private fun delay(x: DoubleArray, d: Int, fb: Double, aLp: Double, aHp: Double): DoubleArray {
        val buf = DoubleArray(d + 1)
        var w = 0
        var lp = 0.0; var hp = 0.0; var hpX = 0.0
        val wet = DoubleArray(x.size)
        for (i in x.indices) {
            var r = w - d
            if (r < 0) r += d + 1
            val tap = buf[r]
            lp = tap + (lp - tap) * aLp
            hp = aHp * (hp + lp - hpX)
            hpX = lp
            val rep = tanh(hp * 1.2) / 1.2
            wet[i] = tap
            buf[w] = x[i] + fb * rep
            if (++w > d) w = 0
        }
        return wet
    }

    /**
     * DUB's delay: each repeat passes a band-pass whose centre follows the repeat's own loudness, so a loud echo
     * opens and closes as it fades and quieter echoes come back darker. The band is unity at its centre: the
     * prototype's first try amplified by 1/Q (about 4.5x) inside the loop, the loop gain passed 1 and the echoes ran
     * away into a whine (99% of the energy above 2 kHz).
     */
    private fun dubDelay(x: DoubleArray, d: Int, fb: Double, aHp: Double): DoubleArray {
        val r = RATE.toDouble()
        val buf = DoubleArray(d + 1)
        var w = 0
        var hp = 0.0; var hpX = 0.0; var env = 0.0; var lp = 0.0; var bp = 0.0
        val aAtt = exp(-1.0 / (0.002 * r))
        val aRel = exp(-1.0 / (0.09 * r))
        val q = 0.22
        val wet = DoubleArray(x.size)
        for (i in x.indices) {
            var rd = w - d
            if (rd < 0) rd += d + 1
            val tap = buf[rd]
            val a = abs(tap)
            env = if (a > env) a + (env - a) * aAtt else a + (env - a) * aRel
            val e = min(env / 0.25, 1.0)
            val fc = 280.0 * 2.0.pow(3.0 * e)
            val f = 2.0 * sin(PI * fc / r)
            val hi = tap - lp - q * bp
            bp += f * hi
            lp += f * bp
            val band = q * bp
            hp = aHp * (hp + band - hpX)
            hpX = band
            val rep = tanh(hp * 1.5) / 1.5
            wet[i] = tap
            buf[w] = x[i] + fb * rep
            if (++w > d) w = 0
        }
        return wet
    }

    private val FDN_LENGTHS = intArrayOf(1031, 1327, 1523, 1801, 2039, 2381, 2713, 3001)

    /** Eight lines through a Householder matrix with one-pole damping; each line's gain is under 1, so it cannot grow. */
    private fun fdn(x: DoubleArray, t60: Double, aDamp: Double): DoubleArray {
        val lines = FDN_LENGTHS.size
        val g = DoubleArray(lines) { 10.0.pow(-3.0 * FDN_LENGTHS[it] / RATE / t60) }
        val bufs = Array(lines) { DoubleArray(FDN_LENGTHS[it]) }
        val idx = IntArray(lines)
        val damp = DoubleArray(lines)
        val v = DoubleArray(lines)
        val out = DoubleArray(x.size)
        for (i in x.indices) {
            var s = 0.0
            for (j in 0 until lines) { v[j] = bufs[j][idx[j]]; s += v[j] }
            out[i] = s / lines
            val hh = 2.0 * s / lines
            for (j in 0 until lines) {
                val y = (v[j] - hh) * g[j]
                damp[j] = y + (damp[j] - y) * aDamp
                bufs[j][idx[j]] = damp[j] + x[i] * 0.35
                if (++idx[j] >= FDN_LENGTHS[j]) idx[j] = 0
            }
        }
        return out
    }

    // ---- output stage ------------------------------------------------------------------------------------------

    /** The house order: band-limit at the internal rate, then decimate to [RATE]. */
    private fun toOutputRate(raw: DoubleArray): FloatArray {
        val f = FloatArray(raw.size) { raw[it].toFloat() }
        Tide.bandLimit(f, FS)
        return Dsp.decimate(f, RATE)
    }

    /** Subtract the mean, then a one-pole high-pass at [OUTPUT_DC_HZ], in place. */
    private fun removeDc(out: FloatArray) {
        var mean = 0.0
        for (v in out) mean += v
        val mm = (mean / out.size.coerceAtLeast(1)).toFloat()
        val hp = Dsp.OnePole(RATE)
        for (i in out.indices) {
            val x = out[i] - mm
            out[i] = x - hp.lp(x, OUTPUT_DC_HZ)
        }
    }

    private fun lerp(x: Double, lo: Double, hi: Double): Double = lo + (hi - lo) * x

    private fun rms(x: DoubleArray): Double {
        var s = 0.0
        for (v in x) s += v * v
        return sqrt(s / x.size.coerceAtLeast(1))
    }

    /**
     * First- and second-order Butterworth sections designed by the bilinear transform with prewarping, in Double,
     * transposed direct form II: the same responses the prototype took from scipy's `butter`.
     */
    internal class Butter private constructor(
        private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double,
    ) {
        fun run(x: DoubleArray): DoubleArray {
            val y = DoubleArray(x.size)
            var z1 = 0.0; var z2 = 0.0
            for (i in x.indices) {
                val xi = x[i]
                val yi = b0 * xi + z1
                z1 = b1 * xi - a1 * yi + z2
                z2 = b2 * xi - a2 * yi
                y[i] = yi
            }
            return y
        }

        companion object {
            private fun k(fc: Double, fs: Double) = tan(PI * fc / fs)

            fun lowpass1(fc: Double, fs: Double): Butter {
                val k = k(fc, fs)
                val b = k / (1.0 + k)
                return Butter(b, b, 0.0, (k - 1.0) / (k + 1.0), 0.0)
            }

            fun highpass1(fc: Double, fs: Double): Butter {
                val k = k(fc, fs)
                val b = 1.0 / (1.0 + k)
                return Butter(b, -b, 0.0, (k - 1.0) / (k + 1.0), 0.0)
            }

            fun lowpass2(fc: Double, fs: Double): Butter {
                val k = k(fc, fs)
                val norm = 1.0 / (1.0 + SQRT2 * k + k * k)
                val b = k * k * norm
                return Butter(b, 2.0 * b, b, 2.0 * (k * k - 1.0) * norm, (1.0 - SQRT2 * k + k * k) * norm)
            }

            fun highpass2(fc: Double, fs: Double): Butter {
                val k = k(fc, fs)
                val norm = 1.0 / (1.0 + SQRT2 * k + k * k)
                return Butter(norm, -2.0 * norm, norm, 2.0 * (k * k - 1.0) * norm, (1.0 - SQRT2 * k + k * k) * norm)
            }

            private val SQRT2 = sqrt(2.0)
        }
    }
}
