package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * ARCO - the bowed string (docs/superpowers/specs/2026-09-29-arco-bowed-string-engine-design.md).
 *
 * Everything else that sustains in `:synth` is a valve in a pipe or an oscillator under an
 * envelope. This is a *bow dragging a string*: the hair grips the string and carries it along
 * (stick), the string's own tension pulls it back until the grip lets go (slip), the string
 * snaps back the other way, and the hair catches it again. One stick and one slip a period is
 * Helmholtz motion - the sawtooth a cello makes - and it is a limit cycle of the *friction law*,
 * not an oscillator someone wrote: the pitch is the string's round trip, the tone is how the
 * friction law shapes the corner.
 *
 * [Strings.Bow] is the string and the friction junction (R1a). This file is the player: the
 * stroke, the grip, the box, and the stop.
 *
 * Two voices, one string each:
 *  - [ArcoVoice.CELLO]: C2 to C4, the bottom string up two octaves, into a two-mode box (an air
 *    resonance and a plate resonance).
 *  - [ArcoVoice.ERHU]: D4 to A5, a two-stringed fiddle's inner string into a membrane box.
 *
 * What is *not* modelled, on purpose (the design's "Simplifications the model makes, stated"):
 * the string's stiffness and its dispersion (the partials are exactly harmonic), a bow's width
 * (a point), the hair's rosin as a state (the friction table is memoryless, so there is no
 * growl and no period doubling), the two polarisations, bow reversal, and any noise: a bow
 * needs none, and the render is a pure function of the macros.
 *
 * **What every constant below was measured against.** R1b drew the knobs on the built bow, at the
 * raw 176.4 kHz rate and then again on the finished render: how long the string takes to lock into
 * one slip a period (read on the string's velocity under the bow, as the gaps between slips), the
 * autocorrelation pitch, the onset and the ring-down, at every TUNE step of both voices. Nothing here
 * was *listened to* - the audition gate decides whether it is a bow - and every value marked
 * "listening" is a first guess for it.
 *
 * The engine renders **dry**, mono, with no landing chain: a bowed note's own tail is its stop.
 */
enum class ArcoVoice { CELLO, ERHU }

object Arco {

    // ---- TUNE ---------------------------------------------------------------

    /** CELLO's root, C2 (65.41 Hz): the bottom string, so the knob spans C2-C4 and its centre is C3. */
    const val CELLO_ROOT_MIDI = 36

    /** ERHU's root, D4 (293.66 Hz): the inner string's open note. */
    const val ERHU_ROOT_MIDI = 62

    /** CELLO's TUNE travel: two octaves, snapped to semitones (every melodic engine's rule). */
    const val CELLO_TUNE_SEMITONES = 24

    /**
     * ERHU's TUNE travel: 19 semitones, D4 to A5 (880 Hz), not the two octaves the other voices
     * have. The design asked for 24 and named this as the fallback if the speaks test failed at the top:
     * R1b's first maps on the bare bow found no cell at D6 (1174.66 Hz) that slips once a period at any
     * pressure the grip can reach, and B5 (987.77 Hz, 21 semitones) the last step that does; ERHU stops two
     * semitones short of that edge, at A5.
     */
    const val ERHU_TUNE_SEMITONES = 19

    fun tuneSemitones(voice: ArcoVoice): Int = when (voice) {
        ArcoVoice.CELLO -> CELLO_TUNE_SEMITONES
        ArcoVoice.ERHU -> ERHU_TUNE_SEMITONES
    }

    fun rootMidi(voice: ArcoVoice): Int = when (voice) {
        ArcoVoice.CELLO -> CELLO_ROOT_MIDI
        ArcoVoice.ERHU -> ERHU_ROOT_MIDI
    }

    /** The snapped semitone above the voice's root that TUNE lands on. */
    internal fun semitoneFor(voice: ArcoVoice, tune: Float): Int =
        Math.round(tune.coerceIn(0f, 1f) * tuneSemitones(voice))

    /** The snapped MIDI note TUNE lands on. */
    fun midiFor(voice: ArcoVoice, tune: Float): Int = rootMidi(voice) + semitoneFor(voice, tune)

    /** The note's frequency: exact, no pin. The bow's own tuning share ([Strings.Bow.SHARE]) puts it in tune. */
    fun frequencyFor(voice: ArcoVoice, tune: Float): Float = Keys.midiHz(midiFor(voice, tune))

    // ---- the macros ---------------------------------------------------------

    const val DEFAULT_BOW = 0.5f
    const val DEFAULT_GRIP = 0.6f
    const val DEFAULT_BODY = 0.5f

    /** HOLD's default: 0.85 s of bow, a note rather than a LOOP at every TUNE (see [drumClassFor]). */
    const val DEFAULT_HOLD = 0.4f

    fun macrosFor(voice: ArcoVoice): List<MacroSpec> = listOf(
        MacroSpec("TUNE", 0.5f, neutral = 0.5f),
        MacroSpec("BOW", DEFAULT_BOW),
        MacroSpec("GRIP", DEFAULT_GRIP, neutral = 0.5f),
        MacroSpec("BODY", DEFAULT_BODY),
        MacroSpec("HOLD", DEFAULT_HOLD),
    )

    fun defaults(voice: ArcoVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    /** [macros] with every missing key at its default and every value in 0..1; a key the engine does not have is dropped. */
    internal fun settled(macros: Map<String, Float>, voice: ArcoVoice): Map<String, Float> {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        return m
    }

    // ---- the string ---------------------------------------------------------

    /**
     * Where the bow sits, as a fraction of the string from the bridge: STK's 0.127 is 0.133 once the
     * string's own length is counted geometrically. Bridge-ward of the middle, where a bow lives,
     * and low enough that the bow's reflection table, at the pressures [gripFor] reaches,
     * speaks once a period. It is also bounded below for a high note: the bridge segment must be at
     * least the filter's delay and two and a half samples long ([Strings.tune]'s own guard), so the
     * position's floor is (2.5 + the delay in samples) over the period - at D6 (1174.66 Hz, past ERHU's top)
     * 0.119 with a 1500 Hz corner and 0.151 with 1000 Hz (R1a measured it). 0.133 builds across ERHU's whole
     * range at the corner floor [gripFor] gives it. A 1000 Hz corner would still build up to A#5 and is refused
     * from B5 (987.77 Hz) up, so the position does not limit ERHU's corner: the lock does (see
     * [ERHU_CORNER_LOW_ROOT_HZ]).
     */
    const val BETA = 0.133f

    /**
     * The bow's velocity at sustain, in the string's own units: STK's `0.03 + 0.2 * 0.5` for an
     * amplitude of one half. One constant for both voices, not a macro, so BOW is heard only as the stroke
     * (the first few hundred milliseconds) and never moves GRIP's window under it.
     */
    const val V_SUSTAIN = 0.13f

    /**
     * What GRIP sweeps at one TUNE step: the bow's pressure from [pressureLow] to [pressureHigh], and
     * the bridge's corner (the string's loss, so its brightness) from [cornerLow] to [cornerHigh].
     * Both move at once: they are one axis, "how hard the player digs in".
     *
     * The record's window was one pressure island at CELLO's C3, with the corner at STK's full 3023.6 Hz.
     * On the built bow that is not one slip at CELLO's own root: at C2 and the full corner the string
     * slips three times a period at pressures 0.5-0.7, once at 0.8, twice at 0.9-1.0 (R1a). So the
     * window was drawn per voice, along the path GRIP actually travels, and drawn at *every* TUNE step:
     * a first window checked every third semitone had holes at C#2 to D#2, pockets of two and three slips in
     * the middle of the pressure range that sit at a pressure which climbs as the note falls. And a cell
     * counts when the string *locks* into one slip a period for good, not when it starts there: a low string
     * begins in a multi-slip scratch (C2 takes 0.5 to 0.9 s to lock, G#2 and up under 0.75 s, ERHU under 0.4 s).
     * The numbers below are the path that locks at every one of the 25 CELLO and 20 ERHU steps, GRIP in tenths:
     * 0 of 495 cells fail, at BOW 0.5 the slowest lock is 0.87 s (CELLO, D#2 at GRIP 1) and 0.24 s (ERHU), the
     * pitch stays within 4.0 cents of the note. See [CELLO_CORNER_RISE_SEMITONES] and [ERHU_CORNER_RISE_SEMITONES] for what each voice's corner does.
     */
    internal class Grip(val pressureLow: Float, val pressureHigh: Float, val cornerLow: Float, val cornerHigh: Float)

    /**
     * The grip's pressure ceiling is 1.0 for both voices: the friction table's own limit for what a bow can press.
     * CELLO's floor is 0.85 from F#2 up, and higher below it: at C2 pressures of 0.88 to 0.94 hold the
     * string in a three-slip scratch for over a second, and the pressure that locks fastest at each of the
     * lowest semitones climbs as the note falls, so CELLO's floor is [CELLO_PRESSURE_LOW_ROOT] at C2 and falls by
     * [CELLO_PRESSURE_LOW_FALL] a semitone until it meets [PRESSURE_LOW].
     */
    const val PRESSURE_LOW = 0.85f
    const val PRESSURE_HIGH = 1.0f
    const val CELLO_PRESSURE_LOW_ROOT = 0.97f
    const val CELLO_PRESSURE_LOW_FALL = 0.02f

    /**
     * ERHU's floor is 0.94, not 0.85, for pitch's sake and not the string's: pressing harder raises a high
     * string's pitch by about 30 cents per unit of pressure (4.6 cents over the 0.15 from 0.85 to 1.0 at C#5), and
     * with the corner's own share of it a floor of 0.85 would have GRIP move the note by 5.92 cents at C#5, 6.60 at F5
     * and 6.35 at F#5 across its travel (the control in ArcoTest's GRIP-is-not-a-pitch-knob test), over its 5 cent
     * bar; at 0.94 the worst is 4.50 cents at F5 (CELLO's is 4.13 at C4). A knob that is a brightness and a grip must
     * not also be a tuning knob, so ERHU's GRIP travels mostly in the corner, which is the brightness, and the
     * pressure only a little.
     */
    const val ERHU_PRESSURE_LOW = 0.94f

    /**
     * CELLO's corner at GRIP 0: 1000 Hz, a dark, close-held string. The low strings need a lower
     * corner than the full 3023.6 Hz to lock quickly - the bridge's loss per period at f0 is what the
     * friction has to beat, and at 65 Hz the one-pole's loss per period is a quarter of what it is at 131 Hz
     * (0.019 and 0.074 dB a period at a 1000 Hz corner), so a corner that gives C3 its single slip is far too bright
     * for C2 (it locks, but after 1.3 s at the full corner, against 0.55 s at 1000 Hz, both at a pressure of 1.0).
     */
    const val CELLO_CORNER_LOW_HZ = 1000f

    /**
     * CELLO's corner at GRIP 1 is 1500 Hz at the root and rises with the note, reaching the full
     * [Strings.Bow.BRIDGE_HZ] by [CELLO_CORNER_RISE_SEMITONES] semitones up (G#2). Along GRIP at BOW 0.5 a G#2 note
     * locks in 0.45 to 0.57 s (ArcoTest's lock table).
     */
    const val CELLO_CORNER_HIGH_ROOT_HZ = 1500f
    const val CELLO_CORNER_RISE_SEMITONES = 8

    /**
     * ERHU's corner at GRIP 0 is 1500 Hz at its root and rises to the full corner by
     * [ERHU_CORNER_RISE_SEMITONES] semitones up; GRIP 1 is the full corner everywhere. The floor rises because a
     * high string cannot be given a dark corner: from D5 a 1500 Hz corner never locks at any pressure, from F5
     * 1750 Hz does not, and at A5 neither does 2250 Hz (the string falls silent or slips twice). The lock is the
     * whole reason: a 1000 Hz corner would still build in ERHU's range (see [BETA]). The rising floor stays above
     * that line at every step.
     */
    const val ERHU_CORNER_LOW_ROOT_HZ = 1500f
    const val ERHU_CORNER_RISE_SEMITONES = 21

    internal fun gripFor(voice: ArcoVoice, semitone: Int): Grip = when (voice) {
        ArcoVoice.CELLO -> Grip(
            max(PRESSURE_LOW, CELLO_PRESSURE_LOW_ROOT - CELLO_PRESSURE_LOW_FALL * semitone), PRESSURE_HIGH,
            CELLO_CORNER_LOW_HZ,
            Dsp.expMap(min(semitone.toFloat() / CELLO_CORNER_RISE_SEMITONES, 1f), CELLO_CORNER_HIGH_ROOT_HZ, Strings.Bow.BRIDGE_HZ),
        )
        ArcoVoice.ERHU -> Grip(
            ERHU_PRESSURE_LOW, PRESSURE_HIGH,
            Dsp.expMap(min(semitone.toFloat() / ERHU_CORNER_RISE_SEMITONES, 1f), ERHU_CORNER_LOW_ROOT_HZ, Strings.Bow.BRIDGE_HZ),
            Strings.Bow.BRIDGE_HZ,
        )
    }

    /**
     * The share of the bridge filter's delay the tuning budget takes out: one share for both voices, [Strings.Bow.SHARE]
     * (0.85). R1a saw 0.85 miss by +6.8 cents at D6 and thought ERHU needed its own, but D6 is past ERHU's top, and
     * inside ERHU's window (pressure 0.94 and up, the corner floor rising with the note) 0.85 holds all 20 steps
     * within 4.0 cents (ArcoTest's tuning table: worst 3.1). So the two arms below are the same constant on purpose.
     */
    internal fun shareFor(voice: ArcoVoice): Float = when (voice) {
        ArcoVoice.CELLO -> Strings.Bow.SHARE
        ArcoVoice.ERHU -> Strings.Bow.SHARE
    }

    /** GRIP -> the bow's pressure at this TUNE step. */
    internal fun pressureFor(voice: ArcoVoice, semitone: Int, grip: Float): Float =
        gripFor(voice, semitone).let { Dsp.lin(grip, it.pressureLow, it.pressureHigh) }

    /** GRIP -> the bridge's corner at this TUNE step, exponentially: equal steps are equal brightness. */
    internal fun cornerFor(voice: ArcoVoice, semitone: Int, grip: Float): Float =
        gripFor(voice, semitone).let { Dsp.expMap(grip, it.cornerLow, it.cornerHigh) }

    // ---- BOW: the stroke ----------------------------------------------------

    /**
     * BOW is how the note begins: 0 is a slow bow (400 ms to full speed), 1 is a stab (10 ms), and
     * 0.5 is 63 ms. It is the *stroke* and nothing else: the speed the bow holds afterwards is
     * [V_SUSTAIN] at every setting. Equal steps are equal ratios of the time, so the knob is even.
     */
    const val ATTACK_SLOW_SECONDS = 0.40f
    const val ATTACK_FAST_SECONDS = 0.010f

    /** The attack never outlasts this much of HOLD, so a short press still reaches its note (SIREN's own number). */
    const val ATTACK_HOLD_FRACTION = 0.85f

    /**
     * Above BOW 0.5 the stroke bites: the bow's velocity starts higher than it will hold, up to
     * [OVERSHOOT_MAX] times at BOW 1 (a player's accent), and the pressure starts partway to the top of the
     * window, in step with the velocity's bite, reaching it at BOW 1; both relax with the attack's own time
     * constant. 0.6 is a 1.15 times bite. Listening values.
     */
    const val OVERSHOOT_FROM = 0.5f
    const val OVERSHOOT_MAX = 1.75f

    fun attackSeconds(bow: Float): Float = Dsp.expMap(bow, ATTACK_SLOW_SECONDS, ATTACK_FAST_SECONDS)

    internal fun overshootFor(bow: Float): Float =
        1f + (OVERSHOOT_MAX - 1f) * ((bow - OVERSHOOT_FROM) / (1f - OVERSHOOT_FROM)).coerceIn(0f, 1f)

    // ---- HOLD: how long the bow is on the string -----------------------------

    /** HOLD, below its LOOP step: how long the bow is on the string, attack included. SIREN's mapping. */
    const val HOLD_MIN_SECONDS = 0.3f
    const val HOLD_MAX_SECONDS = 4f

    /** HOLD at or above this is a LOOP (the top step of the knob); SCRAMBLE never lands there. SIREN's own numbers. */
    const val LOOP_THRESHOLD = 0.99f
    const val SCRAMBLE_HOLD_CEILING = 0.95f

    fun holdSeconds(hold: Float): Float =
        Dsp.expMap(hold.coerceAtMost(LOOP_THRESHOLD) / LOOP_THRESHOLD, HOLD_MIN_SECONDS, HOLD_MAX_SECONDS)

    fun isLoop(hold: Float): Boolean = hold >= LOOP_THRESHOLD

    /** The HOLD that bows for [seconds]: [holdSeconds]'s inverse, for the audition's three-second held note (3 s is 0.88). */
    internal fun holdFor(seconds: Float): Float =
        (LOOP_THRESHOLD * kotlin.math.ln(seconds / HOLD_MIN_SECONDS) / kotlin.math.ln(HOLD_MAX_SECONDS / HOLD_MIN_SECONDS))
            .coerceIn(0f, SCRAMBLE_HOLD_CEILING)

    /** The bow's own release before it lifts: the velocity ramps to nothing over this long. */
    const val RELEASE_RAMP_SECONDS = 0.05f

    /**
     * After the bow lifts the string is *stopped*, the way a player's hand stops it: its bridge loss is raised
     * so that it falls 60 dB over `max(STOP_FLOOR_SECONDS, min(STOP_HOLD_SHARE * hold, [freeRingSeconds]))`
     * (see [stopScale]). A stab's tail is 0.15 s, a long note rings out in about its own free decay at the lowest, and
     * the floor wins above about 630 Hz, where the free ring is shorter than it (a `coerceIn` would throw there,
     * its floor being above its ceiling). Without the stop a lifted C2 rings for two seconds and files every stab
     * past the classifier's 1.5 s line as a LOOP. Measured on the audible part of the wave (the raw tap also
     * carries a static offset that decays at the bridge's own 0.95 a period whatever the pitch, which the output's
     * 20 Hz high-pass removes), the tail is 60 dB down, against the note's own level just before its velocity ramps
     * down (the level at the lift is already lower), at 0.63 to 0.89 of the release at CELLO C2, C3 and C4 and ERHU D4,
     * C5 and A5 at HOLD 0, 0.4 and 0.95 (ArcoTest's stopped-tail test; where the free ring is no longer than the
     * release the stop adds nothing and it is the string's own ring that was measured).
     */
    const val STOP_FLOOR_SECONDS = 0.15f
    const val STOP_HOLD_SHARE = 0.5f

    /**
     * What a lifted string at [hz] keeps of its wave each round trip, with the bridge's corner at [cornerHz]:
     * `0.95 * |H(f0)|`, H the bridge's one-pole at the fundamental, exactly [Strings.tune]'s own filter.
     */
    internal fun periodGain(hz: Float, cornerHz: Float, rate: Int = RATE * Dsp.OVERSAMPLE): Double {
        val a = 1.0 - exp(-2.0 * PI * min(cornerHz, rate * 0.45f) / rate)
        val pole = 1.0 - a
        val w = 2.0 * PI * hz / rate
        val h = a / sqrt(1.0 - 2.0 * pole * cos(w) + pole * pole)
        return Strings.Bow.REFLECTION * h
    }

    /**
     * How long a lifted string at [hz] takes to fall 60 dB with the bridge's corner at [cornerHz]:
     * `60 / -(20 log10([periodGain]))` periods. R1a measured the real ring-down against it (-0.439 dB a period
     * against -0.454 at C3, -60 dB in 1.02 s against 1.011).
     */
    internal fun freeRingSeconds(hz: Float, cornerHz: Float, rate: Int = RATE * Dsp.OVERSAMPLE): Float =
        (-60.0 / (20.0 * log10(periodGain(hz, cornerHz, rate))) / hz).toFloat()

    /**
     * The bridge's loss, as a multiple of its own, that makes a lifted string fall 60 dB in exactly [releaseSeconds]: the
     * stop. It is one constant from the lift to the end, so the tail is a plain exponential (a straight line
     * in dB) that reaches -60 dB where the release ends, and never a ramp: a gain that went from 1 to 0 over the
     * release compounds every period and had the string 60 dB down long before the end (at C2, in 0.5 s of a
     * 1.8 s release), leaving more than a second of the render near silence. Never above 1: where the free
     * ring is already shorter than the release (the floor won) the string is left to ring on its own.
     */
    internal fun stopScale(hz: Float, cornerHz: Float, releaseSeconds: Float, rate: Int = RATE * Dsp.OVERSAMPLE): Float {
        val wanted = 10.0.pow(-3.0 / (releaseSeconds * hz))
        return min(1.0, wanted / periodGain(hz, cornerHz, rate)).toFloat()
    }

    internal fun releaseSeconds(hz: Float, cornerHz: Float, holdSeconds: Float): Float =
        max(STOP_FLOOR_SECONDS, min(STOP_HOLD_SHARE * holdSeconds, freeRingSeconds(hz, cornerHz)))

    // ---- vibrato: baked, a smooth wobble of the wave's own time --------------------------------

    /**
     * A player's finger rocks on the string: +-[VIBRATO_MAX_CENTS] cents at [VIBRATO_HZ] (STK's own
     * default), starting after the string has begun to speak ([VIBRATO_DELAY_SECONDS]; C2 reaches 90 percent of its
     * level at about half a second) and rising in over [VIBRATO_RISE_SECONDS]. Scaled by how long the note lasts: none at a
     * hold of [VIBRATO_HOLD_FROM_SECONDS] or less (a vibrato on a scratch is a wobble), full from
     * [VIBRATO_HOLD_FULL_SECONDS]. Off in a LOOP, which cannot carry a signal that does not repeat. Listening values.
     *
     * It is done to the string's wave, not to the string. R1b's first version retuned the bow ([Strings.Bow.retune], which
     * R1a measured as click-free) every 64 samples, and the wave had no step. But the friction is a hair trigger: on ERHU's
     * short periods a slip split in two (gaps of 0.2 then 0.8 of a period) at the same phase of the swing every time,
     * 37 events in 24,746 ERHU slips (CELLO: 1 in 8,337) with the vibrato on and none with it off, and in the 200 rolled
     * ERHU notes of the identity test 91 had one in the last 1.3 s of a three-second bow. So the vibrato is the plain tape-style one:
     * the finished string wave is read back through a delay that swings by [vibratoDepthSamples] (a four-point cubic reads
     * between samples), which moves every partial by the same ratio, as a finger does, and the bow never feels it.
     */
    const val VIBRATO_HZ = 6.1
    const val VIBRATO_MAX_CENTS = 10f
    const val VIBRATO_DELAY_SECONDS = 0.35
    const val VIBRATO_RISE_SECONDS = 0.2
    const val VIBRATO_HOLD_FROM_SECONDS = 0.6f
    const val VIBRATO_HOLD_FULL_SECONDS = 1.5f

    /** How many cents of vibrato a bow-on time of [holdSeconds] carries: none to [VIBRATO_HOLD_FROM_SECONDS], all from [VIBRATO_HOLD_FULL_SECONDS]. */
    internal fun vibratoCentsFor(holdSeconds: Float): Float = VIBRATO_MAX_CENTS *
        ((holdSeconds - VIBRATO_HOLD_FROM_SECONDS) / (VIBRATO_HOLD_FULL_SECONDS - VIBRATO_HOLD_FROM_SECONDS)).coerceIn(0f, 1f)

    /**
     * The delay's swing, in samples at [rate], that moves the pitch by [cents] at its fastest: a delay of `d sin(2 pi f t)` changes
     * the pitch by `d 2 pi f` of itself at most, so `d = (2^(cents / 1200) - 1) / (2 pi f)` seconds.
     */
    internal fun vibratoDepthSamples(cents: Float, rate: Int): Double =
        (2.0.pow(cents / 1200.0) - 1.0) / (2.0 * PI * VIBRATO_HZ) * rate

    /**
     * [buf] read back through the vibrato's swinging delay, the same length: sample `i` is the wave at `i - d(t)`, `d` zero until
     * [VIBRATO_DELAY_SECONDS] (so everything before it is copied exactly), then the swing rising in over [VIBRATO_RISE_SECONDS].
     * A four-point cubic (Catmull-Rom) reads between samples; the wave is at four times the rate its partials need, so the
     * interpolation's own loss is far under -60 dB where the note's energy is.
     */
    internal fun vibrato(buf: FloatArray, cents: Float, rate: Int): FloatArray {
        if (cents <= 0f) return buf
        val depth = vibratoDepthSamples(cents, rate)
        val out = FloatArray(buf.size)
        val last = buf.size - 1
        for (i in buf.indices) {
            val t = i.toDouble() / rate
            if (t <= VIBRATO_DELAY_SECONDS) {
                out[i] = buf[i]
                continue
            }
            val rise = ((t - VIBRATO_DELAY_SECONDS) / VIBRATO_RISE_SECONDS).coerceAtMost(1.0)
            val pos = i - depth * rise * sin(2.0 * PI * VIBRATO_HZ * (t - VIBRATO_DELAY_SECONDS))
            val k = Math.floor(pos).toInt()
            val x = (pos - k).toFloat()
            val p0 = buf[(k - 1).coerceIn(0, last)]
            val p1 = buf[k.coerceIn(0, last)]
            val p2 = buf[(k + 1).coerceIn(0, last)]
            val p3 = buf[(k + 2).coerceIn(0, last)]
            out[i] = p1 + 0.5f * x * (p2 - p0 + x * (2f * p0 - 5f * p1 + 4f * p2 - p3 + x * (3f * (p1 - p2) + p3 - p0)))
        }
        return out
    }

    // ---- BODY: the box --------------------------------------------------------

    /**
     * CELLO's box: an air resonance at 104 Hz (t60 0.254 s, Q about 12) and a plate resonance at 220 Hz
     * (t60 0.180 s, Q about 18), `t60 = 2.2 Q / f`. Both rows are *shape*: placed by where a cello's
     * body modes live, not sourced from a measured instrument, and the gains are first guesses
     * (listening).
     */
    private val CELLO_BODY = listOf(
        Modes.fixed(104f, 1.0f, 0.254f),
        Modes.fixed(220f, 0.8f, 0.180f),
    )

    /**
     * ERHU's box: the house membrane's five mode ratios on the open string, shape again - the
     * ratios are too spread for a real python skin and the table has no formant. Its
     * decay is the table's scaled by [ERHU_BODY_T60_SCALE]: the table's first mode rings for 1.2 s, a
     * skin's tail longer than the string that struck it, and scaled by 0.25 it rings for 0.3 s. Listening values.
     */
    const val ERHU_BODY_ANCHOR_HZ = 293.66f
    const val ERHU_BODY_T60_SCALE = 0.25f

    private val ERHU_BODY: List<Modes.Mode> by lazy {
        Modes.tableFor(Modes.Material.MEMBRANE).map {
            Modes.fixed(ERHU_BODY_ANCHOR_HZ * it.ratio, it.gain, it.t60 * ERHU_BODY_T60_SCALE)
        }
    }

    internal fun bodyFor(voice: ArcoVoice): List<Modes.Mode> = when (voice) {
        ArcoVoice.CELLO -> CELLO_BODY
        ArcoVoice.ERHU -> ERHU_BODY
    }

    /**
     * The string through the box at the oversampled [rate], and *no longer than the string*: [Strings.bodyRing]
     * follows the box's ring out past the string's end, which would make BODY change how long a note is
     * (the default CELLO would render 1.585 s and be filed a LOOP) - so the ring is cut where the stopped
     * string ends, and the 4 ms fade at the end of the render finishes it. What the cut takes is the box's
     * own tail, a few hundred milliseconds of a ring the stop has already faded to near nothing (the stop
     * takes the drive to nothing over at least 150 ms). BODY 0 is the string itself.
     */
    internal fun withBody(raw: FloatArray, voice: ArcoVoice, amount: Float, rate: Int): FloatArray {
        val rung = Strings.bodyRing(raw, bodyFor(voice), amount, rate, BODY_CEILING_SECONDS)
        return if (rung.size == raw.size) rung else rung.copyOf(raw.size)
    }

    /** The longest string plus the box's ring, with room: [Strings.bodyRing]'s own ceiling. */
    const val BODY_CEILING_SECONDS = 8f

    // ---- the output chain -----------------------------------------------------

    /**
     * The DC goes twice (FORK's rule), before the level: the onset leaves a net displacement in the raw string
     * (its mean reaches 0.09 over the first half second of a C2 note, and 0.07 at most over ArcoTest's grid) that
     * drains only at the bridge's own 0.95 a period, so the mean is taken off and this high-pass takes the rest.
     */
    const val OUTPUT_DC_HZ = 20f

    private fun condition(raw: FloatArray, rate: Int): FloatArray {
        Tide.bandLimit(raw, rate)
        val out = Dsp.decimate(raw, RATE)
        var mean = 0.0
        for (v in out) mean += v
        val m = (mean / out.size.coerceAtLeast(1)).toFloat()
        val hp = Dsp.OnePole(RATE)
        for (i in out.indices) {
            val x = out[i] - m
            out[i] = x - hp.lp(x, OUTPUT_DC_HZ)
        }
        return out
    }

    internal fun finish(raw: FloatArray, rate: Int): FloatArray {
        val out = condition(raw, rate)
        Dsp.levelTo(out, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return out
    }

    // ---- the length of a note: one place --------------------------------------

    /** The classifier's own length rule: past this a render's duration alone reads LOOP. */
    const val LOOP_THRESHOLD_SECONDS = 1.5f

    /**
     * What a bow is asked to be, in raw samples: on the string for [holdN] (the first [attackN] of them the
     * stroke), then [rampN] of velocity going to nothing, then lifted and [stopN] of the stop. A [steady]
     * gate is a LOOP's stretch: attack, then constant velocity and pressure to the end, no vibrato, no stop.
     */
    private class Gate(val holdN: Int, val attackN: Int, val rampN: Int, val stopN: Int, val steady: Boolean) {
        val total get() = holdN + rampN + stopN
    }

    /** The one place a note's length is worked out, for [bow] and [renderFrames] alike. */
    private fun gateFor(voice: ArcoVoice, hz: Float, macros: Map<String, Float>, rate: Int, holdOverride: Float?): Gate {
        val holdSec = holdOverride ?: holdSeconds(macros.getValue("HOLD"))
        val semitone = semitoneFor(voice, macros.getValue("TUNE"))
        val release = releaseSeconds(hz, cornerFor(voice, semitone, macros.getValue("GRIP")), holdSec)
        val holdN = (holdSec * rate).toInt().coerceAtLeast(1)
        val attackSec = min(attackSeconds(macros.getValue("BOW")), ATTACK_HOLD_FRACTION * holdSec)
        val attackN = (attackSec * rate).toInt().coerceAtLeast(1)
        return Gate(holdN, attackN, (RELEASE_RAMP_SECONDS * rate).toInt(), (release * rate).toInt().coerceAtLeast(1), steady = false)
    }

    /**
     * The exact frames a one-shot at [macros] renders at [RATE]: bow on, the ramp, the stop, through the
     * decimator's floored 2:1 steps. ArcoProductTest pins it against a real render and against the classifier's
     * own length line, so a change to the decimator or the body cannot move it silently.
     */
    internal fun renderFrames(voice: ArcoVoice, macros: Map<String, Float>): Int {
        val m = settled(macros, voice)
        return gateFor(voice, frequencyFor(voice, m.getValue("TUNE")), m, RATE * Dsp.OVERSAMPLE, null).total / Dsp.OVERSAMPLE
    }

    /**
     * What a pad holding this sound is filed as: LOOP at the top step or past the classifier's own 1.5 s line on
     * the *rendered* frame count, else PERC (never TONAL; the classifier decides that for itself, and a cello's low notes may
     * read it). Derived from the frame count, never a hard-coded knob position: the line is crossed at HOLD 0.447 at
     * C2 and C3 and a little higher on the shorter stops of the notes above, so TUNE and GRIP are in the formula.
     */
    fun drumClassFor(voice: ArcoVoice, macros: Map<String, Float> = emptyMap()): DrumClass {
        val m = settled(macros, voice)
        val long = isLoop(m.getValue("HOLD")) || renderFrames(voice, m).toFloat() / RATE > LOOP_THRESHOLD_SECONDS
        return if (long) DrumClass.LOOP else DrumClass.PERC
    }

    /**
     * SCRAMBLE: [Dsp.scrambleNear] around a factory preset (or [near]), then HOLD capped short of a LOOP.
     * GRIP and BOW need no clamp: the mappings confine them to speaking values.
     */
    fun scramble(voice: ArcoVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = when {
            near != null -> base + near.macros.filterKeys { it in base }
            temperature >= 1f -> base
            else -> base + ArcoPresets.forVoice(voice).random(random).macros.filterKeys { it in base }
        }
        val rolled = Dsp.scrambleNear(seed, temperature, random).toMutableMap()
        rolled["HOLD"] = rolled.getValue("HOLD").coerceAtMost(SCRAMBLE_HOLD_CEILING)
        return rolled
    }

    // ---- the bow: the core, at exact Hz ----------------------------------------

    /**
     * The bowed string at exact [hz], at [rate] (the oversampled render rate): the wave leaving the bow
     * toward the bridge, BEFORE the body, the band limit, the decimator and the level - so a test reads
     * the physics without [Dsp.levelTo] lifting a silent or stuck render and hiding it (the `Fork.bank`
     * rule). [macros] must carry every macro ([settled] does that).
     *
     * The overrides are for tests and probes, and the window is drawn with them: [pressure] replaces
     * GRIP's pressure, [cornerHz] its corner, [vBow] the voice's sustain velocity, [overshoot] BOW's bite,
     * [gateSeconds] HOLD's bow-on time; [vibrato] false is a plain wave; [lifted] never puts the bow
     * down; [bowPointOut] receives [Strings.Bow.bowPoint] each sample, the string's velocity under
     * the bow, which is what the slips-per-period counter reads (the string itself, which the vibrato never touches);
     * [share] replaces the voice's tuning share.
     */
    internal fun bow(
        voice: ArcoVoice,
        hz: Float,
        macros: Map<String, Float>,
        rate: Int = RATE * Dsp.OVERSAMPLE,
        pressure: Float? = null,
        cornerHz: Float? = null,
        vBow: Float? = null,
        overshoot: Float? = null,
        gateSeconds: Float? = null,
        vibrato: Boolean = true,
        lifted: Boolean = false,
        bowPointOut: FloatArray? = null,
        share: Float? = null,
    ): FloatArray {
        val gate = gateFor(voice, hz, macros, rate, gateSeconds)
        val tap = play(voice, hz, macros, rate, gate, pressure, cornerHz, vBow, overshoot, lifted, bowPointOut, share)
        return if (vibrato) vibrato(tap, vibratoCentsFor(gate.holdN.toFloat() / rate), rate) else tap
    }

    private fun play(
        voice: ArcoVoice, hz: Float, macros: Map<String, Float>, rate: Int, gate: Gate,
        pressure: Float?, cornerHz: Float?, vBow: Float?, overshoot: Float?,
        lifted: Boolean, bowPointOut: FloatArray?, share: Float?,
    ): FloatArray {
        val semitone = semitoneFor(voice, macros.getValue("TUNE"))
        val grip = macros.getValue("GRIP")
        val window = gripFor(voice, semitone)
        val p = pressure ?: pressureFor(voice, semitone, grip)
        val corner = cornerHz ?: cornerFor(voice, semitone, grip)
        val vSustain = vBow ?: V_SUSTAIN
        val steady = gate.steady
        val over = if (steady) 1f else overshoot ?: overshootFor(macros.getValue("BOW"))
        val biteShare = ((over - 1f) / (OVERSHOOT_MAX - 1f)).coerceIn(0f, 1f)
        val pBite = Dsp.lin(biteShare, p, max(p, window.pressureHigh))
        val tau = gate.attackN.toDouble() / rate

        val bow = Strings.Bow(f = hz, beta = BETA, bridgeHz = corner, share = share ?: shareFor(voice), rate = rate)
        if (lifted) bow.lift()

        val out = FloatArray(gate.total)
        val liftAt = gate.holdN + gate.rampN
        val bite = if (over > 1f) (over - 1f).toDouble() else 0.0
        val biteEnd = (BITE_TIME_CONSTANTS * gate.attackN).toInt()
        for (i in 0 until gate.total) {
            val t = i.toDouble() / rate
            val ramp = if (i < gate.attackN) i.toFloat() / gate.attackN else 1f
            val relax = if (bite > 0.0 && i < biteEnd) exp(-t / tau) else 0.0
            val release = when {
                steady || i < gate.holdN -> 1f
                i < liftAt -> 1f - (i - gate.holdN).toFloat() / gate.rampN
                else -> 0f
            }
            val v = vSustain * ramp * (1f + (bite * relax).toFloat()) * release
            val pNow = p + (pBite - p) * relax.toFloat()
            if (!steady && !lifted && i == liftAt) {
                bow.lift()
                bow.gain(stopScale(hz, corner, gate.stopN.toFloat() / rate, rate))
            }
            out[i] = bow.next(v, 5f - 4f * pNow)
            if (bowPointOut != null) bowPointOut[i] = bow.bowPoint
        }
        return out
    }

    /** The bite has relaxed to nothing (to under a millionth of itself) after this many of the attack's own time constants. */
    private const val BITE_TIME_CONSTANTS = 14

    // ---- the LOOP -------------------------------------------------------------

    /** A LOOP holds at least this long, in whole periods, so it is over the classifier's line with room: BORE's number, which is the plan this file reuses. */
    const val LOOP_SECONDS = Bore.LOOP_SECONDS

    /**
     * The steady state is reached and settled this long before a LOOP is cut: at least this many seconds and at least
     * [LOOP_WARMUP_PERIODS] periods. A bow builds in a number of periods, not of seconds, and a string that locks late
     * (a bright corner on a low note: 1.3 s at C2) is still settling after it has locked. With 1.0 s and 100 periods ten
     * loops were over the seam bar, up to 2e-2 (GRIP 1 at C#2 to B2); with 2.0 s and 200 periods all 225 loops
     * measured (every TUNE step of both voices at the default and four other corners) close, the worst at 2.5e-4
     * against the bar of 1e-3.
     */
    const val LOOP_WARMUP_SECONDS = 2.0f
    const val LOOP_WARMUP_PERIODS = 200

    /**
     * How a LOOP's stroke runs: a fixed 63 ms, BOW 0.5's, with no bite - so a LOOP does not depend on BOW
     * (which is heard only at the start of a note, and a LOOP discards its start).
     */
    internal val LOOP_ATTACK_SECONDS get() = attackSeconds(0.5f)

    /**
     * The most times the loop's pitch is corrected so its whole periods fill its whole frames, and how close to
     * 1 the measured length over the wanted one must be to stop early (BORE's numbers, which hold at ARCO's top
     * note too: 3e-7 of a two second loop is about a tenth of a raw sample, 0.0005 of a period at A5, ERHU's top,
     * where a period is 200 raw samples).
     */
    private const val LOOP_PASSES = 5
    private const val LOOP_CONVERGED = 3e-7

    /** Frames of steady stretch kept past the loop, so the long-lag measurement has samples to look at. */
    private const val LOOP_PAD_FRAMES = 2048

    /** The frames [Keys.seamError] looks at before the loop start: its own window. */
    private const val SEAM_FRAMES = 256

    /**
     * [Bore]'s plan, which has no voice in it: whole periods of the note, at least [LOOP_SECONDS] long, in whole frames.
     * ARCO reuses it, and [Bore.measureLoopSamples] below, rather than carrying a second copy: one quantity computed in two
     * places is the house defect.
     */
    internal fun planLoop(hz: Float): Bore.LoopPlan = Bore.planLoop(hz)

    /** The steady stretch: a short stroke, then constant velocity and pressure to the end. No bite, no vibrato, no stop. */
    internal fun stretch(voice: ArcoVoice, macros: Map<String, Float>, tuned: Float, warmFrames: Int, frames: Int): FloatArray {
        val rate = RATE * Dsp.OVERSAMPLE
        val total = (warmFrames + frames + LOOP_PAD_FRAMES) * Dsp.OVERSAMPLE
        val attackN = (LOOP_ATTACK_SECONDS * rate).toInt().coerceAtLeast(1)
        return play(voice, tuned, macros, rate, Gate(total, attackN, 0, 0, steady = true), null, null, null, null, false, null, null)
    }

    /** A rendered LOOP and how well its stretch closes on itself ([Keys.seamError]). */
    internal class LoopRender(val loop: FloatArray, val seam: Double)

    /**
     * The held note as one loop, [Bore.LoopPlan.periods] whole periods in [Bore.LoopPlan.frames] whole frames, so the wrap is
     * seamless: a steady bow, the warm-up discarded, the *played* pitch corrected until the periods fill the
     * frames (BORE's route: a fresh bow rendered at `tuned * ratio` until the ratio is 1 to 3e-7), then cut where
     * the two neighbours are smallest and levelled. A bow's pitch is a limit cycle's, not an accumulator's, so
     * the planned pitch does not close it; the measured one does.
     *
     * Checked, not assumed: the kept stretch is compared with itself one loop later ([Keys.seamError], the
     * Organ's bar), because the loop played twice is tautologically periodic.
     */
    internal fun renderLoopMeasured(voice: ArcoVoice, macros: Map<String, Float>): LoopRender {
        val m = settled(macros, voice)
        val target = frequencyFor(voice, m.getValue("TUNE"))
        val plan = planLoop(target)
        val over = Dsp.OVERSAMPLE
        val rate = RATE * over
        val warm = Math.round(max(LOOP_WARMUP_SECONDS, LOOP_WARMUP_PERIODS / target) * RATE)
        val wanted = plan.frames.toDouble() * over
        var tuned = target.toDouble()
        var raw = stretch(voice, m, tuned.toFloat(), warm, plan.frames)
        for (pass in 1..LOOP_PASSES) {
            val ratio = Bore.measureLoopSamples(raw, warm * over, wanted, plan.periods) / wanted
            if (abs(ratio - 1.0) < LOOP_CONVERGED) break
            tuned *= ratio
            raw = stretch(voice, m, tuned.toFloat(), warm, plan.frames)
        }
        val conditioned = condition(withBody(raw, voice, m.getValue("BODY"), rate), rate)
        val seam = Keys.seamError(conditioned.copyOfRange(warm, warm + plan.frames + SEAM_FRAMES), SEAM_FRAMES)
        val one = conditioned.copyOfRange(warm, warm + plan.frames)
        val cut = Siren.bestCut(one, plan.frames)
        val loop = one.copyOfRange(cut, plan.frames) + one.copyOfRange(0, cut)
        Dsp.levelTo(loop, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        return LoopRender(loop, seam)
    }

    /** The loop, refused by name if its corner cannot close: a click shipped silently is the worse failure. */
    internal fun renderLoop(voice: ArcoVoice, macros: Map<String, Float>): FloatArray {
        val r = renderLoopMeasured(voice, macros)
        require(r.seam < Keys.MAX_SEAM_ERROR) {
            "ARCO $voice ${midiFor(voice, settled(macros, voice).getValue("TUNE"))}: the loop does not close (seam %.2e, bar %.0e)"
                .format(java.util.Locale.ROOT, r.seam, Keys.MAX_SEAM_ERROR)
        }
        return r.loop
    }

    // ---- the render -----------------------------------------------------------

    fun render(voice: ArcoVoice, macros: Map<String, Float> = emptyMap()): Snip {
        val m = settled(macros, voice)
        if (isLoop(m.getValue("HOLD"))) return Snip(renderLoop(voice, m), channels = 1, sampleRate = RATE)
        val hz = frequencyFor(voice, m.getValue("TUNE"))
        val rate = RATE * Dsp.OVERSAMPLE
        val raw = bow(voice, hz, m, rate)
        return Snip(finish(withBody(raw, voice, m.getValue("BODY"), rate), rate), channels = 1, sampleRate = RATE)
    }
}
