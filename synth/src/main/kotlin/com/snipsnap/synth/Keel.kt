package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/** Three sources that obey one gesture. BELLY was built after the owner heard it as its own sound (belly page, answered 2026-10-31). */
enum class KeelVoice { REDUCE, UPRIGHT, BELLY }

/**
 * KEEL: a bass that settles. The gesture is attack with character, the upper spectrum distils away, the body
 * fills in under the note, release. One settle clock `s(t) = 2^(-t / h)` (h set by SETTLE, 0.05..1.2 s) is
 * followed by every voice's spectrum, and WEIGHT's body (the note's own fundamental and first overtones, raised
 * together; see [weightBody]) grows into the space the harmonics leave. The settle is computed, not simulated:
 * an exponential with a half-life, nothing more.
 *
 *  - REDUCE is two-operator FM at ratio 1 whose index is `7 s (0.6 + 0.4 v) + 0.08`, so it distils to its carrier.
 *  - UPRIGHT is a plucked string ([Strings.Loop], retuned to the slide), a closing low-pass that follows the
 *    settle clock, and three fixed body modes that ring shorter than any note.
 *  - BELLY is a warm sine belly and a saw bite 9 cents sharp through a closing low-pass; the bite fades on the
 *    settle clock and the belly stays.
 *
 * Three rules were learnt in the Python prototype and each has a guard here:
 *  1. UPRIGHT's body rings shorter than the note (T60 at most 0.11 s), or it steals the pitch an octave low.
 *  2. WEIGHT raises the fundamental and overtones 2 to 4 together, so the note's own balance and pitch are kept:
 *     boosting overtones 2 to 4 alone read REDUCE an octave high at F#1.
 *  3. WEIGHT is added after GRIT, never driven, so the drive cannot square what it adds.
 *
 * HOLD under [LOOP_HOLD_AT] is the sustain stage: the envelope holds at full for HOLD seconds before it decays,
 * and the render stays under the classifier's 1.5 s line, so the note files TONAL. From [LOOP_HOLD_AT] HOLD is
 * the settled state as one seamless loop ([renderLoop], filed LOOP), and [renderHeld] is the same render with its
 * attack in front, which is what a keygroup zone plays ([Keys.keelPad]). The held state is driven, not decayed:
 * the envelope stays up, the settle clock stops just before the loop, and UPRIGHT's string is held by a bow, a
 * noise that repeats with the loop. The physics of a pluck does not continue into the loop.
 *
 * Every constant not backed by a measurement is a shape: chosen in the prototype, not heard. Design and
 * measurements: docs/superpowers/specs/2026-10-23-keel-bass-engine-design.md. The settle idea is drawn from
 * three engines of the MIT-licensed XO_OX Omnibus (Oaken's curing filter, Omega's distillation, Ogre's gravity),
 * read as research; nothing is ported.
 */
object Keel {
    const val MODEL_VERSION = 1
    const val MIDI_MIN = 24
    const val MIDI_MAX = 55
    const val DEFAULT_MIDI = 36
    const val TUNE_SEMITONES = 24

    /** HOLD from here is the settled-state loop ([renderLoop]). */
    const val LOOP_HOLD_AT = 0.85f

    /** The top of the sustain stage: a one-shot's hold, in seconds, stops here, where the loop region starts. */
    const val SUSTAIN_TOP = LOOP_HOLD_AT

    /** The most HOLD a roll reaches: short of the loop, because the top step is a choice and not a roll. */
    const val ROLL_HOLD_TOP = 0.8f

    /** The held loop's length: the whole number of the note's periods nearest this many seconds (spec section 10). */
    const val LOOP_SECONDS = 3.0

    /** The longest one-shot. `Classifier.LOOP_MIN_SECONDS` is private, so KEEL carries its own 1.5 s line. */
    const val MAX_SECONDS = 1.44f
    const val LOOP_MIN_SECONDS = 1.5f

    private const val RATE = Dsp.RATE
    private const val INTERNAL_RATE = RATE * Dsp.OVERSAMPLE
    private const val TAU = 2.0 * PI
    private const val OUTPUT_DC_HZ = 20f
    private const val ATTACK_SECONDS = 0.003f
    private const val SETTLE_MIN_SECONDS = 0.05f
    private const val SETTLE_MAX_SECONDS = 1.2f
    private const val LENGTH_MIN_SECONDS = 0.20f
    private const val LENGTH_MAX_SECONDS = 1.25f
    private const val TAIL_SHARE = 1.15
    private const val SLIDE_SEMITONES = 12.0
    private const val SOURCE_PEAK = 0.8f
    private const val FM_INDEX = 7.0
    private const val FM_FLOOR = 0.08
    private const val STRING_LOOP_HZ = 2200f
    private const val PICK_BURST_PERIODS = 2.0
    private const val PICK_LEVEL = 0.6f
    private const val BODY_MIX = 0.18f
    private const val RETUNE_BLOCK = 8
    private const val RETUNE_RATIO = 2e-5
    private const val SQRT_2 = 1.4142135f

    /** BELLY: the belly's level and its cube (Omnibus Overbite's sine shape), the bite's level and its detune (spec 6.3). */
    private const val BELLY_LEVEL = 0.75
    private const val BELLY_CUBE = 0.3
    private const val BITE_LEVEL = 0.65
    private const val BITE_CENTS = 9.0

    /** The bow's noise is seeded apart from the pick's (the seed of the note, with these bits flipped). */
    private const val BOW_SEED = 0x5B0E

    /** WEIGHT at 1, fully grown: the fundamental and overtones 2 to 4 rise by this many dB, together. */
    private const val WEIGHT_BOOST_DB = 9.0

    /** A missing overtone is floored at this fraction of the boosted fundamental when WEIGHT is full (overtones 2, 3, 4). */
    private val WEIGHT_FLOOR_DB = doubleArrayOf(-3.0, -6.0, -9.0)

    /** Where an overtone is absent, its floor takes the phase of the fundamental's multiple: a nudge of this size, re |C1|. */
    private const val WEIGHT_PHASE_NUDGE = 0.02
    private const val WEIGHT_LAST_OVERTONE = 4
    private val LN10_OVER_20 = ln(10.0) / 20.0

    /**
     * The runaway guard on the mix before the output stage: finite, and under this peak. The source enters GRIT at
     * a 0.8 peak and leaves it at most 1; WEIGHT at 1 then raises the fundamental and overtones 2 to 4 by up to
     * 9 dB and floors the missing ones, so a loud mix is expected and is not a fault. The worst measured over the
     * macro corners and 600 random settings is about 6.9 (a settled, held, driven note); the harness macro set at
     * WEIGHT .4 stays under the spec's original bar of 2 (section 14, checked in `KeelTest`). This bound is about
     * twice the worst measured, so it catches a blown-up estimate or a runaway filter and never a loud corner.
     * Everything after the mix is linear up to [Dsp.levelTo], which sets the loudness, so the mix's own level is
     * never heard.
     */
    internal const val RAW_PEAK_BOUND = 16f

    /** UPRIGHT held: the string's loop gain, and the bow that holds it (spec section 10; Oaken's bow is filtered noise). */
    private const val HELD_LOOP_GAIN = 0.98f
    private const val BOW_LEVEL = 0.04f
    private const val BOW_HZ = 900f

    /**
     * The bow's hair ([heldBow]): the noise over the locked bow, as the RMS of the beat it makes at the fundamental
     * against the steady note, the same at every pitch. It beats as the noise bow did, smaller: the loop's level moves
     * 1.0 dB at C1, 1.2 at C2 and 3.5 at A2 (house loudness, 200 ms windows), less in the low zones, whose level is
     * spread over more overtones. Heard: the owner chose it over none and .06 at C2 and A2, and heard no pump (bow
     * page, 2026-10-28).
     */
    internal const val BOW_HAIR = 0.15f

    /** The locked bow's harmonics stop here, four octaves of the bow filter's slope above it. */
    private const val BOW_TOP_HZ = 3600.0

    /** One cycle of the locked bow, tabulated: 37 points to its highest harmonic's cycle at C1, read linearly between. */
    private const val BOW_CYCLE_POINTS = 4096

    /** UPRIGHT held: the string's time constants in front of the loop, `period / (1 - g)` each, so the pluck is gone. */
    private const val HELD_STRING_TIME_CONSTANTS = 6.0

    /** The settle clock's half-lives in front of the loop: `s = 2^-10`, the settled note. */
    private const val HELD_SETTLE_HALF_LIVES = 10.0

    /**
     * The settle clock stops this long before the loop starts, so the seam's 256 frames, WEIGHT's two one-period
     * means (61 ms at C1) and the 20 Hz high-pass (8 ms time constant) all see the steady note the loop repeats.
     */
    private const val HELD_LEAD_SECONDS = 0.25

    /** Rendered past the loop's end, so the decimator's edge never reaches it. */
    private const val HELD_TAIL_SECONDS = 0.25

    /** A key zone is levelled on its first this-many seconds: what a short press plays, about a default one-shot's length. */
    const val KEY_LEVEL_SECONDS = 0.6

    /**
     * A key zone's loop sits at most this far under its first [KEY_LEVEL_SECONDS] (house loudness), lifted if lower.
     * It is the mean level of the bowed loops the owner heard as about right (keys page, 2026-10-26: C1 7.3 and C2
     * 7.7 dB under the attack). Those loops swung, and were lifted on their loudest window to 6 dB. The locked bow's
     * loop moves at most 3.5 dB, and lifted on its loudest window to this gap it was heard as about right again (bow
     * page, 2026-10-28, C2 and A2).
     */
    const val KEY_SUSTAIN_GAP_DB = 7.5

    /** [keyEnvelope]: [Loudness.of]'s window and its partial low cut (`x - 0.7 lowpass120(x)`). */
    private const val KEY_ENVELOPE_SECONDS = 0.2
    private const val KEY_ENVELOPE_LOW_HZ = 120.0
    private const val KEY_ENVELOPE_LOW_SHARE = 0.7

    /** The lift's last stretch before the settle clock stops is a straight line to the loop's gain, this long. */
    private const val KEY_JOIN_SECONDS = 0.2

    /** Fixed-Hz body modes (Hz, gain, T60 seconds): a body, not a partial set. Each rings under 0.11 s. */
    private val BODY_MODES = listOf(Triple(190f, 0.7f, 0.11f), Triple(540f, 0.4f, 0.08f), Triple(1050f, 0.25f, 0.06f))

    fun macrosFor(voice: KeelVoice): List<MacroSpec> {
        val settle: Float
        val weight: Float
        val grit: Float
        val length: Float
        when (voice) {
            KeelVoice.REDUCE -> { settle = .50f; weight = .40f; grit = .20f; length = .50f }
            KeelVoice.UPRIGHT -> { settle = .45f; weight = .35f; grit = .15f; length = .55f }
            KeelVoice.BELLY -> { settle = .50f; weight = .45f; grit = .30f; length = .50f }
        }
        return listOf(MacroSpec("TUNE", .5f, .5f), MacroSpec("SETTLE", settle, .5f),
            MacroSpec("WEIGHT", weight, 0f), MacroSpec("GRIT", grit, 0f),
            MacroSpec("SLIDE", .5f, .5f), MacroSpec("LENGTH", length, .5f), MacroSpec("HOLD", 0f, 0f))
    }

    fun defaults(voice: KeelVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    /** Every macro finite and in 0..1, unknown keys dropped, absent ones at the voice's default. */
    internal fun settled(voice: KeelVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).mapValues { (key, default) -> (macros[key]?.takeIf { it.isFinite() } ?: default).coerceIn(0f, 1f) }

    /** A roll near [near] (or near the defaults). HOLD stays under [ROLL_HOLD_TOP], in the sustain stage. */
    fun scramble(voice: KeelVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> {
        val rolled = Dsp.scrambleNear(settled(voice, if (near?.engine == KeelPatch.ENGINE) near.macros else emptyMap()), temperature, random)
        return rolled + ("HOLD" to rolled.getValue("HOLD").coerceAtMost(ROLL_HOLD_TOP))
    }

    /** The played note: the patch's root, moved by TUNE (plus or minus 12 semitones), held inside the register. */
    fun midiFor(@Suppress("UNUSED_PARAMETER") voice: KeelVoice, tune: Float, midi: Int? = null): Int =
        ((midi ?: DEFAULT_MIDI) + (((tune.takeIf { it.isFinite() } ?: .5f).coerceIn(0f, 1f) - .5f) * TUNE_SEMITONES).roundToInt())
            .coerceIn(MIDI_MIN, MIDI_MAX)

    fun frequencyFor(voice: KeelVoice, tune: Float, midi: Int? = null): Float = Keys.midiHz(midiFor(voice, tune, midi))

    /** Whether HOLD asks for the settled-state loop. */
    fun isLoop(hold: Float): Boolean = hold >= LOOP_HOLD_AT

    /**
     * LOOP for the held loop (always past the classifier's 1.5 s line: a loop is [LOOP_SECONDS] long), otherwise
     * TONAL, or LOOP if a one-shot ever ran past that line (it cannot: [MAX_SECONDS] is under it).
     */
    fun drumClassFor(voice: KeelVoice, macros: Map<String, Float> = emptyMap()): DrumClass {
        val m = settled(voice, macros)
        if (isLoop(m.getValue("HOLD"))) return DrumClass.LOOP
        return if (oneShotFrames(m).toFloat() / RATE > LOOP_MIN_SECONDS) DrumClass.LOOP else DrumClass.TONAL
    }

    // ---- the laws ------------------------------------------------------------------------------------------

    /** The -60 dB time of the amplitude envelope. */
    internal fun lengthSeconds(m: Map<String, Float>): Float = Dsp.expMap(m.getValue("LENGTH"), LENGTH_MIN_SECONDS, LENGTH_MAX_SECONDS)

    /** The settle clock's half-life: the speed at which harmonics leave and the body fills in. LENGTH never touches it. */
    internal fun halfLifeSeconds(m: Map<String, Float>): Float = Dsp.expMap(m.getValue("SETTLE"), SETTLE_MIN_SECONDS, SETTLE_MAX_SECONDS)

    /**
     * SLIDE's start offset in semitones, bipolar and linear around 0.5 (no slide). The spec names
     * `Dsp.around(SLIDE, -12, 0, 12)`, but that map is exponential on each side and a centre of 0 gives
     * `ln(0 / -12)`: NaN at the default. The prototype's own pinned-centre map is linear, so this is too.
     */
    internal fun slideOffset(slide: Float): Double = (slide.coerceIn(0f, 1f) - 0.5) * 2.0 * SLIDE_SEMITONES

    /** Seconds a one-shot's envelope holds at full before it decays: HOLD, up to [SUSTAIN_TOP] (the loop starts there). */
    internal fun sustainSeconds(m: Map<String, Float>): Float = m.getValue("HOLD").coerceAtMost(SUSTAIN_TOP)

    internal fun seconds(m: Map<String, Float>): Double =
        min(MAX_SECONDS.toDouble(), TAIL_SHARE * lengthSeconds(m) + sustainSeconds(m))

    /** The one-shot's frame count at [RATE], already settled macros. */
    internal fun oneShotFrames(m: Map<String, Float>): Int = (seconds(m) * RATE).roundToInt()

    /** GRIT's drive: k runs 1..24, scaled by the touch. */
    internal fun driveFor(grit: Float, velocity: Float): Float = Dsp.lin(grit, 1f, 24f) * (0.5f + 0.5f * velocity)

    /** UPRIGHT's pick low-pass: 2 kHz soft, 4 kHz hard. */
    internal fun pickHz(velocity: Float): Float = (2f + 2f * velocity) * 1000f

    /** The string loop's gain: alone it reaches -60 dB at three times the envelope's length. */
    internal fun loopGain(lengthSeconds: Float, hz: Double): Float =
        10.0.pow(-3.0 / (3.0 * lengthSeconds * hz)).coerceIn(0.85, 0.9995).toFloat()

    // ---- the held loop's plan ----------------------------------------------------------------------------

    /**
     * The held loop: [frames] frames at [RATE] holding exactly [periods] periods of the note at [hz]. The note bends
     * to the loop, not the loop to the note: [hz] is `periods * RATE / frames`, at most half a frame in 3 s off the
     * note (0.007 cents), so the phase, the bow and WEIGHT's one-period means all repeat with the loop.
     */
    internal data class LoopPlan(val frames: Int, val periods: Int, val hz: Double)

    internal fun planLoop(noteHz: Double): LoopPlan {
        val periods = Math.round(LOOP_SECONDS * noteHz).toInt().coerceAtLeast(1)
        val frames = Math.round(periods * RATE / noteHz).toInt()
        return LoopPlan(frames, periods, periods.toDouble() * RATE / frames)
    }

    /**
     * Where a held render's loop starts, in frames at [RATE]: ten half-lives of the settle clock, and for UPRIGHT at
     * least six of the string's time constants (`period / (1 - g)`, 1.5 s at C1), so the pluck has died away; then
     * [HELD_LEAD_SECONDS], over which the settle clock has stopped.
     */
    internal fun heldLoopStart(voice: KeelVoice, m: Map<String, Float>, hz: Double): Int {
        var warm = HELD_SETTLE_HALF_LIVES * halfLifeSeconds(m)
        if (voice == KeelVoice.UPRIGHT) warm = max(warm, HELD_STRING_TIME_CONSTANTS / hz / (1.0 - HELD_LOOP_GAIN))
        return Math.round((warm + HELD_LEAD_SECONDS) * RATE).toInt()
    }

    // ---- the render ----------------------------------------------------------------------------------------

    /**
     * Ablations for the test that proves a guard by removing it alone (the house rule: a guard is proven by
     * reverting it and watching its named test fail), and the bow's hair, for its audition and its tests. Production
     * is the default of all four: UPRIGHT's body and its bow.
     */
    internal data class Options(val bodyT60Scale: Float = 1f, val bodyMix: Float = BODY_MIX,
        val bodyLowGain: Float = BODY_MODES[0].second, val bowHair: Float = BOW_HAIR)

    /**
     * The mix at the internal rate before the output stage, and the note it was made for. A held render also carries
     * where its loop starts and how long it is, in frames at [RATE]; a one-shot carries zeros.
     */
    internal class Synthesis(val raw: FloatArray, val midi: Int, val rootHz: Double, val frames: Int,
        val loopStart: Int = 0, val loopFrames: Int = 0)

    /** [Synthesis.raw] copied before the in-place band limit, and what the output stage made of it before it was levelled. */
    internal class Probe(val raw: FloatArray, val conditioned: FloatArray, val midi: Int, val rootHz: Double)

    /** The pitch track: Hz per internal sample, and the phase in cycles of the note. */
    internal class Track(val f: DoubleArray, val phase: DoubleArray)

    internal fun track(hz: Double, offset: Double, n: Int): Track {
        // Log-domain glide that lands on the note: 1% of the offset left after 30 to 280 ms.
        val tau = (0.03 + 0.25 * abs(offset) / SLIDE_SEMITONES) / 4.6
        val f = DoubleArray(n)
        val phase = DoubleArray(n)
        var acc = 0.0
        for (i in 0 until n) {
            val now = if (offset == 0.0) hz else hz * 2.0.pow(offset / 12.0 * exp(-(i.toDouble() / INTERNAL_RATE) / tau))
            f[i] = now
            acc += now / INTERNAL_RATE
            phase[i] = acc
        }
        return Track(f, phase)
    }

    internal fun settleCurve(n: Int, halfLife: Double): DoubleArray =
        DoubleArray(n) { 2.0.pow(-(it.toDouble() / INTERNAL_RATE) / halfLife) }

    /**
     * [held] renders the held state instead of a one-shot: the pitch fitted to the loop ([planLoop]), no slide, the
     * envelope up after its attack, the settle clock stopped [HELD_LEAD_SECONDS] before the loop, and UPRIGHT bowed.
     * The render runs to [HELD_TAIL_SECONDS] past the loop's end. [cancelled] is asked as often as an interrupt is.
     */
    internal fun synthesize(voice: KeelVoice, macros: Map<String, Float> = emptyMap(), midi: Int? = null,
        velocity: Float = 1f, options: Options = Options(), held: Boolean = false,
        cancelled: () -> Boolean = { false }): Synthesis {
        stopIfUnwanted(cancelled)
        require(midi == null || midi in MIDI_MIN..MIDI_MAX) { "KEEL MIDI root must be $MIDI_MIN..$MIDI_MAX" }
        require(velocity.isFinite() && velocity in 0f..1f) { "KEEL velocity must be 0..1" }
        val m = settled(voice, macros)
        val note = midiFor(voice, m.getValue("TUNE"), midi)
        val plan = if (held) planLoop(Keys.midiHz(note).toDouble()) else null
        val hz = plan?.hz ?: Keys.midiHz(note).toDouble()
        val length = lengthSeconds(m)
        val loopStart = if (plan != null) heldLoopStart(voice, m, hz) else 0
        val frames = if (plan != null) loopStart + plan.frames + Math.round(HELD_TAIL_SECONDS * RATE).toInt() else oneShotFrames(m)
        val n = frames * Dsp.OVERSAMPLE
        val halfLife = halfLifeSeconds(m).toDouble()
        val s = if (plan == null) settleCurve(n, halfLife) else {
            val stop = (loopStart - Math.round(HELD_LEAD_SECONDS * RATE).toInt()) * Dsp.OVERSAMPLE
            val stopped = 2.0.pow(-(stop.toDouble() / INTERNAL_RATE) / halfLife)
            DoubleArray(n) { if (it < stop) 2.0.pow(-(it.toDouble() / INTERNAL_RATE) / halfLife) else stopped }
        }
        val tr = track(hz, if (plan == null) slideOffset(m.getValue("SLIDE")) else 0.0, n)
        val seed = Dsp.seedFor("KEEL", MODEL_VERSION, voice, note)

        val source = when (voice) {
            KeelVoice.REDUCE -> reduceSource(s, tr, velocity, cancelled)
            KeelVoice.UPRIGHT -> uprightSource(s, tr, hz, length, velocity, seed, options,
                bowFrames = (plan?.frames ?: 0) * Dsp.OVERSAMPLE, cancelled = cancelled)
            KeelVoice.BELLY -> bellySource(s, tr, velocity, biteRatio(plan), cancelled)
        }
        // Every voice meets GRIT at the same level, so the drive law means one thing.
        var peak = 0f
        for (v in source) peak = max(peak, abs(v))
        val norm = SOURCE_PEAK / max(1e-9f, peak)
        val k = driveFor(m.getValue("GRIT"), velocity).toDouble()
        val shaped = tanh(k)
        for (i in source.indices) source[i] = (tanh(k * source[i] * norm) / shaped).toFloat()

        // WEIGHT is added after GRIT, never driven, and before the envelope: the body decays with the note it belongs to.
        val body = weightBody(source, tr, s, m.getValue("WEIGHT"), cancelled)
        val env = Dsp.Env(ATTACK_SECONDS, length, holdSeconds = if (plan == null) sustainSeconds(m) else Float.POSITIVE_INFINITY)
        val raw = FloatArray(n)
        for (i in 0 until n) {
            if (i % 8192 == 0) stopIfUnwanted(cancelled)
            raw[i] = body[i] * env.at(i.toFloat() / INTERNAL_RATE)
        }
        return Synthesis(raw, note, hz, frames, loopStart, plan?.frames ?: 0)
    }

    /** REDUCE: two-operator FM at ratio 1 whose index rides the settle clock, so it distils to its carrier. */
    private fun reduceSource(s: DoubleArray, tr: Track, velocity: Float, cancelled: () -> Boolean): FloatArray {
        val index0 = FM_INDEX * (0.6 + 0.4 * velocity)
        return FloatArray(s.size) { i ->
            if (i % 8192 == 0) stopIfUnwanted(cancelled)
            val modulator = sin(TAU * tr.phase[i])
            sin(TAU * tr.phase[i] + (index0 * s[i] + FM_FLOOR) * modulator).toFloat()
        }
    }

    /**
     * BELLY's bite against its belly, as a frequency ratio. A one-shot's bite is [BITE_CENTS] sharp. A held loop needs
     * a whole number of beats in it, so the bite runs `beats` cycles more than the note's [LoopPlan.periods] over the
     * loop, the nearest whole count to the nominal detune's and at least one (the house "retune until the seam
     * closes"). Under about D#1 one beat in 3 s is more than 9 cents (17.6 at C1): the low held notes beat faster.
     */
    internal fun biteRatio(plan: LoopPlan?): Double {
        val nominal = 2.0.pow(BITE_CENTS / 1200.0)
        if (plan == null) return nominal
        val beats = max(1L, Math.round(plan.periods * (nominal - 1.0)))
        return 1.0 + beats.toDouble() / plan.periods
    }

    /**
     * BELLY (spec 6.3): `0.75 (sine + 0.3 sine^3) + 0.65 (0.6 + 0.4 v)(0.25 + 0.75 s) LP(saw)`. The saw is PolyBLEP,
     * at [biteRatio] times the note's phase, through two one-poles (12 dB/octave) at `f (2 + 28 s)`, 80 to 7000 Hz:
     * the bite closes and fades as the note settles, and the belly stays. Ported from the Python harness clip the owner
     * heard (`keel_proto.voice_belly`), whose low-pass stepped its cutoff every 128 samples; this one moves per sample.
     */
    private fun bellySource(s: DoubleArray, tr: Track, velocity: Float, biteRatio: Double, cancelled: () -> Boolean): FloatArray {
        val one = Dsp.OnePole(INTERNAL_RATE)
        val two = Dsp.OnePole(INTERNAL_RATE)
        val bite = BITE_LEVEL * (0.6 + 0.4 * velocity)
        return FloatArray(s.size) { i ->
            if (i % 8192 == 0) stopIfUnwanted(cancelled)
            val sine = sin(TAU * tr.phase[i])
            val p = tr.phase[i] * biteRatio
            val t = p - floor(p)
            val saw = (2.0 * t - 1.0 - polyBlep(t, tr.f[i] * biteRatio / INTERNAL_RATE)).toFloat()
            val cutoff = (tr.f[i] * (2.0 + 28.0 * s[i])).coerceIn(80.0, 7000.0).toFloat()
            (BELLY_LEVEL * (sine + BELLY_CUBE * sine * sine * sine) + bite * (0.25 + 0.75 * s[i]) * two.lp(one.lp(saw, cutoff), cutoff)).toFloat()
        }
    }

    /** The PolyBLEP correction at a falling edge (SIREN's, copied: it is private there). [dt] is the phase step this sample. */
    private fun polyBlep(t: Double, dt: Double): Double = when {
        t < dt -> { val x = t / dt; x + x - x * x - 1.0 }
        t > 1.0 - dt -> { val x = (t - 1.0) / dt; x * x + x + x + 1.0 }
        else -> 0.0
    }

    /**
     * UPRIGHT: a pluck of two periods of linearly fading noise (low-passed at the pick), a [Strings.Loop]
     * retuned to the slide, a closing 12 dB/octave low-pass at `f (1.5 + 45 s)`, and a body of three fixed modes
     * mixed at 0.18. A held render ([bowFrames] above 0, the loop's length at the internal rate) raises the loop
     * gain to [HELD_LOOP_GAIN] and bows the string from the first sample with [heldBow], exactly one loop long and
     * tiled. Because the bow repeats with the loop and the string does not move, the string settles into a state
     * that repeats with the loop: the house's periodic driver.
     */
    private fun uprightSource(s: DoubleArray, tr: Track, hz: Double, length: Float, velocity: Float, seed: Int, options: Options,
        bowFrames: Int = 0, cancelled: () -> Boolean = { false }): FloatArray {
        val n = s.size
        val burstLength = (PICK_BURST_PERIODS * INTERNAL_RATE / tr.f[0]).toInt()
        val noise = Dsp.Noise(seed)
        val pick = Dsp.TptSvf(INTERNAL_RATE)
        val pickCutoff = pickHz(velocity)
        val burst = FloatArray(burstLength) { i ->
            val taper = 1f - i.toFloat() / (burstLength - 1)
            pick.process(noise.next() * taper, pickCutoff, SQRT_2)
            pick.low * PICK_LEVEL
        }

        // The ring is sized for the lowest pitch of the glide: retune can only shorten the loop it was built with.
        var lowest = Double.MAX_VALUE
        for (v in tr.f) lowest = min(lowest, v)
        val built = Strings.tune((lowest * 0.99).toFloat(), STRING_LOOP_HZ, INTERNAL_RATE)
        val gain = if (bowFrames > 0) HELD_LOOP_GAIN else loopGain(length, hz)
        val loop = Strings.Loop(built.n, built.a, gain, STRING_LOOP_HZ, INTERNAL_RATE)
        var tuned = tr.f[0]
        loop.retune(tuned.toFloat())
        val bow = if (bowFrames > 0) heldBow(bowFrames, hz, burst, seed, options.bowHair) else null
        val string = FloatArray(n)
        var peak = 0f
        for (i in 0 until n) {
            if (i % 8192 == 0) stopIfUnwanted(cancelled)
            if (i % RETUNE_BLOCK == 0 && abs(tr.f[i] / tuned - 1.0) > RETUNE_RATIO) {
                tuned = tr.f[i]
                loop.retune(tuned.toFloat())
            }
            var drive = if (i < burstLength) burst[i] else 0f
            if (bow != null) drive += bow[i % bow.size]
            val y = loop.next(drive)
            string[i] = y
            peak = max(peak, abs(y))
        }
        for (i in 0 until n) string[i] /= max(1e-9f, peak)

        // The curing low-pass: from bright to "the fundamental and a little second" as s falls.
        val one = Dsp.OnePole(INTERNAL_RATE)
        val two = Dsp.OnePole(INTERNAL_RATE)
        val cured = FloatArray(n)
        for (i in 0 until n) {
            val cutoff = (tr.f[i] * (1.5 + 45.0 * s[i])).coerceIn(60.0, 8000.0).toFloat()
            cured[i] = two.lp(one.lp(string[i], cutoff), cutoff)
        }

        // The prototype compensated each mode by sin(theta) so its peak did not depend on its frequency; Modes.ring
        // does not, and uncompensated the 190 Hz mode would dominate the 1050 Hz one by about 5x at this rate.
        val modes = BODY_MODES.mapIndexed { k, (modeHz, gain, t60) ->
            val level = if (k == 0) options.bodyLowGain else gain
            Modes.fixed(modeHz, level * sin(TAU * modeHz / INTERNAL_RATE).toFloat(), t60 * options.bodyT60Scale)
        }
        val body = Modes.ring(cured, 1f, modes, INTERNAL_RATE)
        var bodyPeak = 0f
        for (v in body) bodyPeak = max(bodyPeak, abs(v))
        return FloatArray(n) { i -> (1f - options.bodyMix) * cured[i] + options.bodyMix * (body[i] / max(1e-9f, bodyPeak)) }
    }

    /**
     * UPRIGHT's held bow, [frames] at the internal rate (one loop, holding the plan's whole periods of [hz]): what a
     * bow's stick-slip does, a drive locked to the string's own period, with a trace of hair noise over it.
     *
     * The first bow was the noise alone, at [BOW_LEVEL] through [BOW_HZ]. Tiled one loop long, its energy sits on
     * lines 1/3 Hz apart, and the string's resonance at the fundamental, `f (1 - g) / pi` wide, takes in more of them
     * the higher the note: their random sum beats, and the loop repeats the beat. The owner heard it as a pump (keys
     * page, 2026-10-26): the level swung 2.6 dB at C1 and 12.5 dB at A2, every 3 s.
     *
     * The locked part is every harmonic of [hz] up to [BOW_TOP_HZ] at the bow filter's 12 dB/octave level, its
     * fundamental carrying the power the noise bow's lines put into the resonance on average, so the held level is
     * the one heard. Each harmonic starts in the pluck's own phase (the [burst]'s projection on it), so the bow
     * builds the note the pluck left instead of cancelling it on the way in. The hair is the old bow's noise,
     * low-passed around the loop so it closes, scaled so its beat against the steady note is [hair] (RMS, at the
     * fundamental) at every pitch: the lines off the harmonic carry `x coth x - 1` of the resonance's peak power,
     * `x = (1 - g) f L / 2` for a loop of L seconds. Deterministic: the hair's noise is seeded.
     */
    private fun heldBow(frames: Int, hz: Double, burst: FloatArray, seed: Int, hair: Float): FloatArray {
        val x = (1.0 - HELD_LOOP_GAIN) * hz * frames / INTERNAL_RATE / 2.0
        val offLines = x / tanh(x) - 1.0
        // Dsp.Noise is uniform on -1..1: variance 1/3, and each of a loop's DFT lines carries 4/3 of it over frames.
        val fundamental = 2.0 * sqrt(1.0 / 3.0) * BOW_LEVEL * sqrt((1.0 + offLines) / frames)

        val cycle = DoubleArray(BOW_CYCLE_POINTS + 1)
        for (k in 1..max(1, (BOW_TOP_HZ / hz).toInt())) {
            val w = TAU * k * hz / INTERNAL_RATE
            var re = 0.0
            var im = 0.0
            for (i in burst.indices) {
                re += burst[i] * cos(w * i)
                im -= burst[i] * sin(w * i)
            }
            val phase = atan2(im, re)
            val level = fundamental / sqrt(1.0 + (k * hz / BOW_HZ).pow(4))
            for (p in 0..BOW_CYCLE_POINTS) cycle[p] += level * cos(TAU * k * p / BOW_CYCLE_POINTS + phase)
        }

        // Whole periods in the loop, so the cycle's position at sample i, (periods i mod frames) / frames, closes.
        val periods = Math.round(hz * frames / INTERNAL_RATE)
        val noise = Dsp.Noise(seed xor BOW_SEED).let { n -> FloatArray(frames) { n.next() } }
        val filter = Dsp.TptSvf(INTERNAL_RATE)
        val hairLevel = hair * BOW_LEVEL * sqrt((1.0 + offLines) / offLines)
        val bow = FloatArray(frames)
        // The filter runs round the loop twice and the second pass is kept, so the hair closes on itself too.
        for (pass in 0..1) for (i in 0 until frames) {
            filter.process(noise[i], BOW_HZ, SQRT_2)
            if (pass == 1) {
                val at = (periods * i % frames).toDouble() / frames * BOW_CYCLE_POINTS
                val p = at.toInt()
                val locked = cycle[p] + (cycle[p + 1] - cycle[p]) * (at - p)
                bow[i] = (locked + hairLevel * filter.low).toFloat()
            }
        }
        return bow
    }

    /**
     * WEIGHT: the body fills in under the note (spec section 9). [source] is the post-GRIT source, [s] the settle
     * curve and [weight] the macro, 0..1; the result is the source with the body added, and [source] itself,
     * untouched, when [weight] is 0.
     *
     * The note's fundamental and overtones 2 to 4 are each read as a complex amplitude `C_k` by synchronous
     * demodulation on the note's own phase track: `x e^(-2 pi i k phase)` through a moving average exactly one
     * period of the note long, taken twice. A one-period mean nulls every other harmonic while the pitch is
     * steady, which a one-pole would not (it leaks the neighbours). The period is per sample,
     * `P(i) = round(INTERNAL_RATE / f(i))`, so the mean follows SLIDE's glide; for the first `P(i)` samples the
     * window is the samples so far. `A_k = 2 |C_k|`.
     *
     * With `w(i) = WEIGHT (0.5 + 0.5 (1 - s(i)))` (half strength at the attack, full as the settle removes the
     * top) and `boost = 10^(9 dB w / 20)`: the fundamental becomes `A_1 boost`, and each overtone's target is
     * `max(A_k boost, floor_k w A_1 boost)` with floors of -3, -6 and -9 dB for overtones 2, 3 and 4. Only the
     * difference is added, in the overtone's own phase `u_k = C_k + 0.02 |C_1| (C_1 / |C_1|)^k`, so where the
     * note has no k-th overtone the floor lands in phase with the fundamental's k-th multiple and nothing
     * cancels. Overtones 1 to 4 rise together: the note keeps its own balance and so its pitch (boosting 2 to 4
     * alone read REDUCE an octave high at F#1).
     *
     * Everything is in Double. Cost: one sin and one cos per sample, then complex products for the overtones. It is
     * streamed, so its memory is sixteen rings one period long, whatever the note's length (a held zone runs 15 s).
     */
    internal fun weightBody(source: FloatArray, tr: Track, s: DoubleArray, weight: Float, cancelled: () -> Boolean = { false }): FloatArray {
        if (weight <= 0f) return source
        val n = source.size
        require(tr.f.size == n && tr.phase.size == n && s.size == n) { "KEEL WEIGHT needs one pitch, phase and settle value per sample" }
        var longest = 1
        for (f in tr.f) longest = max(longest, (INTERNAL_RATE / f).roundToInt())
        // Per overtone: the first mean's real and imaginary parts, then the second's.
        val means = Array(4 * WEIGHT_LAST_OVERTONE) { RunningMean(longest) }
        val floors = DoubleArray(WEIGHT_LAST_OVERTONE - 1) { 10.0.pow(WEIGHT_FLOOR_DB[it] / 20.0) }
        val out = FloatArray(n)
        for (i in 0 until n) {
            if (i % 8192 == 0) stopIfUnwanted(cancelled)
            val p = max(1, (INTERNAL_RATE / tr.f[i]).roundToInt())
            val x = source[i]
            val angle = TAU * tr.phase[i]
            val cos1 = cos(angle)
            val sin1 = sin(angle)

            // The fundamental: its magnitude and its unit phasor, kept for every overtone.
            val re1 = means[2].next(means[0].next(x * cos1, i, p), i, p)
            val im1 = means[3].next(means[1].next(-x * sin1, i, p), i, p)
            val magnitude = sqrt(re1 * re1 + im1 * im1)
            val inverse = 1.0 / max(magnitude, 1e-12)
            val unitRe = re1 * inverse
            val unitIm = im1 * inverse
            val grown = weight * (0.5 + 0.5 * (1.0 - s[i]))
            val boost = exp(LN10_OVER_20 * WEIGHT_BOOST_DB * grown)
            var added = (boost - 1.0) * 2.0 * magnitude * (unitRe * cos1 - unitIm * sin1)

            // e^(i k theta), advanced from k - 1 by one complex product.
            var cosK = cos1
            var sinK = sin1
            for (k in 2..WEIGHT_LAST_OVERTONE) {
                val c = cosK
                cosK = c * cos1 - sinK * sin1
                sinK = sinK * cos1 + c * sin1
                val m = 4 * (k - 1)
                val re = means[m + 2].next(means[m].next(x * cosK, i, p), i, p)
                val im = means[m + 3].next(means[m + 1].next(-x * sinK, i, p), i, p)
                val own = 2.0 * sqrt(re * re + im * im)
                val target = max(own * boost, floors[k - 2] * grown * 2.0 * magnitude * boost)
                val extra = max(target - own, 0.0)
                if (extra == 0.0) continue
                // (C_1 / |C_1|)^k, then the overtone's own phase with a small push toward the fundamental's multiple.
                var powRe = unitRe
                var powIm = unitIm
                for (j in 2..k) {
                    val t = powRe * unitRe - powIm * unitIm
                    powIm = powRe * unitIm + powIm * unitRe
                    powRe = t
                }
                val uRe = re + WEIGHT_PHASE_NUDGE * magnitude * powRe
                val uIm = im + WEIGHT_PHASE_NUDGE * magnitude * powIm
                val norm = max(sqrt(uRe * uRe + uIm * uIm), 1e-12)
                added += extra * (uRe * cosK - uIm * sinK) / norm
            }
            out[i] = (x + added).toFloat()
        }
        return out
    }

    /**
     * The mean of the last `p` values, one sample at a time: prefix sums kept in a ring [longest] + 1 long, so the
     * memory is one period and not the note, and the mean of every value so far while fewer than `p` exist.
     */
    private class RunningMean(longest: Int) {
        private val sums = DoubleArray(longest + 1)
        private var total = 0.0

        /** Takes the [i]th value [x] and returns the mean ending at it. */
        fun next(x: Double, i: Int, p: Int): Double {
            total += x
            sums[(i + 1) % sums.size] = total
            return if (i + 1 >= p) (total - sums[(i + 1 - p) % sums.size]) / p else total / (i + 1)
        }
    }

    /**
     * The house output stage (MERCURY's, copied: its `condition` is private): the band limit at the internal rate,
     * the decimator, the mean taken out, then a 20 Hz one-pole high-pass. [raw] is overwritten.
     */
    private fun condition(raw: FloatArray): FloatArray {
        Tide.bandLimit(raw, INTERNAL_RATE)
        val out = Dsp.decimate(raw, RATE)
        var mean = 0.0
        for (v in out) mean += v
        val mm = (mean / out.size.coerceAtLeast(1)).toFloat()
        val hp = Dsp.OnePole(RATE)
        for (i in out.indices) {
            val x = out[i] - mm
            out[i] = x - hp.lp(x, OUTPUT_DC_HZ)
        }
        return out
    }

    /** [synthesize] with the pre-band-limit buffer kept, for the tests that read it. */
    internal fun probe(voice: KeelVoice, macros: Map<String, Float> = emptyMap(), midi: Int? = null,
        velocity: Float = 1f, options: Options = Options()): Probe {
        val made = synthesize(voice, macros, midi, velocity, options)
        return Probe(made.raw.copyOf(), condition(made.raw), made.midi, made.rootHz)
    }

    /** The one-shot, or from [LOOP_HOLD_AT] the held loop ([renderLoop]). */
    fun render(voice: KeelVoice, macros: Map<String, Float> = emptyMap(), midi: Int? = null, velocity: Float = 1f): Snip {
        if (isLoop(settled(voice, macros).getValue("HOLD"))) return Snip(renderLoop(voice, macros, midi, velocity), channels = 1, sampleRate = RATE)
        val made = synthesize(voice, macros, midi, velocity)
        checkRaw(made.raw)
        val out = condition(made.raw)
        Dsp.levelTo(out, RATE, Dsp.MELODIC_LOUDNESS_TARGET * velocity.pow(.35f))
        Dsp.fadeTail(out)
        stopIfUnwanted()
        return Snip(out, channels = 1, sampleRate = RATE)
    }

    /**
     * A held note at the output rate, unlevelled: the attack and warm-up, then exactly one loop, which runs from
     * [loopStart] to the end of [audio]. [seam] is [Keys.seamError] there, against the cycle that really preceded it.
     */
    internal class HeldNote(val audio: FloatArray, val loopStart: Int, val seam: Double)

    /** The held state with its attack in front (see [synthesize]'s `held`). Not levelled, and no fade: it loops. */
    internal fun renderHeld(voice: KeelVoice, macros: Map<String, Float> = emptyMap(), midi: Int? = null,
        velocity: Float = 1f, cancelled: () -> Boolean = { false }, options: Options = Options()): HeldNote {
        val made = synthesize(voice, macros, midi, velocity, options, held = true, cancelled = cancelled)
        checkRaw(made.raw)
        val audio = condition(made.raw).copyOf(made.loopStart + made.loopFrames)
        stopIfUnwanted(cancelled)
        return HeldNote(audio, made.loopStart, Keys.seamError(audio, made.loopStart))
    }

    /**
     * The settled state as one seamless loop at the note's pitch (fitted to whole periods, [planLoop]), levelled as a
     * one-shot is. SLIDE and LENGTH play no part. A loop that does not close throws: a click shipped silently is the
     * worse failure.
     */
    fun renderLoop(voice: KeelVoice, macros: Map<String, Float> = emptyMap(), midi: Int? = null, velocity: Float = 1f,
        cancelled: () -> Boolean = { false }): FloatArray {
        val held = renderHeld(voice, macros, midi, velocity, cancelled)
        val note = midiFor(voice, settled(voice, macros).getValue("TUNE"), midi)
        Keys.requireSeam("KEEL $voice at MIDI $note", held.audio, held.loopStart)
        val loop = held.audio.copyOfRange(held.loopStart, held.audio.size)
        Dsp.levelTo(loop, RATE, Dsp.MELODIC_LOUDNESS_TARGET * velocity.pow(.35f))
        return loop
    }

    /**
     * One keygroup zone ([Keys.keelPad]): the held note at full touch, with its loop lifted to [KEY_SUSTAIN_GAP_DB]
     * under the attack if it sits lower, then levelled on its first [KEY_LEVEL_SECONDS] to the house target, so short
     * presses match across zones.
     *
     * The lift is for UPRIGHT. Its bow ([heldBow], at the level the loop was heard) leaves the sustain 19 dB under the
     * pluck at C1 and 9 to 11 dB from F#2 up (house loudness, default macros), so a held low key sounded like the note
     * dying; a louder bow evens that out only by driving GRIT harder, which changes the loop's sound. So the note is
     * raised after the fact, by a gain that only rises: wherever the note's own level ([keyEnvelope]) has fallen under
     * the lifted sustain, the gain holds it there, up to the loop's lift. The pluck is not raised, the bow's hair in
     * the loop is not flattened, and the loop gets one constant gain, so its seam is untouched. (A gain modelled on the
     * string's time constant was tried first: the curing low-pass takes the attack's brightness away at the settle
     * half-life, much faster, and the note dipped 10 dB at C1 and swelled back.) REDUCE's loop sits at most 5.8 dB
     * under its attack at any measured setting and is never lifted; a loop above its attack is never lowered (the
     * body filling in is the gesture).
     */
    internal fun renderKey(voice: KeelVoice, macros: Map<String, Float> = emptyMap(), midi: Int? = null,
        cancelled: () -> Boolean = { false }, options: Options = Options()): HeldNote {
        val held = renderHeld(voice, macros, midi, 1f, cancelled, options)
        val audio = held.audio
        val head = min(audio.size, (KEY_LEVEL_SECONDS * RATE).roundToInt())
        val attack = Loudness.of(Snip(audio.copyOf(head), channels = 1, sampleRate = RATE)).toDouble()
        val loop = Loudness.of(Snip(audio.copyOfRange(held.loopStart, audio.size), channels = 1, sampleRate = RATE)).toDouble()
        val gapDb = 20.0 * log10(attack / max(loop, 1e-9))
        if (voice == KeelVoice.UPRIGHT && gapDb > KEY_SUSTAIN_GAP_DB) {
            val lift = 10.0.pow((gapDb - KEY_SUSTAIN_GAP_DB) / 20.0)
            val sustain = loop * lift
            val level = keyEnvelope(audio)
            val stop = held.loopStart - Math.round(HELD_LEAD_SECONDS * RATE).toInt()
            val join = stop - Math.round(KEY_JOIN_SECONDS * RATE).toInt()
            var gain = 1.0
            var from = 1.0
            for (i in audio.indices) {
                when {
                    i < join -> {
                        gain = max(gain, min(lift, sustain / max(level[i], 1e-12)))
                        from = gain
                    }
                    // Into the stopped clock, the last of the rise is finished on a straight line to the loop's gain.
                    i < stop -> gain = from + (lift - from) * (i - join + 1).toDouble() / (stop - join)
                    else -> gain = lift
                }
                audio[i] = (audio[i] * gain).toFloat()
            }
        }
        val level = Loudness.of(Snip(audio.copyOf(head), channels = 1, sampleRate = RATE))
        if (level > 1e-6f) {
            val g = Dsp.MELODIC_LOUDNESS_TARGET / level
            for (i in audio.indices) audio[i] *= g
        }
        Dsp.limitPeak(audio, 0.99f)
        return HeldNote(audio, held.loopStart, Keys.seamError(audio, held.loopStart))
    }

    /**
     * The note's level at every sample, in [Loudness.of]'s own terms: the RMS of `x - 0.7 lowpass120(x)` over a
     * 200 ms window centred on the sample (shorter at the ends), so it compares directly with a loudness reading.
     */
    private fun keyEnvelope(x: FloatArray): DoubleArray {
        val a = 1.0 - exp(-TAU * KEY_ENVELOPE_LOW_HZ / RATE)
        var low = 0.0
        val energy = DoubleArray(x.size + 1)
        for (i in x.indices) {
            low += a * (x[i] - low)
            val w = x[i] - KEY_ENVELOPE_LOW_SHARE * low
            energy[i + 1] = energy[i] + w * w
        }
        val half = (KEY_ENVELOPE_SECONDS * RATE / 2).roundToInt()
        return DoubleArray(x.size) { i ->
            val lo = max(0, i - half)
            val hi = min(x.size, i + half)
            sqrt((energy[hi] - energy[lo]) / (hi - lo))
        }
    }

    private fun checkRaw(raw: FloatArray) {
        check(raw.all { it.isFinite() } && raw.maxOf { abs(it) } < RAW_PEAK_BOUND) { "KEEL produced invalid or excessive raw state" }
    }

    private fun stopIfUnwanted(cancelled: () -> Boolean = { false }) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("KEEL render no longer wanted")
    }
}
