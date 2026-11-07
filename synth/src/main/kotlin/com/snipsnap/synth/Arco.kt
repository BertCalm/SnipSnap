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
 * stroke, the grip, the box, the lift that BODY adds above the middle of the knob, and the stop.
 *
 * Three voices, one string each:
 *  - [ArcoVoice.CELLO]: C2 to C4, the bottom string up two octaves, into a two-mode box (an air
 *    resonance and a plate resonance).
 *  - [ArcoVoice.ERHU]: D4 to A5, a two-stringed fiddle's inner string into a membrane box.
 *  - [ArcoVoice.SARANGI] (R2): D3 to D5, the same bow in a sarangi's register, into ERHU's skin at its default, then a bank of
 *    sympathetic strings (the tarab) that WASH sets in BODY's place ([Arco.withTarab]). Nobody has heard it.
 *
 * BODY is two things, split at [Arco.BODY_KNEE] (R1g). Up to the middle of the knob it is how loud
 * the box rings against the string. Above it the box stays at its middle size and BODY adds a low
 * shelf and a broad bell on the note, at the plain's own gain, backed off per note only where the
 * finished peak would pass [Arco.PEAK_CAP] ([Arco.finished], [Arco.liftDbFor]). Nothing is turned
 * down at the top.
 *
 * BUZZ (R2, the sixth knob) is a loose bridge rattling by analogy: the part of the string's wave above a threshold, referenced to
 * the note's own level, multiplied by noise, before the box ([Arco.buzzed]). It is off by default and exactly off at 0, and in a LOOP
 * its noise repeats with the loop, so the loop still closes. Nobody has heard it.
 *
 * A LOOP carries a vibrato since R2 ([Arco.loopVibrato]): the owner heard R1's loops as "a bit cheap" and "mechanical sounding". It is
 * the one-shot's vibrato at full depth with every movement in it fitted to a whole number of cycles per loop, and humanised (the rate and
 * the depth drift within the loop), so it repeats with the loop and the seam is the string's own.
 *
 * A held key (R2, [Arco.renderHeldMeasured], played by [ArcoKeys]) is the one-shot's own stroke on a bow that never lifts, then that LOOP,
 * cut where the string repeats: a keygroup zone with an attack and a sustain loop, and never a crossfade. Nobody has heard it.
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
 * was *listened to* when R1b was written. The owner has since heard R1b's held notes, a stab and
 * the knob ends (the bowed note was picked out six times in six; BODY 1, CELLO's BOW 1 and CELLO's
 * vibrato were "nearly"), R1c's retune of those three (the bite, the stab and the vibrato yes;
 * BODY 1 still "doesn't seem to do anything"), and four BODY pages (R1d, R1e, R1h and R1j; R1g
 * rebuilt BODY from the answers, the design record's R1h paragraph says R1h's page came back void
 * by its own rules, and its R1j paragraphs say R1j's page, at C2 and F#2, left the lift as built).
 * The owner has not heard the presets, the kit or the loops. Every value marked
 * "listening" is provisional: the BODY lift's two sizes were played on R1e's and R1h's pages (the
 * last of them void by its own rules, so it settles little), and the rest are first guesses for
 * ears that have not heard them.
 *
 * The engine renders **dry**, mono, with no landing chain: a bowed note's own tail is its stop.
 */
enum class ArcoVoice { CELLO, ERHU, SARANGI }

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

    /**
     * SARANGI's root, D3 (146.83 Hz): the owner's pick of 2026-11-07. A solo sarangi's Sa sits near D#3 (the research pass's S7, a
     * survey of recordings, secondary), and D3 is the nearest natural root, so the knob's centre is D4, ERHU's own root.
     */
    const val SARANGI_ROOT_MIDI = 50

    /** SARANGI's TUNE travel: two octaves, D3 to D5, the register the research pass puts a sarangi's playing range in. Every step speaks (the window, [gripFor]). */
    const val SARANGI_TUNE_SEMITONES = 24

    fun tuneSemitones(voice: ArcoVoice): Int = when (voice) {
        ArcoVoice.CELLO -> CELLO_TUNE_SEMITONES
        ArcoVoice.ERHU -> ERHU_TUNE_SEMITONES
        ArcoVoice.SARANGI -> SARANGI_TUNE_SEMITONES
    }

    fun rootMidi(voice: ArcoVoice): Int = when (voice) {
        ArcoVoice.CELLO -> CELLO_ROOT_MIDI
        ArcoVoice.ERHU -> ERHU_ROOT_MIDI
        ArcoVoice.SARANGI -> SARANGI_ROOT_MIDI
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

    /**
     * The six knobs. BUZZ (R2) is last and off by default, so a patch saved before it existed loads at 0 ([settled] fills it) and
     * renders as it always did: see [DEFAULT_BUZZ]. SARANGI has WASH in BODY's place (the owner's decision of 2026-11-07), so it keeps six:
     * its box is ERHU's skin held at BODY's default ([bodyOf]) and the knob in that slot is the tarab ([withTarab]).
     */
    fun macrosFor(voice: ArcoVoice): List<MacroSpec> = listOf(
        MacroSpec("TUNE", 0.5f, neutral = 0.5f),
        MacroSpec("BOW", DEFAULT_BOW),
        MacroSpec("GRIP", DEFAULT_GRIP, neutral = 0.5f),
        when (voice) {
            ArcoVoice.CELLO, ArcoVoice.ERHU -> MacroSpec("BODY", DEFAULT_BODY)
            ArcoVoice.SARANGI -> MacroSpec("WASH", DEFAULT_WASH)
        },
        MacroSpec("HOLD", DEFAULT_HOLD),
        MacroSpec("BUZZ", DEFAULT_BUZZ),
    )

    fun defaults(voice: ArcoVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    /** Whether [voice] has a BODY knob: CELLO and ERHU do; SARANGI has WASH in its place. */
    fun hasBody(voice: ArcoVoice): Boolean = when (voice) {
        ArcoVoice.CELLO, ArcoVoice.ERHU -> true
        ArcoVoice.SARANGI -> false
    }

    /** The name of the fourth knob, the box's or the tarab's: BODY on CELLO and ERHU, WASH on SARANGI. */
    fun boxKnob(voice: ArcoVoice): String = if (hasBody(voice)) "BODY" else "WASH"

    /**
     * The BODY a render plays at, from [settled] macros: the knob's own value where [voice] has one, and [DEFAULT_BODY] on SARANGI, whose
     * box is fixed there (the box at half the string and no lift: [BODY_KNEE] is the default, so nothing above the knee is ever reached).
     */
    internal fun bodyOf(voice: ArcoVoice, m: Map<String, Float>): Float = if (hasBody(voice)) m.getValue("BODY") else DEFAULT_BODY

    /** The WASH a render plays at, from [settled] macros: SARANGI's knob, and 0 (no tarab, [withTarab] hands the buffer back) on the others. */
    internal fun washOf(voice: ArcoVoice, m: Map<String, Float>): Float = if (hasBody(voice)) 0f else m.getValue("WASH")

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

    /**
     * SARANGI's pressure floor: ERHU's 0.94 at every step from D3 to D5, one law across the span with no seam at D4, where CELLO's 0.85 and ERHU's 0.94
     * would otherwise meet. R2 drew the window first (ArcoSarangiWindowMap, on the bare bow at BOW 0.5, every semitone): every cell of pressure 0.85 to 1.0
     * by corner 1000 to 3024 Hz speaks from D3 to E4, and above that the dark corners stop locking as the note rises (1000 Hz at no pressure from G4, 1150 Hz
     * from A4, 1300 Hz from C5, 1500 Hz at D5), so the floor is set by pitch and the corner by the lock. Walked along GRIP (0 to 1 in tenths), this floor with [SARANGI_CORNER_LOW_ROOT_HZ]'s
     * corner speaks at all 275 points (the slowest lock 0.65 s, D#3 at GRIP 0) and GRIP moves the note by at most 3.98 cents (D5); a floor of 0.85 on the
     * same corner also speaks everywhere but moves B4 by 5.02 cents and D5 by 6.34, over the record's 5 cent bar (ArcoTest's pitch-knob test holds both),
     * and CELLO's flat 1000 Hz corner never locks at GRIP 0 from F4 up (26 of 275 points fail). So GRIP is a brightness and a grip on SARANGI as on the
     * others, and not a tuning knob. See [SARANGI_CORNER_LOW_ROOT_HZ] for the corner.
     */
    const val SARANGI_PRESSURE_LOW = ERHU_PRESSURE_LOW

    /**
     * SARANGI's corner at GRIP 0: ERHU's corner floor in absolute pitch carried an octave further down. ERHU's floor is 1500 Hz at D4 and reaches
     * the full corner [ERHU_CORNER_RISE_SEMITONES] semitones up; the same line, one octave below D4, is at D3 [SARANGI_CORNER_LOW_ROOT_HZ], and it
     * reaches the full corner [SARANGI_CORNER_RISE_SEMITONES] semitones above D3, so at every note from D4 to D5 SARANGI's corner floor is ERHU's
     * own, and below D4 it falls toward CELLO's 1000 Hz. GRIP 1 is the full corner everywhere.
     */
    val SARANGI_CORNER_LOW_ROOT_HZ: Float =
        (ERHU_CORNER_LOW_ROOT_HZ * Math.pow((Strings.Bow.BRIDGE_HZ / ERHU_CORNER_LOW_ROOT_HZ).toDouble(), -12.0 / ERHU_CORNER_RISE_SEMITONES)).toFloat()
    const val SARANGI_CORNER_RISE_SEMITONES = ERHU_CORNER_RISE_SEMITONES + 12

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
        ArcoVoice.SARANGI -> Grip(
            SARANGI_PRESSURE_LOW, PRESSURE_HIGH,
            Dsp.expMap(min(semitone.toFloat() / SARANGI_CORNER_RISE_SEMITONES, 1f), SARANGI_CORNER_LOW_ROOT_HZ, Strings.Bow.BRIDGE_HZ),
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
        ArcoVoice.SARANGI -> Strings.Bow.SHARE
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

    /** SARANGI's bite: ERHU's three numbers, the bite the owner heard as a yes, on the same skin. */
    const val OVERSHOOT_MAX_SARANGI = OVERSHOOT_MAX_ERHU
    const val BITE_SECONDS_SARANGI = BITE_SECONDS_ERHU
    const val BITE_PRESSURE_SARANGI = BITE_PRESSURE_ERHU

    fun attackSeconds(bow: Float): Float = Dsp.expMap(bow, ATTACK_SLOW_SECONDS, ATTACK_FAST_SECONDS)

    /** BOW 1's bite, as a multiple of the sustain velocity, for [voice]. */
    internal fun overshootMax(voice: ArcoVoice): Float = when (voice) {
        ArcoVoice.CELLO -> OVERSHOOT_MAX_CELLO
        ArcoVoice.ERHU -> OVERSHOOT_MAX_ERHU
        ArcoVoice.SARANGI -> OVERSHOOT_MAX_SARANGI
    }

    /** The least time constant the bite relaxes with, for [voice]: its time constant is the longer of this and the stroke's attack. */
    internal fun biteSeconds(voice: ArcoVoice): Float = when (voice) {
        ArcoVoice.CELLO -> BITE_SECONDS_CELLO
        ArcoVoice.ERHU -> BITE_SECONDS_ERHU
        ArcoVoice.SARANGI -> BITE_SECONDS_SARANGI
    }

    /** How much of the bite's share goes into the pressure's climb toward the window's top, for [voice]: ERHU's is all of it (as it was). */
    internal fun bitePressure(voice: ArcoVoice): Float = when (voice) {
        ArcoVoice.CELLO -> BITE_PRESSURE_CELLO
        ArcoVoice.ERHU -> BITE_PRESSURE_ERHU
        ArcoVoice.SARANGI -> BITE_PRESSURE_SARANGI
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
     * [VIBRATO_HOLD_FULL_SECONDS]. A LOOP carries its own version since R2 ([loopVibrato]): this one does not repeat, and a loop cannot carry
     * a signal that does not. Listening values.
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
            out[i] = cubicAt(buf, i - depth * rise * vibratoUnit(u, shape), last)
        }
        return out
    }

    /** [buf] read at the fractional place [pos] by a four-point cubic (Catmull-Rom), the ends held: the vibrato's one read, for a note and for a LOOP. */
    private fun cubicAt(buf: FloatArray, pos: Double, last: Int): Float {
        val k = Math.floor(pos).toInt()
        val x = (pos - k).toFloat()
        val p0 = buf[(k - 1).coerceIn(0, last)]
        val p1 = buf[k.coerceIn(0, last)]
        val p2 = buf[(k + 1).coerceIn(0, last)]
        val p3 = buf[(k + 2).coerceIn(0, last)]
        return p1 + 0.5f * x * (p2 - p0 + x * (2f * p0 - 5f * p1 + 4f * p2 - p3 + x * (3f * (p1 - p2) + p3 - p0)))
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

    /**
     * Each voice's finger. SARANGI's is the human one: a sarangi's left hand slides on its nails rather than pressing a board (the research
     * pass's S2), and the owner has picked the human shape twice over the plain one (CELLO's vibrato in R1c, and "Humanized is best" for the loop).
     */
    internal fun vibratoShapeFor(voice: ArcoVoice): VibratoShape = when (voice) {
        ArcoVoice.CELLO -> VIBRATO_HUMAN
        ArcoVoice.ERHU -> VIBRATO_PLAIN
        ArcoVoice.SARANGI -> VIBRATO_HUMAN
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

    /** Each voice's box. SARANGI's is ERHU's skin (a goat-skin belly, as the erhu's is a python skin; no measured sarangi body mode was found), played at [DEFAULT_BODY]. */
    internal fun bodyFor(voice: ArcoVoice): List<Modes.Mode> = when (voice) {
        ArcoVoice.CELLO -> CELLO_BODY
        ArcoVoice.ERHU -> ERHU_BODY
        ArcoVoice.SARANGI -> ERHU_BODY
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
     * Its two sizes are R1e's rungs, per element: [LIFT_HALF_DB] at BODY 0.75 (R1e's small rung) and [LIFT_TOP_DB] at BODY 1 (its large rung). The owner marked some of those clips FULLER and some SAME on R1e's page, with low
     * confidence in the owner's own words. R1h then played this curve on a blind page (CELLO C3 at BODY 0.6, 0.75, 0.9 and 1.0; BODY 0.75 and 1.0 at ERHU C5 and CELLO C2; BODY 1.0 at ERHU A5) that came back void by its own
     * rules (the design record's R1h paragraph); with that caveat the owner called BODY 1.0 FULLER at C3, C5 and A5 and kept BODY 1.0 at the top of the knob for both voices. R1j (2026-10-06 to 2026-10-13) then put CELLO C2 and F#2, where the cap holds the lift back,
     * to a page with the shelf and the bell unequal at the same peak (the design record's R1j paragraphs): the owner could not tell the more-bell split from this equal split, and it stays. Listening values.
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

    /** The low shelf's corner for [voice]. SARANGI has no BODY knob, so its BODY never passes the knee and its lift is never played; its arms are its skin's, ERHU's. */
    internal fun liftShelfHz(voice: ArcoVoice): Float = when (voice) {
        ArcoVoice.CELLO -> CELLO_LIFT_SHELF_HZ
        ArcoVoice.ERHU, ArcoVoice.SARANGI -> ERHU_LIFT_SHELF_HZ
    }

    /** The bell's centre for [voice]. */
    internal fun liftBellHz(voice: ArcoVoice): Float = when (voice) {
        ArcoVoice.CELLO -> CELLO_LIFT_BELL_HZ
        ArcoVoice.ERHU, ArcoVoice.SARANGI -> ERHU_LIFT_BELL_HZ
    }

    /** The bell's Q for [voice]. */
    internal fun liftBellQ(voice: ArcoVoice): Float = when (voice) {
        ArcoVoice.CELLO -> CELLO_LIFT_BELL_Q
        ArcoVoice.ERHU, ArcoVoice.SARANGI -> ERHU_LIFT_BELL_Q
    }

    /**
     * The bar every finished note is held to, strictly under it: ArcoTest and ArcoProductTest read this one number, 0.95, so the engine and its tests cannot disagree about it. It is never loosened. Not a listening value: it is the bar.
     */
    internal const val FINISHED_PEAK_BAR = 0.95f

    /**
     * What the lift is held to: the bar less 0.01. A note [Dsp.levelTo] limits sits at exactly 0.99 and would fail the bar, so the cap works on the lift and never on the level. Where the plain's own peak is already above
     * it the plain's peak is the cap instead, so a plain that is already hot gets no lift and there is no step at the knee. It sets how much lift the lowest CELLO notes can have (they are the ones that reach it). R1j (2026-10-06 to 2026-10-13) measured
     * spending this ceiling on the bell instead of the shelf at CELLO C2 and F#2, and the owner listened to it (the design record's R1j paragraphs): the owner could not tell the more-bell split from the lift as built, so the cap and the equal split stay as they are. Listening value.
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
     * [conditionedWithBody], then the tarab at [wash] ([withTarab]): the stage a LOOP and a held key finish from (a one-shot's finish,
     * [finishedMeasured], makes the same two calls and fades the tarab out at the note's end). At [wash] 0 (every CELLO and ERHU render, and
     * SARANGI at WASH 0) it is [conditionedWithBody]'s own buffer, handed on untouched.
     */
    internal fun conditionedFor(raw: FloatArray, voice: ArcoVoice, body: Float, wash: Float, rate: Int): FloatArray =
        withTarab(conditionedWithBody(raw, voice, body, rate), wash)

    // ---- WASH: the tarab (SARANGI) ----------------------------------------------

    /**
     * WASH's default on SARANGI: the bank in the middle of its range, so a default note carries the tarab that names the voice. A listening
     * value nobody has heard; SANTUR's WASH was heard as "too quiet/absent" twice before its default went to 0.85, which is why this one is not low.
     */
    const val DEFAULT_WASH = 0.5f

    /**
     * The tarab's tuning: SANTUR's bank ([Silk.washModesFor]) on [SilkScales.CHROMATIC] from SARANGI's root, three octaves: D3 to D6 every
     * semitone, 37 strings (a sarangi carries up to about 35 to 37, and the one row every sarangi has is chromatic: the research pass's S4 and
     * S5). Fixed: a sarangi's tarab do not follow the note, so TUNE never moves it, and a note rings the strings its harmonics land on.
     */
    val TARAB_ROOT_HZ: Float = Keys.midiHz(SARANGI_ROOT_MIDI)

    /**
     * How long the tarab rings, from WASH's first step to its top: the bank's t60 is `lin(WASH, low, high)`. Short on purpose (the owner's
     * decision: at most about 2 s, so a LOOP still closes): the bank must settle inside the LOOP's 2.0 s warm-up, and the research pass's Python
     * stand-in measured 1.1 s closing to 3e-12 and 5 s failing at 1.2e-3. A longer ring is also a narrower one, so the bow's few cents decide how
     * loud a tarab is (at 3 s, 4 cents cost 7.4 dB; at 1.1 s, 2.1 dB). Listening values; ArcoSarangiTest measures the LOOP's seam at the top.
     */
    const val WASH_T60_LOW_SECONDS = 0.5f
    const val WASH_T60_HIGH_SECONDS = 1.5f

    /**
     * How loud the tarab is against the string at WASH 1: [Strings.bodyRing]'s `amount`, the bank's RMS over the string's own, so `lin(WASH, 0,
     * this)`: at the default WASH 0.5 the bank is half the string's RMS (6 dB under it), at WASH 1 as loud as the string. SANTUR's goes to 8 on a
     * plucked note; a bowed note drives the bank for as long as it is held, so its steady ring is a far larger share of the note. The first build
     * here went to 3 (the default bank 1.5 times the string, 3.5 dB over it), and three tests read what that does to a bowed note: the bank rings
     * at fixed pitches, so it held the 3 s note's measured vibrato to 4.7 cents against the voice's own 6 to 15 at WASH 0, and it diluted BUZZ so
     * that BUZZ 0.9 moved the default note by 0.027 (the house's bar is 0.04; 0.062 to 0.070 with no tarab). At 1 the bank is a halo on the
     * string, not a second instrument over it. A listening value: SANTUR's own WASH was heard "too quiet/absent" twice before it was raised, so
     * this is the number the owner's ear is most likely to move.
     */
    const val WASH_AMOUNT_HIGH = 1f

    /**
     * How long a one-shot's tarab takes to fade at the end of the note: a raised cosine over the last this-many seconds on the bank alone, ending
     * where the string ends. The bank is cut where the string ends (the owner's decision, as the box is), but the box rings for 0.3 s and the
     * bank for up to [WASH_T60_HIGH_SECONDS], so where the stopped string has fallen 60 dB the bank is still ringing: measured at WASH 1 with the
     * first build's bank, a stab's last 20 ms sat only 2.4 dB under the note's RMS, and the house's 4 ms [Dsp.fadeTail] would cut that off as a
     * click. Over 50 ms the bank's lowest string (D3) makes seven cycles of fade, not half of one. The note's length is the string's either way.
     * A LOOP and a held key never end inside their kept window, so they carry no such fade. A listening value.
     */
    const val TARAB_END_FADE_SECONDS = 0.05f

    internal fun washT60(wash: Float): Float = Dsp.lin(wash, WASH_T60_LOW_SECONDS, WASH_T60_HIGH_SECONDS)

    internal fun washAmount(wash: Float): Float = Dsp.lin(wash, 0f, WASH_AMOUNT_HIGH)

    /** The bank at [wash]'s t60: [TARAB_ROOT_HZ] up three octaves, every semitone, each mode at gain 1 ([Silk.washModesFor]'s uniform gain). */
    internal fun tarabModes(wash: Float): List<Modes.Mode> = Silk.washModesFor(TARAB_ROOT_HZ, SilkScales.CHROMATIC, gain = 1f, t60 = washT60(wash))

    /**
     * The tarab: [conditioned] (the string after the box, at [RATE]) rung through the sympathetic bank ([tarabModes]) by [Strings.bodyRing], the
     * call [withBody] makes for the box and SANTUR's `withWash` makes for its own bank, and **cut where the string ends**, as the box is: the
     * bank's ring past the end of the note is not kept, so WASH never moves a note's length and [renderFrames] and [drumClassFor] stay closed form.
     * The 4 ms fade at the end of the render finishes it.
     *
     * Run after the band limit and the decimator, at [RATE], not at the oversampled rate the box runs at: the bank's top string is D6
     * (1174.66 Hz), far under any fold, and a two-pole resonator in Float tunes itself to within about `3e-8 rate^2 / (4 pi^2 f)` Hz, which at
     * 176.4 kHz is 0.16 Hz at D3 (1.9 cents) and at 44.1 kHz 0.01 Hz (0.12 cents). The tarab's whole effect is how close a harmonic lands to
     * it, so the bank runs where it is in tune; it also costs a quarter.
     *
     * With [endFadeFrames] over 0 (a one-shot: [TARAB_END_FADE_SECONDS]) the bank alone is faded out over that many frames at the end, by a
     * raised cosine, so the cut does not click; the string under it is not touched.
     *
     * At [wash] 0 or under (and for a NaN) it returns [conditioned] itself, the same array: WASH 0 is exactly no tarab, and every CELLO and ERHU
     * render passes through here at 0 untouched. [conditioned] is never changed.
     */
    internal fun withTarab(conditioned: FloatArray, wash: Float, endFadeFrames: Int = 0): FloatArray {
        if (!(wash > 0f)) return conditioned
        val ceiling = (conditioned.size + RATE).toFloat() / RATE
        val rung = Strings.bodyRing(conditioned, tarabModes(wash), washAmount(wash), RATE, ceiling)
        val out = if (rung.size == conditioned.size) rung else rung.copyOf(conditioned.size)
        val fade = min(endFadeFrames, out.size)
        val from = out.size - fade
        for (k in 0 until fade) {
            val i = from + k
            val w = (0.5 * (1.0 + cos(PI * (k + 1) / fade))).toFloat()
            out[i] = conditioned[i] + (out[i] - conditioned[i]) * w
        }
        return out
    }

    /** [TARAB_END_FADE_SECONDS] in frames at [RATE]. */
    internal val TARAB_END_FADE_FRAMES: Int get() = Math.round(TARAB_END_FADE_SECONDS * RATE)

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
    internal fun finished(raw: FloatArray, voice: ArcoVoice, body: Float, rate: Int, wash: Float = 0f): FloatArray =
        finishedMeasured(raw, voice, body, rate, wash).samples

    /**
     * [finished] and what the lift did ([Lift]): the same function, so a test reads the engine's own numbers. [wash] is SARANGI's tarab
     * ([withTarab]), after the box and before the level; at 0, the default and every CELLO and ERHU note, it is not there at all.
     */
    internal fun finishedMeasured(raw: FloatArray, voice: ArcoVoice, body: Float, rate: Int, wash: Float = 0f): Finished {
        val conditioned = withTarab(conditionedWithBody(raw, voice, body, rate), wash, TARAB_END_FADE_FRAMES)
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

    // ---- BUZZ: the chien, by analogy --------------------------------------------

    /**
     * BUZZ's default: off, and exactly off. At 0 [buzzed] hands back the very buffer it was given, so a render at BUZZ 0 (or a patch saved
     * before BUZZ existed, which [settled] fills with 0) is the code before BUZZ, sample for sample: every preset, the kit and
     * ArcoFrozenR1cTest's copy are untouched (ArcoBuzzTest holds the presets' and the defaults' SHA-256 from before the change).
     */
    const val DEFAULT_BUZZ = 0f

    /**
     * How BUZZ makes the string rattle: a loose bridge foot knocking the soundboard whenever the string swings past a level, the
     * hurdy-gurdy's chien by analogy (the design's first sixth-slot candidate; the research pass, `2026-11-04-arco-research.md` section 4).
     * The shape is [Terra]'s contact buzz (and `Tines.kalimba`'s): only the part of the wave above a threshold rattles, and that excess is
     * multiplied by noise and added back. Two things are ARCO's own.
     *  - The threshold is referenced to the render's own level: the raw string's RMS while the bow is on it ([bowOnLevel]), or over the
     *    kept loop of a LOOP. Never an absolute number on an un-normalised buffer (TERRA's 0.12), so a quiet note and a loud one buzz alike.
     *  - It falls as the buzz's drive rises, from [BUZZ_THRESHOLD_FROM] times that RMS at no drive to [BUZZ_THRESHOLD_TO] at a drive of 1, while
     *    the noise's gain rises as the drive times [BUZZ_GAIN]: low on the knob only the peaks of the wave rattle, a sizzle at the slip; higher,
     *    more of the wave does. Both move the same way, so the buzz's energy rises at every step (ArcoBuzzTest).
     *  - The knob is not the drive: the drive is [BUZZ_TOP] times BUZZ to the power [BUZZ_CURVE] ([buzzDrive]), so the knob's whole travel
     *    is the first [BUZZ_TOP] of the law's (the recalibration below).
     *
     * The law was chosen by measurement against the other the research named, a fixed threshold at the RMS with TERRA's gain of 0.45
     * (ArcoBuzzTest's law comparison prints them; the design record's "R2: BUZZ, as built" has the tables). Measured on the one-shot, as the
     * RMS of the change over the note's (the house's "every macro changes the sound" measure), at CELLO C2 and C3 and ERHU D4 and C5: at a
     * drive of 0.25 and 0.9 TERRA's law moves a note by only 0.007 to 0.011 and 0.026 to 0.038, so the gain is ARCO's own, 3.0; and the falling
     * law keeps the bottom of the knob to the peaks of the wave, a burst at the slip once a period (the chien's rhythm, by analogy).
     *
     * **Recalibrated after the owner heard it (2026-11-04).** The first build ran the knob straight onto the drive (BUZZ 1 was a drive of 1,
     * moving a note by 0.305 to 0.438). The owner played CELLO's root and ERHU's middle at BUZZ 0, 0.25, 0.5 and 1 and wrote "For Buzz we need
     * to recalibrate. 40 is probably the top of the range." So BUZZ 1 is now that build's 0.4, the same threshold (1.1 times the RMS) and gain
     * (1.2) to the bit. A straight 0.4 scale would put BUZZ 0.25 at the old 0.1, which moves ERHU D4 by 0.010, a third of the low-end proxy bar
     * (0.03), so the map bends: `drive = 0.4 * BUZZ^0.3` puts BUZZ 0.25 at a drive of 0.264 (0.035 to 0.056 over the four notes), 0.5 at
     * 0.325, 0.75 at 0.367 and 1 at 0.4 (0.066 to 0.100). Listening values: the owner's ear set the top; the curve is set by the proxy.
     *
     * Not the real thing, said plainly: a chien knocks once a period, in step with the string, and can period-double (Padilla and Gibiat,
     * Forum Acusticum 2005). This is hiss shaped by the wave: bounded, one pass over the buffer, and able to repeat with a loop. The
     * period-locked knock is WHEEL's research, in R3.
     */
    const val BUZZ_THRESHOLD_FROM = 1.5f
    const val BUZZ_THRESHOLD_TO = 0.5f
    const val BUZZ_GAIN = 3.0f

    /** The drive at BUZZ 1: the first build's 0.4, the top of the range the owner heard (see the recalibration above). */
    const val BUZZ_TOP = 0.4f

    /** How the knob bends onto the drive: under 1, so the low end still moves a note audibly (BUZZ 0.25 is a drive of 0.264). */
    const val BUZZ_CURVE = 0.3f

    /**
     * A threshold law: [thresholdFrom] and [thresholdTo] times the reference RMS at a drive of 0 and 1, the noise's gain [gain] times the drive,
     * the drive [top] times BUZZ to the power [curve] ([buzzDrive]), and whether a LOOP reads its noise from a table one loop long ([loopTable];
     * false is the control that runs the noise free, which does not close). The engine plays [BUZZ_LAW]; [BUZZ_LAW_V1] is the first build's (the
     * knob straight onto the drive), kept so a test holds BUZZ 1 to its 0.4; the others exist for the tests that measure against it.
     */
    internal class BuzzLaw(
        val thresholdFrom: Float, val thresholdTo: Float, val gain: Float, val loopTable: Boolean = true,
        val top: Float = 1f, val curve: Float = 1f,
    )

    internal val BUZZ_LAW = BuzzLaw(BUZZ_THRESHOLD_FROM, BUZZ_THRESHOLD_TO, BUZZ_GAIN, top = BUZZ_TOP, curve = BUZZ_CURVE)

    /** The first build's law, the knob straight onto the drive: its 0.4 is [BUZZ_LAW]'s 1, to the bit. */
    internal val BUZZ_LAW_V1 = BuzzLaw(BUZZ_THRESHOLD_FROM, BUZZ_THRESHOLD_TO, BUZZ_GAIN)

    /** The drive at [buzz]: [BuzzLaw.top] times [buzz] to the [BuzzLaw.curve] (the knob itself for a curve of 1 and a top of 1). */
    internal fun buzzDrive(buzz: Float, law: BuzzLaw = BUZZ_LAW): Float = law.top * (if (law.curve == 1f) buzz else buzz.pow(law.curve))

    /** The threshold at [buzz], as a multiple of the reference RMS. */
    internal fun buzzThreshold(buzz: Float, law: BuzzLaw = BUZZ_LAW): Float = Dsp.lin(buzzDrive(buzz, law), law.thresholdFrom, law.thresholdTo)

    /** The buzz's noise seed for [voice]: fixed, so a render is a pure function of its macros. */
    internal fun buzzSeed(voice: ArcoVoice): Int = Dsp.seedFor("ARCO", "BUZZ", voice.name)

    /** The RMS of [raw] from [from] up to [until] (clamped to the buffer), in Double. */
    internal fun rmsOf(raw: FloatArray, from: Int, until: Int): Float {
        val a = from.coerceIn(0, raw.size)
        val b = until.coerceIn(a, raw.size)
        if (b <= a) return 0f
        var acc = 0.0
        for (i in a until b) acc += raw[i].toDouble() * raw[i]
        return sqrt(acc / (b - a)).toFloat()
    }

    /** A one-shot's reference level: the raw string's RMS while the bow is on it (the stroke included, the ramp and the stop not). */
    internal fun bowOnLevel(raw: FloatArray, voice: ArcoVoice, hz: Float, macros: Map<String, Float>, rate: Int = RATE * Dsp.OVERSAMPLE): Float =
        rmsOf(raw, 0, gateFor(voice, hz, macros, rate, null).holdN)

    /**
     * [raw] with the chien at [buzz]: every sample whose magnitude is over [buzzThreshold] times [level] gets that excess times a noise
     * sample times [BuzzLaw.gain] times the drive ([buzzDrive]) added to it. At [buzz] 0 or under (and for a NaN) it returns [raw] itself, the same array, not a
     * copy and not a pass at zero gain: that is what keeps BUZZ 0 the code before BUZZ to the bit. Otherwise [raw] is never changed.
     *
     * The noise is [Dsp.Noise] from [buzzSeed]. With [period] 0 it runs free, one draw per sample (a one-shot). With [period] over 0 it is a
     * table of [period] draws read at `(i - phase) mod period`, so the buzz repeats exactly every [period] samples from anywhere in the
     * buffer: a LOOP passes its loop's raw length and the raw sample its kept window starts at, and the stretch stays as periodic with the
     * loop as the string under it is (the box, the band limit, the decimator and the high-pass that follow are linear and settle in the
     * warm-up). The excess is never more than the sample itself, so the result is at most `1 + gain * drive` times the string's own peak.
     */
    internal fun buzzed(
        raw: FloatArray, voice: ArcoVoice, buzz: Float, level: Float,
        period: Int = 0, phase: Int = 0, law: BuzzLaw = BUZZ_LAW,
    ): FloatArray {
        if (!(buzz > 0f)) return raw
        val threshold = buzzThreshold(buzz, law) * level
        val gain = law.gain * buzzDrive(buzz, law)
        val noise = Dsp.Noise(buzzSeed(voice))
        val table = if (period > 0) FloatArray(period) { noise.next() } else null
        val out = raw.copyOf()
        for (i in out.indices) {
            val n = if (table != null) table[Math.floorMod(i - phase, period)] else noise.next()
            val a = abs(out[i])
            if (a > threshold) out[i] += (a - threshold) * n * gain
        }
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
     *
     * BUZZ is not rolled: it is handed back as the seed has it (0 around a factory preset or at temperature 1, [near]'s own otherwise), and it
     * is kept out of [Dsp.scrambleNear]'s map altogether, because that map draws one number per key in order: with BUZZ in it every roll after
     * the first would have drawn different numbers for the other five knobs than it did before BUZZ existed. ArcoBuzzTest holds the five
     * knobs' draws to a frozen copy of this function as it was.
     */
    fun scramble(voice: ArcoVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = when {
            near != null -> base + near.macros.filterKeys { it in base }
            temperature >= 1f -> base
            else -> base + ArcoPresets.forVoice(voice).random(random).macros.filterKeys { it in base }
        }
        val rolled = Dsp.scrambleNear(seed - "BUZZ", temperature, random).toMutableMap()
        rolled["HOLD"] = rolled.getValue("HOLD").coerceAtMost(SCRAMBLE_HOLD_CEILING)
        rolled["BUZZ"] = seed.getValue("BUZZ").coerceIn(0f, 1f)
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

    /** The frames a LOOP's stretch is warmed up for before the kept loop begins: [LOOP_WARMUP_SECONDS] or [LOOP_WARMUP_PERIODS] periods of [hz], the longer. A held key ([renderHeldMeasured]) warms up the same, once or twice over. */
    internal fun warmFramesFor(hz: Float): Int = Math.round(max(LOOP_WARMUP_SECONDS, LOOP_WARMUP_PERIODS / hz) * RATE)

    /** A stretch after [retuned]: the raw string, the pitch that was played to get it, and how many corrections it took (0 when the planned pitch already closed). */
    internal class Retuned(val raw: FloatArray, val tunedHz: Double, val passes: Int)

    /**
     * BORE's route to a loop that closes by period, the one place it runs: [render] the stretch at [target], measure the *played* length of the
     * plan's whole periods after [warm] frames ([Bore.measureLoopSamples]), and render again at `tuned * ratio`, up to [LOOP_PASSES] times, until
     * the ratio is within [LOOP_CONVERGED] of 1. A LOOP ([renderLoopMeasured]) and a held key ([renderHeldMeasured]) both run it, each with its
     * own stretch, so the two cannot retune differently.
     */
    private fun retuned(target: Float, plan: Bore.LoopPlan, warm: Int, render: (Float) -> FloatArray): Retuned {
        val over = Dsp.OVERSAMPLE
        val wanted = plan.frames.toDouble() * over
        var tuned = target.toDouble()
        var raw = render(tuned.toFloat())
        var passes = 0
        for (pass in 1..LOOP_PASSES) {
            val ratio = Bore.measureLoopSamples(raw, warm * over, wanted, plan.periods) / wanted
            if (abs(ratio - 1.0) < LOOP_CONVERGED) break
            tuned *= ratio
            raw = render(tuned.toFloat())
            passes = pass
        }
        return Retuned(raw, tuned, passes)
    }

    /** The steady stretch: a short stroke, then constant velocity and pressure to the end. No bite, no vibrato (the LOOP adds its own after the retune, [loopVibrato]), no stop. */
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

    // ---- the LOOP's vibrato (R2) ------------------------------------------------

    /**
     * How a LOOP's vibrato moves. R1's LOOP had none (the organ's rule: the one-shot's vibrato does not repeat, so a loop could not carry it).
     * The owner heard ENDLESS DRAW and ENDLESS CRY played three times over on 2026-11-04 and wrote "Sounds a bit cheap, try the vibrato fix.
     * Mechanical sounding." So R2 puts one in, built to repeat with the loop: every movement in it is a whole number of cycles per loop, so
     * the delay it reads through returns to where it started at the wrap and the loop closes as the bare string does (the research pass's
     * Python stand-in: a seam of 1e-24 fitted, 4.9e-2 at the free 6.1 Hz).
     *  - [OFF]: R1's loop, sample for sample: the frozen tests' reference and the A/B's "old".
     *  - [SNAPPED]: the plain sine of [VIBRATO_PLAIN] at the rate nearest [VIBRATO_HZ] that fits a whole number of cycles in the loop
     *    ([loopVibratoCycles]: 12 in a two second loop, 5.99 Hz), at full depth. Kept for the A/B only.
     *  - [HUMAN]: the shipped one. [VIBRATO_HUMAN]'s amounts (the rate drifting by up to 7 percent, the depth by up to 18, the swing leaning
     *    by 6, swelling in over half a second), with every drift a sum of sines at 1, 2 and 3 cycles per loop (rate) and 1, 2 and 4 (depth)
     *    in place of the one-shot's 0.21 to 1.37 Hz, which do not fit a two second loop. Regular to the loop, not to the vibrato's own cycle:
     *    no two of a loop's twelve swings are alike.
     */
    internal enum class LoopVibrato { OFF, SNAPPED, HUMAN }

    /** What a LOOP plays: [LoopVibrato.HUMAN], which closes at every loop R1b's grid holds (ArcoLoopVibratoTest). */
    internal val LOOP_VIBRATO = LoopVibrato.HUMAN

    /**
     * The LOOP's depth: [VIBRATO_MAX_CENTS], the one-shot's full depth. The one-shot scales its vibrato by how long the bow is on the string
     * ([vibratoCentsFor]: none at 0.6 s or less, all of it from 1.5 s), and a LOOP is a note held for good, so it is at the top of that scale.
     */
    internal const val LOOP_VIBRATO_CENTS = VIBRATO_MAX_CENTS

    /** How many vibrato cycles a loop of [loopSeconds] holds: the whole number nearest [VIBRATO_HZ] times its length, at least one. */
    internal fun loopVibratoCycles(loopSeconds: Double): Int = max(1, Math.round(VIBRATO_HZ * loopSeconds).toInt())

    /** The LOOP's vibrato rate for a loop of [loopSeconds]: [loopVibratoCycles] over the loop, so the swing fits it exactly (5.99 Hz for a 2.003 s loop). */
    internal fun loopVibratoHz(loopSeconds: Double): Double = loopVibratoCycles(loopSeconds) / loopSeconds

    /** The rate drift's and the depth drift's frequencies, in cycles per loop: the lowest that fit, as the one-shot's are the slowest it has. */
    private val LOOP_RATE_WANDER_CYCLES = intArrayOf(1, 2, 3)
    private val LOOP_DEPTH_WANDER_CYCLES = intArrayOf(1, 2, 4)

    /**
     * The unit swing of a LOOP's vibrato, [u] seconds after the vibrato began, for a loop of [loopSeconds]: [vibratoUnit]'s form with every
     * frequency in it a whole number of cycles per loop, so `loopVibratoUnit(u + loopSeconds) == loopVibratoUnit(u)` (ArcoLoopVibratoTest holds
     * it to 1e-9). [LoopVibrato.SNAPPED] is a plain sine; [LoopVibrato.HUMAN] carries [VIBRATO_HUMAN]'s wander, depth drift and skew, the
     * rate's drift entering as a phase (its integral, which returns to zero each loop because each of its sines is whole cycles of it).
     */
    internal fun loopVibratoUnit(u: Double, loopSeconds: Double, kind: LoopVibrato): Double {
        val rate = loopVibratoHz(loopSeconds)
        if (kind != LoopVibrato.HUMAN) return sin(2.0 * PI * rate * u)
        val shape = VIBRATO_HUMAN
        var drift = 0.0
        for (k in LOOP_RATE_WANDER_CYCLES.indices) {
            val w = 2.0 * PI * LOOP_RATE_WANDER_CYCLES[k] / loopSeconds
            drift += RATE_WANDER_WEIGHT[k] * (cos(RATE_WANDER_PHASE[k]) - cos(w * u + RATE_WANDER_PHASE[k])) / w
        }
        val phase = u + shape.rateWander * drift
        var s = 0.0
        for (k in LOOP_DEPTH_WANDER_CYCLES.indices) {
            s += DEPTH_WANDER_WEIGHT[k] * sin(2.0 * PI * LOOP_DEPTH_WANDER_CYCLES[k] / loopSeconds * u + DEPTH_WANDER_PHASE[k])
        }
        val g = 1.0 + shape.depthWander * s
        val angle = 2.0 * PI * rate * phase
        return g * (sin(angle) + shape.skew * sin(2.0 * angle + SKEW_PHASE))
    }

    /**
     * [raw], a LOOP's converged stretch at the oversampled rate, read back through the LOOP's vibrato ([kind]) for a loop of [loopFrames]
     * frames: the one-shot's tape vibrato ([vibrato]: copied exactly until [VIBRATO_DELAY_SECONDS], then rising in) with [loopVibratoUnit] in
     * place of [vibratoUnit], at [LOOP_VIBRATO_CENTS] at the loop's own rate. The rise is over long before the warm-up ends, so across the
     * kept stretch the delay is periodic with the loop and the stretch stays as periodic as the string under it: the seam is the string's.
     * The read never moves the stretch on or back over a whole loop, so the loop keeps its whole periods and its mean pitch. [LoopVibrato.OFF]
     * hands [raw] back untouched.
     */
    internal fun loopVibrato(raw: FloatArray, voice: ArcoVoice, loopFrames: Int, kind: LoopVibrato = LOOP_VIBRATO, rate: Int = RATE * Dsp.OVERSAMPLE): FloatArray {
        if (kind == LoopVibrato.OFF) return raw
        val loopSeconds = loopFrames.toDouble() / RATE
        val hz = loopVibratoHz(loopSeconds)
        val depth = (2.0.pow(LOOP_VIBRATO_CENTS / 1200.0) - 1.0) / (2.0 * PI * hz) * rate
        val riseSeconds = if (kind == LoopVibrato.HUMAN) VIBRATO_HUMAN.riseSeconds else VIBRATO_PLAIN.riseSeconds
        val out = FloatArray(raw.size)
        val last = raw.size - 1
        for (i in raw.indices) {
            val t = i.toDouble() / rate
            if (t <= VIBRATO_DELAY_SECONDS) {
                out[i] = raw[i]
                continue
            }
            val u = t - VIBRATO_DELAY_SECONDS
            val rise = (u / riseSeconds).coerceAtMost(1.0)
            out[i] = cubicAt(raw, i - depth * rise * loopVibratoUnit(u, loopSeconds, kind), last)
        }
        return out
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
     *
     * BUZZ (R2) goes on the converged stretch, after the last pass and never into the pitch estimator, with its noise read from a table one
     * loop long that starts where the kept window starts ([buzzed]), and its threshold referenced to the RMS of that kept window: the buzz
     * repeats with the loop, so the seam is the string's own. At BUZZ 0 the stretch is handed on untouched. [law] is for tests.
     *
     * The vibrato (R2, [loopVibrato]) goes on the converged stretch too, before BUZZ (the order a one-shot has: the string, its vibrato, the
     * buzz, the box), and also never into the estimator: the retune measures the bare string's periods and the vibrato, fitted to the loop,
     * keeps them. [loopVibrato] [LoopVibrato.OFF] is R1's loop exactly, the frozen tests' reference.
     *
     * SARANGI's tarab ([withTarab]) rings on the conditioned stretch after the box, as in a one-shot. It is linear and fixed, so it settles in the
     * warm-up as the box does and the stretch stays as periodic as the string under it.
     */
    internal fun renderLoopMeasured(
        voice: ArcoVoice, macros: Map<String, Float>, law: BuzzLaw = BUZZ_LAW, loopVibrato: LoopVibrato = LOOP_VIBRATO,
    ): LoopRender {
        val m = settled(macros, voice)
        val target = frequencyFor(voice, m.getValue("TUNE"))
        val plan = planLoop(target)
        val over = Dsp.OVERSAMPLE
        val rate = RATE * over
        val warm = warmFramesFor(target)
        val raw = retuned(target, plan, warm) { hz -> stretch(voice, m, hz, warm, plan.frames) }.raw
        val loopRaw = plan.frames * over
        val sung = loopVibrato(raw, voice, plan.frames, loopVibrato, rate)
        val buzz = m.getValue("BUZZ")
        val strung = if (!(buzz > 0f)) sung else buzzed(
            sung, voice, buzz, rmsOf(sung, warm * over, warm * over + loopRaw),
            period = if (law.loopTable) loopRaw else 0, phase = warm * over, law = law,
        )
        val body = bodyOf(voice, m)
        return loopFrom(conditionedFor(strung, voice, body, washOf(voice, m), rate), voice, body, warm, plan.frames)
    }

    /** The loop, refused by name if its corner cannot close: a click shipped silently is the worse failure. */
    internal fun renderLoop(voice: ArcoVoice, macros: Map<String, Float>, law: BuzzLaw = BUZZ_LAW, loopVibrato: LoopVibrato = LOOP_VIBRATO): FloatArray {
        val r = renderLoopMeasured(voice, macros, law, loopVibrato)
        require(r.seam < Keys.MAX_SEAM_ERROR) {
            "ARCO $voice ${midiFor(voice, settled(macros, voice).getValue("TUNE"))}: the loop does not close (seam %.2e, bar %.0e)"
                .format(java.util.Locale.ROOT, r.seam, Keys.MAX_SEAM_ERROR)
        }
        return r.loop
    }

    // ---- the held key (R2) ------------------------------------------------------

    /**
     * A held key's stretch: the one-shot's own stroke on a gate that never closes. [play] runs the stroke a one-shot runs (BOW's attack,
     * and above [OVERSHOOT_FROM] its bite in the velocity and the pressure), then holds the bow on the string to the end: the gate's bow-on
     * length is the whole stretch, so the release ramp, the lift and the stop are never reached, and there is no vibrato here (the key adds
     * the LOOP's own after the retune, [renderHeldMeasured]). The attack's length is worked out as [gateFor] works it out, capped at
     * [ATTACK_HOLD_FRACTION] of the bow-on time, which on a stretch this long never binds. The length is the LOOP's, [stretch]'s, so the
     * retune measures the same span. At BOW 0.5 and under there is no bite and the stroke is the LOOP's fixed 63 ms at BOW 0.5 exactly, so
     * there this is [stretch], sample for sample (ArcoKeysTest holds that); BOW elsewhere changes only the first few hundred milliseconds.
     */
    internal fun heldStretch(voice: ArcoVoice, macros: Map<String, Float>, tuned: Float, warmFrames: Int, frames: Int): FloatArray {
        val rate = RATE * Dsp.OVERSAMPLE
        val total = (warmFrames + frames + LOOP_PAD_FRAMES) * Dsp.OVERSAMPLE
        val attackSec = min(attackSeconds(macros.getValue("BOW")), ATTACK_HOLD_FRACTION * total.toFloat() / rate)
        val attackN = (attackSec * rate).toInt().coerceAtLeast(1)
        return play(voice, tuned, macros, rate, Gate(total, attackN, 0, 0, steady = false), null, null, null, null, false, null, null)
    }

    /**
     * How far past the warm-up and the seam's own window a held key's loop may start, in frames: the loop start is the best zero crossing
     * ([Siren.bestCut]) in this many frames, so the file ends at most this far past one loop after the warm-up, still 768 frames inside the
     * stretch's [LOOP_PAD_FRAMES] (the decimator's edge never reaches it). It holds one and a half periods at C2 and twenty at A5.
     */
    private const val HELD_CUT_SEARCH_FRAMES = 1024

    /**
     * A held key, rendered and measured: [samples] is the file (the stroke, the warm-up, then exactly one loop of [loopFrames] frames, the
     * last frame the one before the wrap), [loopStart] the frame the loop starts at, [seam] the file's own [Keys.seamError] at that start,
     * [warmFrames] the warm-up it was cut after, [tunedHz] and [passes] what the retune did, [lift] what BODY above the knee did, and
     * [limitDb] how far [Dsp.limitPeak] turned the levelled file down to hold 0.99 (0 when it did not have to).
     */
    internal class HeldRender(
        val samples: FloatArray, val loopStart: Int, val loopFrames: Int, val seam: Double, val warmFrames: Int,
        val tunedHz: Double, val passes: Int, val lift: Lift, val limitDb: Float,
    )

    /** The peak a held key is held to after it is levelled ([Keys.resinPad]'s ending): [Dsp.levelTo]'s own ceiling, one number. */
    internal const val HELD_PEAK_CEILING = LEVEL_CEILING

    /**
     * A held key at [macros]' TUNE (the caller sets TUNE to the key), rendered as one file: the stroke a one-shot plays, then the LOOP's held
     * bow, cut where it repeats. Every step after the stroke is the LOOP's ([renderLoopMeasured]), in its order, so the held key's loop
     * carries what a shipped LOOP carries:
     *  - the stretch is [heldStretch], retuned by [retuned] (BORE's route: the played period measured after the warm-up, the whole stretch
     *    rendered again at the corrected pitch until the loop's whole periods fill its whole frames to 3e-7), so the attack plays at the
     *    corrected pitch too, a fraction of a cent from the key;
     *  - then the LOOP's vibrato ([loopVibrato], [LOOP_VIBRATO], fitted to whole cycles per loop: it swells in from [VIBRATO_DELAY_SECONDS]
     *    after the stroke begins, as a one-shot's does, and repeats with the loop after it), then BUZZ with its noise tabled one loop long
     *    from the warm-up's end and its threshold on the RMS of the loop after the warm-up ([buzzed]), then the box and the output chain
     *    ([conditionedWithBody]), all as the LOOP has them;
     *  - the loop starts at the best zero crossing ([Siren.bestCut]) within [HELD_CUT_SEARCH_FRAMES] frames after the warm-up and the seam's
     *    256-frame window, and the file ends exactly one loop later, so the wrap joins the loop's last frame to its first. Never a crossfade;
     *  - the file is levelled on its loop region, not on the attack ([Keys.resinPad]'s rule: the loop sets the level, a held key is loud
     *    where it is held), to [Dsp.MELODIC_LOUDNESS_TARGET] by [Loudness.of], then [Dsp.limitPeak] holds it to [HELD_PEAK_CEILING]. Above
     *    [BODY_KNEE] the lift goes on the whole conditioned stretch at the plain's gain, capped as the LOOP's is (its peak at most
     *    [PEAK_CAP], or the plain's own if that is higher), the cut chosen on the lifted file, so nothing levels the lift away.
     *
     * [warmScale] multiplies the warm-up (the one retry, at twice it, is the caller's: `ArcoKeys`). [law] and [loopVibrato] are for tests, as
     * on [renderLoopMeasured]. [cancelled] is asked before every pass of the retune and before the finish; a pass is a few hundred milliseconds.
     * The seam is measured, not required: the caller refuses it by name.
     */
    internal fun renderHeldMeasured(
        voice: ArcoVoice, macros: Map<String, Float>, warmScale: Int = 1, law: BuzzLaw = BUZZ_LAW, loopVibrato: LoopVibrato = LOOP_VIBRATO,
        cancelled: () -> Boolean = { false },
    ): HeldRender {
        require(warmScale >= 1) { "the warm-up is at least the LOOP's, got $warmScale times it" }
        fun stopIfCancelled() {
            if (cancelled()) throw java.util.concurrent.CancellationException("ARCO held key cancelled")
        }
        val m = settled(macros, voice)
        val target = frequencyFor(voice, m.getValue("TUNE"))
        val plan = planLoop(target)
        val over = Dsp.OVERSAMPLE
        val rate = RATE * over
        val warm = warmScale * warmFramesFor(target)
        val r = retuned(target, plan, warm) { hz -> stopIfCancelled(); heldStretch(voice, m, hz, warm, plan.frames) }
        stopIfCancelled()
        val loopRaw = plan.frames * over
        val sung = loopVibrato(r.raw, voice, plan.frames, loopVibrato, rate)
        val buzz = m.getValue("BUZZ")
        val strung = if (!(buzz > 0f)) sung else buzzed(
            sung, voice, buzz, rmsOf(sung, warm * over, warm * over + loopRaw),
            period = if (law.loopTable) loopRaw else 0, phase = warm * over, law = law,
        )
        val body = bodyOf(voice, m)
        val conditioned = conditionedFor(strung, voice, body, washOf(voice, m), rate)
        stopIfCancelled()
        return heldFrom(conditioned, voice, body, warm, plan.frames, r)
    }

    /**
     * The finish of a held key from its conditioned stretch: where its loop starts, its level, BODY's lift above the knee, the peak, and the
     * seam read on the file that ships. See [renderHeldMeasured].
     */
    private fun heldFrom(conditioned: FloatArray, voice: ArcoVoice, body: Float, warm: Int, frames: Int, r: Retuned): HeldRender {
        val base = warm + SEAM_FRAMES
        fun loopStartIn(buf: FloatArray): Int =
            base + Siren.bestCut(buf.copyOfRange(base, base + HELD_CUT_SEARCH_FRAMES), HELD_CUT_SEARCH_FRAMES)

        // buf up to one loop past its own cut, times gain: a file, and where its loop starts.
        fun fileOf(buf: FloatArray, gain: Float): Pair<FloatArray, Int> {
            val start = loopStartIn(buf)
            val file = buf.copyOf(start + frames)
            for (i in file.indices) file[i] *= gain
            return file to start
        }

        val plainStart = loopStartIn(conditioned)
        val loud = Loudness.of(Snip(conditioned.copyOfRange(plainStart, plainStart + frames), channels = 1, sampleRate = RATE))
        val gain = if (loud > LEVEL_SILENCE) Dsp.MELODIC_LOUDNESS_TARGET / loud else 1f
        var (file, start) = fileOf(conditioned, gain)
        var lift = Lift.NONE
        if (body > BODY_KNEE) {
            val limit = max(PEAK_CAP, peakOf(file))
            lift = liftPlan(liftDbFor(body), limit) { db -> peakOf(fileOf(lifted(conditioned, voice, db), gain).first) }
            if (lift.delivered > 0f) {
                val shipped = fileOf(lifted(conditioned, voice, lift.delivered), gain)
                file = shipped.first
                start = shipped.second
            }
        }
        val before = peakOf(file)
        Dsp.limitPeak(file, HELD_PEAK_CEILING)
        val after = peakOf(file)
        val limitDb = if (after < before && before > 0f) (20.0 * log10(after.toDouble() / before)).toFloat() else 0f
        return HeldRender(file, start, frames, Keys.seamError(file, start), warm, r.tunedHz, r.passes, lift, limitDb)
    }

    // ---- the render -----------------------------------------------------------

    /**
     * One note: [renderLoop] at HOLD's top step, else the bow at the note's exact pitch finished by [finished], the one place BODY, the level and the lift are worked out. The render is a pure function of [macros] and its
     * length is the string's own at every BODY (the box is cut to it and the lift runs on a buffer of the same length), so [renderFrames] and [drumClassFor] stay closed form.
     */
    fun render(voice: ArcoVoice, macros: Map<String, Float> = emptyMap()): Snip = render(voice, macros, BUZZ_LAW)

    /**
     * [render] with BUZZ played by [law] and a LOOP's vibrato by [loopVibrato]: tests measure the engine's choices against others this way.
     * A one-shot's buzz goes on the bowed string after the vibrato and before the box, its noise running free, its threshold referenced to
     * [bowOnLevel]; at BUZZ 0 the string goes on untouched. [loopVibrato] touches only a LOOP.
     */
    internal fun render(voice: ArcoVoice, macros: Map<String, Float>, law: BuzzLaw, loopVibrato: LoopVibrato = LOOP_VIBRATO): Snip {
        val m = settled(macros, voice)
        if (isLoop(m.getValue("HOLD"))) return Snip(renderLoop(voice, m, law, loopVibrato), channels = 1, sampleRate = RATE)
        val hz = frequencyFor(voice, m.getValue("TUNE"))
        val rate = RATE * Dsp.OVERSAMPLE
        val raw = bow(voice, hz, m, rate)
        val buzz = m.getValue("BUZZ")
        val strung = if (!(buzz > 0f)) raw else buzzed(raw, voice, buzz, bowOnLevel(raw, voice, hz, m, rate), law = law)
        return Snip(finishedFor(strung, voice, m, rate), channels = 1, sampleRate = RATE)
    }

    /** [finished] at the BODY and the WASH [m] (settled) plays at ([bodyOf], [washOf]): a one-shot's finish for any voice, what [render] calls. */
    internal fun finishedFor(raw: FloatArray, voice: ArcoVoice, m: Map<String, Float>, rate: Int): FloatArray =
        finished(raw, voice, bodyOf(voice, m), rate, washOf(voice, m))
}
