package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow

/**
 * ROTARY (saved key `rotary`): the spinning speaker. The characteristic gesture is: the sound plays, then it swirls,
 * the top end circling round the room, its pitch and level rising and falling together, the low end swaying more
 * slowly underneath; and with RAMP, the swirl starts as a slow chorale and spins up to the fast tremolo while the
 * sound is heard.
 *
 * Nothing in the rack makes that. TREMOLO moves the level and VIBRATO the pitch, each at one rate over the whole
 * band; a rotary speaker splits the band at a crossover and moves level and pitch together on the top half, at a
 * rate that is not the bottom half's and that accelerates. This is the pedal board brainstorm's idea 8
 * (`docs/superpowers/specs/2026-10-19-pedal-board-brainstorm.md`), Stage 4b in its order of work.
 *
 * The model is the ORGAN engines' rotor (`otis/OtisOrganSource.kt`'s `Rotor`, with `Tonewheel.kt`'s geometry),
 * copied, not shared: both organs' renders are pinned. A one-pole crossover at [CROSSOVER_HZ] splits the pad into
 * the horn (the top) and the drum (the bottom). Both halves ride one delay line at the same base delay, so they
 * sum back flat when nothing moves; only the modulation differs.
 *
 * - The horn: a source on a circle. It is loudest when it points at the listener, which is also where it is
 *   nearest (the shortest delay), and it moves toward or away fastest a quarter turn later (the pitch at its
 *   highest or lowest). So the delay is base - D cos(angle) and the gain falls from 1 as the angle leaves 0. D at
 *   SPIN 1 is [HORN_DOPPLER_SEC], the horn's radius over the speed of sound (about 18 cm): at the fast speed that
 *   is a pitch swing of about 2 percent, roughly a third of a semitone each way.
 * - The drum: level only, at [BASS_RATE_RATIO] of the horn's speed, so the two drift in and out of step. Its path
 *   has no Doppler, but a low tone's pitch still moves a little (0.9 percent peak to peak at 100 Hz against the
 *   horn's 5.4 at 8 kHz, both measured at SPIN 1, SPEED 1): the two halves rise and fall at different rates, so the
 *   one-pole crossover's phase between them wanders. A real cabinet's crossover leaks the same way, more steeply.
 * - Both start at angle 0, so the first frame plays at full level: a hit's attack is never eaten (as TREMOLO).
 *
 * SPEED sets the speed both rotors run at, from the slow chorale ([SLOW_HZ]) to the fast tremolo ([FAST_HZ]), on a
 * log law. RAMP sets where they start: at 0 they run at SPEED from the first frame; at 1 they start at the chorale
 * and spin up to SPEED with each rotor's own inertia, the horn in about a second ([HORN_INERTIA_SEC]) and the heavier
 * drum more slowly ([BASS_INERTIA_SEC]), which is the sound of a rotary speaker switched to fast. On a pad shorter
 * than a second the spin-up is only a start; a held pad hears all of it.
 *
 * The base delay is taken back out (the section reads that far ahead; it is offline), so the pad's onset lands on
 * the same frame it did: no latency. One rotor for every channel, so a stereo pair swirls together and mono stays
 * mono (a real cabinet's two-microphone width is out of scope, as the pedal spec puts stereo widening). Shape, not
 * loudness: the output is matched to the pad's peak, and at SPIN 0 the pad comes back unchanged. No tail.
 *
 * Sits after TREMOLO and before ECHO: after the amp, where a rotary cabinet sits, and in front of the delay, so the
 * repeats carry the swirl.
 *
 * For held sounds. At its gate (2026-11-13) the owner heard it on ONSET's snare, JANGLE and CHUG (after their amps)
 * and the choir: it sounds like a rotary speaker, it is clearly more than TREMOLO plus VIBRATO at the same speed, the
 * spin-up is heard, it works after CHUG's amp, there are no artifacts, the depth is enough, and it is for held sounds
 * only. They chose Spin-up for the tap (SPIN 1, SPEED 1, RAMP 1), so SPEED and RAMP default to 1, each its own neutral
 * (no AMT moves them, as TREMOLO's RATE), and the ROTARY chip's character, `spun`, sets SPIN 1 alone. SPIN is a depth
 * and fades to none, so a tap (AMT 0.7) swirls at SPIN 0.7 and AMT 1 is the heard clip: a macro tops out at 1, as
 * REWIND's MIX ([RotaryTest] measures how far under the clip the tap's swing lands).
 */
object Rotary {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("SPIN", 0.8f),                    // how much it swirls: none .. the full horn and drum throw
        MacroSpec("SPEED", 1f, neutral = 1f),       // SLOW_HZ .. FAST_HZ (log); the gate's Spin-up, its own neutral, so AMT never moves it
        MacroSpec("RAMP", 1f, neutral = 1f),        // 0 steady at SPEED .. 1 starts at the chorale and spins up; the same
    )

    /** The horn's speed at SPEED 0 (the chorale) and SPEED 1 (the tremolo), turns a second. */
    const val SLOW_HZ = 0.7f
    const val FAST_HZ = 7f

    /** The drum turns a little slower than the horn (the organ rotor's 5.8 against 6.7). */
    const val BASS_RATE_RATIO = 0.87f

    const val CROSSOVER_HZ = 800f

    /** At SPIN 1: the horn's delay swing each way (its radius over the speed of sound). */
    const val HORN_DOPPLER_SEC = 0.00052f

    /** At SPIN 1: how far each rotor's level falls from full when it points away. */
    const val HORN_AM = 0.65f
    const val BASS_AM = 0.4f

    /** How long each rotor takes to cover about two thirds of a change of speed (the organ rotor's). */
    const val HORN_INERTIA_SEC = 0.95f
    const val BASS_INERTIA_SEC = 1.65f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    /** The horn's steady speed in Hz for a SPEED macro. */
    fun hz(speed: Float): Float = Dsp.expMap(speed, SLOW_HZ, FAST_HZ)

    /** The horn's speed at the first frame for a target [hz] and a RAMP macro: from [hz] at 0 to the chorale at 1. */
    fun startHz(hz: Float, ramp: Float): Float =
        (hz * (SLOW_HZ / hz).toDouble().pow(ramp.coerceIn(0f, 1f).toDouble())).toFloat()

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        // A value that is not a number is left at the default: a NaN would otherwise ride through the clamp into the delay.
        for ((k, v) in macros) if (m.containsKey(k) && v.isFinite()) m[k] = v.coerceIn(0f, 1f)
        val spin = m.getValue("SPIN")
        if (spin <= 0f || snip.frameCount == 0) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val rate = snip.sampleRate
        val n = snip.frameCount
        val doppler = spin * HORN_DOPPLER_SEC * rate
        // The base delay keeps the horn's shortest read at least a sample behind the write. It is a whole number of
        // frames, so the drum's read is exact and taking it back out is exact.
        val base = ceil(doppler).toInt() + 2
        val frames = n + base

        // The rotors, once for every channel: the horn's delay and gain, the drum's gain, for each output frame.
        val hornDelay = FloatArray(n)
        val hornGain = FloatArray(n)
        val bassGain = FloatArray(n)
        val targetHz = hz(m.getValue("SPEED")).toDouble()
        var hornHz = startHz(targetHz.toFloat(), m.getValue("RAMP")).toDouble()
        var bassHz = hornHz * BASS_RATE_RATIO
        val bassTarget = targetHz * BASS_RATE_RATIO
        val hornPull = 1.0 - exp(-1.0 / (HORN_INERTIA_SEC * rate))
        val bassPull = 1.0 - exp(-1.0 / (BASS_INERTIA_SEC * rate))
        var hornPhase = 0.0
        var bassPhase = 0.0
        for (f in 0 until n) {
            val horn = cos(2.0 * PI * hornPhase)
            hornDelay[f] = (base - doppler * horn).toFloat()
            hornGain[f] = (1.0 - spin * HORN_AM * 0.5 * (1.0 - horn)).toFloat()
            bassGain[f] = (1.0 - spin * BASS_AM * 0.5 * (1.0 - cos(2.0 * PI * bassPhase))).toFloat()
            // Each phase steps at the speed it has reached, then the speed moves toward the target with its inertia.
            hornPhase += hornHz / rate
            hornPhase -= kotlin.math.floor(hornPhase)
            bassPhase += bassHz / rate
            bassPhase -= kotlin.math.floor(bassPhase)
            hornHz += (targetHz - hornHz) * hornPull
            bassHz += (bassTarget - bassHz) * bassPull
        }

        val split = 1.0 - exp(-2.0 * PI * CROSSOVER_HZ / rate)
        val ch = snip.channels
        val out = FloatArray(snip.samples.size)
        val hornLine = FloatArray(base * 2 + 8)
        val bassLine = FloatArray(hornLine.size)
        for (c in 0 until ch) {
            hornLine.fill(0f)
            bassLine.fill(0f)
            var low = 0.0
            var w = 0
            for (f in 0 until frames) {
                // Past the pad's end the line is fed silence, so the last frames read the pad's own end.
                val x = if (f < n) snip.samples[f * ch + c].toDouble() else 0.0
                low += split * (x - low)
                hornLine[w] = (x - low).toFloat()
                bassLine[w] = low.toFloat()
                val o = f - base
                if (o >= 0) {
                    val horn = Dsp.tap(hornLine, w, hornDelay[o])
                    val bass = bassLine[(w - base + bassLine.size) % bassLine.size]
                    out[o * ch + c] = horn * hornGain[o] + bass * bassGain[o]
                }
                w = (w + 1) % hornLine.size
            }
        }

        // Shape, not loudness: the horn's Doppler can stack a crest a little over the pad's, so match the input's peak.
        val inPeak = snip.peak()
        val outPeak = Snip(out, ch, rate).peak()
        if (inPeak > 0f && outPeak > 1e-9f) {
            val g = inPeak / outPeak
            for (i in out.indices) out[i] = (out[i] * g).coerceIn(-1f, 1f)
        }
        return Snip(out, ch, rate)
    }
}
