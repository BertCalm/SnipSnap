package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random

/**
 * SIREN — the dub siren engine (docs/superpowers/specs/2026-09-27-siren-dub-engine-design.md).
 *
 * Every other engine is a strike. This one is a *movement*: a pulse whose
 * pitch a slow oscillator throws up and down, held under a thumb, let go
 * into an echo. A voice is the oscillator's shape — the switch on the front
 * of every siren box — and the knobs are its speed (RATE), its reach
 * (DEPTH), the dive or climb into the note when the button goes down
 * (SWEEP), the tone's edge (GRIT) and how long the button is down (HOLD).
 *
 * Three rules the sound depends on:
 *  - The LFO moves pitch in the log domain, so DEPTH reads as semitones and
 *    a triangle sounds like an even rise and fall; linear hertz would spend
 *    most of its travel at the top and sound lopsided.
 *  - The pulse's phase is continuous while the pitch moves: only the step
 *    changes, never the phase, so RATE can go as fast as it likes with no
 *    click (FATHOM's GLIDE rule).
 *  - The LFO starts at a fixed phase (the bottom of a triangle, the low
 *    half of a square, the top of a ramp), so a recipe regenerates to the
 *    bit and a siren never starts mid-swing.
 *
 * HOLD's top is LOOP: one seamless loop of whole LFO periods, the pitch
 * fitted so the pulse closes on whole cycles, cut at a zero crossing — the
 * render the SURFACE holds under a finger. See [renderLoop].
 */
enum class SirenVoice { WAIL, TRILL, LASER, BIRD }

object Siren {

    const val TUNE_SEMITONES = 24

    /** C4: the bottom of the two-octave TUNE range, so the centre of the knob is C5, the middle of the siren register. */
    const val ROOT_MIDI = 60

    /** RATE: the LFO's speed, exponential — the ear hears rate by ratio too. */
    const val RATE_MIN_HZ = 0.25f
    const val RATE_MAX_HZ = 25f

    /** DEPTH at 1: two octaves each way. */
    const val DEPTH_MAX_SEMITONES = 24f

    /** SWEEP at either end: the note comes in from two octaves away. */
    const val SWEEP_OCTAVES = 2f

    /**
     * How long the sweep takes to land on the note: a short glide for a
     * small one, up to a second for the full two octaves, so the gesture
     * is heard as a glide and not a blip. The first build had a time
     * constant that *shortened* as the sweep deepened (0.15 s at full), and
     * a two-octave dive over in 100 ms under a wail that itself moves an
     * octave each way was inaudible at the audition. Further is longer,
     * the way a portamento is.
     */
    const val SWEEP_NEAR_SECONDS = 0.25f
    const val SWEEP_FAR_SECONDS = 1.0f

    /** The sweep never outlasts this much of HOLD, so a short press still lands on its note. */
    const val SWEEP_HOLD_FRACTION = 0.85f

    /** HOLD, below its LOOP step: how long the button is down. */
    const val HOLD_MIN_SECONDS = 0.3f
    const val HOLD_MAX_SECONDS = 4f

    /**
     * HOLD at or above this is LOOP. The slider is continuous and its
     * right rail is exactly 1; a recipe hand-edited to 0.995 means the
     * same thing. SCRAMBLE never lands here ([SCRAMBLE_HOLD_CEILING]).
     */
    const val LOOP_THRESHOLD = 0.99f
    const val SCRAMBLE_HOLD_CEILING = 0.95f

    /** A LOOP is the fewest whole LFO periods that reach this long, so the WAV is a usable one-shot on a pad too. */
    const val LOOP_MIN_SECONDS = 2f

    /** No render outlasts this: the longest HOLD plus its release, or a LOOP of one period at the slowest RATE. */
    const val MAX_SECONDS = 4.2f

    /** A siren is gated, not struck: on in 5 ms, off in a short release that is a cut without a click. */
    const val ATTACK_SECONDS = 0.005f
    const val RELEASE_T60 = 0.06f

    /** GRIT: the one-pole after the pulse opens from here to here... */
    const val TONE_LOW_HZ = 1_200f
    const val TONE_HIGH_HZ = 12_000f

    /** ...and the drive after the one-pole rises to this. Before the filter a drive would do nothing to a square. */
    const val DRIVE_MAX = 0.8f

    /**
     * The LFO is de-zippered over a millisecond. Not for continuity — the
     * pulse's phase never jumps — but so a square's step and a ramp's
     * snap-back are a flick of the pitch rather than an instant one. Short
     * enough that a 25 Hz triangle keeps 90% of its depth.
     */
    const val LFO_SMOOTH_SECONDS = 0.001f

    /** Where the pitch stops: DEPTH and SWEEP together can ask for four octaves over C6. */
    const val PITCH_CEILING_HZ = 16_000f

    /** The LOOP render discards this much (at least: a whole LFO period, rounded to whole frames) so every filter is in steady state at both ends. */
    private const val WARMUP_MIN_SECONDS = 0.1f

    /** Rendered past the loop's end so the decimator's edge never reaches it. */
    private const val STRETCH_PAD_SECONDS = 0.05f

    /**
     * The rack's ECHO as a one-shot siren lands with it (the spec's settled
     * question 4): a dub tail, repeats darkening. A LOOP siren lands dry —
     * a baked tail smears across the seam, and the SURFACE's echo is its own.
     */
    val LANDING_ECHO: Map<String, Float> = mapOf("TIME" to 0.45f, "REPEAT" to 0.55f, "TONE" to 0.5f, "MIX" to 0.45f)

    /** The chain SEND TO PAD bakes into a siren's recipe: ECHO on a one-shot, nothing on a LOOP. */
    fun landingChain(macros: Map<String, Float>): FxChain? =
        if (isLoop(macros["HOLD"] ?: DEFAULT_HOLD)) null else FxChain().withSection("echo", LANDING_ECHO)

    private const val DEFAULT_HOLD = 0.5f

    /** RATE's macro value for a speed in hertz — the inverse of [rateHz], so a voice's default can be written as the hertz it means. */
    fun rateMacroFor(hz: Float): Float = (ln((hz / RATE_MIN_HZ).toDouble()) / ln((RATE_MAX_HZ / RATE_MIN_HZ).toDouble())).toFloat()

    /** DEPTH's macro value for a reach in semitones. */
    fun depthMacroFor(semitones: Float): Float = semitones / DEPTH_MAX_SEMITONES

    fun macrosFor(voice: SirenVoice): List<MacroSpec> {
        val (rateHz, depthSemis) = when (voice) {
            SirenVoice.WAIL -> 0.5f to 12f
            SirenVoice.TRILL -> 6f to 5f
            SirenVoice.LASER -> 8f to 18f
            SirenVoice.BIRD -> 10f to 14f
        }
        return listOf(
            MacroSpec("TUNE", 0.5f, 0.5f),
            MacroSpec("RATE", rateMacroFor(rateHz)),
            MacroSpec("DEPTH", depthMacroFor(depthSemis)),
            MacroSpec("SWEEP", 0.5f, 0.5f),
            MacroSpec("GRIT", 0.5f),
            MacroSpec("HOLD", DEFAULT_HOLD),
        )
    }

    fun defaults(voice: SirenVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    /**
     * SCRAMBLE near a preset (see [Thump.scramble]); HOLD is capped short of
     * LOOP, because the top step is a choice, not a roll.
     */
    fun scramble(voice: SirenVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = when {
            near != null -> base + near.macros.filterKeys { it in base }
            temperature >= 1f -> base
            else -> base + SirenPresets.forVoice(voice).random(random).macros.filterKeys { it in base }
        }
        val rolled = Dsp.scrambleNear(seed, temperature, random).toMutableMap()
        rolled["HOLD"] = rolled.getValue("HOLD").coerceAtMost(SCRAMBLE_HOLD_CEILING)
        return rolled
    }

    /** What a pad holding this sound is filed as: a LOOP by name when it is one, a pitched note otherwise. */
    fun drumClassFor(voice: SirenVoice, macros: Map<String, Float> = emptyMap()): DrumClass =
        if (isLoop(macros["HOLD"] ?: DEFAULT_HOLD)) DrumClass.LOOP else DrumClass.TONAL

    fun isLoop(hold: Float): Boolean = hold >= LOOP_THRESHOLD

    /** The snapped MIDI note TUNE lands on. */
    fun midiFor(tune: Float): Int = ROOT_MIDI + Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)

    fun frequencyFor(tune: Float): Float = 440f * 2f.pow((midiFor(tune) - 69) / 12f)

    fun rateHz(rate: Float): Float = Dsp.expMap(rate, RATE_MIN_HZ, RATE_MAX_HZ)

    fun depthSemitones(depth: Float): Float = Dsp.lin(depth, 0f, DEPTH_MAX_SEMITONES)

    fun holdSeconds(hold: Float): Float = Dsp.expMap(hold.coerceAtMost(LOOP_THRESHOLD) / LOOP_THRESHOLD, HOLD_MIN_SECONDS, HOLD_MAX_SECONDS)

    /**
     * SWEEP as the octaves the note starts away from itself: positive above
     * (below centre on the knob: the note *falls in*), negative below (above
     * centre: it *rises in*), zero at the centre.
     */
    internal fun sweepOctaves(sweep: Float): Float = (0.5f - sweep.coerceIn(0f, 1f)) * 2f * SWEEP_OCTAVES

    /** How long the sweep takes to land, in seconds: further is longer, and never past [SWEEP_HOLD_FRACTION] of the hold. */
    internal fun sweepSeconds(sweep: Float, holdSeconds: Float): Float =
        Dsp.expMap(abs(sweepOctaves(sweep)) / SWEEP_OCTAVES, SWEEP_NEAR_SECONDS, SWEEP_FAR_SECONDS)
            .coerceAtMost(holdSeconds * SWEEP_HOLD_FRACTION)

    /**
     * The sweep's remaining offset at [t], as a fraction of where it began:
     * a quadratic ease-out, fast off the mark and slowing into the note,
     * landing exactly at [seconds] with no corner. An exponential approach
     * never lands and spends its audible travel in its first tenth; this
     * spends it across the whole glide.
     */
    internal fun sweepRemaining(t: Float, seconds: Float): Float {
        if (seconds <= 0f || t >= seconds) return 0f
        val left = 1f - t / seconds
        return left * left
    }

    /**
     * The LFO's value in -1..1 at [phase] (cycles), each voice starting
     * where its shape is most itself: WAIL at the bottom of the triangle,
     * TRILL on its low tone, LASER at the top of the fall, BIRD at the
     * bottom of the climb.
     */
    internal fun lfoAt(voice: SirenVoice, phase: Float): Float {
        val p = phase - floor(phase)
        return when (voice) {
            SirenVoice.WAIL -> if (p < 0.5f) -1f + 4f * p else 3f - 4f * p
            SirenVoice.TRILL -> if (p < 0.5f) -1f else 1f
            SirenVoice.LASER -> 1f - 2f * p
            SirenVoice.BIRD -> -1f + 2f * p
        }
    }

    /** The LFO as rendered: the shape at RATE, de-zippered; state so a stretch can be run twice identically. */
    private class Lfo(private val voice: SirenVoice, private val stepPerFrame: Double, rate: Int) {
        private val k = (1.0 - exp(-1.0 / (LFO_SMOOTH_SECONDS * rate))).toFloat()
        private var phase = 0.0
        private var smooth = lfoAt(voice, 0f)
        fun next(): Float {
            val raw = lfoAt(voice, phase.toFloat())
            smooth += k * (raw - smooth)
            phase += stepPerFrame
            if (phase >= 1.0) phase -= 1.0
            return smooth
        }
    }

    /**
     * A band-limited square: the naive step with its two discontinuities
     * corrected by PolyBLEP, so even before the oversample the aliasing sits
     * well under the harmonics. [dt] is the phase step this sample.
     */
    private fun polyBlep(t: Double, dt: Double): Double = when {
        t < dt -> { val x = t / dt; x + x - x * x - 1.0 }
        t > 1.0 - dt -> { val x = (t - 1.0) / dt; x * x + x + x + 1.0 }
        else -> 0.0
    }

    internal fun square(phase: Double, dt: Double): Float {
        val naive = if (phase < 0.5) 1.0 else -1.0
        var shifted = phase + 0.5
        if (shifted >= 1.0) shifted -= 1.0
        return (naive + polyBlep(phase, dt) - polyBlep(shifted, dt)).toFloat()
    }

    /**
     * The tone: a square at whatever pitch it is told each sample, into the
     * one-pole, into the drive. The one-pole's coefficient is fixed for the
     * render (GRIT does not move within a note), so it is computed once
     * rather than per sample as `Dsp.OnePole` would.
     */
    private class Tone(grit: Float, private val rate: Int) {
        private val cutoff = Dsp.expMap(grit, TONE_LOW_HZ, TONE_HIGH_HZ)
        private val a = (1.0 - exp(-2.0 * Math.PI * cutoff / rate)).toFloat()
        private val drive = grit * DRIVE_MAX
        private var state = 0f
        private var phase = 0.0
        fun next(hz: Double): Float {
            val dt = hz.coerceAtMost(PITCH_CEILING_HZ.toDouble()) / rate
            val x = square(phase, dt)
            phase += dt
            if (phase >= 1.0) phase -= 1.0
            state += a * (x - state)
            return Dsp.drive(state, drive)
        }
    }

    private fun settled(macros: Map<String, Float>, voice: SirenVoice): Map<String, Float> {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        return m
    }

    /**
     * The one-shot at [rate]: the button down for HOLD, the sweep on the
     * press, the release after. Split from [render] so the oversampled
     * dispatch can be tested against a native-rate render.
     */
    internal fun synthesize(voice: SirenVoice, macros: Map<String, Float>, rate: Int): FloatArray {
        val m = settled(macros, voice)
        val f0 = frequencyFor(m.getValue("TUNE")).toDouble()
        val depth = depthSemitones(m.getValue("DEPTH")) / 12f
        val hold = holdSeconds(m.getValue("HOLD"))
        val sweepOct = sweepOctaves(m.getValue("SWEEP"))
        val sweepSeconds = sweepSeconds(m.getValue("SWEEP"), hold)
        val env = Dsp.Env(attackSeconds = ATTACK_SECONDS, decay2T60 = RELEASE_T60, holdSeconds = hold)
        val frames = ((ATTACK_SECONDS + hold + RELEASE_T60) * rate).toInt().coerceAtLeast(64)
        val lfo = Lfo(voice, rateHz(m.getValue("RATE")).toDouble() / rate, rate)
        val tone = Tone(m.getValue("GRIT"), rate)
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            val t = i.toFloat() / rate
            val octaves = depth * lfo.next() + sweepOct * sweepRemaining(t, sweepSeconds)
            out[i] = env.at(t) * tone.next(f0 * 2.0.pow(octaves.toDouble()))
        }
        return out
    }

    /**
     * A LOOP's shape, before any audio: [frames] at [RATE] is [periods]
     * whole LFO periods rounded to whole frames (so [lfoHz] is RATE's speed
     * moved by the rounding, under 0.1%), and [baseHz] is the centre pitch
     * moved so the pulse completes exactly [cycles] cycles over the loop —
     * `Keys.planLoop`'s fraction-of-a-cent trick.
     */
    internal data class LoopPlan(val frames: Int, val periods: Int, val lfoHz: Double, val baseHz: Double, val cycles: Long)

    /** The loop's length alone — the part of the plan that needs no LFO run. */
    internal fun loopFrames(rateMacro: Float): Pair<Int, Int> {
        val hz = rateHz(rateMacro).toDouble()
        val periods = ceil(LOOP_MIN_SECONDS * hz).toInt().coerceAtLeast(1)
        val frames = Math.round(periods / hz * RATE).toInt()
        return frames to periods
    }

    /** The warm-up before a loop, whole output frames: at least a period and at least [WARMUP_MIN_SECONDS]. */
    private fun warmupFrames(lfoHz: Double): Int =
        Math.round(max(1.0 / lfoHz, WARMUP_MIN_SECONDS.toDouble()) * RATE).toInt()

    internal fun planLoop(
        voice: SirenVoice,
        macros: Map<String, Float>,
        rate: Int = RATE * Dsp.OVERSAMPLE,
        cancelled: () -> Boolean = { false },
    ): LoopPlan {
        val m = settled(macros, voice)
        val (frames, periods) = loopFrames(m.getValue("RATE"))
        val lfoHz = periods.toDouble() * RATE / frames
        val over = rate / RATE
        val warm = warmupFrames(lfoHz) * over
        val depth = depthSemitones(m.getValue("DEPTH")) / 12.0
        // The LFO exactly as the render will run it, through the warm-up and
        // one loop: the mean pitch multiplier over the loop is what fixes
        // the cycle count. At RATE's floor this warm-up and integral are
        // themselves seconds of iteration, so they ask the same way the
        // audio loop that follows does — a cancelled held render must not
        // have to wait out this planning pass first.
        val lfo = Lfo(voice, lfoHz / rate, rate)
        var asked = 0
        fun checkCancelled() {
            if (asked % LOOP_CANCEL_CHECK_SAMPLES == 0 && (cancelled() || Thread.currentThread().isInterrupted)) {
                throw java.util.concurrent.CancellationException("SIREN loop plan no longer wanted")
            }
            asked++
        }
        repeat(warm) { checkCancelled(); lfo.next() }
        var g = 0.0
        repeat(frames * over) { checkCancelled(); g += 2.0.pow(depth * lfo.next()) / rate }
        val f0 = frequencyFor(m.getValue("TUNE")).toDouble()
        val cycles = Math.round(f0 * g)
        return LoopPlan(frames, periods, lfoHz, cycles / g, cycles)
    }

    /** How often, in oversampled samples, a held-pad render asks whether it is still wanted (`Resin.HELD_CANCEL_CHECK_SAMPLES`'s own value: about every 0.2 s of audio at 44.1 kHz). */
    private const val LOOP_CANCEL_CHECK_SAMPLES = 1 shl 15

    /**
     * [loops] consecutive LOOPs at [RATE], unlevelled, taken from a stretch
     * whose warm-up has been discarded: the LFO periodic in the loop, the
     * pulse closing on whole cycles ([planLoop]), the one-pole, the drive and
     * the band limit all in steady state, and the decimator run over the
     * whole stretch so its edge never touches what is kept. No sweep, no
     * envelope, no fade: the finger is the sweep and the gate is the release.
     *
     * [cancelled] stops the render part-way with a `CancellationException` —
     * a held zone at the slowest RATE is seconds of audio, the same reach
     * [Resin.renderHeld]'s own `cancelled` has over a RESIN zone.
     */
    internal fun synthesizeLoopStretch(
        voice: SirenVoice,
        macros: Map<String, Float>,
        loops: Int,
        cancelled: () -> Boolean = { false },
    ): FloatArray {
        require(loops >= 1) { "a stretch is at least one loop, asked for $loops" }
        val m = settled(macros, voice)
        val rate = RATE * Dsp.OVERSAMPLE
        val over = Dsp.OVERSAMPLE
        val plan = planLoop(voice, m, rate, cancelled)
        val warm = warmupFrames(plan.lfoHz)
        val total = (warm + loops * plan.frames + (STRETCH_PAD_SECONDS * RATE).toInt()) * over
        val depth = depthSemitones(m.getValue("DEPTH")) / 12.0
        val lfo = Lfo(voice, plan.lfoHz / rate, rate)
        val tone = Tone(m.getValue("GRIT"), rate)
        val raw = FloatArray(total)
        for (i in 0 until total) {
            if (i % LOOP_CANCEL_CHECK_SAMPLES == 0 && (cancelled() || Thread.currentThread().isInterrupted)) {
                throw java.util.concurrent.CancellationException("SIREN loop render no longer wanted")
            }
            raw[i] = tone.next(plan.baseHz * 2.0.pow(depth * lfo.next()))
        }
        Tide.bandLimit(raw, rate)
        val out = Dsp.decimate(raw, RATE)
        return out.copyOfRange(warm, warm + loops * plan.frames)
    }

    /**
     * Where to cut one loop out of a two-loop stretch: at the zero crossing
     * whose two neighbours are smallest, so the WAV starts on a crossing and
     * ends one sample before the same crossing. On the SURFACE the wrap is
     * seamless whatever the cut, since the stretch is periodic; on a pad the
     * one-shot then ends on the tone's own edge — a step no larger than the
     * steps the square makes twice a cycle, so not a click the ear can tell
     * from the tone.
     */
    internal fun bestCut(stretch: FloatArray, loopFrames: Int): Int {
        var best = 0
        var bestScore = Float.MAX_VALUE
        for (z in 1 until loopFrames) {
            val a = stretch[z - 1]
            val b = stretch[z]
            if (a * b > 0f) continue
            val score = max(abs(a), abs(b))
            if (score < bestScore) { bestScore = score; best = z }
        }
        return best
    }

    /**
     * One loop, levelled: the stretch is periodic, so the loop from the cut
     * is the stretch from there to its end and then from its start to the
     * cut — one loop rendered, not two. [cancelled] reaches
     * [synthesizeLoopStretch]'s own check.
     */
    internal fun renderLoop(voice: SirenVoice, macros: Map<String, Float>, cancelled: () -> Boolean = { false }): FloatArray {
        val (frames, _) = loopFrames(settled(macros, voice).getValue("RATE"))
        val stretch = synthesizeLoopStretch(voice, macros, loops = 1, cancelled)
        val cut = bestCut(stretch, frames)
        val loop = stretch.copyOfRange(cut, frames) + stretch.copyOfRange(0, cut)
        Dsp.levelTo(loop, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        return loop
    }

    fun render(voice: SirenVoice, macros: Map<String, Float> = emptyMap()): Snip {
        val m = settled(macros, voice)
        if (isLoop(m.getValue("HOLD"))) {
            return Snip(renderLoop(voice, m), channels = 1, sampleRate = RATE)
        }
        // U6: a pulse at 4x RATE, band-limited, then decimated — the fold-free
        // engines' contract, and GRIT's drive makes harmonics too.
        val rate = RATE * Dsp.OVERSAMPLE
        val raw = synthesize(voice, m, rate)
        Tide.bandLimit(raw, rate)
        val out = Dsp.decimate(raw, RATE)
        Dsp.levelTo(out, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }
}
