package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
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
 * was *listened to* when R1b was written. The owner has since heard R1b's held notes, a stab and the knob ends (the bowed note was
 * picked out six times in six; BODY 1, CELLO's BOW 1 and CELLO's vibrato were "nearly", which R1c retunes), and has not heard the
 * retune, the presets, the kit or the loops; every value marked "listening" is a first guess for the ears that have not.
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
     * Above [OVERSHOOT_FROM] the stroke bites: the bow's velocity starts higher than it will hold, up to [overshootMax] times at BOW 1
     * (a player's accent), and the pressure starts part of the way to the top of the window in step with it (the part is
     * [bitePressure], of the bite's share, so at BOW 1 a voice whose [bitePressure] is 1 starts at the window's top); both relax
     * exponentially, with a time constant of the longer of the attack and [biteSeconds]. Each voice has its own three numbers.
     * ERHU's are R1b's: 1.75 times, the attack's own time constant (a 10 ms stroke's bite is down to 5 percent over the sustain in about 27 ms, a hump of 1.28 times
     * the velocity at its highest, since the stroke is still rising), all of the share in the pressure; 0.6 is a 1.15 times bite. Listening values.
     *
     * The bite is per voice since the owner heard CELLO's BOW 1 as "nearly" and "not enough bite" while ERHU's was a yes. R1b's bite on a
     * bass string hardly shows: R1c measured it against the same stroke with none, and the best of the 0-50, 50-100 and 100-200 ms
     * windows is within 0.5 dB of it at F2, C3, G3 and C4 (0.3, 0.5, 0.5 and 0.4; over the whole 200 ms the figure is +0.2, -0.2, +0.4 and -0.6),
     * and ERHU's, which the owner heard as a yes, is 0.3 to 1.1 dB. CELLO's has three numbers of its own: [OVERSHOOT_MAX_CELLO] the velocity's,
     * [BITE_SECONDS_CELLO] the least time constant it relaxes with, and [BITE_PRESSURE_CELLO] the part of its share that presses the string toward
     * the window's top (the rest of the accent is speed alone). Together they are +2.0, +2.9, +3.0 and +2.7 dB in the best of the 0-50, 50-100 and 100-200 ms windows at
     * F2, C3, G3 and C4.
     *
     * The three were searched, not guessed, because the lock is chaotic in them and the roster has bars of its own. R1c ran a few hundred
     * settings (velocity 1.75 to 3.5 times, 20 to 140 ms, a pressure share of 0 to 1) against: the overshoot row (every TUNE step at BOW 1,
     * GRIP 0.6 and 1, locked within 1.5 s), the onset claim (BOW 1 sooner than BOW 0 at C2, C3 and C4), SHORT STAB, GRIT BOW and HORSEHAIR
     * locking inside their bow-on, DRY SCRAPE still a scrape, the lock across BOW (2.0 s), the scramble test's 400 random rolls, how many TUNE
     * steps still lock at BOW 1 in a default note, and a gain of at least 1.5 dB at four notes. Three earlier settings were wrong, each in
     * something the search had not yet looked at. 3.0 times for 60 ms with all of the share in the pressure never locked SHORT STAB in its
     * 0.30 s stab. 2.75 times for 60 ms passed the roster but put C3's BOW 1 at 600 ms to reach 90 percent against BOW 0's 448. 2.5 times for 120 ms
     * with a quarter of the share in the pressure kept those but let only 18 of 25 TUNE steps lock inside a default note at BOW 1 (R1b: 23) and left
     * one scramble roll in 200 at temperature 1 late. 3.0 times for 60 ms with half of the share in the pressure keeps all of them: the row at
     * most 1.44 s, the lock grid 1.47 s, C3 at 434 ms against 448, 22 of 25 default-note steps locking, none of the 400 scramble rolls late, and the
     * steps where BOW 1 is not sooner than BOW 0 down to F#2, G2 and G#2. **What no setting kept** is the BOW 1 stab at SHORT STAB's macros and the
     * figure the owner marked YES (C3 C3 E-flat3 G3 G3 E-flat3 C3, 0.3 s each): R1b's bite was so small that E-flat3 locked at 0.189 s, and every
     * setting that kept the other bars (19 of the 80 searched in the last round passed them, and each of the 19 loses it) leaves it a three-slip scrape for the whole stab; the HOLD-0 stab count of 25 steps
     * locking by 0.255 s falls from 10 to 6.
     */
    const val OVERSHOOT_FROM = 0.5f
    const val OVERSHOOT_MAX_ERHU = 1.75f
    const val OVERSHOOT_MAX_CELLO = 3.0f
    const val BITE_SECONDS_ERHU = 0f
    const val BITE_SECONDS_CELLO = 0.06f
    const val BITE_PRESSURE_ERHU = 1f
    const val BITE_PRESSURE_CELLO = 0.5f

    fun attackSeconds(bow: Float): Float = Dsp.expMap(bow, ATTACK_SLOW_SECONDS, ATTACK_FAST_SECONDS)

    /** BOW 1's bite, as a multiple of the sustain velocity, for [voice]. */
    internal fun overshootMax(voice: ArcoVoice): Float = when (voice) {
        ArcoVoice.CELLO -> OVERSHOOT_MAX_CELLO
        ArcoVoice.ERHU -> OVERSHOOT_MAX_ERHU
    }

    /** The least time constant the bite relaxes with, for [voice]: its time constant is the longer of this and the stroke's attack. */
    internal fun biteSeconds(voice: ArcoVoice): Float = when (voice) {
        ArcoVoice.CELLO -> BITE_SECONDS_CELLO
        ArcoVoice.ERHU -> BITE_SECONDS_ERHU
    }

    /** How much of the bite's share goes into the pressure's climb toward the window's top, for [voice]: ERHU's is all of it (as it was). */
    internal fun bitePressure(voice: ArcoVoice): Float = when (voice) {
        ArcoVoice.CELLO -> BITE_PRESSURE_CELLO
        ArcoVoice.ERHU -> BITE_PRESSURE_ERHU
    }

    internal fun overshootFor(voice: ArcoVoice, bow: Float, max: Float = overshootMax(voice)): Float =
        1f + (max - 1f) * ((bow - OVERSHOOT_FROM) / (1f - OVERSHOOT_FROM)).coerceIn(0f, 1f)

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
    internal fun vibrato(buf: FloatArray, cents: Float, rate: Int, shape: VibratoShape = VIBRATO_PLAIN): FloatArray {
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
            val u = t - VIBRATO_DELAY_SECONDS
            val rise = (u / shape.riseSeconds).coerceAtMost(1.0)
            val pos = i - depth * rise * vibratoUnit(u, shape)
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

    /**
     * How a voice's finger moves. [VIBRATO_PLAIN] is a sine at [VIBRATO_HZ] that swells in over [VIBRATO_RISE_SECONDS], the same at every
     * swing: ERHU's, which the owner heard as a yes. [VIBRATO_HUMAN] is CELLO's, which the owner heard as "nearly" and "too
     * mechanical": the rate drifts by up to [rateWander] of itself, the depth by up to [depthWander] of itself, the swing leans
     * by [skew] (a second harmonic, so the up and the down are not mirror images), and it swells in over a longer
     * [riseSeconds]. Every drift is a sum of three slow sines whose frequencies share no period inside a note (they are all multiples of 0.01 Hz, so the swing repeats after 100 s), so the swing is never the same
     * twice in a note and no two notes differ: it is a function of the time since the vibrato began and nothing else, so a
     * render is the same every time and no seed is carried. Listening values.
     */
    internal class VibratoShape(val riseSeconds: Double, val rateWander: Double, val depthWander: Double, val skew: Double)

    internal val VIBRATO_PLAIN = VibratoShape(VIBRATO_RISE_SECONDS, 0.0, 0.0, 0.0)
    internal val VIBRATO_HUMAN = VibratoShape(HUMAN_RISE_SECONDS, 0.07, 0.18, 0.06)

    internal fun vibratoShapeFor(voice: ArcoVoice): VibratoShape = when (voice) {
        ArcoVoice.CELLO -> VIBRATO_HUMAN
        ArcoVoice.ERHU -> VIBRATO_PLAIN
    }

    /** A cellist's vibrato swells in over about half a second, against [VIBRATO_RISE_SECONDS]'s fifth. */
    const val HUMAN_RISE_SECONDS = 0.5

    private val RATE_WANDER_HZ = doubleArrayOf(0.21, 0.47, 1.13)
    private val RATE_WANDER_WEIGHT = doubleArrayOf(0.5, 0.35, 0.15)
    private val RATE_WANDER_PHASE = doubleArrayOf(0.7, 2.1, 4.0)
    private val DEPTH_WANDER_HZ = doubleArrayOf(0.29, 0.67, 1.37)
    private val DEPTH_WANDER_WEIGHT = doubleArrayOf(0.5, 0.35, 0.15)
    private val DEPTH_WANDER_PHASE = doubleArrayOf(1.3, 3.1, 5.2)
    private const val SKEW_PHASE = 0.9

    /**
     * The unit swing, [u] seconds after the vibrato began, within 1 for [VIBRATO_PLAIN] and at most about 1.25 for [VIBRATO_HUMAN] (the depth's drift 1.18 times the skew's 1.06; its pitch swing reaches 12.75 cents against [VIBRATO_MAX_CENTS]'s 10): `sin` of a phase that advances
     * at [VIBRATO_HZ] times (1 + the rate's drift), times (1 + the depth's drift), plus the skew's second harmonic. The rise is not in
     * it ([vibrato] applies it). With no wander and no skew it is `sin(2 pi [VIBRATO_HZ] u)` exactly, term for term, so [VIBRATO_PLAIN] is
     * the vibrato this file had before the drift existed, bit for bit (ArcoProductTest holds that).
     */
    internal fun vibratoUnit(u: Double, shape: VibratoShape): Double {
        var phase = u
        if (shape.rateWander != 0.0) {
            var drift = 0.0
            for (k in RATE_WANDER_HZ.indices) {
                val w = 2.0 * PI * RATE_WANDER_HZ[k]
                drift += RATE_WANDER_WEIGHT[k] * (cos(RATE_WANDER_PHASE[k]) - cos(w * u + RATE_WANDER_PHASE[k])) / w
            }
            phase = u + shape.rateWander * drift
        }
        var g = 1.0
        if (shape.depthWander != 0.0) {
            var s = 0.0
            for (k in DEPTH_WANDER_HZ.indices) s += DEPTH_WANDER_WEIGHT[k] * sin(2.0 * PI * DEPTH_WANDER_HZ[k] * u + DEPTH_WANDER_PHASE[k])
            g = 1.0 + shape.depthWander * s
        }
        val angle = 2.0 * PI * VIBRATO_HZ * phase
        return g * (sin(angle) + shape.skew * sin(2.0 * angle + SKEW_PHASE))
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
     * takes the drive to nothing over at least 150 ms). BODY 0 is the string itself. The box is the knob's own value up to [BODY_KNEE] and stays at the knee's size above it ([boxAmountFor]), so
     * every BODY above the middle rings the box the default rings; what BODY adds above the middle is the lift, on the conditioned note and not in the box ([finished]).
     */
    internal fun withBody(raw: FloatArray, voice: ArcoVoice, amount: Float, rate: Int): FloatArray {
        val rung = Strings.bodyRing(raw, bodyFor(voice), boxAmountFor(amount), rate, BODY_CEILING_SECONDS)
        return if (rung.size == raw.size) rung else rung.copyOf(raw.size)
    }

    /**
     * BODY is two things, split at [BODY_KNEE]. Up to it BODY is how loud the box rings against the string ([Strings.bodyRing]'s `amount`, the box's RMS over the string's own): the knob's own value, which is what R1b rang
     * at the default BODY 0.5 and at every sound at or under it, so [finished] is R1b's finish there, to the bit. Above it the box stays the size it is at the knee. R1c had climbed it to 1.75 times the string at BODY 1,
     * and the level step after the box took most of that climb back (CELLO C3 against the plain: the low band up 0.84 dB, the 3 to 8 kHz band down 4.96 dB; the owner then wrote "Body doesn't seem to do anything"),
     * so the climb is retired. What BODY adds above the knee is the lift ([liftDbFor]), on top of the plain note at the plain's own gain, and it is continuous there: 0 dB at the knee.
     */
    const val BODY_KNEE = 0.5f

    /** The box's amount at [body]: the knob's own value up to [BODY_KNEE], and the knee's amount above it. */
    internal fun boxAmountFor(body: Float): Float = min(body, BODY_KNEE)

    /** The longest string plus the box's ring, with room: [Strings.bodyRing]'s own ceiling. */
    const val BODY_CEILING_SECONDS = 8f

    // ---- BODY above the knee: the lift ----------------------------------------

    /**
     * What BODY adds above [BODY_KNEE]: a *lift* on top of the conditioned note, a low shelf and then a broad bell in series, each [liftDbFor] dB, at the plain's own gain (R1e's warmth page played this shape on the plain).
     * Its two sizes are R1e's rungs, per element: [LIFT_HALF_DB] at BODY 0.75 (R1e's small rung) and [LIFT_TOP_DB] at BODY 1 (its large rung). The owner marked some of those clips FULLER and some SAME, with low confidence
     * in his own words, and nobody has listened to this curve. Listening values.
     */
    const val LIFT_HALF_DB = 3.5f
    const val LIFT_TOP_DB = 6.0f

    /**
     * The lift BODY asks for, in dB for the shelf and the same again for the bell: none at or under [BODY_KNEE], then the one quadratic through zero at the knee, [LIFT_HALF_DB] at BODY 0.75 and [LIFT_TOP_DB] at BODY 1.
     * With `u` the way from the knee to BODY 1 (0 to 1) it is `(4 H - T) u + (2 T - 4 H) u^2` for H = [LIFT_HALF_DB] and T = [LIFT_TOP_DB], which for 3.5 and 6.0 is `8 u - 2 u^2`: 0.78 dB at BODY 0.55, 1.52 at 0.6,
     * 2.22 at 0.65, 2.88 at 0.7, 3.50 at 0.75, 4.08 at 0.8, 5.12 at 0.9, 5.58 at 0.95 and 6.00 at 1. It rises at every BODY above the knee (slope 8 at the knee, 4 at the top) as long as 3 T is more than 4 H, and it has
     * no step at the knee.
     */
    internal fun liftDbFor(body: Float): Float {
        if (body <= BODY_KNEE) return 0f
        val u = ((body - BODY_KNEE) / (1f - BODY_KNEE)).coerceIn(0f, 1f)
        return (4f * LIFT_HALF_DB - LIFT_TOP_DB) * u + (2f * LIFT_TOP_DB - 4f * LIFT_HALF_DB) * u * u
    }

    /**
     * The lift's two stages for each voice. CELLO: a low shelf with its corner at 200 Hz and a bell at 300 Hz, Q 0.8, broad on purpose (a held note's partials meet a narrow bell only where one happens to land, which
     * is what R1d's narrow box found). ERHU: a low shelf at 600 Hz and a bell at 800 Hz, Q 1.0 (R1e's scouts found a 450 Hz shelf lifts C5's fundamental only 1.4 dB, hence 600). The shelves have [Dsp.Biquad.lowShelf]'s
     * fixed slope (see [LiftSection]: the same section, run in Double). R1e's scouts, kept as they were; listening values.
     */
    const val CELLO_LIFT_SHELF_HZ = 200f
    const val CELLO_LIFT_BELL_HZ = 300f
    const val CELLO_LIFT_BELL_Q = 0.8f
    const val ERHU_LIFT_SHELF_HZ = 600f
    const val ERHU_LIFT_BELL_HZ = 800f
    const val ERHU_LIFT_BELL_Q = 1.0f

    /** The low shelf's corner for [voice]. */
    internal fun liftShelfHz(voice: ArcoVoice): Float = when (voice) {
        ArcoVoice.CELLO -> CELLO_LIFT_SHELF_HZ
        ArcoVoice.ERHU -> ERHU_LIFT_SHELF_HZ
    }

    /** The bell's centre for [voice]. */
    internal fun liftBellHz(voice: ArcoVoice): Float = when (voice) {
        ArcoVoice.CELLO -> CELLO_LIFT_BELL_HZ
        ArcoVoice.ERHU -> ERHU_LIFT_BELL_HZ
    }

    /** The bell's Q for [voice]. */
    internal fun liftBellQ(voice: ArcoVoice): Float = when (voice) {
        ArcoVoice.CELLO -> CELLO_LIFT_BELL_Q
        ArcoVoice.ERHU -> ERHU_LIFT_BELL_Q
    }

    /**
     * The bar every finished note is held to, strictly under it: ArcoTest and ArcoProductTest read this one number, 0.95, so the engine and its tests cannot disagree about it. It is never loosened. Not a listening value: it is the bar.
     */
    internal const val FINISHED_PEAK_BAR = 0.95f

    /**
     * What the lift is held to: the bar less 0.01. A note [Dsp.levelTo] limits sits at exactly 0.99 and would fail the bar, so the cap works on the lift and never on the level. Where the plain's own peak is already above
     * it the plain's peak is the cap instead, so a plain that is already hot gets no lift and there is no step at the knee. It sets how much lift the lowest CELLO notes can have (they are the ones that reach it). Listening value.
     */
    internal const val PEAK_CAP = FINISHED_PEAK_BAR - 0.01f

    /**
     * The cap search's bisection steps (the cap is found to 6 dB over 2 to the 20th) and how many times a delivered lift that fails its own peak check is halved before it is dropped to nothing. Not listening values: the cost
     * and the safety net of [liftPlan].
     */
    private const val LIFT_SEARCH_STEPS = 20
    private const val LIFT_HALVINGS_MAX = 8

    /**
     * What [finishedMeasured] did above the knee, in dB per element: what BODY [asked] for, the [cap] the note's own peak allows (found without looking at BODY, so it is one number for a note), what was [delivered] (the
     * least of the two, checked against the peak, and halved [halvings] times if the check failed, which a peak that rises with the lift never needs: a test asserts it is 0). At or under the knee nothing is asked or
     * delivered ([NONE]).
     */
    internal class Lift(val asked: Float, val cap: Float, val delivered: Float, val halvings: Int) {
        companion object {
            val NONE = Lift(0f, 0f, 0f, 0)
        }
    }

    /** The largest `|x * gain|` in [buf], computed the way [Dsp.limitPeak] reads a peak (one float multiply, then the magnitude). */
    private fun peakOf(buf: FloatArray, gain: Float = 1f): Float {
        var peak = 0f
        for (x in buf) {
            val a = abs(x * gain)
            if (a > peak) peak = a
        }
        return peak
    }

    /**
     * One RBJ cookbook section, its coefficients and its running state in Double: the lift's own filter, in place of [Dsp.Biquad]. The formulas are [Dsp.Biquad.lowShelf]'s and [Dsp.Biquad.peaking]'s, line for line (the same
     * shelf slope of 1, the same division by `a0`, the same direct form I recursion); only the arithmetic is wider. That is what the knee needs. At a 200 to 600 Hz corner and [RATE] the poles sit close to 1 (the cause as R1g
     * read it, not isolated), and [Dsp.Biquad]'s Float coefficients and Float state leave rounding of up to about 2e-4 of the peak on a CELLO note and 3e-5 on an ERHU one even at a lift of a few millionths of a dB (R1g's
     * measure over the 45 TUNE steps, the Float lift at BODY 0.5000001 against the plain: up to 2.09e-4 CELLO at step 9 and 3.19e-5 ERHU at step 1). That rounding is
     * larger than the lift itself at BODY 0.5000001, so with Dsp.Biquad the render just above the knee missed the knee's bar of 1e-5 of the peak by up to 2.1e-4 (CELLO). [Strings.Membrane] already runs its RBJ bank in
     * Double for the same reason. [Dsp] is not touched, so everything else that uses [Dsp.Biquad] keeps its Float sections.
     */
    private class LiftSection private constructor(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        private val nb0 = b0 / a0
        private val nb1 = b1 / a0
        private val nb2 = b2 / a0
        private val na1 = a1 / a0
        private val na2 = a2 / a0
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        /** One sample through the section, in Double. */
        fun process(x: Double): Double {
            val y = nb0 * x + nb1 * x1 + nb2 * x2 - na1 * y1 - na2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        companion object {
            /** [Dsp.Biquad.lowShelf]'s section (slope 1) for a corner of [f0] Hz and [gainDb] dB, at [rate]. */
            fun lowShelf(f0: Double, gainDb: Double, rate: Int): LiftSection {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2.0 * PI * f0 / rate
                val cw = cos(w0)
                val sw = sin(w0)
                val alpha = sw / 2.0 * sqrt(2.0)
                val sqA = sqrt(a)
                return LiftSection(
                    a * ((a + 1) - (a - 1) * cw + 2 * sqA * alpha),
                    2 * a * ((a - 1) - (a + 1) * cw),
                    a * ((a + 1) - (a - 1) * cw - 2 * sqA * alpha),
                    (a + 1) + (a - 1) * cw + 2 * sqA * alpha,
                    -2 * ((a - 1) + (a + 1) * cw),
                    (a + 1) + (a - 1) * cw - 2 * sqA * alpha,
                )
            }

            /** [Dsp.Biquad.peaking]'s section for a bell at [f0] Hz of [gainDb] dB and [q], at [rate]. */
            fun peaking(f0: Double, gainDb: Double, q: Double, rate: Int): LiftSection {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2.0 * PI * f0 / rate
                val cw = cos(w0)
                val sw = sin(w0)
                val alpha = sw / (2.0 * q)
                return LiftSection(
                    1 + alpha * a,
                    -2 * cw,
                    1 - alpha * a,
                    1 + alpha / a,
                    -2 * cw,
                    1 - alpha / a,
                )
            }
        }
    }

    /**
     * [src] through the lift at [db] dB per element, times [gain], and the largest magnitude of what comes out. [LiftSection.lowShelf] then [LiftSection.peaking], in series, at [RATE] (after the conditioning has
     * taken the DC off), one sample at a time, in Double from the sample in to the end of the second section and narrowed to Float once there, then times [gain] in Float as [Dsp.levelTo] multiplies; [src] is never
     * changed. With [out] given, the samples are written there as well, so the peak that was tested and the samples that ship come from the same arithmetic.
     */
    private fun liftPass(src: FloatArray, voice: ArcoVoice, db: Float, gain: Float, out: FloatArray?): Float {
        val shelf = LiftSection.lowShelf(liftShelfHz(voice).toDouble(), db.toDouble(), RATE)
        val bell = LiftSection.peaking(liftBellHz(voice).toDouble(), db.toDouble(), liftBellQ(voice).toDouble(), RATE)
        var peak = 0f
        for (i in src.indices) {
            val v = bell.process(shelf.process(src[i].toDouble())).toFloat() * gain
            if (out != null) out[i] = v
            val a = abs(v)
            if (a > peak) peak = a
        }
        return peak
    }

    /** A copy of the conditioned [conditioned] through the lift at [db] dB per element (a gain of 1): the shape alone, with no level and no cap. Probes and controls use it; [finishedMeasured] does not. */
    internal fun lifted(conditioned: FloatArray, voice: ArcoVoice, db: Float): FloatArray =
        FloatArray(conditioned.size).also { liftPass(conditioned, voice, db, 1f, it) }

    /**
     * How much of the lift [asked] a note can have: [peakAt] says the largest sample the note would have at a given lift, [limit] is the most it may have. The cap is the largest lift in 0 to [LIFT_TOP_DB] that fits: the top
     * is tried first (no search if it fits), else [LIFT_SEARCH_STEPS] bisection steps, and what is returned is always a lift that was itself tried and fitted (or 0, the plain, which fits by the way [limit] is made). It looks at
     * the note only, never at BODY, so a note has one cap and the knob never goes backwards over it. Delivered is the least of [asked] and the cap, checked once more (the cap search trusts that a peak rises with the lift, this
     * does not) and halved, up to [LIFT_HALVINGS_MAX] times, until it fits, then 0. [Dsp.limitPeak] never runs: the peak is met by lifting less, never by turning the note down.
     */
    private fun liftPlan(asked: Float, limit: Float, peakAt: (Float) -> Float): Lift {
        var cap = LIFT_TOP_DB
        if (peakAt(LIFT_TOP_DB) > limit) {
            var low = 0f
            var high = LIFT_TOP_DB
            repeat(LIFT_SEARCH_STEPS) {
                val mid = (low + high) / 2f
                if (peakAt(mid) <= limit) low = mid else high = mid
            }
            cap = low
        }
        var delivered = min(asked, cap)
        var halvings = 0
        while (delivered > 0f && peakAt(delivered) > limit) {
            if (halvings == LIFT_HALVINGS_MAX) {
                delivered = 0f
                break
            }
            delivered /= 2f
            halvings++
        }
        return Lift(asked, cap, delivered, halvings)
    }

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

    /**
     * [withBody], then [condition]: the string through the box and the output chain's cleaning (the band limit, the decimation to [RATE], the mean and the 20 Hz high-pass), run once. It is the one place BODY is composed with the
     * string, and the stage every branch of [finished] and of the loop starts from. [raw] is band-limited in place at BODY 0 (the box hands back the string itself), so pass a copy to keep it.
     */
    internal fun conditionedWithBody(raw: FloatArray, voice: ArcoVoice, body: Float, rate: Int): FloatArray =
        condition(withBody(raw, voice, body, rate), rate)

    /**
     * [Dsp.levelTo]'s own numbers, copied because its defaults cannot be read from here: a signal [Loudness.of] puts at or under [LEVEL_SILENCE] is left alone, and no sample is left past [LEVEL_CEILING]. A test holds [plainGain]
     * equal to [Dsp.levelTo] on a normal and a hot buffer, so a change to either number there fails here. Not listening values.
     */
    private const val LEVEL_SILENCE = 1e-6f
    private const val LEVEL_CEILING = 0.99f

    /**
     * The gain [Dsp.levelTo] puts on [conditioned], as one number, without changing it: [Dsp.MELODIC_LOUDNESS_TARGET] over its [Loudness.of], and then, if that puts a sample past [LEVEL_CEILING], scaled so the largest sample sits
     * there (levelTo's own limiter step; its two multiplies come out as one here, which is a last-place rounding from them). A signal [Loudness.of] cannot see is left as levelTo leaves it, a gain of 1 (so a silent note stays exactly silent
     * and nothing is divided by zero). Above the knee every note gets the plain's gain: the box is frozen there, so the conditioned note is the plain's conditioned note, and nothing re-levels the lift away.
     */
    internal fun plainGain(conditioned: FloatArray): Float {
        if (conditioned.isEmpty()) return 1f
        val measured = Loudness.of(Snip(conditioned.copyOf(), channels = 1, sampleRate = RATE))
        if (measured <= LEVEL_SILENCE) return 1f
        val gain = Dsp.MELODIC_LOUDNESS_TARGET / measured
        val peak = peakOf(conditioned, gain)
        return if (peak > LEVEL_CEILING) gain * (LEVEL_CEILING / peak) else gain
    }

    /** The plain's finish, in place on [conditioned]: [Dsp.levelTo] to [Dsp.MELODIC_LOUDNESS_TARGET], then the 4 ms [Dsp.fadeTail]. Today's two statements, in today's order. */
    private fun levelled(conditioned: FloatArray): FloatArray {
        Dsp.levelTo(conditioned, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(conditioned)
        return conditioned
    }

    /** A finished note and what the lift did to it ([Lift.NONE] at or under the knee). */
    internal class Finished(val samples: FloatArray, val lift: Lift)

    /**
     * The one finish of a note: the string [raw] at the oversampled [rate], through the box, the output chain and BODY [body]. Every render and every test, probe and generator copy that was re-pointed finishes through this one
     * function, so such a copy cannot test a path the engine does not play. The declared exceptions, each on purpose: the frozen reference (ArcoFrozenR1cTest, today's pipeline kept verbatim to hold the sound at and under the knee), the cap-off
     * controls and the CI-equality rebuild (ArcoBodyLiftTest, built from [conditionedWithBody], [plainGain] and [lifted] so each can fail where the engine's own finish cannot), and the R1e helper's own copy of the conditioning
     * (ArcoWarmthCandidates, which exists to be compared with this function). [raw] is band-limited in place at BODY 0 ([conditionedWithBody]), so pass a copy to keep it.
     *
     * At or under [BODY_KNEE] it is today's finish to the sample: the same statements in the same order, which is a branch and not a 0 dB filter (a 0 dB biquad is not bit-identical to none). Above it the box is the knee's
     * ([boxAmountFor]) and the note keeps the plain's own gain ([plainGain]), with [lifted]'s shape by [liftDbFor] on top, backed off only as far as the peak needs ([PEAK_CAP], or the plain's own peak if that is higher; see
     * [liftPlan]). [Dsp.levelTo] and [Dsp.limitPeak] never run after the lift: the level is not matched back to the plain and the top of the note is never turned down. The last step is the 4 ms [Dsp.fadeTail], which only lowers
     * a peak. If nothing can be lifted the result is the plain's finish exactly.
     */
    internal fun finished(raw: FloatArray, voice: ArcoVoice, body: Float, rate: Int): FloatArray =
        finishedMeasured(raw, voice, body, rate).samples

    /** [finished] and what the lift did ([Lift]): the same function, so a test reads the engine's own numbers. */
    internal fun finishedMeasured(raw: FloatArray, voice: ArcoVoice, body: Float, rate: Int): Finished {
        val conditioned = conditionedWithBody(raw, voice, body, rate)
        if (body <= BODY_KNEE) return Finished(levelled(conditioned), Lift.NONE)
        val gain = plainGain(conditioned)
        val limit = max(PEAK_CAP, peakOf(conditioned, gain))
        val lift = liftPlan(liftDbFor(body), limit) { db -> liftPass(conditioned, voice, db, gain, null) }
        if (lift.delivered <= 0f) return Finished(levelled(conditioned), lift)
        val out = FloatArray(conditioned.size)
        liftPass(conditioned, voice, lift.delivered, gain, out)
        Dsp.fadeTail(out)
        return Finished(out, lift)
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
     * [share] replaces the voice's tuning share, [biteSeconds] the least time constant the bite relaxes with (the voice's own, [biteSeconds] the
     * function, otherwise), [vibratoShape] the finger's movement ([vibratoShapeFor] the voice otherwise), [overshootMax] BOW 1's bite for this note
     * (above 1: the velocity's, and, in step with it, the pressure's share of the window), [pressureBite] the part of that share that presses the
     * string (the voice's own, [bitePressure], otherwise). [overshoot] sets only the velocity's bite and takes the pressure's share of it from the
     * voice's own maximum, so it is not a way to play another voice's bite.
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
        biteSeconds: Float? = null,
        vibratoShape: VibratoShape? = null,
        overshootMax: Float? = null,
        pressureBite: Float? = null,
    ): FloatArray {
        val gate = gateFor(voice, hz, macros, rate, gateSeconds)
        val tap = play(voice, hz, macros, rate, gate, pressure, cornerHz, vBow, overshoot, lifted, bowPointOut, share, biteSeconds, overshootMax, pressureBite)
        return if (vibrato) vibrato(tap, vibratoCentsFor(gate.holdN.toFloat() / rate), rate, vibratoShape ?: vibratoShapeFor(voice)) else tap
    }

    private fun play(
        voice: ArcoVoice, hz: Float, macros: Map<String, Float>, rate: Int, gate: Gate,
        pressure: Float?, cornerHz: Float?, vBow: Float?, overshoot: Float?,
        lifted: Boolean, bowPointOut: FloatArray?, share: Float?, biteSecondsOverride: Float? = null, overshootMaxOverride: Float? = null,
        pressureBiteOverride: Float? = null,
    ): FloatArray {
        val semitone = semitoneFor(voice, macros.getValue("TUNE"))
        val grip = macros.getValue("GRIP")
        val window = gripFor(voice, semitone)
        val p = pressure ?: pressureFor(voice, semitone, grip)
        val corner = cornerHz ?: cornerFor(voice, semitone, grip)
        val vSustain = vBow ?: V_SUSTAIN
        val steady = gate.steady
        val biteMax = overshootMaxOverride ?: overshootMax(voice)
        val over = if (steady) 1f else overshoot ?: overshootFor(voice, macros.getValue("BOW"), biteMax)
        val biteShare = if (biteMax > 1f) ((over - 1f) / (biteMax - 1f)).coerceIn(0f, 1f) else 0f
        val pBite = Dsp.lin(biteShare * (pressureBiteOverride ?: bitePressure(voice)), p, max(p, window.pressureHigh))
        val biteN = max(gate.attackN, ((biteSecondsOverride ?: biteSeconds(voice)) * rate).toInt())
        val tau = biteN.toDouble() / rate

        val bow = Strings.Bow(f = hz, beta = BETA, bridgeHz = corner, share = share ?: shareFor(voice), rate = rate)
        if (lifted) bow.lift()

        val out = FloatArray(gate.total)
        val liftAt = gate.holdN + gate.rampN
        val bite = if (over > 1f) (over - 1f).toDouble() else 0.0
        val biteEnd = BITE_TIME_CONSTANTS * biteN
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

    /** The bite has relaxed to nothing (to under a millionth of itself) after this many of its own time constants (the longer of the attack and [biteSeconds]). */
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

    /** A rendered LOOP, how well its stretch closes on itself ([Keys.seamError]), and what the lift did to it ([Lift.NONE] at or under the knee). */
    internal class LoopRender(val loop: FloatArray, val seam: Double, val lift: Lift = Lift.NONE)

    /** What [cutLoop] reads off a conditioned stretch: its seam, the kept window [one], where the cut falls in it, and the window rotated to start at the cut. */
    private class LoopCut(val seam: Double, val one: FloatArray, val cut: Int, val loop: FloatArray)

    /** [one] rotated to start at [cut]: from the cut to the end, then from the start to the cut. */
    private fun rotated(one: FloatArray, cut: Int): FloatArray = one.copyOfRange(cut, one.size) + one.copyOfRange(0, cut)

    /**
     * The seam, cut and rotate tail of a LOOP, for the plain stretch and for the lifted one alike (one helper, so the two cannot drift apart): the seam is read on [stretch] ([Keys.seamError], over the [frames] kept after
     * [warm] frames and the [SEAM_FRAMES] before the wrap), the kept window is cut where the two neighbours are smallest ([Siren.bestCut]) and rotated to start there. Not levelled: the caller does that.
     */
    private fun cutLoop(stretch: FloatArray, warm: Int, frames: Int): LoopCut {
        val seam = Keys.seamError(stretch.copyOfRange(warm, warm + frames + SEAM_FRAMES), SEAM_FRAMES)
        val one = stretch.copyOfRange(warm, warm + frames)
        val cut = Siren.bestCut(one, frames)
        return LoopCut(seam, one, cut, rotated(one, cut))
    }

    /** The plain's loop: the cut loop levelled by [Dsp.levelTo], in place, today's last line (no fade: a loop wraps). */
    private fun levelledLoop(cut: LoopCut): LoopRender {
        Dsp.levelTo(cut.loop, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        return LoopRender(cut.loop, cut.seam)
    }

    /**
     * A LOOP from its conditioned stretch at BODY [body]. At or under [BODY_KNEE] it is the old lines: the seam, cut and rotate tail on the conditioned stretch, then [Dsp.levelTo]. Above it the lift runs on the whole
     * conditioned stretch before the seam is read (the filters have settled long before the kept window begins, and the seam is the lifted note's own), the cut is chosen on the lifted window, and the gain is the plain's:
     * [plainGain] of the plain's own window rotated at that same cut, so nothing levels the lift away. The cap is found on that finished loop (its peak is at most [PEAK_CAP], or the plain's own if that is higher), tried at
     * every lift it tests with its own cut and gain, so what is checked is what ships. If nothing can be lifted the result is the plain's loop exactly.
     */
    private fun loopFrom(conditioned: FloatArray, voice: ArcoVoice, body: Float, warm: Int, frames: Int): LoopRender {
        val plain = cutLoop(conditioned, warm, frames)
        if (body <= BODY_KNEE) return levelledLoop(plain)
        val limit = max(PEAK_CAP, peakOf(plain.one, plainGain(plain.loop)))

        fun liftedAt(db: Float): LoopRender {
            val t = cutLoop(lifted(conditioned, voice, db), warm, frames)
            val gain = plainGain(rotated(plain.one, t.cut))
            for (i in t.loop.indices) t.loop[i] *= gain
            return LoopRender(t.loop, t.seam)
        }

        val lift = liftPlan(liftDbFor(body), limit) { db -> peakOf(liftedAt(db).loop) }
        if (lift.delivered <= 0f) return LoopRender(levelledLoop(plain).loop, plain.seam, lift)
        val r = liftedAt(lift.delivered)
        return LoopRender(r.loop, r.seam, lift)
    }

    /**
     * The held note as one loop, [Bore.LoopPlan.periods] whole periods in [Bore.LoopPlan.frames] whole frames, so the wrap is
     * seamless: a steady bow, the warm-up discarded, the *played* pitch corrected until the periods fill the
     * frames (BORE's route: a fresh bow rendered at `tuned * ratio` until the ratio is 1 to 3e-7), then cut where
     * the two neighbours are smallest and levelled. A bow's pitch is a limit cycle's, not an accumulator's, so
     * the planned pitch does not close it; the measured one does. BODY is composed with the stretch once ([conditionedWithBody]) and
     * finished by [loopFrom]: at or under the knee as it always was, above it with the lift on the stretch before the seam is read, the cut chosen on the lifted window and the plain's gain.
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
        val body = m.getValue("BODY")
        return loopFrom(conditionedWithBody(raw, voice, body, rate), voice, body, warm, plan.frames)
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

    /**
     * One note: [renderLoop] at HOLD's top step, else the bow at the note's exact pitch finished by [finished], the one place BODY, the level and the lift are worked out. The render is a pure function of [macros] and its
     * length is the string's own at every BODY (the box is cut to it and the lift runs on a buffer of the same length), so [renderFrames] and [drumClassFor] stay closed form.
     */
    fun render(voice: ArcoVoice, macros: Map<String, Float> = emptyMap()): Snip {
        val m = settled(macros, voice)
        if (isLoop(m.getValue("HOLD"))) return Snip(renderLoop(voice, m), channels = 1, sampleRate = RATE)
        val hz = frequencyFor(voice, m.getValue("TUNE"))
        val rate = RATE * Dsp.OVERSAMPLE
        val raw = bow(voice, hz, m, rate)
        return Snip(finished(raw, voice, m.getValue("BODY"), rate), channels = 1, sampleRate = RATE)
    }
}
